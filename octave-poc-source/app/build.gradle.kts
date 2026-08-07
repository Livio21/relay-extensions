
plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "org.relay.extensions.octave"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.relay.extensions.octave"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Relay provides the API at runtime – do not package it.
    compileOnly(project(":relay-source-api"))
    // No extra JSON library – use Android's built-in JSONObject.
}