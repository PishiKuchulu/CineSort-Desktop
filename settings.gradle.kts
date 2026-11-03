pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "CineSortDesktop"

include(":shared:core-parser")
include(":shared:core-domain")
include(":core-data-desktop")
include(":app-desktop")
