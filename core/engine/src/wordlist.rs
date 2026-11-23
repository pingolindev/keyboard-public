// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: Apache-2.0 OR MIT

//! Word lists: the text form of a dictionary, as kept in the repository
//! under `dictionaries/<locale>/`. Three tab-separated files, where lines
//! starting with `#` are comments:
//!
//! - `words.tsv`: `word<TAB>frequency[<TAB>flags]`. The frequency is 0 to 255
//!   on the log scale of AOSP word lists (higher is more frequent). 0 marks a
//!   real word that is never suggested but never corrected either. The only
//!   flag is `offensive`: such words are not suggested unless typed in full.
//! - `corrections.tsv`: `typed<TAB>replacement`. Autocorrect always replaces
//!   `typed` (in any capitalization) with `replacement`, which may hold
//!   several words ("alot" becomes "a lot").
//! - `metadata.tsv`: `key<TAB>value`, at least `locale`.
//! - `pairs.tsv` (optional): `previous<TAB>next<TAB>count`, how often `next`
//!   followed `previous` in a corpus, for next-word prediction. `previous` is
//!   `<s>` for the start of a sentence. A line with `*` as `next` gives the
//!   total number of words seen after `previous`, so counts become
//!   probabilities.

use std::collections::BTreeMap;
use std::fmt;

use crate::text::{MAX_KEY_LEN, is_word_char, key_of, nfc};

pub const FLAG_OFFENSIVE: u8 = 1;
/// Every flag a dictionary may carry.
pub const KNOWN_FLAGS: u8 = FLAG_OFFENSIVE;
/// Longest correction replacement, in characters.
pub const MAX_REPLACEMENT_LEN: usize = 64;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WordEntry {
    pub word: String,
    pub frequency: u8,
    pub flags: u8,
}

/// The `previous` of a pair at the start of a sentence.
pub const SENTENCE_START: &str = "<s>";

/// How often `next` followed `previous` in a corpus.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Pair {
    pub previous: String,
    pub next: String,
    pub count: u32,
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct WordList {
    pub metadata: Vec<(String, String)>,
    pub words: Vec<WordEntry>,
    pub corrections: Vec<(String, String)>,
    pub pairs: Vec<Pair>,
    /// Words seen after each `previous` of `pairs`, pruned pairs included.
    pub pair_totals: PairTotals,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ParseError {
    pub file: &'static str,
    pub line: usize,
    pub message: String,
}

impl fmt::Display for ParseError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{} line {}: {}", self.file, self.line, self.message)
    }
}

impl std::error::Error for ParseError {}

/// Data lines with their numbers: comments and blank lines skipped.
fn data_lines(text: &str) -> impl Iterator<Item = (usize, &str)> {
    text.lines()
        .enumerate()
        .map(|(i, line)| (i + 1, line.strip_suffix('\r').unwrap_or(line)))
        .filter(|(_, line)| !line.trim().is_empty() && !line.starts_with('#'))
}

impl WordList {
    /// Parses the three files. Duplicate words are merged (highest frequency,
    /// all flags).
    pub fn parse(words: &str, corrections: &str, metadata: &str) -> Result<Self, ParseError> {
        Ok(WordList {
            metadata: parse_metadata(metadata)?,
            words: parse_words(words)?,
            corrections: parse_corrections(corrections)?,
            pairs: Vec::new(),
            pair_totals: Vec::new(),
        })
    }

    /// Adds the word pairs of `pairs.tsv`.
    pub fn with_pairs(mut self, pairs: &str) -> Result<Self, ParseError> {
        let (pairs, totals) = parse_pairs(pairs)?;
        self.pairs = pairs;
        self.pair_totals = totals;
        Ok(self)
    }

    /// The value of a metadata key.
    pub fn meta(&self, key: &str) -> Option<&str> {
        self.metadata
            .iter()
            .find(|(k, _)| k == key)
            .map(|(_, v)| v.as_str())
    }

    pub fn locale(&self) -> Option<&str> {
        self.meta("locale")
    }
}

pub fn parse_words(text: &str) -> Result<Vec<WordEntry>, ParseError> {
    let err = |line, message: String| ParseError {
        file: "words.tsv",
        line,
        message,
    };
    let mut merged: BTreeMap<String, (u8, u8)> = BTreeMap::new();
    for (line, content) in data_lines(text) {
        let mut fields = content.split('\t');
        let word = nfc(fields.next().unwrap_or(""));
        let frequency = fields
            .next()
            .ok_or_else(|| err(line, "missing frequency".into()))?;
        let frequency: u8 = frequency
            .parse()
            .map_err(|_| err(line, format!("frequency {frequency:?} is not 0 to 255")))?;
        let mut flags = 0u8;
        if let Some(list) = fields.next() {
            for flag in list.split(',').filter(|f| !f.is_empty()) {
                flags |= match flag {
                    "offensive" => FLAG_OFFENSIVE,
                    other => return Err(err(line, format!("unknown flag {other:?}"))),
                };
            }
        }
        if fields.next().is_some() {
            return Err(err(line, "more than three fields".into()));
        }
        check_word(&word).map_err(|m| err(line, m))?;
        let slot = merged.entry(word).or_insert((frequency, flags));
        slot.0 = slot.0.max(frequency);
        slot.1 |= flags;
    }
    Ok(merged
        .into_iter()
        .map(|(word, (frequency, flags))| WordEntry {
            word,
            frequency,
            flags,
        })
        .collect())
}

/// A dictionary word: letters, marks and connectors, with a usable key.
pub fn check_word(word: &str) -> Result<(), String> {
    if word.is_empty() {
        return Err("empty word".into());
    }
    if let Some(c) = word.chars().find(|c| !is_word_char(*c)) {
        return Err(format!(
            "{word:?} contains {c:?}, which is not part of words"
        ));
    }
    let key = key_of(word);
    if key.is_empty() {
        return Err(format!("{word:?} has no letters"));
    }
    if key.len() > MAX_KEY_LEN {
        return Err(format!("{word:?} is longer than {MAX_KEY_LEN} letters"));
    }
    Ok(())
}

pub fn parse_corrections(text: &str) -> Result<Vec<(String, String)>, ParseError> {
    let err = |line, message: String| ParseError {
        file: "corrections.tsv",
        line,
        message,
    };
    let mut merged: BTreeMap<String, String> = BTreeMap::new();
    for (line, content) in data_lines(text) {
        let mut fields = content.split('\t');
        let typed = nfc(fields.next().unwrap_or("")).to_lowercase();
        let replacement = nfc(fields
            .next()
            .ok_or_else(|| err(line, "missing replacement".into()))?);
        if fields.next().is_some() {
            return Err(err(line, "more than two fields".into()));
        }
        check_word(&typed).map_err(|m| err(line, m))?;
        if replacement.is_empty()
            || replacement.chars().count() > MAX_REPLACEMENT_LEN
            || replacement.chars().any(char::is_control)
            || replacement.trim() != replacement
        {
            return Err(err(line, format!("bad replacement {replacement:?}")));
        }
        if replacement == typed {
            return Err(err(line, format!("{typed:?} replaces itself")));
        }
        if merged.insert(typed.clone(), replacement).is_some() {
            return Err(err(line, format!("{typed:?} is listed twice")));
        }
    }
    Ok(merged.into_iter().collect())
}

/// Words seen after each previous word.
pub type PairTotals = Vec<(String, u32)>;

pub fn parse_pairs(text: &str) -> Result<(Vec<Pair>, PairTotals), ParseError> {
    let err = |line, message: String| ParseError {
        file: "pairs.tsv",
        line,
        message,
    };
    let mut pairs: BTreeMap<(String, String), u32> = BTreeMap::new();
    let mut totals: BTreeMap<String, u32> = BTreeMap::new();
    for (line, content) in data_lines(text) {
        let fields: Vec<&str> = content.split('\t').collect();
        let [previous, next, count] = fields.as_slice() else {
            return Err(err(line, "expected previous<TAB>next<TAB>count".into()));
        };
        let previous = nfc(previous);
        let next = nfc(next);
        if previous != SENTENCE_START {
            check_word(&previous).map_err(|m| err(line, m))?;
        }
        let count: u32 = count
            .parse()
            .ok()
            .filter(|c| *c > 0)
            .ok_or_else(|| err(line, format!("count {count:?} is not a positive number")))?;
        if next == "*" {
            if totals.insert(previous.clone(), count).is_some() {
                return Err(err(line, format!("two totals for {previous:?}")));
            }
            continue;
        }
        check_word(&next).map_err(|m| err(line, m))?;
        if pairs
            .insert((previous.clone(), next.clone()), count)
            .is_some()
        {
            return Err(err(line, format!("{previous:?} {next:?} is listed twice")));
        }
    }
    let mut sums: BTreeMap<&str, u64> = BTreeMap::new();
    for ((previous, _), count) in &pairs {
        *sums.entry(previous.as_str()).or_default() += u64::from(*count);
    }
    for (previous, sum) in sums {
        match totals.get(previous) {
            Some(total) if u64::from(*total) >= sum => {}
            Some(_) => {
                return Err(err(
                    0,
                    format!("the total for {previous:?} is below its pairs"),
                ));
            }
            None => return Err(err(0, format!("no total for {previous:?}"))),
        }
    }
    Ok((
        pairs
            .into_iter()
            .map(|((previous, next), count)| Pair {
                previous,
                next,
                count,
            })
            .collect(),
        totals.into_iter().collect(),
    ))
}

pub fn parse_metadata(text: &str) -> Result<Vec<(String, String)>, ParseError> {
    let err = |line, message: String| ParseError {
        file: "metadata.tsv",
        line,
        message,
    };
    let mut out: Vec<(String, String)> = Vec::new();
    for (line, content) in data_lines(text) {
        let (key, value) = content
            .split_once('\t')
            .ok_or_else(|| err(line, "expected key<TAB>value".into()))?;
        if key.is_empty() || !key.chars().all(|c| c.is_ascii_lowercase() || c == '_') {
            return Err(err(line, format!("bad key {key:?}")));
        }
        if value.chars().any(char::is_control) || value.len() > 1024 {
            return Err(err(line, format!("bad value for {key}")));
        }
        if out.iter().any(|(k, _)| k == key) {
            return Err(err(line, format!("{key} is set twice")));
        }
        out.push((key.to_owned(), value.to_owned()));
    }
    if !out.iter().any(|(k, _)| k == "locale") {
        return Err(err(0, "no locale".into()));
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_words_with_comments_flags_and_duplicates() {
        let words = parse_words(
            "# a comment\nthe\t222\n\nsex\t135\toffensive\nthe\t200\r\ncafe\u{301}\t104\n",
        )
        .unwrap();
        assert_eq!(
            words,
            vec![
                WordEntry {
                    word: "café".into(),
                    frequency: 104,
                    flags: 0
                },
                WordEntry {
                    word: "sex".into(),
                    frequency: 135,
                    flags: FLAG_OFFENSIVE
                },
                WordEntry {
                    word: "the".into(),
                    frequency: 222,
                    flags: 0
                },
            ]
        );
    }

    #[test]
    fn rejects_bad_word_lines() {
        for bad in [
            "the",
            "the\t256",
            "the\t-1",
            "the\tx",
            "3D\t100",
            "a b\t1",
            "''\t5",
            "the\t1\tloud",
            "the\t1\t\textra",
        ] {
            assert!(parse_words(bad).is_err(), "{bad:?} should fail");
        }
        let long = format!("{}\t1", "a".repeat(MAX_KEY_LEN + 1));
        assert!(parse_words(&long).is_err());
    }

    #[test]
    fn parses_corrections() {
        let c = parse_corrections("Aint\tain't\nalot\ta lot\n").unwrap();
        assert_eq!(
            c,
            vec![
                ("aint".into(), "ain't".into()),
                ("alot".into(), "a lot".into())
            ]
        );
        for bad in [
            "alot",
            "alot\t",
            "alot\t a lot",
            "alot\talot",
            "alot\ta\tb",
            "a1\tb",
        ] {
            assert!(parse_corrections(bad).is_err(), "{bad:?} should fail");
        }
        assert!(parse_corrections("im\tI'm\nIM\tI am\n").is_err());
    }

    #[test]
    fn parses_pairs() {
        let (pairs, totals) =
            parse_pairs("# c\n<s>\tI\t5\n<s>\t*\t9\nI\tam\t3\nI\t*\t3\n").unwrap();
        assert_eq!(pairs.len(), 2);
        assert_eq!(
            pairs[0],
            Pair {
                previous: "<s>".into(),
                next: "I".into(),
                count: 5
            }
        );
        assert_eq!(totals, vec![("<s>".into(), 9), ("I".into(), 3)]);
        for bad in [
            "I\tam\t3\n",                    // no total
            "I\tam\t3\nI\t*\t2\n",           // total below the pairs
            "I\tam\t0\nI\t*\t2\n",           // zero count
            "I\tam\nI\t*\t2\n",              // two fields
            "I\tam\t1\nI\tam\t1\nI\t*\t2\n", // twice
            "<s>\t<s>\t1\n<s>\t*\t1\n",      // <s> is not a next word
            "I\t*\t1\nI\t*\t1\n",            // two totals
        ] {
            assert!(parse_pairs(bad).is_err(), "{bad:?} should fail");
        }
    }

    #[test]
    fn parses_metadata() {
        let m = parse_metadata("locale\ten-US\nname\tEnglish (US)\n").unwrap();
        assert_eq!(m[0], ("locale".into(), "en-US".into()));
        assert!(parse_metadata("name\tx\n").is_err());
        assert!(parse_metadata("locale\ta\nlocale\tb\n").is_err());
        assert!(parse_metadata("Locale\ten\n").is_err());
    }
}
