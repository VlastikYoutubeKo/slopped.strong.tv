plugins {
    id("com.android.application")
}

android {
    namespace = "cz.plain.odjezdytv"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "cz.plain.odjezdytv"
        minSdk = 30 // WindowInsets.Type.ime()
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

// Bez závislostí: WebView spouští board.js z Odjezdy OS (assets/app, viz scripts/sync-odjezdy-os.ps1).
