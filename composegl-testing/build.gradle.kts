plugins {
    alias(libs.plugins.kotlin.jvm)
}

description = "Shared test material: the scenes every backend must draw the same, and golden comparison."

dependencies {
    api(project(":composegl-ui"))

    // The effects the toolkit ships, so both backends draw the same blur, outline, grade and
    // dissolve and the goldens can be compared side by side.
    api(project(":composegl-effects"))
}
