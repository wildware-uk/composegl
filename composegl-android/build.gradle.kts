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
