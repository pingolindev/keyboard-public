// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: Apache-2.0 OR MIT

//! The touch model: for one tap, how unlikely each letter of the alphabet is
//! to be the one the user meant, from where the finger landed. Distances are
//! measured in key sizes, so the app can give positions in any unit.

use crate::dictionary::Dictionary;
use crate::suggest::Params;
use crate::text::{is_key_char, key_of};

/// A key of the current layout as the app draws it, in any unit (pixels).
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct KeyCenter {
    /// What the key types.
    pub text: String,
    /// Center of the key.
    pub x: f32,
    pub y: f32,
    pub width: f32,
    pub height: f32,
}

/// A point, in the unit of the key centers.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct Point {
    pub x: f32,
    pub y: f32,
}

#[derive(Debug, Clone)]
struct KeyShape {
    letter: char,
    label: Option<u16>,
    x: f32,
    y: f32,
    w: f32,
    h: f32,
}

/// The letter keys of a layout, tied to one dictionary's alphabet.
#[derive(Debug, Clone, Default)]
pub struct Geometry {
    keys: Vec<KeyShape>,
}

impl Geometry {
    /// Keeps the keys that type one letter and have a usable size.
    pub fn new(keys: &[KeyCenter], dictionary: &Dictionary) -> Self {
        let keys = keys
            .iter()
            .filter(|k| {
                k.x.is_finite() && k.y.is_finite() && k.width.is_finite() && k.height.is_finite()
            })
            .filter(|k| k.width > 0.0 && k.height > 0.0)
            .filter_map(|k| match key_of(&k.text).as_slice() {
                [letter] if is_key_char(*letter) => Some(KeyShape {
                    letter: *letter,
                    label: dictionary.label_of(*letter),
                    x: k.x,
                    y: k.y,
                    w: k.width,
                    h: k.height,
                }),
                _ => None,
            })
            .collect();
        Geometry { keys }
    }

    pub fn is_empty(&self) -> bool {
        self.keys.is_empty()
    }

    /// For glide typing: where each letter of `dictionary` is typed (key
    /// centers by alphabet label, in key sizes) and the key size in the
    /// app's unit (the median key). None without keys.
    pub(crate) fn centers(
        &self,
        dictionary: &Dictionary,
    ) -> Option<(crate::glide::Keyboard, (f32, f32))> {
        if self.keys.is_empty() {
            return None;
        }
        let median = |mut v: Vec<f32>| {
            v.sort_by(f32::total_cmp);
            v[v.len() / 2]
        };
        let unit = (
            median(self.keys.iter().map(|k| k.w).collect()),
            median(self.keys.iter().map(|k| k.h).collect()),
        );
        let mut centers = vec![None; dictionary.alphabet().len()];
        for k in &self.keys {
            if let Some(slot) = k.label.and_then(|l| centers.get_mut(l as usize))
                && slot.is_none()
            {
                *slot = Some((k.x / unit.0, k.y / unit.1));
            }
        }
        Some((crate::glide::Keyboard { centers }, unit))
    }

    /// The share of the dictionary's letters that some key types: about 1
    /// for a layout made for the language, 0 for another script.
    pub fn coverage(&self, dictionary: &Dictionary) -> f32 {
        let a = dictionary.alphabet().len();
        if a == 0 {
            return 1.0;
        }
        let mut typed = vec![false; a];
        for key in &self.keys {
            if let Some(l) = key.label {
                typed[l as usize] = true;
            }
        }
        typed.iter().filter(|t| **t).count() as f32 / a as f32
    }

    /// Fills `out` (one cost per alphabet letter) for one typed key letter.
    /// The typed letter costs nothing; others cost more the farther their key
    /// is from the tap, up to `params.sub_far` for a letter no key is near.
    pub fn fill_costs(
        &self,
        typed: char,
        typed_label: Option<u16>,
        position: Option<Point>,
        params: &Params,
        out: &mut [f32],
    ) {
        out.fill(params.sub_far);
        let typed_keys = || self.keys.iter().filter(|k| k.letter == typed);
        match position.filter(|p| p.x.is_finite() && p.y.is_finite()) {
            Some(p) if !self.keys.is_empty() => {
                let two_s2 = 2.0 * params.spatial_sigma * params.spatial_sigma;
                let energy = |k: &KeyShape| {
                    let dx = (p.x - k.x) / k.w;
                    let dy = (p.y - k.y) / k.h;
                    (dx * dx + dy * dy) / two_s2
                };
                let nearest = self.keys.iter().map(energy).fold(f32::INFINITY, f32::min);
                // Relative to the typed key; to the nearest key when no key
                // types that letter (a long-press accent, say).
                let base = typed_keys().map(energy).fold(f32::INFINITY, f32::min);
                let base = if base.is_finite() { base } else { nearest };
                for k in &self.keys {
                    if let Some(l) = k.label {
                        let cost = (energy(k) - base).clamp(0.0, params.sub_far);
                        let slot = &mut out[l as usize];
                        *slot = slot.min(cost);
                    }
                }
            }
            _ => {
                // No position (a screen reader, a hardware keyboard): the keys
                // next to the typed one are likelier than the rest.
                for t in typed_keys() {
                    for k in &self.keys {
                        let Some(l) = k.label else { continue };
                        let dx = (k.x - t.x) / t.w;
                        let dy = (k.y - t.y) / t.h;
                        if dx * dx + dy * dy <= 1.5 * 1.5 {
                            let slot = &mut out[l as usize];
                            *slot = slot.min(params.sub_near);
                        }
                    }
                }
            }
        }
        if let Some(l) = typed_label {
            out[l as usize] = 0.0;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::dictionary::tests::small;

    /// Three keys in a row, 100 wide and 150 tall: t, h, e (made up, so the
    /// distances are easy).
    fn keys() -> Vec<KeyCenter> {
        ["t", "h", "e"]
            .iter()
            .enumerate()
            .map(|(i, t)| KeyCenter {
                text: (*t).into(),
                x: 50.0 + 100.0 * i as f32,
                y: 75.0,
                width: 100.0,
                height: 150.0,
            })
            .collect()
    }

    fn costs(typed: char, position: Option<Point>) -> (Vec<f32>, Dictionary) {
        let d = Dictionary::from_bytes(&small()).unwrap();
        let g = Geometry::new(&keys(), &d);
        let mut out = vec![0.0; d.alphabet().len()];
        g.fill_costs(
            typed,
            d.label_of(typed),
            position,
            &Params::default(),
            &mut out,
        );
        (out, d)
    }

    #[test]
    fn a_tap_on_the_key_center_prices_neighbors_by_distance() {
        let (out, d) = costs('h', Some(Point { x: 150.0, y: 75.0 }));
        let at = |c| out[d.label_of(c).unwrap() as usize];
        assert_eq!(at('h'), 0.0);
        // One key width away: 1 / (2 sigma²) nats.
        let sigma = Params::default().spatial_sigma;
        let one_key = 1.0 / (2.0 * sigma * sigma);
        assert!((at('t') - one_key).abs() < 1e-4, "{}", at('t'));
        assert!((at('e') - one_key).abs() < 1e-4);
        // A letter with no key costs the most.
        assert_eq!(at('w'), Params::default().sub_far);
    }

    #[test]
    fn a_tap_between_keys_makes_both_equally_likely() {
        let (out, d) = costs('h', Some(Point { x: 200.0, y: 75.0 }));
        let at = |c| out[d.label_of(c).unwrap() as usize];
        assert_eq!(at('h'), 0.0);
        assert!(at('e') < 1e-4);
        assert!(at('t') > 3.0);
    }

    #[test]
    fn without_a_position_neighbors_cost_sub_near() {
        let (out, d) = costs('t', None);
        let at = |c| out[d.label_of(c).unwrap() as usize];
        let p = Params::default();
        assert_eq!(at('t'), 0.0);
        assert_eq!(at('h'), p.sub_near);
        assert_eq!(at('e'), p.sub_far);
    }

    #[test]
    fn keys_that_are_not_single_letters_are_ignored() {
        let d = Dictionary::from_bytes(&small()).unwrap();
        let mut k = keys();
        k.push(KeyCenter {
            text: ",".into(),
            x: 0.0,
            y: 0.0,
            width: 1.0,
            height: 1.0,
        });
        k.push(KeyCenter {
            text: "ab".into(),
            x: 0.0,
            y: 0.0,
            width: 1.0,
            height: 1.0,
        });
        k.push(KeyCenter {
            text: "w".into(),
            x: f32::NAN,
            y: 0.0,
            width: 1.0,
            height: 1.0,
        });
        k.push(KeyCenter {
            text: "w".into(),
            x: 0.0,
            y: 0.0,
            width: 0.0,
            height: 1.0,
        });
        assert_eq!(Geometry::new(&k, &d).keys.len(), 3);
    }
}
