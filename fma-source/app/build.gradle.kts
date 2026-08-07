plugins {
    id("com.android.application")
}

android {
    namespace = "org.relay.extensions.fma"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.relay.extensions.fma"
        minSdk = 23
        targetSdk = 36
        versionCode = providers.gradleProperty("relayVersionCode").map(String::toInt).orElse(5).get()
        versionName = providers.gradleProperty("relayVersion").orElse("0.1.4").get()
    }

    val relayKeystore = providers.gradleProperty("relayKeystoreFile")
    val relayStorePassword = providers.gradleProperty("relayKeystorePassword")
    val relayKeyAlias = providers.gradleProperty("relayKeyAlias")
    val relayKeyPassword = providers.gradleProperty("relayKeyPassword")
    if (relayKeystore.isPresent && relayStorePassword.isPresent && relayKeyAlias.isPresent && relayKeyPassword.isPresent) {
        signingConfigs.create("relayRelease") {
            storeFile = file(relayKeystore.get())
            storePassword = relayStorePassword.get()
            keyAlias = relayKeyAlias.get()
            keyPassword = relayKeyPassword.get()
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("relayRelease")
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
