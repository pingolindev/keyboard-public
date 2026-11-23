<!-- SPDX-FileCopyrightText: 2026 Pingolin LLC -->
<!-- SPDX-License-Identifier: Apache-2.0 OR MIT -->

# Slovenščina (`sl`)

Layout: `qwertz`. 59,969 words, 0 fixed corrections, 922 word pairs.

## Sources

- Words: AOSP LatinIME `dictionaries/sl_wordlist.combined.gz` (word list version 44),
  Apache-2.0, converted to `words.tsv`, `corrections.tsv` and `metadata.tsv`.
  https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/refs/heads/main/dictionaries/sl_wordlist.combined.gz
  (sha256 `f35d91b21722730450216f978f799d3daa9354ad9391130622df75efc59f6ecf`, fetched 2026-11-21)
- Word pairs: counted in the `slv` sentences of Tatoeba (https://tatoeba.org),
  by the Tatoeba contributors, CC BY 2.0 FR (`LICENSES/LicenseRef-CC-BY-2.0-FR.txt`).
  https://downloads.tatoeba.org/exports/per_language/slv/slv_sentences.tsv.bz2
  (sha256 `1b9d17f6c4ac886aa5bb09f04866e2c342c9114e303ff5d2780adde1f3c898ed`, fetched 2026-11-22)

The pairs leave out sentences whose number is divisible by 100 (they test
predictions) and sentences naming Tom or Mary, Tatoeba's stock names.

## Regenerating

```sh
zcat sl_wordlist.combined.gz > sl_wordlist.combined
bunzip2 -k slv_sentences.tsv.bz2
cd core
cargo run --release -p pingolin-keyboard-dictc -- import-aosp sl_wordlist.combined ../dictionaries/sl
cargo run --release -p pingolin-keyboard-dictc -- import-pairs slv_sentences.tsv ../dictionaries/sl \
    --exclude "Tom,Mary,Tom's,Mary's,Том,Мэри,Мери,Τομ,Μαίρη,תום,מרי"
```
