# Render targets

A render target is a picture the toolkit draws into instead of the window: a terminal
on a wall, the screen on a gun, or a 3D scene shown inside a panel. The same tree and
the same canvas draw into it. Only where the pixels land is different.

What comes out is **premultiplied**. Draw it on a quad with `ONE, ONE_MINUS_SRC_ALPHA`
for a screen, or `ONE, ONE` for a hologram. No shader of your own is needed.

| frontend | class | made with |
|---|---|---|
| raw OpenGL (LWJGL3) | `GlRenderTarget` | `GlRenderTarget(512, 256)` |
| LibGDX | `GdxRenderTarget` | `GdxRenderTarget(512, 256)` |
| a browser tab (WebGL) | `WebGlRenderTarget` | `WebGlRenderTarget(gl, 512, 256)` |
| KorGE | `KorgeRenderTarget` | see [[KorGE]] |
| your own backend | `RenderTarget` | `RenderTarget(device, 512, 256)` |

```kotlin
val target = GlRenderTarget(512, 256)

// In the game loop: redraw only when the panel changed.
if (panel.needsRedraw(now)) target.draw(canvas) { panel.draw(canvas) }
scene.drawQuad(target.texture)
```

To show a 3D scene inside a panel of your interface, you usually want [[Scene view]]
instead: it makes the target, sizes it to the panel, and redraws it only when asked.

`draw` clears the picture, runs your block in a frame of the canvas, and puts back the
framebuffer and viewport it found. So you can call it in the middle of your own scene.

`resize(width, height)` gives the old picture back to the GPU at once, so a panel that
follows the window's size does not leak one per frame. `close()` (or `dispose()` on
LibGDX) gives it all back.

---

## Depth: drawing a 3D scene into one

Out of the box a render target has colour and nothing else. That is all the interface
needs. But a 3D scene drawn into it comes out inside-out: the back of a model paints
over its front, because there is nothing to test depth against.

Ask for a depth buffer with `depth = true`:

```kotlin
val preview = GlRenderTarget(480, 270, depth = true)

preview.draw(canvas, clear = Colour.Black) {
    canvas.raw { frame ->
        GL11.glEnable(GL11.GL_DEPTH_TEST)
        myRenderer.draw(frame as GlFrame, preview.width, preview.height)
        GL11.glDisable(GL11.GL_DEPTH_TEST)
    }
}
```

`GdxRenderTarget(480, 270, depth = true)` and `WebGlRenderTarget(gl, 480, 270, depth = true)`
work the same way.

What you get:

- **A depth buffer the picture's own size.** It is made with the picture, remade when
  the picture is resized, and given back when it is closed.
- **It is cleared with the colour.** Every `draw` clears depth to the far plane at the
  same moment it clears the colour, so each frame starts from nothing in both.
- **The depth test is yours.** The toolkit's own drawing never uses depth, so it
  leaves the test off. Switch it on inside `raw { }` for your scene, as above.

Clearing depth only works while depth writing is on, so the toolkit switches it on
first. With `HostState.Leave`, the default on LWJGL3, LibGDX and WebGL, it is left on
afterwards, which is OpenGL's own default. With `HostState.Restore` it goes back to
what your engine had.

On KorGE, `KorgeRenderTarget` already has a depth and stencil buffer, because every
KorGE framebuffer does, and its `draw` clears them with the colour.

---

## Very big sizes

A GPU has a biggest texture it will make, often 4096 or 8192 pixels a side. A panel
on a large screen can ask for more than that. Rather than fail in the middle of a
frame, `GlRenderTarget`, `GdxRenderTarget`, `WebGlRenderTarget` and `RenderTarget` cut
the size down to the biggest the GPU allows, and say so:

```kotlin
val target = GlRenderTarget(20_000, 300)
if (target.clamped) println("drawing at ${target.width}x${target.height} instead")
```

`width` and `height` are always the size you really got. Asking again for a size that
cuts down to the same one costs nothing.

---

## In your own backend

The frontends above are thin wrappers over `RenderTarget` in `composegl-render`, which
works against any `GpuDevice`:

```kotlin
val target = RenderTarget(device, 480, 270, depth = true)
target.draw(canvas, Colour.Black) { /* ... */ }
val pixels = target.read()   // premultiplied RGBA, bottom row first
target.close()
```

Underneath, it calls `GpuDevice.offscreen(width, height, depth)`. On OpenGL that is
`GlDevice`, which adds a renderbuffer as the depth attachment. A `Gl` binding needs
the five renderbuffer calls (`createRenderbuffer`, `bindRenderbuffer`,
`deleteRenderbuffer`, `renderbufferStorage`, `framebufferRenderbuffer`) plus
`depthMask` and `clearDepth`, each a one-liner onto your GL. See [[Backends]].

A framebuffer your engine made itself can be drawn into with
`GlDeviceTarget.adopt(framebuffer, texture, width, height, depth = true)`. Say
`depth = true` only if it really has a depth attachment. The toolkit then clears it
with the colour, and never deletes it.
