<!-- SPDX-FileCopyrightText: 2026 Pingolin LLC -->
<!-- SPDX-License-Identifier: Apache-2.0 OR MIT -->

# Српски (`sr`)

Layout: `serbian`. 191,521 words, 0 fixed corrections, 6,482 word pairs.

## Sources

- Words: AOSP LatinIME `dictionaries/sr_wordlist.combined.gz` (word list version 44),
  Apache-2.0, converted to `words.tsv`, `corrections.tsv` and `metadata.tsv`.
  https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/refs/heads/main/dictionaries/sr_wordlist.combined.gz
  (sha256 `a283ef2ae7a36e96baa894a9aebb7749f600cec189693c1cffd76545c6012d1d`, fetched 2026-11-21)
- Word pairs: counted in the `srp` sentences of Tatoeba (https://tatoeba.org),
  by the Tatoeba contributors, CC BY 2.0 FR (`LICENSES/LicenseRef-CC-BY-2.0-FR.txt`).
  https://downloads.tatoeba.org/exports/per_language/srp/srp_sentences.tsv.bz2
  (sha256 `a1c3c59ba167786b6312c6f1b90ed2d5edc1a07b4bffeaf43d44fe6f3fab230f`, fetched 2026-11-22)

The pairs leave out sentences whose number is divisible by 100 (they test
predictions) and sentences naming Tom or Mary, Tatoeba's stock names.

## Regenerating

```sh
zcat sr_wordlist.combined.gz > sr_wordlist.combined
bunzip2 -k srp_sentences.tsv.bz2
cd core
cargo run --release -p pingolin-keyboard-dictc -- import-aosp sr_wordlist.combined ../dictionaries/sr
cargo run --release -p pingolin-keyboard-dictc -- import-pairs srp_sentences.tsv ../dictionaries/sr \
    --exclude "Tom,Mary,Tom's,Mary's,Том,Мэри,Мери,Τομ,Μαίρη,תום,מרי"
```
