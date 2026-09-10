plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
}

description = "The toolkit: nodes, modifiers, layout, widgets, input. No engine, no OpenGL, no AWT."

dependencies {
    api(libs.compose.runtime)
    api(libs.coroutines.core)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

/**
 * The promise this module exists to keep, checked rather than remembered.
 *
 * Two direct dependencies, and everything below is what those two drag in. Anything else — an
 * engine, a window toolkit, Compose UI, skiko — ends the Android and iOS story, which is the only
 * reason this project was rebuilt.
 */
val confinement = tasks.register<DependencyConfinementCheck>("checkDependencyConfinement") {
    description = "Fails if composegl-ui's runtime classpath grows anything but the Compose runtime."
    group = "verification"
    reason.set(
        "composegl-ui must run on Android and iOS, where there is no AWT, no Skia and no desktop " +
            "JVM. It is allowed the Compose runtime, coroutines and the Kotlin standard library. " +
            "Nothing else, and no engine.",
    )
    allowed.set(
        listOf(
            "androidx.annotation:annotation",
            "androidx.annotation:annotation-jvm",
            "androidx.collection:collection",
            "androidx.collection:collection-jvm",
            "androidx.compose.runtime:runtime",
            "androidx.compose.runtime:runtime-annotation",
            "androidx.compose.runtime:runtime-annotation-jvm",
            "androidx.compose.runtime:runtime-desktop",
            "org.jetbrains:annotations",
            "org.jetbrains.compose.runtime:runtime",
            "org.jetbrains.compose.runtime:runtime-desktop",
            "org.jetbrains.kotlin:kotlin-stdlib",
            "org.jetbrains.kotlinx:kotlinx-coroutines-bom",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm",
            "uk.wildware.composegl:composegl-ui",
        ),
    )
    resolved.set(
        provider {
            DependencyConfinementCheck.modulesOf(
                configurations.named("runtimeClasspath").get().incoming.resolutionResult.root,
            )
        },
    )
}

/**
 * The same promise at the bytecode level. The dependency check catches a Gradle line; this catches
 * an import that somehow resolved anyway, and it is the one that would catch AWT arriving through
 * the JDK itself rather than through a jar.
 */
val noEngineTypes = tasks.register<BytecodeReferenceCheck>("checkNoEngineTypes") {
    description = "Fails if composegl-ui names an engine, a window toolkit or Compose UI."
    group = "verification"
    classDirectories.from(layout.buildDirectory.dir("classes/kotlin/main"))
    forbiddenPackages.set(
        listOf(
            "java/awt",
            "javax/swing",
            "com/badlogic/gdx",
            "org/lwjgl",
            "org/jetbrains/skiko",
            "androidx/compose/ui",
            "androidx/compose/material",
            "androidx/compose/foundation",
        ),
    )
    reason.set(
        "The toolkit defines its own events, its own canvas and its own fonts so a backend can be " +
            "written for anything. A reference to one of these means an engine has leaked in.",
    )
    dependsOn(tasks.named("classes"))
}

tasks.named("check") { dependsOn(confinement, noEngineTypes) }
