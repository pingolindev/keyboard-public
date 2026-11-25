#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Pingolin LLC
# SPDX-License-Identifier: GPL-3.0-or-later
"""Writes list.tsv and names/*.tsv from Unicode's emoji list and CLDR's names.

Run by hand when Unicode or CLDR publishes new data. The build only copies the
tables into the app.

    emoji/build.py <emoji-test.txt> <cldr directory>

The tables here come from, all under the Unicode License v3:

- Emoji 18.0: https://www.unicode.org/Public/18.0.0/emoji/emoji-test.txt
  (sha256 8f3735cda1f92a779d78af67cf86066bb1f07143dc22f2ac29394d9bc57ab21a)
- CLDR 49.0.0: from https://github.com/unicode-org/cldr-json, for each language of
  LANGUAGES, in the CLDR directory as ann/<lang>.json and der/<lang>.json:
  cldr-annotations-full/annotations/<lang>/annotations.json and
  cldr-annotations-derived-full/annotationsDerived/<lang>/annotations.json

A names table starts with the most frequent words of its language, which are too
common to stand for an emoji. They come from dictionaries/ (AOSP, Apache-2.0).
"""

import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
# Our language tags (dictionaries/) and the CLDR ones their names come from, first
# to last: a name missing in one is taken from the next.
LANGUAGES = {
    "cs": ["cs"], "da": ["da"], "de": ["de"], "el": ["el"], "en-GB": ["en-GB", "en"],
    "en-US": ["en"], "es": ["es"], "fi": ["fi"], "fr": ["fr"], "he": ["he"],
    "hr": ["hr"], "it": ["it"], "lt": ["lt"], "lv": ["lv"], "nb": ["no"],
    "nl": ["nl"], "pl": ["pl"], "pt-BR": ["pt"], "pt-PT": ["pt-PT", "pt"],
    "ro": ["ro"], "ru": ["ru"], "sl": ["sl"], "sr": ["sr"], "sv": ["sv"], "tr": ["tr"],
}
GROUPS = ["Smileys & Emotion", "People & Body", "Animals & Nature", "Food & Drink",
          "Travel & Places", "Activities", "Objects", "Symbols", "Flags"]
TONES = ["\U0001F3FB", "\U0001F3FC", "\U0001F3FD", "\U0001F3FE", "\U0001F3FF"]
VS16 = "️"
# How many of a language's most frequent words count as common.
COMMON = 150
# The tables' own headers, not this file's. REUSE-IgnoreStart
UNICODE = "# SPDX-FileCopyrightText: 2026 Unicode, Inc.\n"
AOSP = "# SPDX-FileCopyrightText: The Android Open Source Project\n"
LIST_HEADER = (
    UNICODE + "# SPDX-License-Identifier: Unicode-3.0\n"
    "# Emoji in keyboard order: emoji, group (0 smileys to 8 flags), version, skin tone\n"
    "# variants (light to dark, or -). Written by emoji/build.py.\n"
)
NAMES_HEADER = (
    UNICODE + AOSP + "# SPDX-License-Identifier: Unicode-3.0 AND Apache-2.0\n"
    "# Emoji names and search words, {tag}: emoji, name, words. The first row holds common\n"
    "# words. Written by emoji/build.py.\n"
)
# REUSE-IgnoreEnd


def strip(emoji):
    return emoji.replace(VS16, "")


def read_list(path):
    """The fully qualified emoji in keyboard order, with their skin tone variants."""
    group = version = None
    rows, tones, order = [], {}, {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith("# group:"):
            group = line.split(":", 1)[1].strip()
            continue
        if not line or line.startswith("#"):
            continue
        points, rest = line.split(";", 1)
        status, comment = rest.split("#", 1)
        if status.strip() != "fully-qualified" or group not in GROUPS:
            continue
        text = "".join(chr(int(p, 16)) for p in points.split())
        version = comment.split()[1].lstrip("E")
        found = [t for t in text if t in TONES]
        if not found:
            order[strip(text)] = len(rows)
            rows.append([text, GROUPS.index(group), version, {}])
        else:
            tones.setdefault(strip("".join(c for c in text if c not in TONES)), []).append((found, text))
    # One tone for everyone in it: the five plain choices. Mixed-tone couples
    # come as the base emoji with every tone, which the picker does not offer.
    for base, variants in tones.items():
        if base not in order:
            continue
        for found, text in variants:
            if len(set(found)) == 1:
                rows[order[base]][3].setdefault(TONES.index(found[0]), text)
    return rows


def common_words(tag):
    """The most frequent words of a language, from its dictionary."""
    words = []
    path = HERE.parent / "dictionaries" / tag / "words.tsv"
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith("#"):
            continue
        fields = line.split("\t")
        if len(fields) >= 2 and fields[1].isdigit():
            words.append((int(fields[1]), fields[0]))
    words.sort(key=lambda w: -w[0])
    return [w for _, w in words[:COMMON]]


def read_annotations(path):
    if not path.exists():
        return {}
    # {"annotations": {"annotations": {...}}} or {"annotationsDerived": {...}}
    (data,) = json.loads(path.read_text(encoding="utf-8")).values()
    return data.get("annotations", {})


def main():
    test, cldr = Path(sys.argv[1]), Path(sys.argv[2])
    rows = read_list(test)
    with (HERE / "list.tsv").open("w", encoding="utf-8", newline="\n") as out:
        out.write(LIST_HEADER)
        for text, group, version, variants in rows:
            listed = " ".join(variants[i] for i in range(5)) if len(variants) == 5 else "-"
            out.write(f"{text}\t{group}\t{version}\t{listed}\n")
    for tag, sources in LANGUAGES.items():
        merged = {}
        for source in reversed(sources):
            for folder in ("der", "ann"):
                for emoji, entry in read_annotations(cldr / folder / f"{source}.json").items():
                    merged.setdefault(strip(emoji), {}).update({k: v for k, v in entry.items() if v})
        with (HERE / "names" / f"{tag}.tsv").open("w", encoding="utf-8", newline="\n") as out:
            out.write(NAMES_HEADER.format(tag=tag))
            out.write(f"=common\t\t{'|'.join(common_words(tag))}\n")
            for text, _, _, _ in rows:
                entry = merged.get(strip(text))
                if not entry:
                    continue
                name = (entry.get("tts") or [""])[0].strip().replace("\t", " ")
                seen, words = {name.casefold()}, []
                for word in entry.get("default") or []:
                    word = word.strip().replace("\t", " ")
                    if word and word.casefold() not in seen:
                        seen.add(word.casefold())
                        words.append(word)
                if name or words:
                    out.write(f"{text}\t{name}\t{'|'.join(words)}\n")
    return rows


if __name__ == "__main__":
    rows = main()
    print(len(rows), "emoji,", sum(1 for r in rows if len(r[3]) == 5), "with skin tones")
