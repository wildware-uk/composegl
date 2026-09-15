package dev.wildware.composegl.demo.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.layout.VerticalAlignment
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.weight
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.text.Locale
import dev.wildware.composegl.ui.text.ProvideLocale
import dev.wildware.composegl.ui.text.Strings
import dev.wildware.composegl.ui.text.TextDecoration
import dev.wildware.composegl.ui.text.TextRange
import dev.wildware.composegl.ui.text.TextRun
import dev.wildware.composegl.ui.text.stringOf
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.Checkbox
import dev.wildware.composegl.ui.widget.Image
import dev.wildware.composegl.ui.widget.ProvideTextScale
import dev.wildware.composegl.ui.widget.SelectionContainer
import dev.wildware.composegl.ui.widget.Stepper
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.composegl.ui.widget.TextField

@Composable
fun TextPage() {
    Page(Section.Text, "Text is measured and laid out by the toolkit; the browser draws each glyph once into an atlas, so a screen of text is still one draw call.") {
        Styles()
        Runs()
        Selection()
        Fallback()
        RightToLeft()
        Scale()
    }
}

@Composable
private fun Styles() = Card("Styles from the skin") {
    Text("label.title", style = "label.title")
    Text("label.heading", style = "label.heading")
    Text("label, the default")
    Text("label.dim, for anything secondary", style = "label.dim")
    Text("label.danger", style = "label.danger")
    Text("A paragraph wraps when it runs out of room, the way a mission briefing does, and keeps its line height.", Modifier.fillMaxWidth())
}

@Composable
private fun Runs() = Card("Styled runs", "Part of a line coloured and underlined, and found by the pointer. Hover or click a name.") {
    var said by remember { mutableStateOf("Nothing picked") }
    val line = "Find the Sunken Key in the Old Harbour, then return to Edda."
    val runs = remember {
        listOf("Sunken Key", "Old Harbour", "Edda").map { term ->
            val start = line.indexOf(term)
            TextRun(TextRange(start, start + term.length), colour = Warm, decoration = TextDecoration.Underline, tag = term)
        }
    }
    Text(line, Modifier.fillMaxWidth().testTag("runs"), runs = runs, onRunHover = { run -> if (run != null) said = "Over: ${run.tag}" }, onRunClick = { run -> said = "Clicked: ${run.tag}" })
    Text(said, style = "label.dim")
}

@Composable
private fun Selection() = Card("Selectable text", "Drag across it, then Ctrl+C. Double-click picks a word.") {
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(6f)) {
            Text("World seed: 8F3A-22C1-BB07")
            Text("Server: eu-west-3 · build 2026.09.15")
        }
    }
    TextField("", {}, Modifier.fillMaxWidth(), placeholder = "Paste it here")
}

@Composable
private fun Fallback() = Card("Fallback fonts and emoji", "One label mixing the game's font, Chinese, Japanese and Korean cuts, and the system's emoji.") {
    Text("Ace: GG 👍")
    Text("玩家: 你好！欢迎来到游戏 🎮")
    Text("プレイヤー: こんにちは 😀")
    Text("플레이어: 안녕하세요 ❤️")
    Text("Nova: 🚀🔥", style = "label.dim")
}

private val Options = Strings(
    mapOf(
        Locale.English to mapOf("title" to "OPTIONS", "music" to "Music", "name" to "Name", "welcome" to "Welcome back, Ada!"),
        Locale("he") to mapOf("title" to "אפשרויות", "music" to "מוזיקה", "name" to "שם", "welcome" to "ברוך שובך, Ada!"),
    ),
)

@Composable
private fun RightToLeft() = Card("Right to left", "Pick Hebrew: the panel mirrors, and the English name inside the Hebrew sentence still reads the right way.") {
    var language by remember { mutableStateOf(Locale("he")) }
    Stepper(listOf(Locale.English, Locale("he")), language, { language = it }, label = { if (it == Locale.English) "English" else "עברית" }, modifier = Modifier.testTag("language"))
    ProvideLocale(language, Options) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8f)) {
            Text(stringOf("title"), style = "label.heading")
            var music by remember { mutableStateOf(true) }
            Checkbox(music, { music = it }, label = stringOf("music"))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10f), verticalAlignment = VerticalAlignment.Centre) {
                Text(stringOf("name"))
                var name by remember(language) { mutableStateOf(if (language == Locale.English) "Ada" else "עדה") }
                TextField(name, { name = it }, Modifier.weight(1f))
            }
            Text(stringOf("welcome"), style = "label.dim")
        }
    }
}

@Composable
private fun Scale() = Card("Text scale", "The words grow; the crest, the padding and the layout around them do not.") {
    listOf(1f, 1.25f, 1.5f).forEach { scale ->
        ProvideTextScale(scale) {
            Row(horizontalArrangement = Arrangement.spacedBy(10f), verticalAlignment = VerticalAlignment.Centre) {
                Image("icon/crest", Modifier.size(24f))
                Text("${(scale * 100).toInt()}%", Modifier.width(56f), style = "label.dim")
                Button("Engage", {})
            }
        }
    }
}
