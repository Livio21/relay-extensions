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
        versionCode = 2
        versionName = "0.1.1"
    }

    sourceSets.getByName("test").java.directories.add(rootProject.file("source-contract-tests").path)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    val relaySourceApi = project.dependencyFactory.createProjectDependency(":relay-source-api")
    // Relay parent-loads the API: do not package a duplicate in the source APK.
    compileOnly(relaySourceApi)
    testImplementation(relaySourceApi)
    testImplementation("junit:junit:4.13.2")
}
