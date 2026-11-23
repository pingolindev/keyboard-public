<!-- SPDX-FileCopyrightText: 2026 Pingolin LLC -->
<!-- SPDX-License-Identifier: Apache-2.0 OR MIT -->

# Dictionaries

One directory per language, named by its BCP 47 tag (`en-US`). The Android
build compiles each one with `dictc` (`core/dictc`) into a language file,
`android/app/build/outputs/languages/<tag>.pkd`. English (US) is also built
into the APK, and the other files are published with each release.

Each directory holds tab-separated text files; lines starting with `#` are
comments:

- `words.tsv`: `word<TAB>frequency[<TAB>flags]`. Frequency 0 to 255 on the
  log scale of AOSP word lists: higher is more frequent. 0 marks a real word
  that is never suggested and never corrected either. The only flag is
  `offensive`: such words are not suggested unless typed in full.
- `corrections.tsv`: `typed<TAB>replacement`. Autocorrect always replaces
  the typed word (in any capitalization) with the replacement, which may be
  several words.
- `pairs.tsv` (optional): `previous<TAB>next<TAB>count`, how often `next`
  followed `previous` in a corpus, for predicting the next word. `previous`
  is `<s>` for the start of a sentence. A line with `*` as `next` gives the
  total number of words seen after `previous`.
- `metadata.tsv`: `key<TAB>value`: `locale` (required), `name` (in the
  language itself), `layout` (a built-in layout id, see
  `core/engine/src/layout.rs`), `source`, `license`.
- `README.md`: sources, licenses, and the commands that regenerate the files.

Words hold letters, combining marks, apostrophes and hyphens. Matching
ignores case, accents, apostrophes and hyphens, so "cafe" finds "café".

## Sources

- Words: AOSP LatinIME word lists, Apache-2.0.
- Pairs: Tatoeba sentences (https://tatoeba.org), CC BY 2.0 FR
  (`LICENSES/LicenseRef-CC-BY-2.0-FR.txt`).

## Adding a language

A new language is a pull request with a new directory. Its sources must
allow redistribution (free software or public domain). Name them in its README
and in the SPDX headers of each file. To check it:

```sh
cd core
cargo run --release -p pingolin-keyboard-dictc -- compile ../dictionaries/<tag> /tmp/<tag>.pkd
cargo run --release --example replay -- ../dictionaries/<tag>
cargo run --release --example replay -- ../dictionaries/<tag> --sentences <tatoeba>.tsv
```

`replay` measures autocorrect on simulated typing, and predictions with
`--sentences` (see `core/engine/examples/replay.rs`).
