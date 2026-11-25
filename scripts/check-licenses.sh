#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Pingolin LLC
# SPDX-License-Identifier: GPL-3.0-or-later
#
# The app's Licenses screen covers what a release ships: the Rust crates linked
# into the engine and the Android libraries. licenses/shipped.tsv lists them.
# Needs the dependency report of a release build.
#
#   scripts/check-licenses.sh           fails if the list is out of date
#   scripts/check-licenses.sh --print   prints what a release ships now
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
report="$root/android/app/build/reports/policy/release-dependencies.txt"
[ -f "$report" ] || { echo "licenses: no $report, build the release first" >&2; exit 1; }

now="$(mktemp)"
trap 'rm -f "$now"' EXIT
(
    cd "$root/core"
    for target in aarch64-linux-android armv7-linux-androideabi x86_64-linux-android; do
        cargo tree --locked --package pingolin-keyboard-engine --features voice \
            --edges normal,no-proc-macro --target "$target" --prefix none --format '{p}'
    done
) | sed -E 's/ \(.*$//' | awk '$1 != "pingolin-keyboard-engine" { sub(/^v/, "", $2); print "rust\t" $1 "\t" $2 }' >> "$now"
awk -F: 'NF == 3 { print "android\t" $1 ":" $2 "\t" $3 }' "$report" >> "$now"
LC_ALL=C sort -u -o "$now" "$now"

# Two parts every release has: without them nothing was read.
for control in $'rust\tuniffi_core\t' $'android\torg.jetbrains.kotlin:kotlin-stdlib\t'; do
    grep -qF "$control" "$now" || { echo "licenses: no ${control//$'\t'/ }in $now, so this check read nothing" >&2; exit 1; }
done

if [ "${1:-}" = --print ]; then
    cat "$now"
    exit 0
fi
if ! diff -u --label licenses/shipped.tsv --label "what a release ships" <(grep -v '^#' "$root/licenses/shipped.tsv") "$now" >&2; then
    echo "licenses: the Licenses screen is out of date. Run licenses/build.py." >&2
    exit 1
fi
echo "licenses: OK ($(wc -l < "$now") parts)"
