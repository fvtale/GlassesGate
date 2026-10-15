package com.glassesgate.app.user

import android.content.Context
import com.glassesgate.app.data.SecurePrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * The user side's own credentials, encrypted at rest.
 *
 * A phone can hold several -- one per gate that has enrolled it -- but only one is advertised at
 * a time, because a BLE advertisement carries a single service-data payload and cycling between
 * them would make the gate slower for everyone to cover a case most people never hit. [active]
 * is the one the beacon uses; it defaults to the most recently claimed.
 */
class CredentialVault(context: Context) {

    private val prefs = SecurePrefs.open(context, PREFS_NAME)

    private val _credentials = MutableStateFlow(readAll())
    val credentials: StateFlow<List<BoundCredential>> = _credentials.asStateFlow()

    private val _activeId = MutableStateFlow(readActiveId())
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    private val _active = MutableStateFlow(resolveActive())

    /**
     * The credential the beacon should be advertising, observable so the service follows a
     * switch or a removal the moment it happens rather than broadcasting a stale one.
     */
    val active: StateFlow<BoundCredential?> = _active.asStateFlow()

    fun save(credential: BoundCredential) {
        prefs.edit()
            .putString(key(credential), credential.toJson())
            .putString(KEY_ACTIVE, credential.shortId)
            .apply()
        refresh()
    }

    fun remove(credential: BoundCredential) {
        prefs.edit().remove(key(credential)).apply()
        // Dropping the active credential would otherwise leave the beacon pointing at nothing.
        if (_activeId.value == credential.shortId) {
            val next = readAll().firstOrNull { it.shortId != credential.shortId }
            prefs.edit().apply {
                if (next == null) remove(KEY_ACTIVE) else putString(KEY_ACTIVE, next.shortId)
            }.apply()
        }
        refresh()
    }

    fun setActive(credential: BoundCredential) {
        prefs.edit().putString(KEY_ACTIVE, credential.shortId).apply()
        refresh()
    }

    // Synchronized because the screen writes on the main thread while the beacon service reads
    // from its own; the three flows must change together or a reader can see a half-update.
    @Synchronized
    private fun refresh() {
        _credentials.value = readAll()
        _activeId.value = readActiveId()
        _active.value = resolveActive()
    }

    private fun resolveActive(): BoundCredential? =
        _credentials.value.firstOrNull { it.shortId == _activeId.value }

    private fun readActiveId(): String? {
        val stored = prefs.getString(KEY_ACTIVE, null)
        val all = readAll()
        return all.firstOrNull { it.shortId == stored }?.shortId ?: all.firstOrNull()?.shortId
    }

    private fun readAll(): List<BoundCredential> =
        prefs.all.entries
            .filter { it.key.startsWith(KEY_PREFIX) }
            .mapNotNull { (_, value) -> (value as? String)?.let(::fromJson) }
            .sortedByDescending { it.claimedAtEpochSeconds }

    private fun key(credential: BoundCredential) =
        KEY_PREFIX + SecurePrefs.hex(credential.credentialId)

    private fun BoundCredential.toJson(): String = JSONObject()
        .put(FIELD_CREDENTIAL_ID, SecurePrefs.encode(credentialId))
        .put(FIELD_SECRET, SecurePrefs.encode(secret))
        .put(FIELD_SERIAL, serial)
        .put(FIELD_LABEL, label)
        .put(FIELD_GATE, gate)
        .put(FIELD_DEVICE, boundDeviceId)
        .put(FIELD_CLAIMED_AT, claimedAtEpochSeconds)
        .toString()

    private fun fromJson(raw: String): BoundCredential? = runCatching {
        val json = JSONObject(raw)
        BoundCredential(
            credentialId = SecurePrefs.decode(json.getString(FIELD_CREDENTIAL_ID)),
            secret = SecurePrefs.decode(json.getString(FIELD_SECRET)),
            serial = json.optString(FIELD_SERIAL),
            label = json.optString(FIELD_LABEL),
            gate = json.optString(FIELD_GATE),
            boundDeviceId = json.getString(FIELD_DEVICE),
            claimedAtEpochSeconds = json.getLong(FIELD_CLAIMED_AT),
        )
    }.getOrNull()

    private companion object {
        const val PREFS_NAME = "glassesgate_user_credentials"
        const val KEY_PREFIX = "cred:"
        const val KEY_ACTIVE = "active"

        const val FIELD_CREDENTIAL_ID = "id"
        const val FIELD_SECRET = "secret"
        const val FIELD_SERIAL = "serial"
        const val FIELD_LABEL = "label"
        const val FIELD_GATE = "gate"
        const val FIELD_DEVICE = "device"
        const val FIELD_CLAIMED_AT = "claimedAt"
    }
}
