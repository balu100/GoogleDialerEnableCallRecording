# Development and releases

## Repository layout

| Path | Purpose |
|---|---|
| `patches/src/main/kotlin/local/dialer/callrecording/` | Registered patch and target-validation helpers |
| `patches/src/main/kotlin/util/PatchListGenerator.kt` | Official-template patch catalog generator |
| `patches/src/test/kotlin/` | Gate validation tests and local APK verification |
| `.github/workflows/release.yml` | Official-template release automation |
| `.github/scripts/generate_patches_readme.py` | Generated README patch section |
| `tools/Build.ps1` | Windows build against checksum-verified official source releases |
| `docs/` | Technical analysis, device testing and development instructions |

APKs, decompiled app sources, device logs, build outputs and local caches are excluded. The repository contains no original Google Phone APK.

## Local build

The tested toolchain uses Morphe Patcher `1.15.1`, Morphe Gradle plugin `1.3.4`, Gradle `9.8.1` and Kotlin `2.4.10`. The Windows source-build path was tested with JDK `25.0.4.1`; GitHub uses Temurin 21.

```powershell
.\tools\Build.ps1 -BuildOnly
```

The helper downloads official tagged sources, checks their archive SHA-256 values and stores dependencies under `.tooling/`. It requires network access and a suitable JDK, but no GitHub token. Outputs are `patches/build/libs/patches-<version>.mpp` and `dist/local-google-phone-patches-<version>.mpp`.

To run all local tests, patch the APK copy and perform D8 validation, provide `GooglePhone-241-base.apk` in the repository root and run:

```powershell
.\tools\Build.ps1
```

Verification output goes to `verification/`. The original APK is preserved, and nothing is installed on a device automatically.

The standard upstream package build is also available with the [Morphe development authentication setup](https://github.com/MorpheApp/morphe-patcher/blob/main/docs/2_1_setup.md):

```sh
./gradlew buildAndroid
```

## Tests without the original APK

GitHub does not receive the original APK. Seven APK-dependent tests use JUnit assumptions to skip when the fixture is absent; the two independent target-selection tests still run. With the private fixture, all nine tests passed. Full APK and audio verification remain separate checks.

## Release workflow

The project follows the [official Morphe template](https://github.com/MorpheApp/morphe-patches-template). The stable branch is `main`; `dev` is used for prereleases and release backmerges. Both branches must exist before releasing.

The workflow uses Ubuntu 24.04 and Node 24 explicitly, installs the checked-in dependency lock with `npm ci`, and delegates versioning to semantic-release. Make development changes on `dev` and use conventional commit messages:

- `feat: ...` requests a feature release.
- `fix: ...` requests a patch release.
- `docs: ...` and `chore: ...` do not request a release by themselves.

When a release is requested, the workflow builds the `.mpp`, generates its catalog and download metadata, updates the README section and publishes the release asset. For commits without a new release, it still checks that the patch compiles.

The generated `patches-list.json`, `patches-bundle.json` and `CHANGELOG.md` are maintained by the release workflow. Do not hand-edit their release data or recreate an existing release tag. The README content between `PATCHES_START` and `PATCHES_END` is generated as well; edit its generator for changes that should persist across releases.

The `v1.0.0` release has been published and its bundle download and Android DEX content checked. The original workflow failed after publication because `dev` was missing; creating the branch and rerunning the job completed successfully. That failure did not require deleting the release or tag.

## Using a fork as a Morphe source

For a new public fork or repository, retain the workflow, scripts, Gradle wrapper and release configuration. Create `main` and `dev`, enable GitHub Actions and its permission to create/approve pull requests, and make a release-triggering conventional commit.

The GitHub workflow derives the source URL and author from the repository environment. After its first successful release, check that the release has an `.mpp` asset and the default branch's `patches-bundle.json` points to it. Use the generated **Add to Morphe** link or enter `owner/repository` as a GitHub patch source in Morphe.

Source files alone are not an installable Morphe source. A ZIP uploaded as one repository file is not automatically extracted or built by GitHub.

## Release tooling advisories

The October 14, 2026 dependency scan still reported upstream advisories in the Node release tooling, including a `braces` denial-of-service issue without an available published fix. These development dependencies are not bundled into the Android patch. Review `npm audit` when maintaining the release toolchain; a full security audit has not been performed.

Preserve the upstream [LICENSE](../LICENSE) and [NOTICE](../NOTICE), and use independent project branding.

