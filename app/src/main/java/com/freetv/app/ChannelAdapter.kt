package com.freetv.app

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.freetv.app.databinding.ItemChannelBinding

private val CHANNEL_DIFF = object : DiffUtil.ItemCallback<Channel>() {
    override fun areItemsTheSame(oldItem: Channel, newItem: Channel): Boolean = oldItem.url == newItem.url
    override fun areContentsTheSame(oldItem: Channel, newItem: Channel): Boolean = oldItem == newItem
}

// 6 brand-friendly gradients (start, end), dark enough for white text. Picked with name.hashCode().
private val MONO_GRADIENTS: Array<IntArray> = arrayOf(
    intArrayOf(0xFF0A4FD6.toInt(), 0xFF02AEF9.toInt()),  // brand blue
    intArrayOf(0xFF6D28D9.toInt(), 0xFF0088FF.toInt()),  // violet to blue
    intArrayOf(0xFF0E7490.toInt(), 0xFF14B8A6.toInt()),  // teal
    intArrayOf(0xFFBE185D.toInt(), 0xFFF97316.toInt()),  // magenta to orange
    intArrayOf(0xFF047857.toInt(), 0xFF10B981.toInt()),  // green
    intArrayOf(0xFFB91C1C.toInt(), 0xFFF43F5E.toInt())   // red to rose
)

private const val FOCUS_SCALE = 1.08f
private const val DEAD_ALPHA = 0.45f

class ChannelAdapter(
    private val isFavorite: (Channel) -> Boolean,
    private val onClick: (Channel, Int) -> Unit,
    private val onLongClick: (Channel, Int) -> Unit,
    private val cardWidthPx: Int? = null
) : ListAdapter<Channel, ChannelAdapter.VH>(CHANNEL_DIFF) {

    class VH(val b: ItemChannelBinding) : RecyclerView.ViewHolder(b.root) {
        // url of the channel this holder is bound to now (guards late logo-error callbacks after recycling)
        var boundUrl: String? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val vh = VH(ItemChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        val root = vh.b.root

        // Fixed card width (rows); without it the card keeps the width the layout manager gives it (grid cell)
        cardWidthPx?.let { w ->
            val lp = root.layoutParams
            if (lp != null) {
                lp.width = w
                root.layoutParams = lp
            }
        }

        // Phase 19: rows share ONE RecycledViewPool, so this holder may later be bound by ANOTHER ChannelAdapter.
        // The click must therefore go to the adapter that binds the holder now (vh.bindingAdapter), not to the one that created it.
        root.setOnClickListener {
            val p = vh.bindingAdapterPosition
            val a = (vh.bindingAdapter as? ChannelAdapter) ?: this
            if (p != RecyclerView.NO_POSITION) a.onClick(a.getItem(p), p)
        }
        // Long-press (or hold OK on a TV remote) = add / remove favorite
        root.setOnLongClickListener {
            val p = vh.bindingAdapterPosition
            val a = (vh.bindingAdapter as? ChannelAdapter) ?: this
            if (p != RecyclerView.NO_POSITION) a.onLongClick(a.getItem(p), p)
            true
        }
        // Grow the card a little when the remote focuses it
        root.setOnFocusChangeListener { v, hasFocus ->
            val scale = if (hasFocus) FOCUS_SCALE else 1f
            v.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
        }
        return vh
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = getItem(position)
        val root = holder.b.root
        holder.boundUrl = c.url
        holder.b.name.text = c.name
        holder.b.star.isVisible = isFavorite(c)

        // A recycled card must show the scale that matches its focus state (no animation here)
        val scale = if (root.isFocused) FOCUS_SCALE else 1f
        if (root.scaleX != scale || root.scaleY != scale) {
            root.animate().cancel()
            root.scaleX = scale
            root.scaleY = scale
        }

        // Dead channel: dimmed card + gray dot
        val dead = HealthStore.state(c.url) == HealthStore.State.DEAD
        root.alpha = if (dead) DEAD_ALPHA else 1f
        holder.b.health.isVisible = dead

        val logo = c.logo
        if (logo.isNullOrBlank()) {
            holder.b.logo.setImageDrawable(null)
            showMono(holder, c.name)
        } else {
            holder.b.mono.isVisible = false
            holder.b.logo.isVisible = true
            val url = c.url
            val name = c.name
            holder.b.logo.load(logo) {
                placeholder(R.drawable.ic_tv)
                listener(onError = { _, _ ->
                    if (holder.boundUrl == url) showMono(holder, name)
                })
            }
        }
    }

    // Hide the logo, show the first 1-2 letters of the name on a gradient chosen by the name's hash
    private fun showMono(holder: VH, name: String) {
        val colors = MONO_GRADIENTS[(name.hashCode() and Int.MAX_VALUE) % MONO_GRADIENTS.size]
        val bg = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors)
        // corner radius of the monogram gradient = card radius minus the 2dp margin of @id/mono (so the focus ring stays visible).
        // Computed here, not in onCreateViewHolder: a holder re-used from the shared pool was created by another adapter instance.
        val res = holder.itemView.resources
        bg.cornerRadius = (res.getDimension(R.dimen.card_radius) - 2f * res.displayMetrics.density).coerceAtLeast(0f)
        holder.b.logo.isVisible = false
        holder.b.mono.text = monogram(name)
        holder.b.mono.background = bg
        holder.b.mono.isVisible = true
    }

    // First 1-2 letters or digits of the name, upper case; "TV" when the name has none
    private fun monogram(name: String): String {
        val sb = StringBuilder(2)
        for (ch in name) {
            if (ch.isLetterOrDigit()) {
                sb.append(ch)
                if (sb.length == 2) break
            }
        }
        return if (sb.isEmpty()) "TV" else sb.toString().uppercase()
    }
}
