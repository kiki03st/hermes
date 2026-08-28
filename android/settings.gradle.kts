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
        maven("https://jitpack.io") // android-vad(Silero VAD) 배포처
    }
}

rootProject.name = "hermes-client"

include(":shared")
include(":app")
include(":wear")
