# MetroWin Keyboard

**The Windows Phone keyboard, on Android.** Made to work together with [MetroWin Launcher](https://github.com/developerkenshi-coder/MetroWinLauncher).
Free software (GPL-3.0-or-later). No ads, no analytics, no trackers: everything it learns stays on the phone.

**English** · [Italiano](#italiano)

## Features

- **Languages**: 17 layouts included (English, Italiano, Español, Français, Deutsch, Português, Nederlands, Polski, Türkçe, Русский, Українська, Ελληνικά, Čeština, Svenska, Română, العربية, עברית). Any other language or arrangement is a small text file (`.mwl`, see [docs/LAYOUTS.md](docs/LAYOUTS.md)) you import. Any dictionary can be imported: plain word lists, frequency lists (`word count`) and Hunspell `.dic` files. Only small starter lists are bundled (en, it, es, fr, de, pt); import a full dictionary for the best predictions.
- **Looks**: *Classic* (flat Windows Phone), *Modern Glass*, *Modern Droid* (Material You colours on Android 12+), dark / light / system, the 20 Windows Phone accent colours or your own, pure black for OLED.
- **Your picture**: use a photo behind the keys; its most vivid colour becomes the accent.
- **Colour follows the app** below the keyboard (chat, website…): optional, uses an accessibility service that only samples a strip of the screen above the keys (Android 11+). It reads no text.
- **Size**: key height (portrait and landscape), keyboard width with left/centre/right position, key spacing, number row on/off.
- **Typing**: swipe typing, double space → full stop, hold a key for accents and symbols (hold time in ms, default 200), automatic capitals, slide on the space bar to move the cursor.
- **Prediction**: completion, typo correction (Backspace right after gives back what you typed), accent fixing, next-word prediction, phrase completion, offensive-word blocking plus your own list. Learns from you, on the phone only; never in password fields; can be exported or erased.
- **Emoji** (by category, skin tones, flags) and **classic emoticons** (`:-)`, kaomoji).
- **Clipboard**: history saved on the phone with a limit you choose, pinned items, **pictures too**, pasted as real images where the app accepts them.
- **GIF** from a source you choose: GIPHY, Tenor (your own free API key), a folder on the phone, or any JSON service you describe.
- **Voice typing**: dictation and continuous transcription with the phone's recogniser, optionally on-device only.
- **Feedback**: Windows-Phone-style click (synthesised, nothing of Microsoft's inside), Android's sound, your own sound file; vibration with adjustable strength.

## MetroWin Launcher

The keyboard recognises the launcher (its search box gets no correction and no learning), uses the same three looks and the same 20 accent colours, can import the launcher's theme file, and listens for a theme broadcast. See [docs/LAUNCHER_INTEGRATION.md](docs/LAUNCHER_INTEGRATION.md).

## Build

JDK 17+ and the Android SDK (platform 35).

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # engine and keyboard tests on the JVM (Robolectric)
```

## Privacy and permissions

| Permission | Why |
| --- | --- |
| `RECORD_AUDIO` | Voice typing only; asked when you tap the microphone |
| `INTERNET` | Only to fetch GIFs from the source you chose, when you open the GIF panel |
| `VIBRATE` | Key vibration |

Backups and device transfer are disabled for the app's data. The optional colour service is off until you turn it on in Android's accessibility settings.

## Italiano

**La tastiera di Windows Phone su Android**, pensata per funzionare insieme a MetroWin Launcher. Software libero (GPL-3.0 o successiva), senza pubblicità né tracker: tutto ciò che impara resta sul telefono.

Funzioni: 17 lingue incluse e qualsiasi altra tramite file `.mwl`; vocabolari importabili (liste di parole, di frequenza, Hunspell); temi Classic, Modern Glass, Modern Droid; immagine personale con colore ricavato; colore che segue l'app sotto (opzionale); altezza/larghezza tasti; scorrimento sulle lettere; doppio spazio punto; tocco prolungato per i simboli (200 ms predefiniti); correzione, completamento, predizione della parola successiva e della frase; blocco parole offensive; emoji ed emoticon classiche; appunti con immagini e limite personalizzabile; GIF da fonte a scelta; digitazione vocale; suono e vibrazione con intensità.

Per compilare: `./gradlew assembleDebug`.
