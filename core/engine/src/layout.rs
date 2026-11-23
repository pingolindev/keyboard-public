// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: Apache-2.0 OR MIT

//! Keyboard layouts.
//!
//! Built in: two pages of symbols, and letters for each language a dictionary names in its
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
    /// What a long press offers instead (accented letters, other quotes),
    /// most likely first. Typed like `action`'s text.
    pub alternates: Vec<String>,
}

/// A layout: rows of keys, top to bottom.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct KeyboardLayout {
    pub id: String,
    pub rows: Vec<Vec<Key>>,
}

const LETTERS: &str = "letters";
const SYMBOLS: &str = "symbols";
const MORE_SYMBOLS: &str = "more-symbols";
const NUMBERS: &str = "numbers";
const PHONE: &str = "phone";
const DATE_TIME: &str = "date-time";
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
        .chain([
            SYMBOLS.to_owned(),
            MORE_SYMBOLS.to_owned(),
            NUMBERS.to_owned(),
            PHONE.to_owned(),
            DATE_TIME.to_owned(),
            EDITING.to_owned(),
        ])
        .collect()
}

/// A built-in layout by id, or `None` for an unknown id.
#[uniffi::export]
pub fn builtin_layout(id: String) -> Option<KeyboardLayout> {
    if id == SYMBOLS {
        return Some(symbols());
    }
    if id == MORE_SYMBOLS {
        return Some(more_symbols());
    }
    if id == EDITING {
        return Some(editing());
    }
    match id.as_str() {
        NUMBERS => return Some(pad(NUMBERS, ",", ".")),
        PHONE => return Some(phone()),
        DATE_TIME => return Some(pad(DATE_TIME, "/", ":")),
        _ => {}
    }
    LETTER_LAYOUTS
        .iter()
        .find(|(layout, _)| *layout == id)
        .map(|(layout, rows)| letters(layout, rows))
}

/// What a field takes, for the layout it starts with.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum FieldKind {
    Text,
    Number,
    Phone,
    DateTime,
}

/// The layout a field starts with: a number pad for numbers, phone numbers,
/// dates and times, the letters otherwise.
#[uniffi::export]
pub fn initial_layout_id(kind: FieldKind) -> String {
    match kind {
        FieldKind::Text => LETTERS,
        FieldKind::Number => NUMBERS,
        FieldKind::Phone => PHONE,
        FieldKind::DateTime => DATE_TIME,
    }
    .to_owned()
}

/// A number pad: the digits as phones lay them out, 0 between `left` and
/// `right`, and a column of -, Space, Delete and Enter. Keys are large: four
/// to a row.
fn pad(id: &str, left: &str, right: &str) -> KeyboardLayout {
    let w = 2.5;
    let digits = |ds: &str| {
        ds.chars()
            .map(|d| text_key(&d.to_string(), w))
            .collect::<Vec<_>>()
    };
    let row = |ds: &str, last: Key| {
        let mut r = digits(ds);
        r.push(last);
        r
    };
    KeyboardLayout {
        id: id.to_owned(),
        rows: vec![
            row("123", text_key("-", w)),
            row("456", key("", KeyAction::Space, w)),
            row("789", key("", KeyAction::Backspace, w)),
            vec![
                text_key(left, w),
                text_key("0", w),
                text_key(right, w),
                key("", KeyAction::Enter, w),
            ],
        ],
    }
}

/// The phone pad: * and + beside 0; a long press gives # on * and the
/// characters phone numbers are written with on -.
fn phone() -> KeyboardLayout {
    let mut layout = pad(PHONE, "*", "+");
    for key in layout.rows.iter_mut().flatten() {
        if let KeyAction::Text { text } = &key.action {
            key.alternates = match text.as_str() {
                "*" => vec!["#".to_owned()],
                "-" => ["(", ")", "/", ".", ",", ";"].map(String::from).to_vec(),
                _ => Vec::new(),
            };
        }
    }
    layout
}

fn key(label: &str, action: KeyAction, width: f32) -> Key {
    Key {
        label: label.to_owned(),
        action,
        width,
        alternates: Vec::new(),
    }
}

/// A key that types `text`, with what a long press offers for it.
fn text_key(text: &str, width: f32) -> Key {
    let mut k = key(
        text,
        KeyAction::Text {
            text: text.to_owned(),
        },
        width,
    );
    let mut chars = text.chars();
    if let (Some(c), None) = (chars.next(), chars.next())
        && let Some((_, more)) = ALTERNATES.iter().find(|(base, _)| *base == c)
    {
        k.alternates = more.chars().map(String::from).collect();
    }
    k
}

fn text_keys(chars: &str) -> Vec<Key> {
    chars
        .chars()
        .map(|c| text_key(&c.to_string(), 1.0))
        .collect()
}

/// What a long press on a key offers, by the key's own character, in the
/// order most languages need them. [`builtin_layout_for`] moves a language's
/// own letters first.
const ALTERNATES: &[(char, &str)] = &[
    ('a', "áàâäãåāąăæ"),
    ('c', "çćč"),
    ('d', "ďđð"),
    ('e', "éèêëēėęě"),
    ('g', "ğģ"),
    ('i', "íìîïīįı"),
    ('k', "ķ"),
    ('l', "łļľ"),
    ('n', "ñńňņ"),
    ('o', "óòôöõøōőœ"),
    ('r', "ř"),
    ('s', "śšșşß"),
    ('t', "ťțþ"),
    ('u', "úùûüūůųű"),
    ('y', "ýÿ"),
    ('z', "źżž"),
    // Greek: tonos and dialytika.
    ('α', "ά"),
    ('ε', "έ"),
    ('η', "ή"),
    ('ι', "ίϊΐ"),
    ('ο', "ό"),
    ('υ', "ύϋΰ"),
    ('ω', "ώ"),
    // Russian.
    ('е', "ё"),
    // Punctuation and currency.
    ('?', "¿"),
    ('!', "¡"),
    ('"', "“”„«»"),
    ('\'', "‘’‚‹›"),
    ('-', "–—"),
    ('.', "…"),
    ('$', "€£¥¢"),
];

/// The letters each language writes that its layout has no key for, most
/// needed first: a long press offers these before the others.
const LANGUAGE_LETTERS: &[(&str, &str)] = &[
    ("cs", "áčďéěíňóřšťúůýž"),
    ("da", "é"),
    ("de", "é"),
    ("el", "άέήίόύώϊϋΐΰ"),
    ("es", "áéíóúü"),
    ("fi", "é"),
    ("fr", "éèêëàâæçîïôœùûüÿ"),
    ("hr", "čćđšž"),
    ("it", "àèéìíîòóùú"),
    ("lt", "ąčęėįšųūž"),
    ("lv", "āčēģīķļņšūž"),
    ("nb", "é"),
    ("nl", "éëïóöü"),
    ("pl", "ąćęłńóśźż"),
    ("pt", "ãáâàçéêíõóôú"),
    ("ro", "ăâîșț"),
    ("ru", "ё"),
    ("sl", "čšž"),
    ("sv", "é"),
    ("tr", "âîû"),
];

/// A built-in layout with the long-press offers ordered for `language` (a
/// BCP 47 tag such as "pl" or "pt-BR"): its own letters first.
#[uniffi::export]
pub fn builtin_layout_for(id: String, language: String) -> Option<KeyboardLayout> {
    let mut layout = builtin_layout(id)?;
    let base = language.split('-').next().unwrap_or_default();
    if let Some((_, letters)) = LANGUAGE_LETTERS.iter().find(|(tag, _)| *tag == base) {
        let rank = |s: &String| {
            let c = s.chars().next().unwrap_or_default();
            letters.chars().position(|l| l == c).unwrap_or(usize::MAX)
        };
        for key in layout.rows.iter_mut().flatten() {
            // A stable sort: the others keep their order after the language's own.
            key.alternates.sort_by_key(rank);
        }
    }
    Some(layout)
}

fn bottom_row(switch_label: &str, switch_to: &str) -> Vec<Key> {
    bottom_row_with(switch_label, switch_to, ",", ".")
}

/// The bottom row with other keys either side of Space.
fn bottom_row_with(switch_label: &str, switch_to: &str, left: &str, right: &str) -> Vec<Key> {
    vec![
        switch_key(switch_label, switch_to),
        text_key(left, 1.0),
        key("", KeyAction::Space, 5.0),
        text_key(right, 1.0),
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

fn switch_key(label: &str, to: &str) -> Key {
    key(
        label,
        KeyAction::SwitchLayout {
            layout_id: to.to_owned(),
        },
        1.5,
    )
}

/// Digits and the common symbols, laid out as most Android keyboards do.
fn symbols() -> KeyboardLayout {
    let mut third = vec![switch_key("=\\<", MORE_SYMBOLS)];
    third.extend(text_keys("*\"':;!?"));
    third.push(key("", KeyAction::Backspace, 1.5));
    KeyboardLayout {
        id: SYMBOLS.to_owned(),
        rows: vec![
            text_keys("1234567890"),
            text_keys("@#$_&-+()/"),
            third,
            bottom_row("ABC", LETTERS),
        ],
    }
}

/// The second page: the rest of ASCII, currencies and a few signs.
fn more_symbols() -> KeyboardLayout {
    let mut third = vec![switch_key("?123", SYMBOLS)];
    third.extend(text_keys("\\%©®™[]"));
    third.push(key("", KeyAction::Backspace, 1.5));
    KeyboardLayout {
        id: MORE_SYMBOLS.to_owned(),
        rows: vec![
            text_keys("~`|•√π÷×§∆"),
            text_keys("£¢€¥^°={}"),
            third,
            bottom_row_with("ABC", LETTERS, "<", ">"),
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

    fn texts(id: &str) -> Vec<String> {
        builtin_layout(id.to_owned())
            .unwrap()
            .rows
            .iter()
            .flatten()
            .filter_map(|k| match &k.action {
                KeyAction::Text { text } => Some(text.clone()),
                _ => None,
            })
            .collect()
    }

    fn row_width(row: &[Key]) -> f32 {
        row.iter().map(|k| k.width).sum()
    }

    #[test]
    fn every_printable_ascii_character_can_be_typed() {
        let typeable: Vec<String> = [LETTERS, SYMBOLS, MORE_SYMBOLS]
            .iter()
            .flat_map(|id| texts(id))
            .collect();
        for c in '!'..='~' {
            // Capitals are the letters with Shift.
            let wanted = c.to_ascii_lowercase().to_string();
            assert!(typeable.contains(&wanted), "nothing types {c:?}");
        }
    }

    #[test]
    fn the_symbol_pages_lead_to_each_other_and_back_to_letters() {
        let targets = |id: &str| -> Vec<String> {
            builtin_layout(id.to_owned())
                .unwrap()
                .rows
                .iter()
                .flatten()
                .filter_map(|k| match &k.action {
                    KeyAction::SwitchLayout { layout_id } => Some(layout_id.clone()),
                    _ => None,
                })
                .collect()
        };
        assert_eq!(targets(SYMBOLS), [MORE_SYMBOLS, LETTERS]);
        assert_eq!(targets(MORE_SYMBOLS), [SYMBOLS, LETTERS]);
        assert!(targets(LETTERS).contains(&SYMBOLS.to_owned()));
    }

    #[test]
    fn symbol_rows_are_no_wider_than_the_letters() {
        let widest = |id: &str| {
            builtin_layout(id.to_owned())
                .unwrap()
                .rows
                .iter()
                .map(|r| row_width(r))
                .fold(0.0, f32::max)
        };
        let letters = widest(LETTERS);
        assert!(widest(SYMBOLS) <= letters, "symbols would shrink every key");
        assert!(
            widest(MORE_SYMBOLS) <= letters,
            "more symbols would shrink every key"
        );
    }

    #[test]
    fn every_language_reaches_its_letters_by_a_long_press() {
        for (tag, letters) in LANGUAGE_LETTERS {
            for letter in letters.chars() {
                let offered = ALTERNATES.iter().any(|(_, more)| more.contains(letter));
                assert!(offered, "{tag}: no long press offers {letter}");
            }
        }
        // Every key the offers hang on exists on some layout.
        let all: Vec<String> = builtin_layout_ids()
            .into_iter()
            .flat_map(|id| texts(&id))
            .collect();
        for (base, _) in ALTERNATES {
            assert!(all.contains(&base.to_string()), "no key types {base:?}");
        }
    }

    #[test]
    fn a_language_gets_its_own_letters_first() {
        let offers = |language: &str, letter: &str| -> Vec<String> {
            builtin_layout_for(LETTERS.to_owned(), language.to_owned())
                .unwrap()
                .rows
                .iter()
                .flatten()
                .find(|k| k.label == letter)
                .unwrap()
                .alternates
                .clone()
        };
        assert_eq!(offers("pl", "e")[0], "ę");
        assert_eq!(offers("cs", "e")[..2], ["é", "ě"]);
        assert_eq!(offers("ro", "a")[..2], ["ă", "â"]);
        assert_eq!(offers("en-US", "e")[0], "é", "the general order elsewhere");
        assert_eq!(
            offers("pl", "e").len(),
            offers("en-US", "e").len(),
            "nothing is lost"
        );
        assert!(offers("pl", "q").is_empty());
        let greek = builtin_layout_for("greek".to_owned(), "el".to_owned()).unwrap();
        let alpha = greek
            .rows
            .iter()
            .flatten()
            .find(|k| k.label == "α")
            .unwrap();
        assert_eq!(alpha.alternates, ["ά"]);
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
    fn numeric_fields_start_on_a_number_pad() {
        assert_eq!(initial_layout_id(FieldKind::Text), LETTERS);
        assert_eq!(initial_layout_id(FieldKind::Number), NUMBERS);
        assert_eq!(initial_layout_id(FieldKind::Phone), PHONE);
        assert_eq!(initial_layout_id(FieldKind::DateTime), DATE_TIME);
    }

    #[test]
    fn number_pads_have_every_digit_once_and_the_keys_to_finish() {
        for (id, also) in [(NUMBERS, ",.-"), (PHONE, "*+-"), (DATE_TIME, "/:-")] {
            let layout = builtin_layout(id.to_owned()).unwrap();
            let mut found = texts(id);
            found.sort();
            let mut wanted: Vec<String> =
                ('0'..='9').chain(also.chars()).map(String::from).collect();
            wanted.sort();
            assert_eq!(found, wanted, "{id}");
            let actions: Vec<&KeyAction> =
                layout.rows.iter().flatten().map(|k| &k.action).collect();
            for needed in [KeyAction::Space, KeyAction::Backspace, KeyAction::Enter] {
                assert!(actions.contains(&&needed), "{id} lacks {needed:?}");
            }
            assert!(
                layout.rows.iter().all(|r| r.len() == 4),
                "{id}: four large keys a row"
            );
        }
        let hash = builtin_layout(PHONE.to_owned())
            .unwrap()
            .rows
            .iter()
            .flatten()
            .any(|k| k.alternates.contains(&"#".to_owned()));
        assert!(hash, "# is a long press away on the phone pad");
    }
}
