#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Pingolin LLC
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Nothing prebuilt in the repository: everything builds from source. The one
# exception is the Gradle wrapper jar, which must be the one Gradle publishes.
# Update its checksum below with each Gradle upgrade:
#   https://services.gradle.org/distributions/gradle-<version>-wrapper.jar.sha256
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

wrapper="android/gradle/wrapper/gradle-wrapper.jar"
wrapper_sha256="497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7" # Gradle 9.6.1
fail=0

# In a git checkout: tracked files plus new files that are not ignored. Without
# .git every file is checked, so run this before building.
list_files() {
    if git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
        git ls-files -z --cached --others --exclude-standard
    else
        find . -type f -print0 | sed -z 's#^\./##'
    fi
}

while IFS= read -r -d '' file; do
    [ "$file" = "$wrapper" ] && continue
    [ -f "$file" ] || continue
    case "$file" in
        *.so | *.a | *.o | *.dylib | *.dll | *.exe | *.jar | *.aar | *.apk | *.aab | *.dex | *.class | *.wasm)
            echo "prebuilt file $file"
            fail=1
            continue
            ;;
    esac
    magic="$(head -c 4 "$file" | od -An -tx1 | tr -d ' \n')"
    case "$magic" in
        7f454c46 | cafebabe | feedface | feedfacf | cefaedfe | cffaedfe | 6465780a | 4d5a*)
            echo "compiled code in $file"
            fail=1
            ;;
    esac
done < <(list_files)

actual="$(sha256sum "$wrapper" | cut -d' ' -f1)"
if [ "$actual" != "$wrapper_sha256" ]; then
    echo "$wrapper is not the jar Gradle published (sha256 $actual)"
    fail=1
fi

if [ "$fail" -eq 0 ]; then echo "prebuilt: OK"; fi
exit "$fail"
