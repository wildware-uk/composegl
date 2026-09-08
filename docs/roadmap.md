# Roadmap: what is left, and what each piece actually costs

v1 is done: Compose UI inside a LibGDX frame on the desktop JVM, plus an in-world texture and a
raw-LWJGL3 proof that the seam is real. What follows is the roadmap from the design spec, §19,
re-assessed against the code that now exists rather than against the plan.

Each item says what is known, what is genuinely unresolved, and what a first day of work would be.

---

## P2 — Editor mode ([#24](https://github.com/wildware-uk/composegl/issues/24)) — **done**

**Inverted control: Compose owns the window, and the game renders into a Compose node.**

Shipped as `GameTexture` and `GameView` in core, plus `GameFrameBuffer` in `composegl-lwjgl3`. The
game binds a framebuffer, draws its frame, and a node in the Compose layout shows it — same GL
context, no copy, no upload.

The two things that looked like blockers were settled by spike S3
([`docs/superpowers/spikes/s3-editor-mode.md`](superpowers/spikes/s3-editor-mode.md)): a Skia image
adopted from a GL texture tracks the texture's live contents rather than snapshotting it, and the
ownership question is answered by ComposeGL simply owning the texture from adoption onwards.

The piece no API doc would have predicted: Compose cannot see a GL texture change, and a static
Compose tree does not redraw. `GameTexture.invalidate()` is how the game says a new frame exists,
and `GameFrameBuffer.unbind()` calls it so games get it for free.

Still open if someone wants it: routing input into the viewport in the game's own coordinates, and
the same helper for the LibGDX adapter — LibGDX's `FrameBuffer` deletes its own colour texture, so
it needs a hand-built framebuffer rather than that class.

## P3 — Android ([#25](https://github.com/wildware-uk/composegl/issues/25))

**What ComposeGL would add over `ComposeView` on top of `GLSurfaceView`: UI inside the frame, and
UI inside the world.**

### What is already in place

The awkward part is done and it was cheap. From
[`docs/superpowers/spikes/s2-awt-scan.md`](superpowers/spikes/s2-awt-scan.md): every API ComposeGL
touches is AWT-free — `ComposeScene`, `CanvasLayersComposeScene`, `PlatformContext`,
`FrameRecomposer`, the text input path, font loading, and the whole `org.jetbrains.skia` package.
The AWT in the desktop artifact is all in the windowing stack ComposeGL replaces.

CI keeps it that way: `composegl-core` fails the build if it references `java.awt`,
`javax.swing`, `com.badlogic` or `org.lwjgl`, and `composegl-libgdx` depends on gdx core with no
backend, so the adapter itself can be reused as-is.

### What is unresolved — now measured, not guessed

Spike S4 ([`docs/superpowers/spikes/s4-android-ios-artifacts.md`](superpowers/spikes/s4-android-ios-artifacts.md))
went through the published artifacts.

**Good news: Skia's GL backend is there, and on Android it is the only backend.**
`skiko-android` publishes `DirectContext.makeGL()` and `BackendRenderTarget.makeGL()`, and Skiko's
own configuration hard-codes `OS.Android -> GraphicsApi.OPENGL`. The question the roadmap said to
answer first is answered, favourably.

**The whole job is one thing: compose-ui's `skikoMain` source set, built for ART.**
`androidx.compose.ui:ui-android` contains no `ComposeScene`, no `PlatformContext` and no
`FrameRecomposer` — the scene exists only in the skiko source set, which is published for the
desktop JVM and for Kotlin/Native, not for Android. There is no shortcut artifact. S2 already
listed what the build needs: replace `Key.toString`, split the `KeyEvent_desktopKt` multifile
facade, supply a non-AWT `LocaleList`. All of it is a change to somebody else's build.

**One packaging prerequisite.** The Android skiko aar carries no `.so`; its loader calls
`System.loadLibrary`, so `libskiko-android-*.so` has to reach the app's `jniLibs` some other way.

**One genuinely ComposeGL-side item.** Android throws the GL context away when the app is
backgrounded, so `ComposeGlContext` has to be recreatable and every surface has to re-create its
target. The dispose-and-rebuild path already exists and is tested; nothing has exercised it as a
*resume*.

### First day of work

Source or build `libskiko-android-*.so`, then try to compile compose-ui's `skikoMain` for Android
with the three fixes above. If that produces a `ComposeScene` on ART, the rest of P3 is ComposeGL
code that already exists.

## P4 — iOS via RoboVM ([#26](https://github.com/wildware-uk/composegl/issues/26))

**Research, not a feature, and the real blocker is not the one the spec listed.**

Spike S4 found something more fundamental than the Metal-versus-GL question. Skiko and Compose
Multiplatform publish iOS as **Kotlin/Native klibs**, and RoboVM is a JVM. A RoboVM app cannot link
a klib, so Compose Multiplatform's existing iOS support is not reachable from a LibGDX-on-RoboVM
game at all — not with a shim, not with glue.

What a RoboVM port needs is:

1. compose-ui's `skikoMain` compiled for RoboVM's libcore — the same job as P3, which is why P3
   should come first and produces half of this for free.
2. A Skia build for iOS with the GL backend enabled. The iOS target is Metal today, and LibGDX on
   iOS is GL.

Nothing on the ComposeGL side is in the way: `RenderTarget` is a sealed type precisely so a Metal
case costs nothing to add, and core makes no GL calls.

There is still no prior art for Compose UI inside a RoboVM iOS game. Two builds of other people's
projects come before a line of ComposeGL.

## P5 — A second JVM engine adapter ([#27](https://github.com/wildware-uk/composegl/issues/27))

The cheapest item on this list, and the one the architecture was built for. See
`composegl-lwjgl3`, which is the raw-LWJGL3 adapter: it is small, and it was small because
`composegl-core` genuinely needs nothing engine-shaped.

A jMonkeyEngine adapter would be the same shape: a framebuffer, the GL state reset list from
`RenderTarget.Gl`, a premultiplied blit, an input bridge, and a keycode table. The LibGDX adapter
is the worked example.
