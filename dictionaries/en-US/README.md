<!-- SPDX-FileCopyrightText: 2026 Pingolin LLC -->
<!-- SPDX-License-Identifier: Apache-2.0 OR MIT -->

# English (US) (`en-US`)

Layout: `letters`. 160,605 words, 52 fixed corrections, 137,544 word pairs.

## Sources

- Words: AOSP LatinIME `dictionaries/en_US_wordlist.combined.gz` (word list version 54),
  Apache-2.0, converted to `words.tsv`, `corrections.tsv` and `metadata.tsv`.
  https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/refs/heads/main/dictionaries/en_US_wordlist.combined.gz
  (sha256 `0f78dd455b532be169a23f233227b811fabced4b5bd7fc9c40cc05839793bcbd`, fetched 2026-11-21)
- Word pairs: counted in the `eng` sentences of Tatoeba (https://tatoeba.org),
  by the Tatoeba contributors, CC BY 2.0 FR (`LICENSES/LicenseRef-CC-BY-2.0-FR.txt`).
  https://downloads.tatoeba.org/exports/per_language/eng/eng_sentences.tsv.bz2
  (sha256 `0c4d3b18152bd6507bbf77ab690532b25de1e60da950f68705b3c88935c01fb4`, fetched 2026-11-22)

`corrections.tsv` keeps only the AOSP corrections of misspelled words and
contractions.

The pairs leave out sentences whose number is divisible by 100 (they test
predictions) and sentences naming Tom or Mary, Tatoeba's stock names.

## Regenerating

```sh
zcat en_US_wordlist.combined.gz > en_US_wordlist.combined
bunzip2 -k eng_sentences.tsv.bz2
cd core
cargo run --release -p pingolin-keyboard-dictc -- import-aosp en_US_wordlist.combined ../dictionaries/en-US
cargo run --release -p pingolin-keyboard-dictc -- import-pairs eng_sentences.tsv ../dictionaries/en-US \
    --exclude "Tom,Mary,Tom's,Mary's,Том,Мэри,Мери,Τομ,Μαίρη,תום,מרי"
```
