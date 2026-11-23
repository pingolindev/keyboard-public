// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: Apache-2.0 OR MIT

//! The user's own words, learned on the phone: how often each word was typed
//! and which word came before it. The app decides when learning is allowed
//! and where the JSON this produces is kept.
//!
//! A word typed once is only remembered; from the second time on, or at once
//! when the user keeps it against an autocorrection, it is a known word:
//! never autocorrected, and suggested like a dictionary word.
//!
//! JSON format (`format` and `version` are required, the rest may be
//! missing):
//!
//! ```json
//! {"format": "pingolin-keyboard-words", "version": 1,
//!  "words": [{"word": "Pingolin", "count": 3, "last_used": 20414, "kept": true}],
//!  "pairs": [{"previous": "hello", "next": "there", "count": 2, "last_used": 20414}]}
//! ```
//!
//! `last_used` counts days since 1970-01-01. A pair's `previous` is in lower
//! case, or `<s>` for the start of a sentence.

use std::collections::{HashMap, HashSet};

use serde::{Deserialize, Serialize};

use crate::dictionary::{BuildOptions, Dictionary, build_with};
use crate::text::nfc;
use crate::wordlist::{SENTENCE_START, WordEntry, WordList, check_word};

/// Most words remembered; the least used beyond this are forgotten.
pub const MAX_WORDS: usize = 20_000;
/// Most word pairs remembered.
pub const MAX_PAIRS: usize = 50_000;
/// Uses after which a typed word is a known word.
pub const KNOWN_AFTER: u32 = 2;
const MAX_COUNT: u32 = 1_000_000;
/// Largest JSON accepted.
pub const MAX_JSON: usize = 16 << 20;
pub const FORMAT: &str = "pingolin-keyboard-words";
pub const FORMAT_VERSION: u32 = 1;
/// Next words returned per previous word.
const FOLLOWERS: usize = 8;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
struct WordStat {
    count: u32,
    last_day: u32,
    kept: bool,
}

impl WordStat {
    fn known(&self) -> bool {
        self.kept || self.count >= KNOWN_AFTER
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
struct PairStat {
    count: u32,
    last_day: u32,
}

#[derive(Debug, Clone, Default, PartialEq)]
pub struct UserWords {
    words: HashMap<String, WordStat>,
    /// previous (lower case, or `<s>`) -> next -> stat
    pairs: HashMap<String, HashMap<String, PairStat>>,
    pair_count: usize,
    /// Kept words in lower case: never autocorrected, fixed corrections
    /// included.
    kept: HashSet<String>,
}

#[derive(Serialize, Deserialize)]
struct FileFormat {
    format: String,
    version: u32,
    #[serde(default)]
    words: Vec<WordRecord>,
    #[serde(default)]
    pairs: Vec<PairRecord>,
}

#[derive(Serialize, Deserialize)]
struct WordRecord {
    word: String,
    count: u32,
    #[serde(default)]
    last_used: u32,
    #[serde(default)]
    kept: bool,
}

#[derive(Serialize, Deserialize)]
struct PairRecord {
    previous: String,
    next: String,
    count: u32,
    #[serde(default)]
    last_used: u32,
}

/// What an import added.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct Merged {
    pub words: usize,
    pub pairs: usize,
    /// Records refused (not words, zero counts).
    pub skipped: usize,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct JsonError(pub String);

impl std::fmt::Display for JsonError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for JsonError {}

/// A word as stored: NFC, and a dictionary word.
fn word_form(word: &str) -> Option<String> {
    let word = nfc(word.trim());
    check_word(&word).ok().map(|_| word)
}

/// A pair's previous word as stored: lower case, or the sentence start.
fn previous_form(previous: &str) -> Option<String> {
    if previous == SENTENCE_START {
        return Some(SENTENCE_START.to_owned());
    }
    word_form(previous).map(|w| w.to_lowercase())
}

/// The dictionary frequency of a word typed `count` times, in steps at
/// powers of two.
fn frequency(count: u32) -> u8 {
    let steps = (1.0 + count as f32).log2().floor();
    (40.0 * steps).clamp(1.0, 255.0) as u8
}

/// ln P for the user's words: from -10 (typed twice) up to -4.
fn log_probabilities() -> Vec<f32> {
    (0..256).map(|f| -10.0 + 6.0 * f as f32 / 255.0).collect()
}

impl UserWords {
    /// The user typed `word`, after `previous` (`<s>` at a sentence start).
    /// Returns whether the compiled dictionary is out of date: a word became
    /// known or was dropped. New counts alone wait for the next compile.
    pub fn learn(&mut self, word: &str, previous: Option<&str>, day: u32) -> bool {
        let Some(word) = word_form(word) else {
            return false;
        };
        if let Some(previous) = previous.and_then(previous_form) {
            let next = self.pairs.entry(previous).or_default();
            let stat = next.entry(word.clone()).or_default();
            if stat.count == 0 {
                self.pair_count += 1;
            }
            stat.count = (stat.count + 1).min(MAX_COUNT);
            stat.last_day = stat.last_day.max(day);
        }
        let stat = self.words.entry(word).or_default();
        let was_known = stat.known();
        stat.count = (stat.count + 1).min(MAX_COUNT);
        stat.last_day = stat.last_day.max(day);
        let now_known = stat.known();
        let dropped = self.trim();
        (now_known && !was_known) || dropped
    }

    /// The user kept `word` against an autocorrection: a known word at once.
    pub fn keep(&mut self, word: &str, day: u32) -> bool {
        let Some(word) = word_form(word) else {
            return false;
        };
        self.kept.insert(word.to_lowercase());
        let stat = self.words.entry(word).or_default();
        let changed = !stat.kept;
        stat.kept = true;
        stat.count = stat.count.max(KNOWN_AFTER);
        stat.last_day = stat.last_day.max(day);
        let dropped = self.trim();
        changed || dropped
    }

    /// Forgets a word and the pairs it is in.
    pub fn forget(&mut self, word: &str) -> bool {
        let Some(word) = word_form(word) else {
            return false;
        };
        let removed = self.words.remove(&word).is_some();
        let lower = word.to_lowercase();
        let mut gone = self.pairs.remove(&lower).map_or(0, |m| m.len());
        for next in self.pairs.values_mut() {
            if next.remove(&word).is_some() {
                gone += 1;
            }
        }
        self.pairs.retain(|_, next| !next.is_empty());
        self.pair_count -= gone;
        self.index_kept();
        removed || gone > 0
    }

    /// Whether the user kept this typed word (in lower case) against an
    /// autocorrection.
    pub fn is_kept(&self, lower: &str) -> bool {
        self.kept.contains(lower)
    }

    fn index_kept(&mut self) {
        self.kept = self
            .words
            .iter()
            .filter(|(_, s)| s.kept)
            .map(|(w, _)| w.to_lowercase())
            .collect();
    }

    pub fn clear(&mut self) {
        *self = UserWords::default();
    }

    pub fn is_known(&self, word: &str) -> bool {
        word_form(word)
            .and_then(|w| self.words.get(&w).copied())
            .is_some_and(|s| s.known())
    }

    /// The known words, with how often each was typed, most used first.
    pub fn known_words(&self) -> Vec<(String, u32)> {
        let mut out: Vec<(String, u32)> = self
            .words
            .iter()
            .filter(|(_, s)| s.known())
            .map(|(w, s)| (w.clone(), s.count))
            .collect();
        out.sort_by(|a, b| b.1.cmp(&a.1).then_with(|| a.0.cmp(&b.0)));
        out
    }

    pub fn word_count(&self) -> usize {
        self.words.len()
    }

    pub fn pair_count(&self) -> usize {
        self.pair_count
    }

    /// The user's next words after `previous` (or a sentence start), with
    /// ln P(next | previous), most likely first.
    pub fn followers(&self, previous: Option<&str>, sentence_start: bool) -> Vec<(String, f32)> {
        let key = match previous {
            Some(p) => previous_form(p),
            None if sentence_start => Some(SENTENCE_START.to_owned()),
            None => None,
        };
        let Some(next) = key.and_then(|k| self.pairs.get(&k)) else {
            return Vec::new();
        };
        let total: f64 = next.values().map(|s| f64::from(s.count)).sum();
        let mut out: Vec<(String, f32)> = next
            .iter()
            .map(|(w, s)| {
                (
                    w.clone(),
                    ((f64::from(s.count) + 0.5) / (total + 1.0)).ln() as f32,
                )
            })
            .collect();
        out.sort_by(|a, b| b.1.total_cmp(&a.1).then_with(|| a.0.cmp(&b.0)));
        out.truncate(FOLLOWERS);
        out
    }

    /// The known words as a dictionary, or None if there are none.
    pub fn compile(&self) -> Option<Dictionary> {
        compile_entries(self.known_entries())
    }

    /// What `compile` needs, quick to take: the compiling itself can then
    /// happen without holding the words.
    pub fn known_entries(&self) -> Vec<WordEntry> {
        self.words
            .iter()
            .filter(|(_, s)| s.known())
            .map(|(w, s)| WordEntry {
                word: w.clone(),
                frequency: frequency(s.count),
                flags: 0,
            })
            .collect()
    }
}

/// Compiles entries taken with `UserWords::known_entries`.
pub fn compile_entries(mut words: Vec<WordEntry>) -> Option<Dictionary> {
    if words.is_empty() {
        return None;
    }
    // A fixed order, most used first, so the same words compile alike.
    words.sort_by(|a, b| {
        b.frequency
            .cmp(&a.frequency)
            .then_with(|| a.word.cmp(&b.word))
    });
    {
        let list = WordList {
            metadata: vec![("locale".into(), "user".into())],
            words,
            ..WordList::default()
        };
        let options = BuildOptions {
            log_probabilities: Some(log_probabilities()),
        };
        let bytes = build_with(&list, &options).ok()?;
        Dictionary::from_bytes(&bytes).ok()
    }
}

impl UserWords {
    /// Everything, as JSON (see the module comment).
    pub fn to_json(&self) -> String {
        let mut words: Vec<WordRecord> = self
            .words
            .iter()
            .map(|(w, s)| WordRecord {
                word: w.clone(),
                count: s.count,
                last_used: s.last_day,
                kept: s.kept,
            })
            .collect();
        words.sort_by(|a, b| a.word.cmp(&b.word));
        let mut pairs: Vec<PairRecord> = self
            .pairs
            .iter()
            .flat_map(|(previous, next)| {
                next.iter().map(move |(n, s)| PairRecord {
                    previous: previous.clone(),
                    next: n.clone(),
                    count: s.count,
                    last_used: s.last_day,
                })
            })
            .collect();
        pairs.sort_by(|a, b| (&a.previous, &a.next).cmp(&(&b.previous, &b.next)));
        let file = FileFormat {
            format: FORMAT.into(),
            version: FORMAT_VERSION,
            words,
            pairs,
        };
        serde_json::to_string(&file).unwrap_or_default()
    }

    /// Adds what a JSON export holds: counts and dates take the higher
    /// value, so importing the same file twice changes nothing. Records that
    /// are not words are skipped; a file that is not this format is refused.
    pub fn merge_json(&mut self, json: &str) -> Result<Merged, JsonError> {
        if json.len() > MAX_JSON {
            return Err(JsonError("the file is too large".into()));
        }
        let file: FileFormat =
            serde_json::from_str(json).map_err(|e| JsonError(format!("not a word list: {e}")))?;
        if file.format != FORMAT || file.version != FORMAT_VERSION {
            return Err(JsonError(
                "not a Pingolin Keyboard word list of a known version".into(),
            ));
        }
        let mut merged = Merged::default();
        for r in file.words {
            let (Some(word), true) = (word_form(&r.word), r.count > 0) else {
                merged.skipped += 1;
                continue;
            };
            let stat = self.words.entry(word).or_default();
            stat.count = stat.count.max(r.count.min(MAX_COUNT));
            stat.last_day = stat.last_day.max(r.last_used);
            stat.kept |= r.kept;
            merged.words += 1;
        }
        for r in file.pairs {
            let (Some(previous), Some(next), true) =
                (previous_form(&r.previous), word_form(&r.next), r.count > 0)
            else {
                merged.skipped += 1;
                continue;
            };
            let stat = self
                .pairs
                .entry(previous)
                .or_default()
                .entry(next)
                .or_default();
            if stat.count == 0 {
                self.pair_count += 1;
            }
            stat.count = stat.count.max(r.count.min(MAX_COUNT));
            stat.last_day = stat.last_day.max(r.last_used);
            merged.pairs += 1;
        }
        self.trim();
        self.index_kept();
        Ok(merged)
    }

    /// Keeps within the limits: forgets the words and pairs used least
    /// recently, a tenth at a time. Kept words go last. Returns whether
    /// words were dropped.
    fn trim(&mut self) -> bool {
        let dropped = self.words.len() > MAX_WORDS;
        if dropped {
            let mut order: Vec<(bool, u32, u32, String)> = self
                .words
                .iter()
                .map(|(w, s)| (s.kept, s.last_day, s.count, w.clone()))
                .collect();
            order.sort();
            let drop = self.words.len() - MAX_WORDS * 9 / 10;
            for (_, _, _, w) in order.into_iter().take(drop) {
                self.words.remove(&w);
            }
            self.index_kept();
        }
        if self.pair_count > MAX_PAIRS {
            let mut order: Vec<(u32, u32, String, String)> = self
                .pairs
                .iter()
                .flat_map(|(p, next)| {
                    next.iter()
                        .map(move |(n, s)| (s.last_day, s.count, p.clone(), n.clone()))
                })
                .collect();
            order.sort();
            let drop = self.pair_count - MAX_PAIRS * 9 / 10;
            for (_, _, p, n) in order.into_iter().take(drop) {
                if let Some(next) = self.pairs.get_mut(&p) {
                    next.remove(&n);
                }
            }
            self.pairs.retain(|_, next| !next.is_empty());
            self.pair_count = self.pairs.values().map(HashMap::len).sum();
        }
        dropped
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::text::key_of;

    #[test]
    fn a_word_is_known_from_its_second_use() {
        let mut u = UserWords::default();
        assert!(!u.learn("Pingolin", None, 1), "once: only remembered");
        assert!(!u.is_known("Pingolin"));
        assert!(
            u.learn("Pingolin", None, 2),
            "twice: known, so the dictionary changes"
        );
        assert!(u.is_known("Pingolin"));
        assert_eq!(u.known_words(), vec![("Pingolin".to_string(), 2)]);
        // Later uses only count: the next compile picks them up.
        assert!(!u.learn("Pingolin", None, 3));
        assert!(!u.learn("Pingolin", None, 3));
    }

    #[test]
    fn keeping_a_word_makes_it_known_at_once() {
        let mut u = UserWords::default();
        assert!(u.keep("Teh", 1));
        assert!(u.is_known("Teh"));
        assert!(u.is_kept("teh"), "looked up in lower case");
        assert!(!u.keep("Teh", 1));
        u.forget("Teh");
        assert!(!u.is_kept("teh"));
        u.keep("ill", 1);
        let mut v = UserWords::default();
        v.merge_json(&u.to_json()).unwrap();
        assert!(v.is_kept("ill"), "kept survives an export");
    }

    #[test]
    fn only_words_are_learned() {
        let mut u = UserWords::default();
        for bad in ["", "a b", "x1", "http://x", "''", &"a".repeat(60)] {
            u.learn(bad, None, 1);
            u.keep(bad, 1);
        }
        assert_eq!(u.word_count(), 0);
        u.learn("ok", Some("not a word!"), 1);
        assert_eq!(u.pair_count(), 0);
    }

    #[test]
    fn pairs_become_followers() {
        let mut u = UserWords::default();
        for _ in 0..3 {
            u.learn("there", Some("Hello"), 1);
        }
        u.learn("you", Some("hello"), 1);
        u.learn("Hi", Some(SENTENCE_START), 1);
        let after: Vec<String> = u
            .followers(Some("HELLO"), false)
            .into_iter()
            .map(|(w, _)| w)
            .collect();
        assert_eq!(after, ["there", "you"]);
        let p = u.followers(Some("hello"), false)[0].1;
        assert!((p - (3.5f32 / 5.0).ln()).abs() < 1e-5);
        assert_eq!(u.followers(None, true)[0].0, "Hi");
        assert!(u.followers(None, false).is_empty());
        assert!(u.followers(Some("nobody"), false).is_empty());
    }

    #[test]
    fn forgetting_a_word_drops_its_pairs() {
        let mut u = UserWords::default();
        u.learn("there", Some("hello"), 1);
        u.learn("hello", Some("oh"), 1);
        assert_eq!(u.pair_count(), 2);
        assert!(u.forget("hello"));
        assert_eq!(u.pair_count(), 0);
        assert_eq!(u.word_count(), 1);
        assert!(!u.forget("hello"));
        u.clear();
        assert_eq!(u.word_count(), 0);
    }

    #[test]
    fn known_words_compile_into_a_dictionary() {
        let mut u = UserWords::default();
        assert!(u.compile().is_none());
        u.keep("Pingolin", 1);
        u.learn("once", None, 1);
        for _ in 0..20 {
            u.learn("often", None, 1);
        }
        let d = u.compile().unwrap();
        assert_eq!(d.word_count(), 2);
        let pingolin = d.find(&key_of("pingolin")).unwrap();
        let often = d.find(&key_of("often")).unwrap();
        assert!(
            d.find(&key_of("once")).is_none(),
            "typed once: not a word yet"
        );
        let p = |node| d.log_probability(d.entry(d.entries(node).start).frequency);
        assert!(p(often) > p(pingolin));
        assert!(p(pingolin) >= -10.0 && p(often) <= -4.0);
        assert_eq!(
            d.spelling(d.entries(pingolin).start, &key_of("pingolin")),
            "Pingolin"
        );
    }

    #[test]
    fn json_round_trip() {
        let mut u = UserWords::default();
        u.keep("Pingolin", 20000);
        u.learn("there", Some("hello"), 20001);
        u.learn("Hi", Some(SENTENCE_START), 20002);
        let json = u.to_json();
        let mut v = UserWords::default();
        let merged = v.merge_json(&json).unwrap();
        assert_eq!(
            merged,
            Merged {
                words: 3,
                pairs: 2,
                skipped: 0
            }
        );
        assert_eq!(u, v);
        // Importing the same file again changes nothing.
        v.merge_json(&json).unwrap();
        assert_eq!(u, v);
    }

    #[test]
    fn imports_take_the_higher_counts_and_skip_non_words() {
        let mut u = UserWords::default();
        u.learn("hello", None, 5);
        let json = r#"{"format":"pingolin-keyboard-words","version":1,
            "words":[{"word":"hello","count":7,"last_used":3},{"word":"a b","count":2},
                     {"word":"zero","count":0},{"word":"café","count":2,"kept":true}],
            "pairs":[{"previous":"<s>","next":"hello","count":2},{"previous":"x1","next":"hello","count":1}],
            "extra":"ignored"}"#;
        let merged = u.merge_json(json).unwrap();
        assert_eq!(
            merged,
            Merged {
                words: 2,
                pairs: 1,
                skipped: 3
            }
        );
        assert_eq!(
            u.known_words(),
            vec![("hello".to_string(), 7), ("café".to_string(), 2)]
        );
    }

    #[test]
    fn imports_refuse_other_files() {
        let mut u = UserWords::default();
        for bad in [
            "",
            "[]",
            "{}",
            r#"{"format":"other","version":1}"#,
            r#"{"format":"pingolin-keyboard-words","version":2}"#,
            r#"{"format":"pingolin-keyboard-words","version":1,"words":[{"word":"x","count":-1}]}"#,
            r#"{"format":"pingolin-keyboard-words","version":1,"words":"x"}"#,
        ] {
            assert!(u.merge_json(bad).is_err(), "{bad}");
        }
        let deep = format!("{}{}", "[".repeat(10_000), "]".repeat(10_000));
        assert!(u.merge_json(&deep).is_err());
        assert_eq!(u.word_count(), 0);
    }

    #[test]
    fn mutated_json_never_panics() {
        let mut u = UserWords::default();
        u.keep("Pingolin", 1);
        u.learn("there", Some("hello"), 1);
        let json = u.to_json().into_bytes();
        let mut rng = crate::dictionary::tests::XorShift(7);
        for _ in 0..3000 {
            let mut b = json.clone();
            let i = (rng.next() as usize) % b.len();
            b[i] = rng.next() as u8;
            if let Ok(s) = String::from_utf8(b) {
                let mut v = UserWords::default();
                if v.merge_json(&s).is_ok() {
                    let _ = v.compile();
                    let _ = v.followers(Some("hello"), false);
                }
            }
        }
    }

    #[test]
    fn limits_hold() {
        let mut u = UserWords::default();
        let words: Vec<String> = (0..MAX_WORDS + 10)
            .map(|i| {
                let mut n = i;
                let mut w = String::new();
                loop {
                    w.push((b'a' + (n % 26) as u8) as char);
                    n /= 26;
                    if n == 0 {
                        break;
                    }
                }
                w
            })
            .collect();
        u.keep("keepme", 0);
        for (day, w) in words.iter().enumerate() {
            u.learn(w, Some("x"), day as u32 + 1);
        }
        assert!(u.word_count() <= MAX_WORDS);
        assert!(u.is_known("keepme"), "kept words go last");
        assert!(u.pair_count() <= MAX_PAIRS);
        assert!(u.word_form_count_matches());
    }

    impl UserWords {
        fn word_form_count_matches(&self) -> bool {
            self.pair_count == self.pairs.values().map(HashMap::len).sum::<usize>()
        }
    }
}
