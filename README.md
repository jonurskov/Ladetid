# Ladetid

Jon Pedersen, oktober 2026

Android app der viser elprisen de næste 5 dage og giver besked en time før det er billigst at lade.

## Hvad den gør

* Henter priser fra Strømligning og elpriser.org og tager gennemsnittet time for time. Begge kilder har kendte priser for i dag og i morgen og en prognose længere frem.
* Du vælger dit netselskab (standard er Konstant), så nettariffen med spidslast kl. 17 til 21 kommer med.
* Du vælger selv hvad der er med i prisen: transport (netselskab og Energinet), elafgift, moms og elhandlers tillæg.
* Finder for hver dag det billigste sammenhængende tidsrum på det antal timer du vil lade (standard 4 timer).
* Sender en besked en time før hver dags bedste tidsrum. Opdaterer sig selv hver 3. time, også når appen er lukket.

elforbrug.nu og elberegner.dk er ikke med, fordi de ikke har åbne data en app kan hente.

## Sådan får du den på telefonen

**Med GitHub (ingen programmer på computeren)**

1. Opret et nyt repository på github.com og upload alle filerne fra mappen.
2. Gå til fanen Actions. Bygningen "Byg APK" starter af sig selv og tager et par minutter.
3. Åbn den færdige kørsel og hent `Ladetid-apk` under Artifacts. Pak zip filen ud.
4. Send `app-debug.apk` til telefonen, åbn den og tillad installation fra ukendte kilder.

**Med Android Studio**

Åbn mappen i Android Studio, sæt telefonen til med USB (udviklertilstand slået til) og tryk Run.

## Indstillinger i appen

* Ladetid: 1 til 10 timer
* Prisområde: Vest (DK1) eller Øst (DK2)
* Netselskab: vælges fra en liste
* Med i prisen: transport, elafgift, moms og elhandlers tillæg i øre
* Påmindelse: til eller fra

Data: [Strømligning](https://stromligning.dk) og [elpriser.org](https://elpriser.org)
