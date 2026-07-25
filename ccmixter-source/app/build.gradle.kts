plugins {
    id("com.android.application")
}

android {
    namespace = "org.relay.extensions.ccmixter"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.relay.extensions.ccmixter"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Relay parent-loads the API: do not package a duplicate in the source APK.
    compileOnly(project(":relay-source-api"))
}
