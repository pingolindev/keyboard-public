// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: Apache-2.0 OR MIT

//! Text input engine of Pingolin Keyboard.
//!
//! The engine holds everything that is not drawing or platform glue: layouts,
//! dictionaries, the touch model, the decoder behind suggestions, autocorrect
//! and predictions, and the user's own words. It does no I/O of its own and
//! contains no network code. Apps call it through the UniFFI bindings (Kotlin on
//! Android); the Rust API is public too, for tools and other keyboards.

mod api;
pub mod dictionary;
pub mod emoji;
pub mod glide;
mod layout;
pub mod suggest;
pub mod text;
pub mod touch;
pub mod user;
pub mod voice;
pub mod wordlist;

pub use api::{
    Candidate, CandidateKind, Dictionary, DictionaryError, GlideOptions, ImportSummary,
    PredictOptions, SuggestOptions, Suggester, Suggestions, Tap, UserModel, UserModelError,
};
pub use emoji::{Emoji, EmojiError, EmojiIndex};
pub use layout::{
    EditCommand, FieldKind, Key, KeyAction, KeyboardLayout, ModifierKey, builtin_layout,
    builtin_layout_ids, initial_layout_id, terminal_rows,
};
pub use touch::{KeyCenter, Point};
pub use voice::{VoiceError, VoiceModel, voice_available};

uniffi::setup_scaffolding!();

/// Version of the engine.
#[uniffi::export]
pub fn engine_version() -> String {
    env!("CARGO_PKG_VERSION").to_owned()
}

#[cfg(test)]
mod tests {
    #[test]
    fn version_matches_the_crate() {
        assert_eq!(super::engine_version(), env!("CARGO_PKG_VERSION"));
    }
}
