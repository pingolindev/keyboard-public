<!-- SPDX-FileCopyrightText: 2026 Pingolin LLC -->
<!-- SPDX-License-Identifier: Apache-2.0 OR MIT -->

# Deutsch (`de`)

Layout: `qwertz-de`. 205,888 words, 0 fixed corrections, 87,323 word pairs.

## Sources

- Words: AOSP LatinIME `dictionaries/de_wordlist.combined.gz` (word list version 54),
  Apache-2.0, converted to `words.tsv`, `corrections.tsv` and `metadata.tsv`.
  https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/refs/heads/main/dictionaries/de_wordlist.combined.gz
  (sha256 `07ae553cd55f9901412065bad7617379908ed5f2a701d226ffde85a9b8ffd5c0`, fetched 2026-11-21)
- Word pairs: counted in the `deu` sentences of Tatoeba (https://tatoeba.org),
  by the Tatoeba contributors, CC BY 2.0 FR (`LICENSES/LicenseRef-CC-BY-2.0-FR.txt`).
  https://downloads.tatoeba.org/exports/per_language/deu/deu_sentences.tsv.bz2
  (sha256 `e16c57e695bfeccccb455c305d262c5cbe2aa74a5c1e1edb725d53cf907309ac`, fetched 2026-11-22)

The pairs leave out sentences whose number is divisible by 100 (they test
predictions) and sentences naming Tom or Mary, Tatoeba's stock names.

## Regenerating

```sh
zcat de_wordlist.combined.gz > de_wordlist.combined
bunzip2 -k deu_sentences.tsv.bz2
cd core
cargo run --release -p pingolin-keyboard-dictc -- import-aosp de_wordlist.combined ../dictionaries/de
cargo run --release -p pingolin-keyboard-dictc -- import-pairs deu_sentences.tsv ../dictionaries/de \
    --exclude "Tom,Mary,Tom's,Mary's,Том,Мэри,Мери,Τομ,Μαίρη,תום,מרי"
```
