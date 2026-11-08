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
        versionCode = 4
        versionName = "0.4.0"
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

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        // Erros de lint quebram o build; avisos aparecem no relatório (app/build/reports).
        abortOnError = true
        checkDependencies = false
        // A interface é montada em código e só existe em português; mover cada texto para
        // strings.xml fica para quando houver tradução. Os demais avisos continuam ativos.
        disable += "SetTextI18n"
    }
}

dependencies {
    // Só para testes unitários na JVM: o APK continua sem dependências externas.
    testImplementation("junit:junit:4.13.2")
    // Simula o Android na JVM para testar banco de dados e telas sem emulador.
    testImplementation("org.robolectric:robolectric:4.14.1")
}
