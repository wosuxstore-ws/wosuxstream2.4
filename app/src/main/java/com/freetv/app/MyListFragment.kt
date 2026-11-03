package com.freetv.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import com.freetv.app.databinding.FragmentMylistBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.ConcurrentModificationException

/**
 * The "My List" tab (Phase 26): a grid of the favorites, newest first (ChannelAdapter, same cards and span rule as Browse).
 * Click = play (the playlist is this list, so UP / DOWN zaps inside My List). Long-press / hold OK = remove from favorites.
 * The list is read again in onResume and whenever the tab is shown again (tabs are shown / hidden, not recreated).
 */
class MyListFragment : Fragment() {

    private var _b: FragmentMylistBinding? = null
    private val b get() = _b!!

    private lateinit var adapter: ChannelAdapter

    // The list on screen (newest first). Never modified after it is set; a new list is built on every change.
    private var items: List<Channel> = emptyList()
    private var loadJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _b = FragmentMylistBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // As many columns as fit: ~2 on a phone, ~6-8 on a TV (same rule as BrowseFragment)
        val dm = resources.displayMetrics
        val span = ((dm.widthPixels / dm.density) / 150f).toInt().coerceIn(2, 10)

        adapter = ChannelAdapter(
            isFavorite = { true }, // everything in this list is a favorite
            onClick = { ch, _ -> play(ch) },
            onLongClick = { ch, _ -> remove(ch) }
        )
        b.list.layoutManager = GridLayoutManager(requireContext(), span)
        b.list.setHasFixedSize(true)
        b.list.adapter = adapter
        (b.list.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
    }

    override fun onResume() {
        super.onResume()
        // A hidden tab is resumed too (when the app comes back); it is refreshed when it is shown (onHiddenChanged)
        if (!isHidden) refresh()
    }

    // MainActivity shows / hides the tab fragments, so a switch to this tab does not call onResume
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) refresh()
    }

    override fun onDestroyView() {
        loadJob?.cancel()
        loadJob = null
        _b = null
        super.onDestroyView()
    }

    /** Reads the favorites off the main thread (Prefs parses its JSON on first use), then shows them. A newer call replaces an older one. */
    private fun refresh() {
        if (_b == null) return
        loadJob?.cancel()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val list = withContext(Dispatchers.Default) { loadNewestFirst() }
            // null = a toggle on the main thread hit exactly while the list was copied; that toggle already updated the screen
            if (list != null) show(list, null)
        }
    }

    // Prefs keeps favorites oldest first, so the copy is reversed. Prefs is not thread-safe (see HomeFragment.loadFavorites).
    private fun loadNewestFirst(): List<Channel>? =
        try {
            val l = ArrayList<Channel>(Prefs.favorites())
            l.reverse()
            l
        } catch (e: ConcurrentModificationException) {
            null
        }

    private fun show(list: List<Channel>, onCommitted: (() -> Unit)?) {
        val bnd = _b ?: return
        items = list
        adapter.submitList(list) { onCommitted?.invoke() }
        bnd.count.text = getString(R.string.channels_count, list.size)
        bnd.empty.isVisible = list.isEmpty()
    }

    private fun play(ch: Channel) {
        val pos = items.indexOfFirst { it.url == ch.url }
        if (pos < 0) return
        ChannelStore.playlist = items
        ChannelStore.index = pos
        startActivity(Intent(requireContext(), PlayerActivity::class.java))
    }

    // Long-press / hold OK: remove from favorites. The card disappears at once: the new list is built from the one on screen,
    // without reading Prefs again.
    private fun remove(ch: Channel) {
        val bnd = _b ?: return
        val pos = items.indexOfFirst { it.url == ch.url }
        if (pos < 0) return
        val hadFocus = bnd.list.hasFocus()

        val added = Prefs.toggleFavorite(ch)
        Toast.makeText(requireContext(), if (added) R.string.fav_added else R.string.fav_removed, Toast.LENGTH_SHORT).show()
        if (added) {
            // It was no longer a favorite (changed elsewhere) and the toggle has just added it again: read the list again
            refresh()
            return
        }

        val next = ArrayList<Channel>(items.size)
        for (c in items) {
            if (c.url != ch.url) next.add(c)
        }
        show(next) { if (hadFocus) focusNear(pos) }
    }

    // TV: the focused card was removed, so put the focus on the card that took its place (or the last one)
    private fun focusNear(pos: Int) {
        val rv = _b?.list ?: return
        rv.post {
            val cur = _b ?: return@post
            val n = adapter.itemCount
            if (n > 0) {
                cur.list.findViewHolderForAdapterPosition(minOf(pos, n - 1))?.itemView?.requestFocus()
            }
        }
    }
}
