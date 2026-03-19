pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // WebRTC Android SDK
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "ScreenMirrorTV"
include(":app")
