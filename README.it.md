<h1 align="center">MetroWin Keyboard</h1>

<p align="center">
  <b>La tastiera di Windows Phone su Android.</b><br>
  Tasti Metro piatti, caratteri grandi e leggeri, scrittura a scorrimento, traduzione, GIF, appunti, digitazione vocale.<br>
  Software libero. Nessuna pubblicità, nessuna analisi, nessun tracker: ciò che scrivi resta sul tuo telefono.
</p>

<p align="center"><a href="https://github.com/developerkenshi-coder/MetroWin-Keyboard/releases/latest"><img alt="Ultima release" src="https://img.shields.io/github/v/release/developerkenshi-coder/MetroWin-Keyboard?label=release&color=1ba1e2"></a></p>

<p align="center"><a href="README.md">English</a> · <b>Italiano</b></p>

<p align="center"><img src="docs/screenshots/looks.png" width="92%" alt="Classic, Modern Glass e Modern Droid"></p>

<p align="center">
  <img src="docs/screenshots/home.png" width="19%" alt="La home con il riquadro della calibrazione">
  <img src="docs/screenshots/typing.png" width="19%" alt="La tastiera">
  <img src="docs/screenshots/calibration.png" width="19%" alt="Risultato della calibrazione">
  <img src="docs/screenshots/projects.png" width="19%" alt="Altri progetti">
  <img src="docs/screenshots/settings.png" width="19%" alt="Impostazioni">
</p>

Pensata per funzionare insieme a [MetroWin Launcher](https://github.com/developerkenshi-coder/MetroWinLauncher): stessi tre aspetti, stessi 20 colori, e non intralcia nella casella di ricerca del launcher. Funziona bene anche con qualsiasi altro launcher.

> **Cerchi il launcher abbinato?** MetroWin Keyboard nasce per stare accanto a **[MetroWin Launcher](https://github.com/developerkenshi-coder/MetroWinLauncher)**, una schermata Home in stile Windows Phone per Android, libera e open source: tile animate, impostazioni a pivot, gli stessi colori. Installali entrambi e il telefono avrà l'aspetto e la tastiera di un unico sistema.

## Installazione

1. Scarica l'APK dall'[ultima release](../../releases/latest) e aprilo (consenti al browser o al gestore file di installare app sconosciute).
2. Apri **MetroWin Keyboard**. Una guida all'attivazione accende la tastiera e la seleziona, accorgendosi da sola di quando ogni passaggio è fatto.
3. Il tutorial è nell'app e dentro la tastiera (i tre puntini, poi **?**).

Android 8.0 (API 26) o successivo.

## Funzioni

**Scrittura**
- Scrittura a scorrimento, tocco prolungato per accenti, cifre e simboli (durata in ms, 200 di default), doppio spazio per il punto, maiuscole automatiche, scorrimento sulla barra spaziatrice per muovere il cursore.
- Anteprima a colori del tasto che premi, come su Windows Phone.
- Correzione che **Backspace annulla** e da cui impara (intelligente: pesa dove è caduto il dito, le parole intorno e ciò che hai annullato; propone lo spazio mancante), accenti, completamento, previsione della parola successiva e della frase, parole offensive bloccate (più la tua lista).
- Impara da te, **solo sul telefono**; mai nei campi password; esportabile e cancellabile.

**Strumenti**, a un tocco dalla barra: i tre puntini aprono la cassetta degli strumenti e il **tasto rapido** porta dritto agli appunti (o a qualsiasi strumento scelto), con un puntino quando hai copiato qualcosa.
- **Appunti** con limite a scelta, elementi fissati e **immagini**.
- **Traduci** il campo, o ciò che hai appena copiato (utile per i messaggi ricevuti), tra 40 lingue. Di default Google; si possono scegliere Google Cloud, DeepL, LibreTranslate o MyMemory.
- **GIF** da una fonte a scelta: GIPHY, Tenor, Klipy o Imgur (con una tua chiave gratuita; un pulsante apre la pagina dove prenderla), una cartella del telefono o un servizio JSON che descrivi tu, con un livello dei contenuti da G a R.
- **Emoji** ed **emoticon classiche** (`:-)`, kaomoji), con i toni della pelle.
- **Digitazione vocale**, anche trascrizione continua, volendo solo sul telefono.

**Calibrazione**: nessun'altra tastiera lo fa. Scrivi qualche frase e la tastiera impara dove cadono *le tue* dita (lo scarto, la curva del pollice, quanto si disperdono i tocchi), corregge i tocchi senza spostare i tasti, li ingrandisce o riduce gli spazi se sbagli ancora, e mette ogni correzione in funzione tra una frase e l'altra, così la vedi migliorare. Anche le lettere accentate fanno parte del test. Niente resta finché non applichi.

**Correzione intelligente**: un piccolo modello che gira sul telefono corregge i refusi da dove è caduto il dito, da quanto è probabile ogni scivolone sulla tastiera, da quanto è comune la parola, dalla parola precedente e dalle correzioni annullate. Due correzioni ugualmente probabili vengono mostrate, non applicate. Propone anche lo spazio mancante.

**Backup**: un solo file con tutto ciò che l'app conserva (impostazioni, parole apprese, dizionari, appunti); ripristinalo su un altro telefono o dopo aver reinstallato con un'altra firma.

**Lingue**: 17 layout inclusi (English, Italiano, Español, Français, Deutsch, Português, Nederlands, Polski, Türkçe, Русский, Українська, Ελληνικά, Čeština, Svenska, Română, العربية, עברית). Qualsiasi altro layout è un piccolo file di testo ([docs/LAYOUTS.md](docs/LAYOUTS.md)); si può importare qualsiasi dizionario (liste di parole, di frequenza, Hunspell `.dic`). Sono incluse solo piccole liste di partenza (en, it, es, fr, de, pt): importa un dizionario completo per le previsioni migliori.

**Aspetto**: *Classic* (Windows Phone piatto), *Modern Glass*, *Modern Droid* (colori Material You da Android 12), scuro / chiaro / sistema, 20 colori di Windows Phone o uno tuo, nero puro, una foto dietro i tasti (il suo colore diventa l'accento) e, volendo, una tastiera che segue il colore dell'app sotto.

**Dimensioni**: anteprima dal vivo, altezza dei tasti (verticale e orizzontale), larghezza della tastiera con posizione sinistra / centro / destra, spazio tra i tasti, riga dei numeri e una calibrazione automatica che impara da qualche frase scritta dove cadono le tue dita.

**Feedback**: click in stile Windows Phone (sintetizzato, niente di Microsoft), suono di Android o un tuo file; vibrazione con intensità regolabile.

## Privacy e permessi

Nessuna analisi, pubblicità o tracker. L'elenco completo è in [PRIVACY.md](PRIVACY.md).

| Permesso | Perché |
| --- | --- |
| `RECORD_AUDIO` | Solo digitazione vocale; richiesto quando tocchi il microfono |
| `INTERNET` | Solo per GIF e traduzione, dal servizio scelto, quando lo chiedi; e per il controllo degli aggiornamenti, che interroga il repository di questa tastiera su GitHub ogni 12 ore (si può disattivare) |
| `REQUEST_INSTALL_PACKAGES` | Solo per aprire l'installer su una nuova versione che hai scelto di scaricare in *Impostazioni → Aggiornamenti* |
| `RECEIVE_BOOT_COMPLETED` | Mantiene il controllo ogni 12 ore dopo un riavvio |
| `VIBRATE` | Vibrazione dei tasti |

Backup e trasferimento tra dispositivi sono disattivati per i dati dell'app. Il servizio colore è spento finché non lo attivi nelle impostazioni di accessibilità, non legge testo e non invia nulla.

## MetroWin Launcher e altri progetti

[**MetroWin Launcher**](https://github.com/developerkenshi-coder/MetroWinLauncher) è l'altra metà del progetto: un launcher libero e open source che porta su Android la schermata Start di Windows Phone, le impostazioni a pivot e le tile animate. La tastiera lo riconosce, importa il suo file del tema e ne segue aspetto e colori (vedi [docs/LAUNCHER_INTEGRATION.md](docs/LAUNCHER_INTEGRATION.md)). Nell'app è elencato in *Info → Altri progetti*.

| | Progetto | |
| --- | --- | --- |
| Open source | [MetroWin Keyboard](https://github.com/developerkenshi-coder/MetroWin-Keyboard) | questa tastiera |
| Open source | [MetroWin Launcher](https://github.com/developerkenshi-coder/MetroWinLauncher) | il launcher in stile Windows Phone |
| Non open source | [SpesaTracker](https://play.google.com/store/apps/details?id=com.spesatracker.app) | su Google Play |

## Compilare

JDK 17 o successivo e Android SDK (piattaforma 35).

```sh
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew assembleRelease     # richiede keystore.properties; -PsignWithDebugKey=true per una build di prova
```

## Contribuire

Segnalazioni e idee sono benvenute nell'issue tracker, in italiano o inglese. Vedi [CONTRIBUTING.md](CONTRIBUTING.md), con la lista di controllo per aggiungere una funzione (e la sua pagina del tutorial).

## Licenza

GNU General Public License v3.0 o successiva ([LICENSE](LICENSE)). Font Open Sans (SIL Open Font License 1.1); vedi [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Progetto indipendente, non affiliato né approvato da Microsoft o Google; Windows Phone è un marchio di Microsoft Corporation.
