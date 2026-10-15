package com.glassesgate.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.glassesgate.app.admin.ApprovedCredential
import com.glassesgate.app.admin.BeaconScanner
import com.glassesgate.app.glassesGate
import com.glassesgate.app.user.BoundCredential
import com.glassesgate.core.Credentials
import com.glassesgate.core.EnrollmentPayload

/** Why a scanned enrollment code was or was not accepted. */
sealed interface ClaimResult {
    data class Claimed(val credential: BoundCredential) : ClaimResult

    /** The QR was readable but is not a GlassesGate code. */
    data object NotAnEnrollmentCode : ClaimResult

    /** The code was issued too long ago. Ask the admin to generate a fresh one. */
    data object Expired : ClaimResult

    /** No glasses are paired, so there is nothing to bind the credential to. */
    data object NoGlassesConnected : ClaimResult

    /** More than one pair is paired; guessing which one was meant would be wrong. */
    data object AmbiguousGlasses : ClaimResult

    data object AlreadyClaimed : ClaimResult
}

/** What issuing an enrollment produced. */
data class IssuedEnrollment(
    val payload: EnrollmentPayload,
    /** How many older credentials for the same serial were revoked by this one. */
    val replaced: Int,
)

/**
 * The screens' view of the process-wide pieces, plus the gate's scanner, which lives exactly as
 * long as the screen that shows it.
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application.glassesGate

    val vault get() = app.vault
    val approved get() = app.approved
    val link get() = app.link
    val scanner = BeaconScanner(application, app.approved, viewModelScope)

    // ---- Admin ----

    /**
     * Mints a credential for one pair of glasses and adds it to the allowlist.
     *
     * Encoded before it is stored, so an input the wire format cannot carry fails here rather
     * than leaving an orphaned entry behind. Adding it before the wearer has scanned anything is
     * deliberate: an admin who walks away mid-enrollment still has the entry, marked as not yet
     * seen, rather than a QR on screen that matches nothing.
     */
    fun issueEnrollment(serial: String, label: String, gate: String): IssuedEnrollment {
        val payload = EnrollmentPayload(
            credentialId = Credentials.newCredentialId(),
            secret = Credentials.newSecret(),
            serial = normalizeSerial(serial),
            label = label.trim(),
            gate = gate.trim(),
            issuedAtEpochSeconds = nowSeconds(),
        )
        payload.encode() // throws on anything that will not fit; nothing stored yet

        val replaced = approved.enroll(
            ApprovedCredential(
                credentialId = payload.credentialId,
                secret = payload.secret,
                serial = payload.serial,
                label = payload.label,
                issuedAtEpochSeconds = payload.issuedAtEpochSeconds,
                lastSeenEpochSeconds = null,
            ),
        )
        return IssuedEnrollment(payload, replaced)
    }

    fun isSerialEnrolled(serial: String) = approved.isEnrolled(normalizeSerial(serial))

    fun revoke(credential: ApprovedCredential) = approved.remove(credential)

    // ---- Wearer ----

    /**
     * Binds a scanned enrollment to the pair of glasses on this phone.
     *
     * Binds against every pair the SDK knows about rather than only those currently linked, so a
     * slow metadata report cannot make enrollment impossible. That is safe because binding is
     * only half the guarantee: the beacon still refuses to advertise without a live, linked
     * session on this exact pair.
     */
    fun claim(raw: String): ClaimResult {
        val payload = EnrollmentPayload.decode(raw) ?: return ClaimResult.NotAnEnrollmentCode
        if (payload.isExpired(nowSeconds())) return ClaimResult.Expired

        link.start()
        val known = link.knownDeviceIds()
        val deviceId = when {
            known.isEmpty() -> return ClaimResult.NoGlassesConnected
            known.size > 1 -> return ClaimResult.AmbiguousGlasses
            else -> known.single()
        }

        val alreadyHeld = vault.credentials.value.any {
            it.credentialId.contentEquals(payload.credentialId)
        }
        if (alreadyHeld) return ClaimResult.AlreadyClaimed

        val credential = BoundCredential.from(payload, deviceId, nowSeconds())
        vault.save(credential)
        return ClaimResult.Claimed(credential)
    }

    /** The beacon service follows the vault, so forgetting the active credential stops it. */
    fun forget(credential: BoundCredential) = vault.remove(credential)

    private fun nowSeconds() = System.currentTimeMillis() / 1000

    companion object {
        /** Longest text field the enrollment form accepts. Well under the wire format's 255 bytes. */
        const val MAX_FIELD_CHARS = 48

        /**
         * Serials are compared to decide what a re-enrollment replaces, so "rbm 123" and
         * "RBM123" must be the same pair.
         */
        fun normalizeSerial(raw: String): String =
            raw.filterNot { it.isWhitespace() }.uppercase()
    }
}
