package local.dialer.callrecording

import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.apk.ApkUtils.applyTo
import app.morphe.patcher.dex.BytecodeMode
import app.morphe.patcher.patch.loadPatchesFromJar
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest

internal fun readMethods(apk: File): Map<String, Method> = ZipFile(apk).use { zip ->
    val methods = linkedMapOf<String, Method>()
    val classes = mutableSetOf<String>()
    zip.entries().asSequence().filter { it.name.matches(Regex("classes[0-9]*\\.dex")) }.forEach { entry ->
        val dex = zip.getInputStream(entry).buffered().use { DexBackedDexFile.fromInputStream(null, it) }
        dex.classes.forEach { definition ->
            check(classes.add(definition.type)) { "Duplicate class: ${definition.type}" }
            definition.methods.forEach { method -> check(methods.put(method.signature(), method) == null) }
        }
    }
    methods
}

internal fun Method.instructionDump(): String = buildString {
    appendLine("$definingClass->$name(${parameterTypes.joinToString("")})$returnType flags=$accessFlags registers=${implementation?.registerCount}")
    var address = 0
    implementation?.instructions?.forEach { instruction ->
        append("$address ${instruction.opcode}")
        when (instruction) {
            is FiveRegisterInstruction -> append(" regs=${listOf(instruction.registerC, instruction.registerD, instruction.registerE, instruction.registerF, instruction.registerG).take(instruction.registerCount)}")
            is RegisterRangeInstruction -> append(" regs=${instruction.startRegister}..+${instruction.registerCount}")
            is ThreeRegisterInstruction -> append(" regs=${instruction.registerA},${instruction.registerB},${instruction.registerC}")
            is TwoRegisterInstruction -> append(" regs=${instruction.registerA},${instruction.registerB}")
            is OneRegisterInstruction -> append(" reg=${instruction.registerA}")
        }
        if (instruction is ReferenceInstruction) append(" ref=${instruction.reference}")
        if (instruction is DualReferenceInstruction) append(" ref2=${instruction.reference2}")
        if (instruction is WideLiteralInstruction) append(" literal=${instruction.wideLiteral}")
        if (instruction is OffsetInstruction) append(" target=${address + instruction.codeOffset}")
        if (instruction is com.android.tools.smali.dexlib2.iface.instruction.formats.ArrayPayload) {
            append(" width=${instruction.elementWidth} elements=${instruction.arrayElements}")
        }
        if (instruction is SwitchPayload) append(" cases=${instruction.switchElements.map { it.key to it.offset }}")
        appendLine()
        address += instruction.codeUnits
    }
    implementation?.tryBlocks?.forEach { block ->
        appendLine("try ${block.startCodeAddress}+${block.codeUnitCount}: ${block.exceptionHandlers.map { it.exceptionType to it.handlerCodeAddress }}")
    }
}

fun main(args: Array<String>) {
    val apk = File(args[0])
    val output = File(args[1]).apply { mkdirs() }
    val originals = readMethods(apk)
    ZipFile(apk).use { zip ->
        val dexEntries = zip.entries().asSequence().filter { it.name.matches(Regex("classes[0-9]*\\.dex")) }.toList()
        val matches = dexEntries.flatMap { entry ->
            val dex = zip.getInputStream(entry).buffered().use { DexBackedDexFile.fromInputStream(null, it) }
            dex.classes.flatMap { it.methods.toList() }.filter { method ->
                method.implementation?.instructions?.any {
                    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "Call recording is disabled in the current country"
                } == true
            }
        }
        check(matches.size == 1) { "Expected one country diagnostic method, found ${matches.size}" }
        output.resolve("original-gate.txt").writeText(matches.single().instructionDump())
        println("DEX files: ${dexEntries.size}; original gate: ${matches.single()}")
    }
    if (args[2] == "inspect") return

    val inputHash = apk.sha256()
    val target = originals.values.single { COUNTRY_DISABLED in it.exactStrings() }
    val bundle = File(args[3])
    ZipFile(bundle).use { check(it.getEntry("classes.dex") != null) { "Bundle has no Android DEX" } }
    val patches = loadPatchesFromJar(setOf(bundle)).filter { it.name == "Enable call recording" }.toSet()
    check(patches.size == 1) { "Expected only the manual recording patch: ${patches.map { it.name }}" }
    val work = output.resolve("patcher-work").canonicalFile
    check(work.toPath().startsWith(output.canonicalFile.toPath()) && work != output.canonicalFile)
    val patchedApk = output.resolve("GooglePhone-241-patched-unsigned.apk")
    Patcher(PatcherConfig(apkFile = apk, temporaryFilesPath = work,
        useBytecodeMode = BytecodeMode.STRIP_FAST)).use { patcher ->
        val metadata = patcher.context.packageMetadata
        check(metadata.packageName == "com.google.android.dialer")
        check(metadata.versionName == "241.0.991235506" && metadata.versionCode == "20129558")
        output.resolve("package-metadata.txt").writeText(
            "package=${metadata.packageName}\nversion=${metadata.versionName}\nversionCode=${metadata.versionCode}\nsha256=$inputHash\n",
        )
        patcher += patches
        runBlocking { patcher().collect { result -> result.exception?.let { throw it } } }
        val result = patcher.get()
        apk.copyTo(patchedApk, overwrite = true)
        result.applyTo(patchedApk)
    }
    val modified = readMethods(patchedApk)
    check(originals.keys == modified.keys) { "Method definitions changed" }
    val changed = originals.keys.filter { originals.getValue(it).instructionDump() != modified.getValue(it).instructionDump() }
    check(changed == listOf(target.signature())) { "Unexpected changed methods: $changed" }
    val patchedTarget = modified.getValue(target.signature())
    val instructions = patchedTarget.implementation!!.instructions.toList()
    check(instructions[0].opcode == com.android.tools.smali.dexlib2.Opcode.CONST_4)
    check((instructions[0] as OneRegisterInstruction).registerA == 0)
    check((instructions[0] as WideLiteralInstruction).wideLiteral == 1L)
    check(instructions[1].opcode == com.android.tools.smali.dexlib2.Opcode.RETURN)
    check((instructions[1] as OneRegisterInstruction).registerA == 0)
    check(apk.sha256() == inputHash) { "Source APK was modified" }
    output.resolve("patched-gate.txt").writeText(patchedTarget.instructionDump())
    ZipFile(patchedApk).use { zip ->
        val targetDex = zip.entries().asSequence().filter { it.name.matches(Regex("classes[0-9]*\\.dex")) }.single { entry ->
            val dex = zip.getInputStream(entry).buffered().use { DexBackedDexFile.fromInputStream(null, it) }
            dex.classes.any { it.type == target.definingClass }
        }
        output.resolve("patched-dex").mkdirs()
        zip.getInputStream(targetDex).use { input ->
            output.resolve("patched-dex/modified-class.dex").outputStream().use(input::copyTo)
        }
    }
    output.resolve("verification-result.txt").writeText(
        "Input SHA-256: $inputHash\nBundle SHA-256: ${bundle.sha256()}\n" +
            "Verified method definitions: ${originals.size}\nChanged methods: ${changed.joinToString()}\n" +
            "Duplicate class definitions: none\nPrefix: const/4 v0, 1; return v0\nSource APK: unchanged\n" +
            "APK: patched, unsigned, aligned by Morphe; not installed\nART/device verification: pending\n",
    )
    println("Verified ${originals.size} methods; exactly one changed: ${target.signature()}")
    println("Patched unsigned APK: $patchedApk")
}

private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { stream ->
        val bytes = ByteArray(65536)
        while (true) {
            val count = stream.read(bytes)
            if (count < 0) break
            digest.update(bytes, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
