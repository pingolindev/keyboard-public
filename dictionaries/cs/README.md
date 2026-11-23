<!-- SPDX-FileCopyrightText: 2026 Pingolin LLC -->
<!-- SPDX-License-Identifier: Apache-2.0 OR MIT -->

# Čeština (`cs`)

Layout: `qwertz`. 171,525 words, 0 fixed corrections, 14,936 word pairs.

## Sources

- Words: AOSP LatinIME `dictionaries/cs_wordlist.combined.gz` (word list version 44),
  Apache-2.0, converted to `words.tsv`, `corrections.tsv` and `metadata.tsv`.
  https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/refs/heads/main/dictionaries/cs_wordlist.combined.gz
  (sha256 `d9974c28a0535f68de55fbc6c914248e82382e52a79184f54883463e67e89c5d`, fetched 2026-11-21)
- Word pairs: counted in the `ces` sentences of Tatoeba (https://tatoeba.org),
  by the Tatoeba contributors, CC BY 2.0 FR (`LICENSES/LicenseRef-CC-BY-2.0-FR.txt`).
  https://downloads.tatoeba.org/exports/per_language/ces/ces_sentences.tsv.bz2
  (sha256 `951db78f25e4cd30fdb86ea727a851acc1ece475ac70e26f4b9d41803f1d28ad`, fetched 2026-11-22)

The pairs leave out sentences whose number is divisible by 100 (they test
predictions) and sentences naming Tom or Mary, Tatoeba's stock names.

## Regenerating

```sh
zcat cs_wordlist.combined.gz > cs_wordlist.combined
bunzip2 -k ces_sentences.tsv.bz2
cd core
cargo run --release -p pingolin-keyboard-dictc -- import-aosp cs_wordlist.combined ../dictionaries/cs
cargo run --release -p pingolin-keyboard-dictc -- import-pairs ces_sentences.tsv ../dictionaries/cs \
    --exclude "Tom,Mary,Tom's,Mary's,Том,Мэри,Мери,Τομ,Μαίρη,תום,מרי"
```
