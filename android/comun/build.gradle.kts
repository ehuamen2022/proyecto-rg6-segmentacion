plugins {
    id("com.android.library")
}

android {
    namespace = "pe.tesis.rg6.comun"
    compileSdk = 36                                    // CameraX 1.6.2 lo exige

    defaultConfig {
        minSdk = 29                                    // MediaStore con carpetas (Pictures/, Documents/)
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api("androidx.activity:activity:1.8.2")
    api("androidx.camera:camera-camera2:1.6.2")
    api("androidx.camera:camera-lifecycle:1.6.2")
    api("androidx.camera:camera-view:1.6.2")
    api("com.microsoft.onnxruntime:onnxruntime-android:1.27.0")   // = onnxruntime de la evaluación del OE3

    // Prueba en el PC (JVM): el núcleo de detección contra la referencia de Ultralytics
    testImplementation("junit:junit:4.13.2")
}
