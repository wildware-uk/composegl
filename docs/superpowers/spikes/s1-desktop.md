# S1 — desktop feasibility spike

Date: 2026-09-08
Issue: [#1](https://github.com/shaun-wild/composegl/issues/1)
Verdict: **all sub-checks pass. Proceed to M1.** No blocking failure (a, c, h all green).

## What was run

Throwaway LibGDX LWJGL3 app in `spikes/s1-desktop/` (now deleted). GL 3.2 core requested,
spinning `ModelBatch` cube, Skiko `DirectContext.makeGL()` on LibGDX's own context, a
`CanvasLayersComposeScene` with a Material 3 `Button` + `TextField` rendered into a LibGDX
`FrameBuffer` and blitted with `SpriteBatch`. Checks ran as a frame-numbered state machine and
printed one PASS/FAIL line each.

Environment:

| | |
|---|---|
| JDK | Temurin 25 (Kotlin toolchain 21) |
| GL_VERSION | 4.5 (Core Profile) Mesa 25.2.8 |
| GL_RENDERER | llvmpipe (LLVM 20.1.2, 256 bits) — software, under Xvfb |
| Kotlin | 2.4.20 |
| Compose Multiplatform | 1.12.0 (pulls skiko 0.150.1, material3 1.9.0) |
| LibGDX | 1.14.2 |

## Results

| Check | Result | Evidence |
|---|---|---|
| a. Skiko GL loader works on the GLFW/LWJGL context | **PASS** | `DirectContext.makeGL()` returned a context; the FBO's Compose top-left pixel read back `#FFFF0000` (the red marker), bottom-left `#FFE6E0E9` (the text field). Not a black texture. |
| b. Which `SurfaceOrigin` is upright | **PASS** | `SurfaceOrigin.TOP_LEFT` plus a plain `TextureRegion(fbo.colorBufferTexture)` with **no flip** puts the Compose top-left at the screen top-left. |
| c. `CanvasLayersComposeScene` with our own plumbing, no AWT window | **PASS** | Built with our `PlatformContext`, our dispatcher, and a `FrameRecomposer`. No `ComposeWindow`, no AWT toolkit touched. |
| d. Compose `KeyEvent` without `java.awt.event.KeyEvent` | **PASS** | `KeyEvent(key = Key.A, type = KeyEventType.KeyDown, codePoint = …)` is a public `@InternalComposeUiApi` factory in skikoMain. **The `DesktopKeyEvents.kt` allowlist exception in the spec is not needed.** |
| e. `sendPointerEvent` is synchronous | **PASS** | It now returns `PointerEventResult`. Press and release both reported consumed and `onClick` had already fired when the call returned. A press on empty space reported not-consumed. |
| f. `hasInvalidations()` tracks state | **PASS** | 11 frames of untouched static content after the first render: **0** Compose renders. One state write: 1 render. |
| g. Typing via our text input service | **PASS** | `CommitTextCommand("a", 1)` then `("b", 1)` produced `"ab"` in a Material 3 `TextField`. `Key.Backspace` as a plain Compose key event deleted a character. |
| h. GL state firewall | **PASS** | After Compose render + the reset list from spec §8: `glGetError` clean, cube still visible under the HUD, clear colour intact, `SpriteBatch` blit correct. |
| i. Cost at 1080p | **PASS** | Full HUD redraw at 1920×1080 = **0.61 ms on llvmpipe** (software). Heap after GC = 7 MB with the default Skia cache. Resize 1280×720 → 1920×1080 and a degenerate 1×1 resize both clean. |

Teardown (`scene.close()`, `FrameRecomposer.close()`, Skia `Surface`/`BackendRenderTarget`/
`DirectContext` close, LibGDX disposals) threw nothing.

## Findings that change the design

Compose Multiplatform 1.12.0's scene API is not the one the spec was written against. All the
changes are in our favour; the spec sections below need amending before M1.

### 1. `FrameRecomposer` replaces our hand-rolled clock and snapshot pumping

`androidx.compose.ui.platform.FrameRecomposer(coroutineContext, invalidate)` is now the host-side
driver, and `CanvasLayersComposeScene` takes it instead of a `coroutineContext`:

```kotlin
CanvasLayersComposeScene(
    frameRecomposer: FrameRecomposer,
    density: Density,
    layoutDirection: LayoutDirection,
    size: IntSize?,
    platformContext: PlatformContext,
    invalidateLayout: () -> Unit,
    invalidateDraw: () -> Unit,
): ComposeScene
```

It owns the `BroadcastFrameClock`, the `Recomposer`, both work queues, and it registers itself with
`GlobalSnapshotManager`. `performFrame(nanos)` does the trampoline drain and `sendFrame` in one
call; `hasPendingWork()` reports outstanding work; `close()` tears it down.

So spec §7's `update()` becomes:

```kotlin
fun update(frameTimeNanos: Long) {
    assertGlThread()
    dispatcher.drain()
    recomposer.performFrame(frameTimeNanos)   // was: sendApplyNotifications + clock.sendFrame
    dispatcher.drain()
    scene.measureAndLayout()
}
```

We still own the `CoroutineDispatcher` — `FrameRecomposer` requires a `ContinuationInterceptor` in
its context and asserts a single thread — so `GameLoopDispatcher` (issue #4) stays. The explicit
`BroadcastFrameClock` and the explicit `Snapshot.sendApplyNotifications()` call go away.

### 2. `scene.render(canvas, nanos)` is gone; it is `measureAndLayout()` + `draw(canvas)`

Layout is now a separate phase the host runs. Put `measureAndLayout()` at the end of `update()`,
and `draw()` inside `render()`, so `needsRedraw` is evaluated after layout has settled.

`hasInvalidations()` is an extension: `hasPendingMeasureOrLayout || hasPendingDraw`. Both are
public `ComposeScene` properties, so `needsRedraw` needs no internal API beyond the extension.

### 3. Consumption comes back from the scene — the tracker `Box` is not needed

`sendPointerEvent` returns `PointerEventResult`. Spec §9's wrapper `Box` with a
`PointerEventPass.Final` listener can be deleted, and with it the async fallback risk.

One wrinkle: `PointerEventResult.anyChangeConsumed` is `internal`. The public constructor
`PointerEventResult(anyMovementConsumed, anyChangeConsumed, dispatchedToAPointerInputModifier)`
is not, so read the flag by comparing against the four constructible values that have it set:

```kotlin
fun PointerEventResult.changeConsumed(): Boolean =
    listOf(false, true).any { m -> listOf(false, true).any { d ->
        this == PointerEventResult(m, true, d) } }
```

It is a value class over an `Int`; this compiles to four integer comparisons.

### 4. Text input uses the **session** path, not `PlatformTextInputService`

Material 3 1.9.0's `TextField(value, onValueChange)` went through
`PlatformContext.startInputMethod(request)` — the new `PlatformTextInputMethodRequest` path. The
legacy `PlatformContext.textInputService` getter was never called.

That path still exposes `onEditCommand: (List<EditCommand>) -> Unit`, so `sendChar` works
identically:

```kotlin
override suspend fun startInputMethod(request: PlatformTextInputMethodRequest): Nothing {
    current = request
    try { awaitCancellation() } finally { current = null }
}
fun sendChar(c: Char) = current?.let { it.onEditCommand(listOf(CommitTextCommand("$c", 1))); true } ?: false
```

Consequences for the spec: issue #7 should implement `startInputMethod` (and keep a
`PlatformTextInputService` for older content), and **spec §3's non-goal "the new
`BasicTextField(TextFieldState)` input path is not supported" is wrong — it is the path that is
actually used, and it works.** The `request` also carries `state`, `imeOptions`, `onImeAction`, and
caret/text rectangles, which is what a soft keyboard and an IME would need later.

### 5. Focus is readable without wrapping `focusManager`

`scene.focusManager.hasFocus` (`ComposeSceneFocusManager`) reported `true` while the `TextField`
held focus. `hasKeyboardFocus` (issue #9) can read it directly; no `PlatformContext.focusManager`
wrapper is needed. Note `PlatformContext` calls its member `parentFocusManager`, not `focusManager`.

### 6. `PlatformContext.Empty` is an open class

`object : PlatformContext by PlatformContext.Empty()` is unnecessary — subclass
`PlatformContext.Empty()` and override what we need (issue #6).

## Known cost, not a failure

A **focused** `TextField` blinks its caret, which invalidates draw every frame: 6 frames produced
6 Compose renders. The "zero renders when static" claim holds for a HUD with no focused text
field. Worth stating in the README and worth showing in the demo's `stats` overlay.

## Version pins chosen at M0

| Library | Pin | Note |
|---|---|---|
| Kotlin | 2.4.20 | |
| Compose Multiplatform | 1.12.0 | `FrameRecomposer` era. Do not downgrade — the older `coroutineContext` scene API needs the deleted hand-rolled clock. |
| Skiko | 0.150.1 | transitive from Compose 1.12.0; pin explicitly to match |
| Material 3 | 1.9.0 | transitive from `compose.material3` at plugin 1.12.0 |
| LibGDX | 1.14.2 | |
| JVM toolchain | 21 | |
