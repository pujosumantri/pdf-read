plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "id.put.pdfbaca"
    compileSdk = 34

    defaultConfig {
        applicationId = "id.put.pdfbaca"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

    // Kunci tanda tangan tetap, supaya APK versi baru bisa langsung menimpa versi lama.
    signingConfigs {
        create("tetap") {
            storeFile = file("pdfbaca.keystore")
            storePassword = "pdfbaca123"
            keyAlias = "pdfbaca"
            keyPassword = "pdfbaca123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("tetap")
        }
        debug {
            signingConfig = signingConfigs.getByName("tetap")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")
}
