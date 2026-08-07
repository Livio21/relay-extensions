plugins {
    id("com.android.application")
}

android {
    namespace = "org.relay.extensions.octavepoc"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.relay.extensions.octavepoc"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.1-poc"
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Relay parent-loads the API: the extension must not package a duplicate copy.
    compileOnly(project(":relay-source-api"))
    testImplementation("junit:junit:4.13.2")
}
