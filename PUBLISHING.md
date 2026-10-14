# Feltöltés GitHubra és importálás Morphe-ba

A forráskód önmagában még nem importálható GitHub-patchforrásként. Az első kiadási folyamatnak fel kell töltenie a lefordított `.mpp` fájlt, és létre kell hoznia a hozzá tartozó `patches-bundle.json` letöltési metaadatot.

A projekt már tartalmazza a hivatalos Morphe-sablon kiadási folyamatát és szükséges segédfájljait. A `patches-bundle.json` induló értékei az első kiadásig üresek; ezt ne töltsd ki kézzel. A GitHubon futó folyamat a saját repód nevét használja, ezért nem kell felhasználónevet beleégetni a forrásba.

## Első feltöltés

1. Hozz létre egy **nyilvános**, üres GitHub-repót, például `GoogleDialerEnableCallRecording` néven. Alapértelmezett ága legyen `main`.
2. A `dist/google-phone-patches-github-source.zip` tartalmát csomagold ki, és a tartalmát tedd a repó gyökerébe. A `.github`, `.gitignore`, `.gitattributes` és `.releaserc` fájloknak/könyvtáraknak is fel kell kerülniük. A ZIP-et önmagában feltöltve a GitHub nem bontja ki és nem futtatja a kiadási folyamatot.
3. Az első forrásfeltöltés commitüzenete legyen például **`feat: add Google Phone call recording patch`**. Ez indítja el az első automatikus kiadást; a sima `Add files via upload` üzenet nem kér kiadást.
4. Hozd létre a `dev` ágat is a `main` alapján. A hivatalos folyamat ezt használja a fejlesztői kiadásokhoz és visszamerge-eléshez.
5. A repó **Settings → Actions → General** beállításainál engedélyezd az Actions működését és a GitHub Actions által létrehozott/jóváhagyott pull requesteket. A munkafolyamat maga deklarálja a szükséges írási jogosultságokat.
6. Az **Actions → Release** futás legyen sikeres. Az első futás a feltöltéskor már elindulhat; a `dev` ág és a beállítások rendezése után szükség esetén indítsd újra. A **Releases** alatt legyen `.mpp` csatolmány, a `main` ágon pedig a `patches-bundle.json` tartalmazzon tényleges `download_url` értéket.

Ha az első futás nem készített kiadást a commitüzenet miatt, egy új `feat: ...` commit indít kiadást. A workflow kézi indítása önmagában nem pótolja a kiadást kiváltó commitot.

Az automatikus verziózást a semantic-release kezeli; az első GitHub-kiadás verziója eltérhet a helyileg elkészített `0.1.0` csomagétól. A kiadási JSON-t, patchlistát és changelogot a workflow frissíti.

## Morphe-import

Az első sikeres kiadás után a README-ben megjelenő **Hozzáadás a Morphe-hoz** linket használd, vagy a Morphe-ban új GitHub-patchforrásként add hozzá a repódat (`felhasználónév/repónév`).

A forrás letöltése után válaszd ki a Google Phone alkalmazást és az **Enable call recording** patchet. Az importálhatóság a patchcsomag betöltését jelenti; a telefonon működő kétoldali hangrögzítés feltételeit továbbra is a `TESTING.md` szerint kell igazolni.

## Mi kerüljön fel?

A feltöltésre kész ZIP tartalmazza a Kotlin-forrásokat, Gradle-wrapper fájlokat, buildbeállításokat, a kiadási folyamatot, a licenceket és az útmutatókat. A Google Phone APK, visszafejtett alkalmazásforrások, saját telefonos naplók, gyorsítótárak és helyi builderedmények nincsenek benne. A `.mpp` fájlt a GitHub Release fogja elkészíteni és csatolni.

## Mi lett helyben ellenőrizve?

A patch és a patchlista-generátor lefordult. A generált katalógus pontosan az egyetlen Google Phone patchet tartalmazza; a `.mpp` betöltését, APK-módosítását és D8-feldolgozását ismét ellenőriztem. GitHubon még nem futott kiadás, és a Morphe Android-alkalmazásban végzett importot nem teszteltem.

A GitHub build nem kapja meg az eredeti Google Phone APK-t: az APK-t igénylő teszteket kihagyja, az APK nélkül futtatható célpont-egyezési tesztek megmaradnak. Az APK nélküli helyi próbában két teszt sikeres volt, négyet a rendszer kihagyott. A teljes APK-ellenőrzést helyben az eredeti fájllal lehet futtatni.

A kiadási eszközök függőségeit kompatibilis javítóverziókra frissítettem. Az npm ellenőrzése a Node kiadási eszközökben még 17 tranzitív/összesített sérülékenységi jelzést ad (15 high, 2 moderate), köztük javítás nélküli `braces` stack-exhaustion hibát. Ezek a kiadási környezet függőségei, nem az Android-patchbe csomagolt könyvtárak. A GitHub-folyamatban saját, ellenőrzött kiadási konfigurációt és commitokat használj; teljes biztonsági audit nem történt.

Hivatalos alap: [Morphe patches template](https://github.com/MorpheApp/morphe-patches-template), [kiadási konfiguráció](https://github.com/MorpheApp/morphe-patches-template/blob/main/.releaserc).
