package dev.wildware.composegl.ui.preview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composer
import androidx.compose.runtime.currentComposer
import dev.wildware.composegl.ui.backend.HeadlessBackend
import dev.wildware.composegl.ui.backend.UiBackend
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier as JavaModifier
import java.util.jar.JarFile

/**
 * One function marked [Preview], found on a classpath and ready to be composed.
 *
 * @param name the file it becomes, without `.png`.
 * @param function where it was found, as `package.ClassName.function`, for messages.
 */
class PreviewFunction internal constructor(
    val name: String,
    val width: Int,
    val height: Int,
    val background: Colour,
    val function: String,
    private val method: Method,
    private val receiver: Any?,
) {

    /** The screen it is laid out and drawn in. */
    val size: Size get() = Size(width.toFloat(), height.toFloat())

    /**
     * The marked function itself, callable from a composition.
     *
     * The compiler gave the function two parameters nobody wrote — the composer and a bitmask of
     * which arguments changed — and those are exactly what a call from inside a composition passes.
     * With no arguments nothing has changed, so the mask is zero.
     */
    val content: @Composable () -> Unit = { call(currentComposer) }

    /** The content on its [background], filling the preview, which is what a picture of it shows. */
    val screen: @Composable () -> Unit = {
        val ground = if (background.alpha == 0) Modifier else Modifier.background(background)
        Box(Modifier.fillMaxSize().then(ground)) { content() }
    }

    private fun call(composer: Composer) {
        try {
            method.invoke(receiver, composer, 0)
        } catch (thrown: InvocationTargetException) {
            // What the preview itself threw, rather than reflection's wrapper around it.
            throw thrown.cause ?: thrown
        }
    }

    override fun toString() = "$name ($function, ${width}x$height)"
}

/**
 * Finds the functions marked [Preview].
 *
 * Nothing registers a preview: marking the function is the whole of it, the way marking a test is.
 * So they are found by looking — every class under the directories and jars given, loaded without
 * being initialised, asked for methods carrying the annotation.
 *
 * A mistake fails here, loudly, rather than turning into a missing picture nobody notices: a
 * preview with parameters, one that is not `@Composable`, one inside a class that would need an
 * instance, and two previews that would write the same file.
 */
object Previews {

    /**
     * Every preview under [roots], sorted by name.
     *
     * @param roots class directories or jars — a module's compiled output, not its whole classpath.
     * @param loader what loads the classes. It has to see everything they reference.
     * @param packageName only classes in this package or below it. Empty looks at all of them.
     */
    fun find(
        roots: List<File>,
        loader: ClassLoader = Previews::class.java.classLoader,
        packageName: String = "",
    ): List<PreviewFunction> {
        val found = roots
            .flatMap(::classNames)
            .filter { packageName.isEmpty() || it == packageName || it.startsWith("$packageName.") }
            .distinct()
            .flatMap { className ->
                val type = try {
                    Class.forName(className, false, loader)
                } catch (_: LinkageError) {
                    // A class referring to something this classpath does not have cannot hold a
                    // preview anybody could run either.
                    return@flatMap emptyList()
                } catch (_: ClassNotFoundException) {
                    return@flatMap emptyList()
                }
                of(type)
            }

        found.groupBy { it.name }.filterValues { it.size > 1 }.entries.firstOrNull()?.let { (name, clash) ->
            throw IllegalStateException(
                "two previews would both write $name.png: ${clash.joinToString { it.function }}. " +
                    "Give one of them a name.",
            )
        }
        return found.sortedBy { it.name }
    }

    /** The previews declared directly in [type]: a file's top-level functions, or an object's. */
    fun of(type: Class<*>): List<PreviewFunction> {
        val methods = try {
            type.declaredMethods
        } catch (_: LinkageError) {
            return emptyList()
        }
        return methods
            .filter { !it.isSynthetic && it.isAnnotationPresent(Preview::class.java) }
            .filterNot { isCompanionCopyOfStatic(type, it) }
            .sortedBy { it.name }
            .map { preview(type, it) }
    }

    private fun preview(type: Class<*>, method: Method): PreviewFunction {
        val annotation = method.getAnnotation(Preview::class.java)
        val function = "${type.name}.${method.name}"
        val parameters = method.parameterTypes

        require(parameters.isNotEmpty() && parameters.first() == Composer::class.java) {
            if (parameters.isEmpty()) {
                "$function is marked @Preview but is not @Composable, so there is nothing to draw"
            } else {
                "$function is marked @Preview but is not @Composable, or takes arguments; " +
                    "a preview takes none, because nothing would be there to pass them"
            }
        }
        require(parameters.size == 2 && parameters[1] == Int::class.javaPrimitiveType) {
            "$function takes arguments; a preview takes none, because nothing would be there to pass them"
        }
        require(annotation.width > 0 && annotation.height > 0) {
            "$function is ${annotation.width}x${annotation.height}; a preview is at least one pixel each way"
        }

        val receiver: Any? = if (JavaModifier.isStatic(method.modifiers)) {
            null
        } else {
            // An object's members are called on its one instance, and a companion's on the one its
            // class holds. Anything else would need an instance this cannot make.
            val instance = singleInstance(type)
            requireNotNull(instance) {
                "$function is marked @Preview inside a class; put it at the top level of a file, " +
                    "in an object or in a companion object, so there is no instance to make"
            }
            instance.isAccessible = true
            instance.get(null)
        }
        method.isAccessible = true

        return PreviewFunction(
            name = annotation.name.ifEmpty { method.name },
            width = annotation.width,
            height = annotation.height,
            background = Colour(annotation.background.toInt()),
            function = function,
            method = method,
            receiver = receiver,
        )
    }

    /**
     * The static field holding [type]'s one instance: `INSTANCE` on an object, or the field on the
     * enclosing class that a companion object is kept in. Null for an ordinary class.
     */
    private fun singleInstance(type: Class<*>): java.lang.reflect.Field? {
        fun java.lang.reflect.Field.holds() = JavaModifier.isStatic(modifiers) && this.type == type
        runCatching { type.getDeclaredField("INSTANCE") }.getOrNull()?.takeIf { it.holds() }?.let { return it }
        val outer = runCatching { type.declaringClass }.getOrNull() ?: return null
        return runCatching { outer.declaredFields }.getOrDefault(emptyArray()).firstOrNull { it.holds() }
    }

    /**
     * Whether [method] is a companion's own copy of a `@JvmStatic` preview.
     *
     * Kotlin writes a `@JvmStatic` companion function twice, both carrying the annotation: once on
     * the companion and once, static, on the class around it. The static one is kept, so the pair
     * is one preview rather than two that clash over one file name.
     */
    private fun isCompanionCopyOfStatic(type: Class<*>, method: Method): Boolean {
        if (JavaModifier.isStatic(method.modifiers)) return false
        val outer = runCatching { type.declaringClass }.getOrNull() ?: return false
        if (singleInstance(type)?.declaringClass != outer) return false
        val static = runCatching { outer.getDeclaredMethod(method.name, *method.parameterTypes) }.getOrNull()
        return static != null && JavaModifier.isStatic(static.modifiers) && static.isAnnotationPresent(Preview::class.java)
    }

    /** The binary names of every class in a directory or a jar. */
    private fun classNames(root: File): List<String> = when {
        root.isDirectory -> root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".class") }
            .map { it.relativeTo(root).invariantSeparatorsPath.toClassName() }
            .toList()

        root.isFile && root.name.endsWith(".jar") -> JarFile(root).use { jar ->
            jar.entries().asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".class") }
                .map { it.name.toClassName() }
                .toList()
        }

        else -> emptyList()
    }

    private fun String.toClassName() = removeSuffix(".class").replace('/', '.')
        .takeUnless { it.endsWith("module-info") || it.endsWith("package-info") }
        .orEmpty()
}

/**
 * A preview, composed at its own size on its own background, to be driven like any other screen.
 *
 * The picture and the test start from the same function, so what somebody looked at in the PNG is
 * what the test clicks through.
 */
fun uiTest(preview: PreviewFunction, backend: UiBackend = HeadlessBackend()): UiTest =
    uiTest(preview.size, backend, content = preview.screen)
