// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: Apache-2.0 OR MIT

//! What apps call: load dictionaries, then ask a `Suggester` for the
//! suggestions of the word being typed and for the next word. A `UserModel`
//! holds what the user types. Exported through UniFFI.

use std::collections::{HashSet, VecDeque};
use std::sync::{Arc, Mutex, MutexGuard};

use crate::dictionary;
use crate::glide;
use crate::suggest::{self, Context, Params, Press, Request, Source};
use crate::text;
use crate::touch::{Geometry, KeyCenter, Point};
use crate::user::{self, UserWords};

fn lock<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
}

/// A dictionary, read from the bytes of a `.pkd` file.
#[derive(Debug, uniffi::Object)]
pub struct Dictionary {
    inner: dictionary::Dictionary,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Error)]
pub enum DictionaryError {
    /// The bytes are not a dictionary this engine can read.
    Invalid { reason: String },
}

impl std::fmt::Display for DictionaryError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            DictionaryError::Invalid { reason } => write!(f, "invalid dictionary: {reason}"),
        }
    }
}

impl std::error::Error for DictionaryError {}

#[uniffi::export]
impl Dictionary {
    /// Reads and checks a dictionary, refusing bad input.
    #[uniffi::constructor]
    pub fn new(bytes: Vec<u8>) -> Result<Arc<Self>, DictionaryError> {
        dictionary::Dictionary::from_bytes(&bytes)
            .map(|inner| Arc::new(Dictionary { inner }))
            .map_err(|e| DictionaryError::Invalid { reason: e.0 })
    }

    /// The BCP 47 language tag the dictionary is for.
    pub fn locale(&self) -> String {
        self.inner.locale().to_owned()
    }

    pub fn word_count(&self) -> u64 {
        self.inner.word_count() as u64
    }

    /// A metadata value (`name`, `layout`, `source`, `license`...), if set.
    pub fn metadata(&self, key: String) -> Option<String> {
        self.inner.metadata(&key).map(str::to_owned)
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Error)]
pub enum UserModelError {
    /// The JSON is not a word list this engine can read.
    Invalid { reason: String },
}

impl std::fmt::Display for UserModelError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            UserModelError::Invalid { reason } => write!(f, "invalid word list: {reason}"),
        }
    }
}

impl std::error::Error for UserModelError {}

/// What an import added.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct ImportSummary {
    pub words: u64,
    pub pairs: u64,
    pub skipped: u64,
}

/// The user's own words (see `user.rs`). Memory only: the app saves the JSON
/// where it may. Safe to call from any thread.
#[derive(Debug, uniffi::Object)]
pub struct UserModel {
    state: Mutex<UserState>,
}

#[derive(Debug, Default)]
struct UserState {
    words: UserWords,
    /// Bumped whenever the compiled dictionary goes out of date.
    generation: u64,
    /// Bumped on any change, so the app knows when to save.
    changes: u64,
}

impl UserModel {
    fn state(&self) -> MutexGuard<'_, UserState> {
        lock(&self.state)
    }
}

#[uniffi::export]
impl UserModel {
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(UserModel {
            state: Mutex::new(UserState::default()),
        })
    }

    /// The user typed `word`. `previous` is the word before it, `<s>` at
    /// the start of a sentence, or None if unknown. `day`: days since
    /// 1970-01-01.
    pub fn learn(&self, word: String, previous: Option<String>, day: u32) {
        let mut s = self.state();
        if s.words.learn(&word, previous.as_deref(), day) {
            s.generation += 1;
        }
        s.changes += 1;
    }

    /// The user kept `word` against an autocorrection: never correct it.
    pub fn keep(&self, word: String, day: u32) {
        let mut s = self.state();
        if s.words.keep(&word, day) {
            s.generation += 1;
        }
        s.changes += 1;
    }

    /// Forgets one word, and the pairs it is in.
    pub fn forget(&self, word: String) {
        let mut s = self.state();
        if s.words.forget(&word) {
            s.generation += 1;
            s.changes += 1;
        }
    }

    pub fn clear(&self) {
        let mut s = self.state();
        s.words.clear();
        s.generation += 1;
        s.changes += 1;
    }

    pub fn is_known(&self, word: String) -> bool {
        self.state().words.is_known(&word)
    }

    /// The known words, most used first.
    pub fn known_words(&self) -> Vec<String> {
        self.state()
            .words
            .known_words()
            .into_iter()
            .map(|(w, _)| w)
            .collect()
    }

    pub fn word_count(&self) -> u64 {
        self.state().words.word_count() as u64
    }

    /// Increases with every change; the app saves when it moved.
    pub fn change_count(&self) -> u64 {
        self.state().changes
    }

    /// Everything, as JSON.
    pub fn to_json(&self) -> String {
        self.state().words.to_json()
    }

    /// Adds a JSON export (see `user.rs`); refuses anything else.
    pub fn merge_json(&self, json: String) -> Result<ImportSummary, UserModelError> {
        let mut s = self.state();
        let merged = s
            .words
            .merge_json(&json)
            .map_err(|e| UserModelError::Invalid { reason: e.0 })?;
        s.generation += 1;
        s.changes += 1;
        Ok(ImportSummary {
            words: merged.words as u64,
            pairs: merged.pairs as u64,
            skipped: merged.skipped as u64,
        })
    }
}

/// One key press of the word being typed.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Tap {
    /// What the key typed, with its case.
    pub text: String,
    /// Where the finger lifted, in the unit of the key centers. None for a
    /// screen reader or a hardware keyboard.
    pub position: Option<Point>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct SuggestOptions {
    /// Whether to pick a correction that a space should commit.
    pub autocorrect: bool,
    /// Leave out words marked offensive unless they were typed in full.
    pub block_offensive: bool,
    /// The word starts a sentence (its capital came from automatic caps).
    pub sentence_start: bool,
    /// The word before, if known.
    pub previous_word: Option<String>,
    pub max_results: u32,
    /// Use what the keyboard knows about the user: their words, their next
    /// words, the corrections they undid. False in incognito.
    pub personal: bool,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct GlideOptions {
    /// The word starts a sentence.
    pub sentence_start: bool,
    /// The word before, if known.
    pub previous_word: Option<String>,
    pub max_results: u32,
    /// As in `SuggestOptions`.
    pub personal: bool,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct PredictOptions {
    /// The next word starts a sentence.
    pub sentence_start: bool,
    /// The word before, if known.
    pub previous_word: Option<String>,
    pub max_results: u32,
    /// As in `SuggestOptions`.
    pub personal: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum CandidateKind {
    /// The taps spell this word, maybe with mistakes.
    Correction,
    /// The taps start this word.
    Completion,
    /// A fixed correction from the dictionary ("alot" to "a lot").
    Shortcut,
    /// A word that may come next.
    Prediction,
    /// A word the finger's path may spell (glide typing).
    Glide,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Candidate {
    pub text: String,
    pub kind: CandidateKind,
    /// Higher is better. Only comparable within one result.
    pub score: f32,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Suggestions {
    /// The typed word, as typed.
    pub typed: String,
    /// A dictionary (or the user's own words) has the typed word.
    pub typed_is_word: bool,
    /// What a space should commit instead of the typed word, if anything.
    /// When set, it is also the first candidate.
    pub autocorrect: Option<String>,
    /// Best first. Never the typed word itself.
    pub candidates: Vec<Candidate>,
}

/// Typed words whose autocorrection the user undid, when learning is off.
/// Memory only, oldest forgotten first.
#[derive(Debug, Default)]
struct Rejections {
    order: VecDeque<String>,
    set: HashSet<String>,
}

const MAX_REJECTIONS: usize = 1000;
/// Most frequent words kept for predictions with no context.
const TOP_WORDS: usize = 12;
/// Score handicap of a language other than the first.
const OTHER_LANGUAGE: f32 = -1.0;
/// Share of a dictionary's letters the layout must have for the dictionary
/// to be searched (an English layout does not search a Russian dictionary).
const MIN_COVERAGE: f32 = 0.8;

impl Rejections {
    fn add(&mut self, word: String) {
        if self.set.insert(word.clone()) {
            self.order.push_back(word);
            while self.order.len() > MAX_REJECTIONS {
                if let Some(old) = self.order.pop_front() {
                    self.set.remove(&old);
                }
            }
        }
    }
}

#[derive(Debug)]
struct Language {
    dictionary: Arc<Dictionary>,
    geometry: Geometry,
    searched: bool,
}

#[derive(Debug, Default)]
struct UserCache {
    /// The model generation compiled, None before the first compile.
    generation: Option<u64>,
    dictionary: Option<dictionary::Dictionary>,
    geometry: Geometry,
}

/// Up to this many words, the user's words are compiled as soon as they
/// change. More take a while, so `refresh_user_words` compiles them off the
/// main thread, and suggestions use the last compiled words meanwhile.
const QUICK_COMPILE_WORDS: usize = 2_000;

#[derive(Debug)]
struct State {
    keys: Vec<KeyCenter>,
    languages: Vec<Language>,
    top_words: Vec<(String, f32)>,
    user: Option<Arc<UserModel>>,
    user_cache: UserCache,
    rejections: Rejections,
    params: Params,
}

impl State {
    fn language(&self, dictionary: Arc<Dictionary>) -> Language {
        let geometry = Geometry::new(&self.keys, &dictionary.inner);
        let searched = geometry.is_empty() || geometry.coverage(&dictionary.inner) >= MIN_COVERAGE;
        Language {
            dictionary,
            geometry,
            searched,
        }
    }

    /// Recompiles the user's words if they changed, when that is quick or
    /// nothing was compiled yet; otherwise `refresh_user_words` does it.
    fn refresh_user_if_quick(&mut self) {
        let Some(model) = self.user.clone() else {
            self.user_cache = UserCache::default();
            return;
        };
        let state = model.state();
        let compiled = self.user_cache.generation;
        if compiled == Some(state.generation)
            || (compiled.is_some() && state.words.word_count() > QUICK_COMPILE_WORDS)
        {
            return;
        }
        let generation = state.generation;
        let entries = state.words.known_entries();
        drop(state);
        self.set_user_words(generation, user::compile_entries(entries));
    }

    fn set_user_words(&mut self, generation: u64, dictionary: Option<dictionary::Dictionary>) {
        let geometry = dictionary
            .as_ref()
            .map(|d| Geometry::new(&self.keys, d))
            .unwrap_or_default();
        self.user_cache = UserCache {
            generation: Some(generation),
            dictionary,
            geometry,
        };
    }

    /// The dictionaries to search; the user's words only when `personal`.
    fn sources(&self, personal: bool) -> Vec<Source<'_>> {
        let mut out: Vec<Source<'_>> = self
            .languages
            .iter()
            .enumerate()
            .filter(|(i, l)| *i == 0 || l.searched)
            .map(|(i, l)| Source {
                dictionary: &l.dictionary.inner,
                geometry: &l.geometry,
                bonus: if i == 0 { 0.0 } else { OTHER_LANGUAGE },
            })
            .collect();
        if let Some(d) = self.user_cache.dictionary.as_ref().filter(|_| personal) {
            out.push(Source {
                dictionary: d,
                geometry: &self.user_cache.geometry,
                bonus: 0.0,
            });
        }
        out
    }

    fn context(&self, previous: Option<String>, sentence_start: bool, personal: bool) -> Context {
        let previous = previous.filter(|p| !p.is_empty());
        let user_followers = self
            .user
            .as_ref()
            .filter(|_| personal)
            .map(|m| {
                m.state()
                    .words
                    .followers(previous.as_deref(), sentence_start)
            })
            .unwrap_or_default();
        Context {
            previous,
            sentence_start,
            user_followers,
        }
    }
}

/// Suggestions, autocorrect and predictions for the languages the user
/// types in, their own words, and the keyboard layout on screen. Safe to call
/// from any thread.
#[derive(Debug, uniffi::Object)]
pub struct Suggester {
    state: Mutex<State>,
}

impl Suggester {
    fn state(&self) -> MutexGuard<'_, State> {
        lock(&self.state)
    }

    /// A suggester with other costs and thresholds (tests and tuning).
    pub fn with_params(dictionary: Arc<Dictionary>, params: Params) -> Arc<Self> {
        let top_words = dictionary.inner.top_words(TOP_WORDS);
        let mut state = State {
            keys: Vec::new(),
            languages: Vec::new(),
            top_words,
            user: None,
            user_cache: UserCache::default(),
            rejections: Rejections::default(),
            params,
        };
        state.languages = vec![state.language(dictionary)];
        Arc::new(Suggester {
            state: Mutex::new(state),
        })
    }
}

fn candidate(c: suggest::Scored) -> Candidate {
    Candidate {
        text: c.text,
        kind: match c.kind {
            suggest::Kind::Correction => CandidateKind::Correction,
            suggest::Kind::Completion => CandidateKind::Completion,
            suggest::Kind::Shortcut => CandidateKind::Shortcut,
            suggest::Kind::Prediction => CandidateKind::Prediction,
        },
        score: c.score,
    }
}

#[uniffi::export]
impl Suggester {
    /// A suggester for one language.
    #[uniffi::constructor]
    pub fn new(dictionary: Arc<Dictionary>) -> Arc<Self> {
        Self::with_params(dictionary, Params::default())
    }

    /// The languages to type in, the current one first. Others are searched
    /// too when the layout on screen can type them, ranked a little lower.
    pub fn set_dictionaries(&self, dictionaries: Vec<Arc<Dictionary>>) {
        let mut state = self.state();
        if let Some(first) = dictionaries.first() {
            state.top_words = first.inner.top_words(TOP_WORDS);
        }
        state.languages = dictionaries
            .into_iter()
            .map(|d| state.language(d))
            .collect();
    }

    /// The user's own words, or None (incognito, before the first unlock).
    pub fn set_user_model(&self, model: Option<Arc<UserModel>>) {
        let mut state = self.state();
        let same = match (&state.user, &model) {
            (Some(a), Some(b)) => Arc::ptr_eq(a, b),
            (None, None) => true,
            _ => false,
        };
        if !same {
            state.user = model;
            state.user_cache = UserCache::default();
        }
    }

    /// The keys of the layout on screen. Call again when the layout or its
    /// size changes.
    pub fn set_keys(&self, keys: Vec<KeyCenter>) {
        let mut state = self.state();
        state.keys = keys;
        let dictionaries: Vec<Arc<Dictionary>> = state
            .languages
            .iter()
            .map(|l| l.dictionary.clone())
            .collect();
        state.languages = dictionaries
            .into_iter()
            .map(|d| state.language(d))
            .collect();
        // The user's compiled words stay; only their key positions change.
        let geometry = state
            .user_cache
            .dictionary
            .as_ref()
            .map(|d| Geometry::new(&state.keys, d));
        if let Some(geometry) = geometry {
            state.user_cache.geometry = geometry;
        }
    }

    /// Suggestions for the word made of these taps.
    pub fn suggest(&self, taps: Vec<Tap>, options: SuggestOptions) -> Suggestions {
        let presses: Vec<Press> = taps
            .into_iter()
            .map(|t| Press {
                text: t.text,
                position: t.position,
            })
            .collect();
        let mut state = self.state();
        if options.personal {
            state.refresh_user_if_quick();
        }
        let context = state.context(
            options.previous_word,
            options.sentence_start,
            options.personal,
        );
        let request = Request {
            presses: &presses,
            context: &context,
            autocorrect: options.autocorrect,
            block_offensive: options.block_offensive,
            max_results: options.max_results as usize,
        };
        let sources = state.sources(options.personal);
        // Not again: corrections the user undid, in this session or kept in
        // their words (which also stops fixed corrections like "ill").
        let kept = |w: &str| {
            state
                .user
                .as_ref()
                .is_some_and(|m| m.state().words.is_kept(w))
        };
        let outcome = suggest::decode(&sources, &state.params, &request, |w| {
            options.personal && (state.rejections.set.contains(w) || kept(w))
        });
        Suggestions {
            typed: outcome.typed,
            typed_is_word: outcome.typed_is_word,
            autocorrect: outcome.autocorrect,
            candidates: outcome.candidates.into_iter().map(candidate).collect(),
        }
    }

    /// Words that may come next.
    /// The words a finger's path over the keys may spell, best first.
    /// `points` are in the unit of the key centers given to `set_keys`.
    pub fn glide(&self, points: Vec<Point>, options: GlideOptions) -> Vec<Candidate> {
        let mut state = self.state();
        if options.personal {
            state.refresh_user_if_quick();
        }
        let context = state.context(
            options.previous_word,
            options.sentence_start,
            options.personal,
        );
        let sources = state.sources(options.personal);
        glide::decode(
            &sources,
            &state.params,
            &state.params.glide,
            &context,
            &points,
            true,
            options.max_results as usize,
        )
        .into_iter()
        .map(|g| Candidate {
            text: g.text,
            kind: CandidateKind::Glide,
            score: g.score,
        })
        .collect()
    }

    pub fn predict(&self, options: PredictOptions) -> Vec<Candidate> {
        let mut state = self.state();
        if options.personal {
            state.refresh_user_if_quick();
        }
        let context = state.context(
            options.previous_word,
            options.sentence_start,
            options.personal,
        );
        let sources = state.sources(options.personal);
        suggest::predict(
            &sources,
            &state.params,
            &context,
            options.max_results as usize,
            &state.top_words,
        )
        .into_iter()
        .map(candidate)
        .collect()
    }

    /// Compiles the user's words if they changed, without holding up
    /// suggestions meanwhile. Call it off the main thread after learning.
    pub fn refresh_user_words(&self) {
        let (model, compiled) = {
            let state = self.state();
            match &state.user {
                Some(m) => (m.clone(), state.user_cache.generation),
                None => return,
            }
        };
        let (generation, entries) = {
            let m = model.state();
            if compiled == Some(m.generation) {
                return;
            }
            (m.generation, m.words.known_entries())
        };
        let dictionary = user::compile_entries(entries);
        let mut state = self.state();
        let same_model = state.user.as_ref().is_some_and(|u| Arc::ptr_eq(u, &model));
        if same_model && state.user_cache.generation.is_none_or(|g| g < generation) {
            state.set_user_words(generation, dictionary);
        }
    }

    /// How a committed word should be learned: a capital that only marks a
    /// sentence start is dropped when a dictionary has the word in lower
    /// case ("The" is learned as "the"; "I" and "Paris" keep theirs).
    pub fn learning_form(&self, word: String, sentence_start: bool) -> String {
        if !sentence_start || text::case_of(&word) != text::Case::Capitalized {
            return word;
        }
        let mut chars = word.chars();
        let lower: String = match chars.next() {
            Some(first) => first.to_lowercase().chain(chars).collect(),
            None => return word,
        };
        let key = text::key_of(&lower);
        let state = self.state();
        let known = state
            .user
            .as_ref()
            .is_some_and(|m| m.state().words.is_known(&lower))
            || state.languages.iter().any(|l| {
                let d = &l.dictionary.inner;
                d.find(&key)
                    .is_some_and(|node| d.entries(node).any(|id| d.spelling(id, &key) == lower))
            });
        if known { lower } else { word }
    }

    /// The user undid the autocorrection of `typed` where learning is off:
    /// do not autocorrect it again while the keyboard runs. Memory only.
    pub fn reject(&self, typed: String) {
        self.state().rejections.add(typed.to_lowercase());
    }

    /// Forgets every rejected autocorrection.
    pub fn forget_rejections(&self) {
        self.state().rejections = Rejections::default();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::dictionary::tests::small;
    use crate::suggest::tests::{qwerty, taps};

    fn suggester() -> Arc<Suggester> {
        let s = Suggester::new(Dictionary::new(small()).unwrap());
        s.set_keys(qwerty());
        s
    }

    fn options() -> SuggestOptions {
        SuggestOptions {
            autocorrect: true,
            block_offensive: true,
            sentence_start: false,
            previous_word: None,
            max_results: 3,
            personal: true,
        }
    }

    fn tap(word: &str) -> Vec<Tap> {
        taps(word)
            .into_iter()
            .map(|p| Tap {
                text: p.text,
                position: p.position,
            })
            .collect()
    }

    #[test]
    fn suggests_and_autocorrects() {
        let s = suggester();
        let out = s.suggest(tap("teh"), options());
        assert_eq!(out.typed, "teh");
        assert_eq!(out.autocorrect.as_deref(), Some("the"));
        assert_eq!(out.candidates[0].text, "the");
        assert!(out.candidates.len() <= 3);
    }

    #[test]
    fn rejections_stick_until_forgotten() {
        let s = suggester();
        s.reject("Teh".into());
        assert_eq!(s.suggest(tap("teh"), options()).autocorrect, None);
        s.forget_rejections();
        assert_eq!(
            s.suggest(tap("teh"), options()).autocorrect.as_deref(),
            Some("the")
        );
    }

    #[test]
    fn rejections_are_bounded() {
        let mut r = Rejections::default();
        for i in 0..MAX_REJECTIONS + 10 {
            r.add(format!("w{i}"));
        }
        assert_eq!(r.set.len(), MAX_REJECTIONS);
        assert!(!r.set.contains("w0"));
        assert!(r.set.contains(&format!("w{}", MAX_REJECTIONS + 9)));
    }

    #[test]
    fn bad_bytes_are_an_error() {
        assert!(matches!(
            Dictionary::new(vec![1, 2, 3]),
            Err(DictionaryError::Invalid { .. })
        ));
    }

    #[test]
    fn metadata_is_readable() {
        let d = Dictionary::new(small()).unwrap();
        assert_eq!(d.locale(), "en-US");
        assert_eq!(d.metadata("locale".into()).as_deref(), Some("en-US"));
        assert_eq!(d.metadata("nothing".into()), None);
        assert_eq!(d.word_count(), 21);
    }

    #[test]
    fn learned_words_are_words() {
        let s = suggester();
        let model = UserModel::new();
        s.set_user_model(Some(model.clone()));
        // "Pingo" is not a word: a correction is offered for it.
        assert!(!s.suggest(tap("pingo"), options()).typed_is_word);
        model.learn("pingo".into(), None, 1);
        model.learn("pingo".into(), None, 2);
        let out = s.suggest(tap("pingo"), options());
        assert!(out.typed_is_word);
        assert_eq!(out.autocorrect, None);
        // And it is suggested from its first letters.
        let out = s.suggest(tap("ping"), options());
        assert!(
            out.candidates.iter().any(|c| c.text == "pingo"),
            "{:?}",
            out.candidates
        );
        // Kept words are never corrected, from the first time.
        model.keep("teh".into(), 3);
        assert_eq!(s.suggest(tap("teh"), options()).autocorrect, None);
        // Without the model, the dictionary alone decides again.
        s.set_user_model(None);
        assert_eq!(
            s.suggest(tap("teh"), options()).autocorrect.as_deref(),
            Some("the")
        );
    }

    #[test]
    fn many_words_compile_off_the_main_thread() {
        let s = suggester();
        let model = UserModel::new();
        // Words made of letters only: zqa, zqb, ... zqaa, ...
        let word = |mut i: usize| {
            let mut w = String::from("zq");
            loop {
                w.push(char::from(b'a' + (i % 26) as u8));
                i /= 26;
                if i == 0 {
                    break w;
                }
            }
        };
        for i in 0..(QUICK_COMPILE_WORDS + 10) {
            model.keep(word(i), 1);
        }
        assert!(model.word_count() as usize > QUICK_COMPILE_WORDS);
        s.set_user_model(Some(model.clone()));
        // The first compile happens at once.
        assert!(s.suggest(tap("zqh"), options()).typed_is_word);
        model.keep("pingo".into(), 1);
        assert!(
            !s.suggest(tap("pingo"), options()).typed_is_word,
            "not yet compiled"
        );
        s.refresh_user_words();
        assert!(s.suggest(tap("pingo"), options()).typed_is_word);
        // Forgetting everything is never slow, so it applies at once.
        model.clear();
        assert!(!s.suggest(tap("zqh"), options()).typed_is_word);
    }

    #[test]
    fn incognito_uses_nothing_personal() {
        let s = suggester();
        let model = UserModel::new();
        s.set_user_model(Some(model.clone()));
        model.keep("pingo".into(), 1);
        model.keep("alot".into(), 1);
        for _ in 0..3 {
            model.learn("pingo".into(), Some("I".into()), 1);
        }
        s.reject("teh".into());
        let incognito = SuggestOptions {
            personal: false,
            ..options()
        };
        assert!(s.suggest(tap("pingo"), options()).typed_is_word);
        assert!(!s.suggest(tap("pingo"), incognito.clone()).typed_is_word);
        assert_eq!(s.suggest(tap("alot"), options()).autocorrect, None);
        assert_eq!(
            s.suggest(tap("alot"), incognito.clone())
                .autocorrect
                .as_deref(),
            Some("a lot")
        );
        assert_eq!(s.suggest(tap("teh"), options()).autocorrect, None);
        assert_eq!(
            s.suggest(tap("teh"), incognito).autocorrect.as_deref(),
            Some("the")
        );
        let first = |personal: bool| -> Vec<String> {
            s.predict(PredictOptions {
                sentence_start: false,
                previous_word: Some("I".into()),
                max_results: 3,
                personal,
            })
            .into_iter()
            .map(|c| c.text)
            .collect()
        };
        assert!(first(true).contains(&"pingo".to_string()));
        assert!(!first(false).contains(&"pingo".to_string()));
    }

    #[test]
    fn a_kept_word_stops_a_fixed_correction() {
        let s = suggester();
        let model = UserModel::new();
        s.set_user_model(Some(model.clone()));
        assert_eq!(
            s.suggest(tap("alot"), options()).autocorrect.as_deref(),
            Some("a lot")
        );
        model.keep("alot".into(), 1);
        assert_eq!(s.suggest(tap("alot"), options()).autocorrect, None);
    }

    #[test]
    fn the_compiled_words_survive_the_same_model_and_new_keys() {
        let s = suggester();
        let model = UserModel::new();
        model.keep("pingo".into(), 1);
        s.set_user_model(Some(model.clone()));
        assert!(s.suggest(tap("pingo"), options()).typed_is_word);
        let generation = s.state().user_cache.generation;
        s.set_user_model(Some(model.clone()));
        s.set_keys(qwerty());
        let state = s.state();
        assert!(state.user_cache.dictionary.is_some(), "not recompiled");
        assert_eq!(state.user_cache.generation, generation);
        assert!(!state.user_cache.geometry.is_empty());
    }

    #[test]
    fn predictions_use_the_users_pairs() {
        let s = suggester();
        let model = UserModel::new();
        s.set_user_model(Some(model.clone()));
        let predict = |previous: Option<&str>, sentence_start: bool| -> Vec<String> {
            s.predict(PredictOptions {
                sentence_start,
                previous_word: previous.map(str::to_owned),
                max_results: 3,
                personal: true,
            })
            .into_iter()
            .map(|c| c.text)
            .collect()
        };
        assert_eq!(predict(Some("I"), false), ["work", "then"]);
        for _ in 0..3 {
            model.learn("hello".into(), Some("I".into()), 1);
        }
        assert_eq!(predict(Some("i"), false)[0], "hello");
        assert_eq!(predict(None, true)[0], "I");
    }

    #[test]
    fn a_capital_from_a_sentence_start_is_not_learned() {
        let s = suggester();
        assert_eq!(s.learning_form("Hello".into(), true), "hello");
        assert_eq!(s.learning_form("Hello".into(), false), "Hello");
        assert_eq!(s.learning_form("I".into(), true), "I");
        assert_eq!(s.learning_form("Monday".into(), true), "Monday");
        assert_eq!(s.learning_form("Pingolin".into(), true), "Pingolin");
        assert_eq!(s.learning_form("HELLO".into(), true), "HELLO");
    }

    #[test]
    fn user_model_json_and_counts() {
        let model = UserModel::new();
        model.learn("there".into(), Some("hello".into()), 1);
        model.keep("Pingolin".into(), 1);
        assert_eq!(model.known_words(), ["Pingolin"]);
        let changes = model.change_count();
        let json = model.to_json();
        let other = UserModel::new();
        let summary = other.merge_json(json).unwrap();
        assert_eq!(
            summary,
            ImportSummary {
                words: 2,
                pairs: 1,
                skipped: 0
            }
        );
        assert!(other.is_known("Pingolin".into()));
        assert!(other.merge_json("{}".into()).is_err());
        model.forget("Pingolin".into());
        assert!(!model.is_known("Pingolin".into()));
        assert!(model.change_count() > changes);
        model.clear();
        assert_eq!(model.word_count(), 0);
    }

    #[test]
    fn a_layout_that_cannot_type_a_language_does_not_search_it() {
        let list = crate::wordlist::WordList::parse("привет\t200\nмир\t180\n", "", "locale\tru\n")
            .unwrap();
        let russian = Dictionary::new(crate::dictionary::build(&list).unwrap()).unwrap();
        let s = suggester();
        s.set_dictionaries(vec![Dictionary::new(small()).unwrap(), russian]);
        let state = s.state();
        assert!(state.languages[0].searched);
        assert!(
            !state.languages[1].searched,
            "QWERTY has no Cyrillic letters"
        );
    }
}
