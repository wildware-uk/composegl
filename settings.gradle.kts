rootProject.name = "composegl"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
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
    "composegl-gdx",
    "composegl-lwjgl3",
    "composegl-testing",
    "composegl-demo",
    // Throwaway feasibility spike; see docs/superpowers/spikes/s6-runtime-ui.md.
    "spikes:s6-runtime-ui",
)
