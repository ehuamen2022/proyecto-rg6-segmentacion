plugins {
    id("com.android.application")
}

android {
    namespace = "pe.tesis.rg6contador"
    compileSdk = 36

    defaultConfig {
        applicationId = "pe.tesis.rg6contador"            // app de uso en campo
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += "arm64-v8a" }              // Redmi Note 14 Pro (ARM 64 bits): APK más pequeño
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    androidResources {
        noCompress += "onnx"                           // el modelo se lee tal cual desde assets
    }
}

dependencies {
    implementation(project(":comun"))
}
