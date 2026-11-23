<!-- SPDX-FileCopyrightText: 2026 Pingolin LLC -->
<!-- SPDX-License-Identifier: Apache-2.0 OR MIT -->

# Norsk bokmål (`nb`)

Layout: `qwerty-nb`. 171,008 words, 0 fixed corrections, 5,638 word pairs.

## Sources

- Words: AOSP LatinIME `dictionaries/nb_wordlist.combined.gz` (word list version 44),
  Apache-2.0, converted to `words.tsv`, `corrections.tsv` and `metadata.tsv`.
  https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/refs/heads/main/dictionaries/nb_wordlist.combined.gz
  (sha256 `2a8076a48c03ab310bfee8df7f05ae9f587201a3de621019ebac8c4e01e10789`, fetched 2026-11-21)
- Word pairs: counted in the `nob` sentences of Tatoeba (https://tatoeba.org),
  by the Tatoeba contributors, CC BY 2.0 FR (`LICENSES/LicenseRef-CC-BY-2.0-FR.txt`).
  https://downloads.tatoeba.org/exports/per_language/nob/nob_sentences.tsv.bz2
  (sha256 `1d65805473b6c20aa9c9220942cc6c91d43f7ca3cdd5be73f31031eb58d99ebd`, fetched 2026-11-22)

The pairs leave out sentences whose number is divisible by 100 (they test
predictions) and sentences naming Tom or Mary, Tatoeba's stock names.

## Regenerating

```sh
zcat nb_wordlist.combined.gz > nb_wordlist.combined
bunzip2 -k nob_sentences.tsv.bz2
cd core
cargo run --release -p pingolin-keyboard-dictc -- import-aosp nb_wordlist.combined ../dictionaries/nb
cargo run --release -p pingolin-keyboard-dictc -- import-pairs nob_sentences.tsv ../dictionaries/nb \
    --exclude "Tom,Mary,Tom's,Mary's,Том,Мэри,Мери,Τομ,Μαίρη,תום,מרי"
```
