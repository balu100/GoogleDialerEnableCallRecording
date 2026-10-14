package local.dialer.callrecording

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import java.util.logging.Logger

/** Audio permissions, disclosure, direct boot and per-call checks remain independent requirements. */
@Suppress("unused")
val enableCallRecordingPatch = bytecodePatch(
    name = "Enable call recording",
    description = "Enable the existing manual recording availability gate. Requires a working privileged call-audio backend.",
) {
    compatibleWith(
        Compatibility(
            name = "Google Phone",
            packageName = "com.google.android.dialer",
            appIconColor = 0x1A73E8,
            targets = listOf(
                AppTarget("241.0.991235506", versionCode = 20129558,
                    description = "APK bytecode validated; physical-device recording still requires testing."),
                AppTarget(version = null, isExperimental = true),
            ),
        ),
    )

    execute {
        if (packageMetadata.packageName != "com.google.android.dialer") {
            throw PatchException("CanRecord: unexpected application ${packageMetadata.packageName}")
        }
        // DEX block layout need not follow decompiled source order. Anchor with
        // one exact string and check the remaining exact strings without order.
        val strategies = listOf(
            "complete diagnostics" to setOf(COUNTRY_DISABLED, FEATURE_ENABLED, FLAG_DISABLED),
            "feature diagnostic removed" to setOf(COUNTRY_DISABLED, FLAG_DISABLED),
            "country diagnostic removed" to setOf(FEATURE_ENABLED, FLAG_DISABLED),
        )
        val matches = strategies.map { (strategy, diagnostics) ->
            val required = diagnostics + GATE_PROVENANCE + AUDIO_FEATURE
            val fingerprint = Fingerprint(
                returnType = "Z",
                parameters = emptyList(),
                filters = listOf(string(diagnostics.first())),
                custom = { method, _ -> method.exactStrings().containsAll(required) },
            )
            strategy to fingerprint.matchAllOrNull().orEmpty()
        }
        // Evaluate every fallback before mutation, including weaker ones.
        val signature = selectUniqueTarget(matches.map { (strategy, candidates) ->
            strategy to candidates.map { it.originalMethod.signature() }
        })
        val (strategy, target) = matches.firstNotNullOf { (name, candidates) ->
            candidates.singleOrNull { it.originalMethod.signature() == signature }?.let { name to it }
        }
        validateManualGate(target.originalMethod)
        Logger.getLogger("LocalGooglePhone.CallRecording").info(
            "CanRecord: strategy='$strategy'; method=$signature; " +
                "version=${packageMetadata.versionName}; registers=${target.originalMethod.implementation!!.registerCount}",
        )
        // v0 is proven to be local. The existing body becomes unreachable.
        target.method.addInstructions(0, "const/4 v0, 0x1\nreturn v0")
    }
}
