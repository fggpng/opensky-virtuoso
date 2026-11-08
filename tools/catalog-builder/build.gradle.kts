import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Herramienta de PC: convierte el Tycho-2 del CDS al formato binario de la app.
//   ./gradlew :tools:catalog-builder:run --args="--download salida/"
//   ./gradlew :tools:catalog-builder:run --args="salida/ /ruta/tyc2.dat.*.gz /ruta/suppl_1.dat.gz"
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

application {
    mainClass.set("com.opensky.virtuoso.tools.CatalogBuilderKt")
    applicationDefaultJvmArgs = listOf("-Xmx2g")
}

dependencies {
    implementation(project(":core"))
}
