package com.glassesgate.core

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The over-the-air proof that an approved pair of glasses is present.
 *
 * ## What is actually being proved
 *
 * Meta's public SDK does not let a third-party device see someone else's glasses. Glasses pair
 * to exactly one phone through the Meta AI app, and only that phone can hold a DAT session with
 * them. So the beacon is broadcast by the *user's phone*, and only while it holds a live session
 * with the specific pair of glasses that was enrolled. The admin device never talks to the
 * glasses.
 *
 * ```
 * glasses <--DAT session--> user's phone --BLE advert--> admin's phone
 * ```
 *
 * ## The scheme
 *
 * At enrollment the admin mints a random [CREDENTIAL_ID_BYTES]-byte credential id and a
 * [SECRET_BYTES]-byte secret, and hands both to one user over a QR code. Note the credential id
 * is admin-generated rather than derived from the glasses: the admin has no way to learn the
 * glasses' DAT identifier, and does not need to. Binding the credential to a particular pair of
 * glasses is enforced on the user's side, by refusing to advertise unless that pair is connected.
 *
 * The advertised payload is [PAYLOAD_BYTES] bytes:
 *
 * ```
 * [0, TAG_BYTES)                 tag   = SHA-256(credentialId) truncated
 * [TAG_BYTES, PAYLOAD_BYTES)     token = HMAC-SHA256(secret, credentialId || window) truncated
 * ```
 *
 * The tag is a stable, non-reversible handle that tells the admin which stored secret to check
 * the token against, so matching stays O(1)-ish instead of trying every enrolled credential. The
 * token rotates every [ROTATION_SECONDS], which is what bounds how long a captured advertisement
 * stays useful.
 */
object BeaconProtocol {

    /**
     * The 128-bit service UUID GlassesGate advertises and scans for. Regenerate this per
     * deployment if you would rather not share a UUID with other installs.
     */
    const val SERVICE_UUID_STRING = "8c9c1a2e-6b8b-4c0a-9c0e-1f7c2b6a9d10"

    /** How long one token is valid for. */
    const val ROTATION_SECONDS = 15L

    /**
     * How many rotation windows either side of the current one a verifier will accept, to absorb
     * clock skew between the two phones and the lag between an advertisement being built and
     * being received. One window each way puts the worst-case replay window at
     * `ROTATION_SECONDS * 3`; drop it to 0 only if both devices are reliably time-synced.
     */
    const val DRIFT_WINDOWS = 1

    const val CREDENTIAL_ID_BYTES = 16
    const val SECRET_BYTES = 32

    const val TAG_BYTES = 4

    /**
     * Bytes of HMAC actually transmitted. 4 + 8 = 12 bytes of service data fits comfortably in
     * the ~20 usable bytes of a legacy BLE advertisement, which is the floor we design for.
     */
    const val TOKEN_BYTES = 8

    const val PAYLOAD_BYTES = TAG_BYTES + TOKEN_BYTES

    /**
     * Non-reversible handle for a credential, sent in the clear. Truncating to [TAG_BYTES] means
     * collisions are possible in principle; a collision only costs an extra HMAC check, since the
     * token is what actually decides the match.
     */
    fun credentialTag(credentialId: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(credentialId).copyOf(TAG_BYTES)

    fun currentWindow(nowMillis: Long): Long = Math.floorDiv(nowMillis, ROTATION_SECONDS * 1000L)

    /**
     * When the window containing [nowMillis] ends. The advertiser sleeps until exactly this point
     * rather than re-advertising on a fixed interval, so every rotation is published as soon as
     * it becomes valid and no radio time is spent republishing an unchanged payload.
     */
    fun nextWindowBoundaryMillis(nowMillis: Long): Long =
        (currentWindow(nowMillis) + 1) * ROTATION_SECONDS * 1000L

    fun tokenFor(secret: ByteArray, credentialId: ByteArray, window: Long): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        mac.update(credentialId)
        mac.update(windowBytes(window))
        return mac.doFinal().copyOf(TOKEN_BYTES)
    }

    /** The full advertised payload: [TAG_BYTES] of tag followed by [TOKEN_BYTES] of token. */
    fun buildPayload(secret: ByteArray, credentialId: ByteArray, nowMillis: Long): ByteArray =
        credentialTag(credentialId) + tokenFor(secret, credentialId, currentWindow(nowMillis))

    /**
     * Checks a received advertisement against one stored credential, accepting any window within
     * [DRIFT_WINDOWS] of the current one.
     */
    fun matches(
        receivedTag: ByteArray,
        receivedToken: ByteArray,
        credentialId: ByteArray,
        secret: ByteArray,
        nowMillis: Long,
    ): Boolean {
        // The tag is public, so a plain comparison leaks nothing; it exists to skip the HMACs.
        if (!receivedTag.contentEquals(credentialTag(credentialId))) return false
        if (receivedToken.size != TOKEN_BYTES) return false

        val window = currentWindow(nowMillis)
        for (offset in -DRIFT_WINDOWS..DRIFT_WINDOWS) {
            val candidate = tokenFor(secret, credentialId, window + offset)
            // Constant-time: an attacker who could time this loop could otherwise recover a
            // valid token a byte at a time.
            if (MessageDigest.isEqual(receivedToken, candidate)) return true
        }
        return false
    }

    /** Splits a received service-data blob into (tag, token), or null if it is the wrong shape. */
    fun parsePayload(serviceData: ByteArray?): Pair<ByteArray, ByteArray>? {
        if (serviceData == null || serviceData.size < PAYLOAD_BYTES) return null
        return serviceData.copyOfRange(0, TAG_BYTES) to
            serviceData.copyOfRange(TAG_BYTES, PAYLOAD_BYTES)
    }

    private fun windowBytes(window: Long): ByteArray =
        ByteArray(8) { i -> (window ushr (56 - 8 * i)).toByte() }
}
