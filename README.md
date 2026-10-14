# Google Phone call recording patches

[![Release workflow](https://github.com/balu100/GoogleDialerEnableCallRecording/actions/workflows/release.yml/badge.svg)](https://github.com/balu100/GoogleDialerEnableCallRecording/actions/workflows/release.yml)

Morphe patches for Google Phone's existing manual call recording availability gate and a renamed clone's rejected DroidGuard connection. Diagnostic strings and bytecode validation identify targets without hardcoded obfuscated class or method names. The bundle has no Shizuku dependency.

The patch has been compiled and applied to Google Phone **241.0.991235506** (version code **20129558**). A separate rootless ADB test captured both participants on the tested HyperOS phone. Recording through the clone's own Record button still needs verification. The availability patch requires a working call-audio backend and the necessary Android permissions.

## Install

1. [Add this patch source to Morphe](https://morphe.software/add-source?github=balu100/GoogleDialerEnableCallRecording).
2. Select Google Phone and provide the original supported APK.
3. Select **Enable call recording**, then use Morphe's patching and installation process.

See [device testing and installation](docs/testing.md) for signing, privileged permissions, Root Mount and rollback details. The bundle enables manual recording; it does not include an automatic recording patch.

## Available patches

<!-- PATCHES_START EXPANDED -->
**[Add to Morphe](https://morphe.software/add-source?github=balu100/GoogleDialerEnableCallRecording)**

> **[v1.1.0](https://github.com/balu100/GoogleDialerEnableCallRecording/releases/tag/v1.1.0)**&nbsp;&nbsp;•&nbsp;&nbsp;`main`&nbsp;&nbsp;•&nbsp;&nbsp;2 patches total
<details open>
<summary>Google Phone&nbsp;&nbsp;•&nbsp;&nbsp;2 patches</summary>
<br>

**Supported versions:**

| 241.0.991235506 |
| :---: |
| APK bytecode validated; physical-device recording still requires testing. |

| Patch | Description |
|---|---|
| Enable call recording | Enable the existing manual recording availability gate. Requires a working privileged call-audio backend. |
| Handle rejected DroidGuard connection | Report a rejected DroidGuard broker connection as DEVELOPER_ERROR instead of crashing a renamed clone. Device testing required. |

</details>

<details open>
<summary>Google Phone (Morphe clone)&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

**Supported versions:**

| 241.0.991235506 |
| :---: |
| Experimental DroidGuard connection failure handling; not a call-audio permission fix. |

| Patch | Description |
|---|---|
| Handle rejected DroidGuard connection | Report a rejected DroidGuard broker connection as DEVELOPER_ERROR instead of crashing a renamed clone. Device testing required. |

</details>

<!-- PATCHES_END -->

## Compatibility and validation

- The verified APK contains 154,756 methods. Each patch was checked separately and changes exactly one method.
- Nine local tests passed, covering gate validation, incompatible catch semantics and double application of the connection repair.
- The modified class DEX passed a D8 round trip. This does not replace Android runtime or audio testing.
- Newer app versions are experimental. The patch refuses ambiguous matches or incompatible bytecode.
- Call-type checks, audio permissions and the application's recording disclosures remain separate requirements.

The availability override also bypasses the gate's feature flags and Fermat restriction. See the [technical analysis](docs/analysis.md) for its exact scope and remaining conditions.

On the tested HyperOS device, the clone stays running after the DroidGuard repair but its direct recording request is rejected by Android's audio permission checks. A separate ADB test captured both participants during a complete active call, confirmed by playback. The ADB tool is a separate diagnostic; the project's goal is a standalone clone that records without an external helper. That standalone result remains unverified. See [recording failure and ADB test results](docs/recording-failure.md).

## Build

The optional **Handle rejected DroidGuard connection** patch repairs the inspected clone's broker failure. See [clone crash diagnosis, installation and testing](docs/clone-crash-repair.md). The failure handler has passed an isolated ART test on a connected device; full call testing and working audio capture are separate requirements.

On Windows with a suitable JDK:

```powershell
.\tools\Build.ps1 -BuildOnly
```

This builds against checksum-verified official Morphe source releases without a GitHub token. To run the full local APK validation, place your original APK at `GooglePhone-241-base.apk` and run:

```powershell
.\tools\Build.ps1
```

Build outputs are under `patches/build/libs/` and `dist/`. APKs, device logs and local caches are excluded from the repository.

For the standard Gradle build, release workflow and contributing instructions, see [development](docs/development.md).

## License

See [LICENSE](LICENSE) and [NOTICE](NOTICE). This is an independent patch project using the [Morphe patch framework](https://github.com/MorpheApp/morphe-patcher).

