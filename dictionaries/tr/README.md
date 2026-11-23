<!-- SPDX-FileCopyrightText: 2026 Pingolin LLC -->
<!-- SPDX-License-Identifier: Apache-2.0 OR MIT -->

# Türkçe (`tr`)

Layout: `turkish`. 180,728 words, 0 fixed corrections, 80,683 word pairs.

## Sources

- Words: AOSP LatinIME `dictionaries/tr_wordlist.combined.gz` (word list version 54),
  Apache-2.0, converted to `words.tsv`, `corrections.tsv` and `metadata.tsv`.
  https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/refs/heads/main/dictionaries/tr_wordlist.combined.gz
  (sha256 `e474cd9e8c0ab3c332db96649d0de6ecd2ca41d3d851c01c562a2c2158b3c00b`, fetched 2026-11-21)
- Word pairs: counted in the `tur` sentences of Tatoeba (https://tatoeba.org),
  by the Tatoeba contributors, CC BY 2.0 FR (`LICENSES/LicenseRef-CC-BY-2.0-FR.txt`).
  https://downloads.tatoeba.org/exports/per_language/tur/tur_sentences.tsv.bz2
  (sha256 `9da48b18a1df487666dba02e6e5d03d92566536e9462ea3a4fefbffb33ce1862`, fetched 2026-11-22)

The pairs leave out sentences whose number is divisible by 100 (they test
predictions) and sentences naming Tom or Mary, Tatoeba's stock names.

## Regenerating

```sh
zcat tr_wordlist.combined.gz > tr_wordlist.combined
bunzip2 -k tur_sentences.tsv.bz2
cd core
cargo run --release -p pingolin-keyboard-dictc -- import-aosp tr_wordlist.combined ../dictionaries/tr
cargo run --release -p pingolin-keyboard-dictc -- import-pairs tur_sentences.tsv ../dictionaries/tr \
    --exclude "Tom,Mary,Tom's,Mary's,Том,Мэри,Мери,Τομ,Μαίρη,תום,מרי"
```
