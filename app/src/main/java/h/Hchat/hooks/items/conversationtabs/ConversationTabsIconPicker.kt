package h.Hchat.hooks.items.conversationtabs

import android.app.Activity
import android.content.Intent
import h.Hchat.hooks.items.conversationgroup.ConversationGroupFileRequest
import h.Hchat.hooks.items.conversationgroup.ConversationGroupFileResultHooks
import h.Hchat.utils.HLog
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object ConversationTabsIconPicker {
    private data class Pending(
        val tabKey: String,
        val request: ConversationGroupFileRequest<ConversationTabsIconPickResult>
    )

    private val nextRequestCode = AtomicInteger(REQUEST_CODE_START)
    private val pending = ConcurrentHashMap<Int, Pending>()
    private val requests = ConcurrentHashMap<Int, ConversationGroupFileRequest<ConversationTabsIconPickResult>>()
    private val hooks = ConversationGroupFileResultHooks(::onResult)

    @JvmStatic
    @Synchronized
    fun launch(
        activity: Activity,
        tabKey: String,
        callback: (ConversationTabsIconPickResult) -> Unit
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        val key = tabKey.trim()
        if (!ConversationTabsIconStore.isSupportedTabKey(key)) {
            callback(ConversationTabsIconPickResult.FAILED)
            return
        }
        if (!hooks.ensure(activity.javaClass)) {
            cancelAll()
            callback(ConversationTabsIconPickResult.FAILED)
            return
        }
        val requestCode = allocateRequestCode()
        val context = activity.applicationContext
        val request = ConversationGroupFileRequest.create(activity, callback, onDiscard = { result ->
            if (result is ConversationTabsIconPickResult.Saved) {
                ConversationTabsIconStore.delete(context, result.path)
            }
        }) {
            pending.remove(requestCode)
            requests.remove(requestCode)
        }
        if (request == null) {
            callback(ConversationTabsIconPickResult.FAILED)
            return
        }
        requests[requestCode] = request
        pending[requestCode] = Pending(key, request)
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { activity.startActivityForResult(intent, requestCode) }
            .onFailure {
                val fallback = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching {
                    activity.startActivityForResult(
                        Intent.createChooser(fallback, "选择标签图标"),
                        requestCode
                    )
                }.onFailure {
                    pending.remove(requestCode)
                    request.deliver(ConversationTabsIconPickResult.FAILED)
                }
            }
    }

    @Synchronized
    fun cancelAll() {
        requests.values.toList().forEach { it.cancel() }
        pending.clear()
        hooks.clear()
    }

    private fun onResult(activity: Activity, code: Int, resultCode: Int, data: Intent?) {
        val selection = pending[code] ?: return
        val request = selection.request
        if (request.activity.get() !== activity || !pending.remove(code, selection)) return
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null) {
            request.deliver(ConversationTabsIconPickResult.CANCELLED)
            return
        }
        val context = activity.applicationContext
        val key = selection.tabKey
        request.execute(task = { canceled ->
            if (canceled.get()) throw CancellationException()
            // 不捕获页面或选择回调；取消后晚到的文件由请求统一回收。
            ConversationTabsIconStore.saveFromUri(context, key, uri)
                ?.let(ConversationTabsIconPickResult::Saved)
                ?: ConversationTabsIconPickResult.FAILED
        }, failure = { error ->
            HLog.e("[Hchat:ConversationTabs] 读取标签图标失败", error)
            ConversationTabsIconPickResult.FAILED
        })
    }

    private fun allocateRequestCode(): Int {
        repeat(REQUEST_CODE_END - REQUEST_CODE_START + 1) {
            val candidate = nextRequestCode.updateAndGet { current ->
                if (current >= REQUEST_CODE_END) REQUEST_CODE_START else current + 1
            }
            if (!requests.containsKey(candidate)) return candidate
        }
        error("标签图标选择请求已满")
    }

    private const val REQUEST_CODE_START = 0x7a10
    private const val REQUEST_CODE_END = 0x7aff
}

sealed class ConversationTabsIconPickResult {
    data class Saved(val path: String) : ConversationTabsIconPickResult()
    object CANCELLED : ConversationTabsIconPickResult()
    object FAILED : ConversationTabsIconPickResult()
}
