plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.hello"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.hello"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:5.1.0")
    implementation("io.github.webrtc-sdk:android:137.7151.05")
}
