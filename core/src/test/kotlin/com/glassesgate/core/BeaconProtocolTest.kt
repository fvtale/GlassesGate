package com.glassesgate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BeaconProtocolTest {

    private val credentialId = ByteArray(BeaconProtocol.CREDENTIAL_ID_BYTES) { it.toByte() }
    private val otherCredentialId =
        ByteArray(BeaconProtocol.CREDENTIAL_ID_BYTES) { (it + 7).toByte() }
    private val secret = ByteArray(BeaconProtocol.SECRET_BYTES) { it.toByte() }
    private val otherSecret = ByteArray(BeaconProtocol.SECRET_BYTES) { (it + 1).toByte() }

    private val now = 1_800_000_000_000L
    private val windowMillis = BeaconProtocol.ROTATION_SECONDS * 1000L

    /** Builds an advertisement at one instant and verifies it at another. */
    private fun verify(builtAt: Long, checkedAt: Long): Boolean {
        val (tag, token) = BeaconProtocol.parsePayload(
            BeaconProtocol.buildPayload(secret, credentialId, builtAt),
        )!!
        return BeaconProtocol.matches(tag, token, credentialId, secret, checkedAt)
    }

    @Test
    fun `payload is exactly the advertised length`() {
        assertEquals(
            BeaconProtocol.PAYLOAD_BYTES,
            BeaconProtocol.buildPayload(secret, credentialId, now).size,
        )
    }

    @Test
    fun `payload fits in a legacy BLE advertisement`() {
        // 31 bytes total, minus the flags header, minus the service-data header and its UUID
        // reference. Anything past roughly 20 risks being silently dropped by the stack.
        assertTrue(BeaconProtocol.PAYLOAD_BYTES <= 20)
    }

    @Test
    fun `token from the current window verifies`() {
        assertTrue(verify(builtAt = now, checkedAt = now))
    }

    @Test
    fun `token verifies across a window boundary in both directions`() {
        // The advertiser's clock running one window behind, then one window ahead.
        assertTrue(verify(builtAt = now - windowMillis, checkedAt = now))
        assertTrue(verify(builtAt = now + windowMillis, checkedAt = now))
    }

    @Test
    fun `token stops verifying beyond the drift tolerance`() {
        val beyond = windowMillis * (BeaconProtocol.DRIFT_WINDOWS + 1)
        assertFalse(verify(builtAt = now - beyond, checkedAt = now))
        assertFalse(verify(builtAt = now + beyond, checkedAt = now))
    }

    @Test
    fun `replay window stays bounded`() {
        // The README claims a captured advertisement is useless within about a minute. If
        // ROTATION_SECONDS or DRIFT_WINDOWS is retuned, that claim has to be retuned with it.
        val maxReplaySeconds =
            BeaconProtocol.ROTATION_SECONDS * (2L * BeaconProtocol.DRIFT_WINDOWS + 1)
        assertTrue("replay window grew to $maxReplaySeconds s", maxReplaySeconds <= 60)
    }

    @Test
    fun `token signed with a different secret is rejected`() {
        val (tag, token) = BeaconProtocol.parsePayload(
            BeaconProtocol.buildPayload(otherSecret, credentialId, now),
        )!!
        assertFalse(BeaconProtocol.matches(tag, token, credentialId, secret, now))
    }

    @Test
    fun `token for a different credential is rejected`() {
        val (tag, token) = BeaconProtocol.parsePayload(
            BeaconProtocol.buildPayload(secret, otherCredentialId, now),
        )!!
        assertFalse(BeaconProtocol.matches(tag, token, credentialId, secret, now))
    }

    @Test
    fun `a genuine tag paired with a foreign token is rejected`() {
        // Guards against a verifier that checks the cheap public tag and skips the HMAC.
        val tag = BeaconProtocol.credentialTag(credentialId)
        val (_, token) = BeaconProtocol.parsePayload(
            BeaconProtocol.buildPayload(otherSecret, credentialId, now),
        )!!
        assertFalse(BeaconProtocol.matches(tag, token, credentialId, secret, now))
    }

    @Test
    fun `tag is stable and does not reveal the credential id`() {
        val tag = BeaconProtocol.credentialTag(credentialId)
        assertEquals(BeaconProtocol.TAG_BYTES, tag.size)
        assertTrue(tag.contentEquals(BeaconProtocol.credentialTag(credentialId)))
        assertFalse(tag.contentEquals(credentialId.copyOf(BeaconProtocol.TAG_BYTES)))
    }

    @Test
    fun `token changes every window`() {
        val distinct = (0L until 20L)
            .map { BeaconProtocol.tokenFor(secret, credentialId, it).toList() }
            .toSet()
        assertEquals(20, distinct.size)
    }

    @Test
    fun `window boundary is the next multiple of the rotation period`() {
        val boundary = BeaconProtocol.nextWindowBoundaryMillis(now)
        assertTrue(boundary > now)
        assertTrue(boundary - now <= windowMillis)
        assertEquals(BeaconProtocol.currentWindow(now) + 1, BeaconProtocol.currentWindow(boundary))
    }

    @Test
    fun `window arithmetic holds before the epoch`() {
        // floorDiv, not plain division: truncation toward zero would put two adjacent negative
        // timestamps in the same window and make window -1 twice as long as every other.
        assertEquals(-1L, BeaconProtocol.currentWindow(-1L))
        assertEquals(-1L, BeaconProtocol.currentWindow(-windowMillis))
        assertEquals(-2L, BeaconProtocol.currentWindow(-windowMillis - 1))
    }

    @Test
    fun `malformed service data is rejected rather than throwing`() {
        assertNull(BeaconProtocol.parsePayload(null))
        assertNull(BeaconProtocol.parsePayload(ByteArray(0)))
        assertNull(BeaconProtocol.parsePayload(ByteArray(BeaconProtocol.PAYLOAD_BYTES - 1)))
        assertNotNull(BeaconProtocol.parsePayload(ByteArray(BeaconProtocol.PAYLOAD_BYTES)))
    }

    @Test
    fun `trailing service data is ignored rather than rejected`() {
        // Some stacks pad service data. The payload is a prefix, so padding must not break it.
        val padded = BeaconProtocol.buildPayload(secret, credentialId, now) + ByteArray(4)
        val (tag, token) = BeaconProtocol.parsePayload(padded)!!
        assertTrue(BeaconProtocol.matches(tag, token, credentialId, secret, now))
    }

    @Test
    fun `a truncated token does not match on its prefix`() {
        val (tag, token) = BeaconProtocol.parsePayload(
            BeaconProtocol.buildPayload(secret, credentialId, now),
        )!!
        assertFalse(BeaconProtocol.matches(tag, token.copyOf(4), credentialId, secret, now))
    }
}
