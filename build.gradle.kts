// Archivo raíz: solo declara los plugins (versiones en gradle/libs.versions.toml).
// Al declarar kotlin-jvm / kotlin-compose con la misma versión, el Kotlin integrado de
// AGP 9 se alinea con KGP 2.4.21 en un único classloader.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}
