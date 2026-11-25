# Pingolin Keyboard

A free and open source keyboard for Android, with no internet access. It is
at an early stage.

- Suggestions, autocorrect and next-word predictions in 23 languages
- Glide typing
- Voice typing, done on the phone by whisper.cpp
- Incognito mode, on by itself where an app asks for it
- A text editing panel, and terminal keys for ssh
- Clipboard history
- Password manager suggestions (Android 11 and later)
- Colors and keyboard height
- Export and import of settings, learned words and pinned clips

English (US) is built in. Other languages and voice models are files you get
from the app's settings.

Download the APK from the [releases page](https://github.com/pingolindev/keyboard-public/releases).

## Principles

- No internet permission. The build fails if one appears.
- No backups of keyboard data, to the cloud or to a new phone.
- No accounts, ads, payments or tracking. See the [privacy policy](PRIVACY.md).
- Free software: anyone can read, build, change and share it.

## Building

You need:

- JDK 17 or newer
- the Android SDK with NDK 27.2.12479018
- Rust through rustup (`core/rust-toolchain.toml` pins the version)
- cargo-ndk (`cargo install cargo-ndk`)

Then:

    cd android
    ./gradlew assembleDebug

`scripts/check.sh` runs every test and check.

## What is where

- `android/`: the Android app
- `core/`: the engine, in Rust, with no Android code in it
- `dictionaries/`: word lists, one directory per language, built into the
  app at build time (see `dictionaries/README.md`)
- `scripts/`: the checks a release has to pass

## Licenses

The Android app is GPL-3.0-or-later. The engine in `core/` is available under
Apache-2.0 or MIT, so other keyboards can use it too. Every file states its
license, following [REUSE](https://reuse.software), and the full texts are in
`LICENSES/`.

## Contributing

Issues, discussions and pull requests are welcome. See
[CONTRIBUTING.md](CONTRIBUTING.md). To report a security problem, see
[SECURITY.md](SECURITY.md).
