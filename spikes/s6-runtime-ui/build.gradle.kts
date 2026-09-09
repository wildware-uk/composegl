plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    application
}

description = "SPIKE (throwaway): Compose runtime only — our own Applier, layout and OpenGL renderer. No Compose UI, no Skia."

dependencies {
    // The whole point: the runtime, and nothing else from Compose.
    implementation(libs.compose.runtime)

    implementation(libs.gdx)
    implementation(libs.gdx.backend.lwjgl3)
    implementation("com.badlogicgames.gdx:gdx-freetype:${libs.versions.gdx.get()}")
    runtimeOnly(variantOf(libs.gdx.platform) { classifier("natives-desktop") })
    runtimeOnly("com.badlogicgames.gdx:gdx-freetype-platform:${libs.versions.gdx.get()}:natives-desktop")

}

sourceSets.main {
    resources.srcDir("../../composegl-demo-libgdx/src/main/resources")
}

application {
    mainClass.set("spike.rt.MainKt")
}

tasks.withType<AbstractArchiveTask>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
