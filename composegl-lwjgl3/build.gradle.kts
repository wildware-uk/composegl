plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    `maven-publish`
}

description = "Raw LWJGL3 adapter: framebuffer, GL state firewall, blit, GLFW input bridge. No game engine."

java {
    withSourcesJar()
}

dependencies {
    api(project(":composegl-core"))
    api(platform("org.lwjgl:lwjgl-bom:${libs.versions.lwjgl.get()}"))
    api(libs.lwjgl)
    api(libs.lwjgl.glfw)
    api(libs.lwjgl.opengl)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    // The keycode test reflects over GLFW's constants, which initialises the class, which loads
    // the native library — so even the headless test needs LWJGL's natives.
    val natives = project.extra["lwjglNatives"] as String
    testRuntimeOnly(variantOf(libs.lwjgl) { classifier(natives) })
    testRuntimeOnly(variantOf(libs.lwjgl.glfw) { classifier(natives) })
}

/** The adapter knows about LWJGL and nothing else. No AWT, no other engine. */
val checkNoForbiddenReferences by tasks.registering(BytecodeReferenceCheck::class) {
    description = "Fails if composegl-lwjgl3 references AWT, Swing or LibGDX."
    group = "verification"
    classDirectories.from(sourceSets.main.get().output.classesDirs)
    forbiddenPackages.set(listOf("java/awt", "javax/swing", "com/badlogic"))
    reason.set("This adapter is for raw LWJGL3. Anything else belongs in another adapter.")
    dependsOn(tasks.named("classes"))
}

tasks.named("check") {
    dependsOn(checkNoForbiddenReferences)
}

val integrationTest: SourceSet by sourceSets.creating

configurations[integrationTest.implementationConfigurationName]
    .extendsFrom(configurations.implementation.get(), configurations.testImplementation.get())
configurations[integrationTest.runtimeOnlyConfigurationName]
    .extendsFrom(configurations.runtimeOnly.get(), configurations.testRuntimeOnly.get())

dependencies {
    add(integrationTest.implementationConfigurationName, sourceSets.main.get().output)
    add(integrationTest.implementationConfigurationName, libs.compose.material3)
    add(integrationTest.runtimeOnlyConfigurationName, project.extra["skikoRuntime"] as String)
    val natives = project.extra["lwjglNatives"] as String
    add(integrationTest.runtimeOnlyConfigurationName, "org.lwjgl:lwjgl:${libs.versions.lwjgl.get()}:$natives")
    add(integrationTest.runtimeOnlyConfigurationName, "org.lwjgl:lwjgl-glfw:${libs.versions.lwjgl.get()}:$natives")
    add(integrationTest.runtimeOnlyConfigurationName, "org.lwjgl:lwjgl-opengl:${libs.versions.lwjgl.get()}:$natives")
}

tasks.register<Test>("integrationTest") {
    description = "Runs the GL integration tests. Needs a display; use xvfb-run on a headless box."
    group = "verification"
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    useJUnitPlatform()
    testLogging { showStandardStreams = true }
    maxParallelForks = 1
    forkEvery = 1
    onlyIf {
        val hasDisplay = !System.getenv("DISPLAY").isNullOrEmpty()
        if (!hasDisplay) logger.lifecycle("Skipping integrationTest: no DISPLAY.")
        hasDisplay
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name.set("ComposeGL LWJGL3 adapter")
                description.set(project.description)
                url.set("https://github.com/wildware-uk/composegl")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("shaun-wild")
                        name.set("Shaun Wild")
                        url.set("https://github.com/shaun-wild")
                    }
                }
                scm {
                    url.set("https://github.com/wildware-uk/composegl")
                    connection.set("scm:git:https://github.com/wildware-uk/composegl.git")
                    developerConnection.set("scm:git:ssh://git@github.com/wildware-uk/composegl.git")
                }
            }
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/wildware-uk/composegl")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
