// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: Apache-2.0 OR MIT

//! Times voice typing on a WAV file (16-bit PCM, mono, 16 kHz):
//! `cargo run --release --features voice --example transcribe -- <model> <wav> [threads]`

use pingolin_keyboard_engine::voice::{VoiceModel, read_wav};
use std::time::Instant;

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let [model, wav, rest @ ..] = args.as_slice() else {
        eprintln!("usage: transcribe <model.bin> <audio.wav> [threads]");
        std::process::exit(2);
    };
    let threads = rest.first().and_then(|t| t.parse().ok()).unwrap_or(4);
    let samples = read_wav(&std::fs::read(wav).expect("read wav")).expect("16-bit mono 16 kHz WAV");
    let t = Instant::now();
    let voice = VoiceModel::open(model.clone()).expect("open model");
    let loaded = t.elapsed();
    let t = Instant::now();
    let text = voice
        .transcribe(samples.clone(), Some("en".into()), threads)
        .expect("transcribe");
    println!("text: {text}");
    println!(
        "audio {:.1} s, load {:.2} s, transcribe {:.2} s, {} threads, multilingual {}",
        samples.len() as f64 / 16_000.0,
        loaded.as_secs_f64(),
        t.elapsed().as_secs_f64(),
        threads,
        voice.is_multilingual()
    );
}
