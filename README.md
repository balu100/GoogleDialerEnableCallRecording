# Google Phone call recording patches

[![Release workflow](https://github.com/balu100/GoogleDialerEnableCallRecording/actions/workflows/release.yml/badge.svg)](https://github.com/balu100/GoogleDialerEnableCallRecording/actions/workflows/release.yml)

A Morphe patch that enables Google Phone's existing manual call recording availability gate. It uses diagnostic strings and bytecode validation instead of hardcoded obfuscated class or method names.

The patch has been compiled and applied to Google Phone **241.0.991235506** (version code **20129558**). Physical-device recording and capture of both participants' audio still need verification. The patch requires a working call-audio backend and the necessary Android permissions.

## Install

1. [Add this patch source to Morphe](https://morphe.software/add-source?github=balu100/GoogleDialerEnableCallRecording).
2. Select Google Phone and provide the original supported APK.
3. Select **Enable call recording**, then use Morphe's patching and installation process.

See [device testing and installation](docs/testing.md) for signing, privileged permissions, Root Mount and rollback details. The bundle enables manual recording; it does not include an automatic recording patch.

## Available patches

<!-- PATCHES_START EXPANDED -->
**[Add to Morphe](https://morphe.software/add-source?github=balu100/GoogleDialerEnableCallRecording)**

> **[v1.0.0](https://github.com/balu100/GoogleDialerEnableCallRecording/releases/tag/v1.0.0)**&nbsp;&nbsp;•&nbsp;&nbsp;`main`&nbsp;&nbsp;•&nbsp;&nbsp;1 patch total
<details open>
<summary>Google Phone&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

**Supported versions:**

| 241.0.991235506 |
| :---: |
| APK bytecode validated; physical-device recording still requires testing. |

| Patch | Description |
|---|---|
| Enable call recording | Enable the existing manual recording availability gate. Requires a working privileged call-audio backend. |

</details>

<!-- PATCHES_END -->

## Compatibility and validation

- The verified APK contains 154,756 methods. Comparing instructions and exception regions found exactly one changed method.
- Six local tests passed, including rejection of ambiguous targets, conflicting fallback matches and invalid return semantics.
- The modified class DEX passed a D8 round trip. This does not replace Android runtime or audio testing.
- Newer app versions are experimental. The patch refuses ambiguous matches or incompatible bytecode.
- Call-type checks, audio permissions and the application's recording disclosures remain separate requirements.

The availability override also bypasses the gate's feature flags and Fermat restriction. See the [technical analysis](docs/analysis.md) for its exact scope and remaining conditions.

## Build

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
