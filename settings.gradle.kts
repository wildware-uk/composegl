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

        // What the browser build runs on: Node.js, Yarn and Binaryen, fetched as plain downloads.
        // Declared here because project repositories are refused above, and the Kotlin plugin would
        // otherwise add these three itself — see `downloadBaseUrl` in the root build file. Each is
        // held to the one module it serves, so none of them can answer for anything else.
        ivy("https://nodejs.org/dist") {
            name = "Node.js"
            patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("org.nodejs", "node") }
        }
        ivy("https://github.com/yarnpkg/yarn/releases/download") {
            name = "Yarn"
            patternLayout { artifact("v[revision]/[artifact](-v[revision]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("com.yarnpkg", "yarn") }
        }
        ivy("https://github.com/WebAssembly/binaryen/releases/download") {
            name = "Binaryen"
            patternLayout { artifact("version_[revision]/[artifact]-version_[revision]-[classifier].[ext]") }
            metadataSources { artifact() }
            content { includeModule("com.github.webassembly", "binaryen") }
        }
    }
}

include(
    "composegl-ui",
    "composegl-render",
    "composegl-effects",
    "composegl-game",
    "composegl-debug",
    "composegl-gdx",
    "composegl-korge",
    "composegl-lwjgl3",
    "composegl-webgl",
    "composegl-demo-web",
    "composegl-android",
    "composegl-robovm",
    "composegl-testing",
    "composegl-demo",
    "composegl-demo-snake",
    "composegl-demo-snake-core",
    "composegl-demo-snake-android",
    "composegl-demo-showcase",
    "composegl-demo-korge",
    // Throwaway feasibility spike; see docs/superpowers/spikes/s6-runtime-ui.md.
    "spikes:s6-runtime-ui",
)
