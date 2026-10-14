package local.dialer.callrecording

import app.morphe.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal const val COUNTRY_DISABLED = "Call recording is disabled in the current country"
internal const val FEATURE_ENABLED = "Call recording is enabled by call_recording_audio system feature"
internal const val FLAG_DISABLED = "Call recording is disabled by the call recording flag"
internal const val AUDIO_FEATURE = "com.google.android.apps.dialer.call_recording_audio"
internal val GATE_PROVENANCE = setOf(
    "com/android/dialer/callrecording/impl/canrecord/CanRecord", "CanRecord.java", "canRecordCall",
)

internal fun Method.signature() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"
internal fun Instruction.stringValue() = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string
internal fun Method.exactStrings() = implementation?.instructions?.mapNotNull { it.stringValue() }?.toSet().orEmpty()

internal fun selectUniqueTarget(strategies: List<Pair<String, List<String>>>): String {
    strategies.forEach { (strategy, candidates) ->
        if (candidates.size > 1) fail("strategy '$strategy' matched ${candidates.size} methods: ${candidates.joinToString()}")
    }
    val targets = strategies.flatMap { it.second }.distinct()
    if (targets.size != 1) fail(
        if (targets.isEmpty()) "no reliable target; diagnostic strings or gate structure changed"
        else "fallbacks disagree: ${targets.joinToString()}",
    )
    return targets.single()
}

/** Conservative constant propagation over the original gate's control flow. */
internal fun validateManualGate(method: Method) {
    val body = method.implementation ?: fail("target has no implementation")
    if (method.returnType != "Z" || method.parameterTypes.isNotEmpty()) fail("unexpected signature ${method.signature()}")
    val forbiddenFlags = listOf(AccessFlags.STATIC, AccessFlags.ABSTRACT, AccessFlags.NATIVE,
        AccessFlags.SYNCHRONIZED, AccessFlags.DECLARED_SYNCHRONIZED)
    if (forbiddenFlags.any { it.isSet(method.accessFlags) }) fail("expected an ordinary instance method")
    if (body.registerCount < 2) fail("no local register available without overwriting the receiver")
    if (body.tryBlocks.isNotEmpty()) fail("exception regions need a new analysis")
    val instructions = body.instructions.toList()
    if (instructions.isEmpty()) fail("empty gate")
    if (!method.exactStrings().containsAll(GATE_PROVENANCE + AUDIO_FEATURE + FLAG_DISABLED)) fail("missing gate provenance or feature/flag anchors")

    val featureIndex = instructions.indexOfFirst { it.stringValue() == AUDIO_FEATURE }
    val featureRegister = (instructions[featureIndex] as? OneRegisterInstruction)?.registerA
    val featureCall = instructions.getOrNull(featureIndex + 1)
    val reference = (featureCall as? ReferenceInstruction)?.reference as? MethodReference
    if (reference?.definingClass != "Landroid/content/pm/PackageManager;" ||
        reference.name != "hasSystemFeature" || reference.returnType != "Z" ||
        reference.parameterTypes.map { it.toString() } != listOf("Ljava/lang/String;") ||
        featureCall !is FiveRegisterInstruction || featureCall.registerCount != 2 ||
        featureCall.registerD != featureRegister
    ) fail("audio feature is not passed to PackageManager.hasSystemFeature as expected")
    val result = instructions.getOrNull(featureIndex + 2)
    val branch = instructions.getOrNull(featureIndex + 3)
    if (result?.opcode != Opcode.MOVE_RESULT ||
        branch?.opcode !in setOf(Opcode.IF_EQZ, Opcode.IF_NEZ) ||
        (result as? OneRegisterInstruction)?.registerA != (branch as? OneRegisterInstruction)?.registerA
    ) fail("system feature result does not control a boolean branch")

    val addressToIndex = mutableMapOf<Int, Int>()
    val addresses = IntArray(instructions.size)
    var address = 0
    instructions.forEachIndexed { index, instruction ->
        addresses[index] = address
        addressToIndex[address] = index
        address += instruction.codeUnits
    }
    val successors = instructions.mapIndexed { index, instruction ->
        val opcode = instruction.opcode
        if (opcode.name.contains("SWITCH") || opcode.name.contains("PAYLOAD") ||
            opcode.name.startsWith("IPUT") || opcode.name.startsWith("SPUT") ||
            opcode == Opcode.MONITOR_ENTER || opcode == Opcode.MONITOR_EXIT
        ) fail("unexpected state changes or control flow: $opcode")
        when {
            opcode == Opcode.RETURN -> emptyList()
            opcode.name.startsWith("RETURN") || opcode == Opcode.THROW -> fail("unexpected exit: $opcode")
            instruction is OffsetInstruction -> {
                val target = addressToIndex[addresses[index] + instruction.codeOffset] ?: fail("invalid branch target")
                if (target <= index) fail("backward branches require re-analysis")
                if (opcode.name.startsWith("GOTO")) listOf(target) else listOf(index + 1, target)
            }
            else -> listOf(index + 1)
        }.also { next -> if (next.any { it !in instructions.indices }) fail("control flow falls outside the method") }
    }

    // At a join, a register is constant only if all incoming paths agree.
    val states = arrayOfNulls<List<Long?>>(instructions.size)
    states[0] = List(body.registerCount) { null }
    val pending = ArrayDeque<Int>().apply { add(0) }
    while (pending.isNotEmpty()) {
        val index = pending.removeFirst()
        val instruction = instructions[index]
        val state = states[index]!!.toMutableList()
        if (instruction.opcode.setsRegister()) {
            val register = (instruction as? OneRegisterInstruction)?.registerA ?: fail("unsupported register write")
            if (register !in state.indices) fail("invalid register")
            state[register] = when {
                instruction.opcode in setOf(Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST) ->
                    (instruction as WideLiteralInstruction).wideLiteral
                instruction.opcode in setOf(Opcode.MOVE, Opcode.MOVE_FROM16, Opcode.MOVE_16) ->
                    state[(instruction as TwoRegisterInstruction).registerB]
                else -> null
            }
            if (instruction.opcode.setsWideRegister()) {
                if (register + 1 !in state.indices) fail("invalid wide register")
                state[register + 1] = null
            }
        }
        successors[index].forEach { next ->
            val existing = states[next]
            val merged = existing?.mapIndexed { register, value -> if (value == state[register]) value else null } ?: state.toList()
            if (existing != merged) {
                states[next] = merged
                pending.add(next)
            }
        }
    }
    val returnValues = instructions.mapIndexedNotNull { index, instruction ->
        if (instruction.opcode != Opcode.RETURN || states[index] == null) null
        else index to (states[index]!![(instruction as OneRegisterInstruction).registerA]
            ?.takeIf { it == 0L || it == 1L } ?: fail("return value is not a proven boolean constant"))
    }.toMap()
    if (returnValues.values.toSet() != setOf(0L, 1L)) fail("expected both disabled and enabled return paths")

    fun outcomes(start: Int): Set<Long> {
        val seen = mutableSetOf<Int>()
        val queue = ArrayDeque<Int>().apply { add(start) }
        val values = mutableSetOf<Long>()
        while (queue.isNotEmpty()) {
            val index = queue.removeFirst()
            if (!seen.add(index)) continue
            returnValues[index]?.let(values::add)
            queue.addAll(successors[index])
        }
        return values
    }
    val expectations = mapOf(COUNTRY_DISABLED to 0L, FLAG_DISABLED to 0L, FEATURE_ENABLED to 1L,
        "Call recording is enabled by OEM override" to 1L, "Call recording is supported" to 1L,
        "Call recording disabled by fermat" to 0L)
    expectations.forEach { (message, expected) ->
        instructions.indices.filter { instructions[it].stringValue() == message }.forEach { index ->
            if (states[index] == null || outcomes(index) != setOf(expected)) fail("diagnostic '$message' has unexpected return semantics")
        }
    }
}

private fun fail(reason: String): Nothing = throw PatchException("CanRecord: $reason; refusing to guess")
