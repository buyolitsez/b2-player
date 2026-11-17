package dev.buyolitsez.b2player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Albums from Soulseek, through the computer: `cmus-b2-add --serve` there (cmus contrib/cmus-b2-phone.service),
 * reached over Tailscale. It searches with slskd, asks Claude where the picked albums go in the library,
 * and downloads and adds them to the bucket and the catalog in the background, as cmus does.
 */
@UnstableApi
class Soulseek(private val ui: Ui) {
    class Result(val index: Int, val name: String, val folder: String, val user: String, val kind: String, val queue: Int?, val files: List<Pair<String, Long>>) {
        val tracks get() = files.count { it.first.substringAfterLast('.').lowercase() !in COVER_EXT }
    }
    class Album(val name: String, val target: String?, val note: String)
    class Plan(val id: String, val albums: List<Album>)
    class Download(val source: String, val albums: List<String>, val status: String) {
        val ended get() = status.startsWith("done") || status.startsWith("error")
    }

    var query by mutableStateOf("")
        private set
    private var searchId = ""
    var results by mutableStateOf<List<Result>?>(null)
        private set
    val picked = mutableStateListOf<Int>()
    /** Searching, asking Claude or starting: what to show while it runs. */
    var busy by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var plan by mutableStateOf<Plan?>(null)
    var downloads by mutableStateOf<List<Download>>(emptyList())
        private set

    fun search(q: String) {
        query = q; results = null; picked.clear(); plan = null
        run("Searching Soulseek… It takes about 20 seconds") {
            val r = call("search", JSONObject().put("query", q))
            searchId = r.getString("search")
            results = r.getJSONArray("results").objects().mapIndexed { i, o ->
                Result(i, o.getString("name"), o.getString("folder"), o.getString("user"), o.getString("kind"),
                    if (o.isNull("queue")) null else o.getInt("queue"),
                    o.getJSONArray("files").objects().map { it.getString("rel") to it.getLong("size") })
            }
        }
    }

    /** Asks Claude where the picked albums go, or, with [feedback], to change the plan. */
    fun plan(feedback: String? = null) = run("Asking Claude where to put them…") {
        val body = plan?.takeIf { feedback != null }?.let { JSONObject().put("plan", it.id).put("feedback", feedback) }
            ?: JSONObject().put("search", searchId).put("picks", JSONArray(picked.sorted()))
        val r = call("plan", body)
        plan = Plan(r.getString("plan"), r.getJSONArray("albums").objects().map {
            Album(it.getString("name"), it.optString("target").takeIf { t -> t.isNotEmpty() && t != "null" }, it.getString("note"))
        })
    }

    fun start() {
        val p = plan ?: return
        plan = null
        run("Starting the download…") {
            call("start", JSONObject().put("plan", p.id))
            picked.clear()
            ui.toast("Downloading on the computer. The albums show up in the library once added")
            refreshDownloads()
        }
    }

    /** The computer's downloads; one that just ended reloads the library, which has the new albums now. */
    suspend fun refreshDownloads() {
        val now = runCatching { call("jobs", null).getJSONArray("jobs").objects().map { o ->
            Download(o.getString("source"), o.getJSONArray("albums").let { a -> List(a.length()) { a.getString(it) } }, o.getString("status"))
        } }.getOrNull() ?: return
        val wasRunning = downloads.filter { !it.ended }.map { it.albums }.toSet()
        if (now.any { it.ended && it.status.startsWith("done") && it.albums in wasRunning }) ui.refreshCatalog()
        downloads = now
    }

    private fun run(what: String, block: suspend () -> Unit) {
        if (busy != null) return
        busy = what; error = null
        ui.scope.launch {
            try { block() }
            catch (e: IOException) { error = "Can't reach the computer at ${ui.prefs.soulseekServer.value}: ${e.message}. Is Tailscale on?" }
            catch (e: Exception) { error = e.message ?: e.toString(); AppLog.w("soulseek", "Request failed", e) }
            finally { busy = null }
        }
    }

    /** A request to the computer; its error message comes back as an exception. */
    private suspend fun call(path: String, body: JSONObject?): JSONObject = withContext(Dispatchers.IO) {
        val c = URL("http://${ui.prefs.soulseekServer.value.trim()}/$path").openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 180_000  // a search and Claude's answer take a while
        try {
            if (body != null) {
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val ok = c.responseCode == 200
            val text = (if (ok) c.inputStream else c.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            if (!ok) throw RuntimeException(json?.optString("error")?.takeIf { it.isNotEmpty() } ?: "HTTP ${c.responseCode}")
            json ?: throw RuntimeException("Unexpected answer from the computer")
        } finally { c.disconnect() }
    }

    private fun JSONArray.objects() = List(length()) { getJSONObject(it) }

    companion object { private val COVER_EXT = setOf("jpg", "jpeg", "png") }
}

@UnstableApi
@Composable
fun SoulseekScreen(ui: Ui, query: String) {
    val s = ui.soulseek
    LaunchedEffect(query) { if (s.query != query) s.search(query) }
    LaunchedEffect(Unit) { while (true) { s.refreshDownloads(); delay(3000) } }
    var open by remember { mutableStateOf(setOf<Int>()) }
    Column {
        ScreenTitle("Soulseek", "“$query”")
        if (s.picked.isNotEmpty()) Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill(if (s.picked.size == 1) "Add 1 album" else "Add ${s.picked.size} albums", Icons.Default.Download, active = true) { s.plan() }
            Spacer(Modifier.width(8.dp))
            Pill("Clear") { s.picked.clear() }
        }
        s.busy?.let {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(it, Modifier.padding(start = 12.dp), color = Muted)
            }
        }
        s.error?.let {
            Text(it, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            if (s.results == null) Box(Modifier.padding(horizontal = 20.dp)) { Pill("Try again", Icons.Default.Refresh) { s.search(query) } }
        }
        LazyColumn(contentPadding = listPadding()) {
            if (s.downloads.isNotEmpty()) {
                item { SectionTitle("Downloads on the computer") }
                items(s.downloads) { d ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Text(d.albums.joinToString { it.substringAfter('/') }, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${d.source} · ${d.status}", style = MaterialTheme.typography.bodySmall,
                            color = if (d.status.startsWith("error")) MaterialTheme.colorScheme.error else Muted)
                    }
                }
            }
            val results = s.results
            if (results != null) {
                item { SectionTitle(if (results.isEmpty()) "Nothing found" else "Found · tap to pick") }
                items(results, key = { it.index }) { r ->
                    val picked = r.index in s.picked
                    val queue = r.queue?.let { " · queue $it" } ?: ""
                    ListRow(
                        if (picked) Icons.Default.CheckCircle else Icons.Default.Album,
                        r.folder.split('/').takeLast(2).joinToString(" / "),
                        "${r.kind} · ${r.tracks} tracks · ${formatSize(r.files.sumOf { it.second })} · ${r.user}$queue",
                        trailing = {
                            IconButton({ open = if (r.index in open) open - r.index else open + r.index }) {
                                Icon(if (r.index in open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, "Files")
                            }
                        },
                    ) { if (picked) s.picked.remove(r.index) else s.picked.add(r.index) }
                    if (r.index in open) Column(Modifier.padding(start = 78.dp, end = 20.dp, bottom = 8.dp)) {
                        r.files.forEach { (name, size) ->
                            Row {
                                Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(formatSize(size), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall, color = Muted)
                            }
                        }
                    }
                }
            }
        }
    }
    s.plan?.let { PlanDialog(s, it) }
}

/** Claude's plan: where each album goes in the library. Add starts the download; a correction in words asks again. */
@UnstableApi
@Composable
private fun PlanDialog(s: Soulseek, p: Soulseek.Plan) {
    var feedback by remember(p) { mutableStateOf("") }
    val adding = p.albums.any { it.target != null }
    AlertDialog(
        { s.plan = null }, containerColor = Gray2, title = { Text("Add to the library") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                p.albums.forEach { a ->
                    Column {
                        Text(a.name, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(a.target ?: "Skip", style = MaterialTheme.typography.bodyLarge)
                        Text(a.note, style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                }
                OutlinedTextField(feedback, { feedback = it }, Modifier.fillMaxWidth(), placeholder = { Text("Tell Claude what to change") })
                s.busy?.let { Text(it, color = Muted) }
                s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (s.busy != null) Unit
            else if (feedback.isNotBlank()) TextButton({ s.plan(feedback.trim()) }) { Text("Ask again") }
            else if (adding) TextButton({ s.start() }) { Text("Add") }
        },
        dismissButton = { TextButton({ s.plan = null }) { Text("Cancel") } },
    )
}
