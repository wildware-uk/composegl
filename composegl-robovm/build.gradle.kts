plugins {
    alias(libs.plugins.kotlin.jvm)
}

description = "The iOS half of a backend: the parts of a phone no engine reports."

dependencies {
    api(project(":composegl-ui"))

    // UIKit, as Java classes. `compileOnly` because a RoboVM app already has them — they are its
    // runtime, not a library it pulls in — and putting them on a consumer's classpath would give
    // them a second, non-functioning copy of `java.lang`.
    compileOnly(libs.robovm.rt)
    compileOnly(libs.robovm.cocoatouch)

    testImplementation(libs.robovm.rt)
    testImplementation(libs.robovm.cocoatouch)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
