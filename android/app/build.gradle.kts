import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        load(FileInputStream(file))
    }
}

val signalingUrl: String =
    localProperties.getProperty("SIGNALING_URL")
        ?: "ws://192.168.2.114:8080"

android {
    namespace = "com.example.remoteeyes"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.remoteeyes"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "SIGNALING_URL", "\"$signalingUrl\"")
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:5.1.0")
    implementation("io.github.webrtc-sdk:android:137.7151.05")
}
