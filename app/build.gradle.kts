plugins {
    id("com.android.application")
}

android {
    namespace = "com.sameerali.appawake"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sameerali.appawake"
        minSdk = 29
        targetSdk = 36
        versionCode = 22
        versionName = "2.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles("proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = false
    }
}

// Sameer App Awake Auto Lifecycle Guard v2.2.0
// By: Sameer Ali | Contact: sameer43786@gmail.com
