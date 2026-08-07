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

    sourceSets.getByName("test").java.directories.add(rootProject.file("source-contract-tests").path)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    val relaySourceApi = project.dependencyFactory.createProjectDependency(":relay-source-api")
    // Relay parent-loads the API: the extension must not package a duplicate copy.
    compileOnly(relaySourceApi)
    testImplementation(relaySourceApi)
    testImplementation("junit:junit:4.13.2")
    // android.jar's org.json methods throw "not mocked" in host-side tests.
    testImplementation("org.json:json:20240303")
}
