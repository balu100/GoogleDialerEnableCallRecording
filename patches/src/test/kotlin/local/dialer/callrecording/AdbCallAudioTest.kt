package local.dialer.callrecording

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass.Companion.toMutable
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Assumptions.assumeTrue

class AdbCallAudioTest {
    private fun wrapper() = File(System.getProperty("originalApk")).let { apk ->
        assumeTrue(apk.isFile, "Original APK intentionally excluded from GitHub")
        ZipFile(apk).use { zip ->
            zip.entries().asSequence().filter { it.name.matches(Regex("classes[0-9]*\\.dex")) }.flatMap { entry ->
                zip.getInputStream(entry).buffered().use { DexBackedDexFile.fromInputStream(null, it) }.classes.asSequence()
            }.single { definition -> definition.methods.any { method ->
                method.name == "<init>" && method.audioCalls().any { it.name == "<init>" } &&
                    runCatching { validateAudioWrapper(definition) }.isSuccess
            } }.toMutable()
        }
    }
    @Test fun `actual VOICE_CALL wrapper has five distinct operations`() {
        assertEquals(5, validateAudioWrapper(wrapper()).values.toSet().size)
    }
    @Test fun `different PCM sample rate is rejected`() {
        val definition = wrapper()
        val initializer = definition.methods.single { it.name == "<clinit>" }
        val index = initializer.implementation!!.instructions.indexOfFirst {
            (it as? WideLiteralInstruction)?.wideLiteral == 8000L
        }
        val register = (initializer.implementation!!.instructions[index] as OneRegisterInstruction).registerA
        initializer.replaceInstruction(index, "const/16 v$register, 0x3e80")
        assertFailsWith<PatchException> { validateAudioWrapper(definition) }
    }
    @Test fun `replaced recorder field cannot be patched again`() {
        val definition = wrapper()
        definition.instanceFields.single().setType(ADB_RECORDER)
        assertFailsWith<PatchException> { validateAudioWrapper(definition) }
    }
}
