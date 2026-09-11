plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

description = "The Android half of a backend: the parts of a phone no engine reports."

android {
    namespace = "composegl.android"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Android's Java is not the desktop's, so this module says 17 where the rest of the build says 21.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    api(project(":composegl-ui"))

    // Window insets, which is the only way to find out what the keyboard is covering. The plain
    // artifact rather than core-ktx on purpose: this module wants two classes, not a library.
    implementation(libs.androidx.core)
}

/**
 * Published with an empty javadoc jar rather than a generated one.
 *
 * Maven Central refuses a release with no javadoc jar at all, and the Android plugin's generator is
 * a Dokka old enough to fail on a modern JDK's version string (`IllegalArgumentException: 25.0.2`).
 * Everything in this module is documented in the source and on the wiki, and an empty jar is the
 * accepted way to satisfy the rule without shipping a broken one. The other five modules generate
 * theirs normally.
 */
mavenPublishing {
    configure(
        com.vanniktech.maven.publish.AndroidSingleVariantLibrary(
            variant = "release",
            sourcesJar = true,
            publishJavadocJar = false,
        ),
    )
}

private val emptyJavadocJar = tasks.register<Jar>("emptyJavadocJar") {
    archiveClassifier.set("javadoc")
}

afterEvaluate {
    publishing.publications.withType<MavenPublication>().configureEach { artifact(emptyJavadocJar) }
}
