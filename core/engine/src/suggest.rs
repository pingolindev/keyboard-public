// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: Apache-2.0 OR MIT

//! The decoder: from the taps of the word being typed to ranked candidates
//! and an autocorrect decision, and from the previous word to predictions.
//!
//! A noisy-channel model. A candidate word w scores `log P(w) - cost`: how
//! common the word is minus how unlikely these taps are if the user meant w.
//! The cost adds up edits, in nats: a letter typed on a nearby key (the touch
//! model), a letter left out, an extra letter, two letters swapped.
//! Completions cost a little for each letter not typed yet, and a word that
//! often follows the previous word gets a bonus.
//!
//! The search walks each dictionary's trie depth first, one row of edit costs
//! per level, and skips a subtree when its best possible score cannot make the
//! lists.
//!
//! Autocorrect replaces the typed word only if no dictionary has it and the
//! best correction is likelier than the typed word being a word the
//! dictionaries do not know (a name, slang). The first dictionary's letter
//! pairs say how likely that is, so a typed word that looks like a word is kept
//! and a jumble of keys is corrected.

use std::collections::HashMap;

use crate::dictionary::Dictionary;
use crate::text::{self, Case, MAX_KEY_LEN, key_of};
use crate::touch::{Geometry, Point};
use crate::wordlist::FLAG_OFFENSIVE;

/// Costs and thresholds, in nats (natural log units).
#[derive(Debug, Clone, PartialEq)]
pub struct Params {
    /// Glide typing (see `glide.rs`).
    pub glide: crate::glide::GlideParams,
    /// A letter of the word was not typed.
    pub omit: f32,
    /// ... and it was the second of a double letter ("helo" for "hello").
    pub omit_double: f32,
    /// A typed letter is not in the word.
    pub insert: f32,
    /// ... and it repeats the letter before it ("helllo").
    pub insert_repeat: f32,
    /// Two neighboring letters typed in the wrong order.
    pub transpose: f32,
    /// The most a substituted letter can cost.
    pub sub_far: f32,
    /// A neighboring key, when the tap has no position.
    pub sub_near: f32,
    /// Spread of taps around the key center, in key sizes.
    pub spatial_sigma: f32,
    /// Each letter of a completion not typed yet (ranking only).
    pub completion_char: f32,
    /// The word differs from what was typed only in accents, apostrophes or
    /// hyphens.
    pub accent: f32,
    /// Candidates costing more are not searched for. Higher finds nothing
    /// the strip would show (examples/replay.rs) and searches far longer.
    pub max_cost: f32,
    /// Added to the letter-pair log-probability of the typed word to get the
    /// log-probability that it is meant as typed although the dictionary
    /// lacks it. Tuned with `examples/replay.rs` (held-out names against
    /// noisy typos).
    pub new_word_prior: f32,
    /// Weight of the letter-pair log-probability. Below 1 it flattens the
    /// length penalty: short typed strings are seldom new words.
    pub new_word_scale: f32,
    /// Added to that when the user capitalized a word in mid-sentence: likely
    /// a name.
    pub name_bonus: f32,
    /// Autocorrect never makes a correction costing more.
    pub autocorrect_max_cost: f32,
    /// How much likelier than a new word the correction must be.
    pub autocorrect_margin: f32,
    /// Weight of ln P(word | previous) - ln P(word) for a word that follows
    /// the previous word.
    pub context_weight: f32,
    /// The most that context can add to a score.
    pub context_cap: f32,
    /// Added to the user's own next words in predictions.
    pub user_followers_bonus: f32,
}

impl Default for Params {
    fn default() -> Self {
        Params {
            omit: 4.0,
            omit_double: 0.7,
            insert: 4.0,
            insert_repeat: 1.5,
            transpose: 3.5,
            sub_far: 8.0,
            sub_near: 2.5,
            spatial_sigma: 0.4,
            completion_char: 0.4,
            accent: 0.5,
            max_cost: 6.0,
            new_word_prior: -2.5,
            new_word_scale: 0.65,
            name_bonus: 8.0,
            autocorrect_max_cost: 5.5,
            autocorrect_margin: 0.0,
            context_weight: 0.5,
            context_cap: 4.0,
            user_followers_bonus: 1.0,
            glide: crate::glide::GlideParams::default(),
        }
    }
}

/// Longest typed word the decoder looks for, in key letters.
pub const MAX_TYPED: usize = 32;
const CORRECTIONS_KEPT: usize = 8;
const COMPLETIONS_KEPT: usize = 8;
/// Predictions below this many fall back to the most frequent words.
const TOP_WORDS_PENALTY: f32 = 3.0;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kind {
    /// The taps spell this word, possibly with mistakes.
    Correction,
    /// The taps start this word.
    Completion,
    /// A fixed correction from the dictionary ("alot" to "a lot").
    Shortcut,
    /// A word that may come next.
    Prediction,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Scored {
    pub text: String,
    pub kind: Kind,
    pub score: f32,
    /// Edit cost of the taps for this word.
    pub cost: f32,
}

/// One key press: what it typed, and where (in the unit of the geometry).
#[derive(Debug, Clone, PartialEq)]
pub struct Press {
    pub text: String,
    pub position: Option<Point>,
}

/// A dictionary to search, with the key geometry mapped onto its alphabet.
#[derive(Debug, Clone, Copy)]
pub struct Source<'a> {
    pub dictionary: &'a Dictionary,
    pub geometry: &'a Geometry,
    /// Added to the score of every word from here (a language other than the
    /// current one gets less).
    pub bonus: f32,
}

/// What comes before the word.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct Context {
    /// The word before, if known.
    pub previous: Option<String>,
    /// The word starts a sentence (its capital came from automatic caps).
    pub sentence_start: bool,
    /// The user's own words after `previous` (or after a sentence start),
    /// with ln P(word | previous).
    pub user_followers: Vec<(String, f32)>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Request<'a> {
    pub presses: &'a [Press],
    pub context: &'a Context,
    pub autocorrect: bool,
    pub block_offensive: bool,
    pub max_results: usize,
}

#[derive(Debug, Clone, PartialEq, Default)]
pub struct Outcome {
    pub typed: String,
    pub typed_is_word: bool,
    /// What a space should commit instead of the typed word. When set, it is
    /// also the first candidate.
    pub autocorrect: Option<String>,
    /// Best first, without the typed word itself.
    pub candidates: Vec<Scored>,
    /// Trie nodes the search visited, over all dictionaries.
    pub visited: usize,
}

struct Found {
    entry: u32,
    score: f32,
    cost: f32,
    key: Vec<char>,
}

struct TopK {
    k: usize,
    items: Vec<Found>,
}

impl TopK {
    fn new(k: usize) -> Self {
        TopK {
            k,
            items: Vec::with_capacity(k + 1),
        }
    }

    fn threshold(&self) -> f32 {
        if self.items.len() < self.k {
            f32::NEG_INFINITY
        } else {
            self.items.last().map_or(f32::NEG_INFINITY, |f| f.score)
        }
    }

    fn would_take(&self, score: f32) -> bool {
        score > self.threshold()
    }

    fn offer(&mut self, found: Found) {
        if !self.would_take(found.score) {
            return;
        }
        let at = self.items.partition_point(|f| f.score >= found.score);
        self.items.insert(at, found);
        self.items.truncate(self.k);
    }
}

/// Edit-cost inputs for the typed key letters, for one dictionary.
struct Query {
    /// Alphabet index of each typed key letter (None: not in the alphabet).
    labels: Vec<Option<u16>>,
    /// Cost of word letter l at typed position j: `sub[j * a + l]`.
    sub: Vec<f32>,
    /// Cost of typed position j being an extra letter.
    ins: Vec<f32>,
    a: usize,
}

impl Query {
    fn new(
        key: &[(char, Option<Point>)],
        dict: &Dictionary,
        geometry: &Geometry,
        p: &Params,
    ) -> Self {
        let a = dict.alphabet().len();
        let labels: Vec<Option<u16>> = key.iter().map(|(c, _)| dict.label_of(*c)).collect();
        let mut sub = vec![p.sub_far; key.len() * a];
        if a > 0 {
            for (j, (c, position)) in key.iter().enumerate() {
                geometry.fill_costs(*c, labels[j], *position, p, &mut sub[j * a..(j + 1) * a]);
            }
        }
        let ins = (0..key.len())
            .map(|j| {
                if j > 0 && key[j].0 == key[j - 1].0 {
                    p.insert_repeat
                } else {
                    p.insert
                }
            })
            .collect();
        Query {
            labels,
            sub,
            ins,
            a,
        }
    }
}

struct Lists {
    corrections: TopK,
    completions: TopK,
    visited: usize,
}

/// Depth-first search of the trie for the best corrections and completions.
fn search(dict: &Dictionary, q: &Query, p: &Params, block_offensive: bool) -> Lists {
    let n = q.labels.len();
    let w = n + 1;
    let alphabet = dict.alphabet();
    let mut rows = vec![f32::INFINITY; (MAX_KEY_LEN + 1) * w];
    let mut comp = [f32::INFINITY; MAX_KEY_LEN + 1];
    let mut row_min = [f32::INFINITY; MAX_KEY_LEN + 1];
    let mut path = [0u16; MAX_KEY_LEN + 1];
    let mut chars = ['\0'; MAX_KEY_LEN + 1];
    rows[0] = 0.0;
    for j in 1..=n {
        rows[j] = rows[j - 1] + q.ins[j - 1];
    }
    row_min[0] = 0.0;

    let mut lists = Lists {
        corrections: TopK::new(CORRECTIONS_KEPT),
        completions: TopK::new(COMPLETIONS_KEPT),
        visited: 0,
    };
    let mut stack: Vec<(u32, usize)> = Vec::with_capacity(256);
    let mut children: Vec<(u8, u32)> = Vec::with_capacity(64);
    let mut push_children = |node: u32, depth: usize, stack: &mut Vec<(u32, usize)>| {
        if depth > MAX_KEY_LEN {
            return;
        }
        children.clear();
        children.extend(dict.children(node).map(|c| (dict.max_frequency(c), c)));
        // The most frequent subtree is searched first, so the lists fill with
        // good words early and prune the rest.
        children.sort_unstable_by_key(|(f, _)| *f);
        stack.extend(children.iter().map(|(_, c)| (*c, depth)));
    };
    push_children(0, 1, &mut stack);

    while let Some((node, d)) = stack.pop() {
        lists.visited += 1;
        let label = dict.label(node);
        path[d] = label;
        chars[d] = alphabet[label as usize];
        let omit = if d >= 2 && path[d - 1] == label {
            p.omit_double
        } else {
            p.omit
        };
        let (before, after) = rows.split_at_mut(d * w);
        let prev = &before[(d - 1) * w..d * w];
        let cur = &mut after[..w];
        cur[0] = prev[0] + omit;
        let mut min = cur[0];
        for j in 1..=n {
            let mut best = prev[j - 1] + q.sub[(j - 1) * q.a + label as usize];
            best = best.min(prev[j] + omit);
            best = best.min(cur[j - 1] + q.ins[j - 1]);
            if d >= 2
                && j >= 2
                && label != path[d - 1]
                && q.labels[j - 2] == Some(label)
                && q.labels[j - 1] == Some(path[d - 1])
            {
                best = best.min(before[(d - 2) * w + j - 2] + p.transpose);
            }
            cur[j] = best;
            min = min.min(best);
        }
        row_min[d] = min;
        comp[d] = if n > 0 {
            comp[d - 1].min(prev[n]) + p.completion_char
        } else {
            f32::INFINITY
        };
        // Every cost below this node comes from this row or the row above
        // (corrections), or also from the completion state (completions),
        // and costs only grow. Each list is pruned on its own bound: a
        // subtree is skipped once neither list could take anything from it.
        let corrections_lower = min.min(row_min[d - 1]);
        let completions_lower = corrections_lower.min(comp[d]);
        if completions_lower > p.max_cost {
            continue;
        }
        let best = dict.log_probability(dict.max_frequency(node));
        let corrections_alive = corrections_lower <= p.max_cost
            && lists.corrections.would_take(best - corrections_lower);
        if !corrections_alive && !lists.completions.would_take(best - completions_lower) {
            continue;
        }
        let corrected = cur[n];
        for id in dict.entries(node) {
            let e = dict.entry(id);
            if e.frequency == 0 || (block_offensive && e.word_flags() & FLAG_OFFENSIVE != 0) {
                continue;
            }
            let logp = dict.log_probability(e.frequency);
            if corrected <= p.max_cost && lists.corrections.would_take(logp - corrected) {
                lists.corrections.offer(Found {
                    entry: id,
                    score: logp - corrected,
                    cost: corrected,
                    key: chars[1..=d].to_vec(),
                });
            }
            if comp[d] < corrected
                && comp[d] <= p.max_cost
                && lists.completions.would_take(logp - comp[d])
            {
                lists.completions.offer(Found {
                    entry: id,
                    score: logp - comp[d],
                    cost: comp[d],
                    key: chars[1..=d].to_vec(),
                });
            }
        }
        push_children(node, d + 1, &mut stack);
    }
    lists
}

/// ln P(word | context) from a dictionary's pairs, if the word follows.
fn pair_log_probability(dict: &Dictionary, context: &Context, word_lower: &str) -> Option<f32> {
    let node = match &context.previous {
        Some(previous) => dict.find(&key_of(previous))?,
        None if context.sentence_start => 0,
        None => return None,
    };
    dict.followers(node)
        .find(|(w, _)| w.to_lowercase() == word_lower)
        .map(|(_, logp)| logp)
}

/// The bonus for a word that tends to follow the previous one: the best of
/// the dictionaries' pairs and the user's own, against the word's own
/// probability.
pub(crate) fn context_bonus(
    sources: &[Source<'_>],
    context: &Context,
    p: &Params,
    word: &str,
    logp: f32,
) -> f32 {
    if context.previous.is_none() && !context.sentence_start {
        return 0.0;
    }
    let lower = word.to_lowercase();
    let user = context
        .user_followers
        .iter()
        .filter(|(w, _)| w.to_lowercase() == lower)
        .map(|(_, lp)| *lp);
    let best = sources
        .iter()
        .filter_map(|s| pair_log_probability(s.dictionary, context, &lower))
        .chain(user)
        .fold(f32::NEG_INFINITY, f32::max);
    if best.is_finite() {
        p.context_weight * (best - logp).clamp(0.0, p.context_cap)
    } else {
        0.0
    }
}

/// Runs the decoder for the taps of one word. The first source is the main
/// language. `rejected` says whether the user undid an autocorrection of this
/// typed word (in lower case) before.
pub fn decode(
    sources: &[Source<'_>],
    p: &Params,
    request: &Request<'_>,
    rejected: impl Fn(&str) -> bool,
) -> Outcome {
    let typed = text::nfc(
        &request
            .presses
            .iter()
            .map(|t| t.text.as_str())
            .collect::<String>(),
    );
    let mut key_presses: Vec<(char, Option<Point>)> = Vec::new();
    for press in request.presses {
        for c in press.text.chars() {
            let mut folded = Vec::new();
            text::push_key_chars(c, &mut folded);
            key_presses.extend(folded.into_iter().map(|k| (k, press.position)));
        }
    }
    let key: Vec<char> = key_presses.iter().map(|(c, _)| *c).collect();
    let mut outcome = Outcome {
        typed: typed.clone(),
        ..Outcome::default()
    };
    let Some(primary) = sources.first() else {
        return outcome;
    };
    if key.is_empty() || key.len() > MAX_TYPED {
        return outcome;
    }

    let turkic = text::is_turkic(primary.dictionary.locale());
    let case = text::case_of(&typed);
    let typed_lower = typed.to_lowercase();
    let context = request.context;
    outcome.typed_is_word = sources.iter().any(|s| {
        s.dictionary.find(&key).is_some_and(|node| {
            s.dictionary
                .entries(node)
                .any(|id| text::typed_matches(&typed, &s.dictionary.spelling(id, &key), turkic))
        })
    });

    let mut corrections: Vec<Scored> = Vec::new();
    let mut completions: Vec<Scored> = Vec::new();
    // Autocorrect only words typed in the main language's letters.
    let all_typed_known = key
        .iter()
        .all(|c| primary.dictionary.label_of(*c).is_some());
    for source in sources {
        let dict = source.dictionary;
        let q = Query::new(&key_presses, dict, source.geometry, p);
        let lists = search(dict, &q, p, request.block_offensive);
        outcome.visited += lists.visited;
        let score = |found: &Found, kind: Kind| {
            let spelling = dict.spelling(found.entry, &found.key);
            let logp = found.score + found.cost;
            let mut s =
                found.score + source.bonus + context_bonus(sources, context, p, &spelling, logp);
            if found.key == key && spelling.to_lowercase() != typed_lower {
                s -= p.accent;
            }
            Scored {
                text: text::apply_case(&spelling, case, turkic),
                kind,
                score: s,
                cost: found.cost,
            }
        };
        corrections.extend(
            lists
                .corrections
                .items
                .iter()
                .map(|f| score(f, Kind::Correction)),
        );
        completions.extend(
            lists
                .completions
                .items
                .iter()
                .map(|f| score(f, Kind::Completion)),
        );
    }
    corrections.sort_by(|x, y| y.score.total_cmp(&x.score));

    let shortcut = sources
        .iter()
        .find_map(|s| s.dictionary.correction(&typed_lower))
        .map(|r| text::apply_case(r, case, turkic))
        .filter(|r| *r != typed);
    outcome.autocorrect = if !request.autocorrect || rejected(&typed_lower) {
        None
    } else if shortcut.is_some() {
        shortcut.clone()
    } else if outcome.typed_is_word || !all_typed_known {
        None
    } else {
        corrections
            .iter()
            .find(|c| c.text != typed)
            .filter(|best| best.cost <= p.autocorrect_max_cost)
            .filter(|best| key.len() > 1 || best.cost == 0.0)
            .filter(|best| {
                let mut new_word = p.new_word_prior
                    + p.new_word_scale * primary.dictionary.new_word_log_probability(&key);
                if case == Case::Capitalized && !context.sentence_start {
                    new_word += p.name_bonus;
                }
                best.score - new_word >= p.autocorrect_margin
            })
            .map(|best| best.text.clone())
    };

    let mut all: Vec<Scored> = corrections.into_iter().chain(completions).collect();
    all.sort_by(|x, y| y.score.total_cmp(&x.score));
    if let Some(s) = &shortcut {
        all.insert(
            0,
            Scored {
                text: s.clone(),
                kind: Kind::Shortcut,
                score: f32::MAX,
                cost: 0.0,
            },
        );
    }
    let mut seen = std::collections::HashSet::new();
    all.retain(|c| c.text != typed && seen.insert(c.text.clone()));
    if let Some(target) = &outcome.autocorrect
        && let Some(i) = all.iter().position(|c| &c.text == target)
    {
        let c = all.remove(i);
        all.insert(0, c);
    }
    all.truncate(request.max_results);
    outcome.candidates = all;
    outcome
}

/// Words that may come next: the dictionaries' pairs and the user's own after
/// the previous word, else the most frequent words (`top_words`, from the
/// main language). Capitalized at the start of a sentence.
pub fn predict(
    sources: &[Source<'_>],
    p: &Params,
    context: &Context,
    max_results: usize,
    top_words: &[(String, f32)],
) -> Vec<Scored> {
    let Some(primary) = sources.first() else {
        return Vec::new();
    };
    let turkic = text::is_turkic(primary.dictionary.locale());
    let mut scores: HashMap<String, f32> = HashMap::new();
    fn add(scores: &mut HashMap<String, f32>, word: &str, score: f32) {
        let slot = scores.entry(word.to_owned()).or_insert(f32::NEG_INFINITY);
        *slot = slot.max(score);
    }
    for source in sources {
        let node = match &context.previous {
            Some(previous) => source.dictionary.find(&key_of(previous)),
            None if context.sentence_start => Some(0),
            None => None,
        };
        if let Some(node) = node {
            for (word, logp) in source.dictionary.followers(node) {
                add(&mut scores, word, logp + source.bonus);
            }
        }
    }
    for (word, logp) in &context.user_followers {
        add(&mut scores, word, logp + p.user_followers_bonus);
    }
    if scores.len() < max_results && (context.previous.is_none() || context.sentence_start) {
        for (word, logp) in top_words {
            add(&mut scores, word, logp - TOP_WORDS_PENALTY);
        }
    }
    let case = if context.sentence_start {
        Case::Capitalized
    } else {
        Case::Lower
    };
    let mut out: Vec<Scored> = scores
        .into_iter()
        .map(|(word, score)| Scored {
            text: text::apply_case(&word, case, turkic),
            kind: Kind::Prediction,
            score,
            cost: 0.0,
        })
        .collect();
    out.sort_by(|x, y| {
        y.score
            .total_cmp(&x.score)
            .then_with(|| x.text.cmp(&y.text))
    });
    let mut seen = std::collections::HashSet::new();
    out.retain(|c| seen.insert(c.text.clone()));
    out.truncate(max_results);
    out
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use crate::dictionary::build;
    use crate::dictionary::tests::small;
    use crate::touch::KeyCenter;
    use crate::wordlist::WordList;

    /// QWERTY letter keys as the app places them: 10 units wide, rows of
    /// 1.3 units, shorter rows centered (the third row sits after Shift).
    pub(crate) fn qwerty() -> Vec<KeyCenter> {
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

    /// Taps at the centers of the keys of `word`.
    pub(crate) fn taps(word: &str) -> Vec<Press> {
        let keys = qwerty();
        word.chars()
            .map(|c| {
                let lower = c.to_lowercase().next().unwrap();
                let position = keys
                    .iter()
                    .find(|k| k.text.starts_with(lower))
                    .map(|k| Point { x: k.x, y: k.y });
                Press {
                    text: c.to_string(),
                    position,
                }
            })
            .collect()
    }

    /// The test dictionary has 21 words, so its letter pairs make almost any
    /// typed word look like a word. Its tests use a lower new-word prior;
    /// tests/english.rs checks the defaults on the real dictionary.
    pub(crate) fn small_params() -> Params {
        Params {
            new_word_prior: -3.0,
            new_word_scale: 1.0,
            autocorrect_max_cost: 8.0,
            ..Params::default()
        }
    }

    struct Setup {
        dicts: Vec<Dictionary>,
        geometries: Vec<Geometry>,
    }

    impl Setup {
        fn new(lists: &[Vec<u8>]) -> Self {
            let dicts: Vec<Dictionary> = lists
                .iter()
                .map(|b| Dictionary::from_bytes(b).unwrap())
                .collect();
            let geometries = dicts.iter().map(|d| Geometry::new(&qwerty(), d)).collect();
            Setup { dicts, geometries }
        }

        fn sources(&self, bonuses: &[f32]) -> Vec<Source<'_>> {
            self.dicts
                .iter()
                .zip(&self.geometries)
                .zip(bonuses.iter().chain(std::iter::repeat(&0.0)))
                .map(|((dictionary, geometry), bonus)| Source {
                    dictionary,
                    geometry,
                    bonus: *bonus,
                })
                .collect()
        }
    }

    fn run(word: &str) -> Outcome {
        run_with(word, &small_params(), true, false, |_| false)
    }

    fn run_with(
        word: &str,
        params: &Params,
        autocorrect: bool,
        sentence_start: bool,
        rejected: impl Fn(&str) -> bool,
    ) -> Outcome {
        let setup = Setup::new(&[small()]);
        let presses = taps(word);
        let context = Context {
            sentence_start,
            ..Context::default()
        };
        let request = Request {
            presses: &presses,
            context: &context,
            autocorrect,
            block_offensive: true,
            max_results: 5,
        };
        decode(&setup.sources(&[0.0]), params, &request, rejected)
    }

    fn texts(o: &Outcome) -> Vec<&str> {
        o.candidates.iter().map(|c| c.text.as_str()).collect()
    }

    #[test]
    fn a_swapped_pair_is_corrected() {
        let o = run("teh");
        assert_eq!(o.autocorrect.as_deref(), Some("the"));
        assert_eq!(texts(&o)[0], "the");
        assert!(!o.typed_is_word);
        assert!(o.visited > 0);
    }

    #[test]
    fn a_dictionary_word_is_never_autocorrected() {
        let o = run("ten");
        assert!(o.typed_is_word);
        assert_eq!(o.autocorrect, None);
        assert!(
            !texts(&o).contains(&"ten"),
            "the typed word is not a candidate"
        );
    }

    #[test]
    fn a_missing_double_letter_is_corrected() {
        assert_eq!(run("helo").autocorrect.as_deref(), Some("hello"));
    }

    #[test]
    fn a_neighboring_key_is_corrected() {
        // r is next to e.
        assert_eq!(run("thr").autocorrect.as_deref(), Some("the"));
    }

    #[test]
    fn apostrophes_and_capitals_come_back() {
        assert_eq!(run("dont").autocorrect.as_deref(), Some("don't"));
        assert_eq!(run("im").autocorrect.as_deref(), Some("I'm"));
        assert_eq!(run("i").autocorrect.as_deref(), Some("I"));
        assert_eq!(run("monday").autocorrect.as_deref(), Some("Monday"));
    }

    #[test]
    fn the_typed_case_carries_over() {
        let o = run_with("Teh", &small_params(), true, true, |_| false);
        assert_eq!(o.autocorrect.as_deref(), Some("The"));
        assert_eq!(run("TEH").autocorrect.as_deref(), Some("THE"));
        let at_start = run_with("Dont", &small_params(), true, true, |_| false);
        assert_eq!(at_start.autocorrect.as_deref(), Some("Don't"));
    }

    #[test]
    fn completions_are_offered_but_never_autocorrected() {
        // A valid prefix: completions, no correction.
        let o = run("the");
        let t = texts(&o);
        assert!(t.contains(&"they") && t.contains(&"then"), "{t:?}");
        assert!(o.candidates.iter().any(|c| c.kind == Kind::Completion));
        assert_eq!(o.autocorrect, None);
        // With letters left out made too costly to be typos, "hel" can only
        // be the start of a word: suggested, never autocorrected.
        let strict = Params {
            omit: 9.0,
            omit_double: 9.0,
            ..small_params()
        };
        let o = run_with("hel", &strict, true, false, |_| false);
        let t = texts(&o);
        assert!(t.contains(&"help") && t.contains(&"hello"), "{t:?}");
        assert_eq!(o.autocorrect, None);
    }

    #[test]
    fn a_dropped_double_letter_at_the_end_is_a_typo() {
        let o = run("hel");
        assert_eq!(o.autocorrect.as_deref(), Some("hell"));
        assert!(texts(&o).contains(&"help"));
    }

    #[test]
    fn shortcuts_replace_even_valid_looking_words() {
        let o = run("alot");
        assert_eq!(o.autocorrect.as_deref(), Some("a lot"));
        assert_eq!(o.candidates[0].kind, Kind::Shortcut);
        assert_eq!(run("Aint").autocorrect.as_deref(), Some("Ain't"));
    }

    #[test]
    fn a_rejected_correction_is_not_made_again() {
        let o = run_with("teh", &small_params(), true, false, |w| w == "teh");
        assert_eq!(o.autocorrect, None);
        assert_eq!(texts(&o)[0], "the", "still suggested");
        let o = run_with("alot", &small_params(), true, false, |w| w == "alot");
        assert_eq!(o.autocorrect, None);
    }

    #[test]
    fn autocorrect_off_still_suggests() {
        let o = run_with("teh", &small_params(), false, false, |_| false);
        assert_eq!(o.autocorrect, None);
        assert_eq!(texts(&o)[0], "the");
    }

    #[test]
    fn far_off_words_are_kept() {
        // Nothing in the dictionary is near these.
        assert_eq!(run("zxqv").autocorrect, None);
        assert_eq!(run("pingolin").autocorrect, None);
    }

    #[test]
    fn offensive_words_are_not_suggested_but_are_valid() {
        let o = run("dam");
        assert!(!texts(&o).contains(&"damn"));
        let o = run("damn");
        assert!(o.typed_is_word);
        assert_eq!(o.autocorrect, None);
    }

    #[test]
    fn hidden_words_are_valid_but_never_suggested() {
        let o = run("US");
        assert!(o.typed_is_word);
        assert!(!texts(&o).contains(&"US"));
    }

    #[test]
    fn accents_typed_or_not() {
        // Both spellings are words: whatever was typed is kept.
        assert!(run("cafe").typed_is_word);
        let o = run("café");
        assert!(o.typed_is_word);
        assert_eq!(o.autocorrect, None);
    }

    #[test]
    fn single_letters_are_only_fixed_for_case() {
        // "u" is one key from "i" and "y", but a single letter is left alone.
        assert_eq!(run("u").autocorrect, None);
    }

    #[test]
    fn a_capital_in_mid_sentence_protects_a_name() {
        // A capital typed in mid-sentence makes a new word (a name) likelier.
        let protective = Params {
            name_bonus: 100.0,
            ..small_params()
        };
        let mid = run_with("Wotk", &protective, true, false, |_| false);
        let start = run_with("Wotk", &protective, true, true, |_| false);
        assert_eq!(mid.autocorrect, None);
        assert_eq!(start.autocorrect.as_deref(), Some("Work"));
    }

    #[test]
    fn empty_and_overlong_input() {
        assert_eq!(run(""), Outcome::default());
        let long = "a".repeat(MAX_TYPED + 1);
        let o = run(&long);
        assert!(o.candidates.is_empty() && o.autocorrect.is_none());
    }

    #[test]
    fn no_positions_still_work() {
        let d = Dictionary::from_bytes(&small()).unwrap();
        let g = Geometry::default();
        let presses: Vec<Press> = "teh"
            .chars()
            .map(|c| Press {
                text: c.to_string(),
                position: None,
            })
            .collect();
        let context = Context::default();
        let request = Request {
            presses: &presses,
            context: &context,
            autocorrect: true,
            block_offensive: true,
            max_results: 5,
        };
        let sources = [Source {
            dictionary: &d,
            geometry: &g,
            bonus: 0.0,
        }];
        let o = decode(&sources, &small_params(), &request, |_| false);
        assert_eq!(o.autocorrect.as_deref(), Some("the"));
    }

    fn second_language() -> Vec<u8> {
        let list =
            WordList::parse("hallo\t200\nwerk\t180\nder\t220\n", "", "locale\tde\n").unwrap();
        build(&list).unwrap()
    }

    #[test]
    fn several_dictionaries_are_searched_at_once() {
        let setup = Setup::new(&[small(), second_language()]);
        let context = Context::default();
        let run = |word: &str, bonuses: &[f32]| {
            let presses = taps(word);
            let request = Request {
                presses: &presses,
                context: &context,
                autocorrect: true,
                block_offensive: true,
                max_results: 5,
            };
            decode(&setup.sources(bonuses), &small_params(), &request, |_| {
                false
            })
        };
        // A word of the second language is a word.
        let o = run("hallo", &[0.0, -1.0]);
        assert!(o.typed_is_word);
        assert_eq!(o.autocorrect, None);
        // And it is a correction: "hallp" is a typo of "hallo".
        assert_eq!(
            run("hallp", &[0.0, -1.0]).autocorrect.as_deref(),
            Some("hallo")
        );
        // A big enough handicap keeps the other language from correcting.
        assert_eq!(run("hallp", &[0.0, -30.0]).autocorrect, None);
    }

    #[test]
    fn the_previous_word_raises_words_that_follow_it() {
        let setup = Setup::new(&[small()]);
        let rank = |previous: Option<&str>, user: Vec<(String, f32)>| {
            let presses = taps("t");
            let context = Context {
                previous: previous.map(str::to_owned),
                sentence_start: false,
                user_followers: user,
            };
            let request = Request {
                presses: &presses,
                context: &context,
                autocorrect: false,
                block_offensive: true,
                max_results: 8,
            };
            let o = decode(&setup.sources(&[0.0]), &small_params(), &request, |_| false);
            o.candidates.iter().position(|c| c.text == "then").unwrap()
        };
        let alone = rank(None, Vec::new());
        let after_i = rank(Some("I"), Vec::new());
        assert!(after_i < alone, "{after_i} {alone}");
        // The user's own pairs count too.
        let after_ten = rank(Some("ten"), vec![("then".into(), -0.1)]);
        assert!(after_ten < alone, "{after_ten} {alone}");
    }

    #[test]
    fn predictions_follow_the_previous_word() {
        let setup = Setup::new(&[small()]);
        let d = &setup.dicts[0];
        let top = d.top_words(5);
        let predict_after =
            |previous: Option<&str>, sentence_start: bool, user: Vec<(String, f32)>| {
                let context = Context {
                    previous: previous.map(str::to_owned),
                    sentence_start,
                    user_followers: user,
                };
                predict(&setup.sources(&[0.0]), &small_params(), &context, 3, &top)
                    .into_iter()
                    .map(|c| c.text)
                    .collect::<Vec<_>>()
            };
        // A sentence start: the pairs from <s>, then the most frequent words,
        // capitalized.
        assert_eq!(predict_after(None, true, Vec::new()), ["I", "The", "Were"]);
        assert_eq!(
            predict_after(Some("I"), false, Vec::new()),
            ["work", "then"]
        );
        // The user's own next words come first.
        let mine = vec![("hello".to_string(), -0.2)];
        assert_eq!(predict_after(Some("I"), false, mine)[0], "hello");
        // A word nothing follows predicts nothing.
        assert!(predict_after(Some("ten"), false, Vec::new()).is_empty());
    }

    #[test]
    fn mutated_dictionaries_decode_without_panicking() {
        let good = small();
        let mut rng = crate::dictionary::tests::XorShift(42);
        let presses = taps("helo");
        let context = Context {
            previous: Some("I".into()),
            sentence_start: false,
            user_followers: Vec::new(),
        };
        let request = Request {
            presses: &presses,
            context: &context,
            autocorrect: true,
            block_offensive: true,
            max_results: 5,
        };
        for _ in 0..3000 {
            let mut b = good.clone();
            let i = (rng.next() as usize) % b.len();
            b[i] = rng.next() as u8;
            if let Ok(d) = Dictionary::from_bytes(&b) {
                let g = Geometry::new(&qwerty(), &d);
                let sources = [Source {
                    dictionary: &d,
                    geometry: &g,
                    bonus: 0.0,
                }];
                let _ = decode(&sources, &Params::default(), &request, |_| false);
                let _ = predict(&sources, &Params::default(), &context, 3, &[]);
            }
        }
    }
}
