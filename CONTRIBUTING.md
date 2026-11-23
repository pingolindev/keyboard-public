# Contributing

Issues, discussions and pull requests are welcome on
https://github.com/pingolindev/keyboard-public.

## How changes get in

Development happens in a private repository, and keyboard-public gets the
source of every release. Pull requests are reviewed here. An accepted one is
applied to the development repository with you as its author, and closed with
a note naming the release that will include it.

## Sign off your commits

Every commit needs a `Signed-off-by` line; `git commit -s` adds it. The line
certifies the [Developer Certificate of Origin](https://developercertificate.org/):
you wrote the change, or otherwise have the right to submit it under this
project's licenses. There is no CLA.

## Licenses

A contribution is licensed under the license of the files it changes:

- `core/`: Apache-2.0 OR MIT
- `dictionaries/`: each file keeps the license of its source, stated at the
  top of the file (word lists: Apache-2.0 from AOSP; word pairs: CC BY 2.0 FR
  from Tatoeba); our own text there is Apache-2.0 OR MIT
- everything else, including `android/`: GPL-3.0-or-later

New files need SPDX headers like the existing ones. `reuse lint` checks them.

## Languages

New languages are especially welcome: layouts and word lists. Every word list
needs a source whose license allows us to ship it, so say where the data comes
from in the pull request. `dictionaries/README.md` describes the
word list format and how to check a new one.

## Rules the code keeps

- No internet permission, and no code that talks to the network.
- No analytics, ads, or crash reports that send data.
- No backups of keyboard data.
- No Google Play services or other non-free dependencies.
- No link to other Pingolin apps or services.
- Nothing prebuilt in the repository. Everything builds from source.

Every build checks the manifest and the dependencies against these rules, and
`scripts/check.sh` also looks for prebuilt files.

## Before you open a pull request

Run `scripts/check.sh`. It runs the Rust and Android tests and all the checks.

## Translations

The app's text will be translatable on Weblate. Until then, open an issue.
