package com.fsck.k9.ui.messagelist

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.View.MeasureSpec
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.withTranslation
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.ViewHolder
import com.fsck.k9.SwipeAction
import com.fsck.k9.ui.R
import kotlin.math.abs

@SuppressLint("InflateParams")
class MessageListSwipeCallback(
    context: Context,
    private val resourceProvider: SwipeResourceProvider,
    private val swipeActionSupportProvider: SwipeActionSupportProvider,
    private val swipeRightAction: SwipeAction,
    private val swipeLeftAction: SwipeAction,
    private val adapter: MessageListAdapter,
    private val listener: MessageListSwipeListener
) : ItemTouchHelper.Callback() {
    private val swipeThreshold = context.resources.getDimension(R.dimen.messageListSwipeThreshold)
    private val backgroundColorPaint = Paint()

    private val swipeRightLayout: View
    private val swipeLeftLayout: View

    init {
        val layoutInflater = LayoutInflater.from(context)

        swipeRightLayout = layoutInflater.inflate(R.layout.swipe_right_action, null, false)
        swipeLeftLayout = layoutInflater.inflate(R.layout.swipe_left_action, null, false)
    }

    override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: ViewHolder): Int {
        if (viewHolder !is MessageViewHolder) return 0

        val item = adapter.getItemById(viewHolder.uniqueId) ?: return 0

        var swipeFlags = 0
        if (swipeActionSupportProvider.isActionSupported(item, swipeRightAction)) {
            swipeFlags = swipeFlags or ItemTouchHelper.RIGHT
        }
        if (swipeActionSupportProvider.isActionSupported(item, swipeLeftAction)) {
            swipeFlags = swipeFlags or ItemTouchHelper.LEFT
        }

        return makeMovementFlags(0, swipeFlags)
    }

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: ViewHolder,
        target: ViewHolder
    ): Boolean {
        throw UnsupportedOperationException("not implemented")
    }

    override fun onSwiped(viewHolder: ViewHolder, direction: Int) {
        val holder = viewHolder as MessageViewHolder
        val item = adapter.getItemById(holder.uniqueId) ?: error("Couldn't find MessageListItem")

        // ItemTouchHelper expects swiped views to be removed from the view hierarchy. We mark this ViewHolder so that
        // MessageListItemAnimator knows not to reuse it during an animation.
        viewHolder.markAsSwiped(true)

        when (direction) {
            ItemTouchHelper.RIGHT -> listener.onSwipeAction(item, swipeRightAction)
            ItemTouchHelper.LEFT -> listener.onSwipeAction(item, swipeLeftAction)
            else -> error("Unsupported direction: $direction")
        }
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        viewHolder.markAsSwiped(false)
    }

    override fun getSwipeThreshold(viewHolder: ViewHolder): Float {
        return swipeThreshold / viewHolder.itemView.width
    }

    override fun onChildDraw(
        canvas: Canvas,
        recyclerView: RecyclerView,
        viewHolder: ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean
    ) {
        val view = viewHolder.itemView
        val viewWidth = view.width
        val viewHeight = view.height

        val isViewAnimatingBack = !isCurrentlyActive && abs(dX).toInt() >= viewWidth

        canvas.withTranslation(x = view.left.toFloat(), y = view.top.toFloat()) {
            if (isViewAnimatingBack) {
                drawBackground(dX, viewWidth, viewHeight)
            } else {
                val holder = viewHolder as MessageViewHolder
                drawLayout(dX, viewWidth, viewHeight, holder)
            }
        }

        super.onChildDraw(canvas, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
    }

    private fun Canvas.drawBackground(dX: Float, width: Int, height: Int) {
        val swipeAction = if (dX > 0) swipeRightAction else swipeLeftAction
        val backgroundColor = resourceProvider.getBackgroundColor(swipeAction)

        backgroundColorPaint.color = backgroundColor
        drawRect(
            0F,
            0F,
            width.toFloat(),
            height.toFloat(),
            backgroundColorPaint
        )
    }

    private fun Canvas.drawLayout(dX: Float, width: Int, height: Int, viewHolder: MessageViewHolder) {
        val item = adapter.getItemById(viewHolder.uniqueId) ?: return
        val isSelected = adapter.isSelected(item)

        val swipeRight = dX > 0
        val swipeThresholdReached = abs(dX) > swipeThreshold

        val swipeLayout = if (swipeRight) swipeRightLayout else swipeLeftLayout
        val swipeAction = if (swipeRight) swipeRightAction else swipeLeftAction

        val foregroundColor: Int
        val backgroundColor: Int
        if (swipeThresholdReached) {
            foregroundColor = resourceProvider.iconTint
            backgroundColor = resourceProvider.getBackgroundColor(swipeAction)
        } else {
            foregroundColor = resourceProvider.getBackgroundColor(swipeAction)
            backgroundColor = resourceProvider.getBackgroundColor(SwipeAction.None)
        }

        swipeLayout.setBackgroundColor(backgroundColor)

        val icon = resourceProvider.getIcon(item, swipeAction)
        icon.setTint(foregroundColor)

        val iconView = swipeLayout.findViewById<ImageView>(R.id.swipe_action_icon)
        iconView.setImageDrawable(icon)

        val textView = swipeLayout.findViewById<TextView>(R.id.swipe_action_text)
        textView.setTextColor(foregroundColor)
        textView.text = resourceProvider.getActionName(item, swipeAction, isSelected)

        if (swipeLayout.isDirty) {
            val widthMeasureSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
            val heightMeasureSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
            swipeLayout.measure(widthMeasureSpec, heightMeasureSpec)
            swipeLayout.layout(0, 0, width, height)
        }

        swipeLayout.draw(this)
    }
}

fun interface SwipeActionSupportProvider {
    fun isActionSupported(item: MessageListItem, action: SwipeAction): Boolean
}

fun interface MessageListSwipeListener {
    fun onSwipeAction(item: MessageListItem, action: SwipeAction)
}

private fun ViewHolder.markAsSwiped(value: Boolean) {
    itemView.setTag(R.id.message_list_swipe_tag, if (value) true else null)
}

val ViewHolder.wasSwiped
    get() = itemView.getTag(R.id.message_list_swipe_tag) == true
