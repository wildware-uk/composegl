plugins {
    kotlin("jvm") version "2.4.20"
    id("org.jetbrains.compose") version "1.12.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
    application
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(compose.ui)
    implementation(compose.desktop.currentOs)

    implementation("com.badlogicgames.gdx:gdx:1.14.2")
    implementation("com.badlogicgames.gdx:gdx-backend-lwjgl3:1.14.2")
    implementation("com.badlogicgames.gdx:gdx-platform:1.14.2:natives-desktop")
}

application { mainClass.set("spike.MainKt") }

tasks.register("dumpCp") {
    val cp = configurations.named("runtimeClasspath")
    doLast { println(cp.get().files.joinToString("\n") { it.absolutePath }) }
}
