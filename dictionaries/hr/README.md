<!-- SPDX-FileCopyrightText: 2026 Pingolin LLC -->
<!-- SPDX-License-Identifier: Apache-2.0 OR MIT -->

# Hrvatski (`hr`)

Layout: `qwertz`. 209,879 words, 0 fixed corrections, 1,985 word pairs.

## Sources

- Words: AOSP LatinIME `dictionaries/hr_wordlist.combined.gz` (word list version 44),
  Apache-2.0, converted to `words.tsv`, `corrections.tsv` and `metadata.tsv`.
  https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/refs/heads/main/dictionaries/hr_wordlist.combined.gz
  (sha256 `feeb7f587b07c49bf487320cdf8f3f829425a9e9ead1df4a93e4e2147837b2af`, fetched 2026-11-21)
- Word pairs: counted in the `hrv` sentences of Tatoeba (https://tatoeba.org),
  by the Tatoeba contributors, CC BY 2.0 FR (`LICENSES/LicenseRef-CC-BY-2.0-FR.txt`).
  https://downloads.tatoeba.org/exports/per_language/hrv/hrv_sentences.tsv.bz2
  (sha256 `9eb4318830c9f4d4d73b4687d6025f3a7140ddd1ac0c62c81fe01bcd27eaf230`, fetched 2026-11-22)

The pairs leave out sentences whose number is divisible by 100 (they test
predictions) and sentences naming Tom or Mary, Tatoeba's stock names.

## Regenerating

```sh
zcat hr_wordlist.combined.gz > hr_wordlist.combined
bunzip2 -k hrv_sentences.tsv.bz2
cd core
cargo run --release -p pingolin-keyboard-dictc -- import-aosp hr_wordlist.combined ../dictionaries/hr
cargo run --release -p pingolin-keyboard-dictc -- import-pairs hrv_sentences.tsv ../dictionaries/hr \
    --exclude "Tom,Mary,Tom's,Mary's,Том,Мэри,Мери,Τομ,Μαίρη,תום,מרי"
```
