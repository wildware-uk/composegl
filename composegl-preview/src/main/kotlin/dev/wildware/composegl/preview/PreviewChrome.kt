package dev.wildware.composegl.preview

import androidx.compose.runtime.Composable
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.TextureHandle
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.FlowRow
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.clickable
import dev.wildware.composegl.ui.modifier.fillMaxHeight
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.weight
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.Image
import dev.wildware.composegl.ui.widget.ImageFit
import dev.wildware.composegl.ui.widget.ScrollArea
import dev.wildware.composegl.ui.widget.Text

/**
 * The window's own interface: the list of previews down the side, the banner, and the previews.
 *
 * Kept layer only, and it lives for the whole session. It keeps nothing a build declared: every
 * composable here takes a preview's name rather than its stage, because a composable's arguments are
 * kept in its composition, and pictures come through [picture], which is the window's.
 *
 * @param picture the latest drawing of the preview with that name, or null before it has one.
 */
@Composable
fun PreviewChrome(session: PreviewSession, picture: (String) -> TextureHandle?) {
    // Read so that a reload recomposes everything below, even when every name stayed the same.
    session.builds
    Row(Modifier.fillMaxSize().background(Ground)) {
        Sidebar(session)
        Column(
            Modifier.weight(1f).fillMaxHeight().padding(16f),
            verticalArrangement = Arrangement.spacedBy(10f),
        ) {
            session.banner?.let { banner ->
                Box(Modifier.fillMaxWidth().background(Alarm, 4f).padding(10f).testTag("banner")) {
                    Text(banner.message, colour = Colour.White)
                }
            }
            session.status?.let { Text(it, Modifier.testTag("status"), colour = Quiet) }
            session.notice?.let { Text(it, Modifier.testTag("notice"), colour = Amber) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (session.gallery) Gallery(session, picture) else Single(session, picture)
            }
        }
    }
}

@Composable
private fun Sidebar(session: PreviewSession) {
    Column(
        Modifier.width(230f).fillMaxHeight().background(Side).padding(12f),
        verticalArrangement = Arrangement.spacedBy(8f),
    ) {
        Text("PREVIEWS", colour = Quiet)
        Button(
            if (session.gallery) "SHOW ONE" else "SHOW ALL",
            onClick = { session.gallery = !session.gallery },
            modifier = Modifier.fillMaxWidth().testTag("mode"),
        )
        ScrollArea(Modifier.weight(1f).fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(2f)) {
                session.names.forEach { name ->
                    val chosen = name == session.selected
                    Text(
                        name,
                        Modifier.fillMaxWidth()
                            .background(if (chosen) Chosen else Colour.Transparent, 3f)
                            .padding(6f)
                            .clickable {
                                session.select(name)
                                session.gallery = false
                            }
                            .testTag("pick:$name"),
                        colour = if (chosen) Colour.White else Quiet,
                        maxLines = 1,
                    )
                }
            }
        }
        Text("G  all or one\nUp, Down  pick", colour = Quiet)
    }
}

@Composable
private fun Single(session: PreviewSession, picture: (String) -> TextureHandle?) {
    val name = session.selected
    val stage = name?.let(session::stage)
    if (name == null || stage == null) {
        Text("No @Preview functions found.", colour = Quiet)
        return
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8f)) {
        Text("$name   ${stage.width} x ${stage.height}   ${stage.function}", colour = Quiet, maxLines = 1)
        // At its own size when there is room, shrunk to fit when there is not: a size is clamped
        // into the room it is offered, and the picture keeps its shape inside it.
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Face(session, name, picture, Modifier.size(stage.width.toFloat(), stage.height.toFloat()))
        }
    }
}

@Composable
private fun Gallery(session: PreviewSession, picture: (String) -> TextureHandle?) {
    ScrollArea(Modifier.fillMaxSize()) {
        FlowRow(horizontalSpacing = 16f, verticalSpacing = 16f) {
            session.names.forEach { name ->
                val stage = session.stage(name) ?: return@forEach
                val scale = minOf(1f, TileWidth / stage.width, TileHeight / stage.height)
                Column(
                    Modifier.clickable {
                        session.select(name)
                        session.gallery = false
                    },
                    verticalArrangement = Arrangement.spacedBy(4f),
                ) {
                    Text(name, colour = if (name == session.selected) Colour.White else Quiet, maxLines = 1)
                    Face(session, name, picture, Modifier.size(stage.width * scale, stage.height * scale))
                }
            }
        }
    }
}

/** A preview's picture, or what it threw. */
@Composable
private fun Face(session: PreviewSession, name: String, picture: (String) -> TextureHandle?, modifier: Modifier) {
    val failure = session.stage(name)?.failure
    Box(modifier.background(Tile).testTag("face:$name")) {
        if (failure != null) {
            Box(Modifier.fillMaxSize().background(Broken).padding(8f)) {
                Text(failure, colour = Colour.White)
            }
        } else {
            picture(name)?.let { Image(it, Modifier.fillMaxSize(), fit = ImageFit.Contain) }
        }
    }
}

/** What a tile's picture is cleared to before the preview draws its own background over it. */
internal val Tile = Colour(0xFF1A1D24.toInt())

private val Ground = Colour(0xFF0E1014.toInt())
private val Side = Colour(0xFF15181E.toInt())
private val Chosen = Colour(0xFF2B3A55.toInt())
private val Alarm = Colour(0xFF9C2A2A.toInt())
private val Broken = Colour(0xFF4A1C1C.toInt())
private val Amber = Colour(0xFFE0B050.toInt())
private val Quiet = Colour(0xFF9AA3B2.toInt())

private const val TileWidth = 360f
private const val TileHeight = 240f
