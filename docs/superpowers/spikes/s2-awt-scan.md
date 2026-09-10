# S2 — AWT dependency scan of compose-ui desktop and skiko-awt

Date: 2026-09-08
Issue: [#2](https://github.com/wildware-uk/composegl/issues/2)
Verdict: **Android / RoboVM is a repackaging job, not a fork.** Everything ComposeGL's core sits
on is AWT-free. The AWT is confined to the parts we already do not use.

> Part of the **Skia product**, which was abandoned on 2026-09-09. The code this refers to is at
> the tag `skia-final`. The finding below still stands; the product it was found for does not.

## Method

Unpacked the pinned jars and searched every class file's constant pool for `java/awt`,
`javax/swing`, `java/beans`, `javax/imageio`, and `javax/accessibility`. A hit means the class
names one of those types somewhere — a field, a signature, a call, or an inlined debug string.
Compile-time `static final int` constants (`KeyEvent.VK_A`) are inlined by javac and leave no
trace, so a class can use those and still read clean; that is correct for our purpose, since such
a class links and runs fine without AWT on the classpath.

Jars scanned:

- `org.jetbrains.compose.ui:ui-desktop:1.12.0`
- `org.jetbrains.compose.ui:ui-text-desktop:1.12.0`
- `org.jetbrains.skiko:skiko-awt:0.150.1`

## Headline numbers

| Jar | Classes | Referencing AWT/Swing | Share |
|---|---|---|---|
| ui-desktop 1.12.0 | 1778 | 273 (111 top-level) | 15% |
| ui-text-desktop 1.12.0 | 337 | 3 | 1% |
| skiko-awt 0.150.1 | 705 | 48 (39 top-level) | 6% |

## What ComposeGL actually touches

| Thing | AWT? |
|---|---|
| `ComposeScene` (interface) | **clean** |
| `ComposeScene.hasInvalidations()` and friends (`ComposeScene_skikoKt`) | **clean** |
| `CanvasLayersComposeScene` factory and `CanvasLayersComposeSceneImpl` | **clean** |
| `BaseComposeScene` | **clean** |
| `PlatformContext` and `PlatformContext.Empty` | **clean** |
| `FrameRecomposer` | **clean** |
| `RootNodeOwner` | **names no AWT, but needs one at runtime — see the correction below** |
| `ComposeSceneFocusManager`, `ComposeSceneInputHandler` | **clean** |
| `PlatformTextInputMethodRequest` (the input path we use) | **clean** |
| Font loading: `FontFamilyResolverImpl`, `SkiaFontLoader`, `PlatformFontLoader`, every `…font.*` adapter | **clean** |
| The whole `org.jetbrains.skia.*` package — `DirectContext`, `Surface`, `BackendRenderTarget`, `Canvas` | **clean** |
| Skiko's native loader: `org.jetbrains.skiko.Library`, `LibraryLoader`, `org.jetbrains.skia.impl.Library` | **clean** |
| `androidx.compose.ui.input.key.Key` | **AWT — one line** |
| `KeyEvent_desktopKt` (the JVM facade that holds our `KeyEvent(key, type, …)` factory) | **AWT — other half of the facade** |

### The two amber rows

`Key.toString()` is the only AWT in `Key`:

```kotlin
actual override fun toString(): String = "Key: ${java.awt.event.KeyEvent.getKeyText(nativeKeyCode)}"
```

Every `Key.A`-style constant is an inlined `VK_*` int. So `Key` links and works without AWT; only
its debug string would fail. A port replaces one method.

`KeyEvent_desktopKt` is a `@JvmMultifileClass` facade merging three source files. Our AWT-free
factory `KeyEvent(key, type, codePoint, …)` lives in the skiko half; `java.awt.event.KeyEvent
.toComposeEvent()` and `KeyShortcut` live in the desktop half and pull in `java.awt.event.InputEvent`.
A port splits that facade. Nothing in our call path executes AWT — S1-d confirmed the factory runs
with no AWT window and no toolkit.

## Correction, from S5

This scan reads constant pools, so it finds classes that *name* AWT. It does not follow a call one
hop to an implementation that does. `RootNodeOwner` is the case where that matters, and S5 caught
it by running the suite on a JVM with no `java.desktop` module:

```
java.lang.NoClassDefFoundError: java/awt/HeadlessException
  at androidx.compose.ui.node.RootNodeOwner$OwnerImpl.<init>(RootNodeOwner.skiko.kt:471)
```

Line 471 is `override val clipboardManager = createPlatformClipboardManager()`, whose desktop actual
reaches `Toolkit.getDefaultToolkit()` inside a `catch (HeadlessException)`. Catching it is enough:
the class has to resolve. So the scene cannot be *constructed* without AWT on the desktop artifact,
even though ComposeGL replaces the clipboard a moment later.

Read the table below as "does not name AWT", which is the right question for our own bytecode and
the CI check, and the wrong question for "will this run on ART". See
[`s5-no-awt-runtime.md`](s5-no-awt-runtime.md).

## Where the AWT actually lives

All of it is the AWT-hosted windowing stack — precisely the layer ComposeGL replaces.

**compose-ui desktop**, top-level classes by package:

| Package | Count | What it is |
|---|---|---|
| `androidx.compose.ui.window` (+ `.v2`) | 71 | `Window`, `Dialog`, `application {}` |
| `androidx.compose.ui.awt` (+ `.v2`) | 84 | `ComposeWindow`, `ComposePanel`, Swing interop |
| `androidx.compose.ui.scene` (+ `.skia`) | 45 | `ComposeSceneMediator`, `ComposeContainer`, `SkiaLayerComponent`, the Swing/Window scene layers |
| `androidx.compose.ui.platform` | 24 | AWT clipboard, drag and drop, `DesktopTextInputService`, `PlatformComponent` |
| `androidx.compose.ui.platform.a11y` | 16 | `javax.accessibility` bridge — a stated non-goal |
| `androidx.compose.ui.viewinterop`, `.draganddrop`, `.input.mouse`, `.util`, `.semantics` | 27 | interop and AWT event conversion |

**ui-text desktop** — only three classes, none on the resolver path:

- `AwtFontUtils`, `AwtFontInteropKt` — opt-in helpers for converting `java.awt.Font`. Nothing in
  `androidx.compose.ui.text.font.*` references them.
- `DesktopPlatformLocale_desktopKt` — locale lookup via `java.awt`. Reachable from
  `LocaleList`; a port needs a non-AWT locale source.

**skiko-awt** — `SkiaLayer`, `HardwareLayer`, `ClipComponent`, every `redrawer.*`, every `swing.*`,
`AWTKt`, `SwingDispatcher`, `PlatformOperations`. That is skiko's *own* rendering host, which we do
not use: we hand Skia a framebuffer id ourselves. The Skia bindings underneath are clean.

## What this means for P3 (Android) and P4 (iOS/RoboVM)

A port is repackaging, not a fork:

1. Build compose-ui and ui-text for the target without the `desktopMain`/`awt` source sets — the
   `skikoMain` sources we depend on are already AWT-free and already the common non-Android path.
2. Replace `Key.toString()`.
3. Split the `KeyEvent_desktopKt` multifile facade so the skiko-half factory ships alone.
4. Provide a non-AWT `LocaleList` source.
5. Use skiko's Skia bindings without `SkiaLayer` and the redrawers.

The expensive parts of P4 are unchanged and are not AWT problems: Skiko ships no GL-enabled Skia
build for iOS, and RoboVM's libcore still needs validating.

## What this means now

Nothing changes in the v1 plan. Spec §18's rule "core never imports `java.awt`" costs nothing —
every API ComposeGL uses is already on the clean side of the line.
