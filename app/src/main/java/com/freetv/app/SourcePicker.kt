package com.freetv.app

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.freetv.app.databinding.DialogSourceBinding
import com.freetv.app.databinding.ItemSourceBinding

private val SOURCE_DIFF = object : DiffUtil.ItemCallback<Source>() {
    override fun areItemsTheSame(oldItem: Source, newItem: Source): Boolean = oldItem.url == newItem.url
    override fun areContentsTheSame(oldItem: Source, newItem: Source): Boolean = oldItem == newItem
}

/** One focusable row per source; the row of the selected source shows a check. Cheap binding only (text + one visibility). */
private class SourceAdapter(
    private val isSelected: (Source) -> Boolean,
    private val onPick: (Source) -> Unit
) : ListAdapter<Source, SourceAdapter.VH>(SOURCE_DIFF) {

    class VH(val b: ItemSourceBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val vh = VH(ItemSourceBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        vh.b.root.setOnClickListener {
            val p = vh.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onPick(getItem(p))
        }
        return vh
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val s = getItem(position)
        holder.b.sourceName.text = s.name
        holder.b.sourceCheck.isVisible = isSelected(s)
    }
}

/**
 * Phase 28: full-screen source picker. It replaces the chained AlertDialogs of BrowseFragment (menu, country / category / language
 * lists, custom link prompt).
 *
 * 4 tabs: Countries, Categories, Languages (the three share one filter field + one list; the tab decides which `Sources` list is shown,
 * and the row of the current source shows a check) and Custom link (URL field + Load, same validation as before).
 * Picking a source calls `vm.setSource(...)` and closes the picker. The "All channels" button in the header picks `Sources.ALL`.
 *
 * Open it with `SourcePicker().show(childFragmentManager, SourcePicker.TAG)` (BrowseFragment does). Every control is focusable (TV remote).
 */
class SourcePicker : DialogFragment() {

    private var _b: DialogSourceBinding? = null
    private val b get() = _b!!

    private val vm: MainViewModel by activityViewModels<MainViewModel>()
    private lateinit var adapter: SourceAdapter

    private var tab = TAB_COUNTRIES

    // url of the source that is active when the picker opens (the row with this url shows the check)
    private var selectedUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Full-screen: the app theme is not floating (dark background, no title bar), so the dialog fills the screen
        setStyle(STYLE_NORMAL, R.style.Theme_FreeTV)
        tab = savedInstanceState?.getInt(KEY_TAB, TAB_COUNTRIES) ?: initialTab()
    }

    // AppCompatDialog (not the plain Dialog) so the layout's Buttons / EditTexts are inflated as AppCompat widgets, like in the activities
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val d = AppCompatDialog(requireContext(), theme)
        // The keyboard only opens when the user taps / presses OK on a field; the window shrinks above it
        d.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        return d
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = DialogSourceBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        selectedUrl = vm.source.value?.url

        adapter = SourceAdapter(
            isSelected = { it.url == selectedUrl },
            onPick = { pick(it) }
        )
        b.sourceList.layoutManager = LinearLayoutManager(requireContext())
        b.sourceList.adapter = adapter
        b.sourceList.setHasFixedSize(true)
        b.sourceList.itemAnimator = null

        // Header
        b.btnClose.setOnClickListener { dismiss() }
        b.btnAll.text = if (selectedUrl == Sources.ALL.url) "✓ " + getString(R.string.src_all) else getString(R.string.src_all)
        b.btnAll.setOnClickListener { pick(Sources.ALL) }

        // Tabs
        b.tabCountries.setOnClickListener { selectTab(TAB_COUNTRIES) }
        b.tabCategories.setOnClickListener { selectTab(TAB_CATEGORIES) }
        b.tabLanguages.setOnClickListener { selectTab(TAB_LANGUAGES) }
        b.tabCustom.setOnClickListener { selectTab(TAB_CUSTOM) }

        // Filter field (lists)
        b.filterInput.doOnTextChanged { text, _, _, _ ->
            b.btnClearFilter.isVisible = !text.isNullOrEmpty()
            showList(scrollToSelected = false)
        }
        b.filterInput.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard(v)
                true
            } else {
                false
            }
        }
        b.btnClearFilter.setOnClickListener {
            b.filterInput.setText("")
            b.filterInput.requestFocus() // the clear button is gone now; keep the remote focus on the field
        }

        // Custom link
        b.urlInput.setText(Prefs.customUrl)
        b.urlInput.setSelection(b.urlInput.text.length)
        b.urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submitCustom()
                true
            } else {
                false
            }
        }
        b.btnLoad.setOnClickListener { submitCustom() }

        showTab(scrollToSelected = true)

        // TV: start with the focus on the selected tab (posted, the view is not attached yet; no effect in touch mode)
        if (savedInstanceState == null) {
            view.post { _b?.let { tabView(tab).requestFocus() } }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, tab)
    }

    override fun onDestroyView() {
        _b = null
        super.onDestroyView()
    }

    // ---------- tabs ----------

    /** Opens on the tab that holds the active source, so its check is visible at once. */
    private fun initialTab(): Int {
        val url = vm.source.value?.url ?: return TAB_COUNTRIES
        return when {
            Sources.countries.any { it.url == url } -> TAB_COUNTRIES
            Sources.categories.any { it.url == url } -> TAB_CATEGORIES
            Sources.languages.any { it.url == url } -> TAB_LANGUAGES
            url == Sources.ALL.url -> TAB_COUNTRIES
            else -> TAB_CUSTOM
        }
    }

    private fun tabView(t: Int): TextView = when (t) {
        TAB_COUNTRIES -> b.tabCountries
        TAB_CATEGORIES -> b.tabCategories
        TAB_LANGUAGES -> b.tabLanguages
        else -> b.tabCustom
    }

    /** User chose a tab: the filter text belongs to the old tab, so it is cleared. */
    private fun selectTab(t: Int) {
        if (t == tab) return
        tab = t
        if (t != TAB_CUSTOM && b.filterInput.text.isNotEmpty()) {
            b.filterInput.setText("") // the text watcher shows the list (without scrolling to the selected row)
            showTab(scrollToSelected = false, refreshList = false)
        } else {
            showTab(scrollToSelected = true)
        }
    }

    /** Shows the panel and the tab highlight of `tab`; refreshes the list for the three list tabs. */
    private fun showTab(scrollToSelected: Boolean, refreshList: Boolean = true) {
        val ctx = requireContext()
        val on = ContextCompat.getColor(ctx, R.color.accent)
        val off = ContextCompat.getColor(ctx, R.color.text_dim)
        for (t in TAB_COUNTRIES..TAB_CUSTOM) {
            val v = tabView(t)
            val sel = t == tab
            v.isSelected = sel
            v.setTextColor(if (sel) on else off)
        }
        val custom = tab == TAB_CUSTOM
        b.listPanel.isVisible = !custom
        b.customPanel.isVisible = custom
        if (!custom && refreshList) showList(scrollToSelected)
    }

    // ---------- list ----------

    /** Filters the Sources list of the current tab by the text of the filter field (name contains it, ignoring case). */
    private fun showList(scrollToSelected: Boolean) {
        if (tab == TAB_CUSTOM) return
        val base = when (tab) {
            TAB_COUNTRIES -> Sources.countries
            TAB_CATEGORIES -> Sources.categories
            else -> Sources.languages
        }
        val q = b.filterInput.text.toString().trim()
        val list = if (q.isEmpty()) base else base.filter { it.name.contains(q, ignoreCase = true) }
        b.emptyText.isVisible = list.isEmpty()
        adapter.submitList(list) {
            // runs when the new list is committed: back to the top, or (tab switch) to the row with the check
            val binding = _b
            if (binding != null) {
                val pos = if (scrollToSelected) list.indexOfFirst { it.url == selectedUrl } else -1
                (binding.sourceList.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(if (pos > 0) pos else 0, 0)
            }
        }
    }

    // ---------- picking ----------

    private fun pick(s: Source) {
        vm.setSource(s)
        dismiss()
    }

    /** Same validation as the old prompt: the link must start with http:// or https://. */
    private fun submitCustom() {
        val url = b.urlInput.text.toString().trim()
        if (url.startsWith("http://") || url.startsWith("https://")) {
            Prefs.customUrl = url
            vm.setSource(Source(getString(R.string.custom_name), url))
            dismiss()
        } else {
            Toast.makeText(requireContext(), R.string.custom_invalid, Toast.LENGTH_LONG).show()
        }
    }

    private fun hideKeyboard(v: View) {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(v.windowToken, 0)
    }

    companion object {
        const val TAG = "source_picker"
        private const val KEY_TAB = "tab"
        private const val TAB_COUNTRIES = 0
        private const val TAB_CATEGORIES = 1
        private const val TAB_LANGUAGES = 2
        private const val TAB_CUSTOM = 3
    }
}
