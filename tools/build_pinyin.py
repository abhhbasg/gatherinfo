#!/usr/bin/env python3
"""Builds the Chinese data of the Pinyin keyboard:

  app/src/main/assets/pinyin/zh.txt   one line per reading, "word<TAB>pin'yin<TAB>count" ("v" for "ü")
  app/src/main/assets/pinyin/zh.pairs which character tends to follow which: "ab<TAB>score", the score being ten times
                                      ln(P(b after a) / P(b)), so that a sentence reads as people write it

Counts are per billion words.

Sources (see THIRD_PARTY_NOTICES.md):
  - rime-ice (https://github.com/iDvel/rime-ice, GPL-3.0): cn_dicts/8105.dict.yaml for every character of the
    Table of General Standard Chinese Characters with each of its readings, and cn_dicts/base.dict.yaml for the words, all
    read by hand-checked Pinyin (重庆 is "chong qing", 银行 "yin hang").
  - wordfreq (https://github.com/rspeer/wordfreq, Apache-2.0, data CC BY-SA 4.0): how common each word is, measured on
    subtitles, Wikipedia and web text together, so the words of a chat are not drowned by those of the news. rime-ice's
    own weights, which lean on news and law, only count for the words wordfreq does not have (with a ceiling), and to share
    a character's count between its readings (了 is "le" far more than "liao").
  - Pinyin2Hanzi (https://github.com/letiantian/Pinyin2Hanzi, MIT): hmm_transition.json, how often a character follows another.
  - tools/dictionaries/extra_zh.txt: everyday phrases a word list keeps too rarely (你好吗, 辛苦了).

Run: pip install Pinyin2Hanzi wordfreq && python3 tools/build_pinyin.py   (downloads about 17 MB from GitHub)
"""
import json
import math
import os
import pathlib
import re
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent
OUT = ROOT.parent / "app/src/main/assets/pinyin"
CACHE = pathlib.Path(os.environ.get("TMPDIR", "/tmp")) / "metrowin-pinyin"
RIME = "https://raw.githubusercontent.com/iDvel/rime-ice/{rev}/cn_dicts/{name}"
RIME_REV = os.environ.get("RIME_ICE_REV", "main")
MAX_WORDS = 160_000        # words of two characters or more
MAX_LEN = 6                # characters in a word
MIN_PAIR = 1e-4            # the least P(b after a) kept
UNKNOWN_CEILING = 3000     # per billion: a word wordfreq does not know is not taken to be commoner than this
SYLLABLE = re.compile(r"^[a-z]+$")


def fetch(name):
    CACHE.mkdir(parents=True, exist_ok=True)
    f = CACHE / name
    if not f.exists():
        urllib.request.urlretrieve(RIME.format(rev=RIME_REV, name=name), f)
    return f


def rime(name):
    """Rows of a Rime dictionary: (text, syllables, weight)."""
    body = False
    with open(fetch(name), encoding="utf-8") as f:
        for line in f:
            if line.startswith("..."):
                body = True
                continue
            if not body or line.startswith("#") or not line.strip():
                continue
            p = line.rstrip("\n").split("\t")
            if len(p) < 2:
                continue
            syllables = p[1].split(" ")
            if len(syllables) != len(p[0]) or not all(SYLLABLE.match(s) for s in syllables):
                continue
            yield p[0], syllables, int(p[2]) if len(p) > 2 and p[2].isdigit() else 0


def han(word):
    return all("㐀" <= ch <= "鿿" for ch in word)


def main():
    import wordfreq
    freq = wordfreq.get_frequency_dict("zh")

    rime_chars = {}
    for ch, (reading,), weight in rime("8105.dict.yaml"):
        rime_chars[(ch, reading)] = max(rime_chars.get((ch, reading), 0), weight, 1)
    known = {ch for ch, _ in rime_chars}
    top_reading = {}
    for (ch, _), weight in rime_chars.items():
        top_reading[ch] = max(top_reading.get(ch, 0), weight)

    rime_words = {}
    for word, syllables, weight in rime("base.dict.yaml"):
        if 2 <= len(word) <= MAX_LEN and han(word) and all(ch in known for ch in word):
            key = (word, "'".join(syllables))
            rime_words[key] = max(rime_words.get(key, 0), weight, 1)

    # rime-ice's weights brought to wordfreq's scale: the median ratio on the words both know.
    ratios = sorted(freq[w] * 1e9 / c for (w, _), c in rime_words.items() if w in freq)
    scale = ratios[len(ratios) // 2]

    def count(word, rime_weight, share=1.0):
        f = freq.get(word)
        if f:
            return max(1, round(f * 1e9 * share))
        return max(1, min(UNKNOWN_CEILING, round(rime_weight * scale * share)))

    chars = {(ch, r): count(ch, w, w / top_reading[ch]) for (ch, r), w in rime_chars.items()}
    words = {key: count(key[0], w) for key, w in rime_words.items()}
    kept = dict(sorted(words.items(), key=lambda kv: -kv[1])[:MAX_WORDS])

    # Everyday phrases: read with the readings the word list has for their characters, the most common reading first.
    best_reading = {}
    for (ch, reading), weight in rime_chars.items():
        if weight > best_reading.get(ch, ("", -1))[1]:
            best_reading[ch] = (reading, weight)
    readings = {w: r for (w, r) in rime_words}
    for line in (ROOT / "dictionaries/extra_zh.txt").read_text(encoding="utf-8").splitlines():
        p = line.split()
        if len(p) != 2 or line.startswith("#") or not han(p[0]) or len(p[0]) < 2:
            continue
        word, n = p[0], int(p[1])
        reading = readings.get(word) or "'".join(best_reading[ch][0] for ch in word if ch in best_reading)
        if reading.count("'") + 1 != len(word):
            continue
        kept[(word, reading)] = max(kept.get((word, reading), 0), n)

    lines = [(w, r, c) for (w, r), c in chars.items()] + [(w, r, c) for (w, r), c in kept.items()]
    lines.sort(key=lambda t: (-t[2], t[1], t[0]))
    OUT.mkdir(parents=True, exist_ok=True)
    with open(OUT / "zh.txt", "w", encoding="utf-8", newline="\n") as f:
        for word, reading, count in lines:
            f.write(f"{word}\t{reading}\t{count}\n")

    # How much likelier a character is after another than on its own.
    import Pinyin2Hanzi
    data = pathlib.Path(Pinyin2Hanzi.__file__).parent / "data/hmm_transition.json"
    trans = json.loads(data.read_text(encoding="utf-8"))["data"]
    single = {}
    for (ch, _), weight in chars.items():
        single[ch] = single.get(ch, 0) + weight
    total = sum(single.values())
    pairs = []
    for a, row in trans.items():
        if a not in single:
            continue
        for b, p in row.items():
            if b == "default" or b not in single or p < MIN_PAIR:
                continue
            score = round(10 * math.log(p / (single[b] / total)))
            pairs.append((a + b, max(-30, min(80, score))))
    pairs.sort()
    with open(OUT / "zh.pairs", "w", encoding="utf-8", newline="\n") as f:
        for ab, score in pairs:
            f.write(f"{ab}\t{score}\n")
    for name in ("zh.txt", "zh.pairs"):
        print(f"{OUT / name}: {(OUT / name).stat().st_size // 1024} KB")
    print(f"{len(lines)} readings, {len(pairs)} pairs")


if __name__ == "__main__":
    main()
