import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Módulo de dominio puro Kotlin/JVM: astronomía, catálogos, protocolo de la montura,
// alineación, control y servidores Stellarium. Sin dependencias de Android, por lo que
// se prueba con JUnit en la JVM en segundos.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    // Las pruebas de red usan sockets UDP/TCP en loopback.
    systemProperty("java.net.preferIPv4Stack", "true")
}
