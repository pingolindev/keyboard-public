// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: Apache-2.0 OR MIT

//! Glide typing: the finger slides through the letters of a word without
//! lifting. The decoder follows SHARK2 (Kristensson and Zhai, 2004): a word is
//! a candidate when the path passes near its keys in order, from its first
//! letter to its last. Candidates are ranked by how close the path stays to
//! their keys, how alike the path and the word's own path look once both are
//! scaled to the same size, their lengths, and how likely the word is here.
//!
//! Distances are in key sizes, so the app can give positions in pixels.

use crate::dictionary::Dictionary;
use crate::suggest::{Context, Params, Source, context_bonus};
use crate::touch::Point;
use crate::wordlist::FLAG_OFFENSIVE;
use std::collections::HashMap;

/// How the decoder weighs things. Tuned on simulated paths (see the replay example).
#[derive(Debug, Clone, PartialEq)]
pub struct GlideParams {
    /// A letter's key must be this close to the path, in key sizes.
    pub pass_radius: f32,
    /// The first and last letters' keys must be this close to where the path
    /// starts and ends.
    pub end_radius: f32,
    /// Spread of the mean distance between the path and the word's own path
    /// through its keys, both ways, in key sizes.
    pub location_sigma: f32,
    /// Spread of the distance between where the path starts and ends and
    /// the word's first and last keys.
    pub end_sigma: f32,
    /// Spread of the distance between the two paths once scaled.
    pub shape_sigma: f32,
    /// Spread of the log ratio of the two paths' lengths.
    pub length_sigma: f32,
    /// How much the language model counts.
    pub language_weight: f32,
    /// Nodes of the dictionary visited at most for one path.
    pub max_visits: usize,
}

impl Default for GlideParams {
    /// Tuned with `replay --glide`.
    fn default() -> Self {
        GlideParams {
            pass_radius: 1.4,
            end_radius: 1.4,
            location_sigma: 0.13,
            end_sigma: 0.38,
            shape_sigma: 0.08,
            length_sigma: 0.3,
            language_weight: 0.6,
            max_visits: 500_000,
        }
    }
}

/// Points along the path every this many key sizes.
const STEP: f32 = 0.2;
/// The two paths are compared at this many points.
const SHAPE_POINTS: usize = 32;
const MAX_LETTERS: usize = 24;

/// A decoded word and its log score; higher is better.
#[derive(Debug, Clone, PartialEq)]
pub struct Glided {
    pub text: String,
    pub score: f32,
}

/// Where each letter's key is, in key sizes, and the key size used.
pub struct Keyboard {
    /// By alphabet label of one dictionary.
    pub centers: Vec<Option<(f32, f32)>>,
}

/// The words the path in `points` (in the unit of the key centers) may
/// spell, best first. `sources[0]` is the main language.
pub fn decode(
    sources: &[Source<'_>],
    params: &Params,
    glide: &GlideParams,
    context: &Context,
    points: &[Point],
    block_offensive: bool,
    max_results: usize,
) -> Vec<Glided> {
    let mut best: HashMap<String, f32> = HashMap::new();
    for source in sources {
        let Some((keyboard, unit)) = source.geometry.centers(source.dictionary) else {
            continue;
        };
        let path = resample(&to_units(points, unit), STEP);
        if path.len() < 2 {
            continue;
        }
        for (word, score) in decode_one(source.dictionary, &keyboard, glide, &path, block_offensive)
        {
            let logp = score.1;
            let lm = logp + context_bonus(sources, context, params, &word, logp);
            let total = score.0 + glide.language_weight * lm + source.bonus;
            let slot = best.entry(word).or_insert(f32::NEG_INFINITY);
            if total > *slot {
                *slot = total;
            }
        }
    }
    let mut out: Vec<Glided> = best
        .into_iter()
        .map(|(text, score)| Glided { text, score })
        .collect();
    out.sort_by(|a, b| {
        b.score
            .total_cmp(&a.score)
            .then_with(|| a.text.cmp(&b.text))
    });
    out.truncate(max_results);
    out
}

/// Points in key sizes.
fn to_units(points: &[Point], unit: (f32, f32)) -> Vec<(f32, f32)> {
    points
        .iter()
        .filter(|p| p.x.is_finite() && p.y.is_finite())
        .map(|p| (p.x / unit.0, p.y / unit.1))
        .collect()
}

fn dist(a: (f32, f32), b: (f32, f32)) -> f32 {
    ((a.0 - b.0).powi(2) + (a.1 - b.1).powi(2)).sqrt()
}

fn length(path: &[(f32, f32)]) -> f32 {
    path.windows(2).map(|w| dist(w[0], w[1])).sum()
}

/// The path as points `step` apart along it (the first and last kept).
fn resample(path: &[(f32, f32)], step: f32) -> Vec<(f32, f32)> {
    let Some(&first) = path.first() else {
        return Vec::new();
    };
    let mut out = vec![first];
    let mut carry = 0.0;
    for w in path.windows(2) {
        let (a, b) = (w[0], w[1]);
        let d = dist(a, b);
        if d == 0.0 {
            continue;
        }
        let mut t = step - carry;
        while t <= d {
            out.push((a.0 + (b.0 - a.0) * t / d, a.1 + (b.1 - a.1) * t / d));
            t += step;
        }
        carry = d - (t - step);
        if out.len() > 2_000 {
            break;
        }
    }
    let last = *path.last().unwrap_or(&first);
    if out.last().is_some_and(|p| dist(*p, last) > 1e-3) {
        out.push(last);
    }
    out
}

/// `n` points evenly along the path.
fn even(path: &[(f32, f32)], n: usize) -> Vec<(f32, f32)> {
    let total = length(path);
    if path.len() < 2 || total == 0.0 {
        return vec![path.first().copied().unwrap_or((0.0, 0.0)); n];
    }
    let step = total / (n - 1) as f32;
    let mut out = resample(path, step);
    out.truncate(n);
    while out.len() < n {
        out.push(*path.last().unwrap_or(&(0.0, 0.0)));
    }
    out
}

/// The path centered on its middle and scaled to a box of side 1.
fn normalized(path: &[(f32, f32)]) -> Vec<(f32, f32)> {
    let n = path.len() as f32;
    let (cx, cy) = path
        .iter()
        .fold((0.0, 0.0), |s, p| (s.0 + p.0 / n, s.1 + p.1 / n));
    let (minx, maxx) = path
        .iter()
        .fold((f32::MAX, f32::MIN), |s, p| (s.0.min(p.0), s.1.max(p.0)));
    let (miny, maxy) = path
        .iter()
        .fold((f32::MAX, f32::MIN), |s, p| (s.0.min(p.1), s.1.max(p.1)));
    let scale = (maxx - minx).max(maxy - miny).max(1e-3);
    path.iter()
        .map(|p| ((p.0 - cx) / scale, (p.1 - cy) / scale))
        .collect()
}

/// For each complete word the path may spell: (spelling, (path score, log P)).
fn decode_one(
    dict: &Dictionary,
    keyboard: &Keyboard,
    glide: &GlideParams,
    path: &[(f32, f32)],
    block_offensive: bool,
) -> Vec<(String, (f32, f32))> {
    let start = path[0];
    let end = *path.last().unwrap_or(&start);
    let gesture_points = even(path, SHAPE_POINTS);
    let gesture_shape = normalized(&gesture_points);
    let gesture_length = length(path).max(0.5);
    // Path length from the start to each point, to keep the first letter near the start.
    let mut walked = vec![0.0f32; path.len()];
    for i in 1..path.len() {
        walked[i] = walked[i - 1] + dist(path[i - 1], path[i]);
    }
    let total = walked[path.len() - 1];

    struct Frame {
        node: u32,
        at: usize,
        depth: usize,
    }
    let mut out = Vec::new();
    let mut letters: Vec<u16> = Vec::with_capacity(MAX_LETTERS);
    // Iterative depth-first walk; `letters` holds the labels down to the frame.
    let mut stack: Vec<(Frame, u16)> = Vec::new();
    for child in dict.children(0) {
        stack.push((
            Frame {
                node: child,
                at: 0,
                depth: 1,
            },
            dict.label(child),
        ));
    }
    let mut visits = 0usize;
    while let Some((frame, label)) = stack.pop() {
        visits += 1;
        if visits > glide.max_visits {
            break;
        }
        let Some(key) = keyboard.centers.get(label as usize).copied().flatten() else {
            continue;
        };
        letters.truncate(frame.depth - 1);
        let first = frame.depth == 1;
        let repeat = letters.last() == Some(&label);
        // Where the path comes near this key, at or after the previous letter.
        let found = if first {
            if dist(key, start) > glide.end_radius {
                continue;
            }
            Some(0)
        } else if repeat {
            Some(frame.at)
        } else {
            (frame.at..path.len()).find(|&j| dist(path[j], key) <= glide.pass_radius)
        };
        let Some(mut at) = found else { continue };
        if first && walked[at] > glide.end_radius {
            continue;
        }
        // Slide to where the path is closest to the key.
        while at + 1 < path.len() && dist(path[at + 1], key) < dist(path[at], key) {
            at += 1;
        }

        letters.push(label);

        let entries = dict.entries(frame.node);
        let near_end =
            dist(key, end) <= glide.end_radius && total - walked[at] <= glide.end_radius + 0.5;
        if !entries.is_empty() && near_end {
            let ideal: Vec<(f32, f32)> = letters
                .iter()
                .filter_map(|l| keyboard.centers.get(*l as usize).copied().flatten())
                .collect();
            let ideal_length = length(&ideal).max(0.5);
            let ideal_points = even(&ideal, SHAPE_POINTS);
            let shape = shape_distance(&gesture_shape, &ideal_points);
            let location = location_distance(&gesture_points, &ideal_points);
            let ratio = (gesture_length / ideal_length).ln();
            let first_key = ideal.first().copied().unwrap_or(key);
            let ends = dist(first_key, start).powi(2) + dist(key, end).powi(2);
            let path_score = -0.5 * (location / glide.location_sigma).powi(2)
                - 0.5 * ends / glide.end_sigma.powi(2)
                - 0.5 * (shape / glide.shape_sigma).powi(2)
                - 0.5 * (ratio / glide.length_sigma).powi(2);
            let key_chars: Vec<char> = letters
                .iter()
                .map(|l| dict.alphabet()[*l as usize])
                .collect();
            for id in entries {
                let entry = dict.entry(id);
                if block_offensive && entry.word_flags() & FLAG_OFFENSIVE != 0 {
                    continue;
                }
                let spelling = dict.spelling(id, &key_chars);
                out.push((
                    spelling,
                    (path_score, dict.log_probability(entry.frequency)),
                ));
            }
        }
        if frame.depth < MAX_LETTERS {
            for child in dict.children(frame.node) {
                stack.push((
                    Frame {
                        node: child,
                        at,
                        depth: frame.depth + 1,
                    },
                    dict.label(child),
                ));
            }
        }
    }
    out
}

/// Mean distance between the gesture's normalized shape and the word's.
fn shape_distance(gesture: &[(f32, f32)], ideal: &[(f32, f32)]) -> f32 {
    let word = normalized(ideal);
    gesture
        .iter()
        .zip(&word)
        .map(|(a, b)| dist(*a, *b))
        .sum::<f32>()
        / gesture.len().max(1) as f32
}

/// How far the two paths keep from each other where they are: the mean
/// distance from each point of one to the nearest point of the other, both
/// ways, in key sizes. A detour the word does not make costs here.
fn location_distance(a: &[(f32, f32)], b: &[(f32, f32)]) -> f32 {
    let one_way = |from: &[(f32, f32)], to: &[(f32, f32)]| {
        from.iter()
            .map(|p| {
                to.iter()
                    .map(|q| dist(*p, *q))
                    .fold(f32::INFINITY, f32::min)
            })
            .sum::<f32>()
            / from.len().max(1) as f32
    };
    0.5 * (one_way(a, b) + one_way(b, a))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn resampling_keeps_both_ends_and_spacing() {
        let path = resample(&[(0.0, 0.0), (1.0, 0.0), (1.0, 1.0)], 0.25);
        assert_eq!(path.first(), Some(&(0.0, 0.0)));
        assert_eq!(path.last(), Some(&(1.0, 1.0)));
        for w in path.windows(2) {
            assert!(dist(w[0], w[1]) <= 0.2501, "{w:?}");
        }
        assert_eq!(even(&path, 9).len(), 9);
    }

    #[test]
    fn a_shape_matches_itself_at_any_size() {
        let a = normalized(&even(&[(0.0, 0.0), (2.0, 0.0), (2.0, 1.0)], SHAPE_POINTS));
        let b = even(&[(5.0, 5.0), (9.0, 5.0), (9.0, 7.0)], SHAPE_POINTS);
        assert!(shape_distance(&a, &b) < 1e-3);
        let c = even(&[(5.0, 5.0), (5.0, 9.0)], SHAPE_POINTS);
        assert!(shape_distance(&a, &c) > 0.2);
        // Location is not scaled: the same shape elsewhere is far.
        let here = even(&[(0.0, 0.0), (2.0, 0.0)], SHAPE_POINTS);
        let there = even(&[(0.0, 3.0), (2.0, 3.0)], SHAPE_POINTS);
        assert!((location_distance(&here, &there) - 3.0).abs() < 1e-3);
    }
}
