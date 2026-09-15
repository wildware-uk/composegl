package dev.wildware.composegl.gdx

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Mesh

/**
 * How a mesh that is rewritten every frame keeps its vertices, picked the way `SpriteBatch` picks.
 *
 * On GL 2 and OpenGL ES 2 the vertices stay in the game's memory and go to the driver as a pointer
 * at each draw. That is the cheapest thing there, and exactly what this toolkit has always done.
 *
 * On GL 3 and OpenGL ES 3 — whenever `Gdx.gl30` exists — they go in a buffer on the GPU, described
 * by a vertex array object. A GL 3.2 core context refuses the pointer outright: every attribute
 * fails with "GL_INVALID_OPERATION in glVertexAttribPointer(no array object bound)" and nothing is
 * drawn. And even where a pointer is still allowed, a buffer the game's own `SpriteBatch` left bound
 * turns that pointer into an offset into somebody else's vertices.
 */
internal fun streamedVertexType(): Mesh.VertexDataType =
    if (Gdx.gl30 != null) Mesh.VertexDataType.VertexBufferObjectWithVAO else Mesh.VertexDataType.VertexArray
