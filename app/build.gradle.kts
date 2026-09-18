import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.lemoneko.endfieldcharge"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lemoneko.endfieldcharge"
        minSdk = 35
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources {
            // Keep the libxposed entry descriptors; the default merger is happy with them,
            // this is only here to make the intent explicit.
            excludes += setOf()
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    // Provided by the Xposed framework at runtime; must never be bundled into the APK.
    compileOnly(libs.libxposed.api)
    compileOnly(libs.androidx.annotation)

    testImplementation(libs.junit)
    // android.jar only ships throwing stubs for org.json, so the JVM tests need the real one.
    testImplementation(libs.json)
}
