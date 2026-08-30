plugins {
    alias(libs.plugins.kotlin.jvm)
}

// No Android dependency on purpose: the beacon and enrollment formats are the part
// most worth testing, and keeping them here means `./gradlew :core:test` runs anywhere
// without an Android SDK or a DAT package token.
kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
}
