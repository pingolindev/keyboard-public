<!-- SPDX-FileCopyrightText: 2026 Pingolin LLC -->
<!-- SPDX-License-Identifier: Apache-2.0 OR MIT -->

# Nederlands (`nl`)

Layout: `letters`. 178,444 words, 0 fixed corrections, 30,899 word pairs.

## Sources

- Words: AOSP LatinIME `dictionaries/nl_wordlist.combined.gz` (word list version 54),
  Apache-2.0, converted to `words.tsv`, `corrections.tsv` and `metadata.tsv`.
  https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/refs/heads/main/dictionaries/nl_wordlist.combined.gz
  (sha256 `a647ed5fdd846d3240572c9cb10fb8c30880df2ebefea6184a378adb713cd0cd`, fetched 2026-11-21)
- Word pairs: counted in the `nld` sentences of Tatoeba (https://tatoeba.org),
  by the Tatoeba contributors, CC BY 2.0 FR (`LICENSES/LicenseRef-CC-BY-2.0-FR.txt`).
  https://downloads.tatoeba.org/exports/per_language/nld/nld_sentences.tsv.bz2
  (sha256 `514c3d02ebeaf076b5087edf9381e06b9ded3eba94662757bbb7cdd2ea82f62e`, fetched 2026-11-22)

The pairs leave out sentences whose number is divisible by 100 (they test
predictions) and sentences naming Tom or Mary, Tatoeba's stock names.

## Regenerating

```sh
zcat nl_wordlist.combined.gz > nl_wordlist.combined
bunzip2 -k nld_sentences.tsv.bz2
cd core
cargo run --release -p pingolin-keyboard-dictc -- import-aosp nl_wordlist.combined ../dictionaries/nl
cargo run --release -p pingolin-keyboard-dictc -- import-pairs nld_sentences.tsv ../dictionaries/nl \
    --exclude "Tom,Mary,Tom's,Mary's,Том,Мэри,Мери,Τομ,Μαίρη,תום,מרי"
```
