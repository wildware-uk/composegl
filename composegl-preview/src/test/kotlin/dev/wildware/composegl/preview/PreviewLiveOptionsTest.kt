package dev.wildware.composegl.preview

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

class PreviewLiveOptionsTest {

    private val separator = File.pathSeparator

    @Test
    fun `everything the build-file task passes`() {
        val options = PreviewLiveOptions.parse(
            listOf(
                "--classes", "build/classes/kotlin/main${separator}build/classes/java/main",
                "--resources", "build/resources/main",
                "--sources", "src/main/kotlin${separator}src/main/java",
                "--font", "default=fonts/DejaVuSans.ttf@12,16",
                "--package", "com.game.menus",
                "--project-dir", "/work/game",
                "--task", ":game:previewClasses",
                "--gradle-home", "/opt/gradle",
                "--build-file", "/work/game/build.gradle.kts${separator}/work/game/settings.gradle.kts",
                "--build-file", "/work/game/gradle/libs.versions.toml",
            ),
        )

        assertEquals(listOf(File("build/classes/kotlin/main"), File("build/classes/java/main")), options.classes)
        assertEquals(listOf(File("build/resources/main")), options.resources)
        assertEquals(listOf(File("src/main/kotlin"), File("src/main/java")), options.sources)
        assertEquals("default", options.fonts.single().family)
        assertEquals(listOf(12, 16), options.fonts.single().sizes)
        assertEquals("com.game.menus", options.packageName)
        assertEquals(File("/work/game"), options.projectDir)
        assertEquals(listOf(":game:previewClasses"), options.tasks)
        assertEquals(File("/opt/gradle"), options.gradleHome)
        assertEquals(3, options.buildFiles.size)
        assertTrue(options.compiles)
        assertEquals("./gradlew -t :game:previewClasses", options.advice)
    }

    @Test
    fun `with no project to compile, the output is watched`() {
        val options = PreviewLiveOptions.parse(listOf("--classes", "out", "--font", "default=f.ttf"))
        assertFalse(options.compiles)
        assertEquals("./gradlew -t classes", options.advice)
    }

    @Test
    fun `classes and a font are required, and an unknown flag is refused by name`() {
        assertTrue("--classes" in assertThrows<IllegalArgumentException> { PreviewLiveOptions.parse(listOf("--font", "default=f.ttf")) }.message!!)
        assertTrue("--font" in assertThrows<IllegalArgumentException> { PreviewLiveOptions.parse(listOf("--classes", "out")) }.message!!)
        assertTrue("--colour" in assertThrows<IllegalArgumentException> { PreviewLiveOptions.parse(listOf("--colour", "red")) }.message!!)
    }
}
