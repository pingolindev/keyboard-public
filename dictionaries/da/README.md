<!-- SPDX-FileCopyrightText: 2026 Pingolin LLC -->
<!-- SPDX-License-Identifier: Apache-2.0 OR MIT -->

# Dansk (`da`)

Layout: `qwerty-da`. 178,380 words, 0 fixed corrections, 15,344 word pairs.

## Sources

- Words: AOSP LatinIME `dictionaries/da_wordlist.combined.gz` (word list version 44),
  Apache-2.0, converted to `words.tsv`, `corrections.tsv` and `metadata.tsv`.
  https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/refs/heads/main/dictionaries/da_wordlist.combined.gz
  (sha256 `a06d6ea0e51b5c5eed5fd16bacc9f392b24be0e59fd5fa43eda417969d7d4332`, fetched 2026-11-21)
- Word pairs: counted in the `dan` sentences of Tatoeba (https://tatoeba.org),
  by the Tatoeba contributors, CC BY 2.0 FR (`LICENSES/LicenseRef-CC-BY-2.0-FR.txt`).
  https://downloads.tatoeba.org/exports/per_language/dan/dan_sentences.tsv.bz2
  (sha256 `2023284d05885cde0aa18ac8d1dad104b78db6eb61dee7a6d2e703b2222800a0`, fetched 2026-11-22)

The pairs leave out sentences whose number is divisible by 100 (they test
predictions) and sentences naming Tom or Mary, Tatoeba's stock names.

## Regenerating

```sh
zcat da_wordlist.combined.gz > da_wordlist.combined
bunzip2 -k dan_sentences.tsv.bz2
cd core
cargo run --release -p pingolin-keyboard-dictc -- import-aosp da_wordlist.combined ../dictionaries/da
cargo run --release -p pingolin-keyboard-dictc -- import-pairs dan_sentences.tsv ../dictionaries/da \
    --exclude "Tom,Mary,Tom's,Mary's,Том,Мэри,Мери,Τομ,Μαίρη,תום,מרי"
```
