# Installation and device testing

The patch bundle has passed local build and bytecode checks. Physical-device recording remains unverified. Use Morphe's signing and installation process; the locally generated `verification/GooglePhone-241-patched-unsigned.apk` is an unsigned validation artifact.

## Record the baseline

With USB debugging enabled on your own device, the following commands inspect its current configuration:

```powershell
New-Item -ItemType Directory -Force verification | Out-Null
adb devices
adb shell pm path com.google.android.dialer
adb shell dumpsys package com.google.android.dialer > verification/phone-package-before.txt
adb shell pm list features > verification/phone-features.txt
adb shell cmd role get-role-holders --user 0 android.app.role.DIALER
adb shell cmd appops get com.google.android.dialer RECORD_AUDIO
```

Check the installed version and whether `CAPTURE_AUDIO_OUTPUT`, `CAPTURE_VOICE_COMMUNICATION_OUTPUT`, `MODIFY_AUDIO_ROUTING` and `RECORD_AUDIO` are actually granted. A requested manifest permission is not a granted permission. The presence of the `com.google.android.apps.dialer.call_recording_audio` system feature does not prove that a cloned app can access its backend.

The inspected APK contains five DEX files and nine ARM64 native libraries. If `pm path` lists multiple APKs on your device, handle that release's splits through the supported installation process. Local validation covers the supplied base APK.

## Apply the patch

1. [Add this source to Morphe](https://morphe.software/add-source?github=balu100/GoogleDialerEnableCallRecording), or use the [published bundle](https://github.com/balu100/GoogleDialerEnableCallRecording/releases/latest) with Morphe Desktop or a supported local-import workflow.
2. Supply the original Google Phone `241.0.991235506` / `20129558` APK.
3. Select **Enable call recording**.
4. Check that the log selects `complete diagnostics` and the single target `Lktk;->a()Z` for this APK. A compatible future APK can have a different obfuscated name, but every validation must still pass.

Treat newer versions as experimental. If identification fails, analyze the new APK rather than relaxing the fingerprint to one diagnostic string.

## Choose an installation method

**Already rooted device:** use Morphe's supported Root Mount process with a documented rollback method. Record the original application state first. This project does not directly modify a system partition.

**Device without root:** normal installation over the original system package may fail because the signatures differ. A clone needs a separate supported process to rewrite its package name, components and cross-package references; this bundle modifies only the availability method. Audio permissions and capture of both participants are not guaranteed for a clone.

Do not uninstall, disable or clear the original dialer's data as part of this test. If a working, reversible installation method is unavailable, device validation cannot proceed yet.

## Test manual recording

Use an unlocked device and a normal test call with the other participant's knowledge:

1. Check whether the Call recording settings entry appears.
2. Check whether the Record button appears and is enabled.
3. Record 20–30 seconds, with each participant saying a separate recognizable sentence.
4. Verify the application's existing recording announcement or tone and visible indicators.
5. Stop recording, finish the call, and open the recording from call history.
6. Confirm both participants are audible, the duration is correct, and the file can be reopened.
7. After the basic test passes, test incoming calls, Bluetooth and Wi-Fi calling separately. Results can differ between audio routes.

Do not test with an emergency call. The patch does not remove conference, video, RTT or other call-type restrictions.

| Symptom | Next condition to inspect |
|---|---|
| Settings entry is missing | Feature instance availability, direct boot state and which APK is actually loaded |
| Record button appears but is disabled | `callAudioAvailable` and granted permissions |
| Recording starts but is empty or captures only one participant | Tidepods/vendor audio backend and actual UID permissions |

## Collect logs and roll back

```powershell
adb logcat -d > verification/phone-logcat-after.txt
adb shell dumpsys package com.google.android.dialer > verification/phone-package-after.txt
```

Keep device logs local; the repository ignores `verification/`.

For Root Mount, disable the test module and follow the module's documented restore process, restarting the device if required. For a clone, restore the original app as the default Phone application. Preserve the original package data and call history.

There is no automatic-recording patch in this bundle. Assess that feature separately after manual audio capture has been verified.
