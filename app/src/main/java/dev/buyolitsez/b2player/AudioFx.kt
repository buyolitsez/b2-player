package dev.buyolitsez.b2player

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Equalizer and bass boost on the player's audio session; settings survive restarts. */
class AudioFx(context: Context) {
    private val p = context.getSharedPreferences("audiofx", Context.MODE_PRIVATE)
    private var eq: Equalizer? = null
    private var bass: BassBoost? = null

    var enabled by mutableStateOf(p.getBoolean("enabled", false)); private set
    /** -1 = custom band levels. */
    var preset by mutableIntStateOf(p.getInt("preset", -1)); private set
    var bassStrength by mutableIntStateOf(p.getInt("bass", 0)); private set
    var levels by mutableStateOf(List(0) { 0 }); private set
    var bands by mutableStateOf(listOf<Int>()); private set   // center frequencies, Hz
    var range by mutableStateOf(-1500..1500); private set      // millibels
    var presets by mutableStateOf(listOf<String>()); private set
    val available get() = eq != null

    fun attach(sessionId: Int) {
        release()
        try {
            val e = Equalizer(0, sessionId)
            eq = e
            bands = (0 until e.numberOfBands).map { e.getCenterFreq(it.toShort()) / 1000 }
            range = e.bandLevelRange.let { it[0].toInt()..it[1].toInt() }
            presets = (0 until e.numberOfPresets).map { e.getPresetName(it.toShort()) }
            val saved = p.getString("levels", null)?.split(',')?.mapNotNull { it.toIntOrNull() }
            if (preset >= 0 && preset < presets.size) e.usePreset(preset.toShort())
            else if (saved != null && saved.size == bands.size) saved.forEachIndexed { i, l -> e.setBandLevel(i.toShort(), l.toShort()) }
            levels = bands.indices.map { e.getBandLevel(it.toShort()).toInt() }
            e.enabled = enabled
            bass = runCatching { BassBoost(0, sessionId).apply { if (strengthSupported) setStrength(bassStrength.toShort()); this.enabled = this@AudioFx.enabled && bassStrength > 0 } }.getOrNull()
            AppLog.d("fx", "Attached to session $sessionId, ${bands.size} bands")
        } catch (e: Exception) {
            AppLog.w("fx", "Equalizer is not available", e)
            eq = null
        }
    }

    fun switch(v: Boolean) {
        enabled = v; p.edit().putBoolean("enabled", v).apply()
        eq?.enabled = v
        bass?.enabled = v && bassStrength > 0
    }

    fun usePreset(i: Int) {
        val e = eq ?: return
        e.usePreset(i.toShort())
        preset = i; levels = bands.indices.map { e.getBandLevel(it.toShort()).toInt() }
        p.edit().putInt("preset", i).apply()
    }

    fun setLevel(band: Int, level: Int) {
        val e = eq ?: return
        e.setBandLevel(band.toShort(), level.toShort())
        levels = levels.toMutableList().also { it[band] = level }
        preset = -1
        p.edit().putInt("preset", -1).putString("levels", levels.joinToString(",")).apply()
    }

    fun setBass(strength: Int) {
        bassStrength = strength
        p.edit().putInt("bass", strength).apply()
        bass?.run { if (strengthSupported) setStrength(strength.toShort()); this.enabled = this@AudioFx.enabled && strength > 0 }
    }

    fun reset() {
        bands.indices.forEach { setLevel(it, 0) }
        setBass(0)
    }

    fun release() {
        runCatching { eq?.release() }; runCatching { bass?.release() }
        eq = null; bass = null
    }
}
