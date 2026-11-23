<!-- SPDX-FileCopyrightText: 2026 Pingolin LLC -->
<!-- SPDX-License-Identifier: Apache-2.0 OR MIT -->

# Français (`fr`)

Layout: `azerty`. 190,058 words, 1 fixed corrections, 102,803 word pairs.

## Sources

- Words: AOSP LatinIME `dictionaries/fr_wordlist.combined.gz` (word list version 54),
  Apache-2.0, converted to `words.tsv`, `corrections.tsv` and `metadata.tsv`.
  https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/refs/heads/main/dictionaries/fr_wordlist.combined.gz
  (sha256 `cc917a0a81acab0ea12089c2fb06b5c6be98c75f23a7605bfdbbeb698abfd65e`, fetched 2026-11-21)
- Word pairs: counted in the `fra` sentences of Tatoeba (https://tatoeba.org),
  by the Tatoeba contributors, CC BY 2.0 FR (`LICENSES/LicenseRef-CC-BY-2.0-FR.txt`).
  https://downloads.tatoeba.org/exports/per_language/fra/fra_sentences.tsv.bz2
  (sha256 `c9e91cc5a0640db7bfc4423fadf3985db42df08dafce4983d4c6b8c2cf7a4d3d`, fetched 2026-11-22)

`corrections.tsv` keeps only the AOSP corrections of misspelled words and
contractions.

The pairs leave out sentences whose number is divisible by 100 (they test
predictions) and sentences naming Tom or Mary, Tatoeba's stock names.

## Regenerating

```sh
zcat fr_wordlist.combined.gz > fr_wordlist.combined
bunzip2 -k fra_sentences.tsv.bz2
cd core
cargo run --release -p pingolin-keyboard-dictc -- import-aosp fr_wordlist.combined ../dictionaries/fr
cargo run --release -p pingolin-keyboard-dictc -- import-pairs fra_sentences.tsv ../dictionaries/fr \
    --exclude "Tom,Mary,Tom's,Mary's,Том,Мэри,Мери,Τομ,Μαίρη,תום,מרי"
```
