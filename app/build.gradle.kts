plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.familywatch"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.familywatch"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // オンデバイスOCR (端末内で完結、外部送信なし)
    // 注意: 上のtext-recognitionはラテン文字(英数字)専用モデル。
    // 日本語(漢字・かな)を読み取るには text-recognition-japanese が必須。
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")

    // Google Play services のtask APIをawaitで使うため
    implementation("com.google.android.gms:play-services-tasks:18.2.0")
}
