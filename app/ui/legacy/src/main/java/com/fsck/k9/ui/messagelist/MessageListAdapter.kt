package com.fsck.k9.ui.messagelist

import android.annotation.SuppressLint
import android.content.res.Resources
import android.content.res.Resources.Theme
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.View.OnClickListener
import android.view.View.OnLongClickListener
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.NO_POSITION
import com.fsck.k9.FontSizes
import com.fsck.k9.contacts.ContactPictureLoader
import com.fsck.k9.controller.MessageReference
import com.fsck.k9.mail.Address
import com.fsck.k9.ui.R
import com.fsck.k9.ui.helper.RelativeDateTimeFormatter
import com.fsck.k9.ui.resolveColorAttribute
import com.fsck.k9.ui.resolveDrawableAttribute
import kotlin.math.max

private const val FOOTER_ID = 1L

private const val TYPE_MESSAGE = 0
private const val TYPE_FOOTER = 1

class MessageListAdapter internal constructor(
    theme: Theme,
    private val res: Resources,
    private val layoutInflater: LayoutInflater,
    private val contactsPictureLoader: ContactPictureLoader,
    private val listItemListener: MessageListItemActionListener,
    private val appearance: MessageListAppearance,
    private val relativeDateTimeFormatter: RelativeDateTimeFormatter
) : RecyclerView.Adapter<MessageListViewHolder>() {

    private val forwardedIcon: Drawable = theme.resolveDrawableAttribute(R.attr.messageListForwarded)
    private val answeredIcon: Drawable = theme.resolveDrawableAttribute(R.attr.messageListAnswered)
    private val forwardedAnsweredIcon: Drawable = theme.resolveDrawableAttribute(R.attr.messageListAnsweredForwarded)
    private val previewTextColor: Int = theme.resolveColorAttribute(R.attr.messageListPreviewTextColor)
    private val activeItemBackgroundColor: Int = theme.resolveColorAttribute(R.attr.messageListActiveItemBackgroundColor)
    private val selectedItemBackgroundColor: Int = theme.resolveColorAttribute(R.attr.messageListSelectedBackgroundColor)
    private val regularItemBackgroundColor: Int = theme.resolveColorAttribute(R.attr.messageListRegularItemBackgroundColor)
    private val readItemBackgroundColor: Int = theme.resolveColorAttribute(R.attr.messageListReadItemBackgroundColor)
    private val unreadItemBackgroundColor: Int = theme.resolveColorAttribute(R.attr.messageListUnreadItemBackgroundColor)

    var messages: List<MessageListItem> = emptyList()
        @SuppressLint("NotifyDataSetChanged")
        set(value) {
            val oldMessageList = field

            field = value
            messagesMap = value.associateBy { it.uniqueId }

            if (selected.isNotEmpty()) {
                val uniqueIds = messagesMap.keys
                selected = selected.intersect(uniqueIds)
            }

            val diffResult = DiffUtil.calculateDiff(
                MessageListDiffCallback(oldMessageList = oldMessageList, newMessageList = value)
            )
            diffResult.dispatchUpdatesTo(this)
        }

    private var messagesMap = emptyMap<Long, MessageListItem>()

    var activeMessage: MessageReference? = null
        set(value) {
            if (value == field) return

            val oldPosition = getPosition(field)
            val newPosition = getPosition(value)

            field = value

            oldPosition?.let { position -> notifyItemChanged(position) }
            newPosition?.let { position -> notifyItemChanged(position) }
        }

    var selected: Set<Long> = emptySet()
        private set(value) {
            if (value == field) return

            // Selection removed
            field.asSequence()
                .filter { uniqueId -> uniqueId !in value }
                .mapNotNull { uniqueId -> messagesMap[uniqueId] }
                .mapNotNull { messageListItem -> getPosition(messageListItem) }
                .forEach { position ->
                    notifyItemChanged(position)
                }

            // Selection added
            value.asSequence()
                .filter { uniqueId -> uniqueId !in field }
                .mapNotNull { uniqueId -> messagesMap[uniqueId] }
                .mapNotNull { messageListItem -> getPosition(messageListItem) }
                .forEach { position ->
                    notifyItemChanged(position)
                }

            field = value
            selectedCount = calculateSelectionCount()
        }

    val selectedMessages: List<MessageListItem>
        get() = selected.map { messagesMap[it]!! }

    val isAllSelected: Boolean
        get() = selected.isNotEmpty() && selected.size == messages.size

    var selectedCount: Int = 0
        private set

    var footerText: String? = null
        set(value) {
            if (field == value) return

            val hadFooterText = field != null
            val previousFooterPosition = footerPosition
            field = value

            if (hadFooterText) {
                if (value == null) {
                    notifyItemRemoved(previousFooterPosition)
                } else {
                    notifyItemChanged(footerPosition)
                }
            } else {
                notifyItemInserted(footerPosition)
            }
        }

    private val hasFooter: Boolean
        get() = footerText != null

    private val lastMessagePosition: Int
        get() = messages.lastIndex

    private val footerPosition: Int
        get() = if (hasFooter) lastMessagePosition + 1 else NO_POSITION

    private inline val subjectViewFontSize: Int
        get() = if (appearance.senderAboveSubject) {
            appearance.fontSizes.messageListSender
        } else {
            appearance.fontSizes.messageListSubject
        }

    private val messageClickedListener = OnClickListener { view: View ->
        val messageListItem = getItemFromView(view) ?: return@OnClickListener
        listItemListener.onMessageClicked(messageListItem)
    }

    private val messageLongClickedListener = OnLongClickListener { view: View ->
        getItemFromView(view)?.let { messageListItem ->
            listItemListener.onToggleMessageSelection(messageListItem)
        }
        true
    }

    private val footerClickListener = OnClickListener {
        listItemListener.onFooterClicked()
    }

    private val flagClickListener = OnClickListener { view: View ->
        val messageListItem = getItemFromView(view) ?: return@OnClickListener
        listItemListener.onToggleMessageFlag(messageListItem)
    }

    private val contactPictureClickListener = OnClickListener { view: View ->
        val parentView = view.parent.parent as View
        val messageListItem = getItemFromView(parentView) ?: return@OnClickListener
        listItemListener.onToggleMessageSelection(messageListItem)
    }

    init {
        setHasStableIds(true)
    }

    private fun recipientSigil(toMe: Boolean, ccMe: Boolean) = when {
        toMe -> res.getString(R.string.messagelist_sent_to_me_sigil) + " "
        ccMe -> res.getString(R.string.messagelist_sent_cc_me_sigil) + " "
        else -> ""
    }

    override fun getItemCount(): Int = messages.size + if (hasFooter) 1 else 0

    override fun getItemId(position: Int): Long {
        return if (position <= lastMessagePosition) {
            messages[position].uniqueId
        } else {
            FOOTER_ID
        }
    }

    override fun getItemViewType(position: Int): Int {
        return if (position <= lastMessagePosition) TYPE_MESSAGE else TYPE_FOOTER
    }

    private fun getItem(position: Int): MessageListItem = messages[position]

    fun getItemById(uniqueId: Long): MessageListItem? {
        return messagesMap[uniqueId]
    }

    fun getItem(messageReference: MessageReference): MessageListItem? {
        return messages.firstOrNull {
            it.account.uuid == messageReference.accountUuid &&
                it.folderId == messageReference.folderId &&
                it.messageUid == messageReference.uid
        }
    }

    fun getPosition(messageListItem: MessageListItem): Int? {
        return messages.indexOf(messageListItem).takeIf { it != -1 }
    }

    private fun getPosition(messageReference: MessageReference?): Int? {
        if (messageReference == null) return null

        return messages.indexOfFirst {
            messageReference.equals(it.account.uuid, it.folderId, it.messageUid)
        }.takeIf { it != -1 }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageListViewHolder {
        return when (viewType) {
            TYPE_MESSAGE -> createMessageViewHolder(parent)
            TYPE_FOOTER -> createFooterViewHolder(parent)
            else -> error("Unsupported type: $viewType")
        }
    }

    private fun createMessageViewHolder(parent: ViewGroup?): MessageViewHolder {
        val view = layoutInflater.inflate(R.layout.message_list_item, parent, false)
        view.setOnClickListener(messageClickedListener)
        view.setOnLongClickListener(messageLongClickedListener)

        val holder = MessageViewHolder(view)

        view.findViewById<View>(R.id.contact_picture_container).isVisible = appearance.showContactPicture
        holder.contactPicture.setOnClickListener(contactPictureClickListener)

        holder.chip.isVisible = appearance.showAccountChip

        appearance.fontSizes.setViewTextSize(holder.subject, subjectViewFontSize)

        appearance.fontSizes.setViewTextSize(holder.date, appearance.fontSizes.messageListDate)

        // 1 preview line is needed even if it is set to 0, because subject is part of the same text view
        holder.preview.setLines(max(appearance.previewLines, 1))
        appearance.fontSizes.setViewTextSize(holder.preview, appearance.fontSizes.messageListPreview)
        appearance.fontSizes.setViewTextSize(holder.threadCount, appearance.fontSizes.messageListSubject) // thread count is next to subject

        holder.flagged.isVisible = appearance.stars
        holder.flagged.tag = holder
        holder.flagged.setOnClickListener(flagClickListener)

        view.tag = holder

        return holder
    }

    private fun createFooterViewHolder(parent: ViewGroup): MessageListViewHolder {
        val view = layoutInflater.inflate(R.layout.message_list_item_footer, parent, false)
        view.setOnClickListener(footerClickListener)
        return FooterViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageListViewHolder, position: Int) {
        when (val viewType = getItemViewType(position)) {
            TYPE_MESSAGE -> {
                val messageListItem = getItem(position)
                bindMessageViewHolder(holder as MessageViewHolder, messageListItem)
            }
            TYPE_FOOTER -> {
                bindFooterViewHolder(holder as FooterViewHolder)
            }
            else -> {
                error("Unsupported type: $viewType")
            }
        }
    }

    private fun bindMessageViewHolder(holder: MessageViewHolder, messageListItem: MessageListItem) {
        val isSelected = selected.contains(messageListItem.uniqueId)
        val isActive = isActiveMessage(messageListItem)

        if (appearance.showContactPicture) {
            if (isSelected) {
                holder.contactPicture.isVisible = false
                holder.selected.isVisible = true
            } else {
                holder.selected.isVisible = false
                holder.contactPicture.isVisible = true
            }
        }

        with(messageListItem) {
            val maybeBoldTypeface = if (isRead) Typeface.NORMAL else Typeface.BOLD
            val displayDate = relativeDateTimeFormatter.formatDate(messageDate)
            val displayThreadCount = if (appearance.showingThreadedList) threadCount else 0
            val subject = MlfUtils.buildSubject(subject, res.getString(R.string.general_no_subject), displayThreadCount)

            if (appearance.showAccountChip) {
                val accountChipDrawable = holder.chip.drawable.mutate()
                DrawableCompat.setTint(accountChipDrawable, account.chipColor)
                holder.chip.setImageDrawable(accountChipDrawable)
            }

            if (appearance.stars) {
                holder.flagged.isChecked = isStarred
            }
            holder.uniqueId = uniqueId
            if (appearance.showContactPicture && holder.contactPicture.isVisible) {
                setContactPicture(holder.contactPicture, displayAddress)
            }
            setBackgroundColor(holder.itemView, isSelected, isRead, isActive)
            updateWithThreadCount(holder, displayThreadCount)
            val beforePreviewText = if (appearance.senderAboveSubject) subject else displayName
            val sigil = recipientSigil(toMe, ccMe)
            val messageStringBuilder = SpannableStringBuilder(sigil)
                .append(beforePreviewText)
            if (appearance.previewLines > 0) {
                val preview = getPreview(isMessageEncrypted, previewText)
                messageStringBuilder.append(" ").append(preview)
            }
            holder.preview.setText(messageStringBuilder, TextView.BufferType.SPANNABLE)

            formatPreviewText(holder.preview, beforePreviewText, sigil, isRead)

            holder.subject.typeface = Typeface.create(holder.subject.typeface, maybeBoldTypeface)
            if (appearance.senderAboveSubject) {
                holder.subject.text = displayName
            } else {
                holder.subject.text = subject
            }

            holder.date.text = displayDate
            holder.attachment.isVisible = hasAttachments

            val statusHolder = buildStatusHolder(isForwarded, isAnswered)
            if (statusHolder != null) {
                holder.status.setImageDrawable(statusHolder)
                holder.status.isVisible = true
            } else {
                holder.status.isVisible = false
            }
        }
    }

    private fun bindFooterViewHolder(holder: FooterViewHolder) {
        holder.text.text = footerText
    }

    private fun formatPreviewText(
        preview: TextView,
        beforePreviewText: CharSequence,
        sigil: String,
        messageRead: Boolean
    ) {
        val previewText = preview.text as Spannable

        val beforePreviewLength = beforePreviewText.length + sigil.length
        addBeforePreviewSpan(previewText, beforePreviewLength, messageRead)

        // Set span (color) for preview message
        previewText.setSpan(
            ForegroundColorSpan(previewTextColor),
            beforePreviewLength,
            previewText.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }

    private fun addBeforePreviewSpan(text: Spannable, length: Int, messageRead: Boolean) {
        val fontSize = if (appearance.senderAboveSubject) {
            appearance.fontSizes.messageListSubject
        } else {
            appearance.fontSizes.messageListSender
        }

        if (fontSize != FontSizes.FONT_DEFAULT) {
            val span = AbsoluteSizeSpan(fontSize, true)
            text.setSpan(span, 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        if (!messageRead) {
            val span = StyleSpan(Typeface.BOLD)
            text.setSpan(span, 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun setContactPicture(contactPictureView: ImageView, displayAddress: Address?) {
        if (displayAddress != null) {
            contactsPictureLoader.setContactPicture(contactPictureView, displayAddress)
        } else {
            contactPictureView.setImageResource(R.drawable.ic_contact_picture)
        }
    }

    private fun buildStatusHolder(forwarded: Boolean, answered: Boolean): Drawable? {
        if (forwarded && answered) {
            return forwardedAnsweredIcon
        } else if (answered) {
            return answeredIcon
        } else if (forwarded) {
            return forwardedIcon
        }
        return null
    }

    private fun setBackgroundColor(view: View, selected: Boolean, read: Boolean, active: Boolean) {
        val backGroundAsReadIndicator = appearance.backGroundAsReadIndicator
        val backgroundColor = when {
            active -> activeItemBackgroundColor
            selected -> selectedItemBackgroundColor
            backGroundAsReadIndicator && read -> readItemBackgroundColor
            backGroundAsReadIndicator && !read -> unreadItemBackgroundColor
            else -> regularItemBackgroundColor
        }

        view.setBackgroundColor(backgroundColor)
    }

    private fun updateWithThreadCount(holder: MessageViewHolder, threadCount: Int) {
        if (threadCount > 1) {
            holder.threadCount.text = String.format("%d", threadCount)
            holder.threadCount.isVisible = true
        } else {
            holder.threadCount.isVisible = false
        }
    }

    private fun getPreview(isMessageEncrypted: Boolean, previewText: String): String {
        return if (isMessageEncrypted) {
            res.getString(R.string.preview_encrypted)
        } else {
            previewText
        }
    }

    private fun isActiveMessage(item: MessageListItem): Boolean {
        val activeMessage = this.activeMessage ?: return false

        return item.account.uuid == activeMessage.accountUuid &&
            item.folderId == activeMessage.folderId &&
            item.messageUid == activeMessage.uid
    }

    fun isSelected(item: MessageListItem): Boolean {
        return item.uniqueId in selected
    }

    fun toggleSelection(item: MessageListItem) {
        if (messagesMap[item.uniqueId] == null) {
            // MessageListItem is no longer in the list
            return
        }

        if (item.uniqueId in selected) {
            deselectMessage(item)
        } else {
            selectMessage(item)
        }
    }

    private fun selectMessage(item: MessageListItem) {
        selected = selected + item.uniqueId
    }

    private fun deselectMessage(item: MessageListItem) {
        selected = selected - item.uniqueId
    }

    fun selectAll() {
        val uniqueIds = messagesMap.keys.toSet()
        selected = uniqueIds
    }

    fun clearSelected() {
        selected = emptySet()
    }

    fun restoreSelected(selectedIds: Set<Long>) {
        if (selectedIds.isEmpty()) {
            clearSelected()
        } else {
            val uniqueIds = messagesMap.keys
            selected = selectedIds.intersect(uniqueIds)
        }
    }

    private fun calculateSelectionCount(): Int {
        if (selected.isEmpty()) {
            return 0
        }

        if (!appearance.showingThreadedList) {
            return selected.size
        }

        return messages
            .asSequence()
            .filter { it.uniqueId in selected }
            .sumOf { it.threadCount.coerceAtLeast(1) }
    }

    private fun getItemFromView(view: View): MessageListItem? {
        val messageViewHolder = view.tag as MessageViewHolder
        return getItemById(messageViewHolder.uniqueId)
    }
}

private class MessageListDiffCallback(
    private val oldMessageList: List<MessageListItem>,
    private val newMessageList: List<MessageListItem>
) : DiffUtil.Callback() {
    override fun getOldListSize(): Int = oldMessageList.size

    override fun getNewListSize(): Int = newMessageList.size

    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        return oldMessageList[oldItemPosition].uniqueId == newMessageList[newItemPosition].uniqueId
    }

    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        return oldMessageList[oldItemPosition] == newMessageList[newItemPosition]
    }
}

interface MessageListItemActionListener {
    fun onMessageClicked(messageListItem: MessageListItem)
    fun onToggleMessageSelection(item: MessageListItem)
    fun onToggleMessageFlag(item: MessageListItem)
    fun onFooterClicked()
}
