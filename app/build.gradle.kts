plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.alexandria.reader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.alexandria.reader"
        minSdk = 23
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    // Java e Kotlin precisam apontar para o mesmo alvo, senão o Gradle acusa
    // "Inconsistent JVM-target compatibility".
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
