package moe.feng.danmaqua.ui.history.list

import android.view.View
import kotlinx.android.synthetic.main.danmaku_history_toggle_item.*
import moe.feng.danmaqua.R
import moe.feng.danmaqua.ui.history.list.ToggleViewDelegate.*
import moe.feng.danmaqua.ui.list.ItemBasedSimpleViewBinder
import moe.feng.danmaqua.ui.list.ItemBasedViewHolder
import moe.feng.danmaqua.ui.list.viewHolderCreatorOf

class ToggleViewDelegate(var callback: Callback? = null)
    : ItemBasedSimpleViewBinder<Item, ViewHolder>() {

    override val viewHolderCreator: ViewHolderCreator<ViewHolder>
        = viewHolderCreatorOf(R.layout.danmaku_history_toggle_item)

    interface Callback {

        fun onToggle()

    }

    class Item(var value: Boolean)

    inner class ViewHolder(itemView: View) : ItemBasedViewHolder<Item>(itemView) {

        override fun onItemClick() {
            callback?.onToggle()
        }

        override fun onBind() {
            switchView.isChecked = data.value
        }

    }

}