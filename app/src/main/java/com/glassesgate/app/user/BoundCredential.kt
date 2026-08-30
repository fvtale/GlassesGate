package com.glassesgate.app.user

import com.glassesgate.core.Credentials
import com.glassesgate.core.EnrollmentPayload

/**
 * An enrollment this phone has claimed, tied to one specific pair of glasses.
 *
 * [boundDeviceId] is the string form of the DAT `DeviceIdentifier` that was connected at the
 * moment the QR was scanned. It is what makes the credential mean "these glasses are here"
 * rather than "this phone has a secret": the beacon refuses to advertise unless a DAT session
 * with that same device is live. See [BeaconService].
 */
data class BoundCredential(
    val credentialId: ByteArray,
    val secret: ByteArray,
    val serial: String,
    val label: String,
    val gate: String,
    val boundDeviceId: String,
    val claimedAtEpochSeconds: Long,
) {
    /** Stable key for the store, and what the UI shows when two enrollments look alike. */
    val shortId: String get() = Credentials.shortId(credentialId)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BoundCredential) return false
        return credentialId.contentEquals(other.credentialId) &&
            secret.contentEquals(other.secret) &&
            serial == other.serial &&
            label == other.label &&
            gate == other.gate &&
            boundDeviceId == other.boundDeviceId &&
            claimedAtEpochSeconds == other.claimedAtEpochSeconds
    }

    override fun hashCode(): Int {
        var result = credentialId.contentHashCode()
        result = 31 * result + secret.contentHashCode()
        result = 31 * result + serial.hashCode()
        result = 31 * result + label.hashCode()
        result = 31 * result + gate.hashCode()
        result = 31 * result + boundDeviceId.hashCode()
        result = 31 * result + claimedAtEpochSeconds.hashCode()
        return result
    }

    companion object {
        fun from(
            payload: EnrollmentPayload,
            boundDeviceId: String,
            claimedAtEpochSeconds: Long,
        ) = BoundCredential(
            credentialId = payload.credentialId,
            secret = payload.secret,
            serial = payload.serial,
            label = payload.label,
            gate = payload.gate,
            boundDeviceId = boundDeviceId,
            claimedAtEpochSeconds = claimedAtEpochSeconds,
        )
    }
}
