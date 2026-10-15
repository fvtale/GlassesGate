package com.glassesgate.app

import android.app.Application
import android.content.Context
import com.glassesgate.app.admin.ApprovedCredentialStore
import com.glassesgate.app.user.CredentialVault
import com.glassesgate.app.user.GlassesLink

/**
 * Holds the three things that must exist exactly once per process.
 *
 * They used to be constructed wherever they were needed, which meant the beacon service and the
 * screen each had their own credential vault. The service's copy was read when the service was
 * created and never again, so claiming or switching an enrollment while the beacon ran left it
 * broadcasting the old one. One instance each, shared, makes that impossible by construction.
 *
 * Deliberately *not* done here: initializing the DAT SDK. Meta's samples initialize only once the
 * Bluetooth permission is granted, and this runs on first launch before any permission exists.
 * [GlassesLink.start] does it at the right moment.
 */
class GlassesGateApp : Application() {

    val vault: CredentialVault by lazy { CredentialVault(this) }
    val approved: ApprovedCredentialStore by lazy { ApprovedCredentialStore(this) }
    val link: GlassesLink by lazy { GlassesLink(this) }
}

/** The process-wide instances, from any context. */
val Context.glassesGate: GlassesGateApp
    get() = applicationContext as GlassesGateApp
