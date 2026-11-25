#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Pingolin LLC
# SPDX-License-Identifier: GPL-3.0-or-later
"""Writes shipped.tsv and the Licenses screen's pages that name versions.

Run after a dependency changes (scripts/check-licenses.sh says when), with the
release built:

    licenses/build.py

index.tsv lists the pages, with the files each one shows. The .txt files that
are not written here are kept by hand. License texts are in ../LICENSES.
"""

import json
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
TARGETS = ["aarch64-linux-android", "armv7-linux-androideabi", "x86_64-linux-android"]

# The licenses a crate is used under, by what it offers. Another one stops the
# script: somebody has to read it first.
CRATE_LICENSES = {
    "MIT OR Apache-2.0": ["Apache-2.0"],
    "Apache-2.0 OR MIT": ["Apache-2.0"],
    "MIT/Apache-2.0": ["Apache-2.0"],
    "Apache-2.0 WITH LLVM-exception OR Apache-2.0 OR MIT": ["Apache-2.0"],
    "Zlib OR Apache-2.0 OR MIT": ["Apache-2.0"],
    "(MIT OR Apache-2.0) AND Unicode-3.0": ["Apache-2.0", "Unicode-3.0"],
    "MIT": ["MIT"],
    "Unlicense OR MIT": ["MIT"],
    "MPL-2.0": ["MPL-2.0"],
}

# The Android libraries by group: their page, who holds the copyright and where
# the source is. All are used under the Apache License 2.0.
ANDROID = {
    "androidx": ("The Android Open Source Project", "https://android.googlesource.com/platform/frameworks/support"),
    "kotlin": ("JetBrains s.r.o. and Kotlin contributors", "https://github.com/JetBrains/kotlin, https://github.com/Kotlin/kotlinx.coroutines"),
    "guava": ("The Guava Authors, The JSpecify Authors", "https://github.com/google/guava, https://github.com/jspecify/jspecify"),
    "jna": ("1998 Sun Microsystems, Inc., 2007-2015 Timothy Wall, 2007 Wayne Meissner and JNA contributors",
            "https://github.com/java-native-access/jna"),
}
GROUPS = {
    "androidx": "androidx",
    "org.jetbrains": "kotlin",
    "com.google.guava": "guava",
    "org.jspecify": "guava",
    "net.java.dev.jna": "jna",
}

# shipped.tsv's own header, not this file's. REUSE-IgnoreStart
SHIPPED_HEADER = (
    "# SPDX-FileCopyrightText: 2026 Pingolin LLC\n"
    "# SPDX-License-Identifier: GPL-3.0-or-later\n"
    "# What a release ships: rust or android, name, version. Written by build.py.\n"
)
# REUSE-IgnoreEnd


def shipped():
    """(kind, name, version) of every part."""
    out = subprocess.run([ROOT / "scripts" / "check-licenses.sh", "--print"], check=True, capture_output=True, text=True).stdout
    return [tuple(line.split("\t")) for line in out.splitlines()]


def crates():
    """Cargo's metadata of every crate, by name and version."""
    found = {}
    for target in TARGETS:
        out = subprocess.run(
            ["cargo", "metadata", "--locked", "--format-version", "1", "--features", "voice", "--filter-platform", target],
            cwd=ROOT / "core" / "engine", check=True, capture_output=True, text=True).stdout
        for p in json.loads(out)["packages"]:
            found[p["name"], p["version"]] = p
    return found


def license_file(crate, *names):
    folder = Path(crate["manifest_path"]).parent
    for name in names:
        if (folder / name).is_file():
            return (folder / name).read_text(encoding="utf-8").strip()
    sys.exit(f"{crate['name']} has none of {', '.join(names)}")


def rust_page(parts, about):
    apache, mit, unicode = [], [], []
    for name, version in parts:
        crate = about[name, version]
        offered = crate["license"]
        if offered not in CRATE_LICENSES:
            sys.exit(f"{name} {version} is under {offered}: read it and add it to CRATE_LICENSES")
        line = f"{name} {version}, {crate['repository']}"
        folder = Path(crate["manifest_path"]).parent
        if any(folder.glob("NOTICE*")):
            sys.exit(f"{name} {version} has a NOTICE file, which its page must hold")
        for used in CRATE_LICENSES[offered]:
            if used == "Apache-2.0":
                apache.append(line)
            elif used == "MIT":
                mit += [line, license_file(crate, "LICENSE-MIT", "LICENSE")]
            elif used == "Unicode-3.0":
                unicode += [f"{name} {version} holds Unicode data:", license_file(crate, "LICENSE-UNICODE")]
            else:
                sys.exit(f"{name} {version}: {used} needs a page of its own")
    return ["Used under the Apache License 2.0, at the end of this page:", *apache,
            "Used under the MIT license:", *mit, *unicode]


def android_page(page, modules):
    holders, source = ANDROID[page]
    return [f"Copyright {holders}", f"Source code: {source}", *[f"{name} {version}" for name, version in modules]]


def write(name, paragraphs):
    (HERE / name).write_text("\n\n".join(paragraphs) + "\n", encoding="utf-8")


def main():
    parts = shipped()
    about = crates()
    rust = [(n, v) for kind, n, v in parts if kind == "rust"]
    uniffi = [(n, v) for n, v in rust if n.split("_")[0] == "uniffi"]
    if any(about[p]["license"] != "MPL-2.0" for p in uniffi):
        sys.exit("UniFFI is not under MPL-2.0 any more")
    write("rust.txt", rust_page([p for p in rust if p not in uniffi], about))
    write("uniffi.txt", [
        *[f"{n} {v}" for n, v in uniffi],
        f"Source code: https://github.com/mozilla/uniffi-rs, tag v{uniffi[0][1]}",
    ])
    pages = {page: [] for page in ANDROID}
    for kind, name, version in parts:
        if kind != "android":
            continue
        group = name.split(":")[0]
        page = next((page for prefix, page in GROUPS.items() if group == prefix or group.startswith(prefix + ".")), None)
        if page is None:
            sys.exit(f"{name} {version}: read its license and add its group to GROUPS")
        pages[page].append((name, version))
    for page, modules in pages.items():
        if not modules:
            sys.exit(f"nothing on the page {page}: take it out of ANDROID and index.tsv")
        write(f"{page}.txt", android_page(page, modules))
    (HERE / "shipped.tsv").write_text(SHIPPED_HEADER + "".join("\t".join(p) + "\n" for p in parts), encoding="utf-8")
    print(f"{len(parts)} parts: {len(rust)} Rust crates, {len(parts) - len(rust)} Android libraries")


if __name__ == "__main__":
    main()
