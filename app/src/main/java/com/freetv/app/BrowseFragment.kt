package com.freetv.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import com.freetv.app.databinding.FragmentBrowseBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.ConcurrentModificationException

/**
 * The old grid screen ("all channels"), moved out of MainActivity in Phase 17 with identical behavior:
 * grid, Source / Search / Favorites / Refresh buttons, dialogs, focus restore after the player.
 *
 * Phase 24: with the optional argument `group` (see newInstance) the same grid shows ONE category only ("See all" on a Home row):
 * a back button and the category name on top, no Source / Search / Favorites / Refresh buttons, and the shared search text and
 * "favorites only" switch of the ViewModel are ignored, so the whole category is always shown. Without the argument nothing changed.
 *
 * Phase 27: the Search button + dialog are replaced by an inline field (searchInput, with a clear button). The list is filtered while
 * typing (250 ms debounce); the filtering itself runs on Dispatchers.Default and the result is submitted on the main thread.
 *
 * Phase 28: the Source button opens the full-screen SourcePicker (DialogFragment) instead of the chained AlertDialogs. The old
 * showSourceMenu / pickSource / promptCustomUrl / promptText are gone; the picker calls vm.setSource(...) itself.
 */
class BrowseFragment : Fragment() {

    private var _b: FragmentBrowseBinding? = null
    private val b get() = _b!!

    private val vm: MainViewModel by activityViewModels<MainViewModel>()
    private lateinit var adapter: ChannelAdapter

    private var visible: List<Channel> = emptyList()
    private var cameFromPlayer = false

    // Phase 24: the category this screen is limited to (null = the normal grid of all channels). Read from the arguments in onCreate.
    private var group: String? = null
    private var groupJob: Job? = null
    // true once the category list was built at least once (keeps the "no channels" text from flashing while it is being built)
    private var groupReady = false

    // Phase 27: the running filter (normal grid), the pending debounce of the search field, and "a filter result is on its way"
    private var filterJob: Job? = null
    private var searchJob: Job? = null
    private var filtering = false

    // "Favorites only" of the ViewModel counts only in the normal grid, never in a category grid
    private val favOnly: Boolean get() = group == null && vm.favoritesOnly

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        group = arguments?.getString(ARG_GROUP)?.trim()?.takeIf { it.isNotEmpty() }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _b = FragmentBrowseBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // As many columns as fit: ~2 on a phone, ~6-8 on a TV
        val dm = resources.displayMetrics
        val span = ((dm.widthPixels / dm.density) / 150f).toInt().coerceIn(2, 10)

        adapter = ChannelAdapter(
            isFavorite = { Prefs.isFavorite(it.url) },
            onClick = { _, pos -> play(pos) },
            onLongClick = { ch, pos -> toggleFavorite(ch, pos) }
        )
        b.list.layoutManager = GridLayoutManager(requireContext(), span)
        b.list.adapter = adapter
        (b.list.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

        // Category grid: back button + category name instead of the app name and the button row
        val g = group
        if (g != null) {
            b.groupBar.isVisible = true
            b.groupTitle.text = g
            b.appTitle.isVisible = false
            b.searchBar.isVisible = false
            b.toolbar.isVisible = false
            b.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }
            // TV: put the focus on the back button (a view that was focused on the screen below is gone now).
            // Posted, because the view is not attached to the window yet. No effect in touch mode.
            view.post { _b?.btnBack?.requestFocus() }
        }

        if (g == null) setupSearch()

        b.btnSource.setOnClickListener { openSourcePicker() }
        b.btnFav.setOnClickListener {
            vm.favoritesOnly = !vm.favoritesOnly
            applyFilter()
        }
        b.btnRefresh.setOnClickListener { vm.load(force = true) }

        val owner = viewLifecycleOwner
        vm.channels.observe(owner) { applyFilter() }
        vm.loading.observe(owner) { isLoading ->
            b.progress.isVisible = isLoading
            b.empty.isVisible = visible.isEmpty() && !isLoading && !filtering && (group == null || groupReady)
        }
        vm.source.observe(owner) { s -> b.btnSource.text = getString(R.string.source_btn, s.name) }
        vm.message.observe(owner) { msg ->
            if (msg != null) {
                Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                vm.message.value = null
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (cameFromPlayer) {
            cameFromPlayer = false
            // Favorites may have changed inside the player
            if (favOnly) applyFilter() else adapter.notifyItemRangeChanged(0, adapter.itemCount)

            // Put the cursor back on the channel that was playing last
            val idx = ChannelStore.index
            if (idx in visible.indices) {
                val list = b.list // captured, so the delayed lambda never touches the binding after the view is gone
                list.scrollToPosition(idx)
                list.postDelayed({
                    list.findViewHolderForAdapterPosition(idx)?.itemView?.requestFocus()
                }, 150)
            }
        }
    }

    override fun onDestroyView() {
        groupJob?.cancel()
        groupJob = null
        groupReady = false
        filterJob?.cancel()
        filterJob = null
        searchJob?.cancel()
        searchJob = null
        filtering = false
        _b = null
        super.onDestroyView()
    }

    private fun applyFilter() {
        val g = group
        if (g != null) {
            applyGroup(g)
            return
        }

        val favOnlyNow = vm.favoritesOnly
        val q = vm.query.trim()
        val all = vm.channels.value.orEmpty()

        // These texts do not depend on the result
        b.btnFav.setText(if (favOnlyNow) R.string.favorites_on else R.string.favorites_off)
        b.empty.setText(if (favOnlyNow) R.string.empty_favorites else R.string.empty_list)

        // Phase 27: filter off the main thread (thousands of channels), submit on the main thread. A newer call replaces an older one.
        filtering = true
        filterJob?.cancel()
        filterJob = viewLifecycleOwner.lifecycleScope.launch {
            val list = withContext(Dispatchers.Default) {
                filterChannels(if (favOnlyNow) favoritesSafe() else all, q)
            }
            visible = list
            filtering = false
            adapter.submitList(list)
            b.count.text = getString(R.string.channels_count, list.size)
            b.empty.isVisible = list.isEmpty() && vm.loading.value != true
        }
    }

    // ---------- search (Phase 27) ----------

    /** Inline search field: filters while typing (debounced); the search key filters at once and closes the keyboard; the clear button empties it. */
    private fun setupSearch() {
        val input = b.searchInput
        val clear = b.btnClearSearch

        // The text is kept in the ViewModel (like before), so it survives tab switches and rotation
        input.setText(vm.query)
        input.setSelection(input.text.length)
        clear.isVisible = input.text.isNotEmpty()

        input.doOnTextChanged { text, _, _, _ ->
            val q = text?.toString().orEmpty()
            clear.isVisible = q.isNotEmpty()
            if (q != vm.query) {
                vm.query = q
                scheduleFilter()
            }
        }
        input.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchJob?.cancel()
                applyFilter()
                hideKeyboard(v)
                true
            } else {
                false
            }
        }
        clear.setOnClickListener {
            input.setText("")
            searchJob?.cancel()
            applyFilter()
            input.requestFocus() // the clear button is gone now; keep the remote focus on the field
        }
    }

    /** Runs applyFilter() once the typing has paused for SEARCH_DEBOUNCE_MS; every new key press restarts the wait. */
    private fun scheduleFilter() {
        searchJob?.cancel()
        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            applyFilter()
        }
    }

    // Name or group contains the text, ignoring case. An empty text keeps the whole list. Pure function, safe on Dispatchers.Default.
    private fun filterChannels(base: List<Channel>, q: String): List<Channel> {
        if (q.isEmpty()) return base
        val out = ArrayList<Channel>()
        for (c in base) {
            if (c.name.contains(q, ignoreCase = true) || (c.group?.contains(q, ignoreCase = true) == true)) out.add(c)
        }
        return out
    }

    // Prefs parses its favorites JSON on first use, so this runs on the background thread. Prefs is not thread-safe: if a toggle on the
    // main thread hits exactly while the list is copied, try again (a toggle always starts a new applyFilter() afterwards anyway).
    private fun favoritesSafe(): List<Channel> {
        for (i in 0 until 3) {
            try {
                return Prefs.favorites()
            } catch (e: ConcurrentModificationException) {
                // try again
            }
        }
        return emptyList()
    }

    private fun hideKeyboard(v: View) {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(v.windowToken, 0)
    }

    // ---------- category grid (Phase 24) ----------

    /** Builds the list of the category off the main thread, then shows it. A newer call replaces an older one. */
    private fun applyGroup(name: String) {
        val all = vm.channels.value.orEmpty()
        groupJob?.cancel()
        groupJob = viewLifecycleOwner.lifecycleScope.launch {
            val list = withContext(Dispatchers.Default) { filterGroup(all, name) }
            visible = list
            groupReady = true
            adapter.submitList(list)
            b.count.text = getString(R.string.channels_count, list.size)
            b.empty.setText(R.string.empty_group)
            b.empty.isVisible = list.isEmpty() && vm.loading.value != true
        }
    }

    // Playlist order is kept. Pure function, safe on Dispatchers.Default.
    private fun filterGroup(all: List<Channel>, name: String): List<Channel> {
        val out = ArrayList<Channel>()
        for (c in all) {
            if (inGroup(c, name)) out.add(c)
        }
        return out
    }

    /** True when one of the ';'-separated categories of the channel's group equals `name` (trimmed, ignoring case). `name` must not be empty. */
    private fun inGroup(c: Channel, name: String): Boolean {
        val g = c.group ?: return false
        val n = g.length
        var start = 0
        while (start <= n) {
            var end = g.indexOf(';', start)
            if (end < 0) end = n
            var s = start
            var e = end
            while (s < e && g[s].isWhitespace()) s++
            while (e > s && g[e - 1].isWhitespace()) e--
            if (e - s == name.length && g.regionMatches(s, name, 0, name.length, ignoreCase = true)) return true
            start = end + 1
        }
        return false
    }

    private fun play(position: Int) {
        if (position !in visible.indices) return
        ChannelStore.playlist = visible
        ChannelStore.index = position
        cameFromPlayer = true
        startActivity(Intent(requireContext(), PlayerActivity::class.java))
    }

    private fun toggleFavorite(ch: Channel, pos: Int) {
        val added = Prefs.toggleFavorite(ch)
        Toast.makeText(requireContext(), if (added) R.string.fav_added else R.string.fav_removed, Toast.LENGTH_SHORT).show()
        if (favOnly && !added) applyFilter() else adapter.notifyItemChanged(pos)
    }

    // ---------- source picker (Phase 28) ----------

    /** Opens the full-screen source picker (countries / categories / languages / custom link). Does nothing if it is already open. */
    private fun openSourcePicker() {
        val fm = childFragmentManager
        if (fm.isStateSaved || fm.findFragmentByTag(SourcePicker.TAG) != null) return
        SourcePicker().show(fm, SourcePicker.TAG)
    }

    companion object {
        private const val ARG_GROUP = "group"
        private const val SEARCH_DEBOUNCE_MS = 250L

        /**
         * group = name of the category to limit the grid to (matched against the ';'-separated parts of each channel's group, ignoring case),
         * or null for the normal grid of all channels. The name travels in the fragment arguments, so it survives rotation.
         */
        fun newInstance(group: String?): BrowseFragment {
            val f = BrowseFragment()
            if (group != null) {
                val args = Bundle()
                args.putString(ARG_GROUP, group)
                f.arguments = args
            }
            return f
        }
    }
}
