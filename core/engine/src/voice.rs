// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: Apache-2.0 OR MIT

//! Voice typing: whisper.cpp turns 16 kHz mono samples into text. Built with
//! the `voice` feature. Without it every model fails to open with
//! [`VoiceError::Unavailable`], so the bindings are the same either way.

use std::sync::{Arc, Mutex};

/// Longest audio taken at once: two minutes.
pub const MAX_SAMPLES: usize = 16_000 * 120;

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Error)]
pub enum VoiceError {
    /// This build has no voice typing.
    Unavailable,
    /// The file is not a model whisper can read.
    InvalidModel,
    /// The audio could not be turned into text.
    Failed,
}

impl std::fmt::Display for VoiceError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            VoiceError::Unavailable => "voice typing is not in this build",
            VoiceError::InvalidModel => "not a voice model",
            VoiceError::Failed => "transcription failed",
        })
    }
}

impl std::error::Error for VoiceError {}

/// Whether this build can type by voice.
#[uniffi::export]
pub fn voice_available() -> bool {
    cfg!(feature = "voice")
}

/// A loaded voice model. Transcriptions run one at a time.
#[derive(uniffi::Object)]
pub struct VoiceModel {
    inner: Mutex<imp::Handle>,
    multilingual: bool,
}

impl std::fmt::Debug for VoiceModel {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("VoiceModel")
            .field("multilingual", &self.multilingual)
            .finish()
    }
}

#[uniffi::export]
impl VoiceModel {
    /// Opens the model file at `path`.
    #[uniffi::constructor]
    pub fn open(path: String) -> Result<Arc<Self>, VoiceError> {
        let handle = imp::open(&path)?;
        let multilingual = imp::multilingual(&handle);
        Ok(Arc::new(VoiceModel {
            inner: Mutex::new(handle),
            multilingual,
        }))
    }

    /// Whether the model knows many languages (or only English).
    pub fn is_multilingual(&self) -> bool {
        self.multilingual
    }

    /// The text spoken in `pcm`: 16 kHz mono 16-bit samples, little-endian
    /// (what Android records). `language`: a whisper language code ("en",
    /// "de"), or None to detect it.
    pub fn transcribe_pcm16(
        &self,
        pcm: Vec<u8>,
        language: Option<String>,
        threads: u32,
    ) -> Result<String, VoiceError> {
        let samples: Vec<f32> = pcm
            .chunks_exact(2)
            .take(MAX_SAMPLES)
            .map(|b| i16::from_le_bytes([b[0], b[1]]) as f32 / 32768.0)
            .collect();
        self.transcribe(samples, language, threads)
    }
}

impl VoiceModel {
    /// The same for samples already in -1..1.
    pub fn transcribe(
        &self,
        samples: Vec<f32>,
        language: Option<String>,
        threads: u32,
    ) -> Result<String, VoiceError> {
        if samples.is_empty() {
            return Ok(String::new());
        }
        let samples = &samples[..samples.len().min(MAX_SAMPLES)];
        let language = if self.multilingual {
            language
        } else {
            Some("en".to_owned())
        };
        let mut handle = self.inner.lock().map_err(|_| VoiceError::Failed)?;
        let text = imp::transcribe(
            &mut handle,
            samples,
            language.as_deref(),
            threads.clamp(1, 8),
        )?;
        Ok(text.trim().to_owned())
    }
}

#[cfg(feature = "voice")]
mod imp {
    use super::VoiceError;
    use std::ffi::{CStr, CString};
    use std::os::raw::{c_char, c_float, c_int};

    #[repr(C)]
    pub struct PkVoice {
        _private: [u8; 0],
    }

    unsafe extern "C" {
        fn pk_voice_open(path: *const c_char) -> *mut PkVoice;
        fn pk_voice_is_multilingual(voice: *const PkVoice) -> c_int;
        fn pk_voice_transcribe(
            voice: *mut PkVoice,
            samples: *const c_float,
            n: c_int,
            language: *const c_char,
            threads: c_int,
        ) -> *mut c_char;
        fn pk_voice_free_text(text: *mut c_char);
        fn pk_voice_close(voice: *mut PkVoice);
    }

    /// Owns a whisper context; the Mutex around it keeps calls one at a time.
    pub struct Handle(*mut PkVoice);

    // SAFETY: the context is only used through the Mutex in VoiceModel.
    unsafe impl Send for Handle {}

    impl Drop for Handle {
        fn drop(&mut self) {
            // SAFETY: the pointer came from pk_voice_open and is closed once.
            unsafe { pk_voice_close(self.0) }
        }
    }

    pub fn open(path: &str) -> Result<Handle, VoiceError> {
        let path = CString::new(path).map_err(|_| VoiceError::InvalidModel)?;
        // SAFETY: a valid C string; a null result means the file was refused.
        let ptr = unsafe { pk_voice_open(path.as_ptr()) };
        if ptr.is_null() {
            Err(VoiceError::InvalidModel)
        } else {
            Ok(Handle(ptr))
        }
    }

    pub fn multilingual(handle: &Handle) -> bool {
        // SAFETY: a live context.
        unsafe { pk_voice_is_multilingual(handle.0) != 0 }
    }

    pub fn transcribe(
        handle: &mut Handle,
        samples: &[f32],
        language: Option<&str>,
        threads: u32,
    ) -> Result<String, VoiceError> {
        let language = language
            .map(CString::new)
            .transpose()
            .map_err(|_| VoiceError::Failed)?;
        let n = c_int::try_from(samples.len()).map_err(|_| VoiceError::Failed)?;
        // SAFETY: the samples outlive the call; the result is freed below.
        let text = unsafe {
            pk_voice_transcribe(
                handle.0,
                samples.as_ptr(),
                n,
                language.as_ref().map_or(std::ptr::null(), |l| l.as_ptr()),
                threads as c_int,
            )
        };
        if text.is_null() {
            return Err(VoiceError::Failed);
        }
        // SAFETY: a NUL-terminated string from pk_voice_transcribe.
        let out = unsafe { CStr::from_ptr(text) }
            .to_string_lossy()
            .into_owned();
        // SAFETY: freed once, by the function that matches its allocation.
        unsafe { pk_voice_free_text(text) };
        Ok(out)
    }
}

#[cfg(not(feature = "voice"))]
mod imp {
    use super::VoiceError;

    pub struct Handle;

    pub fn open(_path: &str) -> Result<Handle, VoiceError> {
        Err(VoiceError::Unavailable)
    }

    pub fn multilingual(_handle: &Handle) -> bool {
        false
    }

    pub fn transcribe(
        _handle: &mut Handle,
        _samples: &[f32],
        _language: Option<&str>,
        _threads: u32,
    ) -> Result<String, VoiceError> {
        Err(VoiceError::Unavailable)
    }
}

/// 16-bit PCM mono 16 kHz WAV bytes as samples (for tests and the example).
pub fn read_wav(bytes: &[u8]) -> Option<Vec<f32>> {
    if bytes.len() < 12 || &bytes[0..4] != b"RIFF" || &bytes[8..12] != b"WAVE" {
        return None;
    }
    let mut i = 12;
    let mut format_ok = false;
    while i + 8 <= bytes.len() {
        let id = &bytes[i..i + 4];
        let size = u32::from_le_bytes(bytes[i + 4..i + 8].try_into().ok()?) as usize;
        let body = bytes.get(i + 8..i + 8 + size)?;
        if id == b"fmt " && body.len() >= 16 {
            let format = u16::from_le_bytes([body[0], body[1]]);
            let channels = u16::from_le_bytes([body[2], body[3]]);
            let rate = u32::from_le_bytes([body[4], body[5], body[6], body[7]]);
            let bits = u16::from_le_bytes([body[14], body[15]]);
            format_ok = format == 1 && channels == 1 && rate == 16_000 && bits == 16;
        } else if id == b"data" {
            if !format_ok {
                return None;
            }
            return Some(
                body.chunks_exact(2)
                    .map(|b| i16::from_le_bytes([b[0], b[1]]) as f32 / 32768.0)
                    .collect(),
            );
        }
        i += 8 + size + (size & 1);
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_missing_file_is_refused() {
        let err = VoiceModel::open("/nonexistent/model.bin".into()).unwrap_err();
        let expected = if voice_available() {
            VoiceError::InvalidModel
        } else {
            VoiceError::Unavailable
        };
        assert_eq!(err, expected);
    }

    /// Transcribes a WAV file with a real model when both are given:
    /// PK_VOICE_MODEL=... PK_VOICE_WAV=... cargo test --features voice -- --ignored
    #[test]
    #[ignore]
    fn transcribes_a_sample() {
        let (Ok(model), Ok(wav)) = (
            std::env::var("PK_VOICE_MODEL"),
            std::env::var("PK_VOICE_WAV"),
        ) else {
            return;
        };
        let samples = crate::voice::read_wav(&std::fs::read(wav).unwrap()).unwrap();
        let text = VoiceModel::open(model)
            .unwrap()
            .transcribe(samples, Some("en".into()), 4)
            .unwrap();
        assert!(!text.is_empty());
    }
}
