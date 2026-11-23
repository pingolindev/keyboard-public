<!-- SPDX-FileCopyrightText: 2026 Pingolin LLC -->
<!-- SPDX-License-Identifier: Apache-2.0 OR MIT -->

# Português (Brasil) (`pt-BR`)

Layout: `letters`. 170,012 words, 5 fixed corrections, 72,852 word pairs.

## Sources

- Words: AOSP LatinIME `dictionaries/pt_BR_wordlist.combined.gz` (word list version 54),
  Apache-2.0, converted to `words.tsv`, `corrections.tsv` and `metadata.tsv`.
  https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/refs/heads/main/dictionaries/pt_BR_wordlist.combined.gz
  (sha256 `f9b7c2f610eacebc782c7d24e64d8ad2620b449defbac326507dfb5702c6c48b`, fetched 2026-11-21)
- Word pairs: counted in the `por` sentences of Tatoeba (https://tatoeba.org),
  by the Tatoeba contributors, CC BY 2.0 FR (`LICENSES/LicenseRef-CC-BY-2.0-FR.txt`).
  https://downloads.tatoeba.org/exports/per_language/por/por_sentences.tsv.bz2
  (sha256 `d79dfc10fdd0959a259e282f097ea6be59dd58d3d1fe69b1559fd8499efaab7c`, fetched 2026-11-22)

`corrections.tsv` keeps only the AOSP corrections of misspelled words and
contractions.

The pairs leave out sentences whose number is divisible by 100 (they test
predictions) and sentences naming Tom or Mary, Tatoeba's stock names.

## Regenerating

```sh
zcat pt_BR_wordlist.combined.gz > pt_BR_wordlist.combined
bunzip2 -k por_sentences.tsv.bz2
cd core
cargo run --release -p pingolin-keyboard-dictc -- import-aosp pt_BR_wordlist.combined ../dictionaries/pt-BR
cargo run --release -p pingolin-keyboard-dictc -- import-pairs por_sentences.tsv ../dictionaries/pt-BR \
    --exclude "Tom,Mary,Tom's,Mary's,Том,Мэри,Мери,Τομ,Μαίρη,תום,מרי"
```
