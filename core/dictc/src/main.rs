// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: Apache-2.0 OR MIT

//! dictc: builds Pingolin Keyboard dictionaries.
//!
//! ```text
//! dictc compile <dictionary-dir> <out.pkd>      word list -> binary dictionary
//! dictc info <file.pkd>                         what a binary dictionary holds
//! dictc import-aosp <wordlist> <out-dir> [--max-words N]
//!                                               AOSP .combined word list -> word list
//! dictc import-pairs <sentences.tsv> <dictionary-dir> [--exclude W,W] [--holdout N] [--min-count N]
//!                                               Tatoeba sentences -> pairs.tsv
//! ```
//!
//! The Android build runs `compile` for every directory under
//! `dictionaries/`. `import-aosp` reads an uncompressed AOSP LatinIME
//! `*_wordlist.combined` file (gunzip it first). `import-pairs` reads an
//! uncompressed Tatoeba per-language export (id, language, text).

use std::collections::{BTreeMap, HashMap};
use std::fs;
use std::path::{Path, PathBuf};
use std::process::ExitCode;

use pingolin_keyboard_engine::dictionary::{Dictionary, build};
use pingolin_keyboard_engine::text::{is_connector, key_of, nfc};
use pingolin_keyboard_engine::wordlist::{
    FLAG_OFFENSIVE, SENTENCE_START, WordList, check_word, parse_words,
};

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let (positional, options) = split_options(&args);
    let result = match positional
        .iter()
        .map(String::as_str)
        .collect::<Vec<_>>()
        .as_slice()
    {
        ["compile", dir, out] => compile(Path::new(dir), Path::new(out)),
        ["info", file] => info(Path::new(file)),
        ["import-aosp", input, out] => import_aosp(Path::new(input), Path::new(out), &options),
        ["import-pairs", input, dir] => import_pairs(Path::new(input), Path::new(dir), &options),
        _ => Err(USAGE.to_owned()),
    };
    match result {
        Ok(()) => ExitCode::SUCCESS,
        Err(message) => {
            eprintln!("dictc: {message}");
            ExitCode::FAILURE
        }
    }
}

const USAGE: &str = "usage:
  dictc compile <dictionary-dir> <out.pkd>
  dictc info <file.pkd>
  dictc import-aosp <wordlist.combined> <out-dir> [--max-words N]
  dictc import-pairs <sentences.tsv> <dictionary-dir> [--exclude W,W] [--holdout N] [--min-count N]";

/// `--name value` options, and the rest.
fn split_options(args: &[String]) -> (Vec<String>, HashMap<String, String>) {
    let mut positional = Vec::new();
    let mut options = HashMap::new();
    let mut i = 0;
    while i < args.len() {
        if let Some(name) = args[i].strip_prefix("--") {
            options.insert(
                name.to_owned(),
                args.get(i + 1).cloned().unwrap_or_default(),
            );
            i += 2;
        } else {
            positional.push(args[i].clone());
            i += 1;
        }
    }
    (positional, options)
}

fn number(options: &HashMap<String, String>, name: &str, default: usize) -> Result<usize, String> {
    match options.get(name) {
        Some(v) => v.parse().map_err(|_| format!("--{name} needs a number")),
        None => Ok(default),
    }
}

fn read(path: &Path) -> Result<String, String> {
    fs::read_to_string(path).map_err(|e| format!("{}: {e}", path.display()))
}

fn read_optional(path: &Path) -> Result<String, String> {
    if path.exists() {
        read(path)
    } else {
        Ok(String::new())
    }
}

/// Writes through a temporary file, so a failed run leaves no half file.
fn write_atomically(path: &Path, bytes: &[u8]) -> Result<(), String> {
    let tmp = PathBuf::from(format!("{}.tmp", path.display()));
    fs::write(&tmp, bytes).map_err(|e| format!("{}: {e}", tmp.display()))?;
    fs::rename(&tmp, path).map_err(|e| format!("{}: {e}", path.display()))
}

fn compile(dir: &Path, out: &Path) -> Result<(), String> {
    let pairs = read_optional(&dir.join("pairs.tsv"))?;
    let list = WordList::parse(
        &read(&dir.join("words.tsv"))?,
        &read_optional(&dir.join("corrections.tsv"))?,
        &read(&dir.join("metadata.tsv"))?,
    )
    .and_then(|l| l.with_pairs(&pairs))
    .map_err(|e| format!("{}: {e}", dir.display()))?;
    let bytes = build(&list).map_err(|e| format!("{}: {e}", dir.display()))?;
    // Read it back: the app must be able to load what the build ships.
    let dictionary =
        Dictionary::from_bytes(&bytes).map_err(|e| format!("{}: {e}", dir.display()))?;
    write_atomically(out, &bytes)?;
    println!(
        "{}: {} words, {} corrections, {} pairs, {} nodes, {} bytes",
        out.display(),
        dictionary.word_count(),
        list.corrections.len(),
        list.pairs.len(),
        dictionary.node_count(),
        bytes.len()
    );
    Ok(())
}

fn info(file: &Path) -> Result<(), String> {
    let bytes = fs::read(file).map_err(|e| format!("{}: {e}", file.display()))?;
    let d = Dictionary::from_bytes(&bytes).map_err(|e| format!("{}: {e}", file.display()))?;
    for key in ["locale", "name", "layout", "source", "license"] {
        if let Some(value) = d.metadata(key) {
            println!("{key}: {value}");
        }
    }
    println!("words: {}", d.word_count());
    println!("nodes: {}", d.node_count());
    println!("alphabet: {}", d.alphabet().iter().collect::<String>());
    Ok(())
}

/// AOSP file tag, BCP 47 tag, the language's own name, layout.
const LANGUAGES: &[(&str, &str, &str, &str)] = &[
    ("cs", "cs", "Čeština", "qwertz"),
    ("da", "da", "Dansk", "qwerty-da"),
    ("de", "de", "Deutsch", "qwertz-de"),
    ("el", "el", "Ελληνικά", "greek"),
    ("en_GB", "en-GB", "English (UK)", "letters"),
    ("en_US", "en-US", "English (US)", "letters"),
    ("es", "es", "Español", "qwerty-es"),
    ("fi", "fi", "Suomi", "qwerty-sv"),
    ("fr", "fr", "Français", "azerty"),
    ("hr", "hr", "Hrvatski", "qwertz"),
    ("it", "it", "Italiano", "letters"),
    ("iw", "he", "עברית", "hebrew"),
    ("lt", "lt", "Lietuvių", "letters"),
    ("lv", "lv", "Latviešu", "letters"),
    ("nb", "nb", "Norsk bokmål", "qwerty-nb"),
    ("nl", "nl", "Nederlands", "letters"),
    ("pl", "pl", "Polski", "letters"),
    ("pt_BR", "pt-BR", "Português (Brasil)", "letters"),
    ("pt_PT", "pt-PT", "Português (Portugal)", "letters"),
    ("ro", "ro", "Română", "letters"),
    ("ru", "ru", "Русский", "russian"),
    ("sl", "sl", "Slovenščina", "qwertz"),
    ("sr", "sr", "Српски", "serbian"),
    ("sv", "sv", "Svenska", "qwerty-sv"),
    ("tr", "tr", "Türkçe", "turkish"),
];

/// One `word=` line of an AOSP word list and the shortcut lines under it.
#[derive(Default)]
struct AospWord {
    word: String,
    frequency: u8,
    not_a_word: bool,
    nonword_flag: bool,
    offensive: bool,
    whitelist: Vec<String>,
}

fn fields(line: &str) -> HashMap<&str, &str> {
    line.trim()
        .split(',')
        .filter_map(|f| f.split_once('='))
        .collect()
}

/// How much more frequent a contraction must be than the word it replaces
/// for "ill" to become "I'll" (the AOSP scale: 40 steps is about 270 times).
const CONTRACTION_MARGIN: u16 = 40;

fn import_aosp(input: &Path, out: &Path, options: &HashMap<String, String>) -> Result<(), String> {
    let max_words = number(options, "max-words", usize::MAX)?;
    let text = read(input)?;
    let mut lines = text.lines();
    let header = fields(lines.next().ok_or("empty word list")?);
    let aosp_locale = header
        .get("locale")
        .copied()
        .ok_or("the header has no locale")?;
    let (locale, name, layout) = match LANGUAGES.iter().find(|l| l.0 == aosp_locale) {
        Some((_, tag, name, layout)) => {
            ((*tag).to_owned(), (*name).to_owned(), (*layout).to_owned())
        }
        None => (
            aosp_locale.replace('_', "-"),
            header
                .get("description")
                .copied()
                .unwrap_or(aosp_locale)
                .to_owned(),
            "letters".to_owned(),
        ),
    };

    let mut words: Vec<AospWord> = Vec::new();
    for line in lines {
        if let Some(rest) = line.strip_prefix(" word=") {
            let f = fields(line);
            let word = rest.split(',').next().unwrap_or("").to_owned();
            words.push(AospWord {
                word,
                frequency: f.get("f").and_then(|v| v.parse().ok()).unwrap_or(0),
                not_a_word: f.get("not_a_word") == Some(&"true"),
                nonword_flag: f
                    .get("flags")
                    .is_some_and(|v| v.split(':').any(|x| x == "nonword")),
                offensive: f.get("possibly_offensive") == Some(&"true"),
                whitelist: Vec::new(),
            });
        } else if let Some(rest) = line.strip_prefix("  shortcut=") {
            let f = fields(line);
            let target = rest.split(',').next().unwrap_or("").to_owned();
            if f.get("f") == Some(&"whitelist")
                && let Some(last) = words.last_mut()
            {
                last.whitelist.push(target);
            }
        }
    }

    let frequency: HashMap<&str, u8> = words
        .iter()
        .filter(|w| !w.not_a_word && !w.nonword_flag)
        .map(|w| (w.word.as_str(), w.frequency))
        .collect();
    let mut kept: BTreeMap<String, (u8, bool)> = BTreeMap::new();
    let mut skipped_chars = Vec::new();
    let mut skipped_nonwords = 0;
    for w in &words {
        if w.not_a_word || w.nonword_flag {
            skipped_nonwords += 1;
            continue;
        }
        let word = nfc(&w.word);
        if check_word(&word).is_err() {
            skipped_chars.push(w.word.clone());
            continue;
        }
        let slot = kept.entry(word).or_insert((w.frequency, w.offensive));
        slot.0 = slot.0.max(w.frequency);
        slot.1 |= w.offensive;
    }
    // A very long list (AOSP's Romanian has every inflection) keeps its most
    // frequent words.
    let mut dropped = 0;
    if kept.len() > max_words {
        let mut order: Vec<(u8, String)> = kept.iter().map(|(w, (f, _))| (*f, w.clone())).collect();
        order.sort_by(|a, b| b.0.cmp(&a.0).then_with(|| a.1.cmp(&b.1)));
        for (_, w) in order.into_iter().skip(max_words) {
            kept.remove(&w);
            dropped += 1;
        }
    }

    // AOSP's whitelist mixes typo fixes ("alot" to "a lot") with swaps of
    // real words for commoner ones ("hid" to "his"). Keep the fixes of
    // non-words, and contractions that are far commoner than the word typed.
    let mut corrections: BTreeMap<String, String> = BTreeMap::new();
    let mut skipped_corrections = Vec::new();
    for w in &words {
        for target in &w.whitelist {
            let typed = w.word.to_lowercase();
            let keep = if w.not_a_word || w.nonword_flag {
                true
            } else {
                let own = frequency.get(w.word.as_str()).copied().unwrap_or(0) as u16;
                let theirs = frequency.get(target.as_str()).copied().unwrap_or(0) as u16;
                key_of(target) == key_of(&w.word) && theirs >= own + CONTRACTION_MARGIN
            };
            if !keep || check_word(&typed).is_err() || *target == typed {
                skipped_corrections.push(format!("{} -> {target}", w.word));
                continue;
            }
            corrections.entry(typed).or_insert_with(|| target.clone());
        }
    }

    fs::create_dir_all(out).map_err(|e| format!("{}: {e}", out.display()))?;
    let source = input
        .file_name()
        .and_then(|n| n.to_str())
        .unwrap_or("?")
        .to_owned();
    let preamble = format!(
        "# SPDX-FileCopyrightText: The Android Open Source Project\n\
         # SPDX-License-Identifier: Apache-2.0\n\
         #\n\
         # {name}, imported from AOSP LatinIME ({source}) with\n\
         # `dictc import-aosp`. See README.md.\n"
    );
    let mut words_tsv = format!("{preamble}#\n# word<TAB>frequency (0-255)[<TAB>flags]\n");
    for (word, (f, offensive)) in &kept {
        words_tsv.push_str(word);
        words_tsv.push('\t');
        words_tsv.push_str(&f.to_string());
        if *offensive {
            words_tsv.push_str("\toffensive");
        }
        words_tsv.push('\n');
    }
    let mut corrections_tsv = format!("{preamble}#\n# typed<TAB>replacement\n");
    for (typed, target) in &corrections {
        corrections_tsv.push_str(&format!("{typed}\t{target}\n"));
    }
    let version = header.get("version").copied().unwrap_or("?");
    let metadata_tsv = format!(
        "{preamble}locale\t{locale}\nname\t{name}\nlayout\t{layout}\n\
         source\tAOSP LatinIME {source}, version {version}\nlicense\tApache-2.0\n"
    );
    write_atomically(&out.join("words.tsv"), words_tsv.as_bytes())?;
    write_atomically(&out.join("corrections.tsv"), corrections_tsv.as_bytes())?;
    write_atomically(&out.join("metadata.tsv"), metadata_tsv.as_bytes())?;
    println!(
        "{}: {} words, {} corrections; skipped {} non-words, {} words with other characters, {} rare words over the limit, {} whitelist entries",
        out.display(),
        kept.len(),
        corrections.len(),
        skipped_nonwords,
        skipped_chars.len(),
        dropped,
        skipped_corrections.len()
    );
    println!("  other characters: {}", skipped_chars.join(" "));
    println!(
        "  whitelist entries left out: {}",
        skipped_corrections.join(", ")
    );
    Ok(())
}

/// The words of a sentence, with what separates each from the one before:
/// None when only spaces do (a pair), Some(true) at a sentence end.
fn tokens(sentence: &str) -> Vec<(String, Option<bool>)> {
    let mut out = Vec::new();
    let mut word = String::new();
    let mut gap: Option<bool> = Some(true); // the sentence starts
    let chars: Vec<char> = sentence
        .chars()
        .map(|c| if c == '\u{2019}' { '\'' } else { c })
        .collect();
    for (i, &c) in chars.iter().enumerate() {
        let letter = c.is_alphabetic() || unicode_mark(c);
        let joins = is_connector(c)
            && !word.is_empty()
            && chars.get(i + 1).is_some_and(|n| n.is_alphabetic());
        if letter || joins {
            word.push(c);
            continue;
        }
        if !word.is_empty() {
            out.push((std::mem::take(&mut word), gap));
            gap = None;
        }
        if matches!(c, '.' | '!' | '?' | '…' | '。' | '؟' | '¿' | '¡') {
            gap = Some(true);
        } else if c.is_numeric() {
            gap = Some(false); // "3 cats": no pair, and no sentence start
        } else if !c.is_whitespace() && gap.is_none() {
            gap = Some(false); // a comma or a quote: no pair across it
        }
    }
    if !word.is_empty() {
        out.push((word, gap));
    }
    out
}

fn unicode_mark(c: char) -> bool {
    // Combining marks (U+0300.. and script-specific vowel signs) are part of words.
    !c.is_alphabetic()
        && !c.is_whitespace()
        && !c.is_ascii()
        && pingolin_keyboard_engine::text::is_word_char(c)
        && !is_connector(c)
}

fn import_pairs(input: &Path, dir: &Path, options: &HashMap<String, String>) -> Result<(), String> {
    let holdout = number(options, "holdout", 100)?.max(1);
    let min_count = number(options, "min-count", 2)?.max(1) as u32;
    let exclude: Vec<String> = options
        .get("exclude")
        .map(|v| {
            v.split(',')
                .filter(|w| !w.is_empty())
                .map(str::to_owned)
                .collect()
        })
        .unwrap_or_default();
    let words = parse_words(&read(&dir.join("words.tsv"))?).map_err(|e| e.to_string())?;
    // Word ids; 0 is the sentence start.
    let mut id_of: HashMap<String, u32> = HashMap::new();
    let mut names = vec![SENTENCE_START.to_owned()];
    let mut suggestable = vec![false];
    for w in &words {
        id_of.insert(w.word.clone(), names.len() as u32);
        names.push(w.word.clone());
        suggestable.push(w.frequency > 0 && w.flags & FLAG_OFFENSIVE == 0);
    }
    let text = read(input)?;
    let mut pairs: HashMap<(u32, u32), u32> = HashMap::new();
    let mut totals: HashMap<u32, u32> = HashMap::new();
    let (mut used, mut held, mut excluded) = (0usize, 0usize, 0usize);
    for line in text.lines() {
        let mut fields = line.split('\t');
        let (Some(id), Some(_lang), Some(sentence)) = (fields.next(), fields.next(), fields.next())
        else {
            continue;
        };
        if !id.parse::<usize>().is_ok_and(|id| id % holdout != 0) {
            held += 1;
            continue;
        }
        let toks = tokens(&nfc(sentence));
        if toks.iter().any(|(w, _)| exclude.contains(w)) {
            excluded += 1;
            continue;
        }
        used += 1;
        let mut previous: Option<u32> = None;
        for (word, gap) in toks {
            let start = gap == Some(true);
            if start {
                previous = Some(0);
            } else if gap == Some(false) {
                previous = None;
            }
            let id = id_of.get(&word).copied().or_else(|| {
                // A capital at the start of a sentence says nothing.
                let lower = word.to_lowercase();
                if start && lower != word {
                    id_of.get(&lower).copied()
                } else {
                    None
                }
            });
            if let Some(p) = previous {
                *totals.entry(p).or_default() += 1;
                if let Some(n) = id.filter(|n| suggestable[*n as usize]) {
                    *pairs.entry((p, n)).or_default() += 1;
                }
            }
            previous = id;
        }
    }
    let mut by_previous: BTreeMap<String, Vec<(u32, String)>> = BTreeMap::new();
    for ((p, n), count) in pairs {
        if count >= min_count {
            by_previous
                .entry(names[p as usize].clone())
                .or_default()
                .push((count, names[n as usize].clone()));
        }
    }
    let mut out = String::from(
        "# SPDX-FileCopyrightText: Tatoeba contributors <https://tatoeba.org>\n\
         # SPDX-License-Identifier: LicenseRef-CC-BY-2.0-FR\n\
         #\n\
         # Word pairs counted in Tatoeba sentences (https://tatoeba.org/downloads,\n\
         # CC BY 2.0 FR) with `dictc import-pairs`. See README.md.\n\
         #\n\
         # previous<TAB>next<TAB>count; next \"*\" is the total after previous\n",
    );
    let mut kept = 0;
    for (previous, mut next) in by_previous {
        next.sort_by(|a, b| b.0.cmp(&a.0).then_with(|| a.1.cmp(&b.1)));
        next.truncate(16);
        let id = if previous == SENTENCE_START {
            0
        } else {
            id_of[&previous]
        };
        out.push_str(&format!("{previous}\t*\t{}\n", totals[&id]));
        for (count, word) in next {
            out.push_str(&format!("{previous}\t{word}\t{count}\n"));
            kept += 1;
        }
    }
    write_atomically(&dir.join("pairs.tsv"), out.as_bytes())?;
    println!(
        "{}: {kept} pairs from {used} sentences ({held} held out, {excluded} with excluded words)",
        dir.join("pairs.tsv").display()
    );
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::tokens;

    #[test]
    fn sentences_split_into_words_and_gaps() {
        let t = tokens("Hello, world! It's Tom's “red-hot” day. 3 cats");
        let words: Vec<(&str, Option<bool>)> = t.iter().map(|(w, g)| (w.as_str(), *g)).collect();
        assert_eq!(
            words,
            [
                ("Hello", Some(true)),
                ("world", Some(false)),
                ("It's", Some(true)),
                ("Tom's", None),
                ("red-hot", Some(false)),
                ("day", Some(false)),
                ("cats", Some(false)),
            ]
        );
    }

    #[test]
    fn apostrophes_at_the_edges_are_not_part_of_words() {
        let t = tokens("'tis the ’90s'");
        let words: Vec<&str> = t.iter().map(|(w, _)| w.as_str()).collect();
        assert_eq!(words, ["tis", "the", "s"]);
    }
}
