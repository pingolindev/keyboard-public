#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Pingolin LLC
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Every file states its license and copyright (https://reuse.software).
# Needs the reuse tool: `pip install reuse`, or set REUSE to its path.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [ -n "${REUSE:-}" ]; then
    exec "$REUSE" --root "$root" lint
elif command -v reuse >/dev/null 2>&1; then
    exec reuse --root "$root" lint
elif command -v pipx >/dev/null 2>&1; then
    exec pipx run reuse --root "$root" lint
fi
echo "reuse is not installed: pip install reuse (or set REUSE)" >&2
exit 1
