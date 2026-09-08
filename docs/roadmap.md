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

### What is unresolved

1. **A skiko-backed `ComposeScene` on ART.** Compose Multiplatform's Android artifact is the
   `androidx.compose.ui` one, which has no `ComposeScene`; the scene lives in the skiko source set
   and ships in the desktop artifact. Getting it onto ART is a repackaging job — build the skiko
   source set for Android, replace `Key.toString`, split the `KeyEvent_desktopKt` multifile facade,
   provide a non-AWT `LocaleList` — but it is a build of somebody else's project, not a change to
   this one.
2. **Skia for Android with the GL backend.** Skiko publishes Android artifacts; whether the GL
   backend is enabled in them needs checking before anything else.
3. **Context loss on resume.** Android throws the GL context away when the app is backgrounded.
   `ComposeGlContext` would have to be recreatable and every surface would have to re-create its
   target. The lifecycle is already right for this — dispose and rebuild is a supported path today
   — but nothing has ever exercised it.

### First day of work

Answer question 2 before touching anything: unpack the Android skiko artifact and look for the GL
backend. If it is not there, P3 is a Skia build problem, not a ComposeGL problem, and the estimate
changes by an order of magnitude.

---

## P4 — iOS via RoboVM ([#26](https://github.com/wildware-uk/composegl/issues/26))

**Real, expensive, and blocked on somebody else's build.**

Three things have to be true, and only one of them is about ComposeGL.

1. **Skia for iOS with GL enabled.** Skiko ships Metal for iOS. LibGDX on iOS is GL via RoboVM. So
   this needs a Skia build that does not currently exist, or a Metal `RenderTarget` and an engine
   that renders through Metal.
2. **Compose's skiko source set running on RoboVM's libcore.** That is Android's libcore, so S2's
   findings apply and the same repackaging that unlocks P3 unlocks this. Whether RoboVM's
   ahead-of-time compiler copes with Compose's use of reflection and coroutines is unknown.
3. **The ComposeGL side.** Nothing. `RenderTarget` is a sealed type precisely so a Metal case
   costs nothing to add, and core makes no GL calls.

The order matters: P3 first, and only then this, because P3 answers question 2 for free.

### Worth saying plainly

There is no prior art for Compose UI inside a RoboVM iOS game. This is a research project, not a
feature, and it should be scoped as one.

---

## P5 — A second JVM engine adapter ([#27](https://github.com/wildware-uk/composegl/issues/27))

The cheapest item on this list, and the one the architecture was built for. See
`composegl-lwjgl3`, which is the raw-LWJGL3 adapter: it is small, and it was small because
`composegl-core` genuinely needs nothing engine-shaped.

A jMonkeyEngine adapter would be the same shape: a framebuffer, the GL state reset list from
`RenderTarget.Gl`, a premultiplied blit, an input bridge, and a keycode table. The LibGDX adapter
is the worked example.
