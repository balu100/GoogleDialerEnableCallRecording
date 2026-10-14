package local.dialer.callrecording

import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.apk.ApkUtils.applyTo
import app.morphe.patcher.dex.BytecodeMode
import app.morphe.patcher.patch.loadPatchesFromJar
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    val source = File(args[0])
    val bundle = File(args[1])
    val output = File(args[2]).apply { mkdirs() }
    val destination = File(args[3])
    val inputHash = hash(source.readBytes())
    val original = readMethods(source)
    val target = original.values.single { BROKER_SERVICE in it.exactStrings() && "IGmsServiceBroker.getService failed" in it.exactStrings() }
    val gate = original.values.single { COUNTRY_DISABLED in it.exactStrings() }
    val patches = loadPatchesFromJar(setOf(bundle)).filter { it.name == "Handle rejected DroidGuard connection" }.toSet()
    check(patches.size == 1)
    Patcher(PatcherConfig(apkFile = source, temporaryFilesPath = output.resolve("patcher-work"),
        useBytecodeMode = BytecodeMode.STRIP_SAFE)).use { patcher ->
        val metadata = patcher.context.packageMetadata
        check(metadata.packageName == "com.google.android.dialer.morphe")
        check(metadata.versionName == "241.0.991235506")
        patcher += patches
        runBlocking { patcher().collect { result -> result.exception?.let { throw it } } }
        val result = patcher.get()
        destination.parentFile.mkdirs()
        source.copyTo(destination, overwrite = true)
        result.applyTo(destination)
    }
    val repaired = readMethods(destination)
    check(original.keys == repaired.keys) { "Method definitions changed" }
    val changed = original.keys.filter { original.getValue(it).instructionDump() != repaired.getValue(it).instructionDump() }
    check(changed == listOf(target.signature())) { "Unexpected changed methods: $changed" }
    check(original.getValue(gate.signature()).instructionDump() == repaired.getValue(gate.signature()).instructionDump())
    val fixed = repaired.getValue(target.signature())
    check(DROIDGUARD_REJECTION_LOG in fixed.exactStrings())
    check(VERIFICATION_DENIED in fixed.exactStrings())
    val resources = nonDexHashes(source)
    check(resources == nonDexHashes(destination)) { "Manifest, resources, native libraries or other non-DEX entries changed" }
    check(hash(source.readBytes()) == inputHash)
    output.resolve("original-broker.txt").writeText(target.instructionDump())
    output.resolve("fixed-broker.txt").writeText(fixed.instructionDump())
    ZipFile(destination).use { zip ->
        val modifiedDex = zip.entries().asSequence().filter { it.name.matches(Regex("classes[0-9]*\\.dex")) }.single { entry ->
            val dex = zip.getInputStream(entry).buffered().use { DexBackedDexFile.fromInputStream(null, it) }
            dex.classes.any { it.type == target.definingClass }
        }
        zip.getInputStream(modifiedDex).use { input ->
            output.resolve("modified-class.dex").outputStream().use(input::copyTo)
        }
    }
    val result = """
        Input SHA-256: $inputHash
        Candidate SHA-256: ${hash(destination.readBytes())}
        Method definitions checked: ${original.size}
        Changed methods: ${changed.joinToString()}
        Recording availability gate: preserved byte for byte at instruction level (${gate.signature()})
        Non-DEX ZIP entry contents preserved: ${resources.size} (excluding signing metadata)
        Package, manifest, target SDK, resources and native libraries: unchanged
        Original APK: unchanged
        Failure: only DroidGuard client + PackageVerificationRslt not allowed -> existing post-init callback, code 10
        Other SecurityExceptions: original rethrow retained
        Output: unsigned; installation and ART/device validation pending
    """.trimIndent() + "\n"
    output.resolve("result.txt").writeText(result)
    println(result)
}

private fun nonDexHashes(apk: File): Map<String, String> = ZipFile(apk).use { zip ->
    zip.entries().asSequence().filter { !it.isDirectory && !it.name.matches(Regex("classes[0-9]*\\.dex")) &&
        !it.name.startsWith("META-INF/") }.associate { entry -> entry.name to hash(zip.getInputStream(entry).use { it.readBytes() }) }
}

private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it) }
