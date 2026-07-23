package com.Johnny.wcx.features.items.moments

import androidx.activity.ComponentActivity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.Johnny.reflekt.reflekt
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.minutes

@Feature(name = "自动刷新", categories = ["朋友圈"], description = "定时自动刷新朋友圈列表")
object AutoRefresh : ClickableFeature(), IResolveDex {

    private const val TAG = "AutoRefresh"
    private const val DEFAULT_INTERVAL_MINUTES = 30L

    private var intervalMinutes by WePrefs.prefOption("moments_auto_refresh_interval_minutes", DEFAULT_INTERVAL_MINUTES)

    fun interface IRefreshListener {
        fun onRefresh()
    }

    private val refreshListeners = CopyOnWriteArrayList<IRefreshListener>()

    fun addListener(listener: IRefreshListener) {
        refreshListeners.add(listener)
    }

    fun removeListener(listener: IRefreshListener) {
        refreshListeners.remove(listener)
    }

    private var refreshJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val methodGetSnsCore by dexMethod {
        matcher {
            usingEqStrings("getCore", "com.tencent.mm.plugin.sns.model.SnsCore")
        }
    }

    private val methodDoFpList by dexMethod {
        matcher {
            usingEqStrings("doFpList", $$"com.tencent.mm.plugin.sns.model.SnsLogic$SnsServer")
        }
    }

    private val snsLogicSnsServer by lazy {
        val snsCore = methodGetSnsCore.method.invoke(null)
        snsCore.reflekt().firstField {
            type = methodDoFpList.method.declaringClass
        }.get()!!
    }

    override fun onEnable() {
        startRefreshingJob()
    }

    override fun onDisable() {
        refreshJob?.cancel()
        refreshJob = null
    }

    private fun startRefreshingJob() {
        refreshJob?.cancel()
        val interval = intervalMinutes.coerceAtLeast(1L)
        refreshJob = scope.launch {
            while (isActive) {
                delay(interval.minutes)
                refreshMoments()
            }
        }
    }

    private fun refreshMoments() {
        try {
            WeLogger.d(TAG, "refreshing moments")
            methodDoFpList.method.invoke(
                snsLogicSnsServer,
                1, "@__weixintimtline", false, false, 0
            )
            refreshListeners.forEach { it.onRefresh() }
        } catch (e: Exception) {
            WeLogger.w(TAG, "exception during refreshing: ${e.message}")
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var intervalInput by remember { mutableStateOf(intervalMinutes.toString()) }

            AlertDialogContent(
                title = { Text("自动刷新") },
                text = {
                    DefaultColumn(Modifier.verticalScroll(rememberScrollState())) {
                        TextField(
                            value = intervalInput,
                            onValueChange = { intervalInput = it.filter { c -> c.isDigit() }.take(4) },
                            label = { Text("刷新间隔 (分钟)") },
                            supportingText = { Text("每隔指定时间自动刷新一次朋友圈列表") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        intervalMinutes = (intervalInput.toLongOrNull() ?: DEFAULT_INTERVAL_MINUTES).coerceAtLeast(1L)
                        if (isEnabled) startRefreshingJob()
                        showToast("已保存")
                        onDismiss()
                    }) { Text("确定") }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } }
            )
        }
    }
}
