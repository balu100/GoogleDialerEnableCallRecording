# Optional ADB audio backend (test)

This experiment connects Google Phone 241's manual Record button to an explicitly started ADB audio helper. It has **no Shizuku dependency and needs no root**. ADB is still required: this is not a standalone recording solution. Direct ADB capture of both participants was confirmed on the tested HyperOS device; the complete button-to-save flow is pending testing.

## Install the candidate

To build locally, place the original inspected clone at `.ignore/morphe.apk` and run `./tools/Repair-Clone.ps1 -AdbAudio`. This checks all original methods and produces `dist/google-phone-adb-audio-candidate.mpp` plus an unsigned APK. Original APKs and these local outputs are excluded from GitHub.

Import the candidate `.mpp` as a local patch source in Morphe. Use Google Phone **241.0.991235506**, version code **20129558**.

- From the original Google Phone APK, select **Enable call recording**, **Handle rejected DroidGuard connection** if creating the renamed clone, and **Use ADB call audio (test)**.
- From the original inspected clone, select the DroidGuard repair and the ADB audio patch.
- From a clone that already has the DroidGuard repair, select **only Use ADB call audio (test)**. The failure repair intentionally rejects double application.

Use the same Morphe signing key as the installed clone, then install as an update. The unsigned local APK cannot replace an installed copy by itself. Back up that signing key before changing your Morphe installation.

## Start the helper

On Windows, connect an authorized phone by USB and run this from the project folder with an Android SDK (API 31 or newer) and JDK 17 or newer installed:

```powershell
.\tools\Start-AdbAudio.ps1
```

The default package is `com.google.android.dialer.morphe`. Pass `-Package com.google.android.dialer` if using that package, or `-Serial DEVICE_SERIAL` if several devices are connected. The helper expires after 30 minutes; `-Minutes` accepts 1–120.

Keep the terminal open. Starting the helper does not start capture. Start a test call yourself, press Record in the patched Phone app, speak on both sides, press Stop, and play the saved recording from Phone. Check that both participants are audible and that the duration, saving, and stop behavior are correct. Check the existing disclosure playback separately: the original clone had an output-side MODIFY_PHONE_STATE rejection, which this input backend does not repair.

Ctrl+C stops the helper. A phone reboot also stops it. Without the helper, recording fails explicitly instead of pretending to have saved usable audio.

## Scope and authentication

Only five methods in the structurally identified VOICE_CALL wrapper are replaced. Its original 8000 Hz mono PCM16 format stays intact. The Phone recording controller, call-type restrictions, recording disclosures, file encoding and saving logic remain in place. The patch is optional and disabled by default.

The app opens a UID-specific abstract Unix socket. It accepts only UID 2000; the shell helper checks that the other endpoint belongs to the selected installed package's UID. There is no public TCP listener, shared secret in a public bundle, or impersonation of the Phone app. The helper uses its own `com.android.shell` audio attribution.

The helper opens and starts VOICE_CALL only after the connected app sends an explicit START. Disconnect, read failure, timeout or helper expiry releases the recording source. It returns bounded PCM buffers to the existing recording loop and does not save separate audio files. A transport-check command verifies connectivity without opening an AudioRecord.

Android/firmware support varies. The successful independent ADB test on one phone is evidence for that device, not a compatibility guarantee for other phones. See [diagnosis and confirmed test results](recording-failure.md).
