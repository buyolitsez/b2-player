package dev.buyolitsez.b2player

import android.content.Intent
import android.os.SystemClock
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

sealed interface ChatEntry {
    data class User(val text: String) : ChatEntry
    /** [cat] is the catalog the reply's handles point into. */
    data class Reply(val text: String, val error: Boolean = false, val cat: Catalog? = null) : ChatEntry
    /** How long the assistant took to answer, without the time spent waiting for Apply or Cancel. */
    data class Took(val ms: Long) : ChatEntry
    /** A change waiting for one of its actions or Cancel. */
    class Confirm(val change: AssistantTools.Change) : ChatEntry {
        val decision = CompletableDeferred<AssistantTools.Action?>()
        var answered by mutableStateOf(false)
        var chosen by mutableStateOf<AssistantTools.Action?>(null)
        fun answer(action: AssistantTools.Action?) { if (!answered) { answered = true; chosen = action; decision.complete(action) } }
    }
}

/** The assistant's chat screen state over [AssistantChat]. The conversation lives until the app is closed. */
@UnstableApi
class Assistant(private val ui: Ui) {
    private val prefs = ui.context.getSharedPreferences("assistant", android.content.Context.MODE_PRIVATE)
    var apiKey: String
        get() = prefs.getString("apiKey", "")!!
        set(v) { prefs.edit().putString("apiKey", v.trim()).apply() }

    val entries = mutableStateListOf<ChatEntry>()
    var busy by mutableStateOf(false); private set
    /** Messages written while the assistant is busy, sent together when its turn ends. */
    val queued = mutableStateListOf<String>()

    private var confirmWait = 0L
    private var cat: Catalog? = null
    private var job: Job? = null
    private var keptAlive = false

    private val chat = AssistantChat(object : AssistantHost {
        override val intro = """
            You are the assistant inside B2 Player, the user's personal music player on Android. The chat is small: keep replies
            short. Simple Markdown is shown: **bold**, *italic*, `code`, lists, headings and links. The queue has its own liked filter.
            Make albums, tracks and artists from the library that you name tappable with links: [title](a12) or [title](t345)
            with the handle, [name](artist) with the artist's name as in the library. Don't put links inside bold.
        """.trimIndent()
        override val currentId get() = ui.currentId
        override val playing get() = ui.playing
        override val queueIds get() = ui.queueIds
        override val currentIndex get() = ui.currentIndex
        override fun play(tracks: List<Track>, shuffled: Boolean) { ui.play(tracks, shuffled = shuffled) }
        override fun playNext(tracks: List<Track>) { ui.playNext(tracks) }
        override fun addToEnd(tracks: List<Track>) { ui.addToEnd(tracks) }
        override fun restoreQueue() = ui.restoreReplaced()
        override fun isLiked(t: Track) = ui.library.isLiked(t)
        override fun setLiked(tracks: List<Track>, liked: Boolean) = ui.setLiked(tracks, liked)
        override val playlists get() = ui.app.playlists.all
        override fun createPlaylist(name: String, tracks: List<Track>) { ui.createPlaylist(name, tracks) }
        override fun addToPlaylist(p: Playlist, tracks: List<Track>) = ui.addToPlaylist(p, tracks)
        override fun history() = ui.app.history.all()
        override fun rating(a: Album) = ui.app.ratings.get(a.id)
        override fun setRating(a: Album, rating: Int) = ui.setRating(a, rating)
        override fun reply(text: String, error: Boolean) { entries.add(ChatEntry.Reply(text, error, cat)) }
        override suspend fun confirm(change: AssistantTools.Change): AssistantTools.Action? {
            val confirm = ChatEntry.Confirm(change)
            entries.add(confirm)
            val asked = SystemClock.elapsedRealtime()
            return confirm.decision.await().also { confirmWait += SystemClock.elapsedRealtime() - asked }
        }
        override fun log(message: String, error: Throwable?) {
            if (error != null) AppLog.w("assistant", message, error) else AppLog.i("assistant", message)
        }
    })

    fun clear() {
        if (busy) return
        entries.clear(); chat.clear()
    }

    fun stop() { job?.cancel() }

    fun send(text: String) {
        val cat = ui.app.catalog.value
        if (text.isBlank() || cat == null) return
        if (busy) {
            // A new message instead of tapping the card means "not this": it is cancelled and the message goes next.
            entries.filterIsInstance<ChatEntry.Confirm>().forEach { it.answer(null) }
            queued.add(text.trim()); return
        }
        entries.add(ChatEntry.User(text.trim()))
        // Started while the app is on screen: messages queued meanwhile go out on the same service, maybe in the background.
        if (!keptAlive) { keptAlive = true; AssistantService.start(ui.context) }
        busy = true
        val started = SystemClock.elapsedRealtime()
        confirmWait = 0L
        this.cat = cat
        chat.apiKey = apiKey
        job = ui.scope.launch {
            try {
                chat.send(cat, text.trim())
                entries.add(ChatEntry.Took(SystemClock.elapsedRealtime() - started - confirmWait))
            } catch (e: CancellationException) {
                entries.filterIsInstance<ChatEntry.Confirm>().forEach { it.answer(null) }
                entries.add(ChatEntry.Reply("Stopped", error = true))
            } catch (e: Exception) {
                entries.add(ChatEntry.Reply(e.message ?: e.toString(), error = true))
                entries.add(ChatEntry.Took(SystemClock.elapsedRealtime() - started - confirmWait))
            } finally {
                busy = false
                if (queued.isNotEmpty()) send(queued.joinToString("\n").also { queued.clear() })
                else { keptAlive = false; AssistantService.stop() }
            }
        }
    }
}

@UnstableApi
@Composable
fun AssistantScreen(ui: Ui) {
    val a = ui.assistant
    ui.version  // the key is saved in Settings
    if (a.apiKey.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Enter a Claude API key in Accounts to use the assistant", color = Muted, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Pill("Open accounts", active = true) { ui.open(Screen.Settings); ui.open(Screen.Accounts) }
        }
        return
    }
    var input by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(a.entries.size, a.busy, a.queued.size) { if (a.entries.isNotEmpty()) list.animateScrollToItem(a.entries.size + a.queued.size) }
    // Android's own speech recognition dialog: no microphone permission needed. The text goes into the field to edit and send.
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { input = if (input.isBlank()) it else "${input.trimEnd()} $it" }
    }
    // The input sits above the mini player and tabs, which are drawn over the bottom of the screen.
    Column(Modifier.fillMaxSize().padding(bottom = LocalListBottom.current)) {
        LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(20.dp, 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (a.entries.isEmpty()) item {
                Column(Modifier.fillParentMaxHeight(0.8f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)), Alignment.Center) {
                        Icon(Icons.Default.AutoAwesome, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    Text("What should we play?", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.headlineSmall)
                }
            }
            items(a.entries) { e -> ChatBubble(ui, e) }
            item { if (a.busy) Text("…", color = Muted) }
            items(a.queued) { Box(Modifier.fillMaxWidth(), Alignment.CenterEnd) { Text(it, Modifier.clip(RoundedCornerShape(12.dp)).background(Gray1).padding(10.dp, 6.dp), color = Muted) } }
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Message", color = Faint) }, maxLines = 4,
                shape = RoundedCornerShape(24.dp), textStyle = MaterialTheme.typography.bodyLarge,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Gray2, unfocusedContainerColor = Gray2,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { a.send(input); input = "" }))
            IconButton({
                runCatching {
                    speech.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU"))
                }.onFailure { ui.toast("Speech recognition isn't available") }
            }) { Icon(Icons.Default.Mic, "Speak") }
            // While the assistant works, an empty field offers Stop; a typed message is queued for after its turn.
            val stop = a.busy && input.isBlank()
            val ready = stop || input.isNotBlank()
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(if (ready) MaterialTheme.colorScheme.primary else Gray2)
                    .clickable(ready) { if (stop) a.stop() else { a.send(input); input = "" } },
                Alignment.Center,
            ) {
                Icon(if (stop) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send, if (stop) "Stop" else "Send", Modifier.size(20.dp),
                    tint = if (ready) Color.Black else Faint)
            }
        }
    }
}

@Composable
private fun ChatBubble(ui: Ui, e: ChatEntry) {
    when (e) {
        is ChatEntry.User -> Box(Modifier.fillMaxWidth(), Alignment.CenterEnd) {
            SelectionContainer { Text(e.text, Modifier.clip(RoundedCornerShape(18.dp)).background(Gray2).padding(14.dp, 9.dp)) }
        }
        is ChatEntry.Reply -> SelectionContainer {
            if (e.error) Text(e.text, color = MaterialTheme.colorScheme.error)
            else Text(remember(e) { markdown(e.text) { label, url -> e.cat?.let { itemScreen(it, label, url) }?.let { { ui.open(it) } } } })
        }
        is ChatEntry.Took -> Text(if (e.ms < 1000) "${e.ms} ms" else "${e.ms / 100 / 10.0} s",
            style = MaterialTheme.typography.labelSmall, color = Muted)
        is ChatEntry.Confirm -> Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Gray1).padding(10.dp)) {
            Text(e.change.title, fontWeight = FontWeight.Bold)
            e.change.tracks.take(12).forEach { Text("${it.artist} — ${it.title}", style = MaterialTheme.typography.bodySmall, color = Muted) }
            if (e.change.tracks.size > 12) Text("and ${e.change.tracks.size - 12} more", style = MaterialTheme.typography.bodySmall, color = Muted)
            Spacer(Modifier.height(6.dp))
            val chosen = e.chosen
            when {
                !e.answered -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    e.change.actions.forEachIndexed { i, action -> Pill(action.label, active = i == 0) { e.answer(action) } }
                    Pill("Cancel") { e.answer(null) }
                }
                chosen != null -> Text(chosen.done, color = MaterialTheme.colorScheme.primary)
                else -> Text("Cancelled", color = Muted)
            }
        }
    }
}

private val inlineMarkdown = Regex("""\*\*(.+?)\*\*|\*(\S(?:.*?\S)?)\*|`([^`]+)`|\[([^\]]+)]\(([^)\s]+)\)""")
private val bullet = Regex("""^(\s*)[-*+]\s+""")
private val heading = Regex("""^#{1,6}\s+""")
private val linkStyle = TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline))

/** The screen a link to a library item opens: an album or track handle, or "artist" with the artist's name as the label. */
private fun itemScreen(cat: Catalog, label: String, url: String): Screen? {
    val n = url.drop(1).toIntOrNull()
    return when {
        url == "artist" -> cat.artists.firstOrNull { it.first.equals(label, ignoreCase = true) }?.let { Screen.ArtistView(it.first) }
        n == null -> null
        url[0] == 'a' -> cat.albums.getOrNull(n)?.let { Screen.AlbumView(it) }
        url[0] == 't' -> cat.tracks.getOrNull(n)?.let { Screen.AlbumView(it.album) }
        else -> null
    }
}

/**
 * The Markdown a chat reply needs: headings, bullets, **bold**, *italic*, `code` and [links](url). [onItem] gives the
 * action for a link to a library item; other links open in the browser if they are web links, else show as plain text.
 */
private fun markdown(text: String, onItem: (label: String, url: String) -> (() -> Unit)?): AnnotatedString = buildAnnotatedString {
    text.lines().forEachIndexed { i, raw ->
        if (i > 0) append('\n')
        val h = heading.find(raw)
        val line = if (h != null) raw.substring(h.range.last + 1) else raw.replaceFirst(bullet, "$1• ")
        val start = length
        var pos = 0
        for (m in inlineMarkdown.findAll(line)) {
            append(line, pos, m.range.first)
            val (bold, italic, code, label, url) = m.destructured
            when {
                bold.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
                italic.isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(italic) }
                code.isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x33888888))) { append(code) }
                else -> {
                    val item = onItem(label, url)
                    when {
                        item != null -> withLink(LinkAnnotation.Clickable(url, linkStyle) { item() }) { append(label) }
                        url.startsWith("http") -> withLink(LinkAnnotation.Url(url, linkStyle)) { append(label) }
                        else -> append(label)
                    }
                }
            }
            pos = m.range.last + 1
        }
        append(line, pos, line.length)
        if (h != null) addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, length)
    }
}
