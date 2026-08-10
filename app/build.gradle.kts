plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.sam.openspoof"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.sam.openspoof"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    androidResources {
        // Ship one locale. Without this, every translated string that any
        // dependency carries is packaged for ~80 languages the app never uses.
        localeFilters += "en"
    }

    buildTypes {
        release {
            // AGP 9.3+ spelling: turns on R8 code shrinking/obfuscation and
            // resource shrinking together, with the platform default keep rules.
            optimization {
                enable = true
            }
        }
    }

    buildFeatures {
        compose = true
        // Everything below defaults to on in some AGP versions and each one
        // generates classes or resources the app has no use for.
        buildConfig = false
        resValues = false
        shaders = false
        viewBinding = false
        dataBinding = false
        aidl = false
        renderScript = false
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/*.version",
                "/META-INF/*.kotlin_module",
                "/META-INF/com/android/build/gradle/*",
                "/kotlin/**",
                "DebugProbesKt.bin",
            )
        }
    }

    // Strips the ~10-30KB signed dependency blob Play uses for advisories.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// Only two dependencies. Networking is java.net.HttpURLConnection and JSON is
// org.json, both already in the Android framework, so no OkHttp/Retrofit/Moshi.
// Map tiles are drawn by this app's own Canvas renderer rather than osmdroid.
dependencies {
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
}
