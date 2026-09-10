# S5 — running the whole core suite on a JVM with no AWT

Date: 2026-09-08
Issue: [#25](https://github.com/wildware-uk/composegl/issues/25)
Verdict: **it runs.** All 63 core tests pass on a JVM started without the `java.desktop` module,
once fifteen stub classes stand in for the AWT that Compose and Skiko touch on the way to a scene.
There are exactly **three** such places, none of them ComposeGL's, and that list is the real size
of the Android port's upstream work.

> Part of the **Skia product**, which was abandoned on 2026-09-09. The code this refers to is at
> the tag `skia-final`. The finding below still stands; the product it was found for does not.

## Why

S2 scanned constant pools and concluded everything ComposeGL touches is AWT-free. That answers
*does our bytecode name AWT* — the right question for the CI check. It is the wrong question for
*will this run on ART*, because a class can be clean and still call something that is not.

Android and RoboVM share a runtime with no AWT. The closest thing this machine has is a JVM with
`java.desktop` left out of the module graph, where touching `java.awt` fails loudly instead of
quietly working:

```kotlin
jvmArgs("--limit-modules", "java.base,java.logging,java.management,java.instrument,java.naming,java.xml,jdk.unsupported,jdk.zipfs")
```

That is `./gradlew :composegl-core:noAwtTest`, and it is wired into `check`.

## What happened, in order

Each run failed at one place, that place got a stub, and the next one appeared. Three iterations
and it went green.

### 1. `RootNodeOwner` builds an AWT clipboard before anyone can stop it

```
java.lang.NoClassDefFoundError: java/awt/HeadlessException
  at androidx.compose.ui.node.RootNodeOwner$OwnerImpl.<init>(RootNodeOwner.skiko.kt:471)
```

```kotlin
override val clipboardManager = createPlatformClipboardManager()
override val clipboard = createPlatformClipboard()
```

Eager `val`s. The desktop actual reaches `Toolkit.getDefaultToolkit()` inside a
`catch (HeadlessException)`, and catching a type is enough to require it. So a `ComposeScene`
could not be constructed at all — long before ComposeGL supplies its own clipboard, which it does
and which was never reached.

**This one is in `skikoMain`, so it is shared, and it is the one that genuinely blocks Android.**

### 2. Skiko's main dispatcher goes through Swing

```
java.lang.NoClassDefFoundError: javax/swing/SwingUtilities
  at org.jetbrains.skiko.SwingDispatcher.dispatch(MainUIDispatcher.awt.kt:32)
```

`Dispatchers.Main` on desktop is Skiko's Swing dispatcher, and something in the scene launches on
it. The file name says `awt`, so **Skiko's Android build has its own** — an artifact of running
the desktop jar rather than a real Android blocker.

### 3. `PointerIcon`'s desktop actuals are AWT cursors

```
java.lang.NoClassDefFoundError: Could not initialize class androidx.compose.ui.input.pointer.PointerIcon
  at ...TextFieldPointerModifier_desktopKt.textFieldPointer(TextFieldPointerModifier.desktop.kt:35)
```

`pointerIconDefault` and friends wrap `java.awt.Cursor`. In `desktopMain`, so **Android needs its
own actual** — very likely a small one, since the Android platform has cursor types of its own.

### Then: green

| | |
|---|---|
| Core tests on a JVM with no `java.desktop` | **63 pass, 0 fail** |
| Stub classes needed | 15, in `composegl-core/src/awtShim` |
| Places Compose or Skiko needed them | 3 |
| Places ComposeGL needed them | **0** |

Removing any one stub turns the task red, so it is a real regression detector rather than a
decoration.

## What this is worth

**Nothing changes for v1.** Every desktop JVM has `java.desktop`. No shipped behaviour is affected
and the shim is never on the classpath of anything ComposeGL publishes.

**It resizes P3.** "Port compose-ui's `skikoMain` source set to ART, unknown difficulty" becomes a
list with one genuinely shared item on it:

1. Make `RootNodeOwner`'s clipboard lazy, or route it through `PlatformContext` like the rest of
   the platform surface. Small, specific, and proposable upstream on its own.
2. Use Skiko's Android main dispatcher rather than the AWT one. Already exists.
3. Provide an Android actual for `PointerIcon`. Small.

Everything else in the scene — recomposition, layout, Skia drawing, pointer input, key input, text
input, focus, the clipboard once ComposeGL provides it — already runs with no AWT anywhere. That
is now measured rather than assumed.

**It guards the claim.** If a future Compose version reaches for AWT somewhere new on this path,
`check` goes red and the list above is wrong. Better to learn that from a build than from a phone.

## Method note

S2's answer was right for the question it asked and wrong as a proxy for runtime. When the question
is "can this run somewhere with no AWT", run it somewhere with no AWT. Reading bytecode finds the
first hop only.
