// PracticeFood: a small test app used ONLY by the emulator end-to-end tests (android/e2e).
// It is not part of TeachFlow and has no dependencies.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.teachflow.practicefood"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.teachflow.practicefood"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0-test"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
