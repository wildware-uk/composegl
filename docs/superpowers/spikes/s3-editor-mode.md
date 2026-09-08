# S3 — editor mode: can a Compose node show a live GL texture?

Date: 2026-09-08
Issue: [#24](https://github.com/wildware-uk/composegl/issues/24)
Verdict: **yes, and the feared blocker does not bite.** Editor mode is implemented — see
`GameTexture`, `GameView` and `composegl-lwjgl3`'s `GameFrameBuffer`.

## The question

Editor mode inverts ComposeGL: instead of Compose drawing into the game's frame, the game's frame
appears inside the Compose UI. Skiko can wrap a GL texture:

```kotlin
val backend = BackendTexture.makeGL(width, height, isMipmapped = false, textureId, GL_TEXTURE_2D, GL_RGBA8)
val image = Image.adoptTextureFrom(directContext, backend, origin, ColorType.RGBA_8888)
```

Two things looked like they might stop it.

1. **`adopt`, not `borrow`.** Skia takes ownership of the texture and deletes it. Skiko does not
   expose `BorrowTextureFrom`, so either two things believe they own one texture, or one of them
   gives it up.
2. **`SkImage` is immutable by contract.** A game viewport is a texture whose contents change
   every frame. If Skia treats the image as a snapshot, an editor viewport freezes on frame one.

## What was run

Throwaway test in `composegl-smoke-lwjgl3` (deleted after this was written). A GL 3.2 core context,
a framebuffer of the test's own with a colour texture, a Compose scene whose only content was a
node drawing that texture through `drawIntoCanvas`. The test painted the texture red, rendered,
sampled; painted it green, rendered with **the same adopted `Image`**, sampled; then painted blue
and rendered with a freshly adopted image.

## Results

| | |
|---|---|
| Frame 1, texture painted red | `#FFFF0000` |
| Frame 2, texture repainted green, **same cached `Image`** | `#FF00FF00` |
| Frame 3, texture repainted blue, image adopted fresh | `#FF0000FF` |
| `glGetError` throughout | 0 |
| Disposing the `ComposeGlContext` with an adopted texture outstanding | no crash |

**Question 2 is answered: the image tracks the live texture.** Skia's immutability contract does
not turn into caching on the GL backend — the image is a handle to the texture, and drawing it
samples whatever is in the texture now. So there is no need to re-adopt per frame, which is what
would have made this expensive.

**Question 1 is answered by design rather than by discovery.** Adoption is real, so ComposeGL owns
the texture from then on: `GameTexture.close()` releases the Skia image, which deletes the GL
texture, and the helper that created it deliberately never calls `glDeleteTextures`. The rule is
one line in the docs and one comment in the code.

## The thing the spike did not predict

Making it *work* took one more piece that no API doc would have hinted at.

Compose has no idea a GL texture changed. A static Compose tree does not redraw — that is the
whole point of the library — so the first real integration test showed the game's opening frame
forever, correctly and uselessly. `GameTexture.invalidate()` bumps a snapshot state that
`GameView` reads while drawing, so a new game frame invalidates that node and nothing else.
`GameFrameBuffer.unbind()` calls it, so games get it without thinking about it.

That is worth remembering as a general shape: anything outside Compose that changes what a node
should draw has to say so.
