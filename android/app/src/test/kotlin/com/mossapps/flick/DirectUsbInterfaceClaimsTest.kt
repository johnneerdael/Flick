package com.mossapps.flick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectUsbInterfaceClaimsTest {
    private val events = mutableListOf<String>()

    private fun claims(forceClaim: (Int) -> Unit = {}) = DirectUsbInterfaceClaims<Int>(
        forceClaim = { events += "claim $it"; forceClaim(it) },
        releaseInterface = { events += "release $it" },
        closeConnection = { events += "close" },
    )

    @Test
    fun registersExactIdsAndRetainsClaimsUntilRelease() {
        val registration = claims()
        assertTrue(registration.register({ listOf(1, 4) }) {
            events += "register $it"
            true
        })
        assertEquals(listOf("claim 1", "claim 4", "register [1, 4]"), events)

        registration.release()
        assertEquals(
            listOf("claim 1", "claim 4", "register [1, 4]", "release 4", "release 1", "close"),
            events,
        )
    }

    @Test
    fun rollsBackOnlySuccessfulClaimsWhenAClaimFails() {
        val refusal = IllegalStateException("interface busy")
        val registration = claims { if (it == 4) throw refusal }
        val error = assertThrows(IllegalStateException::class.java) {
            registration.register({ listOf(1, 4, 5) }) { error("Must not register a partial claim") }
        }

        assertSame(refusal, error)
        assertEquals(listOf("claim 1", "claim 4", "release 1", "close"), events)
    }

    @Test
    fun rollsBackAllClaimsWhenNativeRejectsRegistration() {
        assertFalse(claims().register({ listOf(1, 4) }) {
            events += "register $it"
            false
        })
        assertEquals(
            listOf("claim 1", "claim 4", "register [1, 4]", "release 4", "release 1", "close"),
            events,
        )
    }
    @Test
    fun optionalFailuresPreservePlaybackAndReleaseOnlySuccessfulClaims() {
        val registration = claims { if (it == 5) error("capture busy") }
        assertTrue(registration.register({ listOf(1, 4) }) {
            assertEquals(listOf(5), registration.claimOptional(listOf(4, 5, 6, 6)))
            assertEquals(listOf(1, 4, 6), registration.successfulClaims())
            true
        })
        registration.release()
        assertEquals(
            listOf("claim 1", "claim 4", "claim 5", "claim 6", "release 6", "release 4", "release 1", "close"),
            events,
        )
    }

    @Test
    fun nativeRefusalRollsBackOptionalClaimsInReverseOrder() {
        val registration = claims()
        assertFalse(registration.register({ listOf(1, 4) }) {
            assertTrue(registration.claimOptional(listOf(6)).isEmpty())
            false
        })
        assertEquals(
            listOf("claim 1", "claim 4", "claim 6", "release 6", "release 4", "release 1", "close"),
            events,
        )
    }

}
