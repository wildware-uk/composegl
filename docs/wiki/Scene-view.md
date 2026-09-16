# Scene view

`SceneView` is a panel with your game's own 3D scene inside it. Use it for a level
editor's viewport, a model spinning in an inventory slot, or a picture-in-picture view
of the world.

It lays out like any other widget. Inside, it draws whatever your renderer draws.

```kotlin
@Composable
fun WeaponPreview(renderer: MyRenderer) {
    val scene = rememberSceneViewState()

    SceneView(scene, Modifier.size(480f, 270f).clip(8f)) {
        clear(Colour.Black)
        raw { frame ->
            GL11.glEnable(GL11.GL_DEPTH_TEST)
            renderer.draw(frame as GlFrame, width, height)
        }
    }

    Button("SPIN", onClick = {
        renderer.turn(15f)
        scene.invalidate()          // draw it again, once, next frame
    })
}
```

`SceneView`, `SceneViewState`, `rememberSceneViewState` and `SceneDrawScope` are in
`dev.wildware.composegl.ui.widget`. `ScenePass` is in `dev.wildware.composegl.ui.draw`.

---

## How it works

Think of the scene view as a photo frame. Your renderer takes the photo, the frame
holds it, and the interface hangs the frame on the wall.

1. **The state owns a picture.** `SceneViewState` holds an offscreen picture with a
   depth buffer, sized to the panel's real pixels.
2. **Your block fills it, only when asked.** The block runs the first time the panel
   has a size, after each `invalidate()`, and when the panel's pixel size changes.
   Otherwise it does not run at all. A shelf of eight still previews costs no GPU work.
3. **It runs before the interface is drawn.** `UiRenderer.render` lays the tree out,
   then renders every dirty scene view, then draws the interface. So the size is always
   known, and your GL state changes never land in the middle of the interface's batch.
4. **The interface draws the picture as an image.** So `clip`, rounded corners, `alpha`
   and the shader effects all work on it, with no extra code.

A live camera feed calls `invalidate()` every frame. A still preview calls it when
something changes.

---

## Inside the block

The block is a `SceneDrawScope`:

| | |
|---|---|
| `width`, `height` | the picture's size in real pixels, not design units |
| `nanos` | the frame's time, from the clock you handed `UiRenderer.render` |
| `clear(colour)` | fills the picture with a colour and its depth buffer with the far plane |
| `raw { frame -> }` | your frontend's own drawing object, with the picture bound and the viewport covering all of it |

`raw` hands over the same object `UiCanvas.raw` does on that frontend: a `GlFrame` on
LWJGL3, a `WebGlFrame` in a browser, the `SpriteBatch` on LibGDX. Inside it, switch on
the depth test and whatever else your scene needs. The toolkit puts your engine's state
back when the block ends.

The widget interprets nothing. There is no camera, no scene graph and no picking. That
is your renderer's job.

---

## The state

```kotlin
val scene = rememberSceneViewState(resolutionScale = 0.5f)
```

| | |
|---|---|
| `invalidate()` | render once more, in the next frame |
| `resolutionScale` | how many of the panel's pixels to render. `1` is all of them, `0.5` is half each way, stretched to fit. Good for a heavy scene. |
| `dirty` | true until the next render |
| `width`, `height` | the size last rendered, in pixels. `0` before the first. |
| `draws` | how many times it has rendered |
| `texture` | the picture, or `null` before the first render |
| `release()` | give the picture back to the GPU now. The next frame makes a new one if the panel is still showing. |

`rememberSceneViewState` keeps the state across recompositions, so a recomposition
does not throw the picture away. When the state leaves the composition, it releases
the picture. Use one state per `SceneView`.

Pixel size is the panel's content box (inside its padding) times the viewport's scale,
times any `Modifier.scale` above it, times `resolutionScale`, rounded up. A GPU's
biggest texture caps it, and `width` and `height` say what you really got.

---

## Sizes and layout

`SceneView` takes the room it is given and asks for none, so give it a size:
`Modifier.size`, `weight`, `fillMaxSize` inside a `Splitter` pane, or a debug window.

A panel squeezed to nothing renders nothing and does not throw. It stays dirty, so it
renders the moment it has room again.

When the panel's size changes, the old picture is given back and a new one is made at
the new size, straight away. Right to left moves the panel to the other side like any
other widget. It does not mirror the picture: a scene is not text.

---

## Driving the prepass yourself

Most games never need this. `UiRenderer` runs the prepass for you.

If you must render scenes between passes of your own, for example after a shadow map
the preview shares, switch it off and call it yourself:

```kotlin
val ui = UiRenderer(host, canvas)
ui.renderScenes = false
ui.onLaidOut = { _ ->
    shadows.render()
    ui.scenes.render(viewport, nanos)
}
```

Two rules. Call `scenes.render` **after layout**, because the size comes from layout.
Call it **outside the canvas's frame**, because a scene binds its own picture. Inside
`onLaidOut` meets both. Called after `render` instead, every new picture shows a frame
late.

`ScenePass(tree, canvas)` is the same pass on its own, for a game that does not use
`UiRenderer` at all.

---

## Frontends

| frontend | scene views |
|---|---|
| raw OpenGL (LWJGL3) | yes |
| LibGDX | yes |
| a browser tab (WebGL) | yes |
| KorGE | not yet: a `SceneView` shows nothing. `KorgeCanvas.drawsScenes` is false. |
| your own `RenderCanvas` | yes, through `GpuDevice.offscreen(width, height, depth = true)` |

A canvas says whether it can with `UiCanvas.drawsScenes`. The one tests use,
`RecordingCanvas`, can: it writes each render down in `scenes`, with the size, the
clears and how often `raw` was asked for, so a test asserts on redraws with no GPU.

```kotlin
ui.render()
assertEquals(1, (ui.backend.canvas as RecordingCanvas).scenes.size)
```

---

## Not in it

- No camera, scene graph or picking helper.
- No multisampling yet.
- A `SceneView` inside a `WorldPanel` is not rendered yet: `WorldPanel` draws inside a
  frame and has no prepass.

See also [[Render targets]] for drawing into a picture by hand.
