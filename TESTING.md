# Telefonos ellenőrzés

A patchcsomag elkészült; a telefonon még nem futott teszt. A `verification/GooglePhone-241-patched-unsigned.apk` aláíratlan ellenőrzési eredmény. Telepítéshez a Morphe saját patchelési, aláírási és telepítési folyamatát használd.

## 1. Kiindulási állapot rögzítése

USB-hibakereséssel, a saját telefonon ezek csak olvasnak:

```powershell
adb devices
adb shell pm path com.google.android.dialer
adb shell dumpsys package com.google.android.dialer > verification/phone-package-before.txt
adb shell pm list features > verification/phone-features.txt
adb shell cmd role get-role-holders --user 0 android.app.role.DIALER
adb shell cmd appops get com.google.android.dialer RECORD_AUDIO
```

A package-kimenetben ellenőrizd a verziót és a ténylegesen megadott `CAPTURE_AUDIO_OUTPUT`, `CAPTURE_VOICE_COMMUNICATION_OUTPUT`, `MODIFY_AUDIO_ROUTING`, `RECORD_AUDIO` jogosultságokat. A manifestben kért jogosultság nem azonos a megadott jogosultsággal. A rendszerfeature megléte sem bizonyítja, hogy egy klónozott csomag hozzáfér az audioháttérhez.

Az eredeti APK 5 DEX-fájlt és 9 ARM64 natív könyvtárat tartalmaz. Ha a telefonon a `pm path` több APK-t sorol fel, a telepítési folyamatnál az adott kiadás splitjeit is megfelelően kezelni kell; a helyi bájtkódteszt csak az itt mellékelt base APK-t ellenőrizte.

## 2. Patch alkalmazása

1. A Morphe Desktopban vagy a Manager támogatott helyi bundle-importján keresztül válaszd ki a `dist/local-google-phone-patches-0.1.0.mpp` csomagot. Ha a Manager-verzió nem kínál helyi importot, a Desktop helyi bundle-kezelését használd.
2. Bemenetnek az eredeti `241.0.991235506` / `20129558` APK-t add meg.
3. Válaszd az **Enable call recording** patchet.
4. A naplóban ellenőrizd a `complete diagnostics` stratégiát és az egyetlen `Lktk;->a()Z` célpontot. Eltérő verziónál más obfuszkált név lehet helyes, de a patchnek minden ellenőrzést sikeresen teljesítenie kell.

Újabb verziót csak kísérletként kezelj. Sikertelen azonosításnál ne lazítsd le a feltételeket egyetlen szövegre; az új APK vezérlési logikáját kell vizsgálni.

## 3. Telepítési út kiválasztása

**Már rootolt telefon:** a Morphe által támogatott Root Mount útvonal használható vizsgálatra. Előtte legyen meg az eredeti alkalmazás állapota és a használt modulhoz tartozó visszaállítási lehetőség. A Morphe saját modulkezelését használd; a projekt nem ír közvetlenül a rendszerpartícióra.

**Root nélküli telefon:** az új aláírású APK rendes telepítése a gyári csomag fölé aláíráseltérés miatt meghiúsulhat. Külön csomagnévvel telepített klón esetén a manifest, a komponensek és csomagközi hivatkozások átírását külön, támogatott klónozó folyamatnak kell elvégeznie; ez a bundle csak az elérhetőségi metódust módosítja. A klón kétoldali hanghozzáférése nem garantált. Először a tényleges jogosultságokat és hangelérhetőséget ellenőrizd.

Ha nincs működő, visszaállítható telepítési út, ezen a ponton a telefonos teszt feltétele hiányzik. Az eredeti Telefon alkalmazás eltávolítása, letiltása vagy adatainak törlése nem része ennek az eljárásnak.

## 4. Kézi funkció tesztelése

Feloldott telefonon, normál teszthívásban, a másik fél tudtával:

1. Ellenőrizd, megjelenik-e a Hívásrögzítés beállítás.
2. Ellenőrizd, megjelenik-e a Rögzítés gomb, és engedélyezett-e.
3. Indíts 20–30 másodperces felvételt. Mindkét fél mondjon külön, felismerhető mondatot.
4. Ellenőrizd a meglévő figyelmeztető hangot/bejelentést és a felületi jelzést.
5. Állítsd le a rögzítést, fejezd be a hívást, és nyisd meg a felvételt a híváselőzményekből.
6. Ellenőrizd külön mindkét fél hallhatóságát, az időtartamot és az újbóli megnyitást.
7. Sikeres alapteszt után külön ellenőrizhető a bejövő hívás, Bluetooth és Wi-Fi-hívás. Az egyes audioútvonalak eredménye eltérhet.

Segélyhívással ne tesztelj. A különleges hívások korlátozásai megmaradtak; a patchtől nem várható konferencia-, videó- vagy RTT-rögzítés feloldása.

Ha a gomb megjelenik, de letiltott, a `callAudioAvailable` és jogosultsági feltételek a következő vizsgálati pontok. Ha a rögzítés indul, de üres/nem kétoldali a fájl, a Tidepods/audioháttér és a tényleges UID-jogosultságok vizsgálata következik. Ha a menü sem jelenik meg, a funkciópéldány elérhetőségét, direct boot állapotot és a ténylegesen betöltött APK-t kell ellenőrizni.

## 5. Napló és visszaállítás

```powershell
adb logcat -d > verification/phone-logcat-after.txt
adb shell dumpsys package com.google.android.dialer > verification/phone-package-after.txt
```

Root Mount esetén a teszthez használt Morphe/Magisk modul kikapcsolásával és az adott modul dokumentált visszaállításával térj vissza az eredeti alkalmazáshoz; szükség esetén indítsd újra a telefont. Klón esetén a Telefon alapértelmezett alkalmazásaként állítsd vissza az eredetit. Az eredeti csomag adatait és híváselőzményeit ne töröld.

Az automatikus rögzítéshez nincs patch ebben a csomagban. Annak tesztelése a kézi hangrögzítés igazolása után külön fejlesztési és ellenőrzési lépés.
