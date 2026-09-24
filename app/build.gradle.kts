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
        versionCode = 5
        versionName = "1.2.2"
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
