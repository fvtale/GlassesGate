package com.glassesgate.app.admin

import android.content.Context
import com.glassesgate.app.data.SecurePrefs
import com.glassesgate.core.BeaconProtocol
import com.glassesgate.core.Credentials
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * One pair of glasses this admin device will admit.
 *
 * [serial] is the number the admin read off the glasses. It is a human label only -- it is never
 * broadcast and never checked cryptographically, because Meta's SDK exposes no way for one
 * device to verify another device's serial. What actually gates entry is [secret].
 */
data class ApprovedCredential(
    val credentialId: ByteArray,
    val secret: ByteArray,
    val serial: String,
    val label: String,
    val issuedAtEpochSeconds: Long,
    /** Null until this credential has been seen at the gate at least once. */
    val lastSeenEpochSeconds: Long?,
) {
    val shortId: String get() = Credentials.shortId(credentialId)

    /** Precomputed so the scan hot path never hashes. */
    val tagKey: String by lazy { SecurePrefs.hex(BeaconProtocol.credentialTag(credentialId)) }

    /** True until the user has claimed the QR and walked past the gate once. */
    val isUnclaimed: Boolean get() = lastSeenEpochSeconds == null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ApprovedCredential) return false
        return credentialId.contentEquals(other.credentialId) &&
            secret.contentEquals(other.secret) &&
            serial == other.serial &&
            label == other.label &&
            issuedAtEpochSeconds == other.issuedAtEpochSeconds &&
            lastSeenEpochSeconds == other.lastSeenEpochSeconds
    }

    override fun hashCode(): Int {
        var result = credentialId.contentHashCode()
        result = 31 * result + secret.contentHashCode()
        result = 31 * result + serial.hashCode()
        result = 31 * result + label.hashCode()
        result = 31 * result + issuedAtEpochSeconds.hashCode()
        result = 31 * result + (lastSeenEpochSeconds?.hashCode() ?: 0)
        return result
    }
}

/**
 * The admin device's allowlist: encrypted, on-device, and deliberately offline.
 *
 * No cloud and no backend means a gate keeps working when the venue's wifi does not, and that
 * nobody's movements are being logged to a server. The cost is that the list is per-device --
 * enrolling at the front door does not enroll you at the loading dock.
 */
class ApprovedCredentialStore(context: Context) {

    private val prefs = SecurePrefs.open(context, PREFS_NAME)

    private val _credentials = MutableStateFlow(readAll())
    val credentials: StateFlow<List<ApprovedCredential>> = _credentials.asStateFlow()

    fun add(credential: ApprovedCredential) {
        prefs.edit().putString(key(credential.credentialId), credential.toJson()).apply()
        _credentials.value = readAll()
    }

    fun remove(credential: ApprovedCredential) {
        prefs.edit().remove(key(credential.credentialId)).apply()
        _credentials.value = readAll()
    }

    /**
     * Records that this credential was just admitted. Called off the scan hot path -- once per
     * arrival, not once per advertisement -- so it never turns a decrypt-and-write into a
     * per-packet cost.
     */
    fun markSeen(credential: ApprovedCredential, atEpochSeconds: Long) {
        add(credential.copy(lastSeenEpochSeconds = atEpochSeconds))
    }

    private fun key(credentialId: ByteArray) = KEY_PREFIX + SecurePrefs.hex(credentialId)

    private fun readAll(): List<ApprovedCredential> =
        prefs.all.entries
            .filter { it.key.startsWith(KEY_PREFIX) }
            .mapNotNull { (_, value) -> (value as? String)?.let(::fromJson) }
            .sortedBy { it.label.lowercase() }

    private fun ApprovedCredential.toJson(): String = JSONObject()
        .put(FIELD_CREDENTIAL_ID, SecurePrefs.encode(credentialId))
        .put(FIELD_SECRET, SecurePrefs.encode(secret))
        .put(FIELD_SERIAL, serial)
        .put(FIELD_LABEL, label)
        .put(FIELD_ISSUED_AT, issuedAtEpochSeconds)
        .apply { lastSeenEpochSeconds?.let { put(FIELD_LAST_SEEN, it) } }
        .toString()

    private fun fromJson(raw: String): ApprovedCredential? = runCatching {
        val json = JSONObject(raw)
        ApprovedCredential(
            credentialId = SecurePrefs.decode(json.getString(FIELD_CREDENTIAL_ID)),
            secret = SecurePrefs.decode(json.getString(FIELD_SECRET)),
            serial = json.optString(FIELD_SERIAL),
            label = json.optString(FIELD_LABEL),
            issuedAtEpochSeconds = json.getLong(FIELD_ISSUED_AT),
            lastSeenEpochSeconds =
                if (json.has(FIELD_LAST_SEEN)) json.getLong(FIELD_LAST_SEEN) else null,
        )
    }.getOrNull()

    private companion object {
        const val PREFS_NAME = "glassesgate_approved_credentials"
        const val KEY_PREFIX = "cred:"

        const val FIELD_CREDENTIAL_ID = "id"
        const val FIELD_SECRET = "secret"
        const val FIELD_SERIAL = "serial"
        const val FIELD_LABEL = "label"
        const val FIELD_ISSUED_AT = "issuedAt"
        const val FIELD_LAST_SEEN = "lastSeen"
    }
}
