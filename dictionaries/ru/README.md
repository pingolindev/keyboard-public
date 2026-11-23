<!-- SPDX-FileCopyrightText: 2026 Pingolin LLC -->
<!-- SPDX-License-Identifier: Apache-2.0 OR MIT -->

# Русский (`ru`)

Layout: `russian`. 220,485 words, 7 fixed corrections, 122,613 word pairs.

## Sources

- Words: AOSP LatinIME `dictionaries/ru_wordlist.combined.gz` (word list version 54),
  Apache-2.0, converted to `words.tsv`, `corrections.tsv` and `metadata.tsv`.
  https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/refs/heads/main/dictionaries/ru_wordlist.combined.gz
  (sha256 `8ef73a234018d23f5ca9ccc6cb65adecfd5667e87a8b6c6f1ed13ab86abad16e`, fetched 2026-11-21)
- Word pairs: counted in the `rus` sentences of Tatoeba (https://tatoeba.org),
  by the Tatoeba contributors, CC BY 2.0 FR (`LICENSES/LicenseRef-CC-BY-2.0-FR.txt`).
  https://downloads.tatoeba.org/exports/per_language/rus/rus_sentences.tsv.bz2
  (sha256 `5dc86aaa70b4a8aa18eff17690394f7bddb5f2437cd22878d5f694fbaf1dab55`, fetched 2026-11-22)

`corrections.tsv` keeps only the AOSP corrections of misspelled words and
contractions.

The pairs leave out sentences whose number is divisible by 100 (they test
predictions) and sentences naming Tom or Mary, Tatoeba's stock names.

## Regenerating

```sh
zcat ru_wordlist.combined.gz > ru_wordlist.combined
bunzip2 -k rus_sentences.tsv.bz2
cd core
cargo run --release -p pingolin-keyboard-dictc -- import-aosp ru_wordlist.combined ../dictionaries/ru
cargo run --release -p pingolin-keyboard-dictc -- import-pairs rus_sentences.tsv ../dictionaries/ru \
    --exclude "Tom,Mary,Tom's,Mary's,Том,Мэри,Мери,Τομ,Μαίρη,תום,מרי"
```
