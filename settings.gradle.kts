rootProject.name = "composegl"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        // The Android Gradle plugin lives here and nowhere else.
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        google()
    }
}

include(
    "composegl-ui",
    "composegl-effects",
    "composegl-gdx",
    "composegl-lwjgl3",
    "composegl-android",
    "composegl-testing",
    "composegl-demo",
    "composegl-demo-snake",
    "composegl-demo-snake-core",
    "composegl-demo-snake-android",
    "composegl-demo-showcase",
    // Throwaway feasibility spike; see docs/superpowers/spikes/s6-runtime-ui.md.
    "spikes:s6-runtime-ui",
)
