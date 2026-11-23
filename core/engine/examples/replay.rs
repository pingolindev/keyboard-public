// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: Apache-2.0 OR MIT

//! Measures suggestions and autocorrect on simulated typing.
//!
//! ```text
//! cargo run --release --example replay -- <dictionary-dir> [--words N] [--sigma S]
//!     [--seed X] [--kss] [--sentences <tatoeba.tsv>] [--glide] [--set param=value]...
//! ```
//!
//! Every tenth word with frequency 1 to 90 is held out of the dictionary, to
//! play a word it does not know (a name, slang). Words are drawn by frequency
//! and typed on QWERTY, each tap off the key center by Gaussian noise of
//! `sigma` key sizes (default 0.25), some with a letter left out, added or
//! swapped.
//!
//! Reported: how often the committed word is the intended one, how often
//! autocorrect breaks a word typed right or a new word, whether the intended
//! word reaches the strip, keystroke savings, and decoder latency.

use std::time::Instant;

use pingolin_keyboard_engine::dictionary::build;
use pingolin_keyboard_engine::suggest::Params;
use pingolin_keyboard_engine::text::key_of;
use pingolin_keyboard_engine::wordlist::{WordEntry, WordList};
use pingolin_keyboard_engine::{
    Dictionary, GlideOptions, KeyAction, KeyCenter, Point, PredictOptions, SuggestOptions,
    Suggester, Tap, builtin_layout,
};

struct Rng(u64);

impl Rng {
    fn next(&mut self) -> u64 {
        self.0 ^= self.0 << 13;
        self.0 ^= self.0 >> 7;
        self.0 ^= self.0 << 17;
        self.0
    }
    fn unit(&mut self) -> f64 {
        (self.next() >> 11) as f64 / (1u64 << 53) as f64
    }
    fn gauss(&mut self) -> f64 {
        let (u, v) = (self.unit().max(1e-12), self.unit());
        (-2.0 * u.ln()).sqrt() * (2.0 * std::f64::consts::PI * v).cos()
    }
}

/// The letters layout placed like the app does (KeyGeometry.place): the
/// widest row fills the width, shorter rows are centered.
fn layout_keys(row_height: f32) -> Vec<(char, KeyCenter, usize)> {
    let layout = builtin_layout("letters".into()).expect("built-in layout");
    let widest = layout
        .rows
        .iter()
        .map(|r| r.iter().map(|k| k.width).sum::<f32>())
        .fold(0.0, f32::max);
    let mut out = Vec::new();
    for (row, keys) in layout.rows.iter().enumerate() {
        let mut x = (widest - keys.iter().map(|k| k.width).sum::<f32>()) / 2.0;
        for key in keys {
            if let KeyAction::Text { text } = &key.action
                && let [c] = text.chars().collect::<Vec<_>>().as_slice()
                && c.is_alphabetic()
            {
                out.push((
                    *c,
                    KeyCenter {
                        text: text.clone(),
                        x: x + key.width / 2.0,
                        y: row_height * (row as f32 + 0.5),
                        width: key.width,
                        height: row_height,
                    },
                    row,
                ));
            }
            x += key.width;
        }
    }
    out
}

/// The key a tap at (x, y) types: row first, then the nearest key in it.
fn hit(keys: &[(char, KeyCenter, usize)], x: f32, y: f32, row_height: f32) -> char {
    let rows = keys.iter().map(|k| k.2).max().unwrap_or(0);
    let row = ((y / row_height).floor().max(0.0) as usize).min(rows);
    keys.iter()
        .filter(|k| k.2 == row)
        .min_by(|a, b| (a.1.x - x).abs().total_cmp(&(b.1.x - x).abs()))
        .map(|k| k.0)
        .unwrap_or('?')
}

#[derive(Default)]
struct Tally {
    n: usize,
    ok: usize,
}

impl Tally {
    fn add(&mut self, ok: bool) {
        self.n += 1;
        self.ok += ok as usize;
    }
    fn pct(&self) -> String {
        if self.n == 0 {
            "-".into()
        } else {
            format!(
                "{:.1}% ({}/{})",
                100.0 * self.ok as f64 / self.n as f64,
                self.ok,
                self.n
            )
        }
    }
}

const USAGE: &str = "usage: replay <dictionary-dir> [--words N] [--sigma S] [--seed X] [--kss] [--sentences <tsv>] [--glide] [--set param=value]...";

fn set_param(p: &mut Params, name: &str, v: f32) {
    let slot = match name {
        "omit" => &mut p.omit,
        "omit_double" => &mut p.omit_double,
        "insert" => &mut p.insert,
        "insert_repeat" => &mut p.insert_repeat,
        "transpose" => &mut p.transpose,
        "sub_far" => &mut p.sub_far,
        "sub_near" => &mut p.sub_near,
        "spatial_sigma" => &mut p.spatial_sigma,
        "completion_char" => &mut p.completion_char,
        "accent" => &mut p.accent,
        "max_cost" => &mut p.max_cost,
        "glide_pass" => &mut p.glide.pass_radius,
        "glide_end" => &mut p.glide.end_radius,
        "glide_location" => &mut p.glide.location_sigma,
        "glide_ends" => &mut p.glide.end_sigma,
        "glide_shape" => &mut p.glide.shape_sigma,
        "glide_length" => &mut p.glide.length_sigma,
        "glide_language" => &mut p.glide.language_weight,
        "new_word_prior" => &mut p.new_word_prior,
        "new_word_scale" => &mut p.new_word_scale,
        "name_bonus" => &mut p.name_bonus,
        "autocorrect_max_cost" => &mut p.autocorrect_max_cost,
        "autocorrect_margin" => &mut p.autocorrect_margin,
        _ => panic!("unknown parameter {name}"),
    };
    *slot = v;
}

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let dir = args.first().expect(USAGE).clone();
    let mut count = 3000usize;
    let mut sigma = 0.25f64;
    let mut seed = 0x5EEDu64;
    let mut kss = false;
    let mut sentences: Option<String> = None;
    let mut glide = false;
    let mut params = Params::default();
    let mut rest = args[1..].iter();
    while let Some(flag) = rest.next() {
        let mut value = || rest.next().expect(USAGE).clone();
        match flag.as_str() {
            "--words" => count = value().parse().expect("--words"),
            "--sigma" => sigma = value().parse().expect("--sigma"),
            "--seed" => seed = value().parse().expect("--seed"),
            "--kss" => kss = true,
            "--sentences" => sentences = Some(value()),
            "--glide" => glide = true,
            "--set" => {
                let v = value();
                let (name, number) = v.split_once('=').expect("--set name=value");
                set_param(&mut params, name, number.parse().expect("a number"));
            }
            _ => panic!("{USAGE}"),
        }
    }
    let read = |name: &str| std::fs::read_to_string(format!("{dir}/{name}")).unwrap_or_default();
    let list = WordList::parse(
        &read("words.tsv"),
        &read("corrections.tsv"),
        &read("metadata.tsv"),
    )
    .and_then(|l| l.with_pairs(&read("pairs.tsv")))
    .expect("word list");

    if let Some(path) = sentences {
        sentence_replay(&list, &params, &path, count, sigma, seed);
        return;
    }
    let typeable = |w: &str| key_of(w).iter().all(|c| c.is_ascii_lowercase());
    let is_name = |w: &str| {
        let mut chars = w.chars();
        chars.next().is_some_and(|c| c.is_ascii_uppercase())
            && chars.all(|c| c.is_ascii_lowercase())
    };
    // Held out: every tenth name and every tenth rare lower-case word.
    let mut known = Vec::new();
    let mut names = Vec::new();
    let mut rare = Vec::new();
    let (mut seen_names, mut seen_rare) = (0usize, 0usize);
    for w in &list.words {
        if (1..=90).contains(&w.frequency) && w.word.len() > 2 && typeable(&w.word) {
            if is_name(&w.word) {
                seen_names += 1;
                if seen_names % 10 == 0 {
                    names.push(w.clone());
                    continue;
                }
            } else if w.word.chars().all(|c| c.is_ascii_lowercase()) {
                seen_rare += 1;
                if seen_rare % 10 == 0 {
                    rare.push(w.clone());
                    continue;
                }
            }
        }
        known.push(w.clone());
    }
    let held = WordList {
        words: known.clone(),
        ..list.clone()
    };
    let bytes = build(&held).expect("build");
    let dictionary = Dictionary::new(bytes.clone()).expect("load");
    let suggester = Suggester::with_params(dictionary, params.clone());
    let row_height = 1.3f32;
    let keys = layout_keys(row_height);
    suggester.set_keys(keys.iter().map(|k| k.1.clone()).collect());
    let reference =
        pingolin_keyboard_engine::dictionary::Dictionary::from_bytes(&bytes).expect("load");

    // Words drawn by how often they are typed.
    let pool: Vec<&WordEntry> = known
        .iter()
        .filter(|w| w.frequency > 0 && w.flags == 0 && typeable(&w.word))
        .collect();
    let weights: Vec<f64> = pool
        .iter()
        .map(|w| (reference.log_probability(w.frequency) as f64).exp())
        .collect();
    let total: f64 = weights.iter().sum();
    let mut rng = Rng(seed | 1);
    let draw = |rng: &mut Rng| {
        let mut r = rng.unit() * total;
        for (w, p) in pool.iter().zip(&weights) {
            r -= p;
            if r <= 0.0 {
                return *w;
            }
        }
        pool[pool.len() - 1]
    };

    if glide {
        glide_replay(
            &suggester,
            &keys,
            row_height,
            || draw(&mut rng),
            count,
            sigma,
            &mut Rng(seed ^ 0x9E37),
        );
        return;
    }
    let options = SuggestOptions {
        autocorrect: true,
        block_offensive: true,
        sentence_start: false,
        previous_word: None,
        max_results: 5,
        personal: true,
    };
    let mut latencies: Vec<f64> = Vec::new();
    let mut timed = |taps: Vec<Tap>, sentence_start: bool| {
        let t = Instant::now();
        let out = suggester.suggest(
            taps,
            SuggestOptions {
                sentence_start,
                ..options.clone()
            },
        );
        latencies.push(t.elapsed().as_secs_f64() * 1e3);
        out
    };
    let key_center = |c: char| keys.iter().find(|k| k.0 == c).map(|k| k.1.clone());
    let noisy_taps = |letters: &[char], rng: &mut Rng| -> Vec<Tap> {
        letters
            .iter()
            .filter_map(|c| key_center(*c))
            .map(|k| {
                let x = k.x + (rng.gauss() * sigma) as f32 * k.width;
                let y = k.y + (rng.gauss() * sigma) as f32 * k.height;
                Tap {
                    text: hit(&keys, x, y, row_height).to_string(),
                    position: Some(Point { x, y }),
                }
            })
            .collect()
    };
    let exact_taps = |letters: &[char], capital: bool| -> Vec<Tap> {
        letters
            .iter()
            .enumerate()
            .filter_map(|(i, c)| key_center(*c).map(|k| (i, k)))
            .map(|(i, k)| Tap {
                text: if capital && i == 0 {
                    k.text.to_uppercase()
                } else {
                    k.text.clone()
                },
                position: Some(Point { x: k.x, y: k.y }),
            })
            .collect()
    };

    let mut typed_right = Tally::default();
    let mut committed_right = Tally::default();
    let mut fixed = Tally::default(); // typed wrong, committed right
    let (mut missed, mut wrong, mut not_found) = (0usize, 0usize, 0usize);
    let mut broken = Tally::default(); // typed right, committed wrong
    let mut in_strip = Tally::default();
    let (mut keystrokes, mut saved_keystrokes) = (0usize, 0usize);

    for _ in 0..count {
        let word = draw(&mut rng);
        let mut letters = key_of(&word.word);
        // Motor slips besides imprecise taps.
        let slip = rng.unit();
        if letters.len() > 2 && slip < 0.03 {
            let i = rng.next() as usize % letters.len();
            letters.remove(i);
        } else if slip < 0.05 {
            let i = rng.next() as usize % letters.len();
            let c = letters[i];
            letters.insert(i, c);
        } else if letters.len() > 2 && slip < 0.07 {
            let i = rng.next() as usize % (letters.len() - 1);
            letters.swap(i, i + 1);
        } else if slip < 0.10
            && let Some(i) = (1..letters.len()).find(|&i| letters[i] == letters[i - 1])
        {
            // One of a double letter left out ("realy"), a common slip.
            letters.remove(i);
        }
        let taps = noisy_taps(&letters, &mut rng);
        let typed: String = taps.iter().map(|t| t.text.as_str()).collect();
        let out = timed(taps, false);
        let committed = out.autocorrect.clone().unwrap_or_else(|| typed.clone());
        let intended = &word.word;
        let typed_ok = typed == *intended;
        typed_right.add(typed_ok);
        committed_right.add(committed == *intended);
        if typed_ok {
            broken.add(committed != *intended);
        } else {
            fixed.add(committed == *intended);
            if committed != *intended {
                if out.autocorrect.is_some() {
                    wrong += 1;
                } else if out.candidates.iter().any(|c| c.text == *intended) {
                    missed += 1;
                } else {
                    not_found += 1;
                }
            }
        }
        let strip: Vec<&str> = std::iter::once(committed.as_str())
            .chain(std::iter::once(typed.as_str()))
            .chain(out.candidates.iter().map(|c| c.text.as_str()))
            .take(3)
            .collect();
        in_strip.add(strip.contains(&intended.as_str()));

        // Keystroke savings, typing without noise and picking from the strip.
        if !kss {
            continue;
        }
        let full = key_of(intended);
        let mut cost = full.len() + 1;
        for k in 1..full.len() {
            let out = timed(exact_taps(&full[..k], false), false);
            let prefix: String = full[..k].iter().collect();
            let middle = out.autocorrect.clone().unwrap_or(prefix);
            let shown: Vec<&str> = std::iter::once(middle.as_str())
                .chain(out.candidates.iter().map(|c| c.text.as_str()))
                .take(3)
                .collect();
            if shown.contains(&intended.as_str()) {
                cost = k + 1;
                break;
            }
        }
        keystrokes += full.len() + 1;
        saved_keystrokes += full.len() + 1 - cost;
    }

    // Words the dictionary does not know, typed exactly: names with their
    // capital (at a sentence start, and in mid-sentence) and rare words.
    let mut names_start = Tally::default();
    let mut names_mid = Tally::default();
    let mut rare_kept = Tally::default();
    let step = |n: usize| (n / count.max(1)).max(1);
    let mut keeps = |w: &WordEntry, capital: bool, sentence_start: bool| {
        let taps = exact_taps(&key_of(&w.word), capital);
        let typed: String = taps.iter().map(|t| t.text.as_str()).collect();
        let out = timed(taps, sentence_start);
        out.autocorrect.is_none() || out.autocorrect.as_deref() == Some(typed.as_str())
    };
    for w in names.iter().step_by(step(names.len())).take(count) {
        names_start.add(keeps(w, true, true));
        names_mid.add(keeps(w, true, false));
    }
    for w in rare.iter().step_by(step(rare.len())).take(count) {
        rare_kept.add(keeps(w, false, false));
    }

    latencies.sort_by(f64::total_cmp);
    let pct = |p: f64| latencies[((latencies.len() as f64 - 1.0) * p) as usize];
    let mean = latencies.iter().sum::<f64>() / latencies.len() as f64;
    println!(
        "dictionary: {} words ({} names and {} rare words held out), sigma {sigma}, seed {seed}",
        known.len(),
        names.len(),
        rare.len()
    );
    // The settings in use, so a stale build cannot pass for the current one.
    println!("{params:?}");
    println!("typed right (before autocorrect):   {}", typed_right.pct());
    println!(
        "committed right (after):            {}",
        committed_right.pct()
    );
    println!("  typos fixed:                      {}", fixed.pct());
    println!(
        "  typos left: {missed} not corrected, {wrong} corrected wrongly, {not_found} not found"
    );
    println!("  right words broken:               {}", broken.pct());
    println!("intended word in the strip:         {}", in_strip.pct());
    println!("names kept, sentence start:         {}", names_start.pct());
    println!("names kept, mid-sentence:           {}", names_mid.pct());
    println!("rare words kept:                    {}", rare_kept.pct());
    if kss {
        println!(
            "keystroke savings (strip picks):    {:.1}%",
            100.0 * saved_keystrokes as f64 / keystrokes as f64
        );
    }
    println!(
        "latency ms: mean {mean:.3}, p50 {:.3}, p95 {:.3}, p99 {:.3}, max {:.3} ({} calls)",
        pct(0.5),
        pct(0.95),
        pct(0.99),
        latencies[latencies.len() - 1],
        latencies.len()
    );
}

/// The words of a sentence (letters and inner apostrophes), each with
/// whether it starts a sentence.
fn sentence_words(sentence: &str) -> Vec<(String, bool)> {
    let mut out = Vec::new();
    let mut word = String::new();
    let mut start = true;
    let chars: Vec<char> = sentence
        .chars()
        .map(|c| if c == '\u{2019}' { '\'' } else { c })
        .collect();
    for (i, &c) in chars.iter().enumerate() {
        let joins =
            c == '\'' && !word.is_empty() && chars.get(i + 1).is_some_and(|n| n.is_alphabetic());
        if c.is_alphabetic() || joins {
            word.push(c);
            continue;
        }
        if !word.is_empty() {
            out.push((std::mem::take(&mut word), start));
            start = false;
        }
        if matches!(c, '.' | '!' | '?') {
            start = true;
        }
    }
    if !word.is_empty() {
        out.push((word, start));
    }
    out
}

/// Replays held-out Tatoeba sentences (id divisible by 100, the ones
/// `dictc import-pairs` leaves out) word by word: predictions before each
/// word, noisy typing with the previous word as context, and keystrokes when
/// picking from the strip.
fn sentence_replay(
    list: &WordList,
    params: &Params,
    path: &str,
    count: usize,
    sigma: f64,
    seed: u64,
) {
    let bytes = build(list).expect("build");
    let suggester = Suggester::with_params(Dictionary::new(bytes).expect("load"), params.clone());
    let row_height = 1.3f32;
    let keys = layout_keys(row_height);
    suggester.set_keys(keys.iter().map(|k| k.1.clone()).collect());
    let known: std::collections::HashSet<&str> =
        list.words.iter().map(|w| w.word.as_str()).collect();
    let key_center = |c: char| keys.iter().find(|k| k.0 == c).map(|k| k.1.clone());
    let mut rng = Rng(seed | 1);
    let text = std::fs::read_to_string(path).expect("sentences");
    let (mut predicted, mut typed_right, mut right, mut new_kept) = (
        Tally::default(),
        Tally::default(),
        Tally::default(),
        Tally::default(),
    );
    let (mut keystrokes, mut saved) = (0usize, 0usize);
    let mut latencies: Vec<f64> = Vec::new();
    let mut words = 0usize;
    'sentences: for line in text.lines() {
        let mut fields = line.split('\t');
        let (Some(id), Some(_), Some(sentence)) = (fields.next(), fields.next(), fields.next())
        else {
            continue;
        };
        if !id.parse::<u64>().is_ok_and(|id| id % 100 == 0) {
            continue;
        }
        let toks = sentence_words(sentence);
        if toks
            .iter()
            .any(|(w, _)| ["Tom", "Mary", "Tom's", "Mary's"].contains(&w.as_str()))
        {
            continue;
        }
        let mut previous: Option<String> = None;
        for (word, start) in toks {
            let letters = key_of(&word);
            if letters.is_empty() || !letters.iter().all(|c| key_center(*c).is_some()) {
                previous = None;
                continue;
            }
            let capital = word.chars().next().is_some_and(char::is_uppercase);
            let context = |sentence_start: bool| SuggestOptions {
                autocorrect: true,
                block_offensive: true,
                sentence_start,
                previous_word: previous.clone(),
                max_results: 5,
                personal: true,
            };
            let t = Instant::now();
            let predictions = suggester.predict(PredictOptions {
                sentence_start: start,
                previous_word: previous.clone(),
                max_results: 3,
                personal: true,
            });
            latencies.push(t.elapsed().as_secs_f64() * 1e3);
            let hit = predictions.iter().any(|c| c.text == word);
            predicted.add(hit);

            let taps: Vec<Tap> = letters
                .iter()
                .enumerate()
                .filter_map(|(i, c)| key_center(*c).map(|k| (i, k)))
                .map(|(i, k)| {
                    let x = k.x + (rng.gauss() * sigma) as f32 * k.width;
                    let y = k.y + (rng.gauss() * sigma) as f32 * k.height;
                    let letter = hit_key(&keys, x, y, row_height);
                    let text = if capital && i == 0 {
                        letter.to_uppercase().collect()
                    } else {
                        letter.to_string()
                    };
                    Tap {
                        text,
                        position: Some(Point { x, y }),
                    }
                })
                .collect();
            let typed: String = taps.iter().map(|t| t.text.as_str()).collect();
            let t = Instant::now();
            let out = suggester.suggest(taps, context(start));
            latencies.push(t.elapsed().as_secs_f64() * 1e3);
            let committed = out.autocorrect.clone().unwrap_or_else(|| typed.clone());
            let in_dictionary =
                known.contains(word.as_str()) || known.contains(word.to_lowercase().as_str());
            if in_dictionary {
                typed_right.add(typed == word);
                right.add(committed == word);
            } else if typed == word {
                new_kept.add(committed == word);
            }

            // Keystrokes: a prediction costs one tap; else the fewest
            // letters after which the strip shows the word, plus a tap.
            let full = letters.len() + 1;
            let mut cost = if hit { 1 } else { full };
            if !hit {
                for k in 1..letters.len() {
                    let taps: Vec<Tap> = letters[..k]
                        .iter()
                        .enumerate()
                        .filter_map(|(i, c)| key_center(*c).map(|kc| (i, kc)))
                        .map(|(i, kc)| Tap {
                            text: if capital && i == 0 {
                                kc.text.to_uppercase()
                            } else {
                                kc.text.clone()
                            },
                            position: Some(Point { x: kc.x, y: kc.y }),
                        })
                        .collect();
                    let prefix: String = taps.iter().map(|t| t.text.as_str()).collect();
                    let out = suggester.suggest(taps, context(start));
                    let middle = out.autocorrect.clone().unwrap_or(prefix);
                    let shown = std::iter::once(middle)
                        .chain(out.candidates.iter().map(|c| c.text.clone()))
                        .take(3);
                    if shown.into_iter().any(|w| w == word) {
                        cost = k + 1;
                        break;
                    }
                }
            }
            keystrokes += full;
            saved += full - cost;
            previous = Some(word);
            words += 1;
            if words >= count {
                break 'sentences;
            }
        }
    }
    latencies.sort_by(f64::total_cmp);
    let pct = |p: f64| latencies[((latencies.len() as f64 - 1.0) * p) as usize];
    println!("{params:?}");
    println!("sentences: {words} words from held-out Tatoeba sentences, sigma {sigma}");
    println!("next word among 3 predictions:      {}", predicted.pct());
    println!("typed right (dictionary words):     {}", typed_right.pct());
    println!("committed right (dictionary words): {}", right.pct());
    println!("unknown words typed right, kept:    {}", new_kept.pct());
    println!(
        "keystroke savings (strip + predictions): {:.1}%",
        100.0 * saved as f64 / keystrokes as f64
    );
    println!(
        "latency ms: p50 {:.3}, p95 {:.3}, p99 {:.3}, max {:.3} ({} calls)",
        pct(0.5),
        pct(0.95),
        pct(0.99),
        latencies[latencies.len() - 1],
        latencies.len()
    );
}

/// The key letter a tap at (x, y) types (see `hit`).
fn hit_key(keys: &[(char, KeyCenter, usize)], x: f32, y: f32, row_height: f32) -> char {
    hit(keys, x, y, row_height)
}

/// Glide typing on simulated paths: the finger goes through each letter's key
/// with Gaussian noise of `sigma` key sizes, rounding the corners a little
/// (one round of corner cutting at 15%), as a hand does.
fn glide_replay<'a>(
    suggester: &Suggester,
    keys: &[(char, KeyCenter, usize)],
    row_height: f32,
    mut draw: impl FnMut() -> &'a WordEntry,
    count: usize,
    sigma: f64,
    rng: &mut Rng,
) {
    let center = |c: char| {
        keys.iter()
            .find(|k| k.0 == c)
            .map(|k| (k.1.x as f64, k.1.y as f64))
    };
    let (mut top1, mut top3, mut top5, mut tried) = (0usize, 0usize, 0usize, 0usize);
    let mut latencies = Vec::new();
    let mut misses = Vec::new();
    while tried < count {
        let word = &draw().word;
        let letters: Vec<char> = key_of(word);
        let mut corners: Vec<(f64, f64)> = Vec::new();
        let mut ok = true;
        for (i, c) in letters.iter().enumerate() {
            if i > 0 && letters[i - 1] == *c {
                continue;
            }
            match center(*c) {
                Some((x, y)) => corners.push((
                    x + rng.gauss() * sigma,
                    y + rng.gauss() * sigma * row_height as f64,
                )),
                None => ok = false,
            }
        }
        if !ok || corners.len() < 2 {
            continue;
        }
        tried += 1;
        for _ in 0..1 {
            let mut cut = vec![corners[0]];
            for w in corners.windows(2) {
                let (a, b) = (w[0], w[1]);
                cut.push((0.85 * a.0 + 0.15 * b.0, 0.85 * a.1 + 0.15 * b.1));
                cut.push((0.15 * a.0 + 0.85 * b.0, 0.15 * a.1 + 0.85 * b.1));
            }
            cut.push(*corners.last().unwrap());
            corners = cut;
        }
        // Touch events about every 0.15 key along the path.
        let mut points = Vec::new();
        for w in corners.windows(2) {
            let (a, b) = (w[0], w[1]);
            let d = ((b.0 - a.0).powi(2) + (b.1 - a.1).powi(2)).sqrt();
            let steps = (d / 0.15).ceil().max(1.0) as usize;
            for s in 0..steps {
                let t = s as f64 / steps as f64;
                points.push(Point {
                    x: (a.0 + (b.0 - a.0) * t) as f32,
                    y: (a.1 + (b.1 - a.1) * t) as f32,
                });
            }
        }
        let last = corners.last().unwrap();
        points.push(Point {
            x: last.0 as f32,
            y: last.1 as f32,
        });
        let t = Instant::now();
        let found = suggester.glide(
            points,
            GlideOptions {
                sentence_start: false,
                previous_word: None,
                max_results: 5,
                personal: false,
            },
        );
        latencies.push(t.elapsed().as_secs_f64() * 1e3);
        let rank = found
            .iter()
            .position(|c| c.text.to_lowercase() == word.to_lowercase());
        match rank {
            Some(0) => top1 += 1,
            _ if misses.len() < 12 => misses.push(format!(
                "{word} -> {}",
                found
                    .iter()
                    .take(3)
                    .map(|c| c.text.as_str())
                    .collect::<Vec<_>>()
                    .join(", ")
            )),
            _ => {}
        }
        if rank.is_some_and(|r| r < 3) {
            top3 += 1;
        }
        if rank.is_some() {
            top5 += 1;
        }
    }
    let pct = |n: usize| 100.0 * n as f64 / tried.max(1) as f64;
    println!("glide paths: {tried}, noise {sigma} key");
    println!("intended word first:    {:.1}%", pct(top1));
    println!("intended word in top 3: {:.1}%", pct(top3));
    println!("intended word in top 5: {:.1}%", pct(top5));
    latencies.sort_by(f64::total_cmp);
    let at = |q: f64| latencies[((latencies.len() - 1) as f64 * q) as usize];
    println!(
        "latency ms: p50 {:.2}, p95 {:.2}, max {:.2}",
        at(0.5),
        at(0.95),
        at(1.0)
    );
    for m in misses {
        println!("  miss: {m}");
    }
}
