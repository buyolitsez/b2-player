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
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.core.jsonMapper
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.Usage
import com.anthropic.models.messages.WebSearchTool20260209
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

sealed interface ChatEntry {
    data class User(val text: String) : ChatEntry
    data class Reply(val text: String, val error: Boolean = false) : ChatEntry
    /** How long the assistant took to answer, without the time spent waiting for Apply or Cancel. */
    data class Took(val ms: Long) : ChatEntry
    /** A change to likes or playlists waiting for Apply or Cancel. */
    class Confirm(val change: AssistantTools.Change) : ChatEntry {
        val decision = CompletableDeferred<Boolean>()
        var answered by mutableStateOf<Boolean?>(null)
        fun answer(ok: Boolean) { if (answered == null) { answered = ok; decision.complete(ok) } }
    }
}

/** Chat with Claude that controls the player through [AssistantTools]. The conversation lives until the app is closed. */
@UnstableApi
class Assistant(private val ui: Ui) {
    private val prefs = ui.context.getSharedPreferences("assistant", android.content.Context.MODE_PRIVATE)
    var apiKey: String
        get() = prefs.getString("apiKey", "")!!
        set(v) { prefs.edit().putString("apiKey", v.trim()).apply(); client = null }

    val entries = mutableStateListOf<ChatEntry>()
    var busy by mutableStateOf(false); private set
    /** Messages written while the assistant is busy, sent together when its turn ends. */
    val queued = mutableStateListOf<String>()

    private var client: AnthropicClient? = null
    private val messages = ArrayList<MessageParam>()
    private var tools: AssistantTools? = null
    private var system = ""
    private var confirmWait = 0L
    private var job: Job? = null

    fun clear() {
        if (busy) return
        entries.clear(); messages.clear(); tools = null
    }

    fun stop() { job?.cancel() }

    fun send(text: String) {
        val cat = ui.app.catalog.value
        if (text.isBlank() || cat == null) return
        if (busy) { queued.add(text.trim()); return }
        // Handles are catalog indices: a reloaded catalog starts a new conversation.
        if (tools?.let { it.cat !== cat } != false) {
            messages.clear()
            tools = AssistantTools(ui, cat).also { system = SYSTEM + "\n\n" + it.librarySummary() }
        }
        val t = tools!!
        entries.add(ChatEntry.User(text.trim()))
        val start = messages.size
        messages.add(MessageParam.ofUser(t.playerState() + "\n" + text.trim()))
        busy = true
        val started = SystemClock.elapsedRealtime()
        confirmWait = 0L
        job = ui.scope.launch {
            try {
                converse(t)
                entries.add(ChatEntry.Took(SystemClock.elapsedRealtime() - started - confirmWait))
            } catch (e: CancellationException) {
                // Keep the user's message so a correction sent next is read with it.
                while (messages.size > start + 1) messages.removeAt(messages.size - 1)
                entries.filterIsInstance<ChatEntry.Confirm>().forEach { it.answer(false) }
                entries.add(ChatEntry.Reply("Stopped", error = true))
            } catch (e: Exception) {
                AppLog.w("assistant", "Request failed", e)
                // Drop the unfinished turn, so the next message starts from a valid conversation.
                while (messages.size > start) messages.removeAt(messages.size - 1)
                entries.add(ChatEntry.Reply(e.message ?: e.toString(), error = true))
                entries.add(ChatEntry.Took(SystemClock.elapsedRealtime() - started - confirmWait))
            } finally {
                busy = false
                if (queued.isNotEmpty()) send(queued.joinToString("\n").also { queued.clear() })
            }
        }
    }

    private suspend fun converse(t: AssistantTools) {
        val c = client ?: AnthropicOkHttpClient.builder().apiKey(apiKey).build().also { client = it }
        while (true) {
            val params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(16000L)
                .system(system)
                .cacheControl(CacheControlEphemeral.builder().build())
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.MEDIUM).build())
                .apply { TOOLS.forEach { addTool(it) } }
                .addTool(WebSearchTool20260209.builder().maxUses(3L).build())
                // A request the model refuses is retried on a fallback model chosen by the API.
                .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .messages(messages.toList())
                .build()
            // Async, so that Stop doesn't wait for the response.
            val response = c.async().messages().create(params).await()
            AppLog.i("assistant", usage(response.usage()))
            messages.add(response.toParam())
            response.content().mapNotNull { it.text().orElse(null)?.text()?.trim() }.filter { it.isNotEmpty() }
                .forEach { entries.add(ChatEntry.Reply(it)) }
            when (response.stopReason().orElse(null)) {
                StopReason.TOOL_USE -> Unit
                StopReason.PAUSE_TURN -> continue   // a long web search: send the conversation back to let it finish
                StopReason.REFUSAL -> { entries.add(ChatEntry.Reply("Claude declined this request", error = true)); return }
                StopReason.MAX_TOKENS -> { entries.add(ChatEntry.Reply("The answer was cut off", error = true)); return }
                else -> return
            }
            val results = response.content().mapNotNull { it.toolUse().orElse(null) }.map { use ->
                val input = JSONObject(jsonMapper().writeValueAsString(use._input()))
                AppLog.d("assistant", "${use.name()} $input")
                val out = runCatching { runTool(t, use.name(), input) }
                    .getOrElse { AppLog.w("assistant", "Tool ${use.name()} failed", it); "Error: ${it.message}" }
                ContentBlockParam.ofToolResult(ToolResultBlockParam.builder().toolUseId(use.id()).content(out).build())
            }
            messages.add(MessageParam.ofUserOfContentBlockParams(results))
        }
    }

    private suspend fun runTool(t: AssistantTools, name: String, input: JSONObject): String {
        // Searches go over 18k tracks: off the main thread. The rest touches the player.
        val r = if (name.startsWith("search") || name == "listening_stats") withContext(Dispatchers.Default) { t.run(name, input) } else t.run(name, input)
        if (r !is AssistantTools.Change) return r as String
        val confirm = ChatEntry.Confirm(r)
        entries.add(confirm)
        val asked = SystemClock.elapsedRealtime()
        val ok = confirm.decision.await()
        confirmWait += SystemClock.elapsedRealtime() - asked
        return if (ok) r.apply() else "The user cancelled this change"
    }

    companion object {
        const val MODEL = "claude-sonnet-5-5"

        /** Token counts of a response for the log, to see what a request costs and whether the cache works. */
        fun usage(u: Usage) = "tokens: in ${u.inputTokens()}, cache read ${u.cacheReadInputTokens().orElse(0)}, " +
            "cache write ${u.cacheCreationInputTokens().orElse(0)}, out ${u.outputTokens()}"

        private val TOOLS = AssistantTools.DEFINITIONS.map { (name, description, schema) ->
            val node = jsonMapper().readTree(schema)
            val props = Tool.InputSchema.Properties.builder()
            node["properties"].fields().forEach { (k, v) -> props.putAdditionalProperty(k, JsonValue.fromJsonNode(v)) }
            Tool.builder().name(name).description(description)
                .inputSchema(Tool.InputSchema.builder().properties(props.build()).required(node["required"]?.map { it.asText() } ?: emptyList()).build())
                .build()
        }

        private val SYSTEM = """
            You are the assistant inside B2 Player, the user's personal music player on Android. The library is the user's own
            collection; you control the player with the tools. Reply in the user's language (usually Russian), briefly: the chat
            is small. Simple Markdown is shown: **bold**, *italic*, `code`, lists, headings and links.

            - Act, don't ask: "включи альбом Янки" means find a Янка Дягилева album and play it. Ask only when the request is
              truly ambiguous and a wrong guess would be annoying.
            - Find tracks and albums with the search tools; the handles they return (t…, a…) are what the other tools take.
              Artist and album names in the library may be in Russian or English: try both spellings if nothing is found.
            - To pick music by mood ("что-нибудь грустное"), search the whole library, not only liked tracks, choose by what you
              know about the songs and artists, and play your choice. Say in one line what you put on. Use liked_only only
              when the user asks for liked music: the queue has its own liked filter.
            - Likes and playlists are the user's data: set_liked, create_playlist and add_to_playlist show the user the change,
              and the tool result says whether they applied or cancelled it. Don't ask for confirmation in text as well.
            - Each user message starts with the date and what is playing, in brackets.
            - Use web search for questions about music, artists and albums that you can't answer confidently.
        """.trimIndent()
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
            items(a.entries) { e -> ChatBubble(e) }
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
private fun ChatBubble(e: ChatEntry) {
    when (e) {
        is ChatEntry.User -> Box(Modifier.fillMaxWidth(), Alignment.CenterEnd) {
            SelectionContainer { Text(e.text, Modifier.clip(RoundedCornerShape(18.dp)).background(Gray2).padding(14.dp, 9.dp)) }
        }
        is ChatEntry.Reply -> SelectionContainer {
            if (e.error) Text(e.text, color = MaterialTheme.colorScheme.error)
            else Text(remember(e.text) { markdown(e.text) })
        }
        is ChatEntry.Took -> Text(if (e.ms < 1000) "${e.ms} ms" else "${e.ms / 100 / 10.0} s",
            style = MaterialTheme.typography.labelSmall, color = Muted)
        is ChatEntry.Confirm -> Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Gray1).padding(10.dp)) {
            Text(e.change.title, fontWeight = FontWeight.Bold)
            e.change.tracks.take(12).forEach { Text("${it.artist} — ${it.title}", style = MaterialTheme.typography.bodySmall, color = Muted) }
            if (e.change.tracks.size > 12) Text("and ${e.change.tracks.size - 12} more", style = MaterialTheme.typography.bodySmall, color = Muted)
            Spacer(Modifier.height(6.dp))
            when (e.answered) {
                null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Apply", active = true) { e.answer(true) }
                    Pill("Cancel") { e.answer(false) }
                }
                true -> Text("Applied", color = MaterialTheme.colorScheme.primary)
                false -> Text("Cancelled", color = Muted)
            }
        }
    }
}

private val inlineMarkdown = Regex("""\*\*(.+?)\*\*|\*(\S(?:.*?\S)?)\*|`([^`]+)`|\[([^\]]+)]\(([^)\s]+)\)""")
private val bullet = Regex("""^(\s*)[-*+]\s+""")
private val heading = Regex("""^#{1,6}\s+""")

/** The Markdown a chat reply needs: headings, bullets, **bold**, *italic*, `code` and [links](url). */
private fun markdown(text: String): AnnotatedString = buildAnnotatedString {
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
                else -> withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline)))) { append(label) }
            }
            pos = m.range.last + 1
        }
        append(line, pos, line.length)
        if (h != null) addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, length)
    }
}
