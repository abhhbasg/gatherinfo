#!/usr/bin/env python3
"""Builds app/src/main/assets/emoji/{it,en,es,fr,de}.tsv: "emoji<TAB>name|tag|tag..." for the emoji search, in the order of Unicode.

Source: emojibase-data (https://emojibase.dev), MIT licence, whose texts come from the Unicode CLDR (Unicode licence).
Run: python3 tools/build_emoji_index.py   (needs the network)
"""
import json
import pathlib
import urllib.request

OUT = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/assets/emoji"
URL = "https://cdn.jsdelivr.net/npm/emojibase-data@15.3.2/{l}/compact.json"

if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    for lang in ("it", "en", "es", "fr", "de"):
        with urllib.request.urlopen(URL.format(l=lang)) as r:
            data = json.load(r)
        lines = []
        for e in sorted((e for e in data if "order" in e and e.get("group") != 2), key=lambda e: e["order"]):
            terms = [e["label"]] + e.get("tags", [])
            lines.append(e["unicode"] + "\t" + "|".join(t.lower().replace("|", " ").replace("\t", " ") for t in terms))
        (OUT / f"{lang}.tsv").write_text("\n".join(lines) + "\n", encoding="utf-8")
        print(lang, len(lines))
