package local.dialer.callrecording

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val DROIDGUARD_SERVICE = "com.google.android.gms.droidguard.internal.IDroidGuardService"
internal const val BROKER_SERVICE = "com.google.android.gms.common.internal.IGmsServiceBroker"
internal const val VERIFICATION_DENIED = "PackageVerificationRslt: not allowed:"
internal const val DROIDGUARD_REJECTION_LOG = "Rejected DroidGuard connection; reporting DEVELOPER_ERROR"

/** Report an unavailable service through its existing failure callback; never fabricate a successful token. */
@Suppress("unused")
val droidGuardConnectionPatch = bytecodePatch(
    name = "Handle rejected DroidGuard connection",
    description = "Report a rejected DroidGuard broker connection as DEVELOPER_ERROR instead of crashing a renamed clone. Device testing required.",
    default = false,
) {
    compatibleWith(
        *listOf("com.google.android.dialer", "com.google.android.dialer.morphe").map { packageName ->
            Compatibility(name = if (packageName.endsWith(".morphe")) "Google Phone (Morphe clone)" else "Google Phone",
                packageName = packageName,
                targets = listOf(AppTarget("241.0.991235506", versionCode = 20129558,
                    description = "Experimental DroidGuard connection failure handling; not a call-audio permission fix.")))
        }.toTypedArray(),
    )
    execute {
        if (packageMetadata.packageName !in setOf("com.google.android.dialer", "com.google.android.dialer.morphe") ||
            packageMetadata.versionName != "241.0.991235506" || packageMetadata.versionCode != "20129558") {
            throw PatchException("DroidGuard: this repair requires the inspected Google Phone 241 APK")
        }
        val target = Fingerprint(returnType = "V", filters = listOf(string(BROKER_SERVICE)),
            custom = { method, _ -> "IGmsServiceBroker.getService failed" in method.exactStrings() })
            .matchAllOrNull().orEmpty().singleOrNull()
            ?: throw PatchException("DroidGuard: missing or ambiguous broker method")
        val client = Fingerprint(returnType = "Ljava/lang/String;", parameters = emptyList(),
            filters = listOf(string(DROIDGUARD_SERVICE))).matchAllOrNull().orEmpty().singleOrNull()
            ?: throw PatchException("DroidGuard: missing or ambiguous service client")
        val intermediate = classDefBy(client.originalClassDef.superclass!!)
        if (intermediate.superclass != target.originalClassDef.type ||
            client.originalClassDef.methods.none { "com.google.android.gms.droidguard.service.START" in it.exactStrings() }) {
            throw PatchException("DroidGuard: unexpected client hierarchy")
        }
        insertDroidGuardFailureHandling(target.method, client.originalClassDef.type)
    }
}

internal fun securityHandlerIndex(method: Method): Int {
    val body = method.implementation ?: throw PatchException("DroidGuard: no broker implementation")
    if (AccessFlags.STATIC.isSet(method.accessFlags) || method.returnType != "V" ||
        method.parameterTypes.size != 2 || method.parameterTypes[1] != "Ljava/util/Set;") {
        throw PatchException("DroidGuard: unexpected broker signature")
    }
    val handlers = body.tryBlocks.flatMap { it.exceptionHandlers }
        .filter { it.exceptionType == "Ljava/lang/SecurityException;" }.map { it.handlerCodeAddress }.distinct()
    val address = handlers.singleOrNull() ?: throw PatchException("DroidGuard: missing or ambiguous security handler")
    val instructions = body.instructions.toList()
    var current = 0
    val index = instructions.indices.singleOrNull { i -> (current == address).also { current += instructions[i].codeUnits } }
        ?: throw PatchException("DroidGuard: invalid handler address")
    val move = instructions[index] as? OneRegisterInstruction
    val rethrow = instructions.getOrNull(index + 1) as? OneRegisterInstruction
    if (move?.opcode != Opcode.MOVE_EXCEPTION || rethrow?.opcode != Opcode.THROW ||
        move.registerA != rethrow.registerA || move.registerA > 15 ||
        body.tryBlocks.any { address >= it.startCodeAddress && address < it.startCodeAddress + it.codeUnitCount }) {
        throw PatchException("DroidGuard: expected an isolated move-exception/rethrow handler")
    }
    return index
}

internal fun insertDroidGuardFailureHandling(method: MutableMethod, clientType: String) {
    val handler = securityHandlerIndex(method)
    val body = method.implementation!!
    val exceptionRegister = (body.instructions[handler] as OneRegisterInstruction).registerA
    // All scratch registers are proven local. The original exception remains intact for rethrow.
    val localCount = body.registerCount - 3
    val scratch = (0 until minOf(localCount, 16)).filter { it != exceptionRegister }.take(5)
    if (scratch.size != 5) throw PatchException("DroidGuard: insufficient local registers")
    val (receiver, flag, message, text, generation) = scratch
    val references = body.instructions.filterIsInstance<ReferenceInstruction>().map { it.reference }
    val failure = references.filterIsInstance<MethodReference>().distinctBy { it.toString() }.singleOrNull {
        it.definingClass == method.definingClass && it.returnType == "V" &&
            it.parameterTypes == listOf("I", "Landroid/os/IBinder;", "Landroid/os/Bundle;", "I")
    } ?: throw PatchException("DroidGuard: missing or ambiguous post-init failure callback")
    val epoch = references.filterIsInstance<FieldReference>().distinctBy { it.toString() }.singleOrNull {
        it.definingClass == method.definingClass && it.type == "Ljava/util/concurrent/atomic/AtomicInteger;"
    } ?: throw PatchException("DroidGuard: missing or ambiguous connection generation")
    val originalThrow = body.instructions[handler + 1]
    method.addInstructionsWithLabels(handler + 1, """
        move-object/from16 v$receiver, p0
        instance-of v$flag, v$receiver, $clientType
        if-eqz v$flag, :original_rethrow
        invoke-virtual {v$exceptionRegister}, Ljava/lang/Throwable;->getMessage()Ljava/lang/String;
        move-result-object v$message
        if-eqz v$message, :original_rethrow
        const-string v$text, "$VERIFICATION_DENIED"
        invoke-virtual {v$message, v$text}, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z
        move-result v$flag
        if-eqz v$flag, :original_rethrow
        const-string v$message, "GooglePhone.CloneRepair"
        const-string v$text, "$DROIDGUARD_REJECTION_LOG"
        invoke-static {v$message, v$text}, Landroid/util/Log;->w(Ljava/lang/String;Ljava/lang/String;)I
        iget-object v$generation, v$receiver, $epoch
        invoke-virtual {v$generation}, Ljava/util/concurrent/atomic/AtomicInteger;->get()I
        move-result v$generation
        const/16 v$flag, 0xa
        const/4 v$message, 0x0
        invoke-virtual {v$receiver, v$flag, v$message, v$message, v$generation}, $failure
        return-void
    """.trimIndent(), ExternalLabel("original_rethrow", originalThrow))
}
