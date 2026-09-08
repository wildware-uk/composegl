# S4 — what actually ships for Android and iOS

Date: 2026-09-08
Issues: [#25](https://github.com/wildware-uk/composegl/issues/25), [#26](https://github.com/wildware-uk/composegl/issues/26)
Verdict: **the graphics half is fine; the Compose half is the whole job.** Neither port is
implementable from this repository, and now we know exactly why.

Offline, artifacts only. S2 asked what compose-ui and skiko *reference*; this asks what they
*publish*.

## Android

### Skia's GL backend is there, and it is the only backend

`org.jetbrains.skiko:skiko-android:0.150.1` publishes the Kotlin bindings, and they include the
calls ComposeGL is built on:

```
org.jetbrains.skia.DirectContext$Companion.makeGL()
org.jetbrains.skia.BackendRenderTarget$Companion.makeGL(int, int, int, int, int, int)
```

Skiko's own configuration is unambiguous about which API it uses there
(`SkikoProperties.kt`, jvmMain):

```kotlin
OS.Android -> GraphicsApi.OPENGL
OS.Android -> return listOf(GraphicsApi.OPENGL)
```

So on Android, Skia through Skiko is OpenGL and nothing else. That is exactly what ComposeGL's
`RenderTarget.Gl` needs, and it removes the question the roadmap said to answer first.

### The native library is not in the artifact

The aar has four entries — `R.txt`, `AndroidManifest.xml`, `classes.jar`, and an aar-metadata
properties file. No `jni/`, no `.so`. The loader explains why:

```kotlin
if (hostOs == OS.Android) {
    System.loadLibrary(name)
    return
}
```

It expects `libskiko-android-*.so` to already be in the app's `jniLibs`, put there by whatever
packages Skiko for Android. Finding or building that is a prerequisite, and it is a packaging
question rather than a code one.

### There is no ComposeScene on Android, and no shortcut artifact

`androidx.compose.ui:ui-android:1.12.0` contains **zero** classes matching `ComposeScene`,
`PlatformContext` or `FrameRecomposer`. `org.jetbrains.compose.ui:ui-android:1.12.0` is a 6 KB
relocation stub pointing at it.

That confirms what S2 implied: the scene API lives only in Compose's `skikoMain` source set, which
is published for the desktop JVM and for Kotlin/Native targets, and not for Android. Compose on
Android is the `androidx` view-backed implementation, which has no scene to render into a
framebuffer at all.

**So P3's real content is one job:** build compose-ui's `skikoMain` source set for ART. S2 already
listed what that needs — replace `Key.toString`, split the `KeyEvent_desktopKt` multifile facade,
supply a non-AWT `LocaleList` — and every one of those is a change to somebody else's build, not
to ComposeGL. Nothing on the ComposeGL side is in the way; the adapter already depends on gdx core
with no backend, and CI already enforces that core names no AWT.

## iOS

The blocker is bigger than the Metal-versus-GL question, and it is not the one the spec listed.

Skiko publishes iOS as Kotlin/Native klibs (`iosArm64`, `iosSimulatorArm64`, `iosX64`), and so does
Compose Multiplatform. **RoboVM is a JVM.** A RoboVM app cannot link a Kotlin/Native klib, so
Compose Multiplatform's existing iOS support is not reachable from a LibGDX-on-RoboVM game at all —
not with a shim, not with glue.

What a RoboVM port would actually need is compose-ui's `skikoMain` compiled for RoboVM's libcore,
which is the same job as P3, plus a Skia build for iOS with the GL backend enabled, which does not
exist today because the iOS target uses Metal.

That is two builds of other people's projects before a line of ComposeGL is written. **P4 should be
scoped as research, and only started after P3, because P3 produces half of it.**

## What changes in this repository

Nothing. Both ports stay blocked on external builds, and the assessments in
[`docs/roadmap.md`](../../roadmap.md) are updated to say so with evidence rather than expectation.
