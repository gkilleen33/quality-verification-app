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
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "QualityVerifier"
include(":shared")
include(":core")
include(":capture")
include(":design")
include(":app")
include(":fundi")
// PesaLog, the mobile money log: a third app that reads notifications, never SMS.
include(":tracker")
include(":server")
