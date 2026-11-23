<!-- SPDX-FileCopyrightText: 2026 Pingolin LLC -->
<!-- SPDX-License-Identifier: Apache-2.0 OR MIT -->

# Latviešu (`lv`)

Layout: `letters`. 200,447 words, 0 fixed corrections, 4,665 word pairs.

## Sources

- Words: AOSP LatinIME `dictionaries/lv_wordlist.combined.gz` (word list version 44),
  Apache-2.0, converted to `words.tsv`, `corrections.tsv` and `metadata.tsv`.
  https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/refs/heads/main/dictionaries/lv_wordlist.combined.gz
  (sha256 `dd5b95e848322ed265d1111864c26734d55e10b53183756e72339dc4ad98f190`, fetched 2026-11-21)
- Word pairs: counted in the `lvs` sentences of Tatoeba (https://tatoeba.org),
  by the Tatoeba contributors, CC BY 2.0 FR (`LICENSES/LicenseRef-CC-BY-2.0-FR.txt`).
  https://downloads.tatoeba.org/exports/per_language/lvs/lvs_sentences.tsv.bz2
  (sha256 `246fc737b8b59315f9eccb8549af5c192a0520c85db7982fc3ae821b1918f9f1`, fetched 2026-11-22)

The pairs leave out sentences whose number is divisible by 100 (they test
predictions) and sentences naming Tom or Mary, Tatoeba's stock names.

## Regenerating

```sh
zcat lv_wordlist.combined.gz > lv_wordlist.combined
bunzip2 -k lvs_sentences.tsv.bz2
cd core
cargo run --release -p pingolin-keyboard-dictc -- import-aosp lv_wordlist.combined ../dictionaries/lv
cargo run --release -p pingolin-keyboard-dictc -- import-pairs lvs_sentences.tsv ../dictionaries/lv \
    --exclude "Tom,Mary,Tom's,Mary's,Том,Мэри,Мери,Τομ,Μαίρη,תום,מרי"
```
