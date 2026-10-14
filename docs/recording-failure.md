# HyperOS clone: recording failure and ADB audio test

On October 14, 2026, the user confirmed that the DroidGuard repair allows the renamed Google Phone clone to run, but pressing Record displays "Recording failed". The connected device runs Android API 36; the APK targets API 37, which is unchanged.

## Confirmed failure

The live recording attempt requests `DOWNLINK_AND_UPLINK`. `hxz` constructs an AudioRecord with audio source 4 (`VOICE_CALL`), 8000 Hz, mono PCM16. The audio service returns `EX_SECURITY`, explicitly reporting missing permissions for that source and the clone's UID. AudioFlinger then rejects track creation; calling startRecording on the uninitialized recorder throws IllegalStateException. The Phone app catches that failure and returns to IDLE.

There is also a separate output-side rejection for MODIFY_PHONE_STATE while attempting the recording disclosure audio. The clone does not have the privileged audio/phone permissions of the original system app. The existing RECORD_AUDIO permission and foreground app-op do not satisfy this call-audio request.

This is a system-side permission failure, not a missing record button, an APK-save failure, or another unhandled DroidGuard exception. Suppressing the final IllegalStateException cannot produce an audio track. The [Android permission declarations](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/res/AndroidManifest.xml) classify the special audio permissions as signature/privileged/role, and the [audio-input documentation](https://developer.android.com/media/platform/sharing-audio-input) describes special requirements for cellular-call capture.

Raw evidence and the focused excerpt remain private under `verification/clone/recording-failure/`; recordings and logs are excluded from GitHub.

## Separate rootless ADB test

The device's shell package has CAPTURE_AUDIO_OUTPUT, CAPTURE_VOICE_COMMUNICATION_OUTPUT and MODIFY_PHONE_STATE. An explicit ADB diagnostic identifies itself as its actual UID 2000 / `com.android.shell` and can initialize VOICE_CALL. No application identity is impersonated and no permission is granted to the clone.

The first approved 20-second recording contained silence or noise. Subsequent audio-policy timestamps showed that the call ended about 1.7 seconds after capture began. That interrupted test was inconclusive.

A second approved test ran for the full 20 seconds while audio policy remained in MODE_IN_CALL. It produced 319,680 PCM bytes (19.98 seconds), peak 12,852 and RMS about 1,539.85. The user listened and confirmed **both participants are audible**. Rootless two-sided capture through the real ADB shell is therefore confirmed on this device. This does not grant the same privileges to the clone or verify the clone's Record button and saving flow.

The published bundle has no Shizuku or ADB helper dependency. The ADB tool is a separate diagnostic, not the requested product. The goal is a standalone Morphe clone that records without an external helper. A prototype ADB backend was closed after a recording-free test found a shell-to-app Unix-socket denial under this device's SELinux policy.

The user reports that a Morphe clone previously recorded on this same phone without a helper. The available before-fix backup is byte-identical to the inspected 241 clone; it is not an independently identified older working build. The working APK/version and recording conditions still need comparison. A permission denial in the current build does not identify what changed in an unknown earlier build.

## Reproduce the ADB diagnostic

The Windows script needs an authorized USB-connected device, an Android SDK and a JDK. It is a separate test tool, not a replacement audio backend inside the APK. Android API 31 or newer is required for the explicit attribution API.

```powershell
# Initialization only: does not start recording or read audio.
.\tools\Test-ShellCallAudio.ps1

# Explicitly records a test call for 20 seconds; run only while your test call is active.
.\tools\Test-ShellCallAudio.ps1 -Record -Seconds 20
```

The explicit record command writes `google-phone-audio-test-*.wav` in the phone's Download directory and retrieves it into the ignored verification directory. The script removes its temporary DEX from the device afterwards. Listening for both participants is the acceptance criterion; successful commands, nonzero samples or a valid WAV header are insufficient.

The original APK, stock Phone, Telecom and telephony services remain intact. The independent ADB capture is confirmed; integrated recording, saving and disclosure playback still require separate device tests.
