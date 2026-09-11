# Shaders

Blur a pause menu, outline the thing you are aiming at, dissolve a panel when it
closes — or write your own GLSL and bind it to any widget.

There is no privileged API here. The effects that ship are written against the same
thing your shader is.

---

## The ones that ship

They live in `composegl-effects`, a separate module so that a game which wants none
of them carries none of them.

```kotlin
implementation("dev.wildware.composegl:composegl-effects:0.1.0")
```

```kotlin
Panel(Modifier.blur(radius = 8f)) { PausedMenu() }

Image(portrait, Modifier.outline(Colour.rgb(0x5B8DEF), width = 2f))

Panel(Modifier.colourGrade(saturation = 0f, brightness = 0.6f)) { DeadScreen() }

Panel(Modifier.dissolve(progress)) { ClosingTerminal() }
```

| | what it does |
|---|---|
| `blur(radius)` | a two-pass gaussian, horizontal then vertical |
| `outline(colour, width)` | a ring of colour round whatever is not transparent |
| `colourGrade(brightness, contrast, saturation, tint)` | the whole knob set, per pixel |
| `dissolve(progress, scale, softness, edge)` | eats it away in patches, optionally burning the rim |

Each also exists as a plain `ShaderEffect`, if you want it somewhere other than a
modifier chain:

```kotlin
val fade = blur(radius = 6f, axis = Axis.Horizontal)
```

---

## How it works

`Modifier.effect(...)` draws the widget and everything under it into an offscreen
picture, then draws that picture through your shader.

Two of them in a chain compose, in the order written — the second works on the
first one's answer:

```kotlin
Modifier.effect(blur(8f, Axis.Horizontal)).effect(blur(8f, Axis.Vertical))
```

That is exactly what `Modifier.blur(8f)` is.

**It costs a picture and a draw call, every frame it is on screen.** Put it on the
panel, not on each of the forty things in the panel.

**A backend with no offscreen drawing, or no shaders, draws the widget plainly and
says nothing.** An effect degrades to no effect, never to a missing widget.

---

## Writing your own

A shader is text — a fragment shader in the old dialect (`varying`, `texture2D`,
`gl_FragColor`), which compiles on a desktop driver and on a phone without being
written twice. The backend adds the `#version` and precision lines; do not write
them.

```kotlin
val scanlines = ShaderSource(
    name = "scanlines",
    fragment = """
        uniform float u_spacing;
        uniform float u_depth;

        void main() {
            vec4 picture = texture2D(u_texture, v_texCoord);
            float line = mod(v_texCoord.y * u_size.y, u_spacing) / u_spacing;
            float dim = 1.0 - u_depth * step(0.5, line);
            gl_FragColor = picture * dim * u_alpha;
        }
    """,
)

fun scanlines(spacing: Float = 3f, depth: Float = 0.25f) = ShaderEffect(
    source = scanlines,
    uniforms = mapOf(
        "u_spacing" to Uniform.Number(spacing),
        "u_depth" to Uniform.Number(depth),
    ),
)

// …then
Panel(Modifier.effect(scanlines())) { Terminal() }
```

### What is declared for you

| name | what it is |
|---|---|
| `v_texCoord` | where in the picture this pixel is, 0 to 1 |
| `u_texture` | the picture: what the interface drew, before the effect |
| `u_textureSize` | its size in real pixels — a blur's step is `1.0 / u_textureSize` |
| `u_size` | the area the effect covers, in design units |
| `u_alpha` | the opacity in force, which your last line should multiply by |

Declare only your own uniforms on top of those.

### Uniforms are typed

```kotlin
Uniform.Number(1.5f)
Uniform.Vector2(x, y)
Uniform.Vector3(r, g, b)
Uniform.Vector4(r, g, b, a)
Uniform.Whole(4)
Uniform.Flag(true)
Uniform.of(Colour.rgb(0x5B8DEF))    // a vec4, NOT premultiplied
```

Passing a size where a colour was meant is a Kotlin compile error rather than a
wrong picture on a screen. A name your shader does not declare is ignored — drivers
throw away a uniform nothing uses.

### Premultiplied alpha

What you sample already has its colours multiplied by its own opacity, and what you
write must be the same. That is what makes a soft edge blend without a dark halo
round it.

In practice: multiply your whole answer by its own alpha, and by `u_alpha`. To work
on the *colour* rather than what is stored, divide it out first:

```glsl
vec3 colour = picture.a > 0.0 ? picture.rgb / picture.a : vec3(0.0);
// …do something to `colour`…
gl_FragColor = vec4(colour * picture.a, picture.a) * u_alpha;
```

`Uniform.of(colour)` is deliberately **not** premultiplied: a shader that wants a
hue to tint by wants the hue, and one that wants a colour to blend towards wants it
multiplied, and only the shader knows which.

### Bleed

An effect that reaches outside the widget has to say so, or the spread is cut off
square at the edge:

```kotlin
ShaderEffect(source = glowShader, uniforms = …, bleed = 12f)
```

It costs a bigger picture, which is why it is zero unless asked for. `blur` sets it
to its radius, `outline` to its width, `colourGrade` and `dissolve` to nothing.

### When it does not compile

The backend throws, with the driver's own message and your shader's `name` in it. A
shader that does not compile is a mistake in the source rather than a condition to
recover from — and a silent black rectangle is the hardest bug in this toolkit to
find.

---

## Drawing to a layer yourself

Under the modifier is one canvas call, if you want it directly:

```kotlin
val picture = canvas.layer(bounds) { drawTheThing() }
if (picture != null) canvas.drawLayer(picture, bounds, effect)
else drawTheThing()                      // no offscreen drawing here: draw it plainly
```

The picture belongs to the canvas and is reused; it is good until the end of the
frame and must not be kept past it.

---

## What next

- **[[Modifiers]]** — where `effect` sits in the chain
- **[[Widgets]]** — what you are putting effects on
- **[[Backends]]** — which backends can compile shaders, and what happens when they cannot
