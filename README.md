# Google Phone hívásrögzítési patch

Elkészült a kézi hívásrögzítés elérhetőségi ellenőrzését feloldó Morphe-patch. A csomag valódi Morphe 1.15.1 függőséggel lefordult, és a mellékelt Google Phone APK-n ellenőriztem a módosítását. A POCO telefonon működő, kétoldali hangrögzítés még nincs igazolva.

## GitHub és Morphe-import

A feltöltés és az első automatikus kiadás lépéseit a [PUBLISHING.md](PUBLISHING.md) tartalmazza. A Morphe GitHub-forrásként a kiadott `.mpp` és az automatikusan generált `patches-bundle.json` alapján tudja letölteni ezt a projektet; a Kotlin-forrás feltöltése önmagában nem elég.

## Patchlista

<!-- PATCHES_START EXPANDED -->
**[Hozzáadás a Morphe-hoz](https://morphe.software/add-source?github=balu100/GoogleDialerEnableCallRecording)**

> **[v1.0.0](https://github.com/balu100/GoogleDialerEnableCallRecording/releases/tag/v1.0.0)**&nbsp;&nbsp;•&nbsp;&nbsp;`main`&nbsp;&nbsp;•&nbsp;&nbsp;1 patches total
<details open>
<summary>📦 Google Phone&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

**🎯 Supported versions:**

| 241.0.991235506 |
| :---: |
| APK bytecode validated; physical-device recording still requires testing. |  |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Enable call recording](#enable-call-recording) | Enable the existing manual recording availability gate. Requires a working privileged call-audio backend. |  |

</details>

<!-- PATCHES_END -->

## Eredmények

- Google Phone: `com.google.android.dialer`, `241.0.991235506`, verziókód `20129558`.
- Morphe Patcher: a hivatalos `v1.15.1` forráskiadás.
- Morphe Gradle-plugin: a hivatalos `v1.3.4` forráskiadás.
- Környezet: Gradle `9.8.1`, Kotlin `2.4.10`, JDK `25.0.4.1`.
- Hat teszt sikeres: átnevezett célpont, hibás visszatérési ág, elégtelen regiszterek, szinkronizált metódus, eltérő fallback-találatok, hiányzó/többértelmű találat.
- Az APK **154 756 metódusának** utasításait és kivételkezelési tartományait összehasonlítva pontosan egy metódus változott: `Lktk;->a()Z`.
- Az APK másolatában nincs kétszer definiált osztály; a módosított osztály DEX-ét a D8 `9.1.31` sikeresen feldolgozta.
- Az eredeti APK SHA-256 értéke változatlan: `30ee03a3089357da17f4ae073d44439b8c747af1876121a3a039666ec52069a8`.

## Fájlok

| Fájl | Szerep |
|---|---|
| `patches/src/main/kotlin/local/dialer/callrecording/EnableCallRecordingPatch.kt` | A regisztrált, fordításban használt patch |
| `patches/src/main/kotlin/local/dialer/callrecording/GateValidation.kt` | Célpontazonosítás és vezérlésifolyam-ellenőrzés |
| `dist/local-google-phone-patches-0.1.0.mpp` | Az átadható patchcsomag |
| `verification/GooglePhone-241-patched-unsigned.apk` | Módosított, aláíratlan APK ellenőrzéshez |
| `verification/build-and-verify.log` | Valós fordítás, tesztek, patchalkalmazás és D8-ellenőrzés |
| `verification/verification-result.txt` | Ellenőrzési eredmények és fájlujjlenyomatok |
| `verification/original-gate.txt`, `verification/patched-gate.txt` | Eredeti és módosított utasítások |
| `TECHNICAL-ASSESSMENT.md` | Mélyebb elemzés és a fennmaradó feltételek |
| `TESTING.md` | Telepítési és telefonos tesztelési útmutató |

A gyökérben található eredeti `EnableCallRecordingPatch-GooglePhone-ObfuscationResistant.kt` megőrzött referencia; a Gradle a `patches/src/main` alatti javított forrást fordítja. A `mitirtamossze.txt` eredeti feladatleírás szintén megmaradt.

## Megismételhető fordítás

PowerShellben, a projekt könyvtárából:

```powershell
.\tools\Build.ps1
```

A parancs ellenőrzött SHA-256 értékű, hivatalos forráskiadásokat használ. A fordításhoz internetelérés és megfelelő JDK szükséges; GitHub-token nem kell. A letöltések és a Gradle gyorsítótára a `.tooling` könyvtárba kerülnek. A tesztekhez az eredeti APK-nak `GooglePhone-241-base.apk` néven kell jelen lennie.

Csak a patchcsomag fordítása, APK-tesztek nélkül:

```powershell
.\tools\Build.ps1 -BuildOnly
```

A normál hivatalos csomagtáras build is támogatott: a Morphe fejlesztői útmutató szerinti `read:packages` GitHub-hitelesítéssel futtasd a `gradlew.bat buildAndroid` parancsot. A helyi forrásos build a csomagtáras hitelesítés nélküli, itt ténylegesen tesztelt út.

## Frissítések

A patch nem támaszkodik obfuszkált osztály- vagy metódusnevekre. A későbbi verziók kísérleti célként szerepelnek, és csak egyértelmű találat, egyező keresési stratégiák, megfelelő aláírás és igazolt vezérlési szerkezet esetén módosíthatók. Eltérő felépítésnél a patch hibával leáll; az új APK-t ismét elemezni kell.

A D8 és a statikus összehasonlítás nem igazolja az ART futásidejű ellenőrzését, a gyártói audiohátteret vagy a felvételek lejátszhatóságát. Ezekhez a telefonos teszt szükséges.

Hivatalos alapok: [Morphe-sablon](https://github.com/MorpheApp/morphe-patches-template), [Patcher v1.15.1](https://github.com/MorpheApp/morphe-patcher/releases/tag/v1.15.1), [Gradle-plugin v1.3.4](https://github.com/MorpheApp/morphe-patches-gradle-plugin/tree/v1.3.4).
