plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Phase 40: signing values come from environment variables (GitHub Secrets in CI) when they are set and not empty;
// otherwise the fallback is used, so a local build still works.
fun envOr(name: String, fallback: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() } ?: fallback

android {
    namespace = "com.freetv.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.wosux.stream"
        minSdk = 21          // Android 5.0+ (covers old Android boxes)
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    // Fixed key so every build is signed the same way and a new APK can be installed over the old one (no uninstall needed).
    // Phase 40: the passwords / alias can come from the secrets WOSUX_STORE_PASSWORD, WOSUX_KEY_ALIAS, WOSUX_KEY_PASSWORD
    // (the workflow also replaces app/freetv.keystore from WOSUX_KEYSTORE_BASE64). Without them the committed key and values are used.
    signingConfigs {
        create("freetv") {
            storeFile = file("freetv.keystore")
            storePassword = envOr("WOSUX_STORE_PASSWORD", "freetv123")
            keyAlias = envOr("WOSUX_KEY_ALIAS", "freetv")
            keyPassword = envOr("WOSUX_KEY_PASSWORD", "freetv123")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("freetv")
        }
        debug {
            applicationIdSuffix = ".test"
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
        viewBinding = true
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Video player (free, open source)
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    // Channel logos (free, open source)
    implementation("io.coil-kt:coil:2.7.0")

    // Splash screen (system splash on Android 12+, same look on older Android)
    implementation("androidx.core:core-splashscreen:1.0.1")

    // One shared HTTP client (Net.kt) and the Media3 data source that uses it
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")
}
