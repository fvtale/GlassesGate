package com.glassesgate.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Both roles hold long-lived secrets on disk -- the admin holds every enrolled credential, the
 * user holds their own -- so both go through [EncryptedSharedPreferences] backed by a keystore
 * master key rather than plain preferences.
 *
 * This protects the secrets at rest against another app or an offline dump of the data
 * directory. It does not protect them from a rooted device or from someone holding an unlocked
 * phone, which is why revocation exists.
 */
internal object SecurePrefs {

    fun open(context: Context, name: String): SharedPreferences =
        EncryptedSharedPreferences.create(
            context.applicationContext,
            name,
            MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    fun decode(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
