// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: Apache-2.0 OR MIT

//! Text helpers shared by the dictionary builder and the decoder.
//!
//! Words are matched on their *key*: lower case, accents removed, apostrophes
//! and hyphens left out. Typing "cafe" finds "café", "dont" finds "don't",
//! and "The" finds "the". Accents are removed only where they are accents
//! (the generic combining diacritics, Arabic harakat, Hebrew points). The
//! vowel signs and viramas of Indic and other scripts are letters there, so
//! they stay.

use unicode_normalization::UnicodeNormalization;

/// Longest key the engine handles, in key characters.
pub const MAX_KEY_LEN: usize = 48;

/// Apostrophes and hyphens: part of a word, but not of its key.
pub fn is_connector(c: char) -> bool {
    matches!(
        c,
        '\'' | '\u{2019}' | '\u{02BC}' | '-' | '\u{2010}' | '\u{2011}'
    )
}

/// Marks that only add an accent to a letter.
fn is_accent_mark(c: char) -> bool {
    matches!(c,
        '\u{0300}'..='\u{036F}'   // combining diacritical marks
        | '\u{1AB0}'..='\u{1AFF}' // ... extended
        | '\u{1DC0}'..='\u{1DFF}' // ... supplement
        | '\u{20D0}'..='\u{20FF}' // ... for symbols
        | '\u{FE20}'..='\u{FE2F}' // combining half marks
        | '\u{064B}'..='\u{065F}' // Arabic harakat, hamza above and below
        | '\u{0670}'              // Arabic superscript alef
        | '\u{0591}'..='\u{05BD}' // Hebrew cantillation and points
        | '\u{05BF}' | '\u{05C1}' | '\u{05C2}' | '\u{05C4}' | '\u{05C5}' | '\u{05C7}'
    )
}

/// Letters with no canonical decomposition that people type as their base letter.
fn base_letter(c: char) -> char {
    match c {
        'ł' => 'l',
        'ø' => 'o',
        'đ' | 'ð' => 'd',
        'ı' => 'i',
        'ħ' => 'h',
        'ŧ' => 't',
        'ŀ' => 'l',
        'ſ' => 's',
        'ς' => 'σ',
        _ => c,
    }
}

/// Appends the key characters of `c` to `out`.
pub fn push_key_chars(c: char, out: &mut Vec<char>) {
    if is_connector(c) {
        return;
    }
    for d in c.to_lowercase().nfd() {
        if !is_accent_mark(d) {
            out.push(base_letter(d));
        }
    }
}

/// The key of a word.
pub fn key_of(word: &str) -> Vec<char> {
    let mut key = Vec::with_capacity(word.len());
    for c in word.chars() {
        push_key_chars(c, &mut key);
    }
    key
}

/// Whether `c` can appear in a key: what folding leaves of a letter.
pub fn is_key_char(c: char) -> bool {
    let mut folded = Vec::new();
    push_key_chars(c, &mut folded);
    folded == [c] && (c.is_alphabetic() || is_mark(c))
}

/// Combining marks of any script (they are part of letters in Indic scripts).
fn is_mark(c: char) -> bool {
    unicode_normalization::char::is_combining_mark(c)
}

/// Whether `c` may appear in a dictionary word: letters, marks, connectors.
pub fn is_word_char(c: char) -> bool {
    c.is_alphabetic() || is_mark(c) || is_connector(c)
}

/// How a typed word is capitalized.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Case {
    /// No capitals (also words in scripts without case).
    Lower,
    /// The first letter only: "Hello", "I".
    Capitalized,
    /// Every letter, at least two of them: "HELLO".
    Upper,
    /// Anything else: "iPhone", "McDonald".
    Mixed,
}

pub fn case_of(word: &str) -> Case {
    let mut cased = word
        .chars()
        .filter(|c| c.is_lowercase() || c.is_uppercase());
    let Some(first) = cased.next() else {
        return Case::Lower;
    };
    let mut rest_upper = 0usize;
    let mut rest_lower = 0usize;
    for c in cased {
        if c.is_uppercase() {
            rest_upper += 1;
        } else {
            rest_lower += 1;
        }
    }
    match (first.is_uppercase(), rest_upper, rest_lower) {
        (false, 0, _) => Case::Lower,
        (true, 0, _) => Case::Capitalized,
        (true, _, 0) => Case::Upper,
        _ => Case::Mixed,
    }
}

/// Languages whose dotted and dotless i change case differently.
pub fn is_turkic(locale: &str) -> bool {
    let lang = locale.split(['-', '_']).next().unwrap_or("");
    lang.eq_ignore_ascii_case("tr") || lang.eq_ignore_ascii_case("az")
}

fn push_upper(c: char, turkic: bool, out: &mut String) {
    match c {
        'i' if turkic => out.push('İ'),
        _ => out.extend(c.to_uppercase()),
    }
}

pub fn to_upper(word: &str, turkic: bool) -> String {
    let mut out = String::with_capacity(word.len());
    for c in word.chars() {
        push_upper(c, turkic, &mut out);
    }
    out
}

/// The word with its first cased letter in upper case.
pub fn capitalize(word: &str, turkic: bool) -> String {
    let mut out = String::with_capacity(word.len());
    let mut done = false;
    for c in word.chars() {
        if !done && (c.is_lowercase() || c.is_uppercase()) {
            push_upper(c, turkic, &mut out);
            done = true;
        } else {
            out.push(c);
        }
    }
    out
}

/// A dictionary word written the way the user is typing: "The" at the start
/// of a sentence, "THE" with caps lock. Words the dictionary writes with
/// capitals ("Monday", "iPhone", "NASA") keep them, except in caps lock.
pub fn apply_case(word: &str, typed: Case, turkic: bool) -> String {
    let has_capitals = word.chars().any(char::is_uppercase);
    match typed {
        Case::Upper => to_upper(word, turkic),
        Case::Capitalized if !has_capitals => capitalize(word, turkic),
        _ => word.to_owned(),
    }
}

/// Whether the typed text is this dictionary word, allowing the capitals of
/// a sentence start or caps lock. A word the dictionary writes with capitals
/// does not match a lower-case spelling: "monday" is not "Monday".
pub fn typed_matches(typed: &str, word: &str, turkic: bool) -> bool {
    if typed == word {
        return true;
    }
    match case_of(typed) {
        Case::Upper => to_upper(word, turkic) == typed,
        Case::Capitalized => capitalize(word, turkic) == typed,
        _ => false,
    }
}

/// NFC, the form words are stored and compared in.
pub fn nfc(text: &str) -> String {
    text.nfc().collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn key(word: &str) -> String {
        key_of(word).into_iter().collect()
    }

    #[test]
    fn keys_drop_case_accents_and_connectors() {
        assert_eq!(key("Café"), "cafe");
        assert_eq!(key("don't"), "dont");
        assert_eq!(key("don’t"), "dont");
        assert_eq!(key("e-mail"), "email");
        assert_eq!(key("Việt"), "viet");
        assert_eq!(key("Łódź"), "lodz");
        assert_eq!(key("ΚΑΛΗΜΈΡΑ"), "καλημερα");
        assert_eq!(key("λόγος"), "λογοσ");
    }

    #[test]
    fn turkish_capitals_fold_to_i() {
        assert_eq!(key("İstanbul"), "istanbul");
        assert_eq!(key("ISPARTA"), "isparta");
        assert_eq!(key("ılık"), "ilik");
    }

    #[test]
    fn indic_vowel_signs_and_viramas_stay() {
        // की = क + ी (vowel sign II); क्या has a virama.
        assert_eq!(key("की"), "की");
        assert_eq!(key("क्या"), "क्या");
        // A nukta letter decomposes, and both spellings get the same key.
        assert_eq!(key("\u{0958}"), key("\u{0915}\u{093C}"));
    }

    #[test]
    fn arabic_harakat_and_hebrew_points_go() {
        assert_eq!(key("كَتَبَ"), "كتب");
        assert_eq!(key("שָׁלוֹם"), "שלום");
    }

    #[test]
    fn key_chars() {
        assert!(is_key_char('a'));
        assert!(is_key_char('क'));
        assert!(is_key_char('\u{093F}')); // Devanagari vowel sign I (a mark)
        assert!(!is_key_char('A'));
        assert!(!is_key_char('é'));
        assert!(!is_key_char('\''));
        assert!(!is_key_char('1'));
    }

    #[test]
    fn cases() {
        assert_eq!(case_of("hello"), Case::Lower);
        assert_eq!(case_of("Hello"), Case::Capitalized);
        assert_eq!(case_of("I"), Case::Capitalized);
        assert_eq!(case_of("HELLO"), Case::Upper);
        assert_eq!(case_of("iPhone"), Case::Mixed);
        assert_eq!(case_of("नमस्ते"), Case::Lower);
        assert_eq!(case_of("O'NEIL"), Case::Upper);
    }

    #[test]
    fn applying_case() {
        assert_eq!(apply_case("the", Case::Capitalized, false), "The");
        assert_eq!(apply_case("the", Case::Upper, false), "THE");
        assert_eq!(apply_case("Monday", Case::Lower, false), "Monday");
        assert_eq!(apply_case("iPhone", Case::Capitalized, false), "iPhone");
        assert_eq!(apply_case("iPhone", Case::Upper, false), "IPHONE");
        assert_eq!(apply_case("don't", Case::Capitalized, false), "Don't");
        assert_eq!(apply_case("istanbul", Case::Capitalized, true), "İstanbul");
        assert_eq!(apply_case("istanbul", Case::Capitalized, false), "Istanbul");
        assert_eq!(apply_case("straße", Case::Upper, false), "STRASSE");
    }

    #[test]
    fn typed_matching() {
        assert!(typed_matches("the", "the", false));
        assert!(typed_matches("The", "the", false));
        assert!(typed_matches("THE", "the", false));
        assert!(typed_matches("Monday", "Monday", false));
        assert!(typed_matches("MONDAY", "Monday", false));
        assert!(!typed_matches("monday", "Monday", false));
        assert!(!typed_matches("cafe", "café", false));
        assert!(!typed_matches("tHe", "the", false));
    }

    #[test]
    fn turkic_locales() {
        assert!(is_turkic("tr"));
        assert!(is_turkic("tr-TR"));
        assert!(is_turkic("az_AZ"));
        assert!(!is_turkic("en-US"));
    }
}
