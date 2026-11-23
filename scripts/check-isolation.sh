#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Pingolin LLC
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Pingolin Keyboard may copy from other Pingolin projects but never connects to
# them. Fails when a build file points at another Pingolin repository, or when
# an APK given as an argument holds the address of a Pingolin service.
#
#   scripts/check-isolation.sh [app.apk ...]
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fail=0

repos='mobile-sdk|pingolin-crypto|note-android|note-ios|track-android|track-ios|pingolin-builder'
build_pattern="($repos|pingolindev/(auth|note|track|theme|ops)([/.\"]|$))"
while IFS= read -r -d '' file; do
    if grep -nHE "$build_pattern" "$file"; then
        echo "${file#"$root"/} refers to another Pingolin project"
        fail=1
    fi
done < <(find "$root" \( -path "$root/core/target" -o -name build -o -name .gradle \) -prune -o \
    \( -name '*.gradle.kts' -o -name '*.gradle' -o -name 'Cargo.toml' -o -name 'settings.gradle*' \) -print0)

if [ -e "$root/.gitmodules" ]; then
    echo "git submodules are not allowed"
    fail=1
fi

hosts='([a-z0-9-]+\.)*pingolindev\.com|(api|auth|api-auth|track|note|push|ingest|geocoder|tiles)[a-z0-9-]*\.pingolin\.com'
for apk in "$@"; do
    if [ ! -f "$apk" ]; then
        echo "no such APK: $apk"
        fail=1
        continue
    fi
    tmp="$(mktemp -d)"
    unzip -qq -o "$apk" -d "$tmp"
    hits="$(grep -rhoaE "$hosts" "$tmp" | sort -u || true)"
    rm -rf "$tmp"
    if [ -n "$hits" ]; then
        echo "$apk contains Pingolin service addresses:"
        echo "$hits" | sed 's/^/  /'
        fail=1
    fi
done

if [ "$fail" -eq 0 ]; then echo "isolation: OK"; fi
exit "$fail"
