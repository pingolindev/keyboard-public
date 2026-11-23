// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: Apache-2.0 OR MIT

//! The default settings on the real English dictionary: common typos get
//! fixed, words typed right stay, and words the dictionary lacks are mostly
//! left alone. examples/replay.rs measures the same on thousands of words.

use std::path::PathBuf;
use std::sync::{Arc, OnceLock};

use pingolin_keyboard_engine::dictionary::build;
use pingolin_keyboard_engine::wordlist::WordList;
use pingolin_keyboard_engine::{
    Dictionary, GlideOptions, KeyCenter, Point, SuggestOptions, Suggester, Tap,
};

fn suggester() -> &'static Arc<Suggester> {
    static SUGGESTER: OnceLock<Arc<Suggester>> = OnceLock::new();
    SUGGESTER.get_or_init(|| {
        let dir = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../dictionaries/en-US");
        let read = |name: &str| std::fs::read_to_string(dir.join(name)).unwrap_or_default();
        let list = WordList::parse(
            &read("words.tsv"),
            &read("corrections.tsv"),
            &read("metadata.tsv"),
        )
        .expect("dictionaries/en-US parses");
        let suggester = Suggester::new(Dictionary::new(build(&list).unwrap()).unwrap());
        suggester.set_keys(qwerty());
        suggester
    })
}

/// The letter keys as the app places them (10 units wide, rows 1.3 tall).
fn qwerty() -> Vec<KeyCenter> {
    let rows: [(&str, f32); 3] = [("qwertyuiop", 0.0), ("asdfghjkl", 0.5), ("zxcvbnm", 1.5)];
    let mut keys = Vec::new();
    for (r, (letters, offset)) in rows.iter().enumerate() {
        for (i, c) in letters.chars().enumerate() {
            keys.push(KeyCenter {
                text: c.to_string(),
                x: offset + i as f32 + 0.5,
                y: 1.3 * r as f32 + 0.65,
                width: 1.0,
                height: 1.3,
            });
        }
    }
    keys
}

/// What a space commits after typing `word` with taps on the key centers.
fn commit(word: &str, sentence_start: bool) -> String {
    let keys = qwerty();
    let taps = word
        .chars()
        .map(|c| {
            let lower = c.to_lowercase().next().unwrap();
            let position = keys
                .iter()
                .find(|k| k.text.starts_with(lower))
                .map(|k| Point { x: k.x, y: k.y });
            Tap {
                text: c.to_string(),
                position,
            }
        })
        .collect();
    let options = SuggestOptions {
        autocorrect: true,
        block_offensive: true,
        sentence_start,
        previous_word: None,
        max_results: 5,
        personal: true,
    };
    let out = suggester().suggest(taps, options);
    out.autocorrect.unwrap_or(out.typed)
}

/// Every mismatch at once, so a tuning change shows its whole effect.
fn check(cases: &[(&str, &str)], sentence_start: bool) {
    let wrong: Vec<String> = cases
        .iter()
        .map(|(typed, want)| (typed, want, commit(typed, sentence_start)))
        .filter(|(_, want, got)| got != *want)
        .map(|(typed, want, got)| format!("{typed:?} -> {got:?}, wanted {want:?}"))
        .collect();
    assert!(wrong.is_empty(), "{}", wrong.join("; "));
}

#[test]
fn common_typos_are_fixed() {
    check(
        &[
            ("teh", "the"),
            ("taht", "that"),
            ("adn", "and"),
            ("yuo", "you"),
            ("dont", "don't"),
            ("im", "I'm"),
            ("i", "I"),
            ("recieve", "receive"),
            ("becuase", "because"),
            ("tomorow", "tomorrow"),
            ("wierd", "weird"),
            ("alot", "a lot"),
            ("thnaks", "thanks"),
            ("pleasr", "please"),
            ("monday", "Monday"),
            ("cant", "can't"),
            ("wont", "won't"),
            ("goign", "going"),
            ("realy", "really"),
            ("beleive", "believe"),
            ("hapy", "happy"),
            ("tommorrow", "tomorrow"),
            ("acommodate", "accommodate"),
        ],
        false,
    );
}

#[test]
fn the_capital_of_a_sentence_start_is_kept() {
    check(
        &[("Teh", "The"), ("Realy", "Really"), ("Dont", "Don't")],
        true,
    );
}

#[test]
fn words_typed_right_stay() {
    let words = [
        "the", "hello", "their", "there", "were", "its", "lets", "well", "cafe", "resume",
        "keyboard", "Monday", "receive", "I", "you", "OK",
    ];
    check(&words.map(|w| (w, w)), false);
}

#[test]
fn names_typed_with_a_capital_in_mid_sentence_stay() {
    let names = ["Pingolin", "Zork", "Yasmin", "Okonkwo"];
    check(&names.map(|w| (w, w)), false);
}

/// The words a glide straight through the key centers of `word` gives.
fn glide(word: &str) -> Vec<String> {
    let keys = qwerty();
    let corners: Vec<(f32, f32)> = word
        .chars()
        .filter_map(|c| {
            keys.iter()
                .find(|k| k.text.starts_with(c))
                .map(|k| (k.x, k.y))
        })
        .collect();
    let mut points = Vec::new();
    for w in corners.windows(2) {
        for i in 0..8 {
            let t = i as f32 / 8.0;
            points.push(Point {
                x: w[0].0 + (w[1].0 - w[0].0) * t,
                y: w[0].1 + (w[1].1 - w[0].1) * t,
            });
        }
    }
    let last = corners.last().unwrap();
    points.push(Point {
        x: last.0,
        y: last.1,
    });
    suggester()
        .glide(
            points,
            GlideOptions {
                sentence_start: false,
                previous_word: None,
                max_results: 3,
                personal: true,
            },
        )
        .into_iter()
        .map(|c| c.text)
        .collect()
}

#[test]
fn glides_through_the_keys_spell_the_word() {
    let mut wrong = Vec::new();
    for word in [
        "with", "hello", "great", "keyboard", "ships", "about", "people", "thanks", "quiet",
        "zebra",
    ] {
        let found = glide(word);
        if found.first().map(String::as_str) != Some(word) {
            wrong.push(format!("{word} -> {found:?}"));
        }
    }
    assert!(wrong.is_empty(), "{wrong:#?}");
}
