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
        versionCode = 8
        versionName = "0.8.0"
    }

    ndkVersion = "27.0.12077973"

    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("generated/ore-jni"))

    packaging {
        jniLibs {
            // Os motores precisam existir como arquivos no aparelho para serem executados.
            useLegacyPackaging = true
        }
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
        unitTests.all { test ->
            // Motores compilados para Linux (ver buildHostEngines), usados nos testes de integração.
            test.systemProperty("ore.hostEngines", layout.buildDirectory.dir("host-engines").get().asFile.path)
            System.getenv("ORE_REQUIRE_HOST_ENGINES")?.let { test.systemProperty("ore.requireHostEngines", it) }
        }
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

// ---------- Motores do OpenReadEra (MOBI, DJVU, CBR) ----------
// São programas separados (não bibliotecas): o app os executa e conversa com eles por pipes.
// O Android só instala e deixa executar arquivos lib*.so do APK, então cada executável gerado
// pelo ndk-build é copiado como lib<motor>.so, do mesmo jeito que o ReadEra faz.
val oreEngines = listOf("eramobi", "eradjvu", "eracomic")
val oreAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")
val oreSource = layout.projectDirectory.dir("src/main/cpp/openreadera")
val oreOut = layout.buildDirectory.dir("ore")

val buildOreEngines by tasks.registering(Exec::class) {
    description = "Compila os motores do OpenReadEra com o ndk-build."
    inputs.dir(oreSource)
    outputs.dir(oreOut.map { it.dir("libs") })
    doFirst {
        val ndkBuild = android.ndkDirectory.resolve("ndk-build")
        commandLine(
            ndkBuild.path,
            "NDK_PROJECT_PATH=null",
            "APP_BUILD_SCRIPT=${oreSource.file("Android.mk").asFile.path}",
            "NDK_APPLICATION_MK=${oreSource.file("Application.mk").asFile.path}",
            "APP_ABI=${oreAbis.joinToString(" ")}",
            "NDK_OUT=${oreOut.get().dir("obj").asFile.path}",
            "NDK_LIBS_OUT=${oreOut.get().dir("libs").asFile.path}",
            "APP_MODULES=${oreEngines.joinToString(" ")}",
            "-j${Runtime.getRuntime().availableProcessors()}"
        )
    }
    // Valor provisório: o comando de verdade é montado em doFirst, quando o NDK já foi resolvido.
    commandLine("true")
}

val packageOreEngines by tasks.registering(Copy::class) {
    description = "Copia os motores como lib<motor>.so para o APK."
    dependsOn(buildOreEngines)
    from(oreOut.map { it.dir("libs") }) {
        include(oreAbis.flatMap { abi -> oreEngines.map { "$abi/$it" } })
        rename { "lib$it.so" }
    }
    into(layout.buildDirectory.dir("generated/ore-jni"))
}

tasks.named("preBuild") { dependsOn(packageOreEngines) }

// Os testes na JVM conversam com os motores de verdade, compilados para Linux a partir das mesmas
// fontes. Precisa de python3, make, gcc, g++, nasm e zlib; se faltar algo (ex.: no Windows), os
// testes desses motores são pulados, a menos que ORE_REQUIRE_HOST_ENGINES=1 (como no CI).
val buildHostEngines by tasks.registering(Exec::class) {
    description = "Compila os motores do OpenReadEra para Linux, para os testes."
    val script = layout.projectDirectory.file("src/test/native/build_host_engines.py")
    val out = layout.buildDirectory.dir("host-engines")
    inputs.dir(oreSource)
    inputs.dir(layout.projectDirectory.dir("src/test/native"))
    outputs.dir(out)
    isIgnoreExitValue = System.getenv("ORE_REQUIRE_HOST_ENGINES") == null
    onlyIf { !System.getProperty("os.name").startsWith("Windows") }
    commandLine("python3", script.asFile.path, oreSource.asFile.path, out.get().asFile.path)
}

tasks.withType<Test>().configureEach { dependsOn(buildHostEngines) }
