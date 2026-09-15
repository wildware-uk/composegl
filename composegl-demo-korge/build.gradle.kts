plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    application
}

description = "The KorGE demo: a 2D scene with a menu, a HUD, settings and a panel in the world, on the KorGE backend."

dependencies {
    implementation(project(":composegl-korge"))
    // The shipped effects, drawn through KorGE's GL context.
    implementation(project(":composegl-effects"))
    // The HUD: bars, hotbar, cooldowns and damage numbers.
    implementation(project(":composegl-game"))

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The fonts and emoji are the example's; ship one copy, not two.
sourceSets.main {
    resources.srcDir("../composegl-demo/src/main/resources")
}

/**
 * KorGE's desktop window reaches its OpenGL context through reflection into AWT, which Java 17 and
 * later refuse unless these packages are opened. Every JVM that opens a KorGE window needs them.
 */
val korgeOpens = listOf(
    "--add-opens=java.desktop/sun.java2d.opengl=ALL-UNNAMED",
    "--add-opens=java.desktop/java.awt=ALL-UNNAMED",
    "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
    "--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED",
)

application {
    mainClass.set("dev.wildware.composegl.korge.demo.MainKt")
    applicationDefaultJvmArgs = korgeOpens
}

tasks.test {
    useJUnitPlatform()
    jvmArgs(korgeOpens)
    // With KORGE_HEADLESS=true KorGE renders offscreen with a real driver and no display at all.
    System.getenv("KORGE_HEADLESS")?.let { environment("KORGE_HEADLESS", it) }
}

/**
 * The Compose runtime brings two different jars that are both called `runtime-desktop-1.12.0.jar`,
 * and a distribution copies every dependency into one folder by file name. Without a strategy the
 * build stops there. The demo is run with `./gradlew :composegl-demo-korge:run`, which uses the
 * real classpath and has both; the archives are only built so `build` passes, never shipped.
 */
tasks.withType<AbstractCopyTask>().matching { it.name in setOf("distTar", "distZip", "installDist") }.configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
