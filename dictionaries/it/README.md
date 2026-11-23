<!-- SPDX-FileCopyrightText: 2026 Pingolin LLC -->
<!-- SPDX-License-Identifier: Apache-2.0 OR MIT -->

# Italiano (`it`)

Layout: `letters`. 172,805 words, 0 fixed corrections, 99,210 word pairs.

## Sources

- Words: AOSP LatinIME `dictionaries/it_wordlist.combined.gz` (word list version 54),
  Apache-2.0, converted to `words.tsv`, `corrections.tsv` and `metadata.tsv`.
  https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/refs/heads/main/dictionaries/it_wordlist.combined.gz
  (sha256 `4ac1fa3b112130416843f5abc2a61fdbd2395a41fb6a719790cd7c45934218ec`, fetched 2026-11-21)
- Word pairs: counted in the `ita` sentences of Tatoeba (https://tatoeba.org),
  by the Tatoeba contributors, CC BY 2.0 FR (`LICENSES/LicenseRef-CC-BY-2.0-FR.txt`).
  https://downloads.tatoeba.org/exports/per_language/ita/ita_sentences.tsv.bz2
  (sha256 `20dc0feb2a4340daf69d42fd4bfb51e4d04c899e2a5bce3692169de9fe1a11fe`, fetched 2026-11-22)

The pairs leave out sentences whose number is divisible by 100 (they test
predictions) and sentences naming Tom or Mary, Tatoeba's stock names.

## Regenerating

```sh
zcat it_wordlist.combined.gz > it_wordlist.combined
bunzip2 -k ita_sentences.tsv.bz2
cd core
cargo run --release -p pingolin-keyboard-dictc -- import-aosp it_wordlist.combined ../dictionaries/it
cargo run --release -p pingolin-keyboard-dictc -- import-pairs ita_sentences.tsv ../dictionaries/it \
    --exclude "Tom,Mary,Tom's,Mary's,Том,Мэри,Мери,Τομ,Μαίρη,תום,מרי"
```
