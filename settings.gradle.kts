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
    "composegl-smoke-lwjgl3",
)
