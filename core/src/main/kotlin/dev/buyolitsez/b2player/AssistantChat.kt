package dev.buyolitsez.b2player

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext

/** The player the assistant runs in, the phone app or cmus, and the chat that shows it. */
interface AssistantHost {
    /** Where the assistant runs and how its replies are shown: the first lines of the system prompt. */
    val intro: String
    val currentId: String?
    val playing: Boolean
    val queueIds: List<String>
    val currentIndex: Int
    fun play(tracks: List<Track>, shuffled: Boolean)
    fun playNext(tracks: List<Track>)
    fun addToEnd(tracks: List<Track>)
    fun isLiked(t: Track): Boolean
    fun setLiked(tracks: List<Track>, liked: Boolean)
    val playlists: List<Playlist>
    fun createPlaylist(name: String, tracks: List<Track>)
    fun addToPlaylist(p: Playlist, tracks: List<Track>)
    fun history(): List<Play>

    /** A reply of Claude, or an error to show in the chat. */
    fun reply(text: String, error: Boolean = false)
    /** Shows a change to likes or playlists and waits for the user to apply or cancel it. */
    suspend fun confirm(change: AssistantTools.Change): Boolean
    fun log(message: String, error: Throwable? = null)
}

/** Chat with Claude that controls the player through [AssistantTools]. */
class AssistantChat(private val host: AssistantHost) {
    var apiKey = ""
        set(v) { if (v != field) { field = v; client = null } }

    private var client: AnthropicClient? = null
    private val messages = ArrayList<MessageParam>()
    private var tools: AssistantTools? = null
    private var system = ""

    fun clear() { messages.clear(); tools = null }

    /**
     * One turn: the user's message, then Claude and the tools until it answers. A stopped turn keeps
     * the user's message, so a correction sent next is read with it; a failed one is dropped, so the
     * next message starts from a valid conversation.
     */
    suspend fun send(cat: Catalog, text: String) {
        // Handles are catalog indices: a reloaded catalog starts a new conversation.
        if (tools?.let { it.cat !== cat } != false) {
            messages.clear()
            tools = AssistantTools(host, cat).also { system = host.intro + "\n\n" + SYSTEM + "\n\n" + it.librarySummary() }
        }
        val t = tools!!
        val start = messages.size
        messages.add(MessageParam.ofUser(t.playerState() + "\n" + text))
        try {
            converse(t)
        } catch (e: CancellationException) {
            while (messages.size > start + 1) messages.removeAt(messages.size - 1)
            throw e
        } catch (e: Exception) {
            host.log("Request failed", e)
            while (messages.size > start) messages.removeAt(messages.size - 1)
            throw e
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
            host.log(usage(response.usage()))
            messages.add(response.toParam())
            response.content().mapNotNull { it.text().orElse(null)?.text()?.trim() }.filter { it.isNotEmpty() }
                .forEach { host.reply(it) }
            when (response.stopReason().orElse(null)) {
                StopReason.TOOL_USE -> Unit
                StopReason.PAUSE_TURN -> continue   // a long web search: send the conversation back to let it finish
                StopReason.REFUSAL -> { host.reply("Claude declined this request", error = true); return }
                StopReason.MAX_TOKENS -> { host.reply("The answer was cut off", error = true); return }
                else -> return
            }
            val results = response.content().mapNotNull { it.toolUse().orElse(null) }.map { use ->
                val input = jsonMapper().readTree(jsonMapper().writeValueAsString(use._input()))
                host.log("${use.name()} $input")
                val out = runCatching { runTool(t, use.name(), input) }
                    .getOrElse { if (it is CancellationException) throw it; host.log("Tool ${use.name()} failed", it); "Error: ${it.message}" }
                ContentBlockParam.ofToolResult(ToolResultBlockParam.builder().toolUseId(use.id()).content(out).build())
            }
            messages.add(MessageParam.ofUserOfContentBlockParams(results))
        }
    }

    private suspend fun runTool(t: AssistantTools, name: String, input: com.fasterxml.jackson.databind.JsonNode): String {
        // Searches go over 18k tracks: off the main thread. The rest touches the player.
        val r = if (name.startsWith("search") || name == "listening_stats") withContext(Dispatchers.Default) { t.run(name, input) } else t.run(name, input)
        if (r !is AssistantTools.Change) return r as String
        return if (host.confirm(r)) r.apply() else "The user cancelled this change"
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
            The library is the user's own collection; you control the player with the tools. Reply in the user's language
            (usually Russian), briefly.

            - Act, don't ask: "включи альбом Янки" means find a Янка Дягилева album and play it. Ask only when the request is
              truly ambiguous and a wrong guess would be annoying.
            - Find tracks and albums with the search tools; the handles they return (t…, a…) are what the other tools take.
              Artist and album names in the library may be in Russian or English: try both spellings if nothing is found.
            - To pick music by mood ("что-нибудь грустное"), search the whole library, not only liked tracks, choose by what you
              know about the songs and artists, and play your choice. Say in one line what you put on. Use liked_only only
              when the user asks for liked music.
            - Likes and playlists are the user's data: set_liked, create_playlist and add_to_playlist show the user the change,
              and the tool result says whether they applied or cancelled it. Don't ask for confirmation in text as well.
            - Each user message starts with the date and what is playing, in brackets.
            - Use web search for questions about music, artists and albums that you can't answer confidently.
        """.trimIndent()
    }
}
