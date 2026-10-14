package local.dialer.callrecording

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

class DroidGuardConnectionTest {
    private fun broker() = File(System.getProperty("originalApk")).let { apk ->
        assumeTrue(apk.isFile, "The original APK is intentionally excluded from GitHub")
        readMethods(apk).values.single { BROKER_SERVICE in it.exactStrings() && "IGmsServiceBroker.getService failed" in it.exactStrings() }
    }

    @Test fun `actual broker has an isolated security rethrow`() {
        assertTrue(securityHandlerIndex(broker()) > 0)
    }

    @Test fun `handler with different failure semantics is rejected`() {
        val target = broker().toMutable()
        val handler = securityHandlerIndex(target)
        target.replaceInstruction(handler + 1, "return-void")
        assertFailsWith<PatchException> { securityHandlerIndex(target) }
    }

    @Test fun `already repaired broker cannot be patched twice`() {
        val target = broker().toMutable()
        insertDroidGuardFailureHandling(target, "Laaex;")
        assertFailsWith<PatchException> { insertDroidGuardFailureHandling(target, "Laaex;") }
    }
}
