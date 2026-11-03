package com.freetv.app

/**
 * Builds the Home rows from the channel lists. Pure Kotlin: no UI, no Context, no shared mutable state,
 * so it is safe to call on Dispatchers.Default. (HealthStore.state is lock-protected and safe from any thread.)
 */
object RowBuilder {

    private const val RECENTS_MAX = 20
    private const val FAVS_MAX = 30
    private const val TOP_CATEGORIES = 14
    private const val ROW_ITEMS = 30
    private const val ALL_ITEMS = 60
    private const val UNDEFINED = "Undefined"

    /**
     * Rows in this order: "Continue watching" (id "recents"), "My List" (id "favs"),
     * up to 14 category rows (id "group:<name>", biggest category first), or one "Channels" row (id "all")
     * when no channel has a usable category. Empty rows are never returned.
     * With hideDead = true, channels that HealthStore reports as DEAD are left out of every row (and of the counts).
     */
    fun build(
        all: List<Channel>,
        favorites: List<Channel>,
        recents: List<Channel>,
        hideDead: Boolean
    ): List<Row> {
        val rows = ArrayList<Row>(TOP_CATEGORIES + 2)

        // 1. Continue watching
        val rec = firstOf(recents, RECENTS_MAX, hideDead)
        if (rec.isNotEmpty()) rows.add(Row("recents", "Continue watching", rec, rec.size))

        // 2. My List
        val fav = firstOf(favorites, FAVS_MAX, hideDead)
        if (fav.isNotEmpty()) rows.add(Row("favs", "My List", fav, fav.size))

        // 3. Category rows. A channel's group may hold several categories separated by ';'.
        val alive: List<Channel> = if (hideDead) all.filter { !isDead(it) } else all
        val groups = LinkedHashMap<String, ArrayList<Channel>>()
        for (c in alive) {
            val g = c.group
            if (g == null || g.isBlank()) continue
            val n = g.length
            var start = 0
            while (start <= n) {
                var end = g.indexOf(';', start)
                if (end < 0) end = n
                val name = g.substring(start, end).trim()
                if (name.isNotEmpty() && !name.equals(UNDEFINED, ignoreCase = true)) {
                    val list = groups.getOrPut(name) { ArrayList() }
                    // "News;News" on one channel must not add it twice
                    if (list.isEmpty() || list[list.size - 1] !== c) list.add(c)
                }
                start = end + 1
            }
        }

        if (groups.isNotEmpty()) {
            // sortedByDescending is stable: categories with the same count keep their first-seen order
            val top = groups.entries.sortedByDescending { it.value.size }.take(TOP_CATEGORIES)
            for (e in top) {
                rows.add(Row("group:" + e.key, e.key, logoFirst(e.value, ROW_ITEMS), e.value.size))
            }
        } else if (alive.isNotEmpty()) {
            // 4. Nobody has a category
            rows.add(Row("all", "Channels", alive.take(ALL_ITEMS), alive.size))
        }
        return rows
    }

    /** The channel for the hero banner: the first one that has a logo and is not DEAD. */
    fun pickHero(all: List<Channel>): Channel? {
        for (c in all) {
            if (hasLogo(c) && !isDead(c)) return c
        }
        return null
    }

    private fun hasLogo(c: Channel): Boolean {
        val l = c.logo
        return l != null && l.isNotBlank()
    }

    private fun isDead(c: Channel): Boolean = HealthStore.state(c.url) == HealthStore.State.DEAD

    /** The first `max` channels (list order), skipping DEAD ones when hideDead. */
    private fun firstOf(list: List<Channel>, max: Int, hideDead: Boolean): List<Channel> {
        val out = ArrayList<Channel>(minOf(list.size, max))
        for (c in list) {
            if (out.size >= max) break
            if (hideDead && isDead(c)) continue
            out.add(c)
        }
        return out
    }

    /** Up to `max` channels: those with a logo first, then the rest, each part in the original order. */
    private fun logoFirst(list: List<Channel>, max: Int): List<Channel> {
        val out = ArrayList<Channel>(minOf(list.size, max))
        for (c in list) {
            if (out.size >= max) break
            if (hasLogo(c)) out.add(c)
        }
        if (out.size < max) {
            for (c in list) {
                if (out.size >= max) break
                if (!hasLogo(c)) out.add(c)
            }
        }
        return out
    }
}
