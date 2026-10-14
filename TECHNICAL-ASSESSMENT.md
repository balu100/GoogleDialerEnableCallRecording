# Technikai értékelés — 2026. október 14.

A leírás fő hipotézise az eredeti APK-ból megerősíthető: a kézi rögzítés elérhetőségét és a beállítás megjelenését ugyanaz a CanRecord-ellenőrzés befolyásolja. A teljes működéshez azonban további feltételek is szükségesek. A telefon és a firmware jellemzői, valamint az eredeti alkalmazásnak megadott jogosultságok a feladatleírásból származnak; ezeket eszközkapcsolat nélkül nem ellenőriztem újra.

## A korábbi patch lényeges hiányosságai

1. **A Java-kód és a DEX blokk-sorrendje eltér.** Az országtiltó üzenet a DEX-metódus végén van. A korábbi `country → system-feature → final-flag` rendezett keresés ezen az APK-n nem talál célpontot. A javítás egy pontos szöveggel keres, a további pontos szövegeket sorrendtől függetlenül ellenőrzi.
2. **Az első sikeres fallback nem zárja ki a többi ellentmondását.** Most minden stratégia lefut a módosítás előtt. Több találat, vagy más metódusra mutató fallback esetén nincs módosítás.
3. **Egyetlen országüzenet nem elegendő azonosítás.** Minden stratégia megköveteli a CanRecord naplózási eredetét, a rendszerfeature tényleges ellenőrzését és a végső flagtiltás jelenlétét.
4. **A regiszterszám önmagában kevés.** Paraméter nélküli példánymetódusnál egyetlen regiszter a `this` objektumot tartalmazza. Az új kód legalább egy valódi helyi regisztert kér, és kizárja a statikus, szinkronizált, natív/absztrakt, valamint kivételkezelési tartományt tartalmazó célpontokat.
5. **A komment nem szemantikai bizonyíték.** Az új ellenőrzés a feltételes ugrásokon át követi a regiszterek konstansértékeit. Összefutó ágaknál csak a minden bemeneten azonos értéket fogadja el. A tiltó üzenetekhez `false`, az engedélyezőkhöz `true` visszatérést követel meg.

## Pontosan mi változik?

A kiválasztott metódus elejére két utasítás kerül:

```smali
const/4 v0, 0x1
return v0
```

A tízregiszteres célpontban a `v0` helyi regiszter. Az eredeti metódustörzs megmarad, de nem érhető el. Más metódus utasításai és kivételkezelési tartományai az ellenőrzött APK-ban nem változtak. Az obfuszkált `Lktk;->a()Z` név az eredmény naplózásában szerepel, az azonosításban nincs beleégetve.

Ez a módosítás az országellenőrzést, OEM-listát, rendszerfeature/flag kapukat és a metóduson belüli Fermat-tiltást egyaránt megkerüli. Tehát a hatása szélesebb egy kizárólagos országfeloldásnál. A `fermat` nem bizonyult hívásonkénti biztonsági ellenőrzésnek: a külön Call Notes / Call Recording funkciófeloldóhoz kapcsolódik. Ennek a párhuzamos útvonallal való együttműködése a telefonos teszt egyik ellenőrzendő pontja.

## A teljes működési lánc

| Feltétel | Az APK-ban látott viselkedés | Igazolás |
|---|---|---|
| Funkció példánya elérhető | `rpq.t()` csak létező, engedélyezett implementációt ad vissza | Statikus elemzés; a CanRecord-patch ezt nem hozza létre |
| Felhasználó feloldotta a telefont | `ksm.a()` direct boot módban letilt | Statikus elemzés; megmarad |
| Beállítás megjelenése | `kwv.e()` az elérhető `ksn` példány CanRecord-válaszával engedi a menüt | Statikus útvonal igazolt, képernyő még nem tesztelt |
| Kézi rögzítés elérhetősége | `kwa.b()` CanRecord-ot hív, külön hívástípus-korlátok után | Statikus útvonal igazolt |
| Gomb engedélyezett állapota | `gyb`, 15. ág: hang elérhető **és** elegendő jogosultság | Önálló feltétel, megmarad |
| Rögzítés indítása | `kvk.g()` a saját közlési/indítási útvonalat futtatja | Kód jelen van, runtime még nem tesztelt |
| Mindkét fél hangja | `kvd.c()` a Tidepods downlink/uplink tee útvonalat kéri | Kód jelen van; a gyártói háttér működése nem igazolt |
| Mentés | Belső `callrecording/record-*.wav` fájl és metaadatok | Kód jelen van; sikeres felvétel és lejátszás még nem igazolt |

A segélyhívás, segélyhívás utáni callback, konferencia és annak gyermekhívása, több egyidejű hívás, CDMA, videó-, RTT- és egyes Fi-hívások ellenőrzése a módosított metóduson kívül van. A patch ezeket nem változtatja meg. Segélyhívással nem kell tesztelni.

## Automatikus rögzítés

Az automatikus rögzítésnek legalább két külön országellenőrzési fogyasztója látszik:

- `ksr`, 11. ág: CanRecord és `kts.a()`; hiba esetén az automatikus beállítást kikapcsolja és értesítést készít.
- `kwk.b()`: az automatikus indítás előtt szintén `kts.a()`; utána a hívóhoz tartozó beállítás és jogosultsági feltételek következnek.

Ezért önmagában a kézi patch nem jelent automatikus rögzítési támogatást. A közös `kuh.c()` országfüggvény átírása a hangjelzéshez használt országlogikára is hathatna (`ksj`), ezért nem ez a megfelelő célpont.

Automatikus patch nincs a csomagban: a kézi hangrögzítés fizikai telefonon még nem igazolt, így az automatikus funkció megbízható támogatása sem állapítható meg. A további fejlesztés célpontja külön, egyértelműen azonosított automatikus geofence-kapu lehet; a hangjelzési és beleegyezési útvonalakat külön ellenőrizni kell.

## Telepítési korlátok

Az új aláírású APK rendes frissítésként nem helyettesítheti automatikusan az eltérő aláírású gyári csomagot. A csomagnév átírásával létrehozott klón más alkalmazásazonosság; a gyári alkalmazás jogosultságai és belső adatai nem öröklődnek át.

Android 16 AOSP-ban a felsorolt különleges hangjogosultságok `signature|privileged|role` védelmi szintűek. A `role` kiegészítés miatt a „kizárólag root biztosíthat hozzáférést” állítás túl általános lenne, de ebből nem következik, hogy az alapértelmezett tárcsázó szerep mindet megadja. Az AOSP DIALER szerep általános telefon/mikrofon jogosultságokat ad; a POCO konkrét szerepkiosztását, UID-jogosultságait és audiohátterét kell ellenőrizni. [Android 16 permission-definíciók](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/res/AndroidManifest.xml), [AOSP szerepkiosztás](https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/main/PermissionController/res/xml/roles.xml).

A Morphe Manager támogat Root Mount telepítést. Ez megfelelő, már rootolt környezetben lehetőséget adhat az eredeti csomaghoz tartozó környezet megtartására, de a HyperOS-jogosultságokat és az audioútvonalat utána is ellenőrizni kell. A forrás alapján nem állítok biztos kompatibilitást ezzel a firmware-rel. [Morphe Manager telepítési lehetőségek](https://github.com/MorpheApp/morphe-manager#-features).

## Az ellenőrzés határa

A sikeres build, a metódus-összehasonlítás, a DEX újraolvasása és a D8 feldolgozás a helyi kódmódosítást igazolják. A Morphe alapértelmezett `NoOpDexVerifier` önmagában nem végzi el az ART típusellenőrzését. A külön D8-teszt sem helyettesíti a telefonos betöltést és a kétoldali beszédtesztet. A futásidejű igazolás pontos következő lépéseit a `TESTING.md` tartalmazza.
