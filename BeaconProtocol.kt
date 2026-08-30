package com.glassesgate.app.core

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The over-the-air proximity beacon used between a GlassesGate "User" device (a phone with
 * an active glasses session) and a GlassesGate "Admin" device (the scanner).
 *
 * Meta's public glasses SDK doesn't let a third-party device scan the glasses directly --
 * pairing is glasses <-> Meta AI app <-> one phone. So this "beacon" is actually broadcast by
 * the USER'S PHONE once it has confirmed a live glasses session, not by the glasses
 * themselves. The Admin device never talks to the glasses at all.
 *
 * token = truncated HMAC-SHA256(secret, deviceId || timeWindow), rotated every
 * ROTATION_SECONDS so a captured token can't be replayed indefinitely.
 */
object BeaconProtocol {

    /**
     * Custom 128-bit service UUID GlassesGate advertises/scans for. Regenerate per deployment
     * if you want to avoid colliding with other installs reusing this same UUID.
     */
    const val SERVICE_UUID_STRING = "8c9c1a2e-6b8b-4c0a-9c0e-1f7c2b6a9d10"

    /**
     * How often the rotating token changes. The Admin checks the current AND previous window
     * to allow for clock drift and scan timing slop.
     */
    const val ROTATION_SECONDS = 30L

    /** Length of the device tag sent in the clear. */
    const val TAG_BYTES = 4

    /**
     * Bytes of the HMAC actually transmitted -- small enough to fit in a BLE service-data
     * field alongside the device tag (4 + 8 = 12 bytes, well within the ~20 usable bytes
     * of a standard BLE advertisement).
     */
    const val TOKEN_BYTES = 8

    /** Total service-data payload length. */
    const val PAYLOAD_BYTES = TAG_BYTES + TOKEN_BYTES

    private fun hmac(secret: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return mac.doFinal(message)
    }

    /**
     * Deterministic short tag derived from a device identifier, sent in the clear so the Admin
     * device knows which stored (deviceId, secret) pair to check the token against, without
     * broadcasting the raw device identifier over the air.
     */
    fun deviceTag(deviceIdentifier: String): ByteArray =
        MessageDigest.getInstance("SHA-256")
            .digest(deviceIdentifier.toByteArray())
            .copyOf(TAG_BYTES)

    fun currentWindow(nowMillis: Long = System.currentTimeMillis()): Long =
        nowMillis / 1000 / ROTATION_SECONDS

    fun tokenFor(secret: ByteArray, deviceIdentifier: String, window: Long): ByteArray {
        val message = deviceIdentifier.toByteArray() + window.toString().toByteArray()
        return hmac(secret, message).copyOf(TOKEN_BYTES)
    }

    /** Builds the full advertised payload: 4-byte tag followed by the 8-byte rotating token. */
    fun buildPayload(
        secret: ByteArray,
        deviceIdentifier: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): ByteArray =
        deviceTag(deviceIdentifier) + tokenFor(secret, deviceIdentifier, currentWindow(nowMillis))

    /**
     * Checks a received (tag, token) pair against one stored (deviceIdentifier, secret),
     * allowing the current and immediately previous rotation window.
     */
    fun matches(
        receivedTag: ByteArray,
        receivedToken: ByteArray,
        storedDeviceIdentifier: String,
        storedSecret: ByteArray,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!receivedTag.contentEquals(deviceTag(storedDeviceIdentifier))) return false
        val window = currentWindow(nowMillis)
        return receivedToken.contentEquals(tokenFor(storedSecret, storedDeviceIdentifier, window)) ||
            receivedToken.contentEquals(tokenFor(storedSecret, storedDeviceIdentifier, window - 1))
    }
}
