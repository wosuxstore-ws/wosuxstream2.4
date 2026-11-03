package com.freetv.app

import android.graphics.drawable.GradientDrawable
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import coil.load
import com.freetv.app.databinding.ItemHeroBinding
import com.freetv.app.databinding.ItemRowBinding

private val HOME_DIFF = object : DiffUtil.ItemCallback<HomeItem>() {
    override fun areItemsTheSame(oldItem: HomeItem, newItem: HomeItem): Boolean = when (oldItem) {
        is HomeItem.RowItem -> newItem is HomeItem.RowItem && oldItem.row.id == newItem.row.id
        is HomeItem.Hero -> newItem is HomeItem.Hero
    }

    override fun areContentsTheSame(oldItem: HomeItem, newItem: HomeItem): Boolean = oldItem == newItem
}

private const val VT_ROW = 0
private const val VT_HERO = 1          // the hero banner (Phase 22)

private const val PREFETCH_CARDS = 5   // cards the inner list prefetches when a row scrolls into view
private const val POOL_MAX_CARDS = 24  // channel cards kept in the ONE pool shared by all rows (default would be 5)
private const val PAYLOAD_CARDS = "cards"  // payload of refreshCards(): re-bind the cards of a row, do not rebuild the row

// Gradients (start, end) for the hero's initials plate, picked with name.hashCode(). Dark enough for white text.
private val HERO_GRADIENTS: Array<IntArray> = arrayOf(
    intArrayOf(0xFF0A4FD6.toInt(), 0xFF02AEF9.toInt()),
    intArrayOf(0xFF6D28D9.toInt(), 0xFF0088FF.toInt()),
    intArrayOf(0xFF0E7490.toInt(), 0xFF14B8A6.toInt()),
    intArrayOf(0xFFBE185D.toInt(), 0xFFF97316.toInt()),
    intArrayOf(0xFF047857.toInt(), 0xFF10B981.toInt()),
    intArrayOf(0xFFB91C1C.toInt(), 0xFFF43F5E.toInt())
)

/**
 * Vertical list of a hero banner (HomeItem.Hero, Phase 22) and horizontal rows (HomeItem.RowItem, Phase 19).
 *  - isFavorite: heart on a card, and the state of the hero's "My List" button
 *  - onPlay(row, indexInRow): a card was clicked (index = position in row.items)
 *  - onLong(channel): a card was long-pressed (OK held on the remote)
 *  - onSeeAll(row): "See all >" was clicked (shown only when row.total > row.items.size)
 *  - onHeroWatch(channel): the hero's "Watch" button was clicked
 *  - onHeroList(channel): the hero's "My List" button was clicked (add / remove favorite)
 */
class HomeAdapter(
    private val isFavorite: (Channel) -> Boolean,
    private val onPlay: (Row, Int) -> Unit,
    private val onLong: (Channel) -> Unit,
    private val onSeeAll: (Row) -> Unit,
    private val onHeroWatch: (Channel) -> Unit = { },
    private val onHeroList: (Channel) -> Unit = { }
) : ListAdapter<HomeItem, RecyclerView.ViewHolder>(HOME_DIFF) {

    class RowVH(val b: ItemRowBinding, val lm: LinearLayoutManager) : RecyclerView.ViewHolder(b.root) {
        lateinit var inner: ChannelAdapter
        // the row this holder shows now (null while the holder sits in the pool)
        var row: Row? = null
        // value of HomeAdapter.cardsEpoch the cards of this holder were last bound under
        var epoch = 0
    }

    // The hero banner (Phase 22)
    class HeroVH(val b: ItemHeroBinding) : RecyclerView.ViewHolder(b.root) {
        // the channel the banner shows now
        var channel: Channel? = null
        // url of that channel (guards a late logo-error callback after the holder was re-bound)
        var boundUrl: String? = null
    }

    // ONE pool for the cards of every row
    private val pool = RecyclerView.RecycledViewPool().apply { setMaxRecycledViews(0, POOL_MAX_CARDS) }

    // rowId -> horizontal scroll state of that row (saved when its holder is recycled, restored when the row is bound again)
    private val scrollStates = HashMap<String, Parcelable>()

    // Raised by refreshCards(). A row whose cards were bound under an older value re-binds its cards the next time it is shown.
    private var cardsEpoch = 0

    /**
     * Redraw the cards of every row (hearts, dead badge) after something outside the rows changed, e.g. a favorite.
     * Needed because DiffUtil sees equal rows and would leave the cards untouched.
     * Visible and cached rows are re-bound now; rows waiting in the pool refresh when they are bound again.
     */
    fun refreshCards() {
        cardsEpoch++
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount, PAYLOAD_CARDS)
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        // A changed row must not cross-fade into a second holder (it would also double the scroll-state work)
        (recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
    }

    override fun getItemViewType(position: Int): Int =
        if (getItem(position) is HomeItem.RowItem) VT_ROW else VT_HERO

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == VT_HERO) return createHero(parent)

        val b = ItemRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        val lm = LinearLayoutManager(parent.context, LinearLayoutManager.HORIZONTAL, false)
        lm.initialPrefetchItemCount = PREFETCH_CARDS
        val vh = RowVH(b, lm)
        vh.epoch = cardsEpoch

        val cardW = parent.resources.getDimensionPixelSize(R.dimen.card_w_row)
        vh.inner = ChannelAdapter(
            isFavorite = isFavorite,
            onClick = { _, index ->
                val r = vh.row
                if (r != null) onPlay(r, index)
            },
            onLongClick = { ch, _ -> onLong(ch) },
            cardWidthPx = cardW
        )

        val list = b.rowList
        list.layoutManager = lm
        list.setHasFixedSize(true)
        list.itemAnimator = null
        list.setRecycledViewPool(pool)
        list.adapter = vh.inner

        b.rowMore.setOnClickListener {
            val r = vh.row
            if (r != null) onSeeAll(r)
        }
        return vh
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        if (holder is RowVH && item is HomeItem.RowItem) bindRow(holder, item.row)
        else if (holder is HeroVH && item is HomeItem.Hero) bindHero(holder, item.channel)
    }

    // refreshCards(): only the cards are re-bound, the row itself (title, list, scroll position) stays as it is
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && holder is RowVH && holder.row != null) {
            rebindCards(holder)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    private fun createHero(parent: ViewGroup): HeroVH {
        val vh = HeroVH(ItemHeroBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        vh.b.btnWatch.setOnClickListener {
            val c = vh.channel
            if (c != null) onHeroWatch(c)
        }
        vh.b.btnHeroList.setOnClickListener {
            val c = vh.channel
            if (c != null) onHeroList(c)
        }
        return vh
    }

    // Cheap work only. Also called for the refreshCards() payload, which is how the "My List" button follows a favorite change.
    private fun bindHero(h: HeroVH, c: Channel) {
        val sameChannel = h.boundUrl == c.url
        h.channel = c
        h.boundUrl = c.url
        val b = h.b
        b.heroName.text = c.name
        val cat = categoryText(c.group)
        b.heroCategory.text = cat
        b.heroCategory.isVisible = cat.isNotEmpty()
        b.btnHeroList.setText(if (isFavorite(c)) R.string.hero_in_list else R.string.hero_list)

        // Same channel as before (e.g. only the favorite changed): keep the logo or initials that are already on screen
        if (sameChannel) return
        val logo = c.logo
        if (logo.isNullOrBlank()) {
            b.heroLogo.setImageDrawable(null)
            showHeroMono(h, c.name)
        } else {
            b.heroMono.isVisible = false
            b.heroLogo.isVisible = true
            val url = c.url
            val name = c.name
            b.heroLogo.load(logo) {
                placeholder(R.drawable.ic_tv)
                listener(onError = { _, _ ->
                    if (h.boundUrl == url) showHeroMono(h, name)
                })
            }
        }
    }

    // Hide the logo, show the first 1-2 letters of the name on a gradient chosen by the name's hash
    private fun showHeroMono(h: HeroVH, name: String) {
        val colors = HERO_GRADIENTS[(name.hashCode() and Int.MAX_VALUE) % HERO_GRADIENTS.size]
        val bg = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors)
        bg.cornerRadius = 8f * h.itemView.resources.displayMetrics.density // same radius as the bg_logo plate
        h.b.heroLogo.isVisible = false
        h.b.heroMono.text = heroMonogram(name)
        h.b.heroMono.background = bg
        h.b.heroMono.isVisible = true
    }

    private fun heroMonogram(name: String): String {
        val sb = StringBuilder(2)
        for (ch in name) {
            if (ch.isLetterOrDigit()) {
                sb.append(ch)
                if (sb.length == 2) break
            }
        }
        return if (sb.isEmpty()) "TV" else sb.toString().uppercase()
    }

    // Up to 3 categories of the channel, "News  ·  Sports"; empty when it has none ("Undefined" is ignored)
    private fun categoryText(group: String?): String {
        if (group.isNullOrBlank()) return ""
        val sb = StringBuilder()
        var count = 0
        for (part in group.split(';')) {
            val p = part.trim()
            if (p.isEmpty() || p.equals("Undefined", ignoreCase = true)) continue
            if (count > 0) sb.append("  ·  ")
            sb.append(p)
            count++
            if (count == 3) break
        }
        return sb.toString()
    }

    private fun rebindCards(h: RowVH) {
        if (h.epoch == cardsEpoch) return
        h.epoch = cardsEpoch
        h.inner.notifyItemRangeChanged(0, h.inner.itemCount)
    }

    private fun bindRow(h: RowVH, row: Row) {
        val prev = h.row
        val rowChanged = prev?.id != row.id
        if (rowChanged) {
            // This holder is being re-used for another row: keep the scroll position of the one it showed
            if (prev != null) saveState(h, prev.id)
            h.b.rowList.stopScroll()
        }
        h.row = row
        h.b.rowTitle.text = row.title
        h.b.rowMore.isVisible = row.total > row.items.size

        if (rowChanged || h.epoch != cardsEpoch) {
            // Both steps run after the cards of THIS row are in the inner adapter (the diff may finish later)
            h.inner.submitList(row.items) {
                if (h.row?.id == row.id) {   // the holder was not re-bound or recycled meanwhile
                    rebindCards(h)
                    if (rowChanged) restoreState(h, row.id)
                }
            }
        } else {
            h.inner.submitList(row.items)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is RowVH) {
            val r = holder.row
            if (r != null) saveState(holder, r.id)
            holder.b.rowList.stopScroll()
            holder.row = null
        }
        super.onViewRecycled(holder)
    }

    private fun saveState(h: RowVH, rowId: String) {
        // A list that was never laid out has no position worth keeping
        if (h.lm.childCount == 0) return
        val st = h.lm.onSaveInstanceState()
        if (st != null) scrollStates[rowId] = st
    }

    private fun restoreState(h: RowVH, rowId: String) {
        if (h.row?.id != rowId) return   // the holder was re-bound or recycled again before the list was ready
        val st = scrollStates.remove(rowId)
        if (st != null) h.lm.onRestoreInstanceState(st) else h.lm.scrollToPositionWithOffset(0, 0)
    }
}
