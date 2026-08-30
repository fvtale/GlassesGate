package com.glassesgate.core

import java.security.SecureRandom

/**
 * Mints the two random values an enrollment is built from. Both come from [SecureRandom] rather
 * than [kotlin.random.Random], since the secret is what the whole scheme rests on.
 */
object Credentials {

    private val random = SecureRandom()

    fun newCredentialId(): ByteArray =
        ByteArray(BeaconProtocol.CREDENTIAL_ID_BYTES).also { random.nextBytes(it) }

    fun newSecret(): ByteArray =
        ByteArray(BeaconProtocol.SECRET_BYTES).also { random.nextBytes(it) }

    /** Short, stable, human-readable form of a credential id, for logs and list rows. */
    fun shortId(credentialId: ByteArray): String =
        credentialId.take(4).joinToString("") { "%02X".format(it) }
}
