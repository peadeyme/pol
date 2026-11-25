plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: CI decodes the keystore from repository secrets into this path.
// Without secrets the release APK falls back to the debug key (fine for testing only).
val releaseKeystore = file(System.getenv("POL_KEYSTORE_PATH") ?: "release.jks")

android {
    namespace = "ir.pedicode.pol"
    compileSdk = 34

    defaultConfig {
        applicationId = "ir.pedicode.pol"
        minSdk = 29
        targetSdk = 34
        versionCode = (System.getenv("POL_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("POL_VERSION_NAME") ?: "0.1.0"
    }

    signingConfigs {
        create("release") {
            if (releaseKeystore.exists()) {
                storeFile = releaseKeystore
                storePassword = System.getenv("POL_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("POL_KEY_ALIAS")
                keyPassword = System.getenv("POL_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (releaseKeystore.exists()) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
