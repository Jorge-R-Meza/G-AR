plugins {
    id("com.android.application")
}

android {
    namespace = "com.hackathon.glasseshello"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hackathon.glasseshello"
        // The M6 glasses run Android 9 (API 28).
        minSdk = 28
        targetSdk = 28
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // targetSdk 28 is deliberate: it matches the glasses.
        disable += "ExpiredTargetSdkVersion"
    }
}
