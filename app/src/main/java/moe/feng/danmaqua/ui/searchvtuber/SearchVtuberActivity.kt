package moe.feng.danmaqua.ui.searchvtuber
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.*
import androidx.content.eventsHelper
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.view.avatarUrl
import com.drakeet.multitype.MultiTypeAdapter
import kotlinx.android.synthetic.main.searchvtuber_activity.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.feng.common.eventshelper.of
import moe.feng.danmaqua.R
import moe.feng.danmaqua.api.bili.RoomApi
import moe.feng.danmaqua.api.bili.UserApi
import moe.feng.danmaqua.event.MainDrawerCallback
import moe.feng.danmaqua.model.Subscription
import moe.feng.danmaqua.ui.common.BaseActivity
import moe.feng.danmaqua.ui.common.list.RaisedViewScrollListener
import moe.feng.danmaqua.ui.common.view.CircleImageView
import moe.feng.danmaqua.ui.main.DrawerViewFragment
import moe.feng.danmaqua.ui.main.list.SubscriptionItemViewDelegate
import moe.feng.danmaqua.ui.subscription.ManageSubscriptionActivity

class SearchVtuberActivity : BaseActivity(){

    private val drawerListAdapter: DrawerListAdapter = DrawerListAdapter()
    private val context = this

    companion object {

        const val REQUEST_CODE_NEW_SUBSCRIPTION = 10000
        const val REQUEST_CODE_MANAGE_SUBSCRIPTION = 10001

    }

    /**
     * 屏幕适配还没做
     */

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.searchvtuber_activity)


        searchvtuber_user_recyclerview.adapter = drawerListAdapter
        searchvtuber_user_recyclerview.addOnScrollListener(
            RaisedViewScrollListener(
                searchvtuber_bottom_textview,
            context.resources.getDimension(R.dimen.subscription_list_raised_view_elevation),
            0F,
            context.resources.getInteger(android.R.integer.config_shortAnimTime).toLong()
        )
        )


        /**
         * @author 醉梦 2022/4/5
         * 添加返回按钮，回到主界面
         */

        searchvtuber_back_imageview.setOnClickListener{
            finish()
        }

        /**
         * @author 醉梦 2022/4/4 跳转到管理订阅界面
         */

        searchvtuber__manage_imageview.setOnClickListener {
            val intent = Intent(this@SearchVtuberActivity, ManageSubscriptionActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            startActivityForResult(intent, REQUEST_CODE_MANAGE_SUBSCRIPTION)
        }


        updateAdapterData(scrollToSelection = true)

        searchvtuber_search_Button.setOnClickListener {
            lifecycleScope.launchWhenResumed {
                val id = searchvtuber_roomid_edittext.text.toString().trim().toLongOrNull() ?: 0L
                if (id <= 0) {
                    Toast.makeText(
                        this@SearchVtuberActivity,
                        R.string.toast_invalid_room_id,
                        Toast.LENGTH_SHORT
                    ).show()
                }
                val idResultInDB = database.subscriptions().findByRoomId(id)
                if (idResultInDB != null) {
                    showAlertDialog {
                        titleRes = R.string.subscribe_existing_streamer_dialog_title
                        message = getString(
                            R.string.subscribe_existing_streamer_dialog_message,
                            idResultInDB.username)
                        okButton {
                            drawerListAdapter.notifyDataSetChanged()
                        }
                    }
                    return@launchWhenResumed
                }
                try {
                    val subscription = getSubscription(id)
                    if (subscription != null) {
                        buildAlertDialog {
                            titleRes = R.string.confirm_subscribe_streamer_dialog_title
                            inflateView(R.layout.new_subscription_confirm_dialog_layout) {
                                onDialogViewCreated(it, savedInstanceState,subscription)
                            }
                            positiveButton(R.string.action_subscribe) {
                                //onPositiveButtonClick()
                                lifecycleScope.launch {
                                    val dao = database.subscriptions()
                                    if (dao.findByUid(subscription.uid) == null) {
                                        if (dao.findSelected() == null) {
                                            subscription.selected = true
                                        }
                                        dao.add(subscription)
                                    }
                                    val items = dao.getAll()
                                    updateAdapterData(items)
                                }
                            }
                            cancelButton()
                        }.show()
                        return@launchWhenResumed
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                buildAlertDialog {
                    titleRes = R.string.search_room_id_no_result_dialog_title
                    messageRes = R.string.search_room_id_no_result_dialog_message
                    okButton()
                }.show()
            }
        }

    }


    private inner class DrawerListAdapter : MultiTypeAdapter(),
        SubscriptionItemViewDelegate.Callback{

        val subscriptionItemDelegate =
            SubscriptionItemViewDelegate(this)
        //val subscriptionAddDelegate = SubscriptionAddButtonViewDelegate(this)
        //val subscriptionManageDelegate = SubscriptionManageButtonViewDelegate(this)

        init {
            register(subscriptionItemDelegate)
            // register(subscriptionAddDelegate)
            //register(subscriptionManageDelegate)
        }

        override fun onSubscriptionItemClick(item: Subscription) {
            lifecycleScope.launch {
                val dao = database.subscriptions()
                val items = dao.getAll()
                items.forEach {
                    it.selected = it.uid == item.uid
                    dao.update(it)
                }
                updateAdapterData(items)
                context.eventsHelper.of<MainDrawerCallback>()
                    .onSubscriptionChange(item)
            }
        }

        override fun onSubscriptionItemLongClick(item: Subscription) {
            context.showAlertDialog {
                titleRes = R.string.unsubscribe_dialog_title
                message = getString(R.string.unsubscribe_dialog_message, item.username)
                yesButton {
                    lifecycleScope.launch {
                        val dao = database.subscriptions()
                        val lastSelected = item.selected
                        dao.delete(item)
                        val items = dao.getAll()
                        if (lastSelected) {
                            var selectedItem: Subscription? = null
                            items.forEachIndexed { index, value ->
                                if (index == 0) {
                                    value.selected = true
                                    selectedItem = value
                                } else {
                                    value.selected = false
                                }
                                dao.update(value)
                            }
                            context.eventsHelper.of<MainDrawerCallback>()
                                .onSubscriptionChange(selectedItem)
                        }
                        updateAdapterData(items)
                    }
                }
                noButton()
            }
        }

    }

    private fun updateAdapterData(
        updateItems: List<Subscription>? = null,
        scrollToSelection: Boolean = false
    ) = lifecycleScope.launch {
        val oldItems = drawerListAdapter.items
        val newItems = withContext(Dispatchers.IO) {
            val result = mutableListOf<Any>()
            result.addAll(updateItems ?: database.subscriptions().getAll())
            result
        }
        val callback = DrawerListDiffCallback(oldItems, newItems)
        val diffResult = DiffUtil.calculateDiff(callback)
        drawerListAdapter.items = newItems
        diffResult.dispatchUpdatesTo(drawerListAdapter)
        if (scrollToSelection) {
            for ((index, item) in newItems.withIndex()) {
                if ((item as? Subscription)?.selected == true) {
                    //drawerList.scrollToPosition(index)
                    break
                }
            }
        }
    }

    private class DrawerListDiffCallback(
        val oldItems: List<Any>,
        val newItems: List<Any>
    ) : DiffUtil.Callback() {

        override fun getOldListSize(): Int {
            return oldItems.size
        }

        override fun getNewListSize(): Int {
            return newItems.size
        }

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val oldItem = oldItems[oldItemPosition]
            val newItem = newItems[newItemPosition]
            return if (oldItem is Subscription && newItem is Subscription) {
                oldItem.uid == newItem.uid
            } else {
                oldItem == newItem
            }
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val oldItem = oldItems[oldItemPosition]
            val newItem = newItems[newItemPosition]
            // Subscription implemented Object#equals(other) so we can use == operation to compare.
            return oldItem == newItem
        }

    }

    suspend fun getSubscription(roomId: Long): Subscription? = withContext(Dispatchers.IO) {
        try {
            val roomInitInfo = RoomApi.getRoomInitInfo(roomId)
            val spaceInfo = UserApi.getSpaceInfo(roomInitInfo.data.uid)
            Subscription(spaceInfo.data.uid, roomId, spaceInfo.data.name, spaceInfo.data.face)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun onDialogViewCreated(
        view: View,
        savedInstanceState: Bundle?,
        subscription: Subscription
    ) {
        val avatarView = view.findViewById<CircleImageView>(R.id.avatarView)
        val usernameView = view.findViewById<TextView>(R.id.usernameView)
        val uidView = view.findViewById<TextView>(R.id.uidView)
        avatarView.avatarUrl = subscription.avatar
        usernameView.text = subscription.username
        uidView.text = getString(R.string.room_id_text_format, subscription.roomId)
    }


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_CODE_NEW_SUBSCRIPTION, REQUEST_CODE_MANAGE_SUBSCRIPTION -> {
                if (resultCode == Activity.RESULT_OK) {
                    lifecycleScope.launch {
                        val dao = database.subscriptions()
                        val selectedSubscription = dao.findSelected() ?: dao.getAll().firstOrNull()
                        selectedSubscription?.selected = true
                        selectedSubscription?.let { dao.update(it) }
                        context.eventsHelper.of<MainDrawerCallback>()
                            .onSubscriptionChange(selectedSubscription, false)
                        updateAdapterData()
                    }
                }
            }
        }
    }
}
