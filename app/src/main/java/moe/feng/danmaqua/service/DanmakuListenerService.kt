package moe.feng.danmaqua.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import kotlinx.coroutines.*
import moe.feng.danmaqua.Danmaqua
import moe.feng.danmaqua.Danmaqua.EXTRA_ACTION
import moe.feng.danmaqua.Danmaqua.EXTRA_START_ROOM
import moe.feng.danmaqua.IDanmakuListenerCallback
import moe.feng.danmaqua.IDanmakuListenerService
import moe.feng.danmaqua.R
import moe.feng.danmaqua.api.bili.DanmakuListener
import moe.feng.danmaqua.data.HistoryManager
import moe.feng.danmaqua.event.SettingsChangedListener
import moe.feng.danmaqua.model.BiliChatDanmaku
import moe.feng.danmaqua.model.BiliChatMessage
import moe.feng.danmaqua.ui.floating.FloatingWindowHolder
import moe.feng.danmaqua.util.DanmakuFilter
import moe.feng.danmaqua.util.ListenerServiceNotificationHelper
import kotlinx.TAG
import androidx.content.eventsHelper
import java.io.EOFException
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class DanmakuListenerService :
    Service(), CoroutineScope by MainScope(),
    DanmakuListener.Callback, SettingsChangedListener {

    companion object {

        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_RECONNECT = "reconnect"

        fun startServiceAndConnect(context: Context, roomId: Long) {
            val intent = Intent(context, DanmakuListenerService::class.java)
            intent.putExtra(EXTRA_ACTION, ACTION_START)
            intent.putExtra(EXTRA_START_ROOM, roomId)
            ContextCompat.startForegroundService(context, intent)
        }

    }

    private val binder: AidlInterfaceImpl = AidlInterfaceImpl()

    private var danmakuFilter: DanmakuFilter = DanmakuFilter.fromSettings()

    private var lastConnectedRoom: Long? = null
    private var danmakuListener: DanmakuListener? = null
    private val serviceCallbacks: MutableList<CallbackHolder> = mutableListOf()

    private var floatingHolder: FloatingWindowHolder? = null
    private val isFloatingShowing: Boolean get() = floatingHolder?.isAdded == true

    private val notiHelper: ListenerServiceNotificationHelper =
        ListenerServiceNotificationHelper(this)

    private val connectLock: Lock = ReentrantLock()

    override fun onBind(intent: Intent?): IBinder? {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Called onCreate")

        // Initialize status notification
        notiHelper.onCreate()

        eventsHelper.registerListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Called onDestroy")
        disconnect()
        destroyFloatingView()
        try {
            stopForeground(true)
            notiHelper.cancel()
        } catch (e: Exception) {

        }
        eventsHelper.unregisterListener(this)
        this.cancel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.getStringExtra(EXTRA_ACTION)
        Log.d(TAG, "onStartCommand: action=$action")
        when (action) {
            ACTION_START -> {
                notiHelper.startForegroundForService()
                val roomId = intent.getLongExtra(EXTRA_START_ROOM, 0)
                if (roomId > 0) {
                    connect(roomId)
                    createFloatingView()
                }
            }
            ACTION_STOP -> {
                stopSelf()
            }
            ACTION_RECONNECT -> {
                lastConnectedRoom?.run(::connect)
            }
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        floatingHolder?.onConfigurationChanged(newConfig)
    }

    override fun onSettingsChanged() {
        danmakuFilter = DanmakuFilter.fromSettings()
        floatingHolder?.loadSettings()

        if (danmakuListener?.isConnected == true) {
            launch {
                if (Danmaqua.Settings.saveHistory && !HistoryManager.isRecording()) {
                    HistoryManager.startRecord(
                        this@DanmakuListenerService, danmakuListener?.roomId!!)
                } else if (!Danmaqua.Settings.saveHistory && HistoryManager.isRecording()) {
                    HistoryManager.stopRecord()
                }
            }
        }
    }

    private fun createFloatingView() = launch {
        Log.d(TAG, "createFloatingView")
        if (floatingHolder == null) {
            floatingHolder = FloatingWindowHolder.create(this@DanmakuListenerService)
            floatingHolder?.onGetDanmakuFilter = { danmakuFilter }
        }
        floatingHolder?.addToWindowManager()
    }

    private fun destroyFloatingView() {
        Log.d(TAG, "destroyFloatingView")
        floatingHolder?.removeFromWindowManager()
        floatingHolder = null
    }

    private fun connect(roomId: Long) {
        connectLock.withLock {
            Log.d(TAG, "connect roomId=$roomId")
            try {
                danmakuListener?.close()
            } catch (ignored: Exception) {

            }
            try {
                lastConnectedRoom = roomId
                danmakuListener = DanmakuListener(roomId, this@DanmakuListenerService)
                launch {
                    danmakuListener?.connect()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                for ((callback, _) in serviceCallbacks) {
                    callback.onConnectFailed(0)
                }
            }
        }
    }

    private fun disconnect() {
        Log.d(TAG, "disconnect")
        try {
            danmakuListener?.close()
        } catch (e: Exception) {

        }
    }

    override fun onConnect() {
        danmakuListener?.let {
            notiHelper.showConnectedNotification(it.roomId)

            if (Danmaqua.Settings.saveHistory) {
                launch { HistoryManager.startRecord(this@DanmakuListenerService, it.roomId) }
            }

            floatingHolder?.addSystemMessage(HtmlCompat.fromHtml(
                getString(R.string.sys_msg_connected_to_room, it.roomId), 0
            ).toString())

            for ((callback, _) in serviceCallbacks) {
                callback.onConnect(it.roomId)
            }
        } ?: Log.e(TAG, "DanmakuListener is null but called onConnect.")
    }

    override fun onDisconnect(userReason: Boolean) {
        notiHelper.showDisconnectedNotification(lastConnectedRoom)

        launch { HistoryManager.stopRecord() }

        floatingHolder?.addSystemMessage(getString(R.string.sys_msg_disconnected))

        for ((callback, _) in serviceCallbacks) {
            callback.onDisconnect()
        }

        if (!userReason) {
            launch {
                delay(3000L)
                lastConnectedRoom?.let {
                    connect(it)
                }
            }
        }
    }

    override fun onHeartbeat(online: Int) {
        for ((callback, _) in serviceCallbacks) {
            callback.onHeartbeat(online)
        }
    }

    override fun onMessage(msg: BiliChatMessage) {
        if (msg !is BiliChatDanmaku) {
            return
        }
        Log.d(TAG, "onMessage: $msg")
        launch {
            if (withContext(Dispatchers.IO) { danmakuFilter(msg) }) {
                for ((callback, _) in serviceCallbacks) {
                    callback.onReceiveDanmaku(msg)
                }
                floatingHolder?.addDanmaku(msg)
            }
            HistoryManager.record(msg)
        }
    }

    override fun onFailure(t: Throwable) {
        if (t is EOFException) {
            // Ignore EOF
            return
        }
        Log.e(TAG, "onFailure", t)
    }

    inner class AidlInterfaceImpl : IDanmakuListenerService.Stub() {

        override fun connect(roomId: Long) {
            this@DanmakuListenerService.connect(roomId)
        }

        override fun disconnect() {
            this@DanmakuListenerService.disconnect()
        }

        override fun showFloating() {
            createFloatingView()
        }

        override fun hideFloating() {
            destroyFloatingView()
        }

        override fun requestHeartbeat() {
            danmakuListener?.requestHeartbeat()
        }

        override fun isConnected(): Boolean {
            return danmakuListener?.isConnected == true
        }

        override fun isFloatingShowing(): Boolean {
            return this@DanmakuListenerService.isFloatingShowing
        }

        override fun getRoomId(): Long {
            return danmakuListener?.roomId ?: 0L
        }

        override fun registerCallback(callback: IDanmakuListenerCallback?, filter: Boolean) {
            if (callback == null) {
                return
            }
            val callbackHolder = serviceCallbacks.find { it.callback == callback }
            if (callbackHolder == null) {
                serviceCallbacks += CallbackHolder(callback, filter)
            } else {
                callbackHolder.filter = true
            }
        }

        override fun unregisterCallback(callback: IDanmakuListenerCallback?) {
            if (callback == null) {
                return
            }
            serviceCallbacks.removeAll { it.callback == callback }
        }

        fun changeFWStatus(){
            floatingHolder?.changeFWStatus()
        }

    }

    private data class CallbackHolder(
        val callback: IDanmakuListenerCallback,
        var filter: Boolean
    )

}