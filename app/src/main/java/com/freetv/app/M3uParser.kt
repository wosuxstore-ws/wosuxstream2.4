package com.freetv.app

import java.io.BufferedReader

/**
 * Minimal, fast M3U/M3U8 playlist parser (supports logos, groups, referrer and user-agent options).
 *
 * Phase 13: no Regex any more. The attributes of an #EXTINF line are found with indexOf scanning
 * (see [Attrs]), no Map is created per line, and the result list / URL set are pre-sized.
 * Output is meant to be identical to the old Regex version: same channels, same order,
 * same dedupe by URL, same logo / group / referrer / userAgent handling.
 *
 * HOW TO VERIFY: open the SAME source (for example "All channels") once with the Phase 12 build and once
 * with this build. The channel count shown on the main screen must be exactly the same
 * (for a custom link, use the same link in both builds).
 */
object M3uParser {

    // Typical size of the biggest playlist (iptv-org "All channels" is around 10,000 entries).
    // Only a starting size: bigger lists still grow normally. HashSet(16384) holds 12288 entries before it resizes.
    private const val EXPECTED_CHANNELS = 12_000
    private const val EXPECTED_URLS = 16_384

    /**
     * Finds the LAST value of tvg-logo, group-title and tvg-name in one #EXTINF line.
     * It follows the same rules as the old Regex ([A-Za-z0-9_-]+)="([^"]*)": a key is a run of
     * letters, digits, '_' or '-' right before =", a value runs up to the next quote, matches never overlap,
     * keys are compared ignoring case, and when a key appears twice the later one wins.
     * Values are kept as index ranges into the line, so no substring is made until a value is really used.
     * One instance is created per parse() call (not per line), so parse() stays thread-safe.
     */
    private class Attrs {
        var logoS = -1
        var logoE = -1
        var groupS = -1
        var groupE = -1
        var nameS = -1
        var nameE = -1

        fun scan(line: String) {
            logoS = -1; logoE = -1
            groupS = -1; groupE = -1
            nameS = -1; nameE = -1

            var pos = 0   // end of the last match: a key can not start before this
            var from = 0  // where to look for the next ="
            while (true) {
                val eq = line.indexOf("=\"", from)
                if (eq < 0) return
                var ks = eq
                while (ks > pos && isKeyChar(line[ks - 1])) ks--
                if (ks == eq) {          // no key before ="  -> not an attribute, look further
                    from = eq + 1
                    continue
                }
                val vs = eq + 2
                val close = line.indexOf('"', vs)
                if (close < 0) return   // no closing quote: no later attribute can match either

                val keyLen = eq - ks
                if (keyLen == 8) {
                    if (keyIs(line, ks, "tvg-logo")) {
                        logoS = vs; logoE = close
                    } else if (keyIs(line, ks, "tvg-name")) {
                        nameS = vs; nameE = close
                    }
                } else if (keyLen == 11) {
                    if (keyIs(line, ks, "group-title")) {
                        groupS = vs; groupE = close
                    }
                }
                pos = close + 1
                from = pos
            }
        }

        private fun isKeyChar(c: Char): Boolean =
            (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') || c == '_' || c == '-'

        /** [key] is lowercase ASCII, and line[start until start + key.length] is already known to be key characters. */
        private fun keyIs(line: String, start: Int, key: String): Boolean {
            for (i in 0 until key.length) {
                var c = line[start + i]
                if (c in 'A'..'Z') c = c + 32
                if (c != key[i]) return false
            }
            return true
        }
    }

    private fun isBlankRange(s: String, from: Int, to: Int): Boolean {
        for (i in from until to) {
            if (!s[i].isWhitespace()) return false
        }
        return true
    }

    fun parse(reader: BufferedReader): List<Channel> {
        val out = ArrayList<Channel>(EXPECTED_CHANNELS)
        val seen = HashSet<String>(EXPECTED_URLS)
        val attrs = Attrs()

        var name: String? = null
        var logo: String? = null
        var group: String? = null
        var referrer: String? = null
        var userAgent: String? = null

        while (true) {
            val raw = reader.readLine() ?: break
            val line = raw.trim()
            if (line.isEmpty()) continue

            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    attrs.scan(line)

                    // Title = text after the LAST comma of the line, trimmed (null when missing or blank).
                    var title: String? = null
                    val comma = line.lastIndexOf(',')
                    if (comma >= 0) {
                        var ts = comma + 1
                        var te = line.length
                        while (ts < te && line[ts].isWhitespace()) ts++
                        while (te > ts && line[te - 1].isWhitespace()) te--
                        if (ts < te) title = line.substring(ts, te)
                    }
                    name = if (title != null) {
                        title
                    } else if (attrs.nameS >= 0 && !isBlankRange(line, attrs.nameS, attrs.nameE)) {
                        line.substring(attrs.nameS, attrs.nameE)
                    } else {
                        "Channel"
                    }

                    logo = if (attrs.logoE - attrs.logoS >= 4 && line.startsWith("http", attrs.logoS)) {
                        line.substring(attrs.logoS, attrs.logoE)
                    } else {
                        null
                    }
                    group = if (attrs.groupS >= 0 && !isBlankRange(line, attrs.groupS, attrs.groupE)) {
                        line.substring(attrs.groupS, attrs.groupE)
                    } else {
                        null
                    }
                    referrer = null
                    userAgent = null
                }

                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val kv = line.substringAfter(':')
                    val key = kv.substringBefore('=').trim().lowercase()
                    val value = kv.substringAfter('=', "").trim()
                    when (key) {
                        "http-referrer", "http-referer" -> referrer = value.ifBlank { null }
                        "http-user-agent" -> userAgent = value.ifBlank { null }
                    }
                }

                line.startsWith("#") -> Unit

                else -> {
                    val n = name
                    val isWeb = line.startsWith("http://") || line.startsWith("https://")
                    if (n != null && isWeb && seen.add(line)) {
                        out.add(Channel(n, line, logo, group, referrer, userAgent))
                    }
                    name = null
                    logo = null
                    group = null
                    referrer = null
                    userAgent = null
                }
            }
        }
        return out
    }
}
