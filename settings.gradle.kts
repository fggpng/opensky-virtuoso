pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "OpenSkyVirtuosoController"

include(":core")
// En el Mac de CI (app de iOS) no hay SDK de Android: -Popensky.skipAndroid=true omite la app.
if (providers.gradleProperty("opensky.skipAndroid").orNull != "true") include(":app")
include(":tools:catalog-builder")
