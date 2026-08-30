package com.glassesgate.core

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EnrollmentPayloadTest {

    private val issuedAt = 1_800_000_000L

    private fun payload(
        serial: String = "RBM-2024-0007",
        label: String = "Lynette",
        gate: String = "Front door",
        issued: Long = issuedAt,
    ) = EnrollmentPayload(
        credentialId = Credentials.newCredentialId(),
        secret = Credentials.newSecret(),
        serial = serial,
        label = label,
        gate = gate,
        issuedAtEpochSeconds = issued,
    )

    @Test
    fun `round trips through encode and decode`() {
        val original = payload()
        assertEquals(original, EnrollmentPayload.decode(original.encode()))
    }

    @Test
    fun `round trips text that is not plain ASCII`() {
        val original = payload(label = "Renee éè 東京", gate = "Cafe entrance")
        val decoded = EnrollmentPayload.decode(original.encode())
        assertEquals(original.label, decoded?.label)
        assertEquals(original.gate, decoded?.gate)
    }

    @Test
    fun `round trips empty optional text`() {
        val original = payload(serial = "", gate = "")
        assertEquals(original, EnrollmentPayload.decode(original.encode()))
    }

    @Test
    fun `encoded form stays small enough for a coarse QR code`() {
        // A denser code takes noticeably longer to acquire when someone is holding a phone at
        // arm's length, which is the whole reason this is a binary format and not JSON.
        assertTrue(payload().encode().length < 200)
    }

    @Test
    fun `survives the whitespace a scanner may hand back`() {
        val original = payload()
        assertEquals(original, EnrollmentPayload.decode("  " + original.encode() + "\n"))
    }

    @Test
    fun `rejects codes that are not GlassesGate codes`() {
        assertNull(EnrollmentPayload.decode(""))
        assertNull(EnrollmentPayload.decode("https://example.com"))
        assertNull(EnrollmentPayload.decode("GG1."))
        assertNull(EnrollmentPayload.decode("GG1.not-valid-base64!!"))
    }

    @Test
    fun `rejects a truncated code rather than throwing`() {
        val encoded = payload().encode()
        assertNull(EnrollmentPayload.decode(encoded.substring(0, encoded.length / 2)))
    }

    @Test
    fun `rejects a version it does not understand`() {
        val body = Base64.getUrlDecoder()
            .decode(payload().encode().removePrefix(EnrollmentPayload.PREFIX))
        body[0] = (EnrollmentPayload.VERSION + 1).toByte()
        val bumped = EnrollmentPayload.PREFIX +
            Base64.getUrlEncoder().withoutPadding().encodeToString(body)
        assertNull(EnrollmentPayload.decode(bumped))
    }

    @Test
    fun `refuses to encode text too long for the wire format`() {
        assertThrows(IllegalArgumentException::class.java) {
            payload(label = "x".repeat(256)).encode()
        }
    }

    @Test
    fun `refuses to encode a wrongly sized secret`() {
        assertThrows(IllegalArgumentException::class.java) {
            payload().copy(secret = ByteArray(8)).encode()
        }
    }

    @Test
    fun `is claimable while the code is freshly on screen`() {
        assertFalse(payload().isExpired(issuedAt))
        assertFalse(payload().isExpired(issuedAt + EnrollmentPayload.DEFAULT_TTL_SECONDS))
    }

    @Test
    fun `stops being claimable once the TTL has passed`() {
        assertTrue(payload().isExpired(issuedAt + EnrollmentPayload.DEFAULT_TTL_SECONDS + 1))
    }

    @Test
    fun `rejects a code stamped far in the future`() {
        // Otherwise an admin phone with a badly wrong clock issues codes that never expire.
        assertTrue(payload(issued = issuedAt + 3600).isExpired(issuedAt))
    }

    @Test
    fun `tolerates minor clock skew between the two phones`() {
        assertFalse(payload(issued = issuedAt + 30).isExpired(issuedAt))
    }

    @Test
    fun `a decoded credential actually drives the beacon`() {
        // The two halves of the system meeting: what the admin encoded into the QR is enough
        // for the user's phone to produce an advertisement the admin will accept.
        val issued = payload()
        val claimed = EnrollmentPayload.decode(issued.encode())!!
        val now = 1_800_000_000_000L

        val (tag, token) = BeaconProtocol.parsePayload(
            BeaconProtocol.buildPayload(claimed.secret, claimed.credentialId, now),
        )!!

        assertTrue(
            BeaconProtocol.matches(tag, token, issued.credentialId, issued.secret, now),
        )
    }
}
