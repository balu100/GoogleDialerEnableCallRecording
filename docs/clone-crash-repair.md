# Google Phone 241: renamed-clone DroidGuard crash

## Evidence and diagnosis

The inspected input is `.ignore/morphe.apk`, package `com.google.android.dialer.morphe`, version `241.0.991235506`, version code `20129558`, target SDK 37. Its SHA-256 is `b4f87d05dc9a4e104aac9db44bdb42130cf55744915e65ec3e7a50d77ff3755f`. APK signature verification succeeds; its Morphe signing certificate fingerprint matches the fingerprint rejected in the crash log. This fingerprint identifies the **signing certificate**, not the APK-file hash.

The supplied log and the connected device's crash buffer both contain `FATAL EXCEPTION: DG` with a broker-side `PackageVerificationRslt: not allowed` SecurityException. The configured DIALER role already belongs to the clone. Telecom disconnects its InCallService after the process fails and binds the preinstalled dialer. Changing the role or disabling the stock dialer does not repair this exception.

JADX analysis of the actual clone identifies the client conclusively:

| Class / method in this APK | Role |
|---|---|
| `aatv.x()` | Creates the `DG` HandlerThread and `aaex` client |
| `aaex` | Service ID 25, `com.google.android.gms.droidguard.service.START`, descriptor `com.google.android.gms.droidguard.internal.IDroidGuardService` |
| `aabo.s()` | Builds GetServiceRequest using the actual context package name; calls IGmsServiceBroker transaction 46 and reads the remote exception |
| `aabl.a()` → `aabn.b()` → `aabg.c()` → `aabh.handleMessage()` | Delivers the service-binding callback and initiates the request |

This is a rejected **DroidGuard connection**, not proof of a failed audio API or a DEX verification error. The exact allowlist/certificate rule inside the installed Play services implementation was not decompiled. Renaming and signing changed the caller identity; the observed broker refuses that identity.

Static callers of the DroidGuard wrapper include `jis.a()` (`dialer_caller_id_event`), `ubt` (`dialer_spam_report`) and `zfj` (`hades_persephone_risk`). The log and stack do not distinguish which caller initiated this particular request. These token-producing paths are separate from the manual recording availability gate and the Tidepods recording backend. Services that require an accepted DroidGuard result remain unavailable to the rejected clone.

## Minimal change and state handling

The optional **Handle rejected DroidGuard connection** patch changes only the existing SecurityException catch in `aabo.s()`. It recognizes both the DroidGuard client type and the exact error prefix `PackageVerificationRslt: not allowed:`. Null messages, other SecurityExceptions and other service clients retain the original rethrow.

The matching denial is reported using the existing post-init callback `D(10, null, null, n.get())`. Google's [ConnectionResult reference](https://developers.google.com/android/reference/com/google/android/gms/common/ConnectionResult) defines code 10 as DEVELOPER_ERROR. The existing generation check remains active. `aabg.c()` transitions the client to DISCONNECTED through `J(1, null, generation)`, unbinds an existing connection, and invokes `aabm.a()`. This reaches `aaey.i()`, which produces Status 17 containing ConnectionResult 10 and drains its pending queue with failure. The task wrapper then completes exceptionally; `aaxg.j()` already handles ExecutionException with its error-result path. No successful attestation result or replacement token is fabricated.

Only the inspected version is accepted. Fingerprints use broker/service strings and class hierarchy rather than these obfuscated names. Changed catch semantics, an ambiguous match or insufficient registers make the patch fail. It is disabled by default while complete call testing is pending.

## Completed validation

- Nine local tests pass, including rejection of changed catch semantics and double application.
- All 154,756 method bodies and exception regions compared: only `Laabo;->s(Laach;Ljava/util/Set;)V` changes.
- The existing `Lktk;->a()Z` recording patch is preserved at instruction level.
- All 4,007 non-DEX ZIP entry contents match, excluding signing metadata. Manifest, package name, target SDK, resources and native libraries are unchanged. Input APK is unchanged.
- D8 accepts the modified DEX. The original manual recording patch is separately revalidated.
- On the connected phone, `DroidGuardFailureProbe` executes the actual candidate APK's classes under ART using an in-process Binder that returns the observed rejection. The broker returns normally; the callback receives Status 17 / ConnectionResult 10 exactly once, the pending queue empties, and the client is neither connected nor connecting. An unrelated message and a null message still throw SecurityException. The same rejection also remains fatal for a different GMS client (`aacy`, client notification telemetry).
- The same probe against the installed original APK rethrows the matching rejection, providing a control result.

The ART probe does not install an app, place calls, invoke a real Google API, or establish full InCallService/recording behavior. The user subsequently confirmed that the updated clone runs, but recording fails. The separate live audio diagnosis is documented in [recording failure](recording-failure.md). Evidence is in `verification/clone/result.txt`, `verification/morphe-repair-build.log` and `verification/clone/art-probe/`.

The fixture source is `tools/art-probe/DroidGuardFailureProbe.java`. To reproduce the isolated ART check, compile it with `javac --release 17` against the installed Android SDK's `android.jar`, then run D8 with `--min-api 30` on all emitted class files. Push the resulting `classes.dex` and the candidate APK to temporary device paths and run:

```text
adb shell "CLASSPATH=/data/local/tmp/google-phone-clone-repair-test.apk:/data/local/tmp/google-phone-clone-repair-probe.dex app_process /system/bin DroidGuardFailureProbe"
```

For the control, substitute the installed clone's `base.apk` path returned by `adb shell pm path com.google.android.dialer.morphe` as the first classpath entry and add `baseline` after the class name. These are fixture-specific checks for the inspected APK's identifiers. No signing key is involved and the installed package is not changed.

## Build and install without root

From the repository directory:

```powershell
.\tools\Repair-Clone.ps1
```

This builds `dist/google-phone-droidguard-fix-candidate.mpp` and `dist/morphe-droidguard-fixed-unsigned.apk`. Published releases also include the optional repair in their `.mpp` bundle. The original v1.0.0 release contains only the recording availability patch.

### Apply through the same Morphe Manager

1. Open Sources → + → Local and select `google-phone-droidguard-fix-candidate.mpp`. [Morphe supports local bundles](https://github.com/MorpheApp/morphe-manager/blob/main/docs/patch-sources.md).
2. Use the preserved, already patched clone `google-phone-morphe-before-fix.apk` as the input APK. Both files are in the phone's Download folder.
3. Select this candidate source and explicitly enable **Handle rejected DroidGuard connection**. Use Expert mode if needed to select an optional patch. Apply only this repair to the clone; its recording change is already present.
4. Let the same Morphe installation sign the result and install it as an update. Android requires the original signing key to keep existing app data. See [Morphe's signing-key documentation](https://github.com/MorpheApp/morphe-manager/blob/main/docs/backup-and-keystore.md).
5. Keep the stock Phone installed and enabled throughout testing. Keep the original clone APK for rollback with `adb install -r` if necessary.

Alternatively, sign the unsigned candidate on the PC with the **exported original Morphe keystore**, then verify the certificate fingerprint matches the original. For an unchanged Morphe-generated key, its documented alias and password are `Morphe`; custom keys require their own credentials. With the password in `MORPHE_SIGNING_PASSWORD`:

```powershell
java -jar "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0\lib\apksigner.jar" sign --ks "C:\path\Morphe.keystore" --ks-key-alias Morphe --ks-pass env:MORPHE_SIGNING_PASSWORD --key-pass env:MORPHE_SIGNING_PASSWORD --out dist\morphe-droidguard-fixed.apk dist\morphe-droidguard-fixed-unsigned.apk
java -jar "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0\lib\apksigner.jar" verify --print-certs dist\morphe-droidguard-fixed.apk
adb install -r dist\morphe-droidguard-fixed.apk
```

The unsigned APK cannot be installed directly. A different key cannot update this clone; do not uninstall it merely to work around a signature mismatch.

## Reproducible full call test

After the signed update is installed:

```powershell
.\tools\Test-Clone.ps1 -Phase Begin
```

Manually make one outgoing call, then one incoming call, using a consenting test partner. Keep each connected for at least 30 seconds. Check the clone UI stays active, mute/speaker/end-call work, and there is no switch to the stock UI. Start and stop manual recording, then play the saved file and check that both voices are audible. Repeat an ordinary call after that to check recovery. No emergency-call test is needed.

```powershell
.\tools\Test-Clone.ps1 -Phase Collect
```

The script records the role holder, package permissions, Telecom state and log window without clearing existing logs. Review `verification/clone/call-test/diagnostics.txt`, the full window and crash buffer together. A `GooglePhone.CloneRepair` denial marker is expected when the rejected service is requested; it should be followed by failure completion, not an AndroidRuntime fatal. Nonfatal GoogleApiManager rejection logs may still occur for other APIs. A changed PID, InCallService disconnect while the call is active, new fatal exception, or inaudible recording requires further investigation. Lack of a crash log alone does not prove a passing call test.

## Remaining audio constraint

The live package dump grants RECORD_AUDIO but does not list CAPTURE_AUDIO_OUTPUT, CAPTURE_VOICE_COMMUNICATION_OUTPUT or BYPASS_CONCURRENT_RECORD_AUDIO_RESTRICTION among granted permissions. A PackageManager.checkPermission query on the device independently returns DENIED (-1) for CAPTURE_AUDIO_OUTPUT and CAPTURE_VOICE_COMMUNICATION_OUTPUT, and GRANTED (0) for RECORD_AUDIO. They are requested in the APK, which is not a grant. The clone is not privileged. Android's [audio-sharing documentation](https://developer.android.com/media/platform/sharing-audio-input) describes the privileged permission requirement for capturing cellular-call audio. The firmware's Tidepods path still needs real testing. This broker repair grants no audio privilege and cannot promise rootless two-sided recording on this device.
