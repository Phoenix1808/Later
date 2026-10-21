package com.example.later

import android.content.Context
import androidx.core.content.edit
import com.google.gson.Gson

// Everything Later knows, kept on the phone. Both the screen and the background
// workers use it, so every read-modify-write goes through one lock.
class ShotStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("later", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun all(): List<Shot> = synchronized(LOCK) { load() }

    // Adds screenshots we haven't seen before; returns how many were new
    fun addNew(found: List<Shot>): Int = synchronized(LOCK) {
        val current = load()
        val known = current.map { it.mediaId }.toSet()
        val fresh = found.filter { it.mediaId !in known }
        if (fresh.isNotEmpty()) save(current + fresh)
        fresh.size
    }

    fun update(mediaId: Long, change: (Shot) -> Shot) = synchronized(LOCK) {
        save(load().map { if (it.mediaId == mediaId) change(it) else it })
    }

    fun remove(mediaId: Long) = synchronized(LOCK) {
        save(load().filterNot { it.mediaId == mediaId })
    }

    // Newest screenshot time we've already looked at (MediaStore seconds)
    var lastSeenSec: Long
        get() = prefs.getLong(KEY_LAST_SEEN, 0L)
        set(value) = prefs.edit { putLong(KEY_LAST_SEEN, value) }

    var darkMode: Boolean
        get() = prefs.getBoolean(KEY_DARK_MODE, false)
        set(value) = prefs.edit { putBoolean(KEY_DARK_MODE, value) }

    private fun load(): List<Shot> =
        prefs.getString(KEY_SHOTS, null)
            ?.let { gson.fromJson(it, Array<Shot>::class.java).toList() }
            .orEmpty()

    private fun save(shots: List<Shot>) {
        // Keep the newest 300 so the list never grows without limit
        val trimmed = shots.sortedByDescending { it.takenAtSec }.take(300)
        prefs.edit { putString(KEY_SHOTS, gson.toJson(trimmed)) }
    }

    companion object {
        private val LOCK = Any()
        private const val KEY_SHOTS = "shots"
        private const val KEY_LAST_SEEN = "last_seen_sec"
        private const val KEY_DARK_MODE = "dark_mode"
    }
}
