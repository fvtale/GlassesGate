package com.glassesgate.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.glassesgate.app.admin.ApprovedCredential
import com.glassesgate.app.admin.ApprovedCredentialStore
import com.glassesgate.app.admin.BeaconScanner
import com.glassesgate.app.user.BoundCredential
import com.glassesgate.app.user.CredentialVault
import com.glassesgate.app.user.GlassesLink
import com.glassesgate.core.Credentials
import com.glassesgate.core.EnrollmentPayload

/** Why a scanned enrollment code was or was not accepted. */
sealed interface ClaimResult {
    data class Claimed(val credential: BoundCredential) : ClaimResult

    /** The QR was readable but is not a GlassesGate code. */
    data object NotAnEnrollmentCode : ClaimResult

    /** The code was issued too long ago. Ask the admin to generate a fresh one. */
    data object Expired : ClaimResult

    /** No glasses are connected, so there is nothing to bind the credential to. */
    data object NoGlassesConnected : ClaimResult

    /** More than one pair is connected; guessing which one was meant would be wrong. */
    data object AmbiguousGlasses : ClaimResult

    data object AlreadyClaimed : ClaimResult
}

/**
 * Holds the pieces both roles need for as long as the app is on screen. The DAT link lives here
 * rather than in a composable so that a rotation does not tear down a session mid-enrollment.
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {

    val vault = CredentialVault(application)
    val approved = ApprovedCredentialStore(application)
    val link = GlassesLink(viewModelScope)
    val scanner = BeaconScanner(application, approved, viewModelScope)

    // ---- Admin ----

    /**
     * Mints a credential for one pair of glasses and adds it to the allowlist immediately, so an
     * admin who walks away mid-enrollment still has the entry (marked unclaimed) rather than
     * losing it. The returned payload is what goes on screen as a QR.
     */
    fun issueEnrollment(serial: String, label: String, gate: String): EnrollmentPayload {
        val payload = EnrollmentPayload(
            credentialId = Credentials.newCredentialId(),
            secret = Credentials.newSecret(),
            serial = serial.trim(),
            label = label.trim(),
            gate = gate.trim(),
            issuedAtEpochSeconds = nowSeconds(),
        )
        approved.add(
            ApprovedCredential(
                credentialId = payload.credentialId,
                secret = payload.secret,
                serial = payload.serial,
                label = payload.label,
                issuedAtEpochSeconds = payload.issuedAtEpochSeconds,
                lastSeenEpochSeconds = null,
            ),
        )
        return payload
    }

    fun revoke(credential: ApprovedCredential) = approved.remove(credential)

    // ---- User ----

    /**
     * Binds a scanned enrollment to the pair of glasses connected right now.
     *
     * The binding is the point: without it the credential would live on the phone alone, and the
     * glasses would be decoration.
     */
    fun claim(raw: String): ClaimResult {
        val payload = EnrollmentPayload.decode(raw) ?: return ClaimResult.NotAnEnrollmentCode
        if (payload.isExpired(nowSeconds())) return ClaimResult.Expired

        // Bind against every pair the SDK knows about, not only those whose metadata has
        // arrived. The binding is only half the guarantee; the beacon still refuses to
        // advertise without a live session with this exact pair.
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

    fun forget(credential: BoundCredential) = vault.remove(credential)

    private fun nowSeconds() = System.currentTimeMillis() / 1000
}
