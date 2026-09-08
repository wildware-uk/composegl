plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    application
}

description = "Runnable sample: spinning cube, Material HUD, in-world panel on a rotating quad."

dependencies {
    implementation(project(":composegl-libgdx"))
    implementation(libs.compose.material3)
    implementation(libs.gdx.backend.lwjgl3)
    runtimeOnly(variantOf(libs.gdx.platform) { classifier("natives-desktop") })
    runtimeOnly(project.extra["skikoRuntime"] as String)
}

application {
    mainClass.set("composegl.demo.DemoKt")
}

// Compose pulls some artifacts in through two coordinates, which collide in the distribution
// archives. The demo is run with `./gradlew :composegl-demo-libgdx:run`, so the archives are
// incidental; take the first copy of any duplicate.
tasks.withType<AbstractArchiveTask>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
