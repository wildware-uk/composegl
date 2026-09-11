plugins {
    alias(libs.plugins.kotlin.jvm)
}

description = "Shared test material: the scenes every backend must draw the same, and golden comparison."

dependencies {
    api(project(":composegl-ui"))
}
