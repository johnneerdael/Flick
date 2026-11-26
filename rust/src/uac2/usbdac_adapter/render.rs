//! The render thread: the pull adapter between Flick's engine and the library's
//! push stream.
//!
//! The engine renders audio when asked (`audio_callback_unpaused`); the library
//! takes audio written to it and plays it. This loop asks the stream how much it
//! wants, renders that, and writes it. Pause, resume and the engine's output
//! flush are passed on to the stream as they happen, and software gain and the
//! "something live is applied" hint go to the stream too, so the library applies
//! the gain as each transfer is prepared and picks the ring depth.

use super::state::{stop_requested, ActiveStream};
use super::DsdTransportMode;
use crate::audio::commands::AudioEvent;
use crate::audio::engine::{audio_callback_unpaused, AudioCallbackData};
use crate::dev_eprintln;
use crossbeam_channel::Sender;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread;
use std::time::{Duration, Instant};

/// How much one render call produces.
pub(crate) const RENDER_CHUNK_MS: usize = 10;
/// How long the loop waits when the ring wants nothing.
pub(crate) const RENDER_POLL_MS: u64 = 2;

/// What the loop needs to know about the stream it feeds.
#[derive(Debug, Clone, Copy)]
pub(crate) struct RenderPlan {
    pub channels: usize,
    /// The unit of the stream's frame counters.
    pub counter_rate_hz: u32,
    pub dsd_transport: DsdTransportMode,
    /// Bytes `writable_bytes` counts per ring sample: the wire subslot for
    /// PCM and DoP, one for native DSD (a ring sample is a DSD byte).
    pub bytes_per_ring_sample: usize,
}

impl RenderPlan {
    /// The wire carries a DSD bitstream: it is never processed, so nothing in it
    /// can be changed live and no gain is applied.
    pub(crate) fn is_raw_bitstream(&self) -> bool {
        self.dsd_transport != DsdTransportMode::None
    }

    pub(crate) fn chunk_frames(&self) -> usize {
        ((self.counter_rate_hz as usize * RENDER_CHUNK_MS) / 1_000).max(1)
    }

    pub(crate) fn chunk_samples(&self) -> usize {
        self.chunk_frames().saturating_mul(self.channels)
    }
}

/// The software gain the library should apply: the engine's gain when the engine
/// runs in passthrough (the DSP chain has already applied it otherwise), never
/// for a bitstream, and never anything the library would refuse.
pub(crate) fn desired_software_gain(raw_bitstream: bool, passthrough: bool, engine_gain: f32) -> f32 {
    if raw_bitstream || !passthrough || !engine_gain.is_finite() {
        1.0
    } else {
        engine_gain.clamp(0.0, 16.0)
    }
}

/// Whether the engine applies something to the audio as it renders it (EQ, DSP,
/// crossfade, pitch, tuning), which the library cannot see: the stream keeps its
/// ring shallow so a change is heard sooner.
pub(crate) fn host_processing(raw_bitstream: bool, passthrough: bool) -> bool {
    !raw_bitstream && !passthrough
}

/// Full-scale `i32` samples from the engine's `f32`: what the library's ring
/// takes for PCM. A bitstream (DoP words, native DSD bytes) travels as the bit
/// pattern of the `f32`, which is how the engine carries it.
pub(crate) fn convert_chunk(rendered: &[f32], out: &mut [i32], raw_bitstream: bool) {
    if raw_bitstream {
        for (dst, src) in out.iter_mut().zip(rendered) {
            *dst = src.to_bits() as i32;
        }
    } else {
        for (dst, src) in out.iter_mut().zip(rendered) {
            let scaled = (f64::from(src.clamp(-1.0, 1.0)) * 2_147_483_647.0) as i64;
            *dst = scaled.clamp(i64::from(i32::MIN), i64::from(i32::MAX)) as i32;
        }
    }
}

/// Why the loop ended.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum RenderEnd {
    Stopped,
    /// The library reported that the stream failed (its events say why). A failed
    /// stream asks for nothing more, so this is how the loop learns of it when
    /// the ring is full.
    StreamFailed,
    /// The stream refused a write: it closed or failed.
    WriteFailed(String),
}

/// Carries out the engine's latest output flush request, if the stream has not
/// seen it yet. Returns true if it flushed.
fn apply_output_flush(
    callback_data: &AudioCallbackData,
    active: &ActiveStream,
    applied: &mut u64,
    pending: Option<&mut Vec<i32>>,
) -> bool {
    let requested = callback_data.output_flush_generation();
    if requested == *applied {
        return false;
    }
    // A failed flush leaves the stream failed or closed, which the next write
    // reports; there is nothing more to do about it here.
    let _ = active.stream.flush();
    if let Some(pending) = pending {
        pending.clear();
    }
    *applied = requested;
    active.set_applied_flush_generation(requested);
    dev_eprintln!("[USB] output flush requested by the engine (generation {requested})");
    true
}

pub(crate) fn run_render_loop(
    callback_data: Arc<AudioCallbackData>,
    event_tx: Sender<AudioEvent>,
    active: Arc<ActiveStream>,
    plan: RenderPlan,
    stop: Arc<AtomicBool>,
) -> RenderEnd {
    let raw_bitstream = plan.is_raw_bitstream();
    let chunk_samples = plan.chunk_samples();
    let chunk_bytes = chunk_samples * plan.bytes_per_ring_sample;
    // The ring holds a start threshold of idle audio, not the deep target: the
    // first audio of whatever plays next must not sit behind a quarter second
    // of silence.
    let start_threshold_frames = u64::from(plan.counter_rate_hz) / 10;
    let mut render_buffer = vec![0.0f32; chunk_samples];
    let mut samples = vec![0i32; chunk_samples];
    // What the ring did not take of the last chunk; written before anything new
    // is rendered, so no audio is ever dropped for lack of room.
    let mut pending: Vec<i32> = Vec::new();
    let mut pending_offset = 0usize;
    let chunk_deadline = Duration::from_millis(RENDER_CHUNK_MS as u64);
    let mut paused = false;
    let mut gain = 1.0f32;
    let mut processing = false;

    // Starvation fills must match the wire format: 0x00 bytes are DC on a
    // native DSD wire and zero words break DoP marker sync.
    match plan.dsd_transport {
        DsdTransportMode::DoP => callback_data.set_dop_wire_silence(true),
        DsdTransportMode::Native => callback_data.set_dsd_wire_silence(true),
        DsdTransportMode::None => {}
    }

    let mut applied_flush_generation = callback_data.output_flush_generation();
    active.set_applied_flush_generation(applied_flush_generation);

    let end = 'render: loop {
        if stop_requested(&stop) {
            break RenderEnd::Stopped;
        }
        if active.failed.load(Ordering::Acquire) {
            break RenderEnd::StreamFailed;
        }
        let cycle_start = Instant::now();

        // A seek, a stop, or a new source replacing a live or paused one: drop
        // what is queued so the stream cannot play stale audio. This also runs
        // while paused, when no chunk is being rendered.
        if apply_output_flush(&callback_data, &active, &mut applied_flush_generation, Some(&mut pending)) {
            pending_offset = 0;
        }

        // Pause holds the stream on wire silence with the ring untouched, so
        // resume continues exactly where the pause landed.
        let engine_paused = callback_data.is_paused();
        if engine_paused != paused {
            paused = engine_paused;
            let _ = if paused {
                active.stream.pause()
            } else {
                active.stream.play()
            };
        }
        if paused {
            thread::sleep(Duration::from_millis(RENDER_POLL_MS));
            continue;
        }

        // Software gain is the library's job (applied as each transfer is
        // prepared), so a change is heard within the bus latency; the engine's
        // live processing only changes how deep the ring is kept.
        let passthrough = callback_data.is_passthrough();
        let wanted_gain = desired_software_gain(raw_bitstream, passthrough, callback_data.get_gain());
        if wanted_gain.to_bits() != gain.to_bits() {
            if active.stream.set_software_gain(wanted_gain).is_ok() {
                gain = wanted_gain;
            }
        }
        let wanted_processing = host_processing(raw_bitstream, passthrough);
        if wanted_processing != processing {
            processing = wanted_processing;
            active.stream.set_host_processing(processing);
        }

        if pending_offset < pending.len() {
            match active.stream.write_i32(&pending[pending_offset..]) {
                Ok(0) => thread::sleep(Duration::from_millis(RENDER_POLL_MS)),
                Ok(taken) => pending_offset += taken,
                Err(error) => break 'render RenderEnd::WriteFailed(error.to_string()),
            }
            if pending_offset >= pending.len() {
                pending.clear();
                pending_offset = 0;
            }
            continue;
        }

        // Render only when the ring wants a whole chunk, so the engine is never
        // ahead of the configured depth and a seek never has much to throw away.
        if active.stream.writable_bytes() < chunk_bytes {
            thread::sleep(Duration::from_millis(RENDER_POLL_MS));
            continue;
        }

        // Pause was checked above. If it lands mid-render this chunk is still
        // real audio, so it is queued rather than lost.
        audio_callback_unpaused(&mut render_buffer, &callback_data, &event_tx);
        if cycle_start.elapsed() > chunk_deadline {
            let count = crate::audio::engine::XRUN_COUNT.fetch_add(1, Ordering::Relaxed) + 1;
            dev_eprintln!(
                "[Xrun] render cycle overrun: {:.1}ms > {}ms (total={})",
                cycle_start.elapsed().as_secs_f64() * 1000.0,
                RENDER_CHUNK_MS,
                count
            );
        }
        // With no source at all the chunk is idle silence: keep only a start
        // threshold of it in the ring.
        if callback_data.is_idle() && active.stream.queued_frames() >= start_threshold_frames {
            thread::sleep(Duration::from_millis(RENDER_POLL_MS));
            continue;
        }

        convert_chunk(&render_buffer, &mut samples, raw_bitstream);

        // A flush requested while this chunk was rendered drops what is queued
        // but keeps the chunk: it is a little stale audio (at most one render
        // chunk) or the first audio of the source that replaced the old one,
        // which must never be the part that is dropped.
        apply_output_flush(&callback_data, &active, &mut applied_flush_generation, None);

        match active.stream.write_i32(&samples) {
            Ok(taken) if taken == samples.len() => {}
            Ok(taken) => {
                pending.clear();
                pending.extend_from_slice(&samples[taken..]);
                pending_offset = 0;
            }
            Err(error) => break 'render RenderEnd::WriteFailed(error.to_string()),
        }
    };

    callback_data.set_dop_wire_silence(false);
    callback_data.set_dsd_wire_silence(false);
    end
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_chunk_is_ten_milliseconds_of_the_counter_rate() {
        let pcm = RenderPlan {
            channels: 2,
            counter_rate_hz: 48_000,
            dsd_transport: DsdTransportMode::None,
            bytes_per_ring_sample: 3,
        };
        assert_eq!(pcm.chunk_frames(), 480);
        assert_eq!(pcm.chunk_samples(), 960);
        // Native DSD64 counts one byte per channel: 352.8 kHz.
        let dsd = RenderPlan {
            counter_rate_hz: 352_800,
            dsd_transport: DsdTransportMode::Native,
            bytes_per_ring_sample: 1,
            ..pcm
        };
        assert_eq!(dsd.chunk_frames(), 3_528);
        assert!(dsd.is_raw_bitstream());
        assert!(!pcm.is_raw_bitstream());
    }

    #[test]
    fn the_gain_goes_to_the_library_only_for_pcm_in_passthrough() {
        assert_eq!(desired_software_gain(false, true, 0.5), 0.5);
        assert_eq!(desired_software_gain(false, true, 1.0), 1.0);
        // The DSP chain has already applied the engine's gain.
        assert_eq!(desired_software_gain(false, false, 0.5), 1.0);
        // A bitstream is never scaled.
        assert_eq!(desired_software_gain(true, true, 0.5), 1.0);
        assert_eq!(desired_software_gain(true, false, 0.5), 1.0);
        // Nothing the library would refuse.
        assert_eq!(desired_software_gain(false, true, f32::NAN), 1.0);
        assert_eq!(desired_software_gain(false, true, 99.0), 16.0);
        assert_eq!(desired_software_gain(false, true, -1.0), 0.0);
    }

    #[test]
    fn the_ring_stays_shallow_only_while_the_engine_processes_pcm() {
        assert!(host_processing(false, false));
        assert!(!host_processing(false, true));
        assert!(!host_processing(true, false), "a bitstream is never processed");
        assert!(!host_processing(true, true));
    }

    #[test]
    fn pcm_goes_to_the_ring_at_full_scale_and_a_bitstream_keeps_its_bits() {
        let rendered = [0.0f32, 1.0, -1.0, 0.5, 2.0, -2.0];
        let mut out = [0i32; 6];
        convert_chunk(&rendered, &mut out, false);
        assert_eq!(out[0], 0);
        assert_eq!(out[1], i32::MAX);
        assert_eq!(out[2], -i32::MAX);
        assert_eq!(out[3], 1_073_741_823);
        assert_eq!(out[4], i32::MAX, "clamped, not wrapped");
        assert_eq!(out[5], -i32::MAX);

        // DoP: marker in bits 31..24, 16 data bits in 23..8.
        let word: u32 = (0xFA << 24) | (0x1234 << 8);
        let dop = [f32::from_bits(word)];
        let mut out = [0i32; 1];
        convert_chunk(&dop, &mut out, true);
        assert_eq!(out[0] as u32, word);
        // Native DSD: one byte per sample, in the low byte.
        let native = [f32::from_bits(0x69), f32::from_bits(0xA5)];
        let mut out = [0i32; 2];
        convert_chunk(&native, &mut out, true);
        assert_eq!(out, [0x69, 0xA5]);
    }
}
