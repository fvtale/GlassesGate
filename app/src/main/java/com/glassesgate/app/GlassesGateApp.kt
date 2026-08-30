package com.glassesgate.app

import android.app.Application
import com.meta.wearable.dat.core.Wearables

class GlassesGateApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Once per process, before any other Wearables call. Anything earlier comes back as
        // WearablesError.NOT_INITIALIZED, including from the beacon service, which can be
        // started by the system without the activity ever having run.
        Wearables.initialize(this)
    }
}
