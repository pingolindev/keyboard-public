#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Pingolin LLC
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Every test and check. Needs the build tools listed in README.md, plus `reuse`
# (see check-reuse.sh). The source checks come first: without .git they cannot
# tell build outputs from sources.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "== Nothing prebuilt"
"$root/scripts/check-prebuilt.sh"

echo "== Licenses"
"$root/scripts/check-reuse.sh"

echo "== Rust: format, lint, tests"
(
    cd "$root/core"
    cargo fmt --all -- --check
    cargo clippy --workspace --all-targets --locked -- -D warnings
    # Voice typing compiles whisper.cpp; lint its Rust side with it built in.
    cargo clippy --package pingolin-keyboard-engine --features voice --all-targets --locked -- -D warnings
    cargo test --workspace --locked
)

echo "== Android: build logic tests, lint, unit tests, rule gates, release build"
(
    cd "$root/android"
    ./gradlew --console=plain -p buildSrc test
    ./gradlew --console=plain :app:check :app:assembleRelease
)

echo "== The Licenses screen covers what a release ships"
"$root/scripts/check-licenses.sh"

echo "== No links to other Pingolin projects"
"$root/scripts/check-isolation.sh" "$root"/android/app/build/outputs/apk/release/*.apk

echo "All checks passed."
