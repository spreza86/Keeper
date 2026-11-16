plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.preza.keeper"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.preza.keeper"
        minSdk = 30          // Android 11: needed for the system trash
        targetSdk = 35
        versionCode = 10
        versionName = "0.7.3"
    }

    // Fixed signing key, kept in the repo: every build from any session signs the same way,
    // so a new version always installs over the old one. Personal app, never published.
    signingConfigs {
        getByName("debug") {
            storeFile = file("keeper-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // On-device face detection. The model ships inside the app, nothing is downloaded.
    implementation("com.google.mlkit:face-detection:16.1.7")
    // On-device detection of the main subject (animals, objects). Bundled model, nothing is downloaded.
    implementation("com.google.mlkit:object-detection:17.0.2")

    // Background scan that continues after the app is closed and resumes where it stopped.
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
}
