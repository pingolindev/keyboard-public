// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: Apache-2.0 OR MIT

//! The emoji the panel offers, the search over their names, and the emoji a
//! typed word stands for.
//!
//! The tables are `emoji/list.tsv` (emoji, group, version, skin tones) and one
//! `emoji/names/<tag>.tsv` per language (emoji, name, search words). The app
//! reads them from its assets. Bad lines are skipped: an emoji without a name
//! can still be picked.

use std::collections::{HashMap, HashSet};

use crate::text::key_of;

/// The index cannot be built.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Error)]
pub enum EmojiError {
    /// The tables do not hold any emoji.
    Invalid { reason: String },
}

impl std::fmt::Display for EmojiError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            EmojiError::Invalid { reason } => write!(f, "invalid emoji tables: {reason}"),
        }
    }
}

impl std::error::Error for EmojiError {}

/// An emoji the panel shows, in the order the keyboard shows it.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Emoji {
    pub text: String,
    /// The group it belongs to, 0 (smileys) to 8 (flags).
    pub group: u8,
    /// What it is called in the first language that names it.
    pub name: String,
    /// The same emoji from light to dark skin, or empty.
    pub tones: Vec<String>,
}

/// Longest text in a table row the engine reads.
const MAX_ROW: usize = 4_096;
/// Most emoji, and most languages.
const MAX_EMOJI: usize = 8_000;
const MAX_LANGUAGES: usize = 16;
/// Shortest word that gets an emoji suggestion.
const MIN_WORD: usize = 3;
/// A search word that few emoji share stands for the best known of them.
const FEW: usize = 8;
/// One that more share (up to this many; "face" is more) stands for a well known
/// emoji that says little else.
const MANY: usize = 50;
/// How much being less known counts against one more word of its own.
const POPULARITY_STEP: usize = 5;

/// Often used emoji first, to order equally good matches. Ours, not a ranking
/// by anyone.
const POPULAR: &str = "😂 ❤️ 🤣 👍 😭 🙏 😘 🥰 😍 😊 🎉 😁 💕 🥺 😅 🔥 ☺️ 🤦 🤷 🙄 😆 🤗 😉 🎂 🤔 👏 🙂 😳 🥳 😎 👌 💜 😔 💪 ✨ 💖 👀 😋 😏 😢 💗 😩 💯 🌹 💞 🎈 💙 😃 😡 💐 😜 🙈 🤞 😄 🤤 🙌 🤪 😀 💋 💀 💔 😌 💓 🤩 🙃 😬 😱 😴 🤭 😐 😒 😇 🌸 😈 🎶 ✌️ 🎊 🥵 😞 💚 ☀️ 🖤 💰 😚 👑 🎁 💥 🙋 ☹️ 😑 💩 ✅ ☕ 🍕 🐶 🐱 🚗 🏠 🌳 ⚽ 🍺 🍻 🍷 🎵 🎄 🌧️ ❄️ 🌈 ⭐ 🌙 📞 📧 📅 ⏰ ✈️ 🚀 🏖️ 🐕 🐈 🎓 💡 🔒 💻 📱";

struct Entry {
    text: String,
    group: u8,
    name: String,
    tones: Vec<String>,
    /// Folded words of the name, then of the search words.
    words: Vec<(String, bool)>,
    /// Folded whole names, one per language.
    names: Vec<String>,
    popularity: usize,
}

/// How well an emoji matches a search, the best first: one called just that,
/// whole words before starts of words, well known emoji before the others, its
/// name before its search words, a name that starts with what was typed.
#[derive(PartialEq, Eq, PartialOrd, Ord)]
struct Rank {
    other_name: bool,
    unfinished: usize,
    popularity: usize,
    not_named: usize,
    not_first: bool,
}

/// Search and word suggestions over the emoji tables.
#[derive(uniffi::Object)]
pub struct EmojiIndex {
    entries: Vec<Entry>,
    /// Folded word of a typed language to the emoji it stands for: their place
    /// in `entries`, and whether the word is the emoji's whole name (or else a
    /// search word).
    by_word: HashMap<String, Vec<(u32, bool)>>,
    /// Folded words too common to stand for an emoji by search words alone.
    common: HashSet<String>,
}

/// The part before the first `sep`, at most `MAX_ROW` long.
fn fields(line: &str) -> Vec<&str> {
    if line.len() > MAX_ROW {
        return Vec::new();
    }
    line.split('\t').collect()
}

fn lines(bytes: &[u8]) -> impl Iterator<Item = &str> {
    std::str::from_utf8(bytes)
        .unwrap_or("")
        .lines()
        .map(|l| l.trim_end_matches('\r'))
        // A comment is "# ...": the keycap emoji of # starts with one too.
        .filter(|l| !l.is_empty() && !l.starts_with("# "))
}

/// The folded letters of one word. Letters people also type as two count as two:
/// "coeur" finds "cœur".
fn key(word: &str) -> String {
    let mut plain = String::with_capacity(word.len());
    for c in word.chars() {
        match c {
            'œ' | 'Œ' => plain.push_str("oe"),
            'æ' | 'Æ' => plain.push_str("ae"),
            'ß' | 'ẞ' => plain.push_str("ss"),
            _ => plain.push(c),
        }
    }
    key_of(&plain).into_iter().collect()
}

/// The folded letters and digits of `text`, split where it has anything else.
fn words_of(text: &str) -> Vec<String> {
    let mut words = Vec::new();
    for part in text.split(|c: char| !c.is_alphanumeric() && !crate::text::is_connector(c)) {
        let whole = key(part);
        if !whole.is_empty() {
            words.push(whole);
        }
        // "t-shirt" is found by "shirt" too, and "tshirt" by its key above.
        if part.contains(crate::text::is_connector) {
            words.extend(
                part.split(crate::text::is_connector)
                    .map(key)
                    .filter(|k| !k.is_empty()),
            );
        }
    }
    words.sort();
    words.dedup();
    words
}

/// The whole of `text` folded, words joined by single spaces.
fn folded(text: &str) -> String {
    text.split(|c: char| !c.is_alphanumeric() && !crate::text::is_connector(c))
        .map(key)
        .filter(|k| !k.is_empty())
        .collect::<Vec<_>>()
        .join(" ")
}

/// What a country's flag is also called: its two letter code, and a few short
/// names people use.
fn flag_words(text: &str) -> Vec<String> {
    let mut code = String::new();
    for c in text.chars() {
        match (c as u32).checked_sub(0x1F1E6) {
            Some(i @ 0..26) => code.push((b'a' + i as u8) as char),
            _ => return Vec::new(),
        }
    }
    let short = match code.as_str() {
        "us" => Some("usa"),
        "gb" => Some("uk"),
        "ae" => Some("uae"),
        _ => None,
    };
    short.map(str::to_owned).into_iter().chain([code]).collect()
}

#[uniffi::export]
impl EmojiIndex {
    /// Builds the index from the list and the name tables, the user's languages
    /// first: that decides the names the panel reads out. Words of the first
    /// `typed` tables can stand for emoji; the other tables are only searched.
    #[uniffi::constructor]
    pub fn new(list: Vec<u8>, names: Vec<Vec<u8>>, typed: u32) -> Result<Self, EmojiError> {
        let popular: HashMap<&str, usize> = POPULAR
            .split(' ')
            .enumerate()
            .map(|(i, e)| (e, i))
            .collect();
        let mut entries: Vec<Entry> = Vec::new();
        let mut at: HashMap<String, usize> = HashMap::new();
        for line in lines(&list) {
            let f = fields(line);
            let [text, group, _version, tones] = f.as_slice() else {
                continue;
            };
            let (Ok(group), false) = (group.parse::<u8>(), text.is_empty()) else {
                continue;
            };
            if group > 8 || entries.len() >= MAX_EMOJI || at.contains_key(*text) {
                continue;
            }
            let tones: Vec<String> = tones
                .split(' ')
                .filter(|t| *t != "-" && !t.is_empty())
                .map(str::to_owned)
                .collect();
            at.insert((*text).to_owned(), entries.len());
            entries.push(Entry {
                text: (*text).to_owned(),
                group,
                name: String::new(),
                tones: if tones.len() == 5 { tones } else { Vec::new() },
                words: flag_words(text).into_iter().map(|w| (w, false)).collect(),
                names: Vec::new(),
                popularity: popular.get(*text).copied().unwrap_or(usize::MAX),
            });
        }
        if entries.is_empty() {
            return Err(EmojiError::Invalid {
                reason: "no emoji".to_owned(),
            });
        }
        let mut by_word: HashMap<String, Vec<(u32, bool)>> = HashMap::new();
        let mut searched: HashMap<String, Vec<u32>> = HashMap::new();
        let mut common: HashSet<String> = HashSet::new();
        for (n, table) in names.iter().take(MAX_LANGUAGES).enumerate() {
            let typed = n < typed as usize;
            for line in lines(table) {
                let f = fields(line);
                if let ["=common", "", words] = f.as_slice() {
                    if typed {
                        common.extend(words.split('|').map(key));
                    }
                    continue;
                }
                let [text, name, search] = f.as_slice() else {
                    continue;
                };
                let Some(&i) = at.get(*text) else { continue };
                let entry = &mut entries[i];
                if entry.name.is_empty() {
                    entry.name = (*name).to_owned();
                }
                let whole = folded(name);
                if typed && !whole.is_empty() && !whole.contains(' ') {
                    let list = by_word.entry(whole.clone()).or_default();
                    if !list.contains(&(i as u32, true)) {
                        list.push((i as u32, true));
                    }
                }
                if !whole.is_empty() && !entry.names.contains(&whole) {
                    entry.names.push(whole);
                }
                for w in words_of(name) {
                    entry.words.push((w, true));
                }
                for word in search.split('|') {
                    let found = words_of(word);
                    if let (true, [w], 1) =
                        (typed, found.as_slice(), folded(word).split(' ').count())
                    {
                        searched.entry(w.clone()).or_default().push(i as u32);
                    }
                    for w in found {
                        entry.words.push((w, false));
                    }
                }
            }
        }
        for entry in &mut entries {
            entry.words.sort();
            entry.words.dedup();
        }
        for (word, found) in searched {
            let list = by_word.entry(word).or_default();
            for i in found {
                if !list.iter().any(|(j, _)| *j == i) {
                    list.push((i, false));
                }
            }
        }
        Ok(EmojiIndex {
            entries,
            by_word,
            common,
        })
    }

    /// Every emoji, in keyboard order.
    pub fn all(&self) -> Vec<Emoji> {
        self.entries
            .iter()
            .map(|e| Emoji {
                text: e.text.clone(),
                group: e.group,
                name: e.name.clone(),
                tones: e.tones.clone(),
            })
            .collect()
    }

    /// The emoji that match every word of `query` (each may be the start of a
    /// word), best first, at most `limit`.
    pub fn search(&self, query: String, limit: u32) -> Vec<String> {
        let wanted = words_of(&query);
        if wanted.is_empty() {
            return Vec::new();
        }
        let phrase = folded(&query);
        let mut found: Vec<(Rank, usize)> = Vec::new();
        for (i, entry) in self.entries.iter().enumerate() {
            let Some(ranks) = wanted
                .iter()
                .map(|want| {
                    entry
                        .words
                        .iter()
                        .filter(|(word, _)| word.starts_with(want.as_str()))
                        .map(|(word, is_name)| (word != want, !is_name))
                        .min()
                })
                .collect::<Option<Vec<(bool, bool)>>>()
            else {
                continue;
            };
            let rank = Rank {
                other_name: !entry.names.contains(&phrase),
                unfinished: ranks.iter().filter(|(unfinished, _)| *unfinished).count(),
                popularity: entry.popularity,
                not_named: ranks.iter().filter(|(_, not_name)| *not_name).count(),
                not_first: !entry.names.iter().any(|n| n.starts_with(phrase.as_str())),
            };
            found.push((rank, i));
        }
        found.sort_unstable();
        found.truncate(limit as usize);
        found
            .into_iter()
            .map(|(_, i)| self.entries[i].text.clone())
            .collect()
    }

    /// The emoji a typed word stands for, or none when it is not clear: the
    /// word is an emoji's whole name, or a search word few emoji share.
    pub fn suggest(&self, word: String) -> Option<String> {
        let key = key(&word);
        if key.chars().count() < MIN_WORD
            || word
                .chars()
                .any(|c| !c.is_alphabetic() && !crate::text::is_connector(c))
        {
            return None;
        }
        let matches = self.by_word.get(&key)?;
        let entry = |i: u32| &self.entries[i as usize];
        let is_common = self.common.contains(&key);
        let best = |pool: &[(u32, bool)]| {
            pool.iter()
                .map(|(i, _)| (entry(*i).popularity, *i))
                .min()
                .map(|(_, i)| i)
        };
        let named: Vec<(u32, bool)> = matches
            .iter()
            .filter(|(_, whole)| *whole)
            .copied()
            .collect();
        let found = if !named.is_empty() {
            best(&named)
        } else if is_common {
            None
        } else if matches.len() <= FEW {
            best(matches)
        } else if matches.len() <= MANY {
            matches
                .iter()
                .filter(|(i, _)| entry(*i).popularity != usize::MAX)
                .map(|(i, _)| {
                    let e = entry(*i);
                    (e.words.len() + e.popularity / POPULARITY_STEP, *i)
                })
                .min()
                .map(|(_, i)| i)
        } else {
            None
        };
        found.map(|i| entry(i).text.clone())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const LIST: &[u8] = include_bytes!("../../../emoji/list.tsv");
    const EN: &[u8] = include_bytes!("../../../emoji/names/en-US.tsv");
    const DE: &[u8] = include_bytes!("../../../emoji/names/de.tsv");
    const FR: &[u8] = include_bytes!("../../../emoji/names/fr.tsv");
    const HE: &[u8] = include_bytes!("../../../emoji/names/he.tsv");
    const TR: &[u8] = include_bytes!("../../../emoji/names/tr.tsv");

    fn index(names: &[&[u8]]) -> EmojiIndex {
        let tables = names.iter().map(|n| n.to_vec()).collect();
        EmojiIndex::new(LIST.to_vec(), tables, names.len() as u32).unwrap()
    }

    #[test]
    fn every_emoji_is_named_and_grouped() {
        let all = index(&[EN]).all();
        let rows = std::str::from_utf8(LIST).unwrap().lines();
        assert_eq!(all.len(), rows.filter(|l| !l.starts_with("# ")).count());
        assert!(all.len() > 1_900, "{}", all.len());
        assert!(all.iter().any(|e| e.text == "#️⃣"));
        for e in &all {
            assert!(!e.name.is_empty(), "{} has no name", e.text);
            assert!(e.group <= 8);
            assert!(e.tones.is_empty() || e.tones.len() == 5, "{}", e.text);
        }
        let wave = all.iter().find(|e| e.text == "👋").unwrap();
        assert_eq!(wave.tones[0], "👋🏻");
        assert_eq!(wave.tones[4], "👋🏿");
        assert_eq!(all.iter().filter(|e| e.group == 8).count(), 270);
        assert_eq!(all.iter().find(|e| e.text == "🏴").unwrap().group, 8);
    }

    #[test]
    fn search_finds_names_before_search_words() {
        let i = index(&[EN]);
        assert_eq!(i.search("pizza".into(), 10)[0], "🍕");
        assert_eq!(i.search("coffee".into(), 10)[0], "☕");
        assert_eq!(i.search("party popper".into(), 10)[0], "🎉");
        assert_eq!(i.search("thumbs up".into(), 5)[0], "👍");
        // Well known ones come first; one called just that before them.
        assert_eq!(i.search("heart".into(), 60)[0], "❤️");
        assert_eq!(i.search("hea".into(), 60)[0], "❤️");
        assert_eq!(i.search("car".into(), 60)[0], "🚗");
        assert_eq!(i.search("cat".into(), 60)[0], "🐈");
        assert_eq!(i.search("love letter".into(), 60)[0], "💌");
        // The start of a word is enough, and words can come in any order.
        assert_eq!(i.search("piz".into(), 5)[0], "🍕");
        assert!(i.search("france flag".into(), 5).contains(&"🇫🇷".to_owned()));
        assert!(
            i.search("FLAG: FRANCE".into(), 5)
                .contains(&"🇫🇷".to_owned())
        );
        // Country codes find flags, but stand for no emoji.
        assert_eq!(i.search("flag us".into(), 5)[0], "🇺🇸");
        assert_eq!(i.search("usa".into(), 5)[0], "🇺🇸");
        assert_eq!(i.search("uk".into(), 5)[0], "🇬🇧");
        assert!(i.search("flag de".into(), 9).contains(&"🇩🇪".to_owned()));
        assert_eq!(i.suggest("usa".into()), None);
        assert!(i.search("zzzzqqq".into(), 5).is_empty());
        assert!(i.search(" - ".into(), 5).is_empty());
        assert_eq!(i.search("a".into(), 3).len(), 3);
    }

    #[test]
    fn search_works_in_every_language_at_once_and_without_accents() {
        let i = index(&[DE, EN]);
        assert!(i.search("kaffee".into(), 10).contains(&"☕".to_owned()));
        assert!(i.search("coffee".into(), 10).contains(&"☕".to_owned()));
        // German names come first, so they are read out.
        let coffee = i.all().into_iter().find(|e| e.text == "☕").unwrap();
        assert!(coffee.name.contains("Heiß"), "{}", coffee.name);
        // "ä" and "a", "ı" and "i" are the same.
        assert!(!index(&[TR]).search("kirmizi kalp".into(), 10).is_empty());
        assert!(!index(&[HE]).search("לב".into(), 10).is_empty());
        // So are "œ" and "oe", "ß" and "ss".
        let fr = index(&[FR]);
        assert_eq!(fr.search("coeur".into(), 9), fr.search("cœur".into(), 9));
        assert!(fr.search("coeur".into(), 9).contains(&"❤️".to_owned()));
        let de = index(&[DE]);
        assert_eq!(
            de.search("fussball".into(), 5),
            de.search("fußball".into(), 5)
        );
        assert!(de.search("fussball".into(), 5).contains(&"⚽".to_owned()));
    }

    #[test]
    fn a_word_gets_an_emoji_only_when_it_is_clear() {
        let i = index(&[EN]);
        assert_eq!(i.suggest("pizza".into()).as_deref(), Some("🍕"));
        assert_eq!(i.suggest("Pizza".into()).as_deref(), Some("🍕"));
        assert_eq!(i.suggest("love".into()).as_deref(), Some("❤️"));
        // "the" is in a name, not a search word: "rolling on the floor laughing".
        assert_eq!(i.suggest("the".into()), None);
        // Search words too common to mean one emoji; a whole name still counts.
        assert_eq!(i.suggest("time".into()), None);
        assert_eq!(i.suggest("one".into()), None);
        // Too short, too general, not a word.
        assert_eq!(i.suggest("ok".into()), None);
        assert_eq!(i.suggest("face".into()), None);
        assert_eq!(i.suggest("a1b2".into()), None);
        assert_eq!(i.suggest("".into()), None);
        assert_eq!(i.suggest("qwertyuiop".into()), None);
    }

    #[test]
    fn a_language_that_is_only_searched_stands_for_no_emoji() {
        // "Gift" is poison in German; English comes along for the search.
        let tables = vec![DE.to_vec(), EN.to_vec()];
        let i = EmojiIndex::new(LIST.to_vec(), tables.clone(), 1).unwrap();
        assert_ne!(i.suggest("Gift".into()).as_deref(), Some("🎁"));
        assert_eq!(i.suggest("coffee".into()), None);
        assert!(i.search("gift".into(), 20).contains(&"🎁".to_owned()));
        assert!(i.search("coffee".into(), 10).contains(&"☕".to_owned()));
        // Typed too, both stand for emoji.
        let both = EmojiIndex::new(LIST.to_vec(), tables, 2).unwrap();
        assert_eq!(both.suggest("coffee".into()).as_deref(), Some("☕"));
        assert_eq!(both.suggest("Geschenk".into()).as_deref(), Some("🎁"));
        assert_eq!(i.suggest("Geschenk".into()).as_deref(), Some("🎁"));
    }

    #[test]
    fn tables_that_hold_nothing_are_refused_and_bad_lines_skipped() {
        assert!(EmojiIndex::new(Vec::new(), Vec::new(), 0).is_err());
        assert!(EmojiIndex::new(b"# only a comment\n".to_vec(), Vec::new(), 0).is_err());
        let list =
            "😀\t0\t1.0\t-\n\t0\t1.0\t-\n😃\t9\t1.0\t-\n😄\tx\t1.0\t-\nshort\n😀\t0\t1.0\t-\n"
                .as_bytes()
                .to_vec();
        let names = "😀\tgrin\thappy|smile\nnot enough\n😃\tghost\tx\n\tempty\t\n"
            .as_bytes()
            .to_vec();
        let i = EmojiIndex::new(list, vec![names], 1).unwrap();
        assert_eq!(i.all().len(), 1);
        assert_eq!(i.search("happy".into(), 5), vec!["😀"]);
        let tones = "👋\t1\t0.6\t👋🏻 👋🏼 👋🏽 👋🏾 👋🏿\n🙏\t1\t0.6\tone two\n"
            .as_bytes()
            .to_vec();
        let i = EmojiIndex::new(tones, Vec::new(), 0).unwrap();
        assert_eq!(i.all()[0].tones.len(), 5);
        assert!(i.all()[1].tones.is_empty());
    }

    #[test]
    fn mutated_tables_never_panic() {
        let mut seed = 0x1357_9bdf_u32;
        let mut next = move || {
            seed ^= seed << 13;
            seed ^= seed >> 17;
            seed ^= seed << 5;
            seed
        };
        let (list, en) = (&LIST[..8_000], &EN[..8_000]);
        for _ in 0..300 {
            let mut l = list.to_vec();
            let mut n = en.to_vec();
            for _ in 0..8 {
                let i = next() as usize;
                let bits = (next() & 0xff) as u8;
                let at = i % l.len();
                l[at] = bits;
                let at = i % n.len();
                n[at] = bits;
            }
            if let Ok(i) = EmojiIndex::new(l, vec![n], 1) {
                i.all();
                i.search("heart".into(), 20);
                i.suggest("pizza".into());
            }
        }
    }
}
