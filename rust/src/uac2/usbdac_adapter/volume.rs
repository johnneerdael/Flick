//! The DAC's hardware volume and mute, as the JNI entry points use them.
//!
//! The control belongs to the DAC, not to a stream: the library keeps it for as
//! long as the DAC is open, so it works between tracks and while the stream is
//! released. This module keeps the last level and mute the player saw, and
//! turns the library's results into the booleans and strings the entry points
//! have always returned.

use super::ports::{DacPort, VolumePort};
use super::state::{with_registration, STATE};
use std::sync::Arc;
use usbdac_core::VolumeState;

const NOT_REGISTERED: &str = "No Android direct USB DAC is registered";

/// The DAC and its volume control, cloned out of the state so no lock is held
/// while the library makes control transfers.
fn dac_and_control() -> Result<(Arc<dyn DacPort>, Option<Box<dyn VolumePort>>), String> {
    let dac = STATE
        .lock()
        .as_ref()
        .map(|registration| registration.dac.clone())
        .ok_or_else(|| NOT_REGISTERED.to_string())?;
    let control = dac.volume();
    Ok((dac, control))
}

fn control_or(unavailable: &str) -> Result<Box<dyn VolumePort>, String> {
    dac_and_control()?
        .1
        .ok_or_else(|| unavailable.to_string())
}

/// Remembers what the DAC said.
pub(crate) fn publish(state: &VolumeState) {
    with_registration(|registration| {
        registration.volume.normalized = Some(state.fraction.clamp(0.0, 1.0));
        registration.volume.muted = state.muted;
        registration.volume.error = None;
    });
}

fn publish_error(error: &str) {
    with_registration(|registration| registration.volume.error = Some(error.to_string()));
}

pub fn android_direct_has_hardware_volume_control() -> bool {
    dac_and_control().is_ok_and(|(_, control)| control.is_some())
}

pub fn android_direct_cached_hardware_volume() -> Option<f64> {
    STATE
        .lock()
        .as_ref()
        .and_then(|registration| registration.volume.normalized)
}

pub fn android_direct_cached_hardware_mute() -> Option<bool> {
    STATE
        .lock()
        .as_ref()
        .and_then(|registration| registration.volume.muted)
}

/// Reads the level the DAC has now into the cache. Used when the DAC is opened
/// and when a stream starts: failing to is not fatal, the control just reports
/// no level until a read works.
pub(crate) fn refresh_volume_cache() {
    let Ok((_, Some(control))) = dac_and_control() else {
        return;
    };
    match control.get() {
        Ok(state) => publish(&state),
        Err(error) => publish_error(&error.to_string()),
    }
}

pub fn android_direct_set_hardware_volume(volume: f64) -> Result<(), String> {
    if !volume.is_finite() {
        return Err("USB hardware volume must be finite".to_string());
    }
    let control = control_or("Android direct USB hardware volume is unavailable")?;
    // The library snaps to the DAC's step, writes every channel, reads it back
    // and fails on a mismatch.
    match control.set_fraction(volume.clamp(0.0, 1.0)) {
        Ok(state) => {
            publish(&state);
            Ok(())
        }
        Err(error) => {
            let message = error.to_string();
            publish_error(&message);
            Err(message)
        }
    }
}

/// Restores a remembered hardware level before the first stream.
///
/// The control exists as soon as the DAC is registered, so the write happens
/// now, before any isochronous transfer, and a refusal stops activation. A DAC
/// without a hardware volume has nothing to restore.
pub fn android_direct_queue_hardware_volume_restore(volume: f64) -> Result<(), String> {
    if !volume.is_finite() || !(0.0..=1.0).contains(&volume) {
        return Err(format!("USB hardware volume restore value out of range: {volume}"));
    }
    let (_, control) = dac_and_control()?;
    let Some(control) = control else {
        log::info!("[USB] No hardware volume control to restore {volume} on");
        return Ok(());
    };
    match control.set_fraction(volume) {
        Ok(state) => {
            publish(&state);
            Ok(())
        }
        Err(error) => {
            let message = error.to_string();
            publish_error(&message);
            Err(message)
        }
    }
}

/// Refreshes the actual DAC state. A physical volume-button change is not a
/// failure; the write path verifies its own read-back.
pub fn android_direct_verify_hardware_volume_health() -> Result<bool, String> {
    let control = control_or("Android direct USB hardware volume is unavailable")?;
    match control.get() {
        Ok(state) => {
            publish(&state);
            Ok(true)
        }
        Err(error) => {
            let message = error.to_string();
            publish_error(&message);
            Err(message)
        }
    }
}

pub fn android_direct_set_hardware_mute(muted: bool) -> Result<(), String> {
    let control = control_or("Android direct USB hardware mute is unavailable")?;
    match control.set_mute(muted) {
        Ok(state) => {
            publish(&state);
            if state.muted == Some(muted) {
                Ok(())
            } else {
                Err("USB mute write did not match readback".to_string())
            }
        }
        Err(error) => {
            let message = error.to_string();
            publish_error(&message);
            Err(message)
        }
    }
}
