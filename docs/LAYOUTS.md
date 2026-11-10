# Adding a language: the `.mwl` layout file

A layout is a small UTF-8 text file. Import it from *Settings → Languages and dictionaries → Import a keyboard layout…*, then switch the language on.

```
# Italiano
id=it                 (letters, digits, - or _; also the id of the dictionary unless dict= is set)
name=Italiano
locale=it_IT
dict=it               (optional)
apostrophe=break      (join: "don't" is one word. break: "l'albero" is two)
row=q w e r t y u i o p
row=a s d f g h j k l
row=z x c v b n m
long.a=à á â ä        (what holding the key offers)
label=ITA             (optional: what the language key shows, three letters as on Windows Phone; default: the language's ISO code)
comma=,               (optional: what the two keys beside the space bar type; default , and .)
period=.
input=pinyin          (optional: see "Chinese" below; input=shuangpin for Microsoft Shuangpin)
```

Up to four rows of up to 14 keys; keys are separated by spaces, and a key can be more than one character (Arabic "لا"). Shift, backspace and the bottom row are added by the keyboard. Import a dictionary for the same language so it can predict: one word per line, `word count` lines, or a Hunspell `.dic`.

## Chinese

`input=pinyin` makes the letters Pinyin: they are held as the word being typed, and the bar offers the Chinese words read so (see `assets/layouts/zh.mwl`). The words come from `assets/pinyin/<dict>.txt`, lines of `word<TAB>pin'yin<TAB>count` with `v` for `ü`, built by [`tools/build_pinyin.py`](../tools/build_pinyin.py). `input=shuangpin` reads every two keys as a syllable with the Microsoft Shuangpin table (`ing` is the `;` key, so give the layout one, see `assets/layouts/zh-sp.mwl`). On such a layout the symbol pages show Chinese punctuation, and `comma=，` / `period=。` put it beside the space bar; `long.，=` sets what holding that key offers.
