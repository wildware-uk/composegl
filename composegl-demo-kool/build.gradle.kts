plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    application
}

description = "The Kool demo: a Kool 3D world with a ComposeGL panel on top, driven by the mouse, on the Kool frontend."

dependencies {
    implementation(project(":composegl-kool"))

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

sourceSets.main {
    resources.srcDir("../composegl-demo/src/main/resources")
}

application {
    mainClass.set("dev.wildware.composegl.kool.demo.MainKt")
}

tasks.test {
    useJUnitPlatform()
    // Where the scripted run leaves its pictures, for a person to look at.
    System.getenv("COMPOSEGL_KOOL_DEMO_SHOTS")?.let { environment("COMPOSEGL_KOOL_DEMO_SHOTS", it) }
}

/**
 * The Compose runtime brings two different jars that are both called `runtime-desktop-1.12.0.jar`,
 * and a distribution copies every dependency into one folder by file name. The demo is run with
 * `./gradlew :composegl-demo-kool:run`; the archives are only built so `build` passes.
 */
tasks.withType<AbstractCopyTask>().matching { it.name in setOf("distTar", "distZip", "installDist") }.configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
