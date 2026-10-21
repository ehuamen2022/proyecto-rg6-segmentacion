pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "RG6Android"
include(":comun")          // código compartido: núcleo de detección, modelo ONNX, cámara, dibujo y archivos
include(":RG6Medir")       // app de medición del OE3
include(":RG6Contador")    // app de uso: detectar, contar y guardar evidencias
