import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test

/**
 * Runs this module's tests tagged `allocation` a second time, on a JVM that counts what a phone counts.
 *
 * The desktop JVM's escape analysis quietly removes a short-lived object, such as the iterator a
 * `for (x in list)` makes, so an allocation test passes on a desktop while the same code makes
 * garbage every frame on Android, whose runtime removes nothing. With escape analysis switched off
 * the JVM makes every object the code asks for, and the bytes counted here match a phone's to within
 * a few percent (#241, #248).
 *
 * A task of its own rather than a flag on [testTask], so the rest of the suite keeps the JVM it was
 * written against. Hung off `check`.
 */
fun Project.allocationTests(testTask: String = "jvmTest") {
    val tests = tasks.named(testTask, Test::class.java)
    val task = tasks.register("jvmAllocationTest", Test::class.java) {
        description = "Runs the allocation tests with escape analysis off, so they count what Android allocates."
        group = "verification"
        testClassesDirs = tests.get().testClassesDirs
        classpath = tests.get().classpath
        javaLauncher.set(tests.flatMap { it.javaLauncher })
        useJUnitPlatform { includeTags("allocation") }
        jvmArgs("-XX:-DoEscapeAnalysis")
    }
    tasks.named("check") { dependsOn(task) }
}
