plugins {
    id("com.android.application")
}

android {
    namespace = "cz.plain.tvstream"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "cz.plain.tvstream"
        minSdk = 28
        targetSdk = 34 // box běží na Androidu 14
        versionCode = 1
        versionName = "1.0"
        // Box je 32bitový ARM – nativní knihovny jen pro armeabi-v7a (+ arm64 pro novější boxy).
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    val media3 = "1.11.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Virtualizované seznamy (tisíce kanálů, plakáty)
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // QR kód pro „Nastavit z telefonu“ (jen generátor, Apache 2.0)
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    // org.json v testech na JVM (Android ho v unit testech jen „mockuje“)
    testImplementation("org.json:json:20240303")
}
