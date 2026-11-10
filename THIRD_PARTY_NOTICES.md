# Third party notices

## Open Sans (fonts)

`app/src/main/res/font/opensans_*.ttf` are Open Sans Light, Regular and SemiBold (Latin subset), copyright 2020 The Open Sans Project Authors,
used under the SIL Open Font License 1.1 (see [`licenses/OFL-1.1.txt`](licenses/OFL-1.1.txt)). They stand in for Segoe WP, the Windows Phone typeface,
which is proprietary and cannot be bundled. The files are the ones MetroWin Launcher ships.

## Word lists

`app/src/main/assets/dictionaries/{it,en,es,fr,de}.txt` are built by [`tools/build_dictionaries.py`](tools/build_dictionaries.py) from the 2018 lists of
[hermitdave/FrequencyWords](https://github.com/hermitdave/FrequencyWords), made from OpenSubtitles and licensed CC BY-SA 4.0
(<https://creativecommons.org/licenses/by-sa/4.0/>), which one-way allows use in GPL-3.0 works. The files were cleaned (see the script) and
a few words with apostrophes were added. This attribution is the credit CC BY-SA asks for.

`app/src/main/assets/bigrams/*.txt` count which word follows which in the sentences of [Tatoeba](https://tatoeba.org), licensed CC BY 2.0 FR
(<https://creativecommons.org/licenses/by/2.0/fr/>), built by [`tools/build_bigrams.py`](tools/build_bigrams.py).

`app/src/main/assets/emoji/*.tsv` hold emoji names and keywords from [emojibase-data](https://emojibase.dev) (MIT), whose texts come from the Unicode
CLDR (Unicode License v3), built by [`tools/build_emoji_index.py`](tools/build_emoji_index.py).

`app/src/main/assets/pinyin/zh.txt`, the Chinese words and how they are read, is built by [`tools/build_pinyin.py`](tools/build_pinyin.py) from the word
list of [jieba](https://github.com/fxsjy/jieba) (`dict.txt`, copyright 2013 Sun Junyi, MIT License) for the words and their counts, and from
[pypinyin](https://github.com/mozillazg/python-pinyin) (copyright 2016 mozillazg, 闲耘, MIT License; its data comes from
[pinyin-data](https://github.com/mozillazg/pinyin-data) and [phrase-pinyin-data](https://github.com/mozillazg/phrase-pinyin-data), both MIT) for the readings.

## Libraries

AndroidX (core, appcompat, preference, recyclerview), Apache License 2.0. Tests only: JUnit (EPL 1.0), Robolectric (MIT), org.json (JSON License).

## Services (never contacted unless you use them)

GIPHY, Tenor, Klipy, Imgur, Google Translate, Google Cloud Translation, DeepL, LibreTranslate and MyMemory are third-party services with their own terms; the keyboard only calls the one you choose, when you ask.

Windows Phone is a trademark of Microsoft Corporation. MetroWin Keyboard is an independent project, not affiliated with or endorsed by Microsoft or Google.
