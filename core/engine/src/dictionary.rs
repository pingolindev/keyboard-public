// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: Apache-2.0 OR MIT

//! The binary dictionary: a trie over word keys (see `text::key_of`), built
//! from a word list when the app is built, and read back by the decoder.
//!
//! Dictionaries also arrive as files the user opens, so reading one
//! treats it as hostile: every count, offset and string is checked before
//! use, child nodes always come after their parent (so every walk ends), and
//! a bad file is refused as a whole.
//!
//! Format, version 2. Little-endian, no padding. A 64-byte header of u32
//! values after the magic:
//!
//! | offset | field |
//! |---|---|
//! | 0 | magic `PKBDICT\0` |
//! | 8 | version = 2 |
//! | 12 | nodes N; node 0 is the root |
//! | 16 | alphabet size A |
//! | 20 | terminals T: nodes that end a word |
//! | 24 | entries E: words (a key can have several spellings) |
//! | 28 | spelled entries S: entries not spelled like their key |
//! | 32 | spelling bytes |
//! | 36 | correction bytes |
//! | 40 | metadata bytes |
//! | 44 | deepest node, at most 48 |
//! | 48 | new-word table size: 0 or (A+1)² |
//! | 52 | pair sources P: nodes with next-word pairs |
//! | 56 | pairs Q |
//! | 60 | pair word bytes |
//!
//! Then, in order:
//!
//! - alphabet: A key characters (u32 code points), ascending;
//! - log-probability of each frequency value 0..=255 (f32);
//! - new-word model: letter bigram log-probabilities (f32), row = previous
//!   letter or A for the start, column = next letter or A for the end;
//! - first child of each node, plus a final N (u32 × (N+1)). Nodes are in
//!   breadth-first order, so the children of node i are
//!   `first_child[i]..first_child[i+1]`;
//! - label of each node: alphabet index of the letter leading to it, u8 when
//!   A ≤ 256, else u16;
//! - highest frequency of any word at or below each node (u8);
//! - terminal bits, one per node (u64 words, bit i of word i/64), then the
//!   number of terminals before each u64 word (u32 each);
//! - first entry of each terminal, plus a final E (u32 × (T+1));
//! - entries, 2 bytes each: frequency, flags (bit 7: spelled differently
//!   from the key);
//! - spelled entries: S entry numbers (u32, ascending), then S offsets into
//!   the spelling bytes (u32);
//! - spelling bytes: strings, each a u16 byte length and UTF-8;
//! - pair sources: P node numbers (u32, ascending; node 0 stands for the
//!   start of a sentence), then the first pair of each, plus a final Q
//!   (u32 × (P+1));
//! - pairs: Q pair word numbers (u32), then Q costs (u8, -ln p × 8), cheapest
//!   first within each source;
//! - pair word bytes: strings as above; a pair word number is the string's
//!   position in this list;
//! - correction bytes: pairs of such strings (typed, replacement);
//! - metadata bytes: pairs of such strings (key, value).

use std::collections::{BTreeMap, HashMap};
use std::fmt;

use crate::text::{MAX_KEY_LEN, is_key_char, key_of, nfc};
use crate::wordlist::{FLAG_OFFENSIVE, KNOWN_FLAGS, SENTENCE_START, WordList};

pub const MAGIC: &[u8; 8] = b"PKBDICT\0";
pub const VERSION: u32 = 2;
const HEADER_LEN: usize = 64;
/// Largest alphabet (distinct key characters) a dictionary may have.
pub const MAX_ALPHABET: usize = 4096;
/// Alphabets up to this size get a letter bigram model for new words, and
/// one-byte labels.
const SMALL_ALPHABET: usize = 256;
const MAX_NODES: usize = 1 << 24;
const MAX_ENTRIES: usize = 1 << 24;
const MAX_BLOB: usize = 1 << 26;
/// Flag bit of an entry whose spelling is stored (not its key).
const FLAG_SPELLED: u8 = 0x80;
/// Additive smoothing of the new-word letter bigrams.
const OOV_SMOOTHING: f64 = 0.5;
/// Next words kept per previous word.
pub const MAX_PAIRS_PER_SOURCE: usize = 16;
/// Pair costs are -ln p in steps of 1/8.
const PAIR_COST_SCALE: f32 = 8.0;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Entry {
    pub frequency: u8,
    pub flags: u8,
}

impl Entry {
    /// The word flags (`wordlist::FLAG_*`), without the format's own bit.
    pub fn word_flags(&self) -> u8 {
        self.flags & !FLAG_SPELLED
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FormatError(pub String);

impl fmt::Display for FormatError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "invalid dictionary: {}", self.0)
    }
}

impl std::error::Error for FormatError {}

fn bad<T>(message: impl Into<String>) -> Result<T, FormatError> {
    Err(FormatError(message.into()))
}

#[derive(Debug)]
enum Labels {
    Narrow(Vec<u8>),
    Wide(Vec<u16>),
}

impl Labels {
    fn get(&self, i: usize) -> u16 {
        match self {
            Labels::Narrow(v) => u16::from(v[i]),
            Labels::Wide(v) => v[i],
        }
    }

    fn len(&self) -> usize {
        match self {
            Labels::Narrow(v) => v.len(),
            Labels::Wide(v) => v.len(),
        }
    }
}

/// A dictionary in memory, read from the binary format.
#[derive(Debug)]
pub struct Dictionary {
    alphabet: Vec<char>,
    logp: Vec<f32>,
    oov: Vec<f32>,
    first_child: Vec<u32>,
    labels: Labels,
    max_freq: Vec<u8>,
    terminal_bits: Vec<u64>,
    terminal_rank: Vec<u32>,
    entry_first: Vec<u32>,
    entries: Vec<Entry>,
    spelled_entries: Vec<u32>,
    spelled_offsets: Vec<u32>,
    spellings: Vec<u8>,
    pair_sources: Vec<u32>,
    pair_first: Vec<u32>,
    pair_words: Vec<u32>,
    pair_costs: Vec<u8>,
    pair_word_offsets: Vec<u32>,
    pair_word_bytes: Vec<u8>,
    corrections: HashMap<String, String>,
    metadata: Vec<(String, String)>,
}

struct Reader<'a> {
    bytes: &'a [u8],
    pos: usize,
}

impl<'a> Reader<'a> {
    fn take(&mut self, len: usize) -> Result<&'a [u8], FormatError> {
        let end = self
            .pos
            .checked_add(len)
            .filter(|end| *end <= self.bytes.len());
        match end {
            Some(end) => {
                let out = &self.bytes[self.pos..end];
                self.pos = end;
                Ok(out)
            }
            None => bad("the file is truncated"),
        }
    }

    fn u32(&mut self) -> Result<u32, FormatError> {
        let b = self.take(4)?;
        Ok(u32::from_le_bytes([b[0], b[1], b[2], b[3]]))
    }

    fn u32s(&mut self, count: usize) -> Result<Vec<u32>, FormatError> {
        let len = count
            .checked_mul(4)
            .ok_or(FormatError("size overflow".into()))?;
        Ok(self
            .take(len)?
            .chunks_exact(4)
            .map(|b| u32::from_le_bytes([b[0], b[1], b[2], b[3]]))
            .collect())
    }

    fn u64s(&mut self, count: usize) -> Result<Vec<u64>, FormatError> {
        let len = count
            .checked_mul(8)
            .ok_or(FormatError("size overflow".into()))?;
        Ok(self
            .take(len)?
            .chunks_exact(8)
            .map(|b| u64::from_le_bytes([b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7]]))
            .collect())
    }

    fn f32s(&mut self, count: usize) -> Result<Vec<f32>, FormatError> {
        Ok(self.u32s(count)?.into_iter().map(f32::from_bits).collect())
    }

    fn u16s(&mut self, count: usize) -> Result<Vec<u16>, FormatError> {
        let len = count
            .checked_mul(2)
            .ok_or(FormatError("size overflow".into()))?;
        Ok(self
            .take(len)?
            .chunks_exact(2)
            .map(|b| u16::from_le_bytes([b[0], b[1]]))
            .collect())
    }
}

/// Reads one length-prefixed string at `pos` of `blob`; returns it and the
/// position after it.
fn blob_string(blob: &[u8], pos: usize) -> Option<(&str, usize)> {
    let len_bytes = blob.get(pos..pos.checked_add(2)?)?;
    let len = u16::from_le_bytes([len_bytes[0], len_bytes[1]]) as usize;
    let start = pos + 2;
    let end = start.checked_add(len)?;
    let s = std::str::from_utf8(blob.get(start..end)?).ok()?;
    Some((s, end))
}

/// Reads a blob of string pairs.
fn blob_pairs(blob: &[u8], what: &str) -> Result<Vec<(String, String)>, FormatError> {
    let mut out = Vec::new();
    let mut pos = 0;
    while pos < blob.len() {
        let Some((a, next)) = blob_string(blob, pos) else {
            return bad(format!("bad string in the {what}"));
        };
        let Some((b, next)) = blob_string(blob, next) else {
            return bad(format!("bad string in the {what}"));
        };
        out.push((a.to_owned(), b.to_owned()));
        pos = next;
    }
    Ok(out)
}

/// The offsets of a blob of strings, checked.
fn blob_offsets(blob: &[u8], what: &str) -> Result<Vec<u32>, FormatError> {
    let mut out = Vec::new();
    let mut pos = 0;
    while pos < blob.len() {
        match blob_string(blob, pos) {
            Some((s, next)) if !s.is_empty() => {
                out.push(pos as u32);
                pos = next;
            }
            _ => return bad(format!("bad string in the {what}")),
        }
    }
    Ok(out)
}

fn ascending(v: &[u32]) -> bool {
    v.windows(2).all(|w| w[0] < w[1])
}

fn non_decreasing(v: &[u32]) -> bool {
    v.windows(2).all(|w| w[0] <= w[1])
}

impl Dictionary {
    /// Reads and checks a dictionary file.
    pub fn from_bytes(bytes: &[u8]) -> Result<Self, FormatError> {
        let mut r = Reader { bytes, pos: 0 };
        if r.take(8)? != MAGIC {
            return bad("not a Pingolin Keyboard dictionary");
        }
        let version = r.u32()?;
        if version != VERSION {
            return bad(format!("format version {version} is not supported"));
        }
        let mut header = [0usize; 13];
        for h in header.iter_mut() {
            *h = r.u32()? as usize;
        }
        let [
            n,
            a,
            t,
            e,
            s,
            spellings_len,
            corrections_len,
            metadata_len,
            max_depth,
            oov_len,
            p,
            q,
            pair_bytes_len,
        ] = header;
        if r.pos != HEADER_LEN {
            return bad("bad header");
        }
        if n == 0 || n > MAX_NODES || t > n || a > MAX_ALPHABET || e > MAX_ENTRIES || s > e {
            return bad("bad counts");
        }
        if p > n || q > MAX_ENTRIES {
            return bad("bad pair counts");
        }
        if [spellings_len, corrections_len, metadata_len, pair_bytes_len]
            .iter()
            .any(|len| *len > MAX_BLOB)
        {
            return bad("bad section size");
        }
        if max_depth > MAX_KEY_LEN {
            return bad("words are too long");
        }
        let full_oov = (a + 1) * (a + 1);
        if oov_len != 0 && (oov_len != full_oov || a > SMALL_ALPHABET) {
            return bad("bad new-word table size");
        }

        let alphabet: Vec<char> = r
            .u32s(a)?
            .into_iter()
            .map(|cp| char::from_u32(cp).filter(|c| is_key_char(*c)))
            .collect::<Option<Vec<char>>>()
            .ok_or(FormatError("bad alphabet character".into()))?;
        if alphabet.windows(2).any(|w| w[0] >= w[1]) {
            return bad("the alphabet is not sorted");
        }
        let logp = r.f32s(256)?;
        if logp.iter().any(|p| !p.is_finite() || *p > 0.0)
            || logp[1..].windows(2).any(|w| w[0] > w[1])
        {
            return bad("bad frequency table");
        }
        let oov = r.f32s(oov_len)?;
        if oov.iter().any(|p| !p.is_finite() || *p > 0.0) {
            return bad("bad new-word table");
        }
        let first_child = r.u32s(n + 1)?;
        let labels = if a <= SMALL_ALPHABET {
            Labels::Narrow(r.take(n)?.to_vec())
        } else {
            Labels::Wide(r.u16s(n)?)
        };
        let max_freq = r.take(n)?.to_vec();
        let words = n.div_ceil(64);
        let terminal_bits = r.u64s(words)?;
        let terminal_rank = r.u32s(words)?;
        let entry_first = r.u32s(t + 1)?;
        let entries: Vec<Entry> = r
            .take(
                e.checked_mul(2)
                    .ok_or(FormatError("size overflow".into()))?,
            )?
            .chunks_exact(2)
            .map(|b| Entry {
                frequency: b[0],
                flags: b[1],
            })
            .collect();
        let spelled_entries = r.u32s(s)?;
        let spelled_offsets = r.u32s(s)?;
        let spellings = r.take(spellings_len)?.to_vec();
        let pair_sources = r.u32s(p)?;
        let pair_first = r.u32s(p + 1)?;
        let pair_words = r.u32s(q)?;
        let pair_costs = r.take(q)?.to_vec();
        let pair_word_bytes = r.take(pair_bytes_len)?.to_vec();
        let corrections_blob = r.take(corrections_len)?;
        let metadata_blob = r.take(metadata_len)?;
        if r.pos != bytes.len() {
            return bad("trailing bytes");
        }

        // The tree: children after their parent, each node with one parent,
        // sorted distinct labels under each node, bounded depth.
        if first_child[0] != 1.min(n as u32) || first_child[n] as usize != n {
            return bad("bad root");
        }
        if labels.get(0) != 0 {
            return bad("the root has a label");
        }
        let mut depth = vec![0u8; n];
        for i in 0..n {
            let (start, end) = (first_child[i] as usize, first_child[i + 1] as usize);
            if start < i + 1 || end < start || end > n {
                return bad("bad child range");
            }
            for c in start..end {
                if labels.get(c) as usize >= a {
                    return bad("label outside the alphabet");
                }
                if c > start && labels.get(c - 1) >= labels.get(c) {
                    return bad("children are not sorted");
                }
                depth[c] = depth[i] + 1;
                if depth[c] as usize > max_depth {
                    return bad("a word is deeper than the header says");
                }
            }
        }
        // Terminals: the bits and their running counts agree with T, and the
        // root ends no word.
        let mut seen = 0u32;
        for (w, bits) in terminal_bits.iter().enumerate() {
            if terminal_rank[w] != seen {
                return bad("bad terminal counts");
            }
            seen += bits.count_ones();
        }
        let tail = n % 64;
        if tail != 0 && terminal_bits[words - 1] >> tail != 0 {
            return bad("terminal bits past the last node");
        }
        if seen as usize != t || terminal_bits[0] & 1 != 0 {
            return bad("bad terminals");
        }
        // Entries: every terminal has at least one, in order.
        if entry_first[0] != 0 || entry_first[t] as usize != e || !ascending(&entry_first) {
            return bad("bad entry ranges");
        }
        if !ascending(&spelled_entries) || spelled_entries.iter().any(|i| *i as usize >= e) {
            return bad("bad spelled entries");
        }
        let mut spelled = spelled_entries.iter().peekable();
        for (i, entry) in entries.iter().enumerate() {
            if entry.flags & !(KNOWN_FLAGS | FLAG_SPELLED) != 0 {
                return bad("unknown word flag");
            }
            let listed = spelled.peek().is_some_and(|x| **x as usize == i);
            if listed {
                spelled.next();
            }
            if listed != (entry.flags & FLAG_SPELLED != 0) {
                return bad("spelling flag and list disagree");
            }
        }
        for offset in &spelled_offsets {
            match blob_string(&spellings, *offset as usize) {
                Some((s, _)) if !s.is_empty() => {}
                _ => return bad("bad word spelling"),
            }
        }
        // The frequency bound under each node, which the search relies on.
        let mut expected = vec![0u8; n];
        let mut terminal = t;
        for i in (0..n).rev() {
            let own = if terminal_bits[i / 64] >> (i % 64) & 1 == 1 {
                terminal -= 1;
                entries[entry_first[terminal] as usize..entry_first[terminal + 1] as usize]
                    .iter()
                    .map(|e| e.frequency)
                    .max()
                    .unwrap_or(0)
            } else {
                0
            };
            let below = (first_child[i]..first_child[i + 1])
                .map(|c| expected[c as usize])
                .max()
                .unwrap_or(0);
            expected[i] = own.max(below);
        }
        if expected != max_freq {
            return bad("bad frequency bounds");
        }
        // Pairs.
        let pair_word_offsets = blob_offsets(&pair_word_bytes, "pair words")?;
        if !ascending(&pair_sources)
            || pair_sources.iter().any(|x| *x as usize >= n)
            || pair_first.first() != Some(&0)
            || pair_first.last().map(|x| *x as usize) != Some(q)
            || !non_decreasing(&pair_first)
            || pair_first
                .windows(2)
                .any(|w| (w[1] - w[0]) as usize > MAX_PAIRS_PER_SOURCE)
            || pair_words
                .iter()
                .any(|w| *w as usize >= pair_word_offsets.len())
        {
            return bad("bad pairs");
        }

        let mut corrections = HashMap::new();
        for (typed, replacement) in blob_pairs(corrections_blob, "corrections")? {
            if typed.is_empty() || replacement.is_empty() {
                return bad("empty correction");
            }
            corrections.insert(typed, replacement);
        }
        let metadata = blob_pairs(metadata_blob, "metadata")?;

        Ok(Dictionary {
            alphabet,
            logp,
            oov,
            first_child,
            labels,
            max_freq,
            terminal_bits,
            terminal_rank,
            entry_first,
            entries,
            spelled_entries,
            spelled_offsets,
            spellings,
            pair_sources,
            pair_first,
            pair_words,
            pair_costs,
            pair_word_offsets,
            pair_word_bytes,
            corrections,
            metadata,
        })
    }

    pub fn alphabet(&self) -> &[char] {
        &self.alphabet
    }

    pub fn label_of(&self, c: char) -> Option<u16> {
        self.alphabet.binary_search(&c).ok().map(|i| i as u16)
    }

    pub fn node_count(&self) -> usize {
        self.labels.len()
    }

    pub fn word_count(&self) -> usize {
        self.entries.len()
    }

    pub fn label(&self, node: u32) -> u16 {
        self.labels.get(node as usize)
    }

    pub fn max_frequency(&self, node: u32) -> u8 {
        self.max_freq[node as usize]
    }

    pub fn children(&self, node: u32) -> std::ops::Range<u32> {
        self.first_child[node as usize]..self.first_child[node as usize + 1]
    }

    pub fn child(&self, node: u32, label: u16) -> Option<u32> {
        let range = self.children(node);
        let (mut lo, mut hi) = (range.start, range.end);
        while lo < hi {
            let mid = lo + (hi - lo) / 2;
            match self.label(mid).cmp(&label) {
                std::cmp::Ordering::Less => lo = mid + 1,
                std::cmp::Ordering::Greater => hi = mid,
                std::cmp::Ordering::Equal => return Some(mid),
            }
        }
        None
    }

    /// The node of a key, if the dictionary has it.
    pub fn find(&self, key: &[char]) -> Option<u32> {
        let mut node = 0u32;
        for c in key {
            node = self.child(node, self.label_of(*c)?)?;
        }
        Some(node)
    }

    /// The entry numbers of the words ending at `node` (empty if none).
    pub fn entries(&self, node: u32) -> std::ops::Range<u32> {
        let i = node as usize;
        let (w, b) = (i / 64, i % 64);
        let bits = self.terminal_bits[w];
        if bits >> b & 1 == 0 {
            return 0..0;
        }
        let t = self.terminal_rank[w] as usize + (bits & ((1u64 << b) - 1)).count_ones() as usize;
        self.entry_first[t]..self.entry_first[t + 1]
    }

    pub fn entry(&self, id: u32) -> Entry {
        self.entries[id as usize]
    }

    /// How entry `id` is spelled; `key` is the key of its node.
    pub fn spelling(&self, id: u32, key: &[char]) -> String {
        if self.entries[id as usize].flags & FLAG_SPELLED != 0
            && let Ok(i) = self.spelled_entries.binary_search(&id)
            && let Some((s, _)) = blob_string(&self.spellings, self.spelled_offsets[i] as usize)
        {
            return s.to_owned();
        }
        key.iter().collect()
    }

    /// Natural log of the probability of a word with this frequency value.
    pub fn log_probability(&self, frequency: u8) -> f32 {
        self.logp[frequency as usize]
    }

    /// Natural log of the probability that someone typing a word that is not
    /// in the dictionary types exactly this key.
    pub fn new_word_log_probability(&self, key: &[char]) -> f32 {
        let a = self.alphabet.len();
        if self.oov.is_empty() {
            return -((a + 1) as f32).ln() * (key.len() + 1) as f32;
        }
        let row = |prev: usize| prev * (a + 1);
        let unknown = -((a + 1) as f32).ln();
        let mut prev = a; // start
        let mut total = 0.0;
        for c in key {
            match self.label_of(*c) {
                Some(l) => {
                    total += self.oov[row(prev) + l as usize];
                    prev = l as usize;
                }
                None => {
                    total += unknown;
                    prev = a;
                }
            }
        }
        total + self.oov[row(prev) + a]
    }

    /// Words that follow the word at `node` (node 0: a sentence start), most
    /// likely first, with ln P(word | previous).
    pub fn followers(&self, node: u32) -> impl Iterator<Item = (&str, f32)> + '_ {
        let range = match self.pair_sources.binary_search(&node) {
            Ok(i) => self.pair_first[i] as usize..self.pair_first[i + 1] as usize,
            Err(_) => 0..0,
        };
        range.filter_map(move |i| {
            let offset = self.pair_word_offsets[self.pair_words[i] as usize] as usize;
            let (word, _) = blob_string(&self.pair_word_bytes, offset)?;
            Some((word, -f32::from(self.pair_costs[i]) / PAIR_COST_SCALE))
        })
    }

    /// The fixed correction for a typed word (in lower case), if any.
    pub fn correction(&self, typed_lower: &str) -> Option<&str> {
        self.corrections.get(typed_lower).map(String::as_str)
    }

    pub fn metadata(&self, key: &str) -> Option<&str> {
        self.metadata
            .iter()
            .find(|(k, _)| k == key)
            .map(|(_, v)| v.as_str())
    }

    pub fn locale(&self) -> &str {
        self.metadata("locale").unwrap_or("")
    }

    /// The most frequent words, for predictions with no context.
    pub fn top_words(&self, count: usize) -> Vec<(String, f32)> {
        let mut best: Vec<(u8, u32, Vec<char>)> = Vec::new();
        let mut stack: Vec<(u32, Vec<char>)> = vec![(0, Vec::new())];
        while let Some((node, key)) = stack.pop() {
            let floor = if best.len() >= count {
                best[count - 1].0
            } else {
                0
            };
            if self.max_frequency(node) <= floor && best.len() >= count {
                continue;
            }
            for id in self.entries(node) {
                let e = self.entry(id);
                if e.frequency == 0 || e.flags & FLAG_OFFENSIVE != 0 {
                    continue;
                }
                if e.frequency > floor || best.len() < count {
                    best.push((e.frequency, id, key.clone()));
                    best.sort_by(|x, y| y.0.cmp(&x.0).then(x.1.cmp(&y.1)));
                    best.truncate(count);
                }
            }
            for c in self.children(node) {
                let mut k = key.clone();
                k.push(self.alphabet[self.label(c) as usize]);
                stack.push((c, k));
            }
        }
        best.into_iter()
            .map(|(f, id, key)| (self.spelling(id, &key), self.log_probability(f)))
            .collect()
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BuildError(pub String);

impl fmt::Display for BuildError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for BuildError {}

/// What to build besides the word list itself.
#[derive(Debug, Clone, Default)]
pub struct BuildOptions {
    /// Log-probabilities for the frequency values, instead of ones derived
    /// from the words' ranks (the user's own words use a fixed table).
    pub log_probabilities: Option<Vec<f32>>,
}

struct BuildNode {
    children: BTreeMap<u16, usize>,
    entries: Vec<usize>,
}

fn push_string(blob: &mut Vec<u8>, s: &str) -> Result<(), BuildError> {
    let len = u16::try_from(s.len()).map_err(|_| BuildError(format!("{s:?} is too long")))?;
    blob.extend_from_slice(&len.to_le_bytes());
    blob.extend_from_slice(s.as_bytes());
    Ok(())
}

/// Log-probabilities for the frequency values 0..=255, from the ranks of the
/// words that have them, assuming Zipf's law (probability ∝ 1 / rank). The
/// AOSP scale is only roughly logarithmic, so the values come from ranks
/// rather than from the scale itself.
fn frequency_table(list: &WordList) -> Vec<f32> {
    let mut count = [0u64; 256];
    for w in &list.words {
        if w.frequency > 0 {
            count[w.frequency as usize] += 1;
        }
    }
    let total: u64 = count.iter().sum();
    let mut table = vec![f32::NAN; 256];
    if total == 0 {
        return vec![-20.0; 256];
    }
    let harmonic = (total as f64).ln() + 0.577_215_664_9 + 1.0 / (2.0 * total as f64);
    let mut rank = 0u64; // ranks handed out so far, most frequent first
    for f in (1..256).rev() {
        let c = count[f];
        if c == 0 {
            continue;
        }
        let (first, last) = (rank + 1, rank + c);
        rank = last;
        // Mean probability of ranks first..=last under 1/(H·r).
        let mass = ((last as f64 + 0.5) / (first as f64 - 0.5)).ln() / harmonic;
        table[f] = (mass / c as f64).ln() as f32;
    }
    // Values no word uses: copy the nearest lower used value (or the lowest).
    let lowest = table
        .iter()
        .copied()
        .filter(|p| p.is_finite())
        .fold(f32::INFINITY, f32::min);
    let mut carry = lowest;
    for p in table.iter_mut().skip(1) {
        if p.is_finite() {
            carry = *p;
        } else {
            *p = carry;
        }
    }
    table[0] = lowest;
    table
}

/// Letter bigrams of the dictionary's keys, each key counted once: what a
/// word the dictionary does not know tends to look like.
fn new_word_table(keys: &[Vec<u16>], a: usize) -> Vec<f32> {
    if a > SMALL_ALPHABET {
        return Vec::new();
    }
    let width = a + 1;
    let mut counts = vec![0f64; width * width];
    for key in keys {
        let mut prev = a;
        for &l in key {
            counts[prev * width + l as usize] += 1.0;
            prev = l as usize;
        }
        counts[prev * width + a] += 1.0;
    }
    let mut table = vec![0f32; width * width];
    for row in 0..width {
        let total: f64 = counts[row * width..(row + 1) * width].iter().sum();
        for col in 0..width {
            let p = (counts[row * width + col] + OOV_SMOOTHING)
                / (total + OOV_SMOOTHING * width as f64);
            table[row * width + col] = p.ln() as f32;
        }
    }
    table
}

/// Builds the binary form of a word list.
pub fn build(list: &WordList) -> Result<Vec<u8>, BuildError> {
    build_with(list, &BuildOptions::default())
}

/// Builds the binary form of a word list, with options.
pub fn build_with(list: &WordList, options: &BuildOptions) -> Result<Vec<u8>, BuildError> {
    // Keys and the alphabet.
    let keys: Vec<Vec<char>> = list.words.iter().map(|w| key_of(&w.word)).collect();
    let mut alphabet: Vec<char> = keys.iter().flatten().copied().collect();
    alphabet.sort_unstable();
    alphabet.dedup();
    if alphabet.len() > MAX_ALPHABET {
        return Err(BuildError(format!(
            "{} distinct letters, at most {MAX_ALPHABET}",
            alphabet.len()
        )));
    }
    if let Some(c) = alphabet.iter().find(|c| !is_key_char(**c)) {
        return Err(BuildError(format!("{c:?} cannot be a key letter")));
    }
    let a = alphabet.len();
    for (word, key) in list.words.iter().zip(&keys) {
        if key.is_empty() || key.len() > MAX_KEY_LEN {
            return Err(BuildError(format!("{:?} has an unusable key", word.word)));
        }
        if word.flags & !KNOWN_FLAGS != 0 {
            return Err(BuildError(format!("{:?} has unknown flags", word.word)));
        }
    }
    let label_keys: Vec<Vec<u16>> = keys
        .iter()
        .map(|k| {
            k.iter()
                .map(|c| alphabet.binary_search(c).unwrap() as u16)
                .collect()
        })
        .collect();

    // A pointer trie, then breadth-first order.
    let mut nodes = vec![BuildNode {
        children: BTreeMap::new(),
        entries: Vec::new(),
    }];
    for (i, key) in label_keys.iter().enumerate() {
        let mut node = 0usize;
        for &l in key {
            node = match nodes[node].children.get(&l) {
                Some(&c) => c,
                None => {
                    nodes.push(BuildNode {
                        children: BTreeMap::new(),
                        entries: Vec::new(),
                    });
                    let c = nodes.len() - 1;
                    nodes[node].children.insert(l, c);
                    c
                }
            };
        }
        nodes[node].entries.push(i);
    }
    if nodes.len() > MAX_NODES {
        return Err(BuildError("too many nodes".into()));
    }
    let mut order = Vec::with_capacity(nodes.len()); // order[new] = old
    let mut new_index = vec![0u32; nodes.len()];
    let mut label_of_node = vec![0u16; nodes.len()];
    order.push(0usize);
    let mut head = 0;
    while head < order.len() {
        let old = order[head];
        new_index[old] = head as u32;
        head += 1;
        for (&l, &c) in &nodes[old].children {
            label_of_node[c] = l;
            order.push(c);
        }
    }
    let n = order.len();

    let mut first_child = Vec::with_capacity(n + 1);
    let mut next = 1u32;
    for &old in &order {
        first_child.push(next.min(n as u32));
        next += nodes[old].children.len() as u32;
    }
    first_child.push(n as u32);

    let words = n.div_ceil(64);
    let mut terminal_bits = vec![0u64; words];
    let mut entry_first = Vec::new();
    let mut entries: Vec<Entry> = Vec::with_capacity(list.words.len());
    let mut spelled_entries = Vec::new();
    let mut spelled_offsets = Vec::new();
    let mut spellings = Vec::new();
    let mut max_depth = 0usize;
    for (new, &old) in order.iter().enumerate() {
        if nodes[old].entries.is_empty() {
            continue;
        }
        terminal_bits[new / 64] |= 1 << (new % 64);
        entry_first.push(entries.len() as u32);
        let mut own = nodes[old].entries.clone();
        own.sort_by(|x, y| {
            let (wx, wy) = (&list.words[*x], &list.words[*y]);
            wy.frequency
                .cmp(&wx.frequency)
                .then_with(|| wx.word.cmp(&wy.word))
        });
        for i in own {
            let word = &list.words[i];
            let key: String = keys[i].iter().collect();
            max_depth = max_depth.max(keys[i].len());
            let spelling = nfc(&word.word);
            let mut flags = word.flags;
            if spelling != key {
                flags |= FLAG_SPELLED;
                spelled_entries.push(entries.len() as u32);
                spelled_offsets.push(spellings.len() as u32);
                push_string(&mut spellings, &spelling)?;
            }
            entries.push(Entry {
                frequency: word.frequency,
                flags,
            });
        }
    }
    entry_first.push(entries.len() as u32);
    let t = entry_first.len() - 1;
    let mut terminal_rank = Vec::with_capacity(words);
    let mut seen = 0u32;
    for bits in &terminal_bits {
        terminal_rank.push(seen);
        seen += bits.count_ones();
    }
    let mut max_freq = vec![0u8; n];
    let mut terminal = t;
    for i in (0..n).rev() {
        let own = if terminal_bits[i / 64] >> (i % 64) & 1 == 1 {
            terminal -= 1;
            entries[entry_first[terminal] as usize..entry_first[terminal + 1] as usize]
                .iter()
                .map(|e| e.frequency)
                .max()
                .unwrap_or(0)
        } else {
            0
        };
        let below = (first_child[i]..first_child[i + 1])
            .map(|c| max_freq[c as usize])
            .max()
            .unwrap_or(0);
        max_freq[i] = own.max(below);
    }

    // Pairs: sources are nodes (the root for a sentence start), next words
    // are stored spellings; both must be in the word list, and a next word
    // must be one that may be suggested (not hidden, not offensive).
    let spelled_words: HashMap<&str, usize> = list
        .words
        .iter()
        .enumerate()
        .map(|(i, w)| (w.word.as_str(), i))
        .collect();
    let totals: HashMap<&str, u32> = list
        .pair_totals
        .iter()
        .map(|(p, t)| (p.as_str(), *t))
        .collect();
    let mut by_source: BTreeMap<u32, Vec<(u8, String)>> = BTreeMap::new();
    for pair in &list.pairs {
        let source = if pair.previous == SENTENCE_START {
            Some(0u32)
        } else {
            spelled_words.get(pair.previous.as_str()).map(|i| {
                let mut node = 0usize;
                for &l in &label_keys[*i] {
                    node = nodes[node].children[&l];
                }
                new_index[node]
            })
        };
        let suggestable = spelled_words.get(pair.next.as_str()).is_some_and(|i| {
            let w = &list.words[*i];
            w.frequency > 0 && w.flags & FLAG_OFFENSIVE == 0
        });
        let (Some(source), true, Some(total)) =
            (source, suggestable, totals.get(pair.previous.as_str()))
        else {
            continue;
        };
        let p = f64::from(pair.count) / f64::from((*total).max(1));
        let cost = (-p.ln() * f64::from(PAIR_COST_SCALE))
            .round()
            .clamp(0.0, 255.0) as u8;
        by_source
            .entry(source)
            .or_default()
            .push((cost, pair.next.clone()));
    }
    let mut pair_sources = Vec::new();
    let mut pair_first = vec![0u32];
    let mut pair_words = Vec::new();
    let mut pair_costs = Vec::new();
    let mut pair_word_index: BTreeMap<String, u32> = BTreeMap::new();
    for (source, mut list) in by_source {
        list.sort();
        list.dedup_by(|x, y| x.1 == y.1);
        list.truncate(MAX_PAIRS_PER_SOURCE);
        pair_sources.push(source);
        for (cost, word) in list {
            let next_index = pair_word_index.len() as u32;
            pair_words.push(*pair_word_index.entry(word).or_insert(next_index));
            pair_costs.push(cost);
        }
        pair_first.push(pair_words.len() as u32);
    }
    let mut ordered: Vec<(&String, &u32)> = pair_word_index.iter().collect();
    ordered.sort_by_key(|(_, i)| **i);
    let mut pair_word_bytes = Vec::new();
    for (word, _) in ordered {
        push_string(&mut pair_word_bytes, word)?;
    }

    let mut corrections = Vec::new();
    for (typed, replacement) in &list.corrections {
        push_string(&mut corrections, typed)?;
        push_string(&mut corrections, replacement)?;
    }
    let mut metadata = Vec::new();
    for (k, v) in &list.metadata {
        push_string(&mut metadata, k)?;
        push_string(&mut metadata, v)?;
    }
    if [&spellings, &corrections, &metadata, &pair_word_bytes]
        .iter()
        .any(|b| b.len() > MAX_BLOB)
    {
        return Err(BuildError("a section is too large".into()));
    }

    let logp = match &options.log_probabilities {
        Some(table) => {
            if table.len() != 256
                || table.iter().any(|p| !p.is_finite() || *p > 0.0)
                || table[1..].windows(2).any(|w| w[0] > w[1])
            {
                return Err(BuildError("bad frequency table".into()));
            }
            table.clone()
        }
        None => frequency_table(list),
    };
    let mut unique_keys = label_keys.clone();
    unique_keys.sort_unstable();
    unique_keys.dedup();
    let oov = new_word_table(&unique_keys, a);

    let mut out = Vec::new();
    out.extend_from_slice(MAGIC);
    for v in [
        VERSION as usize,
        n,
        a,
        t,
        entries.len(),
        spelled_entries.len(),
        spellings.len(),
        corrections.len(),
        metadata.len(),
        max_depth,
        oov.len(),
        pair_sources.len(),
        pair_words.len(),
        pair_word_bytes.len(),
    ] {
        out.extend_from_slice(&(v as u32).to_le_bytes());
    }
    debug_assert_eq!(out.len(), HEADER_LEN);
    for c in &alphabet {
        out.extend_from_slice(&(*c as u32).to_le_bytes());
    }
    for p in logp.iter().chain(&oov) {
        out.extend_from_slice(&p.to_le_bytes());
    }
    for v in &first_child {
        out.extend_from_slice(&v.to_le_bytes());
    }
    for &old in &order {
        let l = label_of_node[old];
        if a <= SMALL_ALPHABET {
            out.push(l as u8);
        } else {
            out.extend_from_slice(&l.to_le_bytes());
        }
    }
    out.extend_from_slice(&max_freq);
    for bits in &terminal_bits {
        out.extend_from_slice(&bits.to_le_bytes());
    }
    for v in terminal_rank.iter().chain(&entry_first) {
        out.extend_from_slice(&v.to_le_bytes());
    }
    for e in &entries {
        out.push(e.frequency);
        out.push(e.flags);
    }
    for v in spelled_entries.iter().chain(&spelled_offsets) {
        out.extend_from_slice(&v.to_le_bytes());
    }
    out.extend_from_slice(&spellings);
    for v in pair_sources.iter().chain(&pair_first).chain(&pair_words) {
        out.extend_from_slice(&v.to_le_bytes());
    }
    out.extend_from_slice(&pair_costs);
    out.extend_from_slice(&pair_word_bytes);
    out.extend_from_slice(&corrections);
    out.extend_from_slice(&metadata);
    Ok(out)
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use crate::wordlist::WordList;

    pub(crate) const WORDS: &str = "the\t222\nthey\t180\nthen\t170\nten\t140\nhello\t120\nhelp\t150\n\
        held\t130\nhell\t100\nhe'll\t94\ncafé\t103\ncafe\t104\nMonday\t111\nI\t196\nI'm\t170\n\
        don't\t175\nwere\t186\nwe're\t106\ndamn\t100\toffensive\nUS\t0\nus\t160\nwork\t170\n";
    pub(crate) const CORRECTIONS: &str = "aint\tain't\nalot\ta lot\n";
    pub(crate) const PAIRS: &str = "<s>\tI\t6\n<s>\tthe\t3\n<s>\t*\t10\nI\tthen\t2\nI\twork\t5\nI\t*\t9\n\
        hello\tthey\t1\nhello\t*\t4\nwork\tUS\t1\nwork\t*\t2\nnobody\tthe\t1\nnobody\t*\t1\n";

    pub(crate) fn small_list() -> WordList {
        WordList::parse(WORDS, CORRECTIONS, "locale\ten-US\n")
            .unwrap()
            .with_pairs(PAIRS)
            .unwrap()
    }

    pub(crate) fn small() -> Vec<u8> {
        build(&small_list()).unwrap()
    }

    fn spellings(d: &Dictionary, key: &str) -> Vec<String> {
        let key = key_of(key);
        let node = d.find(&key).unwrap();
        let mut out: Vec<String> = d.entries(node).map(|id| d.spelling(id, &key)).collect();
        out.sort();
        out
    }

    #[test]
    fn round_trip() {
        let d = Dictionary::from_bytes(&small()).unwrap();
        assert_eq!(d.word_count(), 21);
        assert_eq!(d.locale(), "en-US");
        assert_eq!(spellings(&d, "cafe"), ["cafe", "café"]);
        assert_eq!(spellings(&d, "dont"), ["don't"]);
        assert_eq!(spellings(&d, "were"), ["we're", "were"]);
        assert!(d.find(&key_of("xyz")).is_none());
        assert!(d.entries(d.find(&key_of("th")).unwrap()).is_empty());
        assert_eq!(d.correction("alot"), Some("a lot"));
        assert_eq!(d.max_frequency(0), 222);
    }

    #[test]
    fn pairs_come_back_most_likely_first() {
        let d = Dictionary::from_bytes(&small()).unwrap();
        let start: Vec<(&str, f32)> = d.followers(0).collect();
        assert_eq!(
            start.iter().map(|(w, _)| *w).collect::<Vec<_>>(),
            ["I", "the"]
        );
        assert!((start[0].1 - (0.6f32).ln()).abs() < 0.07, "{start:?}");
        let i = d.find(&key_of("i")).unwrap();
        assert_eq!(
            d.followers(i).map(|(w, _)| w).collect::<Vec<_>>(),
            ["work", "then"]
        );
        // Pairs whose previous word is not in the list, or whose next word is
        // never suggested ("US" has frequency 0), are dropped.
        assert_eq!(d.followers(d.find(&key_of("work")).unwrap()).count(), 0);
        assert_eq!(d.followers(d.find(&key_of("ten")).unwrap()).count(), 0);
    }

    #[test]
    fn top_words() {
        let d = Dictionary::from_bytes(&small()).unwrap();
        let top: Vec<String> = d.top_words(3).into_iter().map(|(w, _)| w).collect();
        assert_eq!(top, ["the", "I", "were"]);
    }

    #[test]
    fn frequency_table_is_monotonic_and_zipf_like() {
        let d = Dictionary::from_bytes(&small()).unwrap();
        let the = d.log_probability(222);
        let hello = d.log_probability(120);
        assert!(the > hello && hello > d.log_probability(94));
        // The most frequent word has rank 1: about 1 / H(20).
        assert!((the - (1.0f32 / 3.6).ln()).abs() < 0.15, "{the}");
    }

    #[test]
    fn a_fixed_frequency_table_can_be_given() {
        let table: Vec<f32> = (0..256).map(|f| -10.0 + f as f32 / 100.0).collect();
        let options = BuildOptions {
            log_probabilities: Some(table.clone()),
        };
        let d = Dictionary::from_bytes(&build_with(&small_list(), &options).unwrap()).unwrap();
        assert_eq!(d.log_probability(100), table[100]);
        let bad = BuildOptions {
            log_probabilities: Some(vec![0.5; 256]),
        };
        assert!(build_with(&small_list(), &bad).is_err());
    }

    #[test]
    fn wide_alphabets_use_two_byte_labels() {
        // 300 CJK characters, one word each.
        let words: String = (0..300u32)
            .map(|i| format!("{}\t{}\n", char::from_u32(0x4E00 + i).unwrap(), 1 + i % 200))
            .collect();
        let list = WordList::parse(&words, "", "locale\tzh\n").unwrap();
        let d = Dictionary::from_bytes(&build(&list).unwrap()).unwrap();
        assert_eq!(d.alphabet().len(), 300);
        assert!(d.find(&['\u{4E00}']).is_some());
        assert!(d.find(&['\u{4E00}', '\u{4E01}']).is_none());
    }

    #[test]
    fn new_words_that_look_like_words_are_likelier() {
        let d = Dictionary::from_bytes(&small()).unwrap();
        let plausible = d.new_word_log_probability(&key_of("thell"));
        let implausible = d.new_word_log_probability(&key_of("qzxwq"));
        assert!(plausible > implausible, "{plausible} {implausible}");
    }

    #[test]
    fn rejects_bad_headers() {
        let good = small();
        let mut b = good.clone();
        b[0] = b'X';
        assert!(Dictionary::from_bytes(&b).is_err());
        let mut b = good.clone();
        b[8] = 1;
        assert!(Dictionary::from_bytes(&b).is_err());
        assert!(Dictionary::from_bytes(&good[..good.len() - 1]).is_err());
        let mut b = good.clone();
        b.push(0);
        assert!(Dictionary::from_bytes(&b).is_err());
        assert!(Dictionary::from_bytes(&[]).is_err());
        assert!(Dictionary::from_bytes(&good[..HEADER_LEN]).is_err());
    }

    /// Small deterministic generator for the mutation tests.
    pub(crate) struct XorShift(pub u64);
    impl XorShift {
        pub(crate) fn next(&mut self) -> u64 {
            self.0 ^= self.0 << 13;
            self.0 ^= self.0 >> 7;
            self.0 ^= self.0 << 17;
            self.0
        }
    }

    #[test]
    fn mutated_files_never_panic() {
        // A parser of files users open must survive any input. Flip, overwrite
        // and truncate bytes: every result must be an error or a dictionary
        // whose walks stay in bounds.
        let good = small();
        let mut rng = XorShift(0x9E37_79B9_7F4A_7C15);
        let mut accepted = 0;
        for round in 0..5000 {
            let mut b = good.clone();
            for _ in 0..1 + rng.next() % 4 {
                let i = (rng.next() as usize) % b.len();
                b[i] = match rng.next() % 3 {
                    0 => b[i] ^ (1 << (rng.next() % 8)),
                    1 => rng.next() as u8,
                    _ => 0xFF,
                };
            }
            if round % 10 == 0 {
                b.truncate((rng.next() as usize) % b.len());
            }
            if let Ok(d) = Dictionary::from_bytes(&b) {
                accepted += 1;
                walk_everything(&d);
            }
        }
        // Some mutations only touch values (frequencies, costs, metadata), so
        // a few files still load: the walk above must have run on them.
        assert!(accepted > 0);
    }

    pub(crate) fn walk_everything(d: &Dictionary) {
        let mut stack = vec![(0u32, Vec::new())];
        while let Some((node, key)) = stack.pop() {
            for id in d.entries(node) {
                let _ = d.spelling(id, &key);
                let _ = d.log_probability(d.entry(id).frequency);
            }
            let _ = d.new_word_log_probability(&key);
            let _ = d.followers(node).count();
            for c in d.children(node) {
                let mut k = key.clone();
                k.push(d.alphabet()[d.label(c) as usize]);
                stack.push((c, k));
            }
        }
        let _ = d.top_words(5);
    }
}
