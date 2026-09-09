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
    // Throwaway feasibility spike; see docs/superpowers/spikes/s6-runtime-ui.md.
    // The v2 modules land here as they are built.
    "spikes:s6-runtime-ui",
)
