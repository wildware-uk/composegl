plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    `maven-publish`
}

// Consumers get sources so they can step into the library when something surprises them.
java {
    withSourcesJar()
}

description = "Compose scene, frame driving, input and platform services. No engine, no GL calls, no AWT."

dependencies {
    api(libs.compose.runtime)
    api(libs.compose.ui)
    api(libs.compose.foundation)
    api(libs.skiko)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.compose.material3)
    testRuntimeOnly(libs.junit.platform.launcher)
    // Raster-target tests draw with real Skia, so they need the native library.
    testRuntimeOnly(project.extra["skikoRuntime"] as String)
}

/**
 * The portability rules from spec §18, enforced rather than trusted.
 *
 * Core is what makes an Android or iOS port possible later, and it only stays that way if it
 * never quietly picks up a dependency on a windowing toolkit or on one engine.
 */
val checkNoForbiddenReferences by tasks.registering(BytecodeReferenceCheck::class) {
    description = "Fails if composegl-core references AWT, Swing, LibGDX or LWJGL."
    group = "verification"
    classDirectories.from(sourceSets.main.get().output.classesDirs)
    forbiddenPackages.set(listOf("java/awt", "javax/swing", "com/badlogic", "org/lwjgl"))
    reason.set(
        "composegl-core must stay free of AWT and of any engine. Android and iOS share a runtime " +
            "with no AWT in it, and the whole point of the core/adapter split is that a second " +
            "engine is a second adapter. Move this into composegl-libgdx, or reach it through " +
            "HostServices.",
    )
    dependsOn(tasks.named("classes"))
}

val checkOptInConfinement by tasks.registering(OptInConfinementCheck::class) {
    description = "Fails if Compose's internal API is opted into outside SceneBridge.kt."
    group = "verification"
    sources.from(sourceSets.main.get().kotlin.srcDirs)
    annotation.set("InternalComposeUiApi")
    allowedFileName.set("SceneBridge.kt")
}

tasks.named("check") {
    dependsOn(checkNoForbiddenReferences, checkOptInConfinement)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name.set("ComposeGL core")
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

/**
 * The whole core suite, run on a JVM that does not have AWT in it at all.
 *
 * `--limit-modules` leaves `java.desktop` out of the module graph, so any attempt to touch
 * `java.awt` fails with `NoClassDefFoundError` rather than quietly working. That is the closest
 * thing to Android's runtime this machine can offer: ART and RoboVM's libcore have no AWT either,
 * and the reason `composegl-core` is written the way it is, is so that it never needs one.
 *
 * The bytecode check says core does not *name* AWT. This says whether it can *run* without one.
 *
 * **It fails today, at a known line, and that failure is the point.** Everything that does not
 * build a scene passes; everything that does fails in Compose, not in ComposeGL:
 *
 * ```
 * java.lang.NoClassDefFoundError: java/awt/HeadlessException
 *   at androidx.compose.ui.node.RootNodeOwner$OwnerImpl.<init>(RootNodeOwner.skiko.kt:471)
 * ```
 *
 * `RootNodeOwner` eagerly builds an AWT-backed clipboard when the scene is created, before
 * ComposeGL gets to provide its own. So this task is a regression detector pointed at somebody
 * else's code: the day that becomes lazy, it goes green and the Android port (#25) loses its
 * nearest blocker. Not wired into `check` for that reason.
 */
tasks.register<Test>("noAwtTest") {
    description = "Runs the core tests on a JVM with no java.desktop module."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    jvmArgs(
        "--limit-modules",
        "java.base,java.logging,java.management,java.instrument,java.naming,java.xml,jdk.unsupported,jdk.zipfs",
    )
    testLogging { showStandardStreams = true }
}
