<!-- SPDX-FileCopyrightText: 2026 Pingolin LLC -->
<!-- SPDX-License-Identifier: Apache-2.0 OR MIT -->

# Polski (`pl`)

Layout: `letters`. 195,046 words, 0 fixed corrections, 24,031 word pairs.

## Sources

- Words: AOSP LatinIME `dictionaries/pl_wordlist.combined.gz` (word list version 54),
  Apache-2.0, converted to `words.tsv`, `corrections.tsv` and `metadata.tsv`.
  https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/refs/heads/main/dictionaries/pl_wordlist.combined.gz
  (sha256 `75a7a488e014ec3b9dbdb2527f09bca6bb28c250232d9ba50cb0ee1f8738ea45`, fetched 2026-11-21)
- Word pairs: counted in the `pol` sentences of Tatoeba (https://tatoeba.org),
  by the Tatoeba contributors, CC BY 2.0 FR (`LICENSES/LicenseRef-CC-BY-2.0-FR.txt`).
  https://downloads.tatoeba.org/exports/per_language/pol/pol_sentences.tsv.bz2
  (sha256 `b1197732e4bb4a08ffb3650a388f8425c097c69c00457ea232dfddb5ecfb2759`, fetched 2026-11-22)

The pairs leave out sentences whose number is divisible by 100 (they test
predictions) and sentences naming Tom or Mary, Tatoeba's stock names.

## Regenerating

```sh
zcat pl_wordlist.combined.gz > pl_wordlist.combined
bunzip2 -k pol_sentences.tsv.bz2
cd core
cargo run --release -p pingolin-keyboard-dictc -- import-aosp pl_wordlist.combined ../dictionaries/pl
cargo run --release -p pingolin-keyboard-dictc -- import-pairs pol_sentences.tsv ../dictionaries/pl \
    --exclude "Tom,Mary,Tom's,Mary's,Том,Мэри,Мери,Τομ,Μαίρη,תום,מרי"
```
