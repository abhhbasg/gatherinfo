#!/usr/bin/env python3
"""Builds app/src/main/assets/bigrams/{it,en,es,fr,de}.txt: which word usually follows which, so the next-word bar is useful on a
fresh install. One line per word: "previous<TAB>word<TAB>count<TAB>word<TAB>count...". "^" is the start of a sentence.

Source: Tatoeba sentences (https://tatoeba.org), CC BY 2.0 FR (see THIRD_PARTY_NOTICES.md). Only words that are in the language's
dictionary are kept as followers. Run after tools/build_dictionaries.py:  python3 tools/build_bigrams.py   (needs the network, ~60 MB)
"""
import bz2
import collections
import pathlib
import re
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent
ASSETS = ROOT.parent / "app/src/main/assets"
CACHE = pathlib.Path("/tmp/metrowin-tatoeba")
URL = "https://downloads.tatoeba.org/exports/per_language/{t}/{t}_sentences.tsv.bz2"
LANGS = {"it": "ita", "en": "eng", "es": "spa", "fr": "fra", "de": "deu"}
BREAK = {"it", "fr"}  # the keyboard ends a word at an apostrophe in these languages (apostrophe=break in the layout)
NAMES = {"tom", "mary", "maria", "john", "marie", "marija"}  # Tatoeba is full of sentences about Tom and Mary
MAX_PREVIOUS = 12_000
FOLLOWERS = 4
WORD = re.compile(r"[^\W\d_]+(?:'[^\W\d_]+)*")


def sentences(t):
    CACHE.mkdir(exist_ok=True)
    f = CACHE / f"{t}.tsv.bz2"
    if not f.exists():
        urllib.request.urlretrieve(URL.format(t=t), f)
    with bz2.open(f, "rt", encoding="utf-8") as r:
        for line in r:
            parts = line.rstrip("\n").split("\t")
            if len(parts) == 3:
                yield parts[2]


def build(lang, t):
    known = {l.split("\t")[0] for l in (ASSETS / "dictionaries" / f"{lang}.txt").read_text(encoding="utf-8").splitlines()}
    pairs = collections.defaultdict(collections.Counter)
    for s in sentences(t):
        s = s.replace("’", "'").lower()
        if lang in BREAK:
            s = s.replace("'", " ")
        found = WORD.findall(s)
        if NAMES.intersection(found):
            continue
        words = ["^"] + found
        for a, b in zip(words, words[1:]):
            if b in known:
                pairs[a][b] += 1
    ranked = sorted(pairs.items(), key=lambda kv: -sum(kv[1].values()))
    out = []
    for prev, followers in ranked[:MAX_PREVIOUS]:
        top = [(w, n) for w, n in followers.most_common(FOLLOWERS) if n >= 2]
        if len(top) > 1:  # a few bulk-generated sentence families would otherwise win everywhere ("a" -> "costruire")
            top[0] = (top[0][0], min(top[0][1], 2 * top[1][1]))
        if sum(followers.values()) >= 5 and top:
            out.append(prev + "".join(f"\t{w}\t{n}" for w, n in top))
    d = ASSETS / "bigrams"
    d.mkdir(exist_ok=True)
    (d / f"{lang}.txt").write_text("\n".join(out) + "\n", encoding="utf-8")
    print(lang, len(out))


if __name__ == "__main__":
    for l, t in LANGS.items():
        build(l, t)
