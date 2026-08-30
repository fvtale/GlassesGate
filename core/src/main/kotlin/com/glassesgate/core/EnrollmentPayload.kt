package com.glassesgate.core

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64

/**
 * What an admin device puts into an enrollment QR code, and a user device reads back.
 *
 * Enrollment runs admin -> user: the admin types in the glasses' serial number, the app mints a
 * credential, and the user scans it to claim it. That direction matters, because it means the
 * admin's approved list is authored by the admin and never has to trust anything a stranger's
 * phone asserts about itself.
 *
 * The QR is a bearer credential for as long as it is on screen: whoever scans it first gets the
 * credential. [issuedAtEpochSeconds] is what keeps a photographed code from being useful
 * indefinitely -- see [isExpired].
 *
 * Wire format is a compact binary blob rather than JSON, so the QR stays low-density enough to
 * scan quickly at arm's length:
 *
 * ```
 * "GG1." + base64url-unpadded(
 *     u8  version
 *     u8  credentialId length, then that many bytes
 *     u8  secret length, then that many bytes
 *     i64 issuedAtEpochSeconds, big endian
 *     u8  serial length, then that many UTF-8 bytes
 *     u8  label  length, then that many UTF-8 bytes
 *     u8  gate   length, then that many UTF-8 bytes
 * )
 * ```
 */
data class EnrollmentPayload(
    /** Admin-generated credential id. Not derived from the glasses -- see [BeaconProtocol]. */
    val credentialId: ByteArray,
    val secret: ByteArray,
    /** The serial number the admin read off the glasses. Human-facing; never broadcast. */
    val serial: String,
    /** Display name for this user, shown on the gate screen when they are recognised. */
    val label: String,
    /** Which gate or venue issued this, so a user can tell two enrollments apart. */
    val gate: String,
    val issuedAtEpochSeconds: Long,
) {

    fun encode(): String {
        require(credentialId.size == BeaconProtocol.CREDENTIAL_ID_BYTES) {
            "credentialId must be ${BeaconProtocol.CREDENTIAL_ID_BYTES} bytes"
        }
        require(secret.size == BeaconProtocol.SECRET_BYTES) {
            "secret must be ${BeaconProtocol.SECRET_BYTES} bytes"
        }

        val body = ByteArrayOutputStream()
        DataOutputStream(body).use { out ->
            out.writeByte(VERSION)
            out.writeLengthPrefixed(credentialId)
            out.writeLengthPrefixed(secret)
            out.writeLong(issuedAtEpochSeconds)
            out.writeLengthPrefixed(serial.encodeToByteArray(), "serial")
            out.writeLengthPrefixed(label.encodeToByteArray(), "label")
            out.writeLengthPrefixed(gate.encodeToByteArray(), "gate")
        }
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(body.toByteArray())
    }

    /**
     * True once the code has been on screen longer than [ttlSeconds]. The user app enforces this
     * so that a QR someone photographed over the admin's shoulder stops working shortly after the
     * enrollment it was meant for.
     */
    fun isExpired(nowEpochSeconds: Long, ttlSeconds: Long = DEFAULT_TTL_SECONDS): Boolean =
        nowEpochSeconds - issuedAtEpochSeconds > ttlSeconds ||
            // A code stamped meaningfully in the future means one of the two clocks is wrong,
            // and trusting it would extend the TTL by however far off it is.
            issuedAtEpochSeconds - nowEpochSeconds > CLOCK_SKEW_TOLERANCE_SECONDS

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EnrollmentPayload) return false
        return credentialId.contentEquals(other.credentialId) &&
            secret.contentEquals(other.secret) &&
            serial == other.serial &&
            label == other.label &&
            gate == other.gate &&
            issuedAtEpochSeconds == other.issuedAtEpochSeconds
    }

    override fun hashCode(): Int {
        var result = credentialId.contentHashCode()
        result = 31 * result + secret.contentHashCode()
        result = 31 * result + serial.hashCode()
        result = 31 * result + label.hashCode()
        result = 31 * result + gate.hashCode()
        result = 31 * result + issuedAtEpochSeconds.hashCode()
        return result
    }

    companion object {
        const val VERSION = 1
        const val PREFIX = "GG1."

        /** How long an enrollment QR stays claimable. */
        const val DEFAULT_TTL_SECONDS = 10L * 60L

        private const val CLOCK_SKEW_TOLERANCE_SECONDS = 5L * 60L

        /** Returns null for anything that isn't a well-formed GlassesGate enrollment code. */
        fun decode(raw: String): EnrollmentPayload? {
            val trimmed = raw.trim()
            if (!trimmed.startsWith(PREFIX)) return null

            return runCatching {
                val body = Base64.getUrlDecoder().decode(trimmed.removePrefix(PREFIX))
                DataInputStream(body.inputStream()).use { input ->
                    if (input.readUnsignedByte() != VERSION) return null
                    val credentialId = input.readLengthPrefixed()
                    val secret = input.readLengthPrefixed()
                    val issuedAt = input.readLong()
                    val serial = String(input.readLengthPrefixed(), Charsets.UTF_8)
                    val label = String(input.readLengthPrefixed(), Charsets.UTF_8)
                    val gate = String(input.readLengthPrefixed(), Charsets.UTF_8)

                    if (credentialId.size != BeaconProtocol.CREDENTIAL_ID_BYTES) return null
                    if (secret.size != BeaconProtocol.SECRET_BYTES) return null

                    EnrollmentPayload(credentialId, secret, serial, label, gate, issuedAt)
                }
            }.getOrNull()
        }

        private fun DataOutputStream.writeLengthPrefixed(bytes: ByteArray, name: String = "field") {
            require(bytes.size <= 255) { "$name is too long to encode (${bytes.size} bytes, max 255)" }
            writeByte(bytes.size)
            write(bytes)
        }

        private fun DataInputStream.readLengthPrefixed(): ByteArray {
            val length = readUnsignedByte()
            return ByteArray(length).also { readFully(it) }
        }
    }
}
