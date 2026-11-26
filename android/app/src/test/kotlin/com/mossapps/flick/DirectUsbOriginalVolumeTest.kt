package com.mossapps.flick

import org.junit.Assert.assertEquals
import org.junit.Test

class DirectUsbOriginalVolumeTest {
    @Test
    fun handingTheDacToAndroidWritesTheOriginalLevelBack() {
        assertEquals(
            OriginalVolumeAction.RESTORE,
            originalVolumeActionOnRelease(handBack = true, dacAttached = true),
        )
    }

    @Test
    fun aDacThatIsGoneIsNeverWrittenToAndItsLevelIsForgotten() {
        assertEquals(
            OriginalVolumeAction.FORGET,
            originalVolumeActionOnRelease(handBack = true, dacAttached = false),
        )
        assertEquals(
            OriginalVolumeAction.FORGET,
            originalVolumeActionOnRelease(handBack = false, dacAttached = false),
        )
    }

    @Test
    fun aReleaseFlickTakesStraightBackKeepsTheLevel() {
        assertEquals(
            OriginalVolumeAction.KEEP,
            originalVolumeActionOnRelease(handBack = false, dacAttached = true),
        )
    }

    @Test
    fun theNativeCodesMatchTheRustConstants() {
        // rust/src/uac2/android_direct.rs: ORIGINAL_VOLUME_KEEP / RESTORE / FORGET.
        assertEquals(0, OriginalVolumeAction.KEEP.code)
        assertEquals(1, OriginalVolumeAction.RESTORE.code)
        assertEquals(2, OriginalVolumeAction.FORGET.code)
    }
}
