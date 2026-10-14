package local.dialer.callrecording

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val ADB_RECORDER = "Llocal/dialer/bridge/AdbAudioRecorder;"
private const val AUDIO_RECORD = "Landroid/media/AudioRecord;"
internal fun Method.audioCalls() = implementation?.instructions?.filterIsInstance<ReferenceInstruction>()
    ?.mapNotNull { it.reference as? MethodReference }?.filter { it.definingClass == AUDIO_RECORD }.orEmpty()

internal fun validateAudioWrapper(definition: ClassDef): Map<String, Method> {
    fun fail(reason: String): Nothing = throw PatchException("ADB audio: $reason")
    if (definition.superclass != "Ljava/lang/Object;" || definition.interfaces.size != 1)
        fail("unexpected recorder hierarchy")
    val fields = definition.instanceFields.toList()
    if (fields.size != 1 || fields.single().type != AUDIO_RECORD ||
        !AccessFlags.PRIVATE.isSet(fields.single().accessFlags) || !AccessFlags.FINAL.isSet(fields.single().accessFlags))
        fail("unexpected recorder field")
    val methods = definition.methods.filter { it.name != "<clinit>" }.toList()
    if (methods.size != 5) fail("unexpected recorder methods")
    val expected = mapOf("<init>" to "V", "startRecording" to "V", "stop" to "V",
        "getRecordingState" to "Z", "read" to "I")
    val selected = expected.mapValues { (call, result) ->
        methods.singleOrNull { method -> method.returnType == result && method.audioCalls().any { it.name == call } }
            ?: fail("missing or ambiguous $call wrapper")
    }
    if (selected.values.toSet().size != 5) fail("overlapping wrappers")
    selected.forEach { (call, method) ->
        val body = method.implementation ?: fail("no $call implementation")
        val parameters = if (call == "read") listOf("[B", "I") else emptyList()
        if (method.parameterTypes != parameters || AccessFlags.STATIC.isSet(method.accessFlags) ||
            body.tryBlocks.isNotEmpty() || body.registerCount > 15 ||
            body.registerCount < parameters.size + 1 + if (call == "<init>") 1 else 0)
            fail("unsupported $call signature or registers")
        val calls = method.audioCalls()
        if (calls.map { it.name }.toSet() != if (call == "stop") setOf("stop", "release") else setOf(call))
            fail("additional audio operations in $call")
        val expectedParameters = when (call) { "<init>" -> List(5) { "I" }; "read" -> listOf("[B", "I", "I", "I"); else -> emptyList() }
        if (calls.any { it.parameterTypes != expectedParameters || it.returnType != if (call in setOf("read", "getRecordingState")) "I" else "V" })
            fail("unsupported AudioRecord overload in $call")
        if (call == "<init>" && method.name != "<init>") fail("expected a constructor")
    }
    val constructor = selected.getValue("<init>")
    if (constructor.audioCalls().single().parameterTypes != List(5) { "I" } ||
        constructor.implementation!!.instructions.filterIsInstance<WideLiteralInstruction>()
            .map { it.wideLiteral }.toList() != listOf(4L))
        fail("expected only the VOICE_CALL source constant")
    val initializer = definition.methods.singleOrNull { it.name == "<clinit>" } ?: fail("no PCM format")
    val constants = initializer.implementation?.instructions?.filterIsInstance<WideLiteralInstruction>()?.map { it.wideLiteral }?.toSet()
    if (constants != setOf(8000L, 16L, 2L)) fail("expected 8000 Hz mono PCM16")
    return selected
}

@Suppress("unused")
val adbCallAudioPatch = bytecodePatch(
    name = "Use ADB call audio (test)",
    description = "Route manual call audio through an explicitly started ADB helper. No root or Shizuku; experimental, device-specific.",
    default = false,
) {
    compatibleWith(*listOf("com.google.android.dialer", "com.google.android.dialer.morphe").map {
        Compatibility(name = if (it.endsWith(".morphe")) "Google Phone (Morphe clone)" else "Google Phone",
            packageName = it, targets = listOf(AppTarget("241.0.991235506", versionCode = 20129558,
                description = "Optional ADB test backend; a running authorized helper is required.")))
    }.toTypedArray())
    extendWith("extensions/adb-audio.dex")
    execute {
        if (packageMetadata.packageName !in setOf("com.google.android.dialer", "com.google.android.dialer.morphe") ||
            packageMetadata.versionName != "241.0.991235506" || packageMetadata.versionCode != "20129558")
            throw PatchException("ADB audio: requires the inspected Google Phone 241 APK")
        val target = Fingerprint(returnType = "V", parameters = emptyList(), custom = { method, definition ->
            method.name == "<init>" && method.audioCalls().any { it.name == "<init>" && it.parameterTypes == List(5) { "I" } } &&
                method.implementation!!.instructions.filterIsInstance<WideLiteralInstruction>().map { it.wideLiteral }.toList() == listOf(4L) &&
                runCatching { validateAudioWrapper(definition) }.isSuccess
        }).matchAllOrNull().orEmpty().singleOrNull()
            ?: throw PatchException("ADB audio: missing or ambiguous VOICE_CALL wrapper")
        val selected = validateAudioWrapper(target.originalClassDef)
        val definition = target.classDef
        val field = definition.instanceFields.single()
        definition.fields.remove(field)
        field.setType(ADB_RECORDER)
        definition.fields.add(field)
        val fieldReference = "${definition.type}->${field.name}:$ADB_RECORDER"
        selected.forEach { (operation, original) ->
            val method = definition.methods.single { it.name == original.name && it.parameterTypes == original.parameterTypes }
            method.removeInstructions(method.implementation!!.instructions.size)
            val instructions = when (operation) {
                "<init>" -> """
                    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                    new-instance v0, $ADB_RECORDER
                    invoke-direct {v0}, $ADB_RECORDER-><init>()V
                    iput-object v0, p0, $fieldReference
                    return-void
                """
                "startRecording" -> """
                    iget-object p0, p0, $fieldReference
                    invoke-virtual {p0}, $ADB_RECORDER->start()V
                    return-void
                """
                "stop" -> """
                    iget-object p0, p0, $fieldReference
                    invoke-virtual {p0}, $ADB_RECORDER->close()V
                    return-void
                """
                "getRecordingState" -> """
                    iget-object p0, p0, $fieldReference
                    invoke-virtual {p0}, $ADB_RECORDER->isRecording()Z
                    move-result p0
                    return p0
                """
                else -> """
                    iget-object p0, p0, $fieldReference
                    invoke-virtual {p0, p1, p2}, $ADB_RECORDER->read([BI)I
                    move-result p0
                    return p0
                """
            }
            method.addInstructions(0, instructions.trimIndent())
        }
    }
}
