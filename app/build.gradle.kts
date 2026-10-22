plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.docscanner.ocr"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.docscanner.ocr"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2.0.0"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            // R8 is disabled out of the box so the OCR native library always links.
            // See proguard-rules.pro if you want to enable minification.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
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
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    androidResources {
        // Tesseract needs to read the models with regular file IO.
        noCompress.add("traineddata")
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    // On-device OCR: Tesseract stays for Arabic (ML Kit has no offline Arabic model).
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")

    // Google ML Kit text recognition v2 (Latin/digits). Bundled model = fully offline.
    // Used for English and as a high-accuracy number pass in the hybrid engine.
    implementation("com.google.mlkit:text-recognition:16.0.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
