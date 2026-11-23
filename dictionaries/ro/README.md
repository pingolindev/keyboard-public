<!-- SPDX-FileCopyrightText: 2026 Pingolin LLC -->
<!-- SPDX-License-Identifier: Apache-2.0 OR MIT -->

# Română (`ro`)

Layout: `letters`. 300,000 words, 0 fixed corrections, 10,471 word pairs.

## Sources

- Words: AOSP LatinIME `dictionaries/ro_wordlist.combined.gz` (word list version 52),
  Apache-2.0, converted to `words.tsv`, `corrections.tsv` and `metadata.tsv`.
  https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/refs/heads/main/dictionaries/ro_wordlist.combined.gz
  (sha256 `47139886aea4be76bd5857a6c62cc2b84f2094d58cb2c7b8e7dc05e17452960f`, fetched 2026-11-21)
- Word pairs: counted in the `ron` sentences of Tatoeba (https://tatoeba.org),
  by the Tatoeba contributors, CC BY 2.0 FR (`LICENSES/LicenseRef-CC-BY-2.0-FR.txt`).
  https://downloads.tatoeba.org/exports/per_language/ron/ron_sentences.tsv.bz2
  (sha256 `44755539b9c44f1d8b2a1f39d3a81fcbcd7a57ab771005fa25cda3569fb2d83c`, fetched 2026-11-22)

AOSP's Romanian list has 1,125,072 words, mostly rare inflections. Only the
300,000 most frequent are kept.

The pairs leave out sentences whose number is divisible by 100 (they test
predictions) and sentences naming Tom or Mary, Tatoeba's stock names.

## Regenerating

```sh
zcat ro_wordlist.combined.gz > ro_wordlist.combined
bunzip2 -k ron_sentences.tsv.bz2
cd core
cargo run --release -p pingolin-keyboard-dictc -- import-aosp ro_wordlist.combined ../dictionaries/ro --max-words 300000
cargo run --release -p pingolin-keyboard-dictc -- import-pairs ron_sentences.tsv ../dictionaries/ro \
    --exclude "Tom,Mary,Tom's,Mary's,Том,Мэри,Мери,Τομ,Μαίρη,תום,מרי"
```
