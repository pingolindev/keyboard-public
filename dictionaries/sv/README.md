<!-- SPDX-FileCopyrightText: 2026 Pingolin LLC -->
<!-- SPDX-License-Identifier: Apache-2.0 OR MIT -->

# Svenska (`sv`)

Layout: `qwerty-sv`. 196,739 words, 0 fixed corrections, 11,741 word pairs.

## Sources

- Words: AOSP LatinIME `dictionaries/sv_wordlist.combined.gz` (word list version 54),
  Apache-2.0, converted to `words.tsv`, `corrections.tsv` and `metadata.tsv`.
  https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/refs/heads/main/dictionaries/sv_wordlist.combined.gz
  (sha256 `a8c8aa7d1c8dd65b331fcfb4baf739dddb87b146a5fe737ee0ac218686e379bb`, fetched 2026-11-21)
- Word pairs: counted in the `swe` sentences of Tatoeba (https://tatoeba.org),
  by the Tatoeba contributors, CC BY 2.0 FR (`LICENSES/LicenseRef-CC-BY-2.0-FR.txt`).
  https://downloads.tatoeba.org/exports/per_language/swe/swe_sentences.tsv.bz2
  (sha256 `c9a3b4f92e995ca9da40244aec017d5e6c598e4f8ed2ec3255f52abc2a4597db`, fetched 2026-11-22)

The pairs leave out sentences whose number is divisible by 100 (they test
predictions) and sentences naming Tom or Mary, Tatoeba's stock names.

## Regenerating

```sh
zcat sv_wordlist.combined.gz > sv_wordlist.combined
bunzip2 -k swe_sentences.tsv.bz2
cd core
cargo run --release -p pingolin-keyboard-dictc -- import-aosp sv_wordlist.combined ../dictionaries/sv
cargo run --release -p pingolin-keyboard-dictc -- import-pairs swe_sentences.tsv ../dictionaries/sv \
    --exclude "Tom,Mary,Tom's,Mary's,Том,Мэри,Мери,Τομ,Μαίρη,תום,מרי"
```
