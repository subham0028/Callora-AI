
plugins {
    alias(libs.plugins.android.application)
    id("com.google.gms.google-services") version "4.4.4"
}

android {
    namespace = "com.example.calloraai"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.calloraai"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner =
            "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf(
                    "androidx.**",
                    "kotlin.**",
                    "kotlinx.**"
                )
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    implementation("io.github.webrtc-sdk:android:125.6422.07")

    // Firebase
    implementation(
        platform("com.google.firebase:firebase-bom:34.19.0")
    )
    implementation("com.google.firebase:firebase-database")

    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}
