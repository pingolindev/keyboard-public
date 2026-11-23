<!-- SPDX-FileCopyrightText: 2026 Pingolin LLC -->
<!-- SPDX-License-Identifier: Apache-2.0 OR MIT -->

# Ελληνικά (`el`)

Layout: `greek`. 184,303 words, 0 fixed corrections, 9,832 word pairs.

## Sources

- Words: AOSP LatinIME `dictionaries/el_wordlist.combined.gz` (word list version 44),
  Apache-2.0, converted to `words.tsv`, `corrections.tsv` and `metadata.tsv`.
  https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/refs/heads/main/dictionaries/el_wordlist.combined.gz
  (sha256 `45ca1e21ff24322762f34b1feac4cb2427645a396621a1ea8aaebcf9217653e6`, fetched 2026-11-21)
- Word pairs: counted in the `ell` sentences of Tatoeba (https://tatoeba.org),
  by the Tatoeba contributors, CC BY 2.0 FR (`LICENSES/LicenseRef-CC-BY-2.0-FR.txt`).
  https://downloads.tatoeba.org/exports/per_language/ell/ell_sentences.tsv.bz2
  (sha256 `101d5321e22c7b8a81dcc10eda921d65214b9020e80770b104372d3065cad077`, fetched 2026-11-22)

The pairs leave out sentences whose number is divisible by 100 (they test
predictions) and sentences naming Tom or Mary, Tatoeba's stock names.

## Regenerating

```sh
zcat el_wordlist.combined.gz > el_wordlist.combined
bunzip2 -k ell_sentences.tsv.bz2
cd core
cargo run --release -p pingolin-keyboard-dictc -- import-aosp el_wordlist.combined ../dictionaries/el
cargo run --release -p pingolin-keyboard-dictc -- import-pairs ell_sentences.tsv ../dictionaries/el \
    --exclude "Tom,Mary,Tom's,Mary's,Том,Мэри,Мери,Τομ,Μαίρη,תום,מרי"
```
