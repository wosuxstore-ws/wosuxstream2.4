package com.freetv.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/** Tiny settings store: selected source, custom URL, favorites and recently watched channels (stored with logo, so they work across sources), and the Phase 30 settings. */
object Prefs {
    private const val RECENTS_MAX = 20

    private lateinit var sp: SharedPreferences

    // Phase 39: favs is only touched with favsLock held (main thread and Dispatchers.Default both use it).
    private val favsLock = Any()
    private var favs: LinkedHashMap<String, Channel>? = null

    // Phase 39: one background thread for the JSON work that must not run on the main thread (preload + saving the favorites).
    // One thread = the saves run in the order they were queued. The thread is a daemon and only starts with the first task.
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "prefs-io").apply { isDaemon = true }
    }

    // Phase 25: recents are guarded by recentsLock. The cached list is never changed after it is published (copy-on-write),
    // so recents() can be read from any thread while addRecent() runs on another one.
    private val recentsLock = Any()
    private var recentsCache: List<Channel>? = null

    /** Counts addRecent() calls (starts at 0 each app start). Lets a screen see "recents changed" without reading the list. */
    @Volatile
    var recentsVersion: Int = 0
        private set

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences("freetv", Context.MODE_PRIVATE)
    }

    /**
     * Phase 39: parses the favorites and the recents JSON on a background thread, so the first isFavorite() / toggleFavorite() /
     * recents() on any screen is a plain lookup. Call it once, right after init(). Safe to call from any thread.
     */
    fun preload() {
        io.execute {
            try {
                synchronized(favsLock) { loadFavs() }
                synchronized(recentsLock) { loadRecents() }
            } catch (e: Exception) {
                // a failed preload only means the first real use parses the data itself
            }
        }
    }

    var source: Source
        get() = Source(
            sp.getString("src_name", null) ?: Sources.ALL.name,
            sp.getString("src_url", null) ?: Sources.ALL.url
        )
        set(value) {
            sp.edit().putString("src_name", value.name).putString("src_url", value.url).apply()
        }

    var customUrl: String
        get() = sp.getString("custom_url", "") ?: ""
        set(value) {
            sp.edit().putString("custom_url", value).apply()
        }

    // Phase 30: settings (same SharedPreferences file). Plain reads / writes of single values, safe on the main thread (apply() saves in the background).

    /** Hide channels that HealthStore reports as DEAD from the lists. Default false. */
    var hideDead: Boolean
        get() = sp.getBoolean("hide_dead", false)
        set(value) {
            sp.edit().putBoolean("hide_dead", value).apply()
        }

    /** Zapping in the player skips DEAD channels. Default true. */
    var skipDeadOnZap: Boolean
        get() = sp.getBoolean("skip_dead_zap", true)
        set(value) {
            sp.edit().putBoolean("skip_dead_zap", value).apply()
        }

    /** Check channels in the background (lazy health checks). Default true. */
    var autoCheck: Boolean
        get() = sp.getBoolean("auto_check", true)
        set(value) {
            sp.edit().putBoolean("auto_check", value).apply()
        }

    /** Video quality cap: 0 = Auto, 1 = SD max, 2 = 720p max. Default 0. Values outside 0..2 are read and saved as 0. */
    var qualityCap: Int
        get() = sp.getInt("quality_cap", 0).let { if (it in 0..2) it else 0 }
        set(value) {
            sp.edit().putInt("quality_cap", if (value in 0..2) value else 0).apply()
        }

    // Phase 39: all three take favsLock, so a read on Dispatchers.Default can no longer meet a toggle on the main thread half-way.
    fun favorites(): List<Channel> = synchronized(favsLock) { loadFavs().values.toList() }

    fun isFavorite(url: String): Boolean = synchronized(favsLock) { loadFavs().containsKey(url) }

    /**
     * @return true if the channel is now a favorite, false if it was removed.
     * Phase 39: the map changes at once (the answer is right immediately); the JSON is built and saved on the "prefs-io" thread.
     */
    fun toggleFavorite(c: Channel): Boolean = synchronized(favsLock) {
        val map = loadFavs()
        val added = if (map.containsKey(c.url)) {
            map.remove(c.url)
            false
        } else {
            map[c.url] = c
            true
        }
        val snapshot = ArrayList<Channel>(map.values)
        io.execute { save(snapshot) }
        added
    }

    /** Recently watched channels, newest first, at most 20. Parses the JSON on first use: call it off the main thread. */
    fun recents(): List<Channel> = synchronized(recentsLock) { loadRecents() }

    /** Puts [c] first in the recents (moves it when its url is already there), keeps at most 20. Call it off the main thread. */
    fun addRecent(c: Channel) {
        synchronized(recentsLock) {
            val old = loadRecents()
            val next = ArrayList<Channel>(minOf(old.size + 1, RECENTS_MAX))
            next.add(c)
            for (o in old) {
                if (next.size >= RECENTS_MAX) break
                if (o.url != c.url) next.add(o)
            }
            recentsCache = next
            recentsVersion++
            saveRecents(next)
        }
    }

    // Must be called with recentsLock held.
    private fun loadRecents(): List<Channel> {
        recentsCache?.let { return it }
        val list = ArrayList<Channel>()
        val seen = HashSet<String>()
        try {
            val arr = JSONArray(sp.getString("recents", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                if (list.size >= RECENTS_MAX) break
                val o = arr.getJSONObject(i)
                val c = Channel(
                    name = o.getString("n"),
                    url = o.getString("u"),
                    logo = o.optString("l").takeIf { it.isNotBlank() },
                    group = o.optString("g").takeIf { it.isNotBlank() },
                    referrer = o.optString("r").takeIf { it.isNotBlank() },
                    userAgent = o.optString("a").takeIf { it.isNotBlank() }
                )
                if (seen.add(c.url)) list.add(c)
            }
        } catch (e: Exception) {
            // ignore corrupt data
        }
        recentsCache = list
        return list
    }

    private fun saveRecents(list: List<Channel>) {
        val arr = JSONArray()
        for (c in list) {
            val o = JSONObject()
            o.put("n", c.name)
            o.put("u", c.url)
            c.logo?.let { o.put("l", it) }
            c.group?.let { o.put("g", it) }
            c.referrer?.let { o.put("r", it) }
            c.userAgent?.let { o.put("a", it) }
            arr.put(o)
        }
        sp.edit().putString("recents", arr.toString()).apply()
    }

    // Must be called with favsLock held.
    private fun loadFavs(): LinkedHashMap<String, Channel> {
        favs?.let { return it }
        val map = LinkedHashMap<String, Channel>()
        try {
            val arr = JSONArray(sp.getString("favs", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val c = Channel(
                    name = o.getString("n"),
                    url = o.getString("u"),
                    logo = o.optString("l").takeIf { it.isNotBlank() },
                    group = o.optString("g").takeIf { it.isNotBlank() },
                    referrer = o.optString("r").takeIf { it.isNotBlank() },
                    userAgent = o.optString("a").takeIf { it.isNotBlank() }
                )
                map[c.url] = c
            }
        } catch (e: Exception) {
            // ignore corrupt data
        }
        favs = map
        return map
    }

    // Runs on the "prefs-io" thread (Phase 39). Works on its own snapshot, so it needs no lock.
    private fun save(list: List<Channel>) {
        val arr = JSONArray()
        for (c in list) {
            val o = JSONObject()
            o.put("n", c.name)
            o.put("u", c.url)
            c.logo?.let { o.put("l", it) }
            c.group?.let { o.put("g", it) }
            c.referrer?.let { o.put("r", it) }
            c.userAgent?.let { o.put("a", it) }
            arr.put(o)
        }
        sp.edit().putString("favs", arr.toString()).apply()
    }
}
