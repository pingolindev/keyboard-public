<!-- SPDX-FileCopyrightText: 2026 Pingolin LLC -->
<!-- SPDX-License-Identifier: Apache-2.0 OR MIT -->

# Suomi (`fi`)

Layout: `qwerty-sv`. 223,275 words, 0 fixed corrections, 28,810 word pairs.

## Sources

- Words: AOSP LatinIME `dictionaries/fi_wordlist.combined.gz` (word list version 44),
  Apache-2.0, converted to `words.tsv`, `corrections.tsv` and `metadata.tsv`.
  https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/refs/heads/main/dictionaries/fi_wordlist.combined.gz
  (sha256 `df70a90f067026cab3cd9eb27482e954f3cd0bb194ffbf6aff342efcd56fa274`, fetched 2026-11-21)
- Word pairs: counted in the `fin` sentences of Tatoeba (https://tatoeba.org),
  by the Tatoeba contributors, CC BY 2.0 FR (`LICENSES/LicenseRef-CC-BY-2.0-FR.txt`).
  https://downloads.tatoeba.org/exports/per_language/fin/fin_sentences.tsv.bz2
  (sha256 `841aece9ce0a8f28b1c09d3c848a6c5a76a713c19c433a0e390adce7d4e4bd6e`, fetched 2026-11-22)

The pairs leave out sentences whose number is divisible by 100 (they test
predictions) and sentences naming Tom or Mary, Tatoeba's stock names.

## Regenerating

```sh
zcat fi_wordlist.combined.gz > fi_wordlist.combined
bunzip2 -k fin_sentences.tsv.bz2
cd core
cargo run --release -p pingolin-keyboard-dictc -- import-aosp fi_wordlist.combined ../dictionaries/fi
cargo run --release -p pingolin-keyboard-dictc -- import-pairs fin_sentences.tsv ../dictionaries/fi \
    --exclude "Tom,Mary,Tom's,Mary's,Том,Мэри,Мери,Τομ,Μαίρη,תום,מרי"
```
