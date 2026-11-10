#!/usr/bin/env python3
"""Builds app/src/main/assets/pinyin/zh.txt: the Simplified Chinese word list of the Pinyin keyboard.

One line per reading, most frequent first: "word<TAB>pin'yin<TAB>count" (syllables split by an apostrophe, "v" for "ü").

Sources (both MIT, see THIRD_PARTY_NOTICES.md):
  - jieba's dict.txt for the words and how often each is used;
  - pypinyin for how each word and character is read (it knows the readings that depend on the word: 重庆 is "chong'qing").
tools/dictionaries/extra_zh.txt adds the everyday words that a list made from written text holds too rarely (你好, 谢谢, 晚安).

Only words written with the characters of GB 2312 (Simplified Chinese) are kept. A character with several readings is listed once
for each, the less usual ones with a smaller count, so that 了 is found under both "le" and "liao".

Run: pip install jieba pypinyin && python3 tools/build_pinyin.py
"""
import os
import pathlib
import re

import jieba
from pypinyin import Style, lazy_pinyin, pinyin

ROOT = pathlib.Path(__file__).resolve().parent
OUT = ROOT.parent / "app/src/main/assets/pinyin/zh.txt"
MAX_WORDS = 60000          # words of two characters or more
MAX_LEN = 5                # characters in a word
MIN_COUNT = 30
OTHER_READING = 8          # a less usual reading of a character counts this many times less
SYLLABLE = re.compile(r"^[a-z]+$")


def simplified(word):
    try:
        word.encode("gb2312")
    except UnicodeEncodeError:
        return False
    return all("一" <= ch <= "龥" for ch in word)


def syllables(word):
    out = lazy_pinyin(word, style=Style.NORMAL, errors="ignore")
    return out if len(out) == len(word) and all(SYLLABLE.match(s) for s in out) else None


def main():
    source = os.path.join(os.path.dirname(jieba.__file__), "dict.txt")
    chars, words = {}, {}
    with open(source, encoding="utf-8") as f:
        for line in f:
            parts = line.split()
            if len(parts) < 2 or not parts[1].isdigit():
                continue
            word, count = parts[0], int(parts[1])
            if not simplified(word):
                continue
            if len(word) == 1:
                chars[word] = max(chars.get(word, 0), count)
            elif len(word) <= MAX_LEN and count >= MIN_COUNT:
                words[word] = max(words.get(word, 0), count)

    extra = ROOT / "dictionaries/extra_zh.txt"
    for line in extra.read_text(encoding="utf-8").splitlines():
        parts = line.split()
        if len(parts) == 2 and not line.startswith("#") and simplified(parts[0]) and len(parts[0]) > 1:
            words[parts[0]] = max(words.get(parts[0], 0), int(parts[1]))

    # Every character of GB 2312 can be typed, also the ones the frequency list does not hold.
    for hi in range(0xB0, 0xF8):
        for lo in range(0xA1, 0xFF):
            try:
                ch = bytes([hi, lo]).decode("gb2312")
            except UnicodeDecodeError:
                continue
            chars.setdefault(ch, 1)

    lines = []
    for ch, count in chars.items():
        readings = [r for r in pinyin(ch, style=Style.NORMAL, heteronym=True)[0] if SYLLABLE.match(r)]
        for i, reading in enumerate(dict.fromkeys(readings)):
            lines.append((ch, reading, count if i == 0 else max(1, count // OTHER_READING)))
    kept = sorted(words.items(), key=lambda kv: -kv[1])[:MAX_WORDS]
    for word, count in kept:
        s = syllables(word)
        if s:
            lines.append((word, "'".join(s), count))

    lines.sort(key=lambda t: (-t[2], t[1], t[0]))
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        for word, reading, count in lines:
            f.write(f"{word}\t{reading}\t{count}\n")
    print(f"{OUT}: {len(lines)} lines, {OUT.stat().st_size // 1024} KB")


if __name__ == "__main__":
    main()
