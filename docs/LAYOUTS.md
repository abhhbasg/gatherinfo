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
```

Up to four rows of up to 14 keys; keys are separated by spaces, and a key can be more than one character (Arabic "لا"). Shift, backspace and the bottom row are added by the keyboard. Import a dictionary for the same language so it can predict: one word per line, `word count` lines, or a Hunspell `.dic`.
