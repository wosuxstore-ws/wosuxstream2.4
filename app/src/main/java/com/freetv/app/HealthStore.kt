package com.freetv.app

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * Remembers which channels worked or failed, so the app can show / skip them without playing them again.
 * Key = url.hashCode(). Results live in memory and are saved (debounced, in the background) to filesDir/health.txt.
 */
object HealthStore {

    enum class State { UNKNOWN, ALIVE, DEAD }

    /** Goes up by 1 (at most once per second) when a channel's State changed. Collect it to refresh the UI. */
    val version: MutableStateFlow<Int> = MutableStateFlow(0)

    private const val FILE_NAME = "health.txt"
    private const val MAX_ENTRIES = 20_000
    private const val ALIVE_MS = 6L * 60L * 60L * 1000L
    private const val DEAD_MS = 24L * 60L * 60L * 1000L
    private const val DEAD_AFTER_FAILS = 2
    private const val SAVE_DELAY_MS = 3_000L
    private const val BUMP_MS = 1_000L

    private class Entry(val hash: Int, val alive: Boolean, val fails: Int, val ts: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // lock guards: map, dirty, started. Lock order when both are needed: fileLock, then lock.
    private val lock = Any()
    private val fileLock = Any()
    private val saveLock = Any()
    private val bumpLock = Any()

    // Insertion order = order of the last update (an updated entry is removed and re-added), so the first entry is the oldest.
    private var map = LinkedHashMap<Int, Entry>()
    private var dirty = false
    private var started = false

    @Volatile private var loaded = false
    @Volatile private var file: File? = null

    private var saveJob: Job? = null          // guarded by saveLock
    private var bumpScheduled = false         // guarded by bumpLock
    private var lastBump = -BUMP_MS           // guarded by bumpLock (SystemClock.elapsedRealtime() time base)

    /** Call once from Application.onCreate. Loads health.txt on a background thread; until then everything is UNKNOWN. */
    fun init(ctx: Context) {
        val app = ctx.applicationContext
        synchronized(lock) {
            if (started) return
            started = true
        }
        scope.launch {
            val f = File(app.filesDir, FILE_NAME)
            file = f
            val fromFile = readFile(f)
            val needSave = synchronized(lock) {
                // Results marked while the file was still loading are newer: they win and go last.
                val merged = LinkedHashMap<Int, Entry>()
                for (e in fromFile) {
                    if (!map.containsKey(e.hash)) merged[e.hash] = e
                }
                for (e in map.values) merged[e.hash] = e
                trim(merged)
                map = merged
                loaded = true
                dirty
            }
            if (fromFile.isNotEmpty()) notifyChanged()
            if (needSave) requestSave()
        }
    }

    /** ALIVE for 6 h after a success, DEAD for 24 h after 2 failures in a row, otherwise (also before loading) UNKNOWN. */
    fun state(url: String): State {
        if (!loaded) return State.UNKNOWN
        val e = synchronized(lock) { map[url.hashCode()] }
        return stateOf(e, System.currentTimeMillis())
    }

    /** True when the channel has a non-expired ALIVE or DEAD result (one failure so far is not a result yet). */
    fun isFresh(url: String): Boolean = state(url) != State.UNKNOWN

    /** Records a check result. Success resets the fail counter, failure adds 1. Saved 3 s later in the background. */
    fun mark(url: String, alive: Boolean) {
        val key = url.hashCode()
        val now = System.currentTimeMillis()
        val changed = synchronized(lock) {
            val old = map.remove(key)
            val fails = if (alive) 0 else (old?.fails ?: 0) + 1
            val e = Entry(key, alive, fails, now)
            map[key] = e
            trim(map)
            dirty = true
            stateOf(old, now) != stateOf(e, now)
        }
        if (changed) notifyChanged()
        requestSave()
    }

    private fun stateOf(e: Entry?, now: Long): State {
        if (e == null) return State.UNKNOWN
        val age = now - e.ts
        if (age < 0L) return State.UNKNOWN   // clock was set back: do not trust it
        return when {
            e.alive && age < ALIVE_MS -> State.ALIVE
            !e.alive && e.fails >= DEAD_AFTER_FAILS && age < DEAD_MS -> State.DEAD
            else -> State.UNKNOWN
        }
    }

    /** Drops the oldest entries until at most MAX_ENTRIES are left. Call with `lock` held (or on a private map). */
    private fun trim(m: LinkedHashMap<Int, Entry>) {
        if (m.size <= MAX_ENTRIES) return
        val iter = m.entries.iterator()
        while (m.size > MAX_ENTRIES && iter.hasNext()) {
            iter.next()
            iter.remove()
        }
    }

    /** Raises `version` at most once per second: first change goes out at once, later ones are merged into one delayed bump. */
    private fun notifyChanged() {
        synchronized(bumpLock) {
            if (bumpScheduled) return
            val wait = BUMP_MS - (SystemClock.elapsedRealtime() - lastBump)
            if (wait <= 0L) {
                lastBump = SystemClock.elapsedRealtime()
                version.value = version.value + 1
            } else {
                bumpScheduled = true
                scope.launch {
                    delay(wait)
                    synchronized(bumpLock) {
                        bumpScheduled = false
                        lastBump = SystemClock.elapsedRealtime()
                        version.value = version.value + 1
                    }
                }
            }
        }
    }

    /** Debounce: every call restarts the 3 s timer. Nothing is saved before the file was loaded (it would overwrite it). */
    private fun requestSave() {
        if (!loaded) return
        synchronized(saveLock) {
            saveJob?.cancel()
            saveJob = scope.launch {
                delay(SAVE_DELAY_MS)
                writeFile()
            }
        }
    }

    private fun readFile(f: File): List<Entry> {
        val byHash = HashMap<Int, Entry>()
        try {
            if (f.exists()) {
                f.bufferedReader().use { reader ->
                    reader.forEachLine { line ->
                        val p = line.split(',')
                        if (p.size < 4) return@forEachLine
                        val hash = p[0].trim().toIntOrNull() ?: return@forEachLine
                        val alive = p[1].trim() == "1"
                        val fails = p[2].trim().toIntOrNull() ?: return@forEachLine
                        val ts = p[3].trim().toLongOrNull() ?: return@forEachLine
                        val e = Entry(hash, alive, if (alive) 0 else fails.coerceAtLeast(0), ts)
                        val prev = byHash[hash]
                        if (prev == null || prev.ts <= e.ts) byHash[hash] = e
                    }
                }
            }
        } catch (e: Exception) {
            // unreadable or corrupt file: start with what was read so far
        }
        return byHash.values.sortedBy { it.ts }
    }

    private fun writeFile() {
        val f = file ?: return
        synchronized(fileLock) {
            val snapshot: Array<Entry> = synchronized(lock) {
                dirty = false
                map.values.toTypedArray()
            }
            try {
                val sb = StringBuilder(snapshot.size * 32 + 16)
                for (e in snapshot) {
                    sb.append(e.hash).append(',')
                        .append(if (e.alive) '1' else '0').append(',')
                        .append(e.fails).append(',')
                        .append(e.ts).append('\n')
                }
                val tmp = File(f.path + ".tmp")
                tmp.writeText(sb.toString())
                if (!tmp.renameTo(f)) {
                    f.delete()
                    if (!tmp.renameTo(f)) throw IOException("rename failed")
                }
            } catch (e: Exception) {
                synchronized(lock) { dirty = true }   // try again on the next mark()
            }
        }
    }
}
