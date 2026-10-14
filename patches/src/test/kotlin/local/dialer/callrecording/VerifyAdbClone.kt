package local.dialer.callrecording

import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.apk.ApkUtils.applyTo
import app.morphe.patcher.dex.BytecodeMode
import app.morphe.patcher.patch.loadPatchesFromJar
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    val source = File(args[0])
    val bundle = File(args[1])
    val output = File(args[2]).apply { mkdirs() }
    val destination = File(args[3])
    val original = readMethods(source)
    val patches = loadPatchesFromJar(setOf(bundle)).filter {
        it.name in setOf("Use ADB call audio (test)", "Handle rejected DroidGuard connection")
    }.toSet()
    check(patches.size == 2)
    val wrapper = ZipFile(source).use { zip ->
        zip.entries().asSequence().filter { it.name.matches(Regex("classes[0-9]*\\.dex")) }.flatMap { entry ->
            val dex = zip.getInputStream(entry).buffered().use { DexBackedDexFile.fromInputStream(null, it) }
            dex.classes.asSequence()
        }.single { definition -> definition.methods.any { method ->
            method.name == "<init>" && method.audioCalls().any { it.name == "<init>" } &&
                runCatching { validateAudioWrapper(definition) }.isSuccess
        } }
    }
    val selected = validateAudioWrapper(wrapper)
    val broker = original.values.single { BROKER_SERVICE in it.exactStrings() && "IGmsServiceBroker.getService failed" in it.exactStrings() }
    Patcher(PatcherConfig(apkFile = source, temporaryFilesPath = output.resolve("patcher-work"),
        useBytecodeMode = BytecodeMode.STRIP_SAFE)).use { patcher ->
        patcher += patches
        runBlocking { patcher().collect { result -> result.exception?.let { throw it } } }
        source.copyTo(destination.apply { parentFile.mkdirs() }, overwrite = true)
        patcher.get().applyTo(destination)
    }
    val modified = readMethods(destination)
    check(modified.keys.containsAll(original.keys))
    check((modified.keys - original.keys).all { it.startsWith("Llocal/dialer/bridge/") }) {
        "Unexpected added methods: ${(modified.keys - original.keys).filterNot { it.startsWith("Llocal/dialer/bridge/") }}"
    }
    val changed = original.keys.filter { original.getValue(it).instructionDump() != modified.getValue(it).instructionDump() }.toSet()
    check(changed == selected.values.map { it.signature() }.toSet() + broker.signature()) { "Unexpected changes: $changed" }
    check(nonDexHashes(source) == nonDexHashes(destination)) { "Non-DEX contents changed" }
    output.resolve("original-recorder.txt").writeText(wrapper.methods.joinToString("\n") { it.instructionDump() })
    output.resolve("patched-recorder.txt").writeText(modified.values.filter { it.definingClass == wrapper.type }.joinToString("\n") { it.instructionDump() })
    ZipFile(destination).use { zip ->
        zip.entries().asSequence().filter { it.name.matches(Regex("classes[0-9]*\\.dex")) }.forEach { entry ->
            val bytes = zip.getInputStream(entry).use { it.readBytes() }
            val dex = DexBackedDexFile.fromInputStream(null, bytes.inputStream().buffered())
            if (dex.classes.any { it.type == wrapper.type || it.type.startsWith("Llocal/dialer/bridge/") })
                output.resolve(entry.name).writeBytes(bytes)
        }
    }
    val report = """
        Original methods checked: ${original.size}
        Changed original methods: ${changed.size} (five VOICE_CALL wrapper methods + DroidGuard failure handler)
        Added methods: ${modified.size - original.size}, only in local.dialer.bridge
        PCM format initializer and all other original methods: unchanged
        Manifest, resources, SDK targets, native libraries: unchanged
        ADB dependency: explicit helper, authenticated Unix socket; no Shizuku
        Candidate: ${destination.absolutePath}; unsigned, integrated call testing pending
    """.trimIndent() + "\n"
    output.resolve("result.txt").writeText(report)
    println(report)
}
