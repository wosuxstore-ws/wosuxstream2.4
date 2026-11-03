package com.freetv.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume

/**
 * Tests whether a channel answers, without playing it.
 * Uses the short-timeout client [Net.probe]. Reads at most 1024 bytes and always closes the response
 * (live streams never end).
 */
object StreamChecker {

    private const val MAX_PARALLEL = 6
    private const val PROBE_BYTES = 1024

    // Same string the player sends when a channel has no user-agent of its own (PlayerActivity.DEFAULT_UA is private),
    // so a check sees the same answer from the server as the player would.
    private const val DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /**
     * True when the channel's URL answers like a stream.
     *  - ".m3u8" URL: HTTP 200 and the first 1024 bytes contain "#EXTM3U" (case-insensitive).
     *  - anything else: HTTP 200 or 206 (asked with "Range: bytes=0-1023") and Content-Type is not text/html.
     * Any exception means false, except CancellationException, which is rethrown.
     */
    suspend fun check(ch: Channel): Boolean = withContext(Dispatchers.IO) {
        try {
            val hls = ch.url.contains(".m3u8", ignoreCase = true)
            val call = Net.probe.newCall(buildRequest(ch, hls))
            runCall(call, hls)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Checks channels from [list] that have no fresh result yet, at most 6 at the same time, and stops after
     * [limit] real checks. Every finished check is stored with HealthStore.mark. A check that is cancelled
     * is not stored. Channels with the same url are checked once.
     */
    suspend fun checkAll(list: List<Channel>, limit: Int = 40) {
        if (limit <= 0 || list.isEmpty()) return
        withContext(Dispatchers.IO) {
            val gate = Semaphore(MAX_PARALLEL)
            val seen = HashSet<String>()
            var started = 0
            for (ch in list) {
                ensureActive()
                if (started >= limit) break
                if (!seen.add(ch.url)) continue
                if (HealthStore.isFresh(ch.url)) continue
                gate.acquire()
                started++
                launch {
                    try {
                        val alive = check(ch)
                        HealthStore.mark(ch.url, alive)
                    } finally {
                        gate.release()
                    }
                }
            }
        }
    }

    private fun buildRequest(ch: Channel, hls: Boolean): Request {
        val builder = Request.Builder().url(ch.url).get()
        val ua = ch.userAgent
        builder.header("User-Agent", if (ua.isNullOrBlank()) DEFAULT_UA else ua)
        val ref = ch.referrer
        if (!ref.isNullOrBlank()) builder.header("Referer", ref)
        if (!hls) builder.header("Range", "bytes=0-${PROBE_BYTES - 1}")
        return builder.build()
    }

    /** Runs the blocking call; cancelling the coroutine cancels the call, so a blocked read stops at once. */
    private suspend fun runCall(call: Call, hls: Boolean): Boolean =
        suspendCancellableCoroutine<Boolean> { cont ->
            cont.invokeOnCancellation { call.cancel() }
            val ok = try {
                call.execute().use { resp -> evaluate(resp, hls) }
            } catch (e: Exception) {
                false
            }
            cont.resume(ok)
        }

    private fun evaluate(resp: Response, hls: Boolean): Boolean {
        val code = resp.code
        if (hls) {
            if (code != 200) return false
            val body = resp.body ?: return false
            val buf = ByteArray(PROBE_BYTES)
            val input = body.byteStream()
            var total = 0
            while (total < PROBE_BYTES) {
                val n = input.read(buf, total, PROBE_BYTES - total)
                if (n < 0) break
                total += n
            }
            return String(buf, 0, total, Charsets.ISO_8859_1).contains("#EXTM3U", ignoreCase = true)
        }
        if (code != 200 && code != 206) return false
        val type = resp.header("Content-Type")
        return type == null || !type.contains("text/html", ignoreCase = true)
    }
}
