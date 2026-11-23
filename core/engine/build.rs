// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: Apache-2.0 OR MIT

//! With the `voice` feature, compiles whisper.cpp and ggml (MIT, vendored in
//! `whisper/`, v1.9.5) and the C shim `whisper/pk_voice.cpp` from source.
//! CPU only, no OpenMP, no GPU backends.

use std::env;
use std::path::PathBuf;

const C_FILES: &[&str] = &[
    "ggml/src/ggml.c",
    "ggml/src/ggml-alloc.c",
    "ggml/src/ggml-quants.c",
    "ggml/src/ggml-cpu/ggml-cpu.c",
    "ggml/src/ggml-cpu/quants.c",
];

const CPP_FILES: &[&str] = &[
    "ggml/src/ggml.cpp",
    "ggml/src/ggml-backend.cpp",
    "ggml/src/ggml-backend-meta.cpp",
    "ggml/src/ggml-backend-reg.cpp",
    "ggml/src/ggml-opt.cpp",
    "ggml/src/ggml-threading.cpp",
    "ggml/src/gguf.cpp",
    "ggml/src/ggml-cpu/ggml-cpu.cpp",
    "ggml/src/ggml-cpu/repack.cpp",
    "ggml/src/ggml-cpu/hbm.cpp",
    "ggml/src/ggml-cpu/traits.cpp",
    "ggml/src/ggml-cpu/amx/amx.cpp",
    "ggml/src/ggml-cpu/amx/mmq.cpp",
    "ggml/src/ggml-cpu/tiled/tiled.cpp",
    "ggml/src/ggml-cpu/tiled/tiled-kernel.cpp",
    "ggml/src/ggml-cpu/binary-ops.cpp",
    "ggml/src/ggml-cpu/unary-ops.cpp",
    "ggml/src/ggml-cpu/vec.cpp",
    "ggml/src/ggml-cpu/ops.cpp",
    "src/whisper.cpp",
    // Apple's Neural Engine encoder; elsewhere it compiles to stubs.
    "src/aneforge/whisper-aneforge.cpp",
    "pk_voice.cpp",
];

fn main() {
    println!("cargo:rerun-if-changed=build.rs");
    println!("cargo:rerun-if-env-changed=CARGO_FEATURE_VOICE");
    if env::var_os("CARGO_FEATURE_VOICE").is_none() {
        return;
    }
    let root = PathBuf::from("whisper");
    println!("cargo:rerun-if-changed={}", root.display());
    let arch = env::var("CARGO_CFG_TARGET_ARCH").unwrap_or_default();
    let os = env::var("CARGO_CFG_TARGET_OS").unwrap_or_default();

    // llamafile's matrix code speeds up 64-bit CPUs; on 32-bit ARM it needs
    // half-precision NEON that plain NEON lacks, so ggml's own code runs there.
    let llamafile = arch != "arm";
    let (arch_c, mut arch_cpp): (Vec<&str>, Vec<&str>) = match arch.as_str() {
        "aarch64" | "arm" => (
            vec!["ggml/src/ggml-cpu/arch/arm/quants.c"],
            vec!["ggml/src/ggml-cpu/arch/arm/repack.cpp"],
        ),
        "x86_64" | "x86" => (
            vec!["ggml/src/ggml-cpu/arch/x86/quants.c"],
            vec!["ggml/src/ggml-cpu/arch/x86/repack.cpp"],
        ),
        other => panic!("voice: no ggml CPU code for {other}"),
    };
    if llamafile {
        arch_cpp.push("ggml/src/ggml-cpu/llamafile/sgemm.cpp");
    }

    let configure = |build: &mut cc::Build| {
        build
            .include(root.join("include"))
            .include(root.join("ggml/include"))
            .include(root.join("ggml/src"))
            .include(root.join("ggml/src/ggml-cpu"))
            .define("NDEBUG", None)
            .define("GGML_USE_CPU", None)
            .define("GGML_SCHED_MAX_COPIES", "4")
            .define("_XOPEN_SOURCE", "600")
            .define("_GNU_SOURCE", None)
            .define("WHISPER_VERSION", "\"1.9.5\"")
            .opt_level(3)
            .warnings(false)
            .pic(true);
        if llamafile {
            build.define("GGML_USE_LLAMAFILE", None);
        }
        match arch.as_str() {
            // Every 64-bit ARM phone; newer instructions are not assumed.
            "aarch64" => {
                build.flag("-march=armv8-a");
            }
            "arm" => {
                build.flag("-mfpu=neon").flag("-mfloat-abi=softfp");
            }
            _ => {}
        }
    };

    let mut c = cc::Build::new();
    configure(&mut c);
    c.std("c11");
    for file in C_FILES.iter().chain(arch_c.iter()) {
        c.file(root.join(file));
    }
    c.compile("pk_ggml_c");

    let mut cpp = cc::Build::new();
    configure(&mut cpp);
    cpp.cpp(true).std("c++17");
    if os == "android" {
        // Static libc++: the APK carries no libc++_shared.so.
        cpp.cpp_link_stdlib("c++_static");
    }
    for file in CPP_FILES.iter().chain(arch_cpp.iter()) {
        cpp.file(root.join(file));
    }
    cpp.compile("pk_ggml_cpp");
    if os == "android" {
        println!("cargo:rustc-link-lib=c++abi");
    }
}
