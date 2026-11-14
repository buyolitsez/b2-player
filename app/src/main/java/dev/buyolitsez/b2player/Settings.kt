package dev.buyolitsez.b2player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Key
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi

@UnstableApi
@Composable
fun SettingsScreen(ui: Ui, cat: Catalog?) {
    val app = ui.app
    val p = app.prefs
    ui.version
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = LocalListBottom.current + 24.dp)) {
        ScreenTitle("Settings")
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Gray1)
                .clickable { ui.open(Screen.Accounts) }.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0xFF262626)), Alignment.Center) { Icon(Icons.Default.Key, null, Modifier.size(20.dp)) }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text("Accounts", style = MaterialTheme.typography.titleMedium)
                Text("Backblaze B2 · Last.fm · Claude", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Color(0xFF6E6E6E))
        }

        // ---------- playback ----------
        SettingsGroup("Playback") {
        SettingChoice("Preload upcoming tracks", "Downloads the next tracks of the queue into the cache, for weak networks",
            listOf(0, 1, 2, 3, 5), p.prefetchCount.value, { if (it == 0) "Off" else "$it tracks" }) { p.prefetchCount.set(it) }
        SettingSwitch("Skip silence", "Cut silent parts inside tracks", p.skipSilence)
        SettingSwitch("Restore queue on start", "Continue where you stopped after the app was closed", p.restoreQueue)
        SettingSwitch("Offer undo when the queue is replaced", "Shows “Undo” after you start another album", p.undoReplace)
        }

        // ---------- downloads ----------
        SettingsGroup("Downloads") {
        SettingSwitch("Download liked tracks", "Liking a track downloads it", p.autoDownloadLiked)
        SettingSwitch("…only on Wi-Fi", "Automatic downloads wait for an unmetered network", p.autoWifiOnly, enabled = p.autoDownloadLiked.value)
        SettingSwitch("Remove download when unliked", "Unliking deletes the copy on the phone", p.deleteOnUnlike)
        SettingSwitch("Manual downloads only on Wi-Fi", "Download buttons wait for Wi-Fi", p.manualWifiOnly)
        if (cat != null) {
            val missing = remember(cat, ui.version) { cat.tracks.filter { ui.library.isLiked(it) && !ui.library.isDownloaded(it) } }
            SettingAction(
                "Download all liked now",
                if (missing.isEmpty()) "Everything liked is on the phone" else "${missing.size} tracks, ${formatSize(missing.distinctBy { it.path }.sumOf { it.size })}",
            ) {
                if (missing.isNotEmpty()) {
                    DownloadWorker.enqueue(ui.context, "liked-all", "Liked", missing, wifiOnly = p.autoWifiOnly.value)
                    ui.toast("Downloading liked tracks")
                }
            }
        }
        SettingAction("Downloads", "Progress and files on this phone") { ui.open(Screen.Downloads) }
        }

        // ---------- library ----------
        SettingsGroup("Library") {
        SettingSwitch("Show albums as a grid", null, p.albumGrid)
        SettingChoice("Grid columns", null, listOf(2, 3, 4), p.gridColumns.value, { "$it" }) { p.gridColumns.set(it) }
        SettingChoice("Open on start", null, Tab.entries.map { it.name }, Tab.entries.firstOrNull { it.name == p.startTab.value }?.name ?: Tab.Library.name,
            { n -> Tab.valueOf(n).title }) { p.startTab.set(it) }
        SettingSwitch("Swipe actions", "Swipe right: play next; swipe left: add to queue or remove", p.swipeActions)
        SettingSwitch("Colors from album art", "Tint the player with the cover of the current album", p.coverTint)
        SettingAction("Reload library", cat?.let { "${it.albums.size} albums, ${it.tracks.size} tracks" }) { ui.refreshCatalog() }
        }

        // ---------- history ----------
        SettingsGroup("History") {
        SettingSwitch("Keep listening history", "Used for stats; synced to the bucket", p.recordHistory)
        var confirmClear by remember { mutableStateOf(false) }
        SettingAction("Clear history on this phone", "${plays(app.history.all().size)}. The copy in the bucket stays") { confirmClear = true }
        if (confirmClear) AlertDialog(
            { confirmClear = false }, containerColor = Gray2, title = { Text("Clear local history?") },
            text = { Text("It comes back from the bucket on the next sync unless you delete state/history.tsv there.") },
            confirmButton = { TextButton({ app.history.clear(); ui.version++; confirmClear = false }) { Text("Clear") } },
            dismissButton = { TextButton({ confirmClear = false }) { Text("Cancel") } },
        )
        }

        // ---------- lyrics and controls ----------
        SettingsGroup("Lyrics and controls") {
        SettingSwitch("Online lyrics", "When the file has no synced lyrics, look them up on lrclib.net (sends artist and title)", p.onlineLyrics)
        SettingAction("Clear lyrics cache", "Look the lyrics up again") { app.lyrics.clearCache(); ui.toast("Lyrics cache cleared") }
        SettingSwitch("Headset button gestures", "Press twice: next track; three times: previous", p.headsetGestures)
        SettingAction("Equalizer", if (app.audioFx.enabled) "On" else "Off") { ui.open(Screen.EqualizerView) }
        }

        // ---------- storage ----------
        SettingsGroup("Storage") {
        var cacheGb by remember { mutableFloatStateOf(p.cacheGb.value.toFloat()) }
        Column(Modifier.fillMaxWidth().background(Gray1).padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text("Stream cache: ${cacheGb.toInt()} GB")
            Text("Used ${formatSize(ui.library.cacheBytes())}. A new size applies after the app restarts",
                style = MaterialTheme.typography.bodySmall, color = Muted)
            Slider(cacheGb, { cacheGb = it }, valueRange = 1f..50f, steps = 48, onValueChangeFinished = { p.cacheGb.set(cacheGb.toInt()) })
        }
        SettingAction("Clear stream cache", "Downloads are kept") { ui.library.clearCache(); ui.version++; ui.toast("Cache cleared") }
        }

        SettingsGroup("Diagnostics") {
        SettingSwitch("Detailed logs", "Also log every track change and action; applies after restart", p.verboseLogs)
        SettingSwitch("Upload logs after problems", "Copies the log to logs/<date>.log in the bucket when something failed", p.uploadLogs)
        SettingAction("Upload logs now", "Log on the phone: ${formatSize(AppLog.sizeBytes())}, never more than about 1.5 MB") {
            AppLog.i("app", "Manual log upload"); app.uploadLogs(force = true); ui.toast("Uploading logs")
        }
        SettingAction("Clear logs", null) { AppLog.clear(); ui.version++; ui.toast("Logs cleared") }
        }

        Text("B2 Player ${ui.context.packageManager.getPackageInfo(ui.context.packageName, 0).versionName}",
            Modifier.padding(20.dp, 24.dp), color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

/** Keys and sign-ins: Backblaze B2, Last.fm and the assistant's Claude key. */
@UnstableApi
@Composable
fun AccountsScreen(ui: Ui) {
    val app = ui.app
    ui.version
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = LocalListBottom.current + 24.dp)) {
        ScreenTitle("Accounts")
        SettingsGroup("Backblaze B2") {
            val s = remember { app.b2.settings }
            var keyId by remember { mutableStateOf(s.keyId) }
            var appKey by remember { mutableStateOf(s.appKey) }
            var bucket by remember { mutableStateOf(s.bucket) }
            FieldsBlock {
                OutlinedTextField(keyId, { keyId = it }, Modifier.fillMaxWidth(), label = { Text("keyID") }, singleLine = true)
                OutlinedTextField(appKey, { appKey = it }, Modifier.fillMaxWidth(), label = { Text("applicationKey") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(bucket, { bucket = it }, Modifier.fillMaxWidth(), label = { Text("Bucket") }, singleLine = true)
                Pill("Save and reload library", active = true) {
                    app.b2.settings = B2.Settings(keyId, appKey, bucket); ui.refreshCatalog(); ui.toast("Saved")
                }
            }
        }
        // ---------- Last.fm ----------
        SettingsGroup("Last.fm") {
        val lf = app.lastFm
        if (lf.connected) {
            SettingSwitch("Scrobble", "After half the track or 4 minutes", lf.scrobbleEnabled) { lf.scrobbleEnabled = it; ui.version++ }
            SettingSwitch("Send “Now playing”", null, lf.nowPlayingEnabled) { lf.nowPlayingEnabled = it; ui.version++ }
            SettingAction("Signed in as ${lf.user}", "${lf.pendingCount} scrobbles waiting to be sent. Tap to sign out") { ui.lastFmLogout() }
        } else {
            var apiKey by remember { mutableStateOf(lf.apiKey) }
            var secret by remember { mutableStateOf(lf.secret) }
            var user by remember { mutableStateOf("") }
            var pass by remember { mutableStateOf("") }
            FieldsBlock {
                Text("Create an API account at last.fm/api/account/create, then enter its key and secret.",
                    style = MaterialTheme.typography.bodySmall, color = Muted)
                OutlinedTextField(apiKey, { apiKey = it }, Modifier.fillMaxWidth(), label = { Text("API key") }, singleLine = true)
                OutlinedTextField(secret, { secret = it }, Modifier.fillMaxWidth(), label = { Text("Shared secret") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth(), label = { Text("Username") }, singleLine = true)
                OutlinedTextField(pass, { pass = it }, Modifier.fillMaxWidth(), label = { Text("Password (not stored)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation())
                Pill("Connect", active = true) { ui.lastFmLogin(apiKey, secret, user, pass); pass = "" }
            }
        }

        // ---------- assistant ----------
        }
        SettingsGroup("Assistant") {
        var claudeKey by remember { mutableStateOf(ui.assistant.apiKey) }
        FieldsBlock {
            Text("Chat that controls the player, on ${Assistant.MODEL}. Create a key at platform.claude.com; requests are billed to it.",
                style = MaterialTheme.typography.bodySmall, color = Muted)
            OutlinedTextField(claudeKey, { claudeKey = it }, Modifier.fillMaxWidth(), label = { Text("Claude API key") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation())
            Pill("Save", active = true) { ui.assistant.apiKey = claudeKey; ui.version++; ui.toast("Saved") }
        }
        }
    }
}

/** Text fields and their button inside a settings group. */
@Composable
fun FieldsBlock(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().background(Gray1).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)

/** A titled card of settings rows; the 1 dp gaps between rows show as dividers. */
@Composable
fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    SectionTitle(title)
    Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF222222)),
        verticalArrangement = Arrangement.spacedBy(1.dp), content = content,
    )
}

@Composable
fun SettingSwitch(title: String, subtitle: String?, value: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Gray1).clickable(enabled) { onChange(!value) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else Muted)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        val accent = MaterialTheme.colorScheme.primary
        Switch(value, onChange, enabled = enabled, colors = SwitchDefaults.colors(
            checkedThumbColor = accent, checkedTrackColor = accent.copy(alpha = 0.32f), checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = Color(0xFF7A7A7A), uncheckedTrackColor = Color(0xFF2E2E2E), uncheckedBorderColor = Color.Transparent,
        ))
    }
}

@Composable
fun SettingSwitch(title: String, subtitle: String?, pref: Prefs.Bool, enabled: Boolean = true) =
    SettingSwitch(title, subtitle, pref.value, enabled) { pref.set(it) }

@Composable
fun SettingAction(title: String, subtitle: String?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Gray1).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Muted)
    }
}

@Composable
fun <T> SettingChoice(title: String, subtitle: String?, options: List<T>, value: T, label: (T) -> String, onChange: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().background(Gray1).clickable { open = true }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        Box {
            Text(label(value), style = MaterialTheme.typography.bodyMedium, color = Muted)
            DropdownMenu(open, { open = false }, containerColor = Gray2) {
                options.forEach { o -> DropdownMenuItem({ Text(label(o)) }, { open = false; onChange(o) }) }
            }
        }
    }
}
