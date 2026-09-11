plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

description = "Snake on a phone: the LibGDX Android backend, and the same game as the desktop one."

/**
 * The processor kinds this build carries natives for. The emulator here is x86_64; a phone is one
 * of the two arm ones.
 *
 * LibGDX publishes each native as a plain jar with the `.so` sitting at the root, which is not
 * where Android looks for it. So they cannot be ordinary dependencies — a jar on the classpath
 * puts nothing in `lib/<abi>/`. One configuration per processor kind, unpacked by
 * [UnpackAndroidNatives] into a folder handed to the variant as a generated JNI source, is the
 * whole of the fix, and is what LibGDX's own project template does.
 */
val nativeAbis = listOf("armeabi-v7a", "arm64-v8a", "x86_64")

val nativeConfigurations = nativeAbis.map { abi ->
    configurations.create("natives-$abi") {
        isCanBeConsumed = false
        isCanBeResolved = true
    }
}

/**
 * Turns LibGDX's flat natives jars into the `<abi>/lib*.so` tree Android packages.
 *
 * A task class rather than a `Sync`, because the Android plugin will only take a generated source
 * folder from a task that names it as an output property — which is also what makes the build
 * order right without anybody writing a `dependsOn`.
 */
abstract class UnpackAndroidNatives : DefaultTask() {

    @get:InputFiles
    abstract val jars: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Inject
    abstract val archives: ArchiveOperations

    @get:Inject
    abstract val files: FileSystemOperations

    @TaskAction
    fun unpack() {
        files.delete { delete(outputDirectory) }
        jars.forEach { jar ->
            val abi = requireNotNull(ABI.find(jar.name)) { "Not a LibGDX natives jar: ${jar.name}" }
            files.copy {
                from(archives.zipTree(jar))
                include("*.so")
                into(outputDirectory.dir(abi.groupValues[1]))
            }
        }
    }

    private companion object {
        /** `gdx-platform-1.14.2-natives-x86_64.jar` says which processor it is for, and nothing else does. */
        val ABI = Regex("""-natives-([A-Za-z0-9_-]+)\.jar$""")
    }
}

val copyAndroidNatives = tasks.register<UnpackAndroidNatives>("copyAndroidNatives") {
    description = "Unpacks LibGDX's native jars into the shape Android packages JNI libraries from."
    jars.from(nativeConfigurations)
    outputDirectory.set(layout.buildDirectory.dir("gdx-natives"))
}

android {
    namespace = "uk.wildware.composegl.snake.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "uk.wildware.composegl.snake.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        named("release") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(
            copyAndroidNatives,
            UnpackAndroidNatives::outputDirectory,
        )
    }
}

// Android's Java is not the desktop's, so this module says 17 where the rest of the build says 21.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    // The game, with no backend in it, and the LibGDX backend, which on Android is the one that
    // exists. Between them that is the whole port.
    implementation(project(":composegl-demo-snake-core"))
    implementation(project(":composegl-gdx"))

    // The parts of a phone LibGDX does not report: what the keyboard is covering, and when the
    // player dismissed it.
    implementation(project(":composegl-android"))
    // Used directly by the launcher to go fullscreen without losing insets; see AndroidLauncher.
    implementation(libs.androidx.core)
    implementation(libs.gdx.backend.android)

    // The native halves of LibGDX and FreeType, one bucket per processor kind. Not ordinary
    // dependencies: see [nativeAbis] for why.
    nativeAbis.forEach { abi ->
        add("natives-$abi", variantOf(libs.gdx.platform) { classifier("natives-$abi") })
        add("natives-$abi", variantOf(libs.gdx.freetype.platform) { classifier("natives-$abi") })
    }
}
