# Technical analysis

This analysis covers Google Phone `241.0.991235506`, version code `20129558`, inspected on October 14, 2026. Obfuscated identifiers below describe that APK only; the patch does not depend on them.

## Modified method

The patch identifies the manual recording availability gate (`Lktk;->a()Z` in the inspected APK) and inserts:

```smali
const/4 v0, 0x1
return v0
```

The method has ten registers, so `v0` is a local register. The existing body remains present but becomes unreachable. Comparing all 154,756 method definitions, instructions and exception regions found exactly one changed method, with no duplicate class definitions.

The override bypasses the method's country check, OEM allowlist, system-feature and feature-flag gates, and Fermat restriction. It therefore has a broader effect than bypassing only the country check. The Fermat resolver is associated with a separate Call Notes / Call Recording feature path; coexistence with that path still needs device testing.

## Target identification

The earlier prototype assumed that diagnostic strings appeared in decompiled Java source order. In this APK, the country-disabled block appears at the end of the DEX method, so that ordered fingerprint does not match.

The current patch anchors each search with one exact diagnostic string and checks the remaining exact strings without assuming their order. Every strategy requires the CanRecord diagnostic provenance, the audio-system-feature anchor and the final flag-disabled message.

All fallback strategies are evaluated before any mutation. Multiple matches or disagreement between strategies cause a failure. There is no single-string fallback that silently accepts a loosely related boolean method.

Validation also checks:

- A boolean, parameterless instance method with a genuine local register.
- No static, abstract, native or synchronized method, or exception regions requiring new analysis.
- The audio feature is passed to `PackageManager.hasSystemFeature`, whose result controls a boolean branch.
- Conservative constant propagation through the control-flow graph: values are constant at a join only when all incoming paths agree.
- Disabled diagnostic paths return `false`; enabled diagnostic paths return `true`.
- Unexpected state writes, switch payloads, backward branches and incompatible exits are rejected.

Ordinary identifier obfuscation is tolerated. More substantial code changes can require new analysis even when the user-facing app version looks similar.

## Remaining feature conditions

| Condition | Observed implementation | Verification |
|---|---|---|
| Feature instance exists | `rpq.t()` returns only an available, enabled implementation | Static; the patch does not create a missing implementation |
| Device is unlocked | `ksm.a()` rejects direct boot | Static; unchanged |
| Settings entry | `kwv.e()` consults the available `ksn` implementation and its CanRecord result | Static path confirmed; UI not tested |
| Manual recording availability | `kwa.b()` checks CanRecord after call-type restrictions | Static path confirmed |
| Record button enabled | `gyb`, case 15, requires available call audio and sufficient permissions | Independent condition; unchanged |
| Recording starts | `kvk.g()` uses the existing disclosure and startup path | Present; runtime untested |
| Both participants' audio | `kvd.c()` requests the Tidepods downlink/uplink tee path | Present; vendor audio backend untested |
| Save and playback | Internal `callrecording/record-*.wav` files and associated metadata | Present; successful recording/playback untested |

Emergency calls and callbacks, conferences and conference child calls, multiple simultaneous calls, CDMA, video, RTT and some Google Fi paths are checked outside the modified method. These call-type and call-state restrictions remain unchanged. They include both protective and compatibility conditions; describing all of them as "safety restrictions" would be imprecise.

## Automatic recording

At least two automatic-recording paths independently consume the additional geofence result:

- `ksr`, case 11, requires CanRecord and `kts.a()`. Failure disables the automatic setting and creates a notification.
- `kwk.b()` also checks `kts.a()` before the caller-specific and permission conditions for automatic startup.

The manual patch alone therefore does not establish automatic-recording support. Changing the shared locale function `kuh.c()` could also affect the country logic for recording beep disclosures (`ksj`). A future automatic patch should identify its own geofence target and verify the disclosure paths separately.

No automatic patch is included. Reliable manual audio capture on the physical device must be established before automatic behavior can be assessed.

## Signing and audio permissions

A differently signed APK cannot ordinarily update the original system package. A renamed clone has a separate application identity and does not automatically inherit the original package's privileged permissions or private data.

Android 16 AOSP defines the relevant special audio permissions with `signature|privileged|role` protection. The role qualifier does not mean that choosing an app as the default dialer grants every special audio permission: the AOSP DIALER role grants general phone and microphone permissions. The actual firmware role policy, granted UID permissions and audio backend must be inspected. See the [Android 16 permission definitions](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/res/AndroidManifest.xml) and [AOSP role configuration](https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/main/PermissionController/res/xml/roles.xml).

Morphe Manager supports [Root Mount installation](https://github.com/MorpheApp/morphe-manager#-features) on an appropriate rooted setup. This may preserve the original package environment, but it does not establish compatibility with a particular firmware or guarantee working audio capture.

## Validation limits

Compilation, DEX parsing, instruction comparison and the D8 round trip validate the local bytecode change. Morphe's default `NoOpDexVerifier` does not perform ART type verification. D8 also does not prove that Android can load the package or that a vendor audio backend can capture both participants.

The original APK SHA-256 was `30ee03a3089357da17f4ae073d44439b8c747af1876121a3a039666ec52069a8` and remained unchanged during testing. The original APK is not distributed by this repository. Follow [device testing](testing.md) for the remaining runtime checks.
