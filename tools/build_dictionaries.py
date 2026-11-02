#!/usr/bin/env python3
"""Builds app/src/main/assets/dictionaries/{it,en,es,fr,de}.txt ("word<TAB>count", most frequent first).

Source: hermitdave/FrequencyWords, 2018 lists made from OpenSubtitles (CC BY-SA 4.0, see THIRD_PARTY_NOTICES.md).
Subtitles are noisy, so the words are cleaned: letters and apostrophes only, no stray single letters, and the spellings
that are really mistakes (Italian "perchè", "perche" without the accent, English "dont") are dropped so that they get corrected.
Run: python3 tools/build_dictionaries.py   (needs the network)
"""
import pathlib
import unicodedata
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent
OUT = ROOT.parent / "app/src/main/assets/dictionaries"
URL = "https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/{l}/{l}_50k.txt"
MIN_COUNT = 100

# Single letters that are words.
ONE_LETTER = {"it": "aeoiè", "en": "ai", "es": "aeouy", "fr": "aàyô", "de": ""}

# Fragments left by splitting at the apostrophe, and contractions without it: the subtitles hold them as words, but they are mistakes.
# The real forms ("don't", "qu'il") are in tools/dictionaries/extra_*.txt.
DROP = {
    "en": set(
        "dont im cant wont ive youre theyre thats whats isnt didnt doesnt wasnt couldnt wouldnt shouldnt hasnt havent arent werent hadnt "
        "aint theres heres youll youve weve theyve theyll wouldve couldve shouldve whos hows wheres "
        "don isn aren wasn weren hasn haven hadn wouldn couldn shouldn didn doesn ain ll ve re".split()
    ),
    "fr": set("qu jusqu lorsqu puisqu quelqu aujourd presqu".split()),
}


# Italian words that are real without the accent too (pope, goal, lighthouse, the name Sara).
REAL_WORDS = {"papa", "meta", "faro", "sara"}


def read(lang):
    with urllib.request.urlopen(URL.format(l=lang)) as r:
        for line in r.read().decode("utf-8").splitlines():
            w, _, n = line.partition(" ")
            if n.isdigit():
                yield w.replace("’", "'").lower(), int(n)


def keep(lang, w, n):
    if n < MIN_COUNT or w.startswith("'") or not all(c.isalpha() or c == "'" for c in w):
        return False
    if len(w) == 1:
        return w in ONE_LETTER[lang]
    if w in DROP.get(lang, ()) or (lang == "en" and w.endswith("'")):
        return False
    return True


def build(lang):
    words = {}
    for w, n in read(lang):
        if keep(lang, w, n):
            words[w] = words.get(w, 0) + n
    if lang == "it":
        words = fix_italian(words)
    words = drop_bare_accents(words, 0.25 if lang == "it" else 4.0, REAL_WORDS if lang == "it" else ())
    words = prune_typos(words)
    extra = ROOT / "dictionaries" / f"extra_{lang}.txt"
    for line in extra.read_text(encoding="utf-8").splitlines() if extra.exists() else []:
        w, _, n = line.partition(" ")
        if w and not w.startswith("#"):
            words[w] = max(words.get(w, 0), int(n))
    ranked = sorted(words.items(), key=lambda kv: (-kv[1], kv[0]))
    (OUT / f"{lang}.txt").write_text("".join(f"{w}\t{n}\n" for w, n in ranked), encoding="utf-8")
    print(lang, len(ranked))


def strip_accents(w):
    return "".join(c for c in unicodedata.normalize("NFD", w) if not unicodedata.combining(c))


def fix_italian(words):
    out = {}
    for w, n in words.items():
        # "perchè", "finchè", "nè", "sè": the acute accent is the right one; add the right form if the list lacks it.
        if w.endswith("chè") or w in ("nè", "sè"):
            fixed = w[:-1] + "é"
            out[fixed] = max(out.get(fixed, 0), words.get(fixed, 0), n)
            continue
        out[w] = max(out.get(w, 0), n)
    return out


def drop_bare_accents(words, ratio, keep=()):
    """Drops the bare form of a long word when the accented one is in use: "perche" next to "perché", "tambien" next to "también".
    [ratio] is how much more common than the bare form the accented one must be (0.25 for Italian, where a bare form is nearly always a slip)."""
    accented = {}
    for w, n in words.items():
        if strip_accents(w) != w:
            accented[strip_accents(w)] = max(accented.get(strip_accents(w), 0), n)
    return {w: n for w, n in words.items()
            if not (len(w) > 3 and w in accented and accented[w] >= ratio * n and w not in keep) and w not in ("piu", "gia", "puo")}


def prune_typos(words):
    """Subtitles hold typos ("tommorow"). Drops a long word when a word one edit away is at least 100 times more common."""
    keys = {}
    for w in words:
        for i in range(len(w)):
            keys.setdefault(w[:i] + w[i + 1:], []).append(w)

    def near(w):
        yield from keys.get(w, ())
        for i in range(len(w)):
            k = w[:i] + w[i + 1:]
            yield k
            yield from keys.get(k, ())
        for i in range(len(w) - 1):
            yield w[:i] + w[i + 1] + w[i] + w[i + 2:]

    return {w: n for w, n in words.items()
            if len(w) < 6 or not any(v != w and words.get(v, 0) >= 100 * n for v in near(w))}


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    for l in ("it", "en", "es", "fr", "de"):
        build(l)
