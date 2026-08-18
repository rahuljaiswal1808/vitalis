plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.vitalis.antispoof"
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
    // TFLite models must not be compressed in the APK.
    androidResources { noCompress += "tflite" }
}

dependencies {
    api(project(":vitalis-core"))
    api(project(":vitalis-camera"))
    api(project(":vitalis-face"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.camera.core)
    // Passive tier (scaffold; no validated model shipped — see PassiveSpoofScorer docs, §5/§10.5).
    implementation(libs.tensorflow.lite)
    implementation(libs.tensorflow.lite.support)
}
