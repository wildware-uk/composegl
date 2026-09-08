import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.DataInputStream
import java.io.File

/**
 * Fails the build when compiled classes name packages they are not allowed to name.
 *
 * ComposeGL's core is only useful on Android and iOS later if it never reaches for AWT or an
 * engine today. That promise is easy to make and easy to break by accident — one convenience
 * import in one file — so it is checked rather than trusted.
 *
 * It reads the constant pool rather than searching the file for bytes, which matters: a class may
 * legitimately hold `"java.awt.datatransfer.StringSelection"` as a *string*, reached reflectively
 * so the code still runs where AWT does not exist. Class references and type descriptors spell
 * packages with slashes; string literals in our source spell them with dots. Only the former is a
 * real dependency, and only the former is what this looks at.
 */
@CacheableTask
abstract class BytecodeReferenceCheck : DefaultTask() {

    /** The compiled classes to scan. Only our own output; dependencies are not our business. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classDirectories: ConfigurableFileCollection

    /** Package prefixes in slash form, e.g. `java/awt`. */
    @get:Input
    abstract val forbiddenPackages: ListProperty<String>

    /** Why these are forbidden, printed when one turns up. */
    @get:Input
    abstract val reason: Property<String>

    @TaskAction
    fun check() {
        val forbidden = forbiddenPackages.get()
        val offences = mutableListOf<String>()

        classDirectories.asFileTree.matching { include("**/*.class") }.forEach { file ->
            val referenced = referencedTypes(file)
            forbidden.forEach { prefix ->
                referenced.filter { it.startsWith(prefix) }.distinct().sorted().forEach { type ->
                    offences += "  ${file.className()} references ${type.replace('/', '.')}"
                }
            }
        }

        if (offences.isNotEmpty()) {
            throw IllegalStateException(
                buildString {
                    appendLine("${offences.size} forbidden reference(s) in ${project.name}:")
                    offences.forEach { appendLine(it) }
                    appendLine()
                    appendLine(reason.get())
                },
            )
        }
        logger.lifecycle("${project.name}: no references to ${forbidden.joinToString(", ")}")
    }

    private fun File.className(): String =
        relativeTo(classDirectories.first { startsWith(it) }).path.removeSuffix(".class").replace('/', '.')

    /**
     * Every type name a class file mentions: class references and the types inside field and
     * method descriptors, which is where a dependency can also hide.
     */
    private fun referencedTypes(file: File): List<String> {
        val strings = mutableListOf<String>()
        val classNameIndices = mutableSetOf<Int>()
        val descriptorLike = mutableListOf<String>()

        DataInputStream(file.inputStream().buffered()).use { input ->
            require(input.readInt() == -0x35014542) { "$file is not a class file" }
            input.readUnsignedShort() // minor
            input.readUnsignedShort() // major

            val poolCount = input.readUnsignedShort()
            val utf8 = HashMap<Int, String>()
            var index = 1
            while (index < poolCount) {
                when (val tag = input.readUnsignedByte()) {
                    1 -> utf8[index] = input.readUTF()
                    7, 8, 16, 19, 20 -> {
                        val target = input.readUnsignedShort()
                        if (tag == 7) classNameIndices += target
                    }
                    15 -> { input.readUnsignedByte(); input.readUnsignedShort() }
                    3, 4, 9, 10, 11, 12, 17, 18 -> input.readInt()
                    5, 6 -> { input.readLong(); index++ } // longs and doubles take two slots
                    else -> error("$file has an unknown constant pool tag $tag at $index")
                }
                index++
            }

            classNameIndices.forEach { utf8[it]?.let(strings::add) }
            // Descriptors are plain Utf8 entries; pick out anything shaped like `Lsome/Type;`.
            utf8.values.forEach { if (it.contains("L") && it.contains(";")) descriptorLike += it }
        }

        strings += descriptorLike.flatMap { descriptor ->
            Regex("L([A-Za-z0-9_$/]+);").findAll(descriptor).map { it.groupValues[1] }
        }
        return strings
    }
}
