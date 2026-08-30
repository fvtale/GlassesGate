import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.glassesgate.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.glassesgate.app"
        // API 31 is the floor for BLUETOOTH_SCAN / BLUETOOTH_ADVERTISE, and matches what
        // Meta's own DAT samples target. Below it we would need the legacy Bluetooth
        // permission set plus ACCESS_FINE_LOCATION, on phones too old to be worth supporting.
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.2.0"

        // 0/0 is the Developer Mode placeholder; see gradle.properties.
        manifestPlaceholders["mwdatApplicationId"] =
            (project.findProperty("MWDAT_APPLICATION_ID") ?: "0").toString()
        manifestPlaceholders["mwdatClientToken"] =
            (project.findProperty("MWDAT_CLIENT_TOKEN") ?: "0").toString()
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions { jvmTarget = JvmTarget.JVM_17 }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.coroutines.android)

    // Enrollment QR: ZXing writes the code on the admin side, ML Kit reads it on the user side.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.zxing.core)

    // Meta Wearables Device Access Toolkit.
    implementation(libs.mwdat.core)
    // Lets the whole user flow be exercised without physical glasses on hand.
    debugImplementation(libs.mwdat.mockdevice)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
