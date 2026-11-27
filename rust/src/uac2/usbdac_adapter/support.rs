//! Opt-in, bounded support evidence. Never reads the app log or audio payloads.
use parking_lot::Mutex;
use serde_json::{json, Value};
use std::{sync::LazyLock, time::Instant};
use usbdac_core::{DacConfig, DacEvent, DeviceInfo, Error};

const MAX_EVENTS: usize = 512;
const MAX_LEGS: usize = 8;
const MAX_TEXT: usize = 4096;
const MAX_EVENT_BYTES: usize = 8192;
const MAX_FEEDBACK: usize = 512 * 1024;
static STORE: LazyLock<Mutex<Store>> = LazyLock::new(|| Mutex::new(Store::default()));

#[derive(Default)]
struct Store {
    next: u64,
    attempt: Option<Attempt>,
}
struct Attempt {
    id: u64,
    start: Instant,
    events: Vec<Value>,
    legs: Vec<Value>,
    first_failure: Option<Value>,
    dropped_events: u64,
    dropped_legs: u64,
    next_leg: usize,
}
#[derive(Clone)]
pub(super) struct Capture {
    id: u64,
    private_device_name: Option<String>,
}

impl Store {
    fn begin(&mut self) -> u64 {
        if self.attempt.is_some() {
            return 0;
        }
        self.next = self
            .next
            .checked_add(1)
            .filter(|id| *id <= i64::MAX as u64)
            .unwrap_or(1);
        self.attempt = Some(Attempt {
            id: self.next,
            start: Instant::now(),
            events: vec![],
            legs: vec![],
            first_failure: None,
            dropped_events: 0,
            dropped_legs: 0,
            next_leg: 0,
        });
        self.next
    }
    fn finish(&mut self, id: u64) -> Option<String> {
        if self.attempt.as_ref()?.id != id {
            return None;
        }
        let a = self.attempt.take()?;
        Some(json!({"schemaVersion":1,"attemptId":a.id,"usbdacVersion":"0.1.2", "events":a.events,"legs":a.legs,"firstFailure":a.first_failure,"droppedEvents":a.dropped_events,"droppedLegs":a.dropped_legs}).to_string())
    }
}
pub fn begin_usb_support_capture() -> u64 {
    STORE.lock().begin()
}
pub fn finish_usb_support_capture(id: u64) -> Option<String> {
    STORE.lock().finish(id)
}

/// Only this call changes the capture flag, on the configuration actually opened.
pub(super) fn prepare(info: &DeviceInfo, config: &mut DacConfig) -> Option<Capture> {
    let id = STORE.lock().attempt.as_ref().map(|a| a.id)?;
    config.capture_feedback_trace = true;
    let capture = Capture {
        id,
        private_device_name: info.device_name.clone(),
    };
    capture.event("dac_open_requested", json!({
        "vendorId":info.vendor_id,"productId":info.product_id,
        "productModel":capture.scrub(&info.product_name),"permissionGranted":true,
        "configurationId":info.configuration_id,"claimedInterfaces":info.claimed_interfaces,
        "unavailableFeedbackInterfaces":info.unavailable_feedback_interfaces,
        "config": {"captureFeedbackTrace":config.capture_feedback_trace,
            "requireVerifiedRate":config.require_verified_rate,"disableFeedback":config.disable_feedback,
            "allowUnsynchronizedAsync":config.allow_unsynchronized_async,
            "bufferTargetMs":config.buffer_target_ms,"bufferCapacityMs":config.buffer_capacity_ms,
            "startThresholdMs":config.start_threshold_ms,"processingBufferTargetMs":config.processing_buffer_target_ms,
            "busQueueMs":config.bus_queue_ms,"transfersInFlight":config.transfers_in_flight,
            "cancelInFlightOnFlush":config.cancel_in_flight_on_flush,"dsdBigEndian":config.dsd_big_endian,
            "dsdSubslot":config.dsd_subslot,"padded24bit":config.padded_24bit,
            "clockSettleDelayMs":config.clock_settle_delay_ms,"realtimePriority":config.realtime_priority,
            "requireHardwareVolume":config.require_hardware_volume,
            "startupVolume":format!("{:?}",config.startup_volume),"handBack":format!("{:?}",config.hand_back),
            "quirks":config.quirks.iter().take(32).map(|q| json!({"vendorId":q.vendor_id,"productId":q.product_id,"quirks":format!("{:?}",q.quirks)})).collect::<Vec<_>>()}
    }));
    Some(capture)
}
impl Capture {
    fn with(&self, f: impl FnOnce(&mut Attempt)) {
        if let Some(a) = STORE.lock().attempt.as_mut().filter(|a| a.id == self.id) {
            f(a);
        }
    }
    pub(super) fn active(&self) -> bool {
        STORE
            .lock()
            .attempt
            .as_ref()
            .is_some_and(|a| a.id == self.id)
    }
    pub(super) fn scrub(&self, text: &str) -> String {
        let text = self
            .private_device_name
            .as_ref()
            .filter(|s| !s.is_empty())
            .map(|name| text.replace(name, "[device]"))
            .unwrap_or_else(|| text.to_owned());
        scrub(&text, MAX_TEXT)
    }
    pub(super) fn event(&self, stage: &str, mut fields: Value) {
        self.with(|a| {
            fields["elapsedUs"] = json!(a.start.elapsed().as_micros().min(u64::MAX as u128) as u64);
            fields["stage"] = json!(stage);
            if fields.to_string().len() > MAX_EVENT_BYTES {
                fields =
                    json!({"elapsedUs":fields["elapsedUs"],"stage":stage,"fieldsOmitted":true});
            }
            if a.events.len() == MAX_EVENTS {
                a.events.remove(128);
                a.dropped_events += 1;
            }
            a.events.push(fields);
        });
    }
    pub(super) fn failure(&self, stage: &str, error: &Error) {
        let detail = self.scrub(&error.to_string());
        let mut failure = json!({"stage":stage,"code":error.code(),"detail":detail});
        self.with(|a| {
            failure["elapsedUs"] =
                json!(a.start.elapsed().as_micros().min(u64::MAX as u128) as u64);
            if a.first_failure.is_none() {
                a.first_failure = Some(failure.clone());
            }
        });
        self.event(stage, failure);
    }
    pub(super) fn leg_failure(&self, number: Option<usize>, stage: &str, error: &Error) {
        self.update_leg(
            number,
            "failure",
            json!({"stage":stage,"code":error.code(),"detail":self.scrub(&error.to_string())}),
        );
        self.failure(stage, error);
    }
    pub(super) fn leg(&self) -> Option<usize> {
        let mut number = None;
        self.with(|a| {
            if a.legs.len() == MAX_LEGS {
                // Keep the first leg and the earliest failed leg, plus the newest context.
                let failed = a.legs.iter().position(|leg| leg["failure"].is_object());
                let remove = (1..a.legs.len()).find(|index| Some(*index) != failed).unwrap_or(1);
                a.legs.remove(remove);
                a.dropped_legs += 1;
            }
            a.next_leg += 1;
            number = Some(a.next_leg);
            a.legs.push(json!({"number":number,"elapsedUs":a.start.elapsed().as_micros().min(u64::MAX as u128) as u64}));
        });
        number
    }
    pub(super) fn update_leg(&self, number: Option<usize>, key: &str, value: Value) {
        self.with(|a| {
            if let Some(leg) = a
                .legs
                .iter_mut()
                .find(|l| l["number"].as_u64() == number.map(|n| n as u64))
            {
                leg[key] = value;
            }
        });
    }
    pub(super) fn feedback(&self, number: Option<usize>, mut trace: usbdac_core::FeedbackTrace) {
        trace.failure = trace.failure.as_ref().map(|s| self.scrub(s));
        let mut text = format!("{trace:#?}");
        let original_bytes = text.len();
        retain_feedback_ends(&mut text, MAX_FEEDBACK);
        self.update_leg(
            number,
            "feedbackTruncated",
            json!(original_bytes > MAX_FEEDBACK),
        );
        self.update_leg(number, "feedbackOriginalBytes", json!(original_bytes));
        self.update_leg(number, "feedback", json!(text));
    }
    pub(super) fn sdk_event(&self, event: &DacEvent) {
        match event {
            DacEvent::Error(e) => self.failure("stream_error", e),
            DacEvent::DeviceLost => self.failure("device_lost", &Error::DeviceLost),
            DacEvent::StateChanged(state) => {
                self.event("stream_state", json!({"state":format!("{state:?}")}))
            }
            DacEvent::FeedbackStale => self.event("feedback_stale", json!({})),
            DacEvent::LostFrames { frames } => self.event("lost_frames", json!({"frames":frames})),
            DacEvent::BitPerfectChanged(verified) => {
                self.event("bit_perfect_changed", json!({"verified":verified}))
            }
            _ => (),
        }
    }
}
fn truncate(text: &mut String, limit: usize) {
    let mut end = text.len().min(limit);
    while !text.is_char_boundary(end) {
        end -= 1;
    }
    text.truncate(end);
}
/// Preserve both startup and terminal observations when the host text cap is hit.
fn retain_feedback_ends(text: &mut String, limit: usize) {
    if text.len() <= limit {
        return;
    }
    const OMITTED: &str = "\n... [middle omitted by Flick feedback text limit] ...\n";
    let mut head = limit / 4;
    while !text.is_char_boundary(head) {
        head -= 1;
    }
    let tail_bytes = limit.saturating_sub(head + OMITTED.len());
    let mut tail = text.len() - tail_bytes;
    while !text.is_char_boundary(tail) {
        tail += 1;
    }
    *text = format!("{}{}{}", &text[..head], OMITTED, &text[tail..]);
}

/// Strip path/URL tokens and serial assignments while preserving technical text.
fn scrub(text: &str, limit: usize) -> String {
    let mut hide_next = false;
    let mut out = text
        .split_whitespace()
        .map(|token| {
            let lower = token.to_ascii_lowercase();
            if hide_next {
                hide_next = false;
                return "[redacted]".to_owned();
            }
            if lower.contains("serial")
                || lower.contains("device_name")
                || lower.contains("devicename")
            {
                hide_next = !token.contains('=');
                return "[redacted]".to_owned();
            }
            if token.contains('/') || token.contains('\\') || token.contains("://") {
                "[path]".to_owned()
            } else {
                token.to_owned()
            }
        })
        .collect::<Vec<_>>()
        .join(" ");
    truncate(&mut out, limit);
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn attempts_are_opt_in_isolated_and_finish_seals() {
        assert!(STORE.lock().attempt.is_none());
        let info = DeviceInfo::new(1, 2, "test DAC").with_device_name("/dev/bus/usb/001/002");
        let mut normal = DacConfig::default();
        assert!(prepare(&info, &mut normal).is_none());
        assert!(!normal.capture_feedback_trace);
        let first = begin_usb_support_capture();
        assert!(first > 0);
        assert_eq!(begin_usb_support_capture(), 0);
        assert!(finish_usb_support_capture(first + 1).is_none());
        let mut config = DacConfig::default();
        config.disable_feedback = true;
        config.require_verified_rate = false;
        let capture = prepare(&info, &mut config).unwrap();
        assert!(config.capture_feedback_trace);
        assert!(config.disable_feedback);
        assert!(!config.require_verified_rate);
        let leg = capture.leg();
        capture.update_leg(leg, "feedback", json!("retained after close"));
        capture.leg_failure(
            leg,
            "negotiate",
            &Error::Transfer("first /dev/bus/usb/001/002".into()),
        );
        capture.failure("stream_open", &Error::Transfer("later".into()));
        for _ in 0..MAX_EVENTS + 10 {
            capture.event("stream_state", json!({"state":"playing"}));
        }
        let failed_middle = capture.leg();
        capture.leg_failure(failed_middle, "stream_open", &Error::DeviceLost);
        for _ in 0..MAX_LEGS + 3 {
            capture.leg();
        }
        let report: Value =
            serde_json::from_str(&finish_usb_support_capture(first).unwrap()).unwrap();
        assert_eq!(report["legs"][0]["feedback"], "retained after close");
        assert_eq!(report["firstFailure"]["stage"], "negotiate");
        assert!(report["firstFailure"]["detail"]
            .as_str()
            .unwrap()
            .contains("[device]"));
        assert_eq!(report["events"].as_array().unwrap().len(), MAX_EVENTS);
        assert_eq!(report["legs"].as_array().unwrap().len(), MAX_LEGS);
        assert!(report["droppedEvents"].as_u64().unwrap() > 0);
        assert!(report["droppedLegs"].as_u64().unwrap() > 0);
        assert!(finish_usb_support_capture(first).is_none());
        let second = begin_usb_support_capture();
        assert_ne!(first, second);
        assert!(finish_usb_support_capture(first).is_none());
        capture.event("old_teardown", json!({}));
        capture.failure("old_failure", &Error::DeviceLost);
        capture.update_leg(leg, "feedback", json!("late old trace"));
        let report: Value =
            serde_json::from_str(&finish_usb_support_capture(second).unwrap()).unwrap();
        assert_eq!(report["events"], json!([]));
        assert_eq!(report["legs"], json!([]));
        assert!(report["firstFailure"].is_null());
    }
    #[test]
    fn bounded_feedback_keeps_utf8_startup_and_terminal_failure() {
        let mut text = format!(
            "startup locked\n{}\nterminal failure status=-4",
            "界".repeat(MAX_FEEDBACK)
        );
        let original_bytes = text.len();
        retain_feedback_ends(&mut text, MAX_FEEDBACK);
        assert!(text.len() <= MAX_FEEDBACK);
        assert!(original_bytes > text.len());
        assert!(text.starts_with("startup locked\n"));
        assert!(text.ends_with("terminal failure status=-4"));
        assert!(text.contains("[middle omitted by Flick feedback text limit]"));
        let mut short = "small trace".to_owned();
        retain_feedback_ends(&mut short, MAX_FEEDBACK);
        assert_eq!(short, "small trace");
    }
    #[test]
    fn privacy_scrubs_paths_urls_and_serials() {
        assert_eq!(
            scrub(
                "transfer /dev/bus/usb/001 failed https://host/private serial: secret status=-4",
                MAX_TEXT
            ),
            "transfer [path] failed [path] [redacted] [redacted] status=-4"
        );
    }
}
