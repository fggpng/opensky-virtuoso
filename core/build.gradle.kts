import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

// Núcleo multiplataforma: astronomía, catálogos, protocolo de la montura, alineación, control y
// servidores Stellarium. Lo comparten la app de Android (destino JVM) y la de iOS (framework
// «OpenSkyCore»). Las pruebas comunes corren en la JVM (en segundos) y, en el Mac de CI, en nativo.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// Los destinos de Apple solo se pueden compilar en macOS: en Linux (CI de Android) el núcleo es
// solo JVM y no se descarga el compilador nativo. -Popensky.apple=true los fuerza.
val buildApple = System.getProperty("os.name").lowercase().contains("mac") ||
    providers.gradleProperty("opensky.apple").orNull == "true"

/** El catálogo de cielo profundo viaja dentro del framework de iOS como código generado. */
abstract class GenerateDeepSkyData : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val catalog: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val text = catalog.get().asFile.readText(Charsets.UTF_8)
        fun escape(s: String) = buildString {
            for (c in s) when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '$' -> append("\\$")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
        // Trozos de ~8 000 caracteres cortados en finales de línea (límite cómodo para literales).
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        for (line in text.split('\n')) {
            if (current.length + line.length > 8000 && current.isNotEmpty()) {
                chunks += current.toString(); current.setLength(0)
            }
            current.append(line).append('\n')
        }
        if (current.isNotEmpty()) chunks += current.toString().removeSuffix("\n")
        val out = outputDir.get().asFile.resolve("com/opensky/virtuoso/platform/DeepSkyCatalogData.kt")
        out.parentFile.mkdirs()
        out.writeText(buildString {
            appendLine("// Generado por la tarea generateDeepSkyData a partir de catalogs/deepsky.tsv. No editar.")
            appendLine("package com.opensky.virtuoso.platform")
            appendLine()
            appendLine("internal actual fun deepSkyCatalogText(): String = buildString(${text.length}) {")
            for (chunk in chunks) appendLine("    append(\"${escape(chunk)}\")")
            appendLine("}")
        })
    }
}

val generateDeepSkyData = tasks.register<GenerateDeepSkyData>("generateDeepSkyData") {
    catalog.set(layout.projectDirectory.file("src/jvmMain/resources/catalogs/deepsky.tsv"))
    outputDir.set(layout.buildDirectory.dir("generated/deepsky/kotlin"))
}

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        testRuns["test"].executionTask.configure {
            // Las pruebas de red usan sockets UDP/TCP en loopback.
            systemProperty("java.net.preferIPv4Stack", "true")
        }
    }

    if (buildApple) {
        val xcf = XCFramework("OpenSkyCore")
        listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
            target.binaries.framework {
                baseName = "OpenSkyCore"
                isStatic = true
                binaryOption("bundleId", "com.opensky.virtuoso.core")
                xcf.add(this)
            }
        }
        // Solo para correr las pruebas nativas directamente en el Mac de CI (sin simulador).
        macosArm64()
    }

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        if (buildApple) {
            appleMain {
                kotlin.srcDir(generateDeepSkyData)
            }
        }
    }
}
