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
    "composegl-core",
    "composegl-libgdx",
    "composegl-lwjgl3",
    "composegl-demo-libgdx",
    "composegl-demo-snake",
    "composegl-demo-showcase",
    // Throwaway feasibility spike; see docs/superpowers/spikes/.
    "spikes:s6-runtime-ui",
    "composegl-smoke-lwjgl3",
)
