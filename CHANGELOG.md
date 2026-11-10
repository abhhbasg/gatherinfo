# Changelog

## Unreleased

- **Chinese:** two new layouts, 简体中文 (full Pinyin) and 简体中文 (双拼) (Microsoft Shuangpin: two keys a syllable, `ing` on a `;` key added to the second row). The letters stay in the field while they are typed (for Shuangpin, the Pinyin they stand for) and the bar offers the Chinese words, in a row that slides sideways; the arrow at its end shows every word in the place of the keys. Space takes the first, a tap takes any other and the rest of the letters stays to be chosen, Enter keeps the letters.
  - The first word offered is usually the whole sentence: a beam search weighs how common each word is and how well each character follows the one before, so "nihaoma" is 你好吗 and not 你号码, and "wojintianhengaox" already reads 我今天很高兴. On 51 everyday sentences typed without a pause, 49 come first (`PinyinQualityTest`).
  - It reads words from their first letters ("zhongg", or just the initials "zg", offer 中国), `v` for `ü`, the apostrophe where the syllables must be split ("xi'an") and, if switched on in *Settings → Suggestions*, fuzzy Pinyin (zh/z, ch/c, sh/s, n/l).
  - After a choice the bar offers what usually comes next (中国 → 人, 人民…), and learns what the user writes after what.
  - The comma and full stop keys type `，` and `。` and the symbol pages hold Chinese punctuation (hold a sign for the plain one). The words you choose come first next time and a phrase made of several choices is learned as a word, on the phone only, never in password fields; *Forget everything learned* and the backup cover it.
  - About 170,000 words and readings are built in, every character of the Table of General Standard Chinese Characters with each of its readings (`tools/build_pinyin.py`, from rime-ice, GPL-3.0; wordfreq, CC BY-SA 4.0 data; Pinyin2Hanzi, MIT). Calibration sentences in Pinyin are included.
- **The Windows Phone bar:** the suggestion bar holds only words, as on Windows Phone; the toolbox (clipboard, translate, GIF, emoji, voice, help, settings) opens by holding the smiley key and fills the bar. *Settings → Typing → Buttons on the suggestion bar* puts the three dots and the quick button back.
- **The app in Chinese:** settings, set-up, tutorial and panels are translated into Simplified Chinese (`values-zh-rCN`), and there is a Chinese README.
- **Closer to Windows Phone:**
  - every letter key has the same width, so the second row of letters is set in by half a key instead of having wider keys, and shift and backspace take the room their row leaves; the room beside a row that is set in still types its first and last letter;
  - the key to the digits and symbols says `&123` and the one back to the letters `abcd`, as on Windows Phone (it was `?123` / `ABC`, the Android labels);
  - the symbol pages are in the Windows Phone order: `@ # $ % & ( ) - \` and `! ; : ' " ? /` on the first, the brackets, `+ = * ^` and the rest behind the arrow, which is now drawn as an arrow;
  - the language key shows the language in three letters (`ENU`, `ITA`, `CHS`…) instead of a globe. A layout file can set them with `label=`.

## 26.1.2S

- **Landscape:** with the three-button navigation the bottom row of keys is no longer covered by the strip where the system draws its keyboard buttons. The calibration is usable on its side: the title makes room, so the sentence stays above the keyboard.
- **Panels:** the word suggestions no longer stay above an open GIF, clipboard or translation panel.
- **Toolbox:** it no longer repeats the tool that the quick button already offers.
- **Pictures:** the three looks (Classic, Modern Glass, Modern Droid) are shown as whole portrait screens in the README and in *About → Other projects*.

## 26.1S

- **Updates:** *Settings → Updates* looks for a new version every 12 hours (switch it off, or press *Check now*) and only in this keyboard's own releases on GitHub; nothing about you is sent. When one is found a blue tile appears at the top of *Start*; the screen shows the changelog since your version and downloads and installs it. The file is checked against the release's `SHA256SUMS`, and Android refuses it unless it is signed with the same key as the installed app.
- **Windows Phone home:** the settings open on a pivot, like MetroWin Launcher: large light titles side by side (*start*, *settings*, *about*), the next one peeking in at the edge, pages that slide with the finger. Entries tilt under the finger.
- **Other projects:** *About → Other projects* lists the same author's apps with a description and screenshots, open source (this keyboard, MetroWin Launcher) and not (SpesaTracker).
- **Backup and restore:** *Settings → Backup and restore* saves everything the app keeps (settings, learned words, imported dictionaries and layouts, clipboard, picture, custom sound) in one zip file and puts it back, on this phone or another, or after reinstalling the app with another signature. A file is checked before anything is replaced.
- **Automatic calibration:** type a few sentences and the keyboard learns where your fingers land. It corrects your touches without moving the keys (an offset that follows where on the screen you tap, including the arc of your thumb) and, if you still miss, makes the keys taller or the gaps smaller. The fixes go in between one sentence and the next, so the keyboard improves while you type the test; accented letters are asked for too (hold the key, pick the accent). Sentences for all 17 layouts. A blue tile at the top of *Start* opens it; *Settings → Size and layout → Automatic calibration*.
- **Smart correction:** a typo is fixed with a small model that runs on the phone, not just by spelling distance. It weighs where your finger landed (a tap between two keys makes both likely, and the spread of your taps comes from the calibration), how likely each slip is on the keyboard map (next key, accent, doubled letter, swapped letters), how common the word is, the word before it and which corrections you undid (an undone correction is not made again). Two fixes that are equally likely are shown, not applied. A missing space is offered ("thankyou" → "thank you"). *Settings → Suggestions → Smart correction*.
- **Smaller app:** the release is shrunk and optimised (about a third smaller).
- **Fixes:** on Android 8.x the globe key and the language switch no longer call a method that only exists from Android 9; the pivot titles tell TalkBack they are tabs and which one is selected.
- **Version name** now follows MetroWin Launcher (year, month, stable): `26.1S`.

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
