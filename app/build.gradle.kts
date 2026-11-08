// Módulo Android. AGP 9 compila Kotlin de forma integrada (built-in Kotlin): no se aplica
// org.jetbrains.kotlin.android; solo el plugin del compilador de Compose.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// ---- Versión y actualizaciones -------------------------------------------------------------
// En GitHub Actions: VERSION_CODE = número de ejecución y GITHUB_REPOSITORY = "usuario/repo".
// En local: versionCode 1 y repositorio tomado de gradle.properties (opensky.updateRepo).
val ciVersionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
val updateRepo = System.getenv("GITHUB_REPOSITORY")
    ?: (providers.gradleProperty("opensky.updateRepo").orNull ?: "")
// Firma de las versiones publicadas: la misma clave en cada compilación (si cambia, Android no
// deja actualizar). La clave vive en los secretos del repositorio, nunca en el código.
val signingKeystore: String? = System.getenv("SIGNING_KEYSTORE_PATH")

android {
    namespace = "com.opensky.virtuoso"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.opensky.virtuoso"
        minSdk = 29          // WifiNetworkSpecifier (Android 10+)
        targetSdk = 36       // Android 16
        versionCode = ciVersionCode
        versionName = "1.0.$ciVersionCode"
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (signingKeystore != null) {
            create("release") {
                storeFile = file(signingKeystore)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Sin R8 mientras la app está en pruebas: errores más legibles y menos riesgo de clases eliminadas.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // Las compilaciones de Android Studio se instalan como una app aparte («.dev») para no chocar
            // con la firma de las versiones publicadas en GitHub.
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.play.services.location)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    testImplementation(libs.junit)
}
