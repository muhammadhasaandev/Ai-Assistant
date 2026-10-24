plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hassan.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hassan.assistant"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.2"
    }

    // A fixed key kept in the repo so every GitHub build can be installed
    // over the previous one without uninstalling.
    signingConfigs {
        create("shared") {
            storeFile = file("assistant.keystore")
            storePassword = "android"
            keyAlias = "assistant"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
