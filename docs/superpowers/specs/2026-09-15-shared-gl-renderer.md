# One shared renderer; backends become thin wrappers

Status: **step 1 built** — `composegl-render` exists and `composegl-lwjgl3` draws with it (steps 1, 2a
and 2b of §8 landed together). **gdx built** (step 3): `composegl-gdx` draws with it too.
**korge built** (step 5): `composegl-korge` draws with it through `KorgeKmlGl` and `HostState.Restore`,
3,934 lines down to 1,966. **webgl built** (step 4): `composegl-webgl` draws with it through `WebGl`,
3,525 lines down to 2,009. Every backend is now a thin wrapper. Supersedes the "engine-agnostic toolkit, engine-specific renderer" row of
`2026-09-09-runtime-ui-design.md` §4, and the "shares no code with the LibGDX backend" promise in
`docs/wiki/Backends.md` and `GlShapeBatch`.

## Corrections from building step 1

What the code does where this design said otherwise. The code is the reference.

- **The vertex is 31 floats, not 33.** The attributes add up to 31 (3 + 4 + 4 + 4 + 2 + 2 + 2 + 3 +
  4 + 3). The first port said 33 and drew nothing; a common test now pins the sum.
- **`Gl` is 68 members, not about 55.** It also has `isEnabled`, `getIntegers`, `pixelStorei`,
  `uniform1i/1f/2f/3f/4f`, both `bufferData` overloads and the three storage factories. lwjgl3's
  binding, `LwjglGl.kt` (not `Lwjgl3Gl.kt`), is 160 lines of one-liners.
- **`GpuDevice` is 21 members** and differs in shape from §2.2: `prepare`/`prepared` for `warmUp`;
  `noScissor()` rather than a nullable box; `target(target, x, y, width, height)`;
  `write(texture, x, y, width, height, source, sourceWidth)` takes the whole page and a rectangle;
  `drawEffect(effect, picture, EffectQuad, blend)` with the quad already in clip space; `close()`.
  There is **no `originBottomLeft`**: the canvas always hands a device bottom-left pixels and a
  top-left device converts, since only it knows its target's height. `FrameTarget` is `Host` (what
  the engine has bound) or a `DeviceTarget`; a game's own framebuffer is adopted with
  `GlDeviceTarget.adopt`, not described by a `Bound(width, height, isTexture)`.
- **A fifth dialect.** A GL 3.0 or 3.1 forward-compatible context compiles `#version 130`. A context
  counts as core when it has the core profile bit *or* the forward-compatible flag, which is what
  `MESA_GL_VERSION_OVERRIDE=3.2FC` gives, so `testGl30` really runs the `#version 150` path.
- **Steps 2a and 2b went together.** `StbFonts` is an `AtlasFonts` with an stb rasteriser from the
  start. It makes every registered glyph up front on one page (`maxPages = 1`), exactly like the old
  baked atlas, so no golden and no draw count changed. `StbTextLayout` is now a type alias.
- **A premultiplied picture drawn with `image()` is straightened in the shader.** That includes a
  layer's picture handed to `image()`, which old lwjgl3 drew as if it were straight.
- **`RenderTarget.read()` runs a device frame** and so leaves the `Leave` end state behind it.
- **Not in step 1:** the `testGles3` run in §8's matrix. lwjgl3's window and binding are desktop GL
  only; an ES run needs a GLES binding, and belongs with the first ES backend.
- **gdx (step 3).** `src/main` went from 4,119 lines to 1,629. `GdxGl` reports version 2 whenever
  `Gdx.gl30` is null, so the GL 2 / ES 2 path never asks for a vertex array object it has no call
  for; on `testGl30` it compiles `#version 150`. `GdxFonts` keeps LibGDX's fixed page size (1,024,
  up to 8 pages) and makes a font's `characters` when it is registered, so draw counts did not
  change. Its `FreeTypeRasteriser` loads glyphs with LibGDX's hinting flags, gamma and whole-pixel
  metrics. Ten goldens whose scenes have text were re-made once for the lost kerning. Context loss
  is noticed in `begin` on Android, where LibGDX makes a new `GLVersion` with each new context,
  rather than by a helper in `composegl-android`, which has no renderer dependency. As §8 asked,
  its text-free scenes are also held to lwjgl3's goldens (added in a follow-up check). Render gained
  `BoundPicture.rotated`, an open `lend` round `raw` blocks, and text drawn by a canvas made
  without fonts.
- **korge (step 5).** `AtlasFonts` gained `LineBreaking.Paragraph` (ui's `paragraph()`, which KorGE
  already used), `wholePixelWidths` and `smoothPages = false`, so no KorGE golden or text test moved.
  `RenderCanvas.begin(…, topRowFirst = true)` turns the viewport, scissor and projection over for a
  KorGE render texture, which KorGE stores top row first; there is still no device-level origin. A
  canvas can be made on a bare `GlyphAtlas` (`KorgeCanvas(fonts.atlas)`), draws text measured onto
  pages it was not given, and gives its device's atlas textures back on `close()`. KorGE's game
  textures are adopted by the GL name KorGE binds, with a bind hook that binds through
  `AGOpengl.textureBind`. A KorGE atlas is 1,024-pixel pages that do not grow, up to 16.
- **webgl (step 4).** `src/wasmJsMain` went from 3,525 lines to 2,009 (the estimate was 1,820);
  `WebGl.kt` is 267 lines. What building it changed:
  - The index buffer is uploaded through `ELEMENT_ARRAY_BUFFER`, with the device's own vertex array
    bound first where there are any: WebGL refuses a buffer that was ever bound to `ARRAY_BUFFER`
    as an index buffer.
  - The shape shader's fragment header asks for `highp` where `GL_FRAGMENT_PRECISION_HIGH` exists
    (`GlslDialect.fragment(source, highPrecision = true)`), as the old WebGL batch did. Effects stay
    `mediump`. So §10's `highp` follow-up is done for the shape shader, with no golden changed.
  - The binding does not write a float at a time into a `Float32Array`. `GlFloats`, `GlShorts` and
    `GlBytes` are Kotlin arrays, and an upload copies them into Wasm memory once and hands WebGL a
    view of it: no JS call per number. A showcase frame is no slower than before (see the commit).
  - WebGL objects live in one page-wide table; each object carries its own handle, which is how
    `getParameter(FRAMEBUFFER_BINDING)` comes back as a number.
  - `AtlasFonts.fallBackTo` is open: `WebFonts` hands its fallbacks to the browser as a CSS font list
    rather than to the shared chain, so a fallback need not be registered at every size.
  - Text edges are slightly fuller than the old backend's, whose atlas darkened partly covered
    pixels; the shared atlas is straight white with coverage in alpha, like stb's. Every golden
    still passes unchanged.
  - The forced WebGL 1 run is `wasmJsBrowserWebGl1Test` (Chrome's `--disable-webgl2`), not
    `wasmJsBrowserTestWebGl1`.
- lwjgl3 `src/main` went from 4,574 lines to 1,782 (the estimate was 1,680). `composegl-render` is
  3,949 lines, plus 1,706 of common tests (93 tests on jvm, linuxX64 and wasmJs) using a recording
  `GpuDevice` and a recording `Gl`.

## The answer in one paragraph

The library draws the interface itself. A new published module, `composegl-render`, holds one
renderer: the canvas, the batch, the shape maths, layers, render targets, shader effects, the glyph
atlas, fallback fonts and emoji, and draw-call tracing. It talks to the GPU through one small
**device** seam. There is one device today, the OpenGL one, which covers GL 2.1, GL 3.2+ core,
OpenGL ES 2/3, WebGL 1 and WebGL 2. The OpenGL device calls a plain Kotlin `Gl` interface of about 55
functions. Each backend then supplies four small pieces: that `Gl` binding (mostly one-liners), a
glyph rasteriser, a way to turn its own texture type into something the renderer can bind, and the
engine handoff (`raw`, state restore). Each backend drops from 3,500–4,600 lines to about
1,500–1,800, and only 500–650 of those are about drawing. The rest is input, clipboard and haptics,
which stay where they are.

Think of it like a printer driver. Today each backend is a whole word processor. After this there is
one word processor, and each backend is a driver that knows how to talk to one printer.

---

## 1. Where it lives

### One new module: `composegl-render`

| | |
|---|---|
| Targets | Same as `composegl-ui`: jvm, linuxX64, iosArm64, iosSimulatorArm64, wasmJs. All code in `commonMain`. |
| Depends on | `api(project(":composegl-ui"))` and nothing else. |
| Packages | `dev.wildware.composegl.render`: device-neutral (canvas, batch, layers, fonts, atlas, tracing). `dev.wildware.composegl.render.gl`: the OpenGL device, `Gl` interface, GLSL sources, dialects. |
| Published | Yes. It is the renderer every backend uses. |
| Central quota | About +21 files per release (322 / 15 modules on average), so about 343 per release. Still about 3 releases a month. |

**Why not inside `composegl-ui`?** It would pass the dependency check, because `Gl` is our own
interface. But `composegl-ui` promises "no OpenGL". A headless test or a server-side layout user
should not carry a renderer. A separate module also makes the device boundary something a build
check can enforce.

**Why not two modules (`render` + `gl`)?** It would cost another ~21 files per release and buy
nothing that a package-level bytecode check cannot give.

### Dependency rules (checked, not remembered)

| Module | Rule | Check |
|---|---|---|
| `composegl-ui` | Unchanged. No engine, no GL. | Existing `checkDependencyConfinement`, `checkNoEngineTypes`. |
| `composegl-render` | Allowed list = `composegl-ui`'s list + itself. No `org/lwjgl`, `com/badlogic`, `korlibs`, `org/khronos`, `java/awt`. | New `checkDependencyConfinement` and `checkNoEngineTypes` in its build file. |
| `composegl-render` (device-neutral package) | Classes under `render/` except `render/gl/` must not reference `render/gl/`. | Extend `BytecodeReferenceCheck` with an `include`/`exclude` class-path pattern (about 10 lines). |
| Backends | Depend on `composegl-render`. No shader text, no batch, no draw calls outside the `Gl` binding file. | New `RendererConfinementCheck` (§8). |
| `composegl-effects` | Unchanged. It still only needs `composegl-ui`. | Existing. |

---

## 2. The seams

Top to bottom:

```
UiCanvas (composegl-ui)                      what widgets call
  RenderCanvas         render/               clip/alpha/tint/blend stacks, y flip, layers, text placement
    QuadBatch          render/               vertex writing, texture/blend/clip batching, trace reasons
      GpuDevice        render/  <-- the device seam
        GlDevice       render/gl/            programs, VAOs, FBOs, dialects, state save/restore
          Gl           render/gl/  <-- what a backend implements
            LWJGL / Gdx.gl / KmlGl / WebGLRenderingContext
```

### 2.1 What is device-neutral and what is GL

| Piece | Where | Notes |
|---|---|---|
| Canvas state (clip, alpha, tint, blend stacks) | neutral | Already `CanvasState` in ui. |
| Y flip, design-to-pixel, letterbox, scissor boxes | neutral | Device reports whether its framebuffer origin is bottom-left (GL) or top-left (Vulkan/Metal/DX). |
| Shape tessellation: rounded box quad, fan to quads, turned quad, four-corner quad, projected (x, y, w) quad, `featherOutline` cut | neutral | Straight from `GlShapeBatch`. |
| Vertex layout: 33 floats, attribute names and meaning | neutral | `ShapeVertex`. Every device must consume it. |
| Batching and flush reasons (`Texture`, `Blend`, `Clip`, `Layer`, `Shader`, `Raw`, `Full`, `End`), `drawCalls`, `DrawCallTrace` | neutral | One copy, so traces match across backends. |
| Layer pool (reuse by size, 60 idle frames, 4096 cap), render-target bookkeeping | neutral | From `GlLayers`. |
| Glyph atlas packing, pages, white block, dirty rectangles, fallback chains, emoji pictures, line wrap, ellipsis, metrics | neutral | §5. |
| Blend modes | neutral enum: `SourceOver` / `Additive` × straight / premultiplied | Device maps to factors. |
| The shape shader, the effect vertex shader, the effect preamble | GL | GLSL, one source with dialect headers (§2.4). |
| Programs, attribute binding, uniforms, VAOs, buffers, FBOs, texture objects | GL | `GlDevice`. |
| Engine state save/restore | GL | `HostState` (§3). |

### 2.2 `GpuDevice` (the seam a future Vulkan/Metal/DirectX device implements)

Small on purpose. Only what the renderer really does.

```kotlin
interface GpuDevice {
    val limits: DeviceLimits                     // maxTextureSize, offscreen: Boolean, originBottomLeft: Boolean

    fun begin(into: FrameTarget)                 // takes the engine's state (see HostState)
    fun end()                                    // gives it back
    fun suspend()                                // around raw { }: engine state back, block runs
    fun resume()                                 //   then ours again

    fun texture(width: Int, height: Int, smooth: Boolean): DeviceTexture
    fun write(texture: DeviceTexture, x: Int, y: Int, width: Int, height: Int, rgba: ByteArray, offset: Int)
    fun offscreen(width: Int, height: Int): DeviceTarget     // colour texture inside
    fun delete(resource: DeviceResource)

    fun target(target: FrameTarget, viewport: IntBox)
    fun scissor(box: IntBox?)                    // null = off
    fun clear()                                  // transparent black

    fun vertices(quads: Int): VertexStream       // storage the device owns (see 2.3)
    fun drawShapes(vertices: VertexStream, quads: Int, texture: DeviceTexture, blend: Blend, projection: FloatArray)
    fun drawEffect(effect: ShaderEffect, picture: DeviceTexture, corners: FloatArray, inputs: EffectInputs, blend: Blend)

    fun read(box: IntBox, into: ByteArray)       // tests and render-target read-back
    fun contextLost()                            // forget every object; rebuild on next use
}
```

`FrameTarget` is either "whatever the engine has bound" (with its pixel size and whether it is a
texture) or one of our `DeviceTarget`s. `DeviceTexture` has a width and a height, and batching
compares it by identity.

### 2.3 The `Gl` interface (what a backend implements)

This comes from the calls the four renderers make today (lwjgl3 `GL11`–`GL30`, gdx `Gdx.gl` plus
`Mesh`/`ShaderProgram`/`FrameBuffer` internals, webgl `WebGLRenderingContext`, korge `KmlGl` plus
`AG.draw`), reduced to one set. Handles are `Int`, and 0 means none.

```kotlin
interface Gl {
    val profile: GlProfile            // api = Desktop | Es | WebGl; major; minor; core: Boolean
    fun hasExtension(name: String): Boolean

    // Memory the binding allocates, so each platform picks storage it can upload without a copy.
    fun floats(capacity: Int): GlFloats   // operator set(index, value)
    fun shorts(capacity: Int): GlShorts
    fun bytes(capacity: Int): GlBytes     // set(index, byte); fill(from: ByteArray, offset, count)

    // state
    fun enable(cap: Int);  fun disable(cap: Int);  fun isEnabled(cap: Int): Boolean
    fun blendFuncSeparate(srcRgb: Int, dstRgb: Int, srcAlpha: Int, dstAlpha: Int)
    fun blendEquationSeparate(rgb: Int, alpha: Int)
    fun colorMask(r: Boolean, g: Boolean, b: Boolean, a: Boolean)
    fun viewport(x: Int, y: Int, width: Int, height: Int)
    fun scissor(x: Int, y: Int, width: Int, height: Int)
    fun clearColor(r: Float, g: Float, b: Float, a: Float);  fun clear(mask: Int)
    fun getInteger(name: Int): Int
    fun getIntegers(name: Int, into: IntArray)          // VIEWPORT, SCISSOR_BOX, COLOR_WRITEMASK
    fun pixelStorei(name: Int, value: Int)

    // shaders
    fun createShader(type: Int): Int;  fun shaderSource(shader: Int, source: String)
    fun compileShader(shader: Int);  fun shaderCompiled(shader: Int): Boolean
    fun shaderInfoLog(shader: Int): String;  fun deleteShader(shader: Int)
    fun createProgram(): Int;  fun attachShader(program: Int, shader: Int)
    fun bindAttribLocation(program: Int, index: Int, name: String)
    fun linkProgram(program: Int);  fun programLinked(program: Int): Boolean
    fun programInfoLog(program: Int): String;  fun deleteProgram(program: Int)
    fun useProgram(program: Int)
    fun getUniformLocation(program: Int, name: String): Int   // -1 when absent
    fun uniform1i(at: Int, v: Int);  fun uniform1f(at: Int, v: Float)
    fun uniform2f(at: Int, x: Float, y: Float);  fun uniform3f(at: Int, x: Float, y: Float, z: Float)
    fun uniform4f(at: Int, x: Float, y: Float, z: Float, w: Float)
    fun uniformMatrix4fv(at: Int, matrix: FloatArray)

    // buffers and vertex arrays
    fun createBuffer(): Int;  fun bindBuffer(target: Int, buffer: Int);  fun deleteBuffer(buffer: Int)
    fun bufferData(target: Int, data: GlFloats, count: Int, usage: Int)
    fun bufferData(target: Int, data: GlShorts, count: Int, usage: Int)
    fun enableVertexAttribArray(index: Int);  fun disableVertexAttribArray(index: Int)
    fun vertexAttribPointer(index: Int, size: Int, type: Int, normalized: Boolean, stride: Int, offset: Int)
    fun createVertexArray(): Int;  fun bindVertexArray(array: Int);  fun deleteVertexArray(array: Int)
    fun drawElements(mode: Int, count: Int, type: Int, offset: Int)

    // textures
    fun createTexture(): Int;  fun bindTexture(target: Int, texture: Int);  fun deleteTexture(texture: Int)
    fun activeTexture(unit: Int)
    fun texParameteri(target: Int, name: Int, value: Int)
    fun texImage2D(target: Int, level: Int, internal: Int, width: Int, height: Int, format: Int, type: Int, pixels: GlBytes?)
    fun texSubImage2D(target: Int, level: Int, x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, pixels: GlBytes)

    // framebuffers
    fun createFramebuffer(): Int;  fun bindFramebuffer(target: Int, framebuffer: Int);  fun deleteFramebuffer(framebuffer: Int)
    fun framebufferTexture2D(target: Int, attachment: Int, textarget: Int, texture: Int, level: Int)
    fun checkFramebufferStatus(target: Int): Int
    fun readPixels(x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, into: GlBytes)
}
```

What changed from today's calls:

- **Dropped.** `glDrawArrays` and `TRIANGLE_FAN`: the effect quad uses the shared quad index buffer.
  `glGenTextures`-style array calls become single-object calls.
- **Added.** `texSubImage2D`: glyphs are uploaded as small dirty rectangles instead of whole pages.
  WebFonts re-uploads the whole page today. `blendEquationSeparate` and `colorMask`: an engine can
  leave either set another way, and KorGE does.
- **Moved into the binding.** WebGL's `UNPACK_PREMULTIPLY_ALPHA_WEBGL = false` and
  `UNPACK_COLORSPACE_CONVERSION_WEBGL = NONE` are set once, where the context is made. KorGE's
  non-ASCII blanking in `shaderSource` stays too: its JVM binding passes a character count as the byte
  length.

**Constants** live in `render/gl/GlConst.kt`. GL, ES and WebGL share the numbers:
`TRIANGLES UNSIGNED_SHORT UNSIGNED_BYTE FLOAT BLEND SCISSOR_TEST DEPTH_TEST CULL_FACE STENCIL_TEST ZERO
ONE SRC_ALPHA ONE_MINUS_SRC_ALPHA FUNC_ADD COLOR_BUFFER_BIT ARRAY_BUFFER ELEMENT_ARRAY_BUFFER
STATIC_DRAW STREAM_DRAW VERTEX_SHADER FRAGMENT_SHADER TEXTURE_2D TEXTURE0 TEXTURE_MIN_FILTER
TEXTURE_MAG_FILTER TEXTURE_WRAP_S TEXTURE_WRAP_T LINEAR NEAREST CLAMP_TO_EDGE RGBA RGBA8
UNPACK_ALIGNMENT PACK_ALIGNMENT FRAMEBUFFER COLOR_ATTACHMENT0 FRAMEBUFFER_COMPLETE FRAMEBUFFER_BINDING
VIEWPORT SCISSOR_BOX CURRENT_PROGRAM ARRAY_BUFFER_BINDING ELEMENT_ARRAY_BUFFER_BINDING
VERTEX_ARRAY_BINDING ACTIVE_TEXTURE TEXTURE_BINDING_2D BLEND_SRC_RGB BLEND_DST_RGB BLEND_SRC_ALPHA
BLEND_DST_ALPHA BLEND_EQUATION_RGB BLEND_EQUATION_ALPHA COLOR_WRITEMASK MAX_TEXTURE_SIZE`.
One trap: an offscreen texture's internal format is `RGBA8` on desktop GL, ES 3 and WebGL 2, but plain
`RGBA` on ES 2 and WebGL 1. `GlDevice` picks by `profile`.

### 2.4 How each backend implements `Gl`, and what each call copies

| Backend | Handles | `GlFloats` storage | Vertex write | Upload at flush | Byte uploads (atlas, pictures) | Matrix |
|---|---|---|---|---|---|---|
| lwjgl3 | GL names | direct `FloatBuffer` (`BufferUtils`) | absolute `put(i, v)`, a JIT intrinsic | `glBufferData(target, buffer.limit(count))`. **No copy** (today: one `float[]` to buffer copy per flush) | copy the dirty rectangle into a cached direct `ByteBuffer` | `float[]` straight through |
| gdx (desktop, Android, RoboVM iOS) | GL names | `BufferUtils.newFloatBuffer`: direct, native order, which Android's JNI requires | same | `Gdx.gl.glBufferData(target, count*4, buffer, usage)`. **No copy** (today `Mesh.setVertices` copies) | same as lwjgl3 | `glUniformMatrix4fv(at, 1, false, float[], 0)` |
| korge | `KmlGl` ints | `korlibs.memory.Buffer(direct = true)` | `setF32LE` | `KmlGl.bufferData(target, size, buffer, usage)`. **No copy** | `Buffer` copy of the rectangle | copy 16 floats into a cached `Buffer` |
| webgl (Kotlin/Wasm) | `JsArray` tables: `Int` to `WebGLBuffer`/`WebGLTexture`/`WebGLProgram`/`WebGLFramebuffer`/VAO; uniform locations in a per-program table | `Float32Array` | one JS interop call per float, **the same cost as today** (`WebGlShapeBatch` already writes into a `Float32Array`) | `bufferData(target, array.subarray(0, count), usage)`. **No copy** | per-byte interop into a `Uint8Array`. Small, because only the dirty glyph rectangles go up | copy 16 floats into a cached `Float32Array(16)` |
| (future Native) | GL names | `FloatArray` pinned with `usePinned` at upload | array store | pointer, no copy | pinned `ByteArray` | pinned |

The one measurable cost is WebGL's per-float interop, which is the same as today. If profiling ever
shows it, write the vertices into Wasm linear memory and hand WebGL a `Float32Array` view. That is a
change inside the WebGL binding only.

### 2.5 GL versions, extensions and shader dialects

**Capabilities `GlDevice` reads once**, from `profile` and `hasExtension`:

| Need | GL 2.1 desktop | GL 3.2+ core | ES 2 / WebGL 1 | ES 3 / WebGL 2 |
|---|---|---|---|---|
| Vertex array object | use if `GL_ARB_vertex_array_object`, else none | **required** (core draws nothing without one) | use if `OES_vertex_array_object`, else none | core |
| Framebuffers (layers) | `GL_ARB_framebuffer_object` or `GL_EXT_framebuffer_object`, else `drawsLayers = false` | core | core | core |
| 16-bit indices | yes (2048 quads × 4 = 8,192 vertices, under 65,536) | yes | yes | yes |
| Non-power-of-two textures | yes | yes | yes with `CLAMP_TO_EDGE` and no mipmaps, which is already what we do | yes |
| Instancing | **not used.** Quads are batched on the CPU; instancing would need a second vertex format for no gain at interface sizes. | | | |

With a VAO, the device keeps **its own** VAO bound only while it draws. That keeps the engine's
attribute state untouched, which is what #188 fixed for gdx and lwjgl3. Without one, it disables the
attributes it enabled after each draw (today's GL 2 behaviour).

**One GLSL source per shader**, written as GLSL ES 1.00 (`attribute`, `varying`, `texture2D`,
`gl_FragColor`), which is what all four backends and the public `ShaderEffect` contract use today.
`GlDevice` adds a header and rewrites a few tokens for the context it is on:

| Context | Header | Token rewrites (in Kotlin, before compiling) |
|---|---|---|
| GL 2.1, and GL 3.x/4.x **compatibility** profiles (Mesa and NVIDIA KorGE contexts today) | none (GLSL 1.10), exactly as today | none |
| GL 3.2+ **core** (macOS, `MESA_GL_VERSION_OVERRIDE=3.2FC`) | `#version 150` | vertex: `attribute`→`in`, `varying`→`out`; fragment: `varying`→`in`, `texture2D`→`texture`, `gl_FragColor`→`cg_FragColor` plus `out vec4 cg_FragColor;` |
| ES 2, WebGL 1 | `#version 100` + `precision mediump float;` | none |
| ES 3, WebGL 2 | `#version 300 es` + `precision mediump float;` | as core |

The rewrite is done in Kotlin rather than with `#define gl_FragColor`, because names starting `gl_`
are reserved in GLSL ES 3.00 and some drivers reject that. The rewrite is plain token replacement,
tested in `commonTest`. Effect authors keep writing `texture2D` and `gl_FragColor`. The effect
preamble (`v_texCoord`, `u_texture`, `u_textureSize`, `u_size`, `u_alpha`) is the same text on every
device, and today it is copied four times. Documented limit: an effect cannot use `in`, `out` or
`texture` as its own identifiers.

Mesa accepts header-less 1.10 shaders on a core context today, which is why `testGl30` passes. macOS
does not. The `#version 150` path is what makes a core context on a Mac work at all.

---

## 3. Handing GL state back to the engine

The renderer sets everything it relies on in `begin` and never assumes it: blend (on, function,
equation `FUNC_ADD`), scissor, viewport, framebuffer, program, texture unit 0 and its binding, VAO,
array and element buffers, `colorMask(true × 4)`, and `DEPTH_TEST`, `CULL_FACE` and `STENCIL_TEST`
off. The last three are new. Today a 3D game that leaves depth testing on loses its interface.

Two ways to hand the state back, and each backend picks one:

| Mode | `begin` | `end`, and around `raw` | Cost |
|---|---|---|---|
| `Leave` | reads only `FRAMEBUFFER_BINDING` when the caller did not pass one | leaves a **documented end state**: the frame's framebuffer, full viewport, scissor off, blend on with `SRC_ALPHA, ONE_MINUS_SRC_ALPHA`, program 0, VAO 0, buffers 0, texture 0 on unit 0, depth, cull and stencil off | nothing extra |
| `Restore` | snapshots ~20 values with `getInteger`: the list above, plus `BLEND_*`, `SCISSOR_BOX`, `COLOR_WRITEMASK`, enables | puts every value back | ~20 queries **per frame**. Today `KorgeEffects` makes 13 queries **per effect draw**. |

| Engine | Mode | What it expects, and what the wrapper does |
|---|---|---|
| raw LWJGL | `Leave` | Same as today. `raw` hands a `GlFrame(projection, viewport)`. |
| LibGDX | `Leave` | `SpriteBatch.begin` binds its own shader and sets depth mask; its flush sets blending itself; `Texture.bind` always calls `glBindTexture`. `VertexBufferObjectWithVAO` binds its own VAO. GL 2 `VertexArray` meshes need `ARRAY_BUFFER` = 0, which the end state gives. g3d `RenderContext` caches depth, blend and cull, but resets them in its own `begin`, so interleaving by frame is safe. `raw`: flush, `device.suspend()`, set the `SpriteBatch` projection, `begin`, block, `end`, put back projection and colour, `device.resume()`. That is today's code minus its `applyBlend` patch. |
| KorGE | `Restore` | `AGOpengl` privately caches program, vertex data, blending, texture units and versions, scissor, viewport, framebuffer, colour mask and render state. The only public reset is `contextLost()`, which re-uploads every texture, so it is not usable. What `KorgeEffects` learned, kept as rules: **(1)** `ctx.flush()` before `begin` and after `raw`. **(2)** Bind the target framebuffer with `ag.bindFrameBuffer(ctx.currentFrameBuffer…)` *before* the snapshot, because KorGE binds lazily. **(3)** Everything else set through raw GL is snapshotted and restored, so KorGE's cache is true again when it next draws. Nothing of KorGE's runs between our `begin` and `end` except inside `raw`, which is wrapped in `suspend`/`resume`. **(4)** Tell the canvas whether the target is a texture and `ctx.flipRenderTexture`, for the y sign. KorGE's own `KmlGlState.save()`/`restore()` is the fallback if our list ever misses something. `raw` hands the `RenderContext`. |
| WebGL | `Leave` (default), `Restore` optional | `BrowserUi` owns the context. A page that shares it with another library that caches state (three.js does) passes `Restore`. `raw` hands `WebGlFrame(gl, projection, viewport)`. |

**Context loss.** Today gdx gets it for nothing, because `Mesh`, `ShaderProgram` and `FrameBuffer`
are "managed" and LibGDX rebuilds them after Android's GL context is lost on resume. The shared
renderer's GL objects are not managed, so `GpuDevice.contextLost()` forgets every handle. The
next frame rebuilds programs, buffers and the white texture, re-uploads atlas pages from their CPU
copies (kept in RAM, 4 MB per 1024² page), and drops the layer pool. Who calls it:

- gdx: from `ApplicationListener.resume`, only if the context really changed (compare a
  `Gdx.graphics` context counter).
- korge: when `ag.contextVersion` changes.
- webgl: on `webglcontextlost` and `webglcontextrestored`.
- lwjgl3: never.

---

## 4. Textures and images

A backend gives the canvas one function:

```kotlin
fun interface TextureResolver { fun resolve(handle: TextureHandle): BoundPicture? }   // null = not ours: throw as today
class BoundPicture(val texture: DeviceTexture, val u: Float, val v: Float, val u2: Float, val v2: Float,
                   val premultiplied: Boolean)
```

For GL, `GlDevice` takes an **adopted** texture: an engine's GL name with a width and a height, and
an optional bind hook for engines that must do the binding themselves. The device never deletes an
adopted texture and never changes its filtering or wrap settings, because it belongs to the game.

| Engine type | Becomes | Premultiplied | Notes |
|---|---|---|---|
| gdx `GdxTexture(TextureRegion)` | `region.texture.textureObjectHandle`, region `u v u2 v2` | no | Cached on the handle. Rotated atlas regions: `source` sub-rectangles still refused, as today. |
| lwjgl3 `GlTexture` | name + uv, as today | no | `GlTexture.rgba/decode` stay (stb_image decoding) and create through the device. |
| webgl `WebGlTexture(WebGLTexture)` | name adopted into the WebGL binding's handle table | no | Uploaded with premultiply off, as today. |
| korge `KorgeTexture(Bitmap, smooth)` | adopted with a **bind hook**: `ctx.getTex(bitmap).base`, then `ag.textureBind(tex, TEXTURE_2D)`, which does KorGE's **lazy upload** (when `contentVersion` changed) and keeps its cache true; then set `LINEAR` or `NEAREST` from `smooth`, since KorGE samples per draw | `bitmap.premultiplied` | The bind hook runs at flush, inside `Restore`, so KorGE's texture-unit cache is put right in `end`. Batching compares the cached `DeviceTexture`, one per `KorgeTexture`. |

**Alpha rules, one table for every backend.** Straight alpha stays the working space, so that goldens
survive.

| Source | Stored as | Drawn with |
|---|---|---|
| Atlas pages (glyphs, emoji, white block) | straight RGBA, uploaded by the device. KorGE's atlas is premultiplied today and becomes straight; no KorGE warning, because we upload through GL, not AG. | `SRC_ALPHA` |
| Game textures, gdx, lwjgl3, webgl | straight | `SRC_ALPHA` |
| Premultiplied game picture (KorGE `Bitmap32(premultiplied = true)`, a gdx atlas packed premultiplied) | premultiplied | the shader straightens it first, using the vertex flag KorGE has today (`a_shape`'s spare slot), now in the shared shader |
| Layers and render targets | premultiplied (the alpha half of the blend accumulates) | `ONE`, opacity in all four channels |

Emoji are scaled down with alpha-weighted averaging (LWJGL's `shrink`, KorGE's `Premultiplied`) in
common code, once.

---

## 5. Fonts

### Decision: the library does atlas, fallback, emoji and layout; each backend supplies a small `GlyphRasteriser`

```kotlin
interface GlyphRasteriser {
    fun face(family: String, size: Int): RasterFace?          // null = not registered with this rasteriser
}
interface RasterFace {
    val ascent: Float; val descent: Float; val capHeight: Float
    fun has(codepoint: Int): Boolean
    fun advance(codepoint: Int): Float
    fun draw(codepoint: Int, into: GlyphBitmap): Boolean     // width, height, xOffset, yOffset, pixels, kind = Coverage | Colour
}
fun interface ImageDecoder { fun decode(encoded: ByteArray): RgbaImage }   // for registerEncodedPictures
```

| Backend | Rasteriser | Lines | Decoder |
|---|---|---|---|
| lwjgl3 | stb_truetype `stbtt_GetCodepointBitmap` + `GetCodepointHMetrics` | ~180 | `STBImage` |
| gdx | gdx-freetype `FreeType.Face.loadChar` + `GlyphSlot.getBitmap` | ~150 | `Pixmap` |
| webgl | Canvas2D `fillText` + `getImageData` for one glyph. Keeps the **browser's system fallback** for scripts no registered font covers. A colour emoji drawn this way comes back as `Colour`. | ~200 (+ ~40 `FontFace` loading) | `createImageBitmap` (async: `registerEncodedPictures` suspends on web only) |
| korge | `Font.renderGlyphToBitmap` (already used) | ~120 | `PNG.readImage` |

**Moved into `composegl-render`** (`AtlasFonts : FontProvider`, `GlyphAtlas`, `FallbackChain`):

- Registration by family and size.
- `fallBackTo` for every family and per family; `fallbacksOf`, `families`, `sizesOf`.
- Tried one character at a time; the main font's `?` when nothing has the character.
- Variation selectors `FE0E`/`FE0F` treated as invisible.
- `registerPictures`: one codepoint each, scaled to the size, gap `size / 16`, drop `0.12` below the
  baseline, `colour = true`.
- Whole-pixel snapping. `textRing` leaves pictures out.
- Wrapping and ellipsis through ui's `paragraph()`, as KorGE does today. StbFonts and WebFonts each
  carry a copy of the wrapping.
- **No kerning**, already decided for LWJGL and KorGE (widths are sums of advances; `Paragraph`,
  carets, bidi and `Typewriter` rely on it).
- Glyphs made on demand, shelf-packed, several pages (KorGE's model), white block on page 0, and
  dirty rectangles uploaded with `texSubImage2D`.

**Why not a common-code TTF rasteriser?** It would give identical text pixels everywhere, which is
tempting. But:

1. The web backend would lose the browser's system fallback, so Chinese on the web would need a
   10–20 MB font download.
2. A correct rasteriser plus CFF (`.otf`) outlines is a project of its own, 2–3k lines, and it would
   put text parity at risk during a migration whose whole point is parity.
3. Every backend already has a good rasteriser.

The `GlyphRasteriser` interface is small enough that a common one can be added later as one more
implementation, for example for a future native iOS backend with no FreeType.

---

## 6. Layers, render targets, effects, tracing: all shared

| Feature | Shared implementation | Backend part |
|---|---|---|
| `layer` / `drawLayer` (plain, turned, mirrored, onto, tilted) / `cutLayer` | `RenderCanvas`, ported from `GlCanvas` (the float-vertex reference). All capability flags true when `limits.offscreen`. | none |
| Layer pool | `LayerPool`, from `GlLayers` | none |
| Render target | `RenderCanvas.begin(viewport, into = FrameTarget.Bound(width, height, isTexture))` draws into whatever is bound. `RenderTarget` (shared) owns a `DeviceTarget` for lwjgl3 and webgl. | gdx: `GdxRenderTarget` keeps a gdx `FrameBuffer` so the game gets a `TextureRegion` (~40 lines). korge: `KorgeRenderTarget` keeps its `AGFrameBuffer` and `KorgeRenderTargetView`, bound through AG (~70 lines). |
| `ShaderEffect` | `GlDevice` program cache keyed by fragment text; standard uniforms; `Uniform` mapping; premultiplied blend in the stack's mode; compile and link errors thrown with the name, as today | none |
| `drawCalls`, `traceDrawCalls` | `QuadBatch`: one count per `drawShapes`/`drawEffect`, one `BatchBreak` reason | none |
| `composegl-effects` shaders | unchanged; compiled by the one device on every backend | none |

A **recording device** in `composegl-render`'s `commonTest` (a fake `GpuDevice` that writes down
calls) runs on jvm, linuxX64 and wasmJs. It tests batching, trace reasons, clip, alpha and blend
nesting, layer bookkeeping, dialect rewriting and atlas packing with no GL. Today each of those is
tested four times, behind a GPU.

---

## 7. What is left in each backend

Line counts are `src/main` today. Kept platform code (input, clipboard, cursor, haptics, keyboard,
window, demos hooks) is unchanged.

### composegl-lwjgl3: 4,574 → about 1,680

| File | Fate |
|---|---|
| `GlCanvas.kt` (1,110) | deleted; `GlCanvas` becomes a ~30-line subclass of `RenderCanvas` (public name kept), plus `GlFrame` |
| `GlShapeBatch.kt` (903) | deleted (moves to `render/QuadBatch` + `render/gl/ShapeShader`) |
| `GlEffects.kt` (229) | deleted (moves to `GlDevice`) |
| `GlRenderTarget.kt` (177) | ~30-line wrapper round shared `RenderTarget` |
| `GlLayers.kt` (70), `VertexArrays.kt` (31) | deleted |
| `StbFonts.kt` (775) | ~180 `StbRasteriser` + ~40 `StbFonts` (subclass of `AtlasFonts` keeping `register`/`registerPictures`) |
| `GlTexture.kt` (116) | ~50 (decode, adopt) |
| **new** `Lwjgl3Gl.kt` | ~230 |
| kept: `Glfw*` (781), `Lwjgl3Backend` (61), `Preedit` (63), `preview/` (258) | unchanged except constructor wiring |

### composegl-gdx: 4,119 → about 1,460

| File | Fate |
|---|---|
| `GdxCanvas.kt` (1,116) | ~50-line subclass + `SpriteBatch` raw bridge |
| `UiShapeBatch.kt` (835), `GdxEffects.kt` (183), `GdxLayers.kt` (93), `VertexTypes.kt` (19) | deleted |
| `GdxAtlas.kt` (119), `GdxFallback.kt` (242) | deleted (shared atlas and chain) |
| `GdxFonts.kt` (447) | ~150 `FreeTypeRasteriser` + ~40 `GdxFonts` keeping `registerTrueType(FileHandle…)`/`registerPictures(Pixmap)`. `register(family, size, BitmapFont)` goes (§10 Q1). |
| `GdxRenderTarget.kt` (131) | ~40 |
| `GdxTexture.kt` (29) | ~45 (resolve) |
| **new** `GdxGl.kt` | ~250 (`Gdx.gl`/`Gdx.gl30`, small cached `IntBuffer`s for `glGetShaderiv` and similar) |
| kept: input, cursor, clipboard, keyboard, haptics, `GdxNinePatches`, `GdxBackend` (905) | unchanged |

### composegl-webgl: 3,504 → about 1,820

| File | Fate |
|---|---|
| `WebGlCanvas.kt` (729) | ~40-line subclass + `WebGlFrame` |
| `WebGlShapeBatch.kt` (685), `WebGlEffects.kt` (167) | deleted |
| `WebGlRenderTarget.kt` (169, includes `WebGlLayers`) | ~30 |
| `WebFonts.kt` (445) | ~200 `CanvasRasteriser` + ~40 font loading |
| `WebGlTexture.kt` (137) | ~80 (fetch and `createImageBitmap` stay) |
| **new** `WebGl.kt` | ~300 (handle tables, WebGL 1/2 VAO switch) |
| kept: `Dom*` input (872), `BrowserUi` (220), `WebGlBackend` (80) | unchanged |

### composegl-korge: 3,934 → about 1,770

| File | Fate |
|---|---|
| `KorgeCanvas.kt` (813) | ~60-line subclass (`renderContext`, flush rules, y sign) |
| `KorgeShapeBatch.kt` (615), `KorgeEffects.kt` (314), `KorgeLayers.kt` (94) | deleted |
| `KorgeAtlas.kt` (155), `KorgeFallback.kt` (242) | deleted |
| `KorgeFonts.kt` (325) | ~120 `KorgeRasteriser` + ~40 `KorgeFonts` |
| `KorgeRenderTarget.kt` (139) | ~70 |
| `KorgeTexture.kt` (25) | ~60 (bind hook) |
| **new** `KorgeGl.kt` + `KorgeHostState.kt` | ~230 + ~90 |
| kept: input, `ComposeGlView` (275), clipboard, cursor, haptics, keyboard, `KorgeBackend` (1,212) | unchanged |

### composegl-render (new): about 4,000 lines

`RenderCanvas` ~1,100 · `QuadBatch` + tessellation ~650 · `GpuDevice` + `LayerPool` + `RenderTarget`
~300 · `GlDevice` + dialects + `HostState` ~750 · shape and effect GLSL ~250 · `AtlasFonts` +
`GlyphAtlas` + `FallbackChain` + pictures ~900 · plus `commonTest` with the recording device.

**Net:** about 16,100 renderer-and-backend lines become about 6,700 + 4,000. More important: one
batch, one shader and one atlas, not four.

`composegl-android` and `composegl-robovm` are input and haptics helpers with no rendering. They do
not change, and they get the new renderer through gdx's `Gl` binding on GLES.

---

## 8. Migration: one backend at a time, master green at every step

### Order

| Step | Change | Proof it drew the same |
|---|---|---|
| 0 | This doc. | — |
| 1 | `composegl-render` with `GpuDevice`, `GlDevice`, `Gl`, dialects, `AtlasFonts`, recording-device tests. Port `GlCanvas`/`GlShapeBatch` nearly word for word. **No backend uses it yet.** | `commonTest` on jvm, linuxX64 and wasmJs; dialect rewrite tests; build checks. |
| 2a | **lwjgl3 canvas** onto the shared renderer, with `StbFonts` still baking its own atlas (it adapts through the resolver). | Existing lwjgl3 goldens, **unchanged files**, on `test` (GL 2.1) and `testGl30` (3.2 core). `GlDrawCallTraceTest` counts unchanged. |
| 2b | **lwjgl3 fonts** onto `AtlasFonts` + `StbRasteriser`. | `text`, `text-outline` goldens and `StbFontFallbackGlTest` unchanged. A draw count may only fall (atlas layout). |
| 3 | **gdx**. Packed-byte colours become float colours (inside `ChannelTolerance`). FreeType glyphs in the shared atlas, no kerning. | Text-free goldens unchanged. **Also held to lwjgl3's goldens** by the rule KorGE and WebGL already use. Text goldens re-made once with `COMPOSEGL_UPDATE_GOLDENS=1` and **looked at in the PR** (§10 Q2). All `*GlTest` and `uiTest` suites on `test` + `testGl30`. Android and RoboVM demo modules still compile. |
| 4 | **webgl**. | `WebGlScreenshotTest` goldens unchanged (text within tolerance, since the rasteriser is the same Canvas2D); held to lwjgl3's text-free goldens; `UiTestWebGlTest`; run on WebGL 2 **and** forced WebGL 1. |
| 5 | **korge** last: AG's cached state is the riskiest handoff. | `KorgeScreenshotTest` goldens; `KorgeShippedEffectsTest`; `KorgeUiTestScreenTest`, `KorgeInputScreenTest`; a new test that draws a KorGE sprite **after** the interface and before it, and checks both come out right (state restore); the headless `KORGE_HEADLESS=true` run. |
| 6 | Docs: `docs/wiki/Backends.md` "how to write a backend" becomes "implement `Gl`, a rasteriser, a resolver". | — |

Rules for every step:

- The old renderer files are **deleted in the same PR** that switches the backend. No dual paths.
- The backend's `RendererConfinementCheck` is switched on in that PR.
- A golden may change only in step 3's text scenes, and then with the picture in the PR.
- Nothing is released. Master builds snapshots.

### Test matrix (the CI `gl` job, all under Mesa llvmpipe or SwiftShader)

| Context | lwjgl3 | gdx | korge | webgl | New? |
|---|---|---|---|---|---|
| GL 2.1 compatibility | `test` | `test` | — | — | no |
| GL 3.2 core (`MESA_GL_VERSION_OVERRIDE=3.2FC`) | `testGl30` | `testGl30` | — | — | no |
| OpenGL ES 3.0 (GLFW `OPENGL_ES_API`, Mesa) | `testGles3` | — | — | — | **yes**: the ES dialect on desktop CI |
| GL 4.5 compatibility (KorGE's own) | — | — | `jvmTest` (Xvfb + headless) | — | no |
| WebGL 2 (headless Chrome) | — | — | — | `wasmJsBrowserTest` | no |
| WebGL 1 (same test, context forced to `webgl`) | — | — | — | `wasmJsBrowserTestWebGl1` | **yes** |
| No GL (recording device) | `composegl-render:allTests` on jvm, linuxX64, wasmJs | | | | **yes** |

### The check that keeps renderer code out of backends

New `buildSrc/RendererConfinementCheck.kt`, registered on `check` in the four backend modules. It
scans `src/*[mM]ain/**/*.kt` and fails when:

1. **Shader text appears anywhere:** `gl_FragColor`, `gl_Position`, `texture2D(`, `#version`,
   `precision mediump`, `varying `, `attribute vec`.
2. **Draw, shader or blend calls appear outside the one binding file** (`*Gl.kt` whose class
   implements `render.gl.Gl`): `glDrawElements|drawElements\(|glDrawArrays|drawArrays\(|
   glShaderSource|shaderSource\(|glCreateProgram|createProgram\(|glBlendFunc|blendFunc|
   glVertexAttribPointer|vertexAttribPointer\(|glBindFramebuffer|bindFramebuffer\(`. The KorGE state
   file is named as the second allowed file, for its restore calls only.
3. **The binding file is over 400 lines**, a sign that logic crept in.

Plus the existing `BytecodeReferenceCheck`, with new forbidden prefixes per backend:

- gdx: `com/badlogic/gdx/graphics/Mesh`, `com/badlogic/gdx/graphics/glutils/ShaderProgram`,
  `com/badlogic/gdx/graphics/g2d/PixmapPacker`.
- korge: `korlibs/graphics/shader`, `korlibs/graphics/AGVertexData`.
- lwjgl3: `org/lwjgl/stb/STBTTPackContext`.

---

## 9. Other graphics APIs later

OpenGL is the only device now. The seam in §2.2 is where Vulkan, Metal or DirectX would plug in. No
code for them is written, and nothing above the seam knows GL exists.

**A future device implements `GpuDevice`, about 16 members:**

- textures (create, write a rectangle, delete)
- offscreen targets
- begin, end, suspend and resume with the engine
- target, scissor and clear
- a vertex stream
- `drawShapes` with the fixed 33-float layout
- `drawEffect`
- read-back and context loss

It reports `originBottomLeft = false`, and the canvas flips once, as it already does per target.

**Shaders are the real work:**

- **The built-in shape shader** is one shader. A new device ships it hand-ported (HLSL, MSL or
  SPIR-V). About 90 lines of maths, and the goldens check it.
- **User `ShaderEffect`s** stay GLSL ES 1.00 as their public contract. A non-GL device has to
  translate them at run time (glslang to SPIR-V, then SPIRV-Cross to HLSL or MSL, or naga), or
  offline when the effect is known at build time. The fixed preamble and the five standard inputs
  make that translation mechanical. Changing the effect contract to a neutral language is **not**
  proposed now.
- **Nothing else is GL-shaped above the seam.** Blend is an enum. Scissor and viewport are pixel
  boxes. Uniforms are the existing `Uniform` values.

A non-GL device would come with its own engine binding (for example a LibGDX-on-WebGPU backend). The
four backends here stay on `GlDevice`.

---

## 10. Risks, and questions that need the owner

### Risks (handled in the plan, listed so nobody is surprised)

| Risk | Handling |
|---|---|
| KorGE on a non-OpenGL `AG` | `KorgeShapeBatch` works through any `AG` today; the shared renderer needs `AGOpengl`. KorGE 6 is OpenGL or WebGL on every target, and `KorgeEffects` already requires `AGOpengl`. Fail at `begin` with a clear message. |
| Android context loss (gdx) | §3 `contextLost`; a gdx test that calls it mid-run and redraws a golden. |
| Mesa is lenient about GLSL on core contexts, so CI can pass what a Mac rejects | The dialect table plus the new ES 3 run. The strict `#version 150` path is used on every core context, not only on Macs. |
| Draw counts change with the shared atlas (gdx has several pages, lwjgl3 one) | Trace tests may only fall. |
| Precision: `mediump` fragment maths on ES with design coordinates up to 4096 | Unchanged from today, so parity first. Moving the shape shader to `highp` where `GL_FRAGMENT_PRECISION_HIGH` exists is a separate follow-up with its own goldens. |

### Questions

1. **Public API: keep backend class names, or break cleanly?** Recommended: keep `GlCanvas`,
   `GdxCanvas(spriteBatch, …)`, `WebGlCanvas`, `KorgeCanvas`, `StbFonts`, `GdxFonts`, `WebFonts`,
   `KorgeFonts` and the `*RenderTarget`s as thin subclasses, so demos and games keep compiling.
   Remove `GdxAtlas`, `KorgeAtlas`, `UiShapeBatch` and `GdxFonts.register(family, size, BitmapFont)`,
   because a pre-built `BitmapFont` cannot feed the shared atlas. This changes public API in the next
   minor. OK?
2. **gdx text changes by a pixel here and there.** gdx kerns today; LWJGL and KorGE deliberately do
   not. Sharing the font layer means gdx stops kerning, and its text goldens are re-made once. OK?
3. **The "second opinion" backend.** `composegl-lwjgl3` exists partly to prove the toolkit by sharing
   no code with gdx (its `checkNoEngineTypes` and wiki page say so). After this, all four share the
   renderer, and the independent check is the committed goldens themselves. Keep lwjgl3 as the
   reference raw-GL wrapper and first migration target (recommended), and retire the "shares no
   code" wording?
