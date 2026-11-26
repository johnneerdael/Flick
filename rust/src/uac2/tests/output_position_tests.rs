use crate::uac2::iso_packet_validation::{account_timed_out_transfer, PacketAcceptance};
use crate::uac2::iso_transport_loss::{LossLimits, LossTracker, UrbLoss};
use crate::uac2::output_position::{
    audible_position_secs, inflight_music_frames, music_frames_delivered, output_latency_frames,
    PlayedMusicCounter,
};
use libusb1_sys::{constants::LIBUSB_TRANSFER_COMPLETED, libusb_iso_packet_descriptor};

// ---- which frames of a transfer count as music that was played

#[test]
fn a_clean_music_transfer_counts_in_full() {
    assert_eq!(music_frames_delivered(32, 32, 0), 32);
}

#[test]
fn held_silence_is_not_music() {
    // A held transfer is filled with silence and takes nothing from the ring.
    assert_eq!(music_frames_delivered(32, 0, 0), 0);
    // Not even when part of it was lost: nothing was music to begin with.
    assert_eq!(music_frames_delivered(32, 0, 10), 0);
}

#[test]
fn underrun_padding_is_not_music() {
    // 20 frames came from the ring and 12 are padding.
    assert_eq!(music_frames_delivered(32, 20, 0), 20);
}

#[test]
fn lost_frames_come_off_the_padding_before_the_music() {
    // Padding and loss both sit at the end of a transfer, so a loss no larger
    // than the padding costs the music nothing.
    assert_eq!(music_frames_delivered(32, 20, 12), 20);
    assert_eq!(music_frames_delivered(32, 20, 5), 20);
    // Beyond the padding the music is lost too.
    assert_eq!(music_frames_delivered(32, 20, 15), 17);
    assert_eq!(music_frames_delivered(32, 32, 7), 25);
    assert_eq!(music_frames_delivered(32, 32, 32), 0);
}

#[test]
fn nonsense_inputs_never_overflow_or_exceed_the_transfer() {
    assert_eq!(music_frames_delivered(32, 99, 0), 32, "music cannot exceed the transfer");
    assert_eq!(music_frames_delivered(32, 32, 99), 0);
    assert_eq!(music_frames_delivered(0, 0, 0), 0);
    assert_eq!(music_frames_delivered(u64::MAX, u64::MAX, u64::MAX), 0);
}

// ---- the running count

#[test]
fn the_count_only_grows_between_flushes() {
    let mut played = PlayedMusicCounter::default();
    let mut last = 0;
    for delivered in [32, 32, 0, 0, 20, 32, 0, 32] {
        let total = played.complete(0, 0, delivered);
        assert!(total >= last, "{total} fell below {last}");
        last = total;
    }
    assert_eq!(last, 148);
}

#[test]
fn a_flush_starts_a_new_count() {
    let mut played = PlayedMusicCounter::default();
    assert_eq!(played.complete(0, 0, 32), 32);
    assert_eq!(played.complete(0, 0, 32), 64);

    // The ring is flushed (seek): epoch 1. Held silence completes first.
    assert_eq!(played.complete(1, 1, 0), 0, "reset on flush");
    assert_eq!(played.complete(1, 1, 32), 32);
}

#[test]
fn audio_queued_before_a_flush_does_not_count_after_it() {
    let mut played = PlayedMusicCounter::default();
    assert_eq!(played.complete(0, 0, 32), 32);
    // Seek. Two transfers filled before the flush are still on the bus and
    // complete in the new epoch; they hold audio from before the seek.
    assert_eq!(played.complete(1, 0, 32), 0);
    assert_eq!(played.complete(1, 0, 32), 0);
    // The first transfer filled after the flush does count.
    assert_eq!(played.complete(1, 1, 32), 32);
}

#[test]
fn the_count_starts_at_zero_with_each_new_counter() {
    // A stream gets a new counter, so nothing carries over from the last one.
    let mut first = PlayedMusicCounter::default();
    first.complete(0, 0, 500);
    let mut second = PlayedMusicCounter::default();
    assert_eq!(second.complete(0, 0, 0), 0);
}

// ---- pause, refill and loss against the count

fn descriptors(sizes: &[usize], actual: &[usize]) -> Vec<libusb_iso_packet_descriptor> {
    sizes
        .iter()
        .zip(actual)
        .map(|(length, actual)| libusb_iso_packet_descriptor {
            length: *length as u32,
            actual_length: *actual as u32,
            status: LIBUSB_TRANSFER_COMPLETED,
        })
        .collect()
}

const SCHEDULE: [usize; 8] = [20, 24, 20, 24, 20, 24, 20, 24];
const SCHEDULE_FRAMES: u64 = 44;

/// One completed transfer as the output loop sees it: what was queued, how much
/// of it was music, which epoch, whether it was held, and what the transport
/// lost.
struct Completed {
    music_frames: u64,
    epoch: u64,
    held: bool,
    urb: UrbLoss,
}

impl Completed {
    fn music(music_frames: u64) -> Self {
        Self {
            music_frames,
            epoch: 0,
            held: false,
            urb: UrbLoss::default(),
        }
    }

    fn held() -> Self {
        Self {
            music_frames: 0,
            epoch: 0,
            held: true,
            urb: UrbLoss::default(),
        }
    }
}

/// The output loop's bookkeeping for one completed transfer.
fn complete(
    played: &mut PlayedMusicCounter,
    tracker: &mut LossTracker,
    current_epoch: u64,
    done: &Completed,
) -> u64 {
    let urb = done.urb.with_held(done.held);
    tracker.record(SCHEDULE_FRAMES, urb);
    played.complete(
        current_epoch,
        done.epoch,
        music_frames_delivered(SCHEDULE_FRAMES, done.music_frames, urb.lost_frames),
    )
}

fn tracker() -> LossTracker {
    LossTracker::new(LossLimits {
        max_consecutive_lossy_urbs: 1_000,
        max_lost_frames: 1_000_000,
    })
}

#[test]
fn pause_and_refill_holds_add_nothing_and_playing_resumes_the_count() {
    let mut played = PlayedMusicCounter::default();
    let mut tracker = tracker();

    // Playing, then a pause (held), then a flush with its refill wait (held,
    // new epoch), then playing again.
    let mut totals = Vec::new();
    for (epoch, done) in [
        (0, Completed::music(SCHEDULE_FRAMES)),
        (0, Completed::music(SCHEDULE_FRAMES)),
        (0, Completed::held()),
        (0, Completed::held()),
        (0, Completed::music(SCHEDULE_FRAMES)),
        (1, Completed::held()),
        (1, Completed::held()),
        (
            1,
            Completed {
                epoch: 1,
                ..Completed::music(SCHEDULE_FRAMES)
            },
        ),
    ] {
        totals.push(complete(&mut played, &mut tracker, epoch, &done));
    }

    assert_eq!(
        totals,
        [44, 88, 88, 88, 132, 0, 0, 44],
        "held transfers hold the count; a flush restarts it"
    );
    assert!(!tracker.is_latched(), "holds are not lost audio");
}

#[test]
fn a_timed_out_transfer_counts_only_what_went_out_and_its_resubmission_counts_once() {
    let mut played = PlayedMusicCounter::default();
    let mut tracker = tracker();

    // Packets 0 and 1 went out whole, the rest of the URB timed out.
    let timed_out_descriptors = descriptors(&SCHEDULE, &[20, 24, 0, 0, 0, 0, 0, 0]);
    let stats = account_timed_out_transfer(
        &SCHEDULE,
        &timed_out_descriptors,
        PacketAcceptance::new(44_100, 125, 4),
    );
    let timed_out = Completed {
        urb: UrbLoss::from_completion(&stats, true, 4),
        ..Completed::music(SCHEDULE_FRAMES)
    };
    assert_eq!(timed_out.urb.lost_frames, SCHEDULE_FRAMES - 11);

    let after_clean = complete(&mut played, &mut tracker, 0, &Completed::music(SCHEDULE_FRAMES));
    assert_eq!(after_clean, 44);
    let after_timeout = complete(&mut played, &mut tracker, 0, &timed_out);
    assert_eq!(after_timeout, 44 + 11, "only the delivered packets count");

    // The slot is resubmitted with the next audio from the ring. Its frames are
    // new frames, so the lost URB's audio is not counted a second time.
    let after_resubmit = complete(&mut played, &mut tracker, 0, &Completed::music(SCHEDULE_FRAMES));
    assert_eq!(after_resubmit, 44 + 11 + 44);
    assert_eq!(tracker.lost_frames(), SCHEDULE_FRAMES - 11);
}

#[test]
fn a_timed_out_hold_adds_nothing_and_loses_no_music() {
    let mut played = PlayedMusicCounter::default();
    let mut tracker = tracker();
    complete(&mut played, &mut tracker, 0, &Completed::music(SCHEDULE_FRAMES));

    let held_timeout = Completed {
        urb: UrbLoss {
            timed_out: true,
            lost_frames: SCHEDULE_FRAMES,
            ..UrbLoss::default()
        },
        ..Completed::held()
    };
    assert_eq!(complete(&mut played, &mut tracker, 0, &held_timeout), 44);
    assert_eq!(tracker.lost_frames(), 0);
}

#[test]
fn padding_and_loss_together_are_charged_padding_first() {
    let mut played = PlayedMusicCounter::default();
    let mut tracker = tracker();
    // 30 of 44 frames were music; the DAC took 36 of 44, so 8 were lost. The 14
    // frames of padding absorb all 8.
    let done = Completed {
        urb: UrbLoss {
            lost_frames: 8,
            ..UrbLoss::default()
        },
        ..Completed::music(30)
    };
    assert_eq!(complete(&mut played, &mut tracker, 0, &done), 30);
}

// ---- the latency query

#[test]
fn only_music_of_the_current_epoch_counts_as_in_flight() {
    // Three transfers on the bus: two music (one from before the flush), one
    // held silence.
    let in_flight = [(1, 32), (0, 32), (1, 0)];
    assert_eq!(inflight_music_frames(1, in_flight), 32);
    assert_eq!(inflight_music_frames(0, in_flight), 32);
    assert_eq!(inflight_music_frames(2, in_flight), 0);
    assert_eq!(inflight_music_frames(1, []), 0);
}

#[test]
fn latency_is_the_ring_plus_what_is_on_the_bus() {
    assert_eq!(output_latency_frames(11_025, 256), 11_281);
    assert_eq!(output_latency_frames(0, 0), 0);
    assert_eq!(output_latency_frames(u64::MAX, 1), u64::MAX);
}

// ---- the audible position

fn secs(frames: u64, rate: u32) -> f64 {
    frames as f64 / f64::from(rate)
}

#[test]
fn the_audible_position_trails_the_decoder_by_the_latency() {
    // 250 ms of ring and 8 ms on the bus at 44.1 kHz.
    let latency = 11_025 + 352;
    let audible = audible_position_secs(10.0, latency, 44_100, 1.0);
    assert!((audible - (10.0 - secs(latency, 44_100))).abs() < 1e-9);
    assert!(audible < 10.0 && audible > 9.7);
}

#[test]
fn the_audible_position_is_never_negative() {
    // The first moments of a track, and the start of the next track at a
    // gapless boundary while the tail of the last one is still buffered.
    assert_eq!(audible_position_secs(0.05, 11_025, 44_100, 1.0), 0.0);
    assert_eq!(audible_position_secs(0.0, 11_025, 44_100, 1.0), 0.0);
}

#[test]
fn a_playback_speed_scales_the_source_time_inside_the_buffer() {
    // At 2x the decoder consumes 2 s of source per second of output, so 250 ms
    // of buffered output is 500 ms of source.
    let latency = 11_025;
    let audible = audible_position_secs(10.0, latency, 44_100, 2.0);
    assert!((audible - 9.5).abs() < 1e-9);
    let slow = audible_position_secs(10.0, latency, 44_100, 0.5);
    assert!((slow - 9.875).abs() < 1e-9);
}

#[test]
fn dsd_ring_rates_work_in_ring_frames() {
    // Native DSD64 stereo: the ring queues one byte per channel, 352.8 kHz.
    let latency = 88_200;
    let audible = audible_position_secs(5.0, latency, 352_800, 1.0);
    assert!((audible - 4.75).abs() < 1e-9);
}

#[test]
fn bad_inputs_leave_the_decoder_position_alone() {
    assert_eq!(audible_position_secs(3.0, 100, 0, 1.0), 3.0);
    assert!(audible_position_secs(f64::NAN, 100, 44_100, 1.0).is_nan());
    // A nonsense speed is taken as 1.0 rather than flipping the sign.
    let audible = audible_position_secs(10.0, 44_100, 44_100, f32::NAN);
    assert!((audible - 9.0).abs() < 1e-9);
    let audible = audible_position_secs(10.0, 44_100, 44_100, -1.0);
    assert!((audible - 9.0).abs() < 1e-9);
}

/// A stream simulated in 2 ms steps: one transfer completes and the next is
/// submitted from the ring, and every fifth step the decoder tops the ring up to
/// its target. With nothing lost, the audible position computed from the decoder
/// and the latency equals what the counter says was played, which is the whole
/// point of reporting it.
struct Stream {
    ring: u64,
    bus: std::collections::VecDeque<(u64, u64)>,
    decoder_frames: u64,
    base_secs: f64,
    epoch: u64,
    played: PlayedMusicCounter,
    played_in_epoch: u64,
    step: u64,
}

const RATE: u32 = 48_000;
const URB_FRAMES: u64 = 96; // 2 ms
const CHUNK_FRAMES: u64 = 480; // 10 ms
const TARGET_FRAMES: u64 = 12_000; // 250 ms
const BUS_URBS: usize = 8;

impl Stream {
    fn new() -> Self {
        let mut stream = Self {
            ring: 0,
            bus: std::collections::VecDeque::new(),
            decoder_frames: 0,
            base_secs: 0.0,
            epoch: 0,
            played: PlayedMusicCounter::default(),
            played_in_epoch: 0,
            step: 0,
        };
        // Prime: fill the ring, then put the bus full of music.
        stream.render();
        while stream.bus.len() < BUS_URBS {
            stream.submit(false);
        }
        stream
    }

    fn render(&mut self) {
        while self.ring + CHUNK_FRAMES <= TARGET_FRAMES {
            self.ring += CHUNK_FRAMES;
            self.decoder_frames += CHUNK_FRAMES;
        }
    }

    fn submit(&mut self, held: bool) {
        let music = if held { 0 } else { URB_FRAMES.min(self.ring) };
        self.ring -= music;
        self.bus.push_back((self.epoch, music));
    }

    fn seek(&mut self, to_secs: f64) {
        // The render thread clears the ring and bumps the epoch; what is
        // already on the bus stays there.
        self.ring = 0;
        self.epoch += 1;
        self.base_secs = to_secs;
        self.decoder_frames = 0;
    }

    fn tick(&mut self, paused: bool) {
        self.step += 1;
        if let Some((epoch, music)) = self.bus.pop_front() {
            self.played_in_epoch = self
                .played
                .complete(self.epoch, epoch, music_frames_delivered(URB_FRAMES, music, 0));
        }
        self.submit(paused);
        if self.step % 5 == 0 && !paused {
            self.render();
        }
    }

    fn audible(&self) -> f64 {
        let latency = output_latency_frames(
            self.ring,
            inflight_music_frames(self.epoch, self.bus.iter().copied()),
        );
        audible_position_secs(
            self.base_secs + secs(self.decoder_frames, RATE),
            latency,
            RATE,
            1.0,
        )
    }

    fn played_secs(&self) -> f64 {
        self.base_secs + secs(self.played_in_epoch, RATE)
    }
}

#[test]
fn the_audible_position_equals_what_was_played_and_never_steps_back() {
    let mut stream = Stream::new();
    let mut last = stream.audible();
    for _ in 0..1_500 {
        stream.tick(false);
        let audible = stream.audible();
        assert!(audible >= last - 1e-12, "stepped back: {audible} < {last}");
        assert!(
            (audible - stream.played_secs()).abs() < 1e-9,
            "audible {audible} differs from played {}",
            stream.played_secs()
        );
        last = audible;
    }
    // 1500 steps of 2 ms, minus nothing: the position advanced by exactly that.
    assert!((last - 3.0).abs() < 1e-9, "{last}");
}

#[test]
fn pausing_holds_the_audible_position_and_resuming_continues_from_it() {
    let mut stream = Stream::new();
    for _ in 0..500 {
        stream.tick(false);
    }
    // Pause. The music already on the bus still plays out (16 ms), then the
    // position holds while held silence goes out and the ring is kept.
    let mut last = stream.audible();
    let mut settled = None;
    for _ in 0..400 {
        stream.tick(true);
        let audible = stream.audible();
        assert!(audible >= last - 1e-12, "stepped back while pausing");
        last = audible;
        if stream.bus.iter().all(|(_, music)| *music == 0) {
            settled = Some(audible);
        }
        if let Some(settled) = settled {
            assert!((audible - settled).abs() < 1e-9, "moved while paused");
        }
    }
    let held = settled.expect("the bus drained");
    assert!((held - stream.played_secs()).abs() < 1e-9);
    // The decoder is not ahead of the held position by a whole ring any more:
    // the ring still holds what was not played, and the position excludes it.
    assert!(stream.ring >= TARGET_FRAMES - CHUNK_FRAMES);

    // Resume: the position continues from where it held, with no jump.
    for _ in 0..8 {
        stream.tick(false);
        let audible = stream.audible();
        assert!(audible >= held - 1e-12 && audible <= held + 0.020);
    }
    for _ in 0..500 {
        stream.tick(false);
    }
    assert!(stream.audible() > held + 0.9);
}

#[test]
fn a_seek_moves_the_audible_position_to_the_target_without_counting_old_audio() {
    let mut stream = Stream::new();
    for _ in 0..500 {
        stream.tick(false);
    }
    let before = stream.audible();
    assert!((before - 1.0).abs() < 1e-9);

    // Seek to 60 s. Old music is still on the bus for 16 ms.
    stream.seek(60.0);
    assert!(stream.bus.iter().any(|(epoch, music)| *epoch == 0 && *music > 0));
    assert_eq!(
        stream.audible(),
        60.0,
        "the old audio on the bus is not subtracted from the new position"
    );

    let mut last = stream.audible();
    for _ in 0..600 {
        stream.tick(false);
        let audible = stream.audible();
        assert!(audible >= last - 1e-12);
        assert!(
            (audible - stream.played_secs()).abs() < 1e-9,
            "{audible} vs {}",
            stream.played_secs()
        );
        last = audible;
    }
    assert!(last > 60.0);
}

#[test]
fn the_start_of_a_track_reads_zero_until_the_buffer_has_played() {
    // A gapless transition: the decoder is already in the next track while its
    // first 250 ms is still in the ring. The audible position clamps at zero,
    // never negative.
    let decoder = 0.25;
    let latency = TARGET_FRAMES + 8 * URB_FRAMES;
    assert_eq!(audible_position_secs(decoder, latency, RATE, 1.0), 0.0);
}
