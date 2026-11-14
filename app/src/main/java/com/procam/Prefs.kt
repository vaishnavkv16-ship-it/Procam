package com.procam

import android.content.Context

class Setting(val key: String, val title: String, val options: List<String>, val def: Int, val section: String)

object Cfg {
    val list = listOf(
        Setting("jpegq", "JPEG quality", listOf("80", "90", "95", "100"), 3, "Photo"),
        Setting("photores", "Default photo size", listOf("Maximum", "12 MP", "8 MP"), 0, "Photo"),
        Setting("raw", "Save RAW (DNG) with photos", listOf("Off", "On"), 0, "Photo"),
        Setting("sound", "Shutter sound", listOf("On", "Off"), 0, "Photo"),
        Setting("volkey", "Volume buttons", listOf("Shutter", "Off"), 0, "Photo"),
        Setting("grid", "Grid lines", listOf("Off", "3x3", "4x4"), 0, "Photo"),
        Setting("vres", "Video resolution", listOf("8K", "4K", "1080p", "720p"), 1, "Video"),
        Setting("vfps", "Video frame rate", listOf("24", "30", "60"), 1, "Video"),
        Setting("vbit", "Video bitrate (Mbps)", listOf("20", "35", "50", "80", "100", "150"), 2, "Video"),
        Setting("vcodec", "Video codec", listOf("HEVC", "H.264"), 0, "Video"),
        Setting("vaudio", "Record audio", listOf("On", "Off"), 0, "Video"),
        Setting("vstab", "Stabilization (OIS / EIS)", listOf("Best", "Off"), 0, "Video"),
        Setting("pblur", "Portrait blur strength", listOf("Low", "Medium", "High", "Max"), 1, "Portrait"),
        Setting("aframes", "Number of frames", listOf("8", "12", "16", "24"), 2, "Astrophotography"),
        Setting("aexp", "Exposure per frame (sec)", listOf("5", "10", "15", "20", "30"), 2, "Astrophotography"),
        Setting("aiso", "ISO", listOf("800", "1600", "3200", "6400"), 1, "Astrophotography"),
        Setting("accent", "Accent colour", listOf("Yellow", "Blue", "Green", "Orange"), 0, "Interface")
    )
}

class Prefs(c: Context) {
    private val sp = c.getSharedPreferences("procam", Context.MODE_PRIVATE)
    private fun spec(key: String) = Cfg.list.first { it.key == key }
    fun idx(key: String): Int = sp.getInt(key, spec(key).def).coerceIn(0, spec(key).options.size - 1)
    fun set(key: String, v: Int) { sp.edit().putInt(key, v).apply() }
    fun value(key: String): String = spec(key).options[idx(key)]
    fun int(key: String): Int = value(key).toIntOrNull() ?: 0
    fun reset() { sp.edit().clear().apply() }
}
