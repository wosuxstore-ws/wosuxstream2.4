package com.freetv.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.freetv.app.databinding.ItemZapBinding

private val ZAP_DIFF = object : DiffUtil.ItemCallback<Channel>() {
    override fun areItemsTheSame(oldItem: Channel, newItem: Channel): Boolean = oldItem.url == newItem.url
    override fun areContentsTheSame(oldItem: Channel, newItem: Channel): Boolean = oldItem == newItem
}

// Payload for "only the highlight changed": the row is not bound again (no logo reload)
private const val PAYLOAD_CURRENT = "current"

/**
 * Phase 34: the channel list of the side panel in the player (name + small logo per row).
 * The row at [setCurrent]'s position is highlighted (activated state + accent colored name).
 * [onPick] gets the position of the clicked row in the list that was given to submitList.
 */
class ZapAdapter(
    private val onPick: (Int) -> Unit
) : ListAdapter<Channel, ZapAdapter.VH>(ZAP_DIFF) {

    class VH(val b: ItemZapBinding) : RecyclerView.ViewHolder(b.root)

    private var current = -1
    private var colorName = 0
    private var colorCurrent = 0

    /** Highlights the row at [pos] (-1 = none). Only the old and the new row are refreshed. */
    fun setCurrent(pos: Int) {
        if (pos == current) return
        val old = current
        current = pos
        if (old in 0 until itemCount) notifyItemChanged(old, PAYLOAD_CURRENT)
        if (pos in 0 until itemCount) notifyItemChanged(pos, PAYLOAD_CURRENT)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        colorName = ContextCompat.getColor(ctx, R.color.text)
        colorCurrent = ContextCompat.getColor(ctx, R.color.accent)
        val vh = VH(ItemZapBinding.inflate(LayoutInflater.from(ctx), parent, false))
        vh.b.root.setOnClickListener {
            val p = vh.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onPick(p)
        }
        return vh
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = getItem(position)
        holder.b.name.text = c.name
        val logo = c.logo
        if (logo.isNullOrBlank()) {
            holder.b.logo.load(R.drawable.ic_tv)
        } else {
            holder.b.logo.load(logo) {
                placeholder(R.drawable.ic_tv)
                error(R.drawable.ic_tv)
            }
        }
        bindState(holder, position)
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty()) {
            bindState(holder, position)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    private fun bindState(holder: VH, position: Int) {
        val isCurrent = position == current
        holder.itemView.isActivated = isCurrent
        holder.b.name.setTextColor(if (isCurrent) colorCurrent else colorName)
    }
}
