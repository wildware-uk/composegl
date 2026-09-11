plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

description = "The toolkit: nodes, modifiers, layout, widgets, input. No engine, no OpenGL, no AWT."

/**
 * Multiplatform, with every line of the toolkit in `commonMain`.
 *
 * The second target is the point. A module that only ever compiles for a JVM can claim to be
 * portable for years and be wrong, because nothing ever checks; one line reaching for
 * `System.getProperty` or `String.format` is all it takes. Compiling for something with no JVM
 * anywhere near it turns that claim into a build failure.
 *
 * Linux is that target rather than iOS only because this is what the machines here and in CI can
 * build. The compiler does not care which one it is — what it checks is that the source is common,
 * and iOS is a target being added to a list rather than a port.
 */
/**
 * The default skin, as source the compiler can see on every platform.
 *
 * `src/commonMain/skins/default.json` is an ordinary skin file, read by the same loader a game's
 * own file goes through — nothing in the toolkit's code knows what colour a button is. Embedding it
 * is what lets that stay true on a platform with no file system a library may read: Kotlin/Native
 * has no resource loader of its own, and this module is not allowed a dependency that would bring
 * one.
 */
val embedDefaultSkin = tasks.register<EmbedTextAsSource>("embedDefaultSkin") {
    description = "Turns the default skin file into a Kotlin source file."
    group = "build"
    source.set(layout.projectDirectory.file("src/commonMain/skins/default.json"))
    packageName.set("dev.wildware.composegl.ui.skin")
    propertyName.set("DEFAULT_SKIN_JSON")
    outputDirectory.set(layout.buildDirectory.dir("generated/skin"))
}

kotlin {
    jvm()
    linuxX64()

    // `expect class` is still officially Beta, and the warning is an error here. One internal
    // lock uses it, deliberately: see `internal/Guard.kt`.
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }

    sourceSets {
        commonMain { kotlin.srcDir(embedDefaultSkin) }

        commonMain.dependencies {
            api(libs.compose.runtime)
            api(libs.coroutines.core)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        jvmTest.dependencies {
            // The JUnit 5 flavour, so that `kotlin.test` in commonTest and the JUnit annotations
            // the rest of the suite uses run in the same engine.
            implementation(kotlin("test-junit5"))
            implementation(libs.junit.jupiter)
            runtimeOnly(libs.junit.platform.launcher)
        }
    }
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
            "dev.wildware.composegl:composegl-ui",
        ),
    )
    resolved.set(
        provider {
            DependencyConfinementCheck.modulesOf(
                configurations.named("jvmRuntimeClasspath").get().incoming.resolutionResult.root,
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
    classDirectories.from(layout.buildDirectory.dir("classes/kotlin/jvm/main"))
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
    dependsOn(tasks.named("jvmMainClasses"))
}

tasks.named("check") { dependsOn(confinement, noEngineTypes) }
