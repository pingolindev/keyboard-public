<!-- SPDX-FileCopyrightText: 2026 Pingolin LLC -->
<!-- SPDX-License-Identifier: Apache-2.0 OR MIT -->

# עברית (`he`)

Layout: `hebrew`. 94,797 words, 0 fixed corrections, 41,316 word pairs.

## Sources

- Words: AOSP LatinIME `dictionaries/iw_wordlist.combined.gz` (word list version 44),
  Apache-2.0, converted to `words.tsv`, `corrections.tsv` and `metadata.tsv`.
  https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/refs/heads/main/dictionaries/iw_wordlist.combined.gz
  (sha256 `0fbbaa614e8512fdf2dfe7ce091c0fd011074eae654041a2ccd78459ca24c0fc`, fetched 2026-11-21)
- Word pairs: counted in the `heb` sentences of Tatoeba (https://tatoeba.org),
  by the Tatoeba contributors, CC BY 2.0 FR (`LICENSES/LicenseRef-CC-BY-2.0-FR.txt`).
  https://downloads.tatoeba.org/exports/per_language/heb/heb_sentences.tsv.bz2
  (sha256 `fd19184f0cbf99acb9ba1b5add24a3515c472800cece72fd4a105bef1aa543c3`, fetched 2026-11-22)

The pairs leave out sentences whose number is divisible by 100 (they test
predictions) and sentences naming Tom or Mary, Tatoeba's stock names.

## Regenerating

```sh
zcat iw_wordlist.combined.gz > iw_wordlist.combined
bunzip2 -k heb_sentences.tsv.bz2
cd core
cargo run --release -p pingolin-keyboard-dictc -- import-aosp iw_wordlist.combined ../dictionaries/he
cargo run --release -p pingolin-keyboard-dictc -- import-pairs heb_sentences.tsv ../dictionaries/he \
    --exclude "Tom,Mary,Tom's,Mary's,Том,Мэри,Мери,Τομ,Μαίρη,תום,מרי"
```
