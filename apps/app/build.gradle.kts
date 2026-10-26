plugins {
    id("com.android.application")
}

android {
    namespace = "cz.plain.tvtools"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "cz.plain.tvtools"
        minSdk = 28
        targetSdk = 34 // box běží na Androidu 14
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
            // Podepsáno debug klíčem, aby šlo instalovat přes adb bez vlastního keystore.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// Žádné další závislosti: čisté Android API, malé APK a nízká spotřeba RAM.
