# S5 — running the core suite on a JVM with no AWT

Date: 2026-09-08
Issue: [#25](https://github.com/wildware-uk/composegl/issues/25)
Verdict: **ComposeGL's own code is fine. Compose's `RootNodeOwner` is not, and that is now the
nearest blocker on the Android port.**

## Why

S2 scanned constant pools and concluded that everything ComposeGL touches is AWT-free. That
answers "does our bytecode name AWT", which is the right question for the CI check. It is the
wrong question for "will this run on ART", because a class can be clean and still call something
that is not.

Android and RoboVM share a runtime with no AWT in it. The closest thing this machine has is a JVM
started with the `java.desktop` module left out of the graph, where any attempt to touch
`java.awt` fails loudly instead of quietly working:

```kotlin
jvmArgs("--limit-modules", "java.base,java.logging,java.management,java.instrument,java.naming,java.xml,jdk.unsupported,jdk.zipfs")
```

That is the `noAwtTest` task in `composegl-core`.

## Result

| | |
|---|---|
| Tests that never build a `ComposeScene` (dispatcher, argument checks) | **11 pass** |
| Every test that builds a `ComposeScene` | **fails, all at the same line** |

```
java.lang.NoClassDefFoundError: java/awt/HeadlessException
  at androidx.compose.ui.node.RootNodeOwner$OwnerImpl.<init>(RootNodeOwner.skiko.kt:471)
  at androidx.compose.ui.scene.CanvasLayersComposeSceneImpl.<init>(CanvasLayersComposeScene.skiko.kt:115)
```

`RootNodeOwner.skiko.kt:471` is:

```kotlin
override val clipboardManager = createPlatformClipboardManager()
override val clipboard = createPlatformClipboard()
```

Both are eager `val`s, and the desktop actuals reach `Toolkit.getDefaultToolkit()` inside a
`catch (HeadlessException)`. Catching an exception type is enough to require it: the class has to
resolve when the constructor runs.

So **the scene cannot be constructed at all without AWT on the desktop artifact** — before
ComposeGL gets anywhere near providing its own clipboard, which it does a moment later and which
is then never reached.

## What this changes

**Nothing in v1.** Every desktop JVM has `java.desktop`. Nothing here is a bug for the platform
ComposeGL supports today, and no shipped behaviour changes.

**It sharpens P3.** The Android port's list of upstream fixes gains a concrete, small item: make
`RootNodeOwner`'s clipboard lazy, or route it through `PlatformContext` the way the rest of the
platform surface goes. That is a smaller and more specific ask than "port the source set", and it
is the kind of change that can be proposed upstream on its own.

**It leaves a detector behind.** `./gradlew :composegl-core:noAwtTest` is not wired into `check`,
because it fails. It is pointed at somebody else's code on purpose: the day that line becomes lazy,
the task goes green, and whoever is looking at #25 next learns it from a build rather than from
reading release notes.

## Method note, for the next scan

S2's answer was right for the question it asked and wrong as a proxy for runtime. When the question
is "can this run somewhere with no AWT", run it somewhere with no AWT. Reading bytecode finds the
first hop only.
