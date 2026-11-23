// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: Apache-2.0 OR MIT

//! Keyboard layouts.
//!
//! Built in: symbols, and letters for each language a dictionary names in its
//! `layout` metadata. "letters" is QWERTY; the others are the national
//! layouts phones use, with the letters a language needs on their own keys.
//! Accented letters without a key are typed as their base letter: the decoder
//! matches "cafe" to "café". Also built in: the text editing panel, and the
//! terminal rows an app may put above any layout.

/// What a key does when it is pressed.
#[derive(Debug, Clone, PartialEq, uniffi::Enum)]
pub enum KeyAction {
    /// Types `text`. With Shift on, the app types it in upper case.
    Text { text: String },
    /// Shift: once for the next letter, twice in a row for caps lock.
    Shift,
    /// Deletes the character before the cursor, or the selection.
    Backspace,
    /// Types a space.
    Space,
    /// Runs the field's action (Go, Send, Next) or starts a new line.
    Enter,
    /// Shows another layout of the same keyboard.
    SwitchLayout { layout_id: String },
    /// Moves the cursor or edits around it, as a hardware keyboard's key does.
    Edit { command: EditCommand },
    /// Ctrl or Alt for the next key; a second tap in a row locks it.
    Modifier { key: ModifierKey },
}

/// What an `Edit` key does.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum EditCommand {
    Left,
    Right,
    Up,
    Down,
    /// To the start of the line.
    Home,
    /// To the end of the line.
    End,
    PageUp,
    PageDown,
    /// Deletes the character after the cursor.
    ForwardDelete,
    Escape,
    Tab,
    /// Moving keys select while this is on.
    Select,
    SelectAll,
    Cut,
    Copy,
    Paste,
}

/// A modifier key.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ModifierKey {
    Ctrl,
    Alt,
}

/// One key of a layout.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Key {
    /// What the key shows. Empty for keys the app draws itself (Shift, Space).
    pub label: String,
    pub action: KeyAction,
    /// Width relative to a letter key, which is 1.0.
    pub width: f32,
}

/// A layout: rows of keys, top to bottom.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct KeyboardLayout {
    pub id: String,
    pub rows: Vec<Vec<Key>>,
}

const LETTERS: &str = "letters";
const SYMBOLS: &str = "symbols";
const EDITING: &str = "editing";

/// Letter layouts: their rows of letter keys, top to bottom. The bottom row
/// sits between Shift and Delete.
const LETTER_LAYOUTS: &[(&str, [&str; 3])] = &[
    (LETTERS, ["qwertyuiop", "asdfghjkl", "zxcvbnm"]),
    ("qwertz", ["qwertzuiop", "asdfghjkl", "yxcvbnm"]),
    ("qwertz-de", ["qwertzuiopü", "asdfghjklöä", "yxcvbnmß"]),
    ("azerty", ["azertyuiop", "qsdfghjklm", "wxcvbn"]),
    ("qwerty-es", ["qwertyuiop", "asdfghjklñ", "zxcvbnm"]),
    ("qwerty-sv", ["qwertyuiopå", "asdfghjklöä", "zxcvbnm"]),
    ("qwerty-da", ["qwertyuiopå", "asdfghjklæø", "zxcvbnm"]),
    ("qwerty-nb", ["qwertyuiopå", "asdfghjkløæ", "zxcvbnm"]),
    ("turkish", ["qwertyuıopğü", "asdfghjklşi", "zxcvbnmöç"]),
    ("russian", ["йцукенгшщзхъ", "фывапролджэ", "ячсмитьбю"]),
    ("serbian", ["љњертзуиопшђ", "асдфгхјклчћж", "ѕџцвбнм"]),
    ("greek", ["ςερτυθιοπ", "ασδφγηξκλ", "ζχψωβνμ"]),
    ("hebrew", ["קראטוןםפ", "שדגכעיחלךף", "זסבהנמצתץ"]),
];

/// Ids of the built-in layouts.
#[uniffi::export]
pub fn builtin_layout_ids() -> Vec<String> {
    LETTER_LAYOUTS
        .iter()
        .map(|(id, _)| (*id).to_owned())
        .chain([SYMBOLS.to_owned(), EDITING.to_owned()])
        .collect()
}

/// A built-in layout by id, or `None` for an unknown id.
#[uniffi::export]
pub fn builtin_layout(id: String) -> Option<KeyboardLayout> {
    if id == SYMBOLS {
        return Some(symbols());
    }
    if id == EDITING {
        return Some(editing());
    }
    LETTER_LAYOUTS
        .iter()
        .find(|(layout, _)| *layout == id)
        .map(|(layout, rows)| letters(layout, rows))
}

/// The layout a field starts with: symbols for numeric fields, letters otherwise.
#[uniffi::export]
pub fn initial_layout_id(numeric_field: bool) -> String {
    if numeric_field { SYMBOLS } else { LETTERS }.to_owned()
}

fn key(label: &str, action: KeyAction, width: f32) -> Key {
    Key {
        label: label.to_owned(),
        action,
        width,
    }
}

fn text_keys(chars: &str) -> Vec<Key> {
    chars
        .chars()
        .map(|c| {
            let text = c.to_string();
            key(&text, KeyAction::Text { text: text.clone() }, 1.0)
        })
        .collect()
}

fn bottom_row(switch_label: &str, switch_to: &str) -> Vec<Key> {
    let switch = KeyAction::SwitchLayout {
        layout_id: switch_to.to_owned(),
    };
    vec![
        key(switch_label, switch, 1.5),
        key(
            ",",
            KeyAction::Text {
                text: ",".to_owned(),
            },
            1.0,
        ),
        key("", KeyAction::Space, 5.0),
        key(
            ".",
            KeyAction::Text {
                text: ".".to_owned(),
            },
            1.0,
        ),
        key("", KeyAction::Enter, 1.5),
    ]
}

fn letters(id: &str, rows: &[&str; 3]) -> KeyboardLayout {
    let mut third = vec![key("", KeyAction::Shift, 1.5)];
    third.extend(text_keys(rows[2]));
    third.push(key("", KeyAction::Backspace, 1.5));
    KeyboardLayout {
        id: id.to_owned(),
        rows: vec![
            text_keys(rows[0]),
            text_keys(rows[1]),
            third,
            bottom_row("?123", SYMBOLS),
        ],
    }
}

fn edit(command: EditCommand, width: f32) -> Key {
    key("", KeyAction::Edit { command }, width)
}

/// The text editing panel: arrows, selection, the clipboard.
fn editing() -> KeyboardLayout {
    use EditCommand::*;
    let w = 2.5;
    KeyboardLayout {
        id: EDITING.to_owned(),
        rows: vec![
            vec![edit(Home, w), edit(Up, w), edit(End, w), edit(SelectAll, w)],
            vec![
                edit(Left, w),
                edit(Select, w),
                edit(Right, w),
                edit(Copy, w),
            ],
            vec![
                key("", KeyAction::Backspace, w),
                edit(Down, w),
                edit(ForwardDelete, w),
                edit(Cut, w),
            ],
            vec![
                key(
                    "ABC",
                    KeyAction::SwitchLayout {
                        layout_id: LETTERS.to_owned(),
                    },
                    w,
                ),
                key("", KeyAction::Space, 3.0),
                key("", KeyAction::Enter, 2.0),
                edit(Paste, w),
            ],
        ],
    }
}

/// Two rows for terminals and ssh, to put above a layout: Esc, Tab, Ctrl,
/// Alt, the arrows, Home, End, Page Up and Down, and ~ | / -.
#[uniffi::export]
pub fn terminal_rows() -> Vec<Vec<Key>> {
    use EditCommand::*;
    let w = 1.25;
    let text = |t: &str| key(t, KeyAction::Text { text: t.to_owned() }, w);
    let modifier = |k: ModifierKey| key("", KeyAction::Modifier { key: k }, w);
    vec![
        vec![
            edit(Escape, w),
            text("~"),
            text("|"),
            edit(Home, w),
            edit(Up, w),
            edit(End, w),
            edit(PageUp, w),
            text("/"),
        ],
        vec![
            edit(Tab, w),
            modifier(ModifierKey::Ctrl),
            modifier(ModifierKey::Alt),
            edit(Left, w),
            edit(Down, w),
            edit(Right, w),
            edit(PageDown, w),
            text("-"),
        ],
    ]
}

fn symbols() -> KeyboardLayout {
    let mut third = text_keys("*\"':;!?/");
    third.push(key("", KeyAction::Backspace, 1.5));
    KeyboardLayout {
        id: SYMBOLS.to_owned(),
        rows: vec![
            text_keys("1234567890"),
            text_keys("@#$%&-+()"),
            third,
            bottom_row("ABC", LETTERS),
        ],
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn all_layouts() -> Vec<KeyboardLayout> {
        builtin_layout_ids()
            .into_iter()
            .map(|id| builtin_layout(id).expect("a listed id must resolve"))
            .collect()
    }

    #[test]
    fn every_listed_id_resolves_to_itself() {
        for id in builtin_layout_ids() {
            assert_eq!(builtin_layout(id.clone()).unwrap().id, id);
        }
    }

    #[test]
    fn an_unknown_id_is_none() {
        assert!(builtin_layout("no-such-layout".to_owned()).is_none());
    }

    #[test]
    fn letters_have_every_latin_letter_exactly_once() {
        let mut found: Vec<String> = builtin_layout(LETTERS.to_owned())
            .unwrap()
            .rows
            .iter()
            .flatten()
            .filter_map(|k| match &k.action {
                KeyAction::Text { text } if text.chars().all(|c| c.is_ascii_lowercase()) => {
                    Some(text.clone())
                }
                _ => None,
            })
            .collect();
        found.sort();
        let expected: Vec<String> = ('a'..='z').map(|c| c.to_string()).collect();
        assert_eq!(found, expected);
    }

    #[test]
    fn symbols_have_every_digit() {
        let symbols = builtin_layout(SYMBOLS.to_owned()).unwrap();
        for digit in '0'..='9' {
            let text = digit.to_string();
            assert!(
                symbols
                    .rows
                    .iter()
                    .flatten()
                    .any(|k| k.action == KeyAction::Text { text: text.clone() }),
                "missing {digit}"
            );
        }
    }

    #[test]
    fn every_layout_has_backspace_space_and_enter() {
        for layout in all_layouts() {
            let actions: Vec<&KeyAction> =
                layout.rows.iter().flatten().map(|k| &k.action).collect();
            for wanted in [KeyAction::Backspace, KeyAction::Space, KeyAction::Enter] {
                assert!(actions.contains(&&wanted), "{} lacks {wanted:?}", layout.id);
            }
        }
    }

    #[test]
    fn layout_switches_point_at_builtin_layouts() {
        let ids = builtin_layout_ids();
        for layout in all_layouts() {
            for key in layout.rows.iter().flatten() {
                if let KeyAction::SwitchLayout { layout_id } = &key.action {
                    assert!(ids.contains(layout_id), "{} -> {layout_id}", layout.id);
                    assert_ne!(layout_id, &layout.id, "a switch must go somewhere else");
                }
            }
        }
    }

    #[test]
    fn widths_are_positive_and_text_keys_show_their_text() {
        for layout in all_layouts() {
            for key in layout.rows.iter().flatten() {
                assert!(key.width > 0.0, "{}: {:?}", layout.id, key);
                if let KeyAction::Text { text } = &key.action {
                    assert_eq!(&key.label, text);
                }
            }
        }
    }

    #[test]
    fn letter_layouts_have_distinct_lower_case_letters() {
        for (id, rows) in LETTER_LAYOUTS {
            let letters: Vec<char> = rows.iter().flat_map(|r| r.chars()).collect();
            let mut unique = letters.clone();
            unique.sort_unstable();
            unique.dedup();
            assert_eq!(unique.len(), letters.len(), "{id} repeats a letter");
            for c in letters {
                assert!(c.is_alphabetic(), "{id}: {c:?}");
                assert!(!c.is_uppercase(), "{id}: {c:?} is upper case");
            }
        }
    }

    #[test]
    fn rows_are_equally_wide() {
        let width = |row: &Vec<Key>| row.iter().map(|k| k.width).sum::<f32>();
        for layout in [builtin_layout(EDITING.to_owned()).unwrap()] {
            for row in &layout.rows {
                assert!(
                    (width(row) - 10.0).abs() < 1e-4,
                    "{}: {}",
                    layout.id,
                    width(row)
                );
            }
        }
        for row in terminal_rows() {
            assert!((width(&row) - 10.0).abs() < 1e-4);
        }
    }

    #[test]
    fn the_editing_panel_moves_selects_and_uses_the_clipboard() {
        let panel = builtin_layout(EDITING.to_owned()).unwrap();
        let commands: Vec<EditCommand> = panel
            .rows
            .iter()
            .flatten()
            .filter_map(|k| match k.action {
                KeyAction::Edit { command } => Some(command),
                _ => None,
            })
            .collect();
        use EditCommand::*;
        for wanted in [
            Left, Right, Up, Down, Home, End, Select, SelectAll, Cut, Copy, Paste,
        ] {
            assert!(commands.contains(&wanted), "{wanted:?}");
        }
    }

    #[test]
    fn terminal_rows_have_what_ssh_needs() {
        let keys: Vec<KeyAction> = terminal_rows()
            .into_iter()
            .flatten()
            .map(|k| k.action)
            .collect();
        use EditCommand::*;
        for wanted in [
            Escape, Tab, Left, Right, Up, Down, Home, End, PageUp, PageDown,
        ] {
            assert!(
                keys.contains(&KeyAction::Edit { command: wanted }),
                "{wanted:?}"
            );
        }
        for wanted in [ModifierKey::Ctrl, ModifierKey::Alt] {
            assert!(keys.contains(&KeyAction::Modifier { key: wanted }));
        }
        for t in ["~", "|", "/", "-"] {
            assert!(
                keys.contains(&KeyAction::Text { text: t.to_owned() }),
                "{t}"
            );
        }
    }

    #[test]
    fn numeric_fields_start_on_symbols() {
        assert_eq!(initial_layout_id(true), SYMBOLS);
        assert_eq!(initial_layout_id(false), LETTERS);
    }
}
