<!-- SPDX-FileCopyrightText: 2026 Pingolin LLC -->
<!-- SPDX-License-Identifier: Apache-2.0 OR MIT -->

# Lietuvių (`lt`)

Layout: `letters`. 198,074 words, 0 fixed corrections, 25,622 word pairs.

## Sources

- Words: AOSP LatinIME `dictionaries/lt_wordlist.combined.gz` (word list version 44),
  Apache-2.0, converted to `words.tsv`, `corrections.tsv` and `metadata.tsv`.
  https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/refs/heads/main/dictionaries/lt_wordlist.combined.gz
  (sha256 `63b296c5cf7a1a1da601c92933043caa402ae25c3845ddb705ccba0c902315b7`, fetched 2026-11-21)
- Word pairs: counted in the `lit` sentences of Tatoeba (https://tatoeba.org),
  by the Tatoeba contributors, CC BY 2.0 FR (`LICENSES/LicenseRef-CC-BY-2.0-FR.txt`).
  https://downloads.tatoeba.org/exports/per_language/lit/lit_sentences.tsv.bz2
  (sha256 `b6391f5117c3cc789de5835851d08fa3327704c9d6e4639fe9cb5ffdbaf41d81`, fetched 2026-11-22)

The pairs leave out sentences whose number is divisible by 100 (they test
predictions) and sentences naming Tom or Mary, Tatoeba's stock names.

## Regenerating

```sh
zcat lt_wordlist.combined.gz > lt_wordlist.combined
bunzip2 -k lit_sentences.tsv.bz2
cd core
cargo run --release -p pingolin-keyboard-dictc -- import-aosp lt_wordlist.combined ../dictionaries/lt
cargo run --release -p pingolin-keyboard-dictc -- import-pairs lit_sentences.tsv ../dictionaries/lt \
    --exclude "Tom,Mary,Tom's,Mary's,Том,Мэри,Мери,Τομ,Μαίρη,תום,מרי"
```
