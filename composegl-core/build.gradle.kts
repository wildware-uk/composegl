plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
}

description = "Compose scene, frame driving, input and platform services. No engine, no GL calls, no AWT."

dependencies {
    api(libs.compose.runtime)
    api(libs.compose.ui)
    api(libs.compose.foundation)
    api(libs.skiko)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.compose.material3)
    testRuntimeOnly(libs.junit.platform.launcher)
    // Raster-target tests draw with real Skia, so they need the native library.
    testRuntimeOnly(project.extra["skikoRuntime"] as String)
}

/**
 * The portability rules from spec §18, enforced rather than trusted.
 *
 * Core is what makes an Android or iOS port possible later, and it only stays that way if it
 * never quietly picks up a dependency on a windowing toolkit or on one engine.
 */
val checkNoForbiddenReferences by tasks.registering(BytecodeReferenceCheck::class) {
    description = "Fails if composegl-core references AWT, Swing, LibGDX or LWJGL."
    group = "verification"
    classDirectories.from(sourceSets.main.get().output.classesDirs)
    forbiddenPackages.set(listOf("java/awt", "javax/swing", "com/badlogic", "org/lwjgl"))
    reason.set(
        "composegl-core must stay free of AWT and of any engine. Android and iOS share a runtime " +
            "with no AWT in it, and the whole point of the core/adapter split is that a second " +
            "engine is a second adapter. Move this into composegl-libgdx, or reach it through " +
            "HostServices.",
    )
    dependsOn(tasks.named("classes"))
}

val checkOptInConfinement by tasks.registering(OptInConfinementCheck::class) {
    description = "Fails if Compose's internal API is opted into outside SceneBridge.kt."
    group = "verification"
    sources.from(sourceSets.main.get().kotlin.srcDirs)
    annotation.set("InternalComposeUiApi")
    allowedFileName.set("SceneBridge.kt")
}

tasks.named("check") {
    dependsOn(checkNoForbiddenReferences, checkOptInConfinement)
}
