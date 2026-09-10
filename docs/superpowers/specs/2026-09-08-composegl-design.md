# ComposeGL — Design Spec

Date: 2026-09-08
Status: **superseded and abandoned.** This designed the Skia product, which was built, worked, and
was dropped on 2026-09-09 because skiko publishes no Android or iOS binary. The code is at the tag
`skia-final`. The current design is
[`2026-09-09-runtime-ui-design.md`](2026-09-09-runtime-ui-design.md).

Kept because it records why each decision was made, and most of those reasons still apply.

## 1. What this is

A library that lets a game written against a JVM game engine draw its UI with Jetpack Compose (Compose Multiplatform), inside the game's own frame, using the game's own OpenGL context.

Plain version: Compose is the brain and hands that decide and lay out the UI. Skia is the pen that draws it. The game engine supplies the paper (an OpenGL framebuffer). This library is the desk that holds them all in place and passes notes between them.

First engine: LibGDX. Everything engine-specific lives in one adapter module so a second engine is a second adapter, not a rewrite.

## 2. Decisions already made, and why

| Decision | Why |
|---|---|
| Desktop JVM only for v1 | It is the only platform where Compose's `ComposeScene`, Skiko, and a shared GL context all ship today with no porting. |
| OpenGL is the only v1 backend, but the API never says "GL" | Skia is backend-blind. A `RenderTarget` value keeps Metal/Vulkan reachable at zero cost now. |
| LibGDX is adapter #1 and the only shipped adapter | User choice. A ~200-line raw LWJGL3 smoke test keeps the core/adapter seam honest. |
| Compose renders to an offscreen FBO, then the engine blits a quad | Compose re-renders only when the composition changes. A static HUD at 144 fps costs 144 blits and zero Compose renders. Direct-to-backbuffer would force a full render every frame. It also gives in-world UI for free and isolates GL state. |
| Everything Compose does runs on the game render thread inside `update()` | No races with the game loop, no locks, predictable frame time. |
| Core module never imports `java.awt.*` or any engine | Android (ART) and iOS (RoboVM) share Android's libcore, which has no AWT. Keeping core AWT-free is what keeps those ports possible later. Enforced by a CI bytecode check. |
| iOS / Android / web are out of scope for v1 | LibGDX on iOS is RoboVM (Java) and on web is GWT/TeaVM; Compose there is Kotlin/Native and Kotlin/Wasm. Different runtimes, cannot call each other. Android is reachable later and shares the AWT problem with RoboVM, so one fix serves both. |

## 3. Non-goals for v1

- IME composition (CJK candidate windows). Dead keys work because GLFW composes them before LibGDX sees them.
- Accessibility / screen readers.
- Android, iOS, web.
- Drag and drop, multiple windows.
- Second engine adapter.

## 4. Architecture

```
 game render thread, every frame
 ┌──────────────────────────────────────────────────────────────┐
 │ game draws world                                             │
 │ overlay.update()  ─► drain dispatcher ─► Snapshot notify     │
 │                     ─► clock.sendFrame ─► recompose + layout │
 │ overlay.draw()    ─► if scene.hasInvalidations():            │
 │                          bind FBO, resetAll, scene.render,   │
 │                          flush, restore GL state             │
 │                     ─► blit FBO texture as a full-screen quad│
 └──────────────────────────────────────────────────────────────┘

 input: Gdx.input ─► InputMultiplexer(overlay, game)
        overlay returns true when Compose consumed the event
```

Layers, bottom to top:

1. **Skiko** — Kotlin bindings to Skia. Creates a `DirectContext` on the engine's live GL context and a `Surface` on the engine's FBO. Does its own GL calls through its own native loader; needs only that a GL context is current on the calling thread.
2. **Compose UI `ComposeScene`** — takes content, a coroutine context, a frame clock, and a `PlatformContext`. Measures, lays out, and draws into any Skia `Canvas`. Accepts pointer and key events.
3. **`composegl-core`** — owns the scene, the frame clock, the dispatcher, the `PlatformContext` implementation, the text input service, and consumption tracking. No GL calls, no engine, no AWT.
4. **`composegl-libgdx`** — creates the FBO, restores GL state, blits the quad, implements `HostServices`, implements `InputProcessor`, maps keycodes.

## 5. Modules

```
composegl-core          Kotlin/JVM. Deps: compose-runtime, compose-ui, compose-foundation, skiko.
composegl-libgdx        Kotlin/JVM. Deps: core, gdx. No backend dependency, so the same adapter can serve Android later.
composegl-demo-libgdx   Runnable sample: spinning cube + Material HUD + in-world panel.
composegl-smoke-lwjgl3  Raw LWJGL3, no LibGDX. ~200 lines. Proves the seam; hosts GL integration tests.
composegl-lwjgl3        Added after v1 shipped: the raw LWJGL3 adapter, for games with no engine at all.
```

Build rules enforced in CI:

- `composegl-core` fails the build if any class in *our* compiled output references `java.awt`, `javax.swing`, `com.badlogic`, or `org.lwjgl`. Transitive dependencies (compose-ui-desktop, skiko-awt) are not scanned; we only control our own code. There are no allowlisted exceptions: S1-d showed Compose `KeyEvent` can be built without AWT, so the `DesktopKeyEvents.kt` escape hatch was never needed.
- Every `@OptIn(InternalComposeUiApi::class)` lives in `SceneBridge.kt`. Nowhere else.
- Compose Multiplatform, Kotlin, Skiko, and LibGDX versions are pinned in `gradle/libs.versions.toml`:

| Library | Pin |
|---|---|
| Kotlin | 2.4.20 |
| Compose Multiplatform | 1.12.0 |
| Skiko | 0.150.1 |
| Material 3 | 1.9.0 |
| LibGDX | 1.14.2 |
| JVM toolchain | 21 |

## 6. Core API

```kotlin
package composegl

/** One per GL context. Wraps Skia's DirectContext. Create with the GL context current. */
class ComposeGlContext private constructor(...) {
    companion object {
        /** Throws ComposeGlUnsupportedException if Skia cannot wrap the current context. */
        fun create(config: ContextConfig = ContextConfig()): ComposeGlContext
    }
    fun dispose()
}

data class ContextConfig(
    val resourceCacheBytes: Long = 64L * 1024 * 1024,
    val debugChecks: Boolean = false,          // thread asserts + glGetError after render
)

/** What the engine gives us to draw into. Only GL in v1; the sealed type is the door for Metal/Vulkan. */
sealed interface RenderTarget {
    data class Gl(
        val framebufferId: Int,
        val width: Int,
        val height: Int,
        val stencilBits: Int = 8,              // Skia needs stencil for path clipping
        val sampleCount: Int = 0,              // Skia anti-aliases analytically; MSAA not needed
    ) : RenderTarget
}

/** What the engine must provide. Every method has a default so a minimal host is `object : HostServices { override val density = 1f }`. */
interface HostServices {
    val density: Float                          // desktop: backBufferWidth / logicalWidth
    fun requestFrame() {}                       // for engines with non-continuous rendering
    fun setCursor(cursor: CursorShape) {}
    fun getClipboard(): String? = null
    fun setClipboard(text: String) {}
    fun showSoftKeyboard(visible: Boolean) {}
}

enum class CursorShape { Default, Text, Hand, Crosshair }

class ComposeSurface(
    context: ComposeGlContext,
    host: HostServices,
    config: SurfaceConfig = SurfaceConfig(),
) {
    fun setContent(content: @Composable () -> Unit)

    /** Call on creation and on every size change. Recreates the Skia surface. */
    fun setRenderTarget(target: RenderTarget)

    /** Runs pending coroutine work, propagates state, sends a frame to the clock. */
    fun update(frameTimeNanos: Long)

    /** True when the composition, layout, or draw was invalidated since the last render, or the target changed. */
    val needsRedraw: Boolean

    /** Draws into the render target. Caller binds nothing; Skia binds the FBO itself. Caller restores GL state afterwards. */
    fun render(frameTimeNanos: Long)

    // Input. Types are Compose's own public types. Return true when Compose consumed the event.
    fun sendPointerEvent(
        type: PointerEventType, x: Float, y: Float,
        pointerId: Int = 0, button: PointerButton? = null,
        pointerType: PointerType = PointerType.Mouse,
        scrollX: Float = 0f, scrollY: Float = 0f,
        modifiers: PointerKeyboardModifiers = PointerKeyboardModifiers(),
        timeMillis: Long = System.nanoTime() / 1_000_000,
    ): Boolean
    fun sendKeyEvent(
        key: Key, down: Boolean,
        modifiers: PointerKeyboardModifiers = PointerKeyboardModifiers(),
        timeMillis: Long = System.nanoTime() / 1_000_000,
    ): Boolean
    fun sendChar(codePoint: Int): Boolean

    /** True while a Compose node holds keyboard focus. Adapter uses it to decide whether the game sees key events. */
    val hasKeyboardFocus: Boolean

    val stats: SurfaceStats
    fun dispose()
}

data class SurfaceConfig(
    val fontScale: Float = 1f,
    /** Called when content throws during composition, layout, or draw. Default rethrows. */
    val onError: (Throwable) -> Unit = { throw it },
)

data class SurfaceStats(
    val composeRenders: Long,     // times scene.render actually ran
    val frames: Long,             // times update() ran
    val lastRenderNanos: Long,
)
```

Threading contract, stated once: **every method on `ComposeGlContext` and `ComposeSurface` must be called on the thread where the GL context is current.** With `debugChecks = true` the thread is captured at construction and asserted on every call. The failure mode without this is a GL crash several frames later with a useless stack.

## 7. Frame lifecycle

Compose needs a frame clock and a `CoroutineDispatcher`. Desktop Compose normally owns both and ties them to vsync. We take both over.

Compose 1.12.0 ships `FrameRecomposer`, the host-side driver that a platform is expected to own. It bundles a `BroadcastFrameClock`, the `Recomposer`, the two work queues, and a `GlobalSnapshotManager` registration, so we no longer hand-roll any of that.

- **`GameLoopDispatcher`** — a `CoroutineDispatcher` whose `dispatch` appends to a queue. `isDispatchNeeded` always returns true so nothing runs inline mid-render. `update()` drains the queue. It does not implement `Delay`; `delay()` in a `LaunchedEffect` uses the default timer and resumes back on our queue, which is the behaviour we want.
- **`FrameRecomposer(dispatcher, invalidate = { host.requestFrame() })`** — `performFrame(frameTimeNanos)` drains its trampoline queue and sends the frame. Compose animations therefore tick at the game's frame rate; if the game stops calling `update()`, animations freeze. `hasPendingWork()` reports outstanding recomposition or queued work.
- The recomposer is passed to `CanvasLayersComposeScene`, along with `invalidateLayout` and `invalidateDraw` callbacks.

`ComposeScene` no longer has `render(canvas, nanos)`. Layout is a host-driven phase: `measureAndLayout()` runs at the end of `update()`, and `draw(canvas)` runs in `render()`, so `needsRedraw` is evaluated after layout has settled.

```kotlin
fun update(frameTimeNanos: Long) {
    assertGlThread()
    dispatcher.drain()                    // effects, coroutine resumptions
    recomposer.performFrame(frameTimeNanos)  // apply notifications, clock, recomposition
    dispatcher.drain()                    // recomposition may have queued more
    scene.measureAndLayout()
    stats.frames++
}

val needsRedraw get() = !failed && skiaSurface != null && (targetChanged || scene.hasInvalidations())

fun render(frameTimeNanos: Long) {
    assertGlThread()
    if (skiaSurface == null) return       // zero-size target (window minimised)
    directContext.resetAll()              // forget cached GL state; the engine changed it
    skiaSurface.canvas.clear(Color.TRANSPARENT)
    scene.draw(skiaSurface.canvas.asComposeCanvas())
    directContext.flush()
    targetChanged = false
    stats.composeRenders++
}
```

`FrameRecomposer` registers itself with `GlobalSnapshotManager`, so we do not call `Snapshot.sendApplyNotifications()` ourselves.

`hasInvalidations()` is `hasPendingMeasureOrLayout || hasPendingDraw`. It is `false` for untouched static content after the first render (S1-f). A **focused** `TextField` blinks its caret, which invalidates draw every frame; that is the one common case where a HUD is never static.

Resize: `setRenderTarget` with new dimensions disposes the old Skia `Surface` and `BackendRenderTarget`, creates new ones, and sets `scene.size`. If width or height is 0 the surface is left null and `render()` returns early. Density changes (window moved to a different-DPI monitor) go through `setRenderTarget` too; the adapter passes the new density via `HostServices.density`, which core reads at that moment and pushes into `scene.density`.

Dispose order, always on the GL thread: scene, then Skia `Surface`, then `BackendRenderTarget`. `ComposeGlContext.dispose()` disposes the `DirectContext` last. Both are idempotent.

## 8. Rendering and the GL state firewall

Skia and LibGDX both cache GL state and both assume nobody else touched it. Neither is trusted.

Before Skia draws: `directContext.resetAll()`. Tells Skia its cached state is stale.

After Skia draws, the **adapter** resets to GL defaults. LibGDX rebinds what it needs at each `begin()`/`bind()`, so defaults are enough:

```
glBindFramebuffer(GL_FRAMEBUFFER, 0)
glViewport(0, 0, backBufferWidth, backBufferHeight)
glUseProgram(0)
glBindVertexArray(0)
glBindBuffer(GL_ARRAY_BUFFER, 0); glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0)
glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, 0)
glDisable(GL_SCISSOR_TEST); glDisable(GL_STENCIL_TEST)
glDepthMask(true); glColorMask(true, true, true, true)
glDisable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
glPixelStorei(GL_UNPACK_ALIGNMENT, 4)
```

This list lives in core as a doc comment on `RenderTarget.Gl` so every adapter author sees it. The adapter executes it with the engine's GL binding.

Skia surface: `Surface.makeFromBackendRenderTarget(context, BackendRenderTarget.makeGL(w, h, sampleCount, stencilBits, fboId, GL_RGBA8), origin, SurfaceColorFormat.RGBA_8888, ColorSpace.sRGB)`. Pixels are premultiplied. The blit therefore uses `glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)`. Origin is `SurfaceOrigin.TOP_LEFT` (S1-b): a plain `TextureRegion(fbo.colorBufferTexture)` is then upright with no flip. The choice is made once in the adapter and never exposed.

Blit: adapter draws the FBO colour texture with its own `SpriteBatch` in premultiplied mode, full screen. One draw call.

Multiple surfaces share one `ComposeGlContext`. Each surface has its own `ComposeScene`, own `Recomposer`, own FBO. Clock and dispatcher are per-context, shared.

## 9. Input

Core accepts Compose's public input types. No middle enum.

**Pointer.** `sendPointerEvent` builds a Compose pointer event and calls `scene.sendPointerEvent(...)`, which returns a `PointerEventResult` synchronously (S1-e). No wrapper composable and no consumption tracker are needed.

`PointerEventResult.anyChangeConsumed` is `internal`, but its constructor is public, so the flag is read by comparing against the constructible values that have it set:

```kotlin
internal fun PointerEventResult.changeConsumed(): Boolean =
    listOf(false, true).any { m -> listOf(false, true).any { d ->
        this == PointerEventResult(m, true, d) } }
```

It is a value class over an `Int`, so this is four integer comparisons.

Rules applied in core:

- A press consumed by Compose captures that pointer id. Until its release, its moves and the release itself go to Compose and return true regardless of consumption.
- A press consumed by nobody calls `focusManager.clearFocus()` so the game gets keyboard events back.
- Moves of an uncaptured pointer (hover) are delivered to Compose for hover effects but always return false. The game seeing hover is harmless and keeps crosshairs and picking working.
- Scroll returns whatever a `scrollable` consumed.

**Keyboard.** `sendKeyEvent` returns false immediately when `!hasKeyboardFocus` — Compose never sees keys the user is aiming at the game. When focused, it builds a Compose `KeyEvent` with the AWT-free `KeyEvent(key, type, codePoint, …)` factory (S1-d) and returns the scene's answer. `hasKeyboardFocus` reads `scene.focusManager.hasFocus` directly.

**Characters.** `sendChar(codePoint)` goes to the text input service (Section 10), never to `scene.sendKeyEvent`. Returns true when a text field is active.

**Modifiers.** Adapter builds `PointerKeyboardModifiers` by querying `Gdx.input.isKeyPressed` for shift, ctrl, alt, meta at event time. LibGDX events carry no modifier bits.

**Coordinates.** LibGDX `InputProcessor` coordinates are logical (window) pixels, top-left origin, y down — same orientation as Compose. On HDPI the adapter multiplies by `backBufferWidth / width`. Compose works in physical pixels and applies `density` itself.

**Keycode map.** `composegl-libgdx` holds one table `Input.Keys` → `androidx.compose.ui.input.key.Key`, about 100 entries, unit-tested for coverage of every `Input.Keys` constant. Unmapped keys become `Key.Unknown` and are dropped.

## 10. Platform services

Core implements `PlatformContext` by delegating to `PlatformContext.Empty` and overriding:

- `setPointerIcon(icon)` — compares against `PointerIcon.Default/Text/Hand/Crosshair` and calls `HostServices.setCursor`. Anything else maps to `Default`.
- `startInputMethod(request)` — the session path, which is what Material 3's `TextField` actually uses (S1-g). It stores the `PlatformTextInputMethodRequest`, calls `host.showSoftKeyboard(true)`, suspends until cancelled, then clears and hides. `sendChar` calls `request.onEditCommand(listOf(CommitTextCommand(text, 1)))`.
- `textInputService` — the same behaviour behind the legacy `PlatformTextInputService`, kept so content still on the old path works. Both funnel into one holder; `sendChar` uses whichever is live.
- Backspace, arrows, selection, and shortcuts arrive as key events and are handled by Compose's own text field key handling. Cut/copy/paste shortcuts route through the clipboard below.
- `viewConfiguration` — touch slop 8dp × density, long press 500ms, double tap 300ms. Plain defaults.
- `windowInfo` — reports `isWindowFocused = true` always. Game surfaces do not have a focus concept we can honour without AWT.
- Clipboard — core wraps user content in `CompositionLocalProvider(LocalClipboardManager provides GameClipboardManager(host))`, which forwards to `HostServices.getClipboard/setClipboard`. Providing the local ourselves works regardless of what `PlatformContext` offers in the pinned version.

Accessibility: `semanticsOwnerListener` left empty. Stated non-goal.

Fonts: Compose desktop resolves `FontFamily.Default` through Skiko's `FontMgr`, no AWT. Games should bundle fonts via `Font(resource)` for consistent rendering across machines; the demo does.

## 11. LibGDX adapter API

```kotlin
package composegl.gdx

/** Process-wide holder for the one ComposeGlContext. Created lazily on first use. */
object ComposeGdx {
    fun init(config: ContextConfig = ContextConfig())   // optional; called implicitly otherwise
    fun dispose()                                       // call from ApplicationListener.dispose()
    val hostServices: HostServices                      // density, cursor, clipboard via Gdx
}

/** Screen-sized Compose layer. Feels like scene2d Stage. */
class ComposeOverlay(config: SurfaceConfig = SurfaceConfig()) : InputProcessor, Disposable {
    fun setContent(content: @Composable () -> Unit)
    fun resize(width: Int, height: Int)      // from ApplicationListener.resize; uses backbuffer size internally
    fun update()                             // uses System.nanoTime()
    fun draw()                               // render if needed, then blit
    val stats: SurfaceStats
    val hasKeyboardFocus: Boolean
    // InputProcessor: every method returns true when Compose consumed the event
}

/** Fixed-size Compose texture for in-world UI. You draw the texture; you feed it input. */
class ComposeTexture(width: Int, height: Int, config: SurfaceConfig = SurfaceConfig()) : Disposable {
    fun setContent(content: @Composable () -> Unit)
    fun update()
    fun render()                             // render if needed; no blit
    val texture: Texture                     // premultiplied alpha; use blend ONE, ONE_MINUS_SRC_ALPHA
    fun sendPointer(type: PointerEventType, x: Float, y: Float, button: PointerButton? = null): Boolean
    fun sendKey(keycode: Int, down: Boolean): Boolean
    fun sendChar(c: Char): Boolean
    val stats: SurfaceStats
}
```

Usage:

```kotlin
class MyGame : ApplicationAdapter() {
    lateinit var ui: ComposeOverlay

    override fun create() {
        ui = ComposeOverlay()
        ui.setContent { Hud(viewModel) }
        Gdx.input.inputProcessor = InputMultiplexer(ui, gameInput)   // UI gets first refusal
    }
    override fun resize(w: Int, h: Int) = ui.resize(w, h)
    override fun render() {
        drawWorld()
        ui.update()
        ui.draw()
    }
    override fun dispose() { ui.dispose(); ComposeGdx.dispose() }
}
```

Requirements documented in the README and checked at `ComposeGdx.init()`:

- `Lwjgl3ApplicationConfiguration.useOpenGL3(true, 3, 2)` or newer. Anything below GL 3.0 throws `ComposeGlUnsupportedException("ComposeGL needs OpenGL 3.0+. Call config.useOpenGL3(true, 3, 2) before Lwjgl3Application.")`.
- FBO: `FrameBuffer(Pixmap.Format.RGBA8888, w, h, hasDepth = true, hasStencil = true)`. Depth is unused by Skia but LibGDX's stencil-only path is unreliable across drivers; packed depth-stencil is the safe choice.
- Cursor: `Gdx.graphics.setSystemCursor(SystemCursor.Arrow/Ibeam/Hand/Crosshair)`.
- Clipboard: `Gdx.app.clipboard`.
- Non-continuous rendering: `requestFrame()` calls `Gdx.graphics.requestRendering()`.

## 12. Mount modes

- **A — HUD overlay.** `ComposeOverlay`. Delivered in P1.
- **B — In-world.** `ComposeTexture`. Delivered in P1. The developer raycasts, converts the hit to texture-space pixels, and calls `sendPointer`. Demo shows a panel on a quad in the 3D scene.
- **C — Editor.** Compose owns the window, game renders into a Compose node. Delivered after v1 as `GameTexture` + `GameView` in core and `GameFrameBuffer` in `composegl-lwjgl3`; see [`docs/superpowers/spikes/s3-editor-mode.md`](../spikes/s3-editor-mode.md).

## 13. Error handling

| Situation | Behaviour |
|---|---|
| Method called off the GL thread (debug) | `IllegalStateException("ComposeSurface must be used on the GL thread (expected 'LWJGL Application', got 'pool-1-thread-3')")` |
| `DirectContext.makeGL()` fails | `ComposeGlUnsupportedException` with GL version string and the `useOpenGL3` hint |
| Skiko native library fails to load | Wrapped in `ComposeGlUnsupportedException("Skiko native library not found for linux-x64. Add runtimeOnly(\"org.jetbrains.skiko:skiko-awt-runtime-linux-x64:<version>\")")` |
| Content throws in composition, layout, or draw | Caught in `update()`/`render()`, passed to `SurfaceConfig.onError`. Default rethrows. If the handler returns, the surface is marked failed, `needsRedraw` stays false, `render()` returns early, and the last good texture keeps being blitted. `setContent` clears the failed state. |
| Render target 0×0 | `render()` returns early, scene kept, no exception |
| `dispose()` twice | No-op |
| `glGetError()` non-zero after render (debug) | Logged once per error code with the Skia render count, then suppressed |
| `sendPointerEvent` before `setContent` | Returns false |

## 14. Testing

**Core, headless, runs in CI without a GPU.** `ComposeScene` draws into a CPU Skia surface (`Surface.makeRasterN32Premul`) exactly as it draws into GL. Core has a test-only `RenderTarget.Raster(width, height)` variant, internal to the test source set, so every core test drives the real scene, real clock, real dispatcher, real input path.

- Render a `Button`; assert the pixel at its centre is the Material primary colour.
- Send press+release at the button; assert `onClick` fired and both events returned true.
- Send press on empty space; assert returned false and `hasKeyboardFocus` became false.
- Focus a `TextField`, `sendChar('a'..)`; assert value updated. Send `Key.Backspace`; assert deletion.
- Static content: after the first render, run 100 `update()` calls; assert `stats.composeRenders == 1`.
- Animating content (`rememberInfiniteTransition`): assert `composeRenders` grows every frame.
- `LaunchedEffect` that sets state after `delay(10)`; pump `update()`; assert recomposition occurred on the game thread (record `Thread.currentThread()` inside the composable).
- `onError`: content throws; assert handler called, surface marked failed, subsequent `render()` does not throw.
- Dispose idempotent.
- `GameLoopDispatcher`: ordering, re-entrancy (a task that enqueues a task runs it in the same drain).

**LibGDX adapter, headless.** Keycode table covers every `Input.Keys` constant. Modifier mapping. HDPI coordinate scaling. Uses LibGDX `HeadlessApplication`; no GL needed for these.

**GL integration, needs a GL context.** Lives in `composegl-smoke-lwjgl3` under an `integrationTest` source set. Runs on CI on Linux with Xvfb and Mesa llvmpipe; skipped elsewhere. Also runs locally on demand.

- Create context on an LWJGL3 GL 3.2 core window, render a Compose button into an FBO, read pixels back, compare against a golden PNG with a per-channel tolerance of 8.
- Draw a coloured triangle with raw GL after `render()` and state restore; assert its pixels are correct (proves the firewall).
- Resize 800×600 → 1024×768 → 0×0 → 800×600; assert no GL error and correct final render.

**Demo.** Manual. HUD with a counter button, a text field, a dropdown, an in-world panel on a rotating quad. `stats` printed in the corner so the "zero renders when static" claim is visible.

## 15. Phase 0 — spikes that gate implementation

Throwaway code in `spikes/`, deleted after findings are recorded in `docs/superpowers/spikes/`. Both run before Milestone 1.

**S1 is done and every sub-check passed — see [`docs/superpowers/spikes/s1-desktop.md`](../spikes/s1-desktop.md).**

**S1 — desktop feasibility (blocking).** LibGDX LWJGL3 app, GL 3.2 core, spinning cube, Skiko `DirectContext.makeGL()` on LibGDX's context, `CanvasLayersComposeScene` with a Material `Button` and `TextField` rendered into a LibGDX `FrameBuffer`, blitted with `SpriteBatch`. Sub-checks, each recorded pass/fail:

- a. Skiko's GL loader works on a GLFW/LWJGL context. No crash, no black texture.
- b. Which `SurfaceOrigin` yields an upright `TextureRegion` without a flip.
- c. `CanvasLayersComposeScene` can be constructed with our `PlatformContext`, dispatcher, and clock, with no AWT window and no `ComposeWindow`.
- d. Compose `KeyEvent` can be constructed on the desktop artifact without a `java.awt.event.KeyEvent`. If not: the `DesktopKeyEvents.kt` allowlist exception is activated and recorded.
- e. `scene.sendPointerEvent` runs synchronously (consumption flag readable on return).
- f. `scene.hasInvalidations()` is false for static content after the first render, true after a state change.
- g. Typing into the Material `TextField` works via our `PlatformTextInputService` and `CommitTextCommand`.
- h. GL state after render + restore leaves the cube and a `SpriteBatch` sprite correct; `glGetError` clean.
- i. Rough cost: render time for the HUD at 1080p, and steady-state memory with the 64 MB Skia cache.

Fail on a, c, or h means the offscreen approach needs rework before anything else is built; stop and redesign. Fail on d, e, f, or g means a known fallback recorded in the risks table.

**S2 — AWT dependency scan (roadmap only, not blocking).** Offline. Unpack the pinned compose-ui desktop jar and skiko-awt jar; list every class whose constant pool references `java.awt` or `javax.swing`; record whether `ComposeScene`, `PlatformContext`, `CanvasLayersComposeScene`, text input, and font loading are on that list. Output is a short markdown table. Decides whether Android/RoboVM is a repackaging job or a fork. About 30 minutes.

## 16. Milestones

- **M0** — Spikes S1 and S2 done, findings written, Compose version pinned.
- **M1 — core.** `ComposeGlContext`, `ComposeSurface`, clock, dispatcher, `PlatformContext`, text input service, consumption tracker. All headless tests green. Raster-target rendering works end to end.
- **M2 — LibGDX overlay.** `ComposeGdx`, `ComposeOverlay`, keycode table, state restore, blit, cursor, clipboard. Demo shows HUD over cube with click-through. Integration tests green on Linux CI.
- **M3 — in-world + smoke.** `ComposeTexture`; demo panel on a quad; `composegl-smoke-lwjgl3` proves the seam with no LibGDX.
- **M4 — ship.** README with requirements and the three-line usage, `stats` overlay in the demo, CI bytecode checks, Maven publication config.

## 17. Risks and open questions

| Risk | Likelihood | Mitigation |
|---|---|---|
| `ComposeScene` / `PlatformContext` are `@InternalComposeUiApi` and change between releases | High over time | Exact version pin. All opt-ins in `SceneBridge.kt`. Upgrading Compose is a deliberate task with the integration suite as the gate. |
| Skiko GL loader and LWJGL both loading GL functions in one process | Resolved | Confirmed working in S1-a on Mesa/llvmpipe. |
| macOS: GL is deprecated, capped at 4.1 core, main-thread rules | Medium for mac users | 4.1 core is enough. LWJGL's `-XstartOnFirstThread` is a LibGDX concern already. Metal later via `RenderTarget`. |
| `hasInvalidations()` reports true too often | Resolved for static content | S1-f: 0 renders over 11 static frames. A focused `TextField` blinks its caret and does redraw every frame; documented, not fixed. |
| Material `TextField` input path changes again | Medium over time | Pinned version. Both the session (`startInputMethod`) and legacy (`PlatformTextInputService`) paths are implemented, so either works. |
| Memory: one full-screen RGBA FBO per overlay plus Skia caches | Certain, small | `resourceCacheBytes` configurable; documented. ~8 MB for the FBO at 1080p plus cache. |
| Android GL context loss on resume | N/A for v1 | Noted for P3: `ComposeGlContext` must be recreatable and surfaces must re-create targets. |

## 18. Portability rules (keep the door open, cost nothing now)

1. `composegl-core` never imports `java.awt.*`, `javax.swing.*`, or any engine. CI enforces on our bytecode.
2. Cursor, clipboard, soft keyboard, and frame requests go through `HostServices`. Never AWT types.
3. Render target is a value. Core makes no GL calls.
4. No assumption of a window, a mouse, or a single surface in core.
5. Anything `@InternalComposeUiApi` is in one file.

## 19. Roadmap after P1

- **P2** — ~~Editor mode (C) on desktop~~. Done: `GameTexture`, `GameView`, `GameFrameBuffer`.
- **P3** — Android. Overlay is free via `ComposeView` over `GLSurfaceView`; in-frame and in-world need the S2 findings. Same fix unlocks P4.
- **P4** — iOS via RoboVM, GL. Requires a Skia build for iOS with GL enabled (Skiko ships Metal only), the S2 fix, and RoboVM libcore validation. No prior art. Real but expensive.
- **P5** — ~~Second JVM engine adapter~~. Done: `composegl-lwjgl3` is the raw LWJGL3 adapter. A jMonkeyEngine one would be the same shape.

Each of P2, P3 and P4 is re-assessed against the shipped code in [`docs/roadmap.md`](../../roadmap.md).
