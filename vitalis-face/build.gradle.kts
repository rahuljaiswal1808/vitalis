plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.vitalis.face"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }
    buildFeatures { buildConfig = false }
}

dependencies {
    api(project(":vitalis-core"))
    implementation(libs.androidx.core.ktx)
    // ImageProxy type only; camera binding lives in vitalis-camera.
    compileOnly(libs.camera.core)
    implementation(libs.camera.core)
    // ML Kit Face Detection: on-device, bundled model, gives eye-open + smile probabilities.
    // NOTE: pulls in Google Play Services dependencies — confirm acceptable for the fleet (§5).
    implementation(libs.mlkit.face.detection)
}
