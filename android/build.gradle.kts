plugins {
    // AGP 9 compiles Kotlin itself, so there is no separate kotlin-android plugin.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release builds pass -PappVersion=1.2.3 (taken from the git tag).
val appVersion = providers.gradleProperty("appVersion").getOrElse("1.0.0")
val (major, minor, patch) = appVersion.split(".").map { it.toInt() }

android {
    namespace = "earcam.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "earcam.viewer"
        minSdk = 29
        targetSdk = 36
        versionCode = major * 10000 + minor * 100 + patch
        versionName = appVersion
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":protocol"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
}
