# Changelog

## 0.3.0

- **Corrections that work:** full word lists for Italian, English, Spanish, French and German (about 40,000 words each) are built in, so a mistyped word now gets a correction. "perche" becomes "perché", "dont" becomes "don't", "cose" also offers "cos'è". The lists are rebuilt with `tools/build_dictionaries.py`. *Settings → Languages* has a new entry listing where to find more dictionaries to import.
- **Next-word prediction** is useful from the first day: common word pairs of each language are built in (`tools/build_bigrams.py`) and the keyboard also learns three-word habits. Everything stays on the phone.
- **Long press:** the first choice is now the accented letter (hold `a`, let go: `à`); the plain letter follows it in the bar. This reverses the 0.2.0 behaviour.
- **Suggestions are never cut with "…":** a long word gets more room and, if needed, smaller type.
- **Clipboard:** an item deleted with the X (or cleared) no longer comes back when the keyboard is opened again.
- **Emoji:** the panel is as tall as the keyboard and all categories are one list that scrolls. A magnifier searches emoji by name (*it, en, es, fr, de*, built from emojibase).
- **Smart emoji search:** type `:dog` and the emoji appear in the suggestion bar, where they slide sideways. On by default; *Settings → Suggestions*.
- **Voice:** what is heard is written in the text field as you speak instead of in the bar. Pauses of up to about four seconds no longer end the dictation, and "keep listening" is on by default.


## 0.2.2

- **GIF:** Klipy and Imgur join GIPHY and Tenor. Picking a service shows a button that opens the page where its free key is obtained.
- **GIF content level:** choose from G to R (*Settings → GIF*); it is passed to each service in its own terms. Imgur has only a "mature" flag, shown at R.
- **Size and layout:** a live preview of the keyboard at the top, which changes as you move the sliders, and a button to try the real keyboard.

## 0.2.1

- **Translation:** with DeepL, *Settings → Translation* shows how many characters are used and left in your plan (only DeepL offers this).
- **The globe key can be hidden** (*Settings → Size and layout*) to widen the space bar. With several languages on, hold the space bar to change language.

## 0.2.0

- **Fix:** the bottom row of keys was hidden behind the navigation bar (or the launcher's own navigation bar) on Android 15. The keyboard now keeps clear of it, with an "extra space at the bottom" setting as a safety net.
- **Fix:** holding a key for a little too long typed the accent instead of the letter. The long-press bar now starts on the letter itself.
- **Fix:** the word being typed could be cut in two by a late cursor update from the app.
- **New look:** big light Open Sans type, a Windows Phone style app bar (three dots, quick button), a coloured preview of the key you press, flat Metro settings screens with a big lower-case title.
- **Quick button** on the bar, straight to the clipboard (or any tool you choose), with a dot when something new was copied.
- **Translate** the text of the field, or what you just copied, from one language to another. Google by default; Google Cloud, DeepL, LibreTranslate and MyMemory can be chosen instead.
- **Tutorial** in the app and inside the keyboard (the "?" in the toolbox), and a **guided set-up** on first start that notices by itself when each step is done.
- The number row is off by default (digits are on a long press of the top row, or on "?123"); it can be turned on in the settings.
- Release builds are no longer debuggable.

## 0.1.0

First version: 17 layouts, importable dictionaries and layouts, swipe typing, correction with undo, next-word and phrase prediction, offensive-word blocking, local-only learning, emoji and classic emoticons, clipboard history with pictures, GIF from a source you choose, voice typing, Classic / Modern Glass / Modern Droid, picture background, optional colour-follows-app, MetroWin Launcher integration.
