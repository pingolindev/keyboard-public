<!-- SPDX-FileCopyrightText: 2026 Pingolin LLC -->
<!-- SPDX-License-Identifier: Apache-2.0 OR MIT -->

# Español (`es`)

Layout: `qwerty-es`. 236,193 words, 0 fixed corrections, 75,994 word pairs.

## Sources

- Words: AOSP LatinIME `dictionaries/es_wordlist.combined.gz` (word list version 54),
  Apache-2.0, converted to `words.tsv`, `corrections.tsv` and `metadata.tsv`.
  https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/refs/heads/main/dictionaries/es_wordlist.combined.gz
  (sha256 `889ad52bce2933e2a30a0560b8d5a76f5334500e776a7db7c7cb3e9e79fc2652`, fetched 2026-11-21)
- Word pairs: counted in the `spa` sentences of Tatoeba (https://tatoeba.org),
  by the Tatoeba contributors, CC BY 2.0 FR (`LICENSES/LicenseRef-CC-BY-2.0-FR.txt`).
  https://downloads.tatoeba.org/exports/per_language/spa/spa_sentences.tsv.bz2
  (sha256 `7872d2e66debfd8e73e96cc9bca9560a32cc914aee9c820649d7d3d5e7353f48`, fetched 2026-11-22)

The pairs leave out sentences whose number is divisible by 100 (they test
predictions) and sentences naming Tom or Mary, Tatoeba's stock names.

## Regenerating

```sh
zcat es_wordlist.combined.gz > es_wordlist.combined
bunzip2 -k spa_sentences.tsv.bz2
cd core
cargo run --release -p pingolin-keyboard-dictc -- import-aosp es_wordlist.combined ../dictionaries/es
cargo run --release -p pingolin-keyboard-dictc -- import-pairs spa_sentences.tsv ../dictionaries/es \
    --exclude "Tom,Mary,Tom's,Mary's,Том,Мэри,Мери,Τομ,Μαίρη,תום,מרי"
```
