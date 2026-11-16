package com.mcmyth.picmerge

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

internal class ThumbnailStrip(
    context: Context,
    private val images: MutableList<StitchImage>,
    private val onSelect: (StitchImage) -> Unit,
    private val onOrderChanged: () -> Unit,
    private val onVerticalSwipe: (Boolean) -> Unit = {}
) : RecyclerView(context) {
    private var selected: StitchImage? = null
    private var changed = false
    private val tiles = Tiles()
    private var downX = 0f
    private var downY = 0f
    private val swipeDistance = context.dp(48)

    init {
        layoutManager = LinearLayoutManager(context, HORIZONTAL, false)
        setPadding(context.dp(10), 0, context.dp(4), 0)
        clipToPadding = false
        isHorizontalScrollBarEnabled = false
        adapter = tiles
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0) {
            override fun isLongPressDragEnabled() = this@ThumbnailStrip.isEnabled

            override fun onMove(view: RecyclerView, holder: ViewHolder, target: ViewHolder): Boolean {
                val from = holder.adapterPosition
                val to = target.adapterPosition
                if (!this@ThumbnailStrip.isEnabled || from !in images.indices || to !in images.indices) return false
                move(from, to)
                changed = true
                return true
            }

            override fun onSwiped(holder: ViewHolder, direction: Int) = Unit

            override fun onSelectedChanged(holder: ViewHolder?, state: Int) {
                super.onSelectedChanged(holder, state)
                holder?.itemView?.animate()?.scaleX(1.10f)?.scaleY(1.10f)?.setDuration(120)?.start()
            }

            override fun clearView(view: RecyclerView, holder: ViewHolder) {
                super.clearView(view, holder)
                holder.itemView.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
                if (changed) {
                    changed = false
                    post { onOrderChanged() }
                }
            }
        }).attachToRecyclerView(this)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
            }
            MotionEvent.ACTION_UP -> {
                val vertical = event.y - downY
                if (kotlin.math.abs(vertical) > swipeDistance &&
                    kotlin.math.abs(vertical) > kotlin.math.abs(event.x - downX) * 1.25f
                ) {
                    onVerticalSwipe(vertical < 0)
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    fun reload() = tiles.notifyDataSetChanged()

    fun removed(position: Int) {
        tiles.notifyItemRemoved(position)
        tiles.notifyItemRangeChanged(position, images.size - position, POSITION)
    }

    fun inserted(position: Int) {
        tiles.notifyItemInserted(position)
        tiles.notifyItemRangeChanged(position + 1, images.size - position - 1, POSITION)
    }

    fun select(item: StitchImage?, scroll: Boolean) {
        val old = images.indexOf(selected)
        selected = item
        val next = images.indexOf(item)
        if (old >= 0 && old != next) tiles.notifyItemChanged(old, POSITION)
        if (next >= 0) {
            tiles.notifyItemChanged(next, POSITION)
            if (scroll) smoothScrollToPosition(next)
        }
    }

    private fun move(from: Int, to: Int) {
        images.add(to, images.removeAt(from))
        tiles.notifyItemMoved(from, to)
        val first = minOf(from, to)
        tiles.notifyItemRangeChanged(first, maxOf(from, to) - first + 1, POSITION)
    }

    private inner class Tiles : Adapter<Tile>() {
        override fun getItemCount() = images.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Tile(FrameLayout(context))

        override fun onBindViewHolder(holder: Tile, position: Int) {
            holder.picture.setImageBitmap(images[position].preview)
            bindPosition(holder, position)
        }

        override fun onBindViewHolder(holder: Tile, position: Int, payloads: MutableList<Any>) {
            if (payloads.isEmpty()) onBindViewHolder(holder, position) else bindPosition(holder, position)
        }

        private fun bindPosition(holder: Tile, position: Int) {
            val active = images[position] === selected
            holder.itemView.isSelected = active
            holder.itemView.background = context.pill(EditorColors.surface, 10, if (active) EditorColors.accent else 0)
            holder.number.text = (position + 1).toString()
            holder.itemView.contentDescription = "裁切第 ${position + 1} 张图片，可长按拖动排序"
        }
    }

    private inner class Tile(val frame: FrameLayout) : ViewHolder(frame) {
        val picture = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = context.pill(EditorColors.surface, 7)
            clipToOutline = true
        }
        val number = TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 11f
            setTextColor(EditorColors.text)
            typeface = Typeface.DEFAULT_BOLD
            background = context.pill(0xD91C252C.toInt(), 5)
        }

        init {
            frame.layoutParams = LayoutParams(context.dp(58), context.dp(54)).apply {
                marginEnd = context.dp(8)
                topMargin = context.dp(5)
            }
            frame.setPadding(context.dp(3), context.dp(3), context.dp(3), context.dp(3))
            frame.addView(picture, FrameLayout.LayoutParams(-1, -1))
            frame.addView(number, FrameLayout.LayoutParams(context.dp(21), context.dp(18), Gravity.BOTTOM or Gravity.START))
            frame.setOnClickListener {
                if (this@ThumbnailStrip.isEnabled) images.getOrNull(adapterPosition)?.let(onSelect)
            }
            ViewCompat.addAccessibilityAction(frame, "向前移动") { _, _ -> moveBy(-1) }
            ViewCompat.addAccessibilityAction(frame, "向后移动") { _, _ -> moveBy(1) }
        }

        private fun moveBy(offset: Int): Boolean {
            val from = adapterPosition
            val to = from + offset
            if (!this@ThumbnailStrip.isEnabled || from !in images.indices || to !in images.indices) return false
            move(from, to)
            onOrderChanged()
            return true
        }
    }

    private companion object {
        const val POSITION = "position"
    }
}
