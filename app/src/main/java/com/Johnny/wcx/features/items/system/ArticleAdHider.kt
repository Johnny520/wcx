package com.Johnny.wcx.features.items.system

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.ValueCallback
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.TargetProcesses
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.reflection.ClassLoaders
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.json.JSONObject
import org.json.JSONTokener
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Test branch v7: article-scoped CSS plus inline DOM filtering, no blocking scan thread.
 * Each WebView has an independent callback deadline; stale pages cannot delay a live article.
 * The existing qe iframe rule is retained as an experimental rule, restricted to article pages.
 * Explicit ad slots cover body/footer/comment ads; no parent/text-based blanket deletion.
 */
@Feature(
    name = "隐藏文章内广告卡",
    categories = ["系统与隐私"],
    description = "测试版 v7：异步清理文章广告，监听正文、底部及留言流动态广告节点"
)
object ArticleAdHider : SwitchFeature() {
    private const val TAG = "ArticleAdHider"
    override val defaultEnabled: Boolean = true
    override val shouldLoadInCurrentProcess: Boolean = true

    // Exported by tests directly from this literal: this is the exact script sent to WeChat.
    internal val HIDE_JS = """
(function () {
    if (window.__wcxArticleAdV6) window.__wcxArticleAdV6.stop();
    var key = '__wcxArticleAdV7';
    function article() {
        return location.hostname === 'mp.weixin.qq.com' &&
            /^\/(s|s\/|mp\/appmsg\/show)(\?|$)/.test(location.pathname + location.search);
    }
    var previous = window[key];
    if (!article()) {
        if (previous) previous.stop();
        return JSON.stringify({article:false});
    }
    if (previous) { previous.scan(); return previous.report(); }
    var stopped = false, total = 0, reapplied = 0;
    var changed = [], hidden = new Map();
    var styleId = '__wcx_article_ads_v7_style';
    var sheet = null;
    function ensureStyle() {
        if (sheet && sheet.isConnected) return;
        sheet = document.createElement('style');
        sheet.id = styleId;
        sheet.textContent = '#js_ad_area,#js_bottom_ad_area,#js_middle_ad_area,#js_top_ad_area,' +
            '#js_sponsor_ad_area,#js_comment_ad_area,#js_cmt_ad_area,' +
            '.mpda_bottom_container,.comment-ad-container,li.cidad_comment_constant_key,' +
            'iframe[src^="https://wxa.wxs.qq.com/tmpl/qe/"],' +
            'iframe[src^="http://wxa.wxs.qq.com/tmpl/qe/"],' +
            'iframe[src^="//wxa.wxs.qq.com/tmpl/qe/"]{display:none!important;}';
        (document.head || document.documentElement).appendChild(sheet);
    }
    var slots = '#js_ad_area,#js_bottom_ad_area,#js_middle_ad_area,#js_top_ad_area,#js_sponsor_ad_area,#js_comment_ad_area,#js_cmt_ad_area';
    function hide(el, rule) {
        if (hidden.has(el)) {
            if (el.style.getPropertyValue('display') !== 'none' || el.style.getPropertyPriority('display') !== 'important') {
                el.style.setProperty('display', 'none', 'important');
                reapplied++;
            }
            return;
        }
        // Store only our exact element/style changes; never collapse arbitrary ancestors.
        hidden.set(el, {value:el.style.getPropertyValue('display'), priority:el.style.getPropertyPriority('display')});
        el.style.setProperty('display', 'none', 'important');
        total++;
        if (changed.length < 8) changed.push(rule + ':' + el.tagName + '#' + (el.id || '').slice(0,60) + '.' + String(el.className || '').slice(0,80));
    }
    function restore(old, el) {
        if (el.style.getPropertyValue('display') === 'none' && el.style.getPropertyPriority('display') === 'important') {
            if (old.value) el.style.setProperty('display', old.value, old.priority);
            else el.style.removeProperty('display');
        }
    }
    function scan() {
        if (stopped) return;
        if (!article()) { state.stop(); return; }
        ensureStyle();
        // Do not retain detached nodes when WeChat replaces its preloaded article DOM.
        hidden.forEach(function (old, el) {
            if (!el.isConnected) {
                restore(old, el);
                hidden.delete(el);
            }
        });
        document.querySelectorAll(slots).forEach(function (el) { hide(el, 'slot'); });
        document.querySelectorAll('iframe[src]').forEach(function (el) {
            try {
                var u = new URL(el.getAttribute('src'), location.href);
                if (u.hostname === 'wxa.wxs.qq.com' && /^\/tmpl\/qe\//.test(u.pathname)) {
                    hide(el, 'qe-test');
                    // Confirmed by 2026-10-25 device log: collapse only specific ad wrappers.
                    var bottom = el.closest('.mpda_bottom_container');
                    if (bottom) hide(bottom, 'bottom-ad');
                    var comment = el.closest('.comment-ad-container');
                    if (comment) {
                        hide(comment, 'comment-ad');
                        var row = comment.closest('li.cidad_comment_constant_key');
                        if (row) hide(row, 'comment-ad-row');
                    }
                }
            } catch (_) {}
        });
    }
    // MutationObserver already batches changes in a microtask. Do not defer to a timer:
    // that yields another rendering opportunity and makes the ad flash before being hidden.
    var observer = new MutationObserver(scan);
    var state = {
        scan:scan,
        report:function () {
            var result = JSON.stringify({article:true,hidden:total,reapplied:reapplied,css:!!(sheet && sheet.isConnected),changed:changed});
            changed = [];
            return result;
        },
        stop:function () {
            if (stopped) return;
            stopped = true; observer.disconnect();
            window.removeEventListener('pagehide', state.stop);
            if (sheet && sheet.parentNode) sheet.parentNode.removeChild(sheet);
            sheet = null;
            hidden.forEach(function (old, el) {
                restore(old, el);
            });
            hidden.clear();
            if (window[key] === state) delete window[key];
        }
    };
    window[key] = state;
    observer.observe(document.documentElement, {subtree:true,childList:true,attributes:true,attributeFilter:['src','id','style','class']});
    window.addEventListener('pagehide', state.stop);
    scan();
    return state.report();
})()
""".trimIndent()

    private const val STOP_JS = "window.__wcxArticleAdV7 && window.__wcxArticleAdV7.stop();"
    private val handler = Handler(Looper.getMainLooper())
    // Accessed only on main thread, values never retain a WebView.
    private data class State(var request: Long = 0, var deadline: Long = 0, var failures: Int = 0,
                             var retryAt: Long = 0, var lastTotal: Int = -1, var lastReapplied: Int = -1)
    private val captured = WeakHashMap<Any, State>()
    private var running = false
    private var generation = 0
    private var nextRequest = 0L
    private val sweep = object : Runnable {
        override fun run() {
            if (!running) return
            captured.entries.toList().forEach { (view, state) -> evaluate(view, state) }
            handler.postDelayed(this, 500L)
        }
    }

    override fun onEnable() {
        generation++
        running = true
        for (name in listOf("android.webkit.WebView", "com.tencent.xweb.WebView", "com.tencent.smtt.sdk.WebView")) {
            val clazz = runCatching { Class.forName(name, false, ClassLoaders.HOST) }.getOrNull() ?: continue
            val capture = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject ?: return
                    handler.post {
                        if (running) {
                            val state = captured.getOrPut(view) { State() }
                            state.retryAt = 0
                            evaluate(view, state)
                        }
                    }
                }
            }
            XposedBridge.hookAllConstructors(clazz, capture).forEach { registerUnhook(it) }
            // Re-capture reused/preloaded WebViews when navigation restarts after a failed document.
            XposedBridge.hookAllMethods(clazz, "loadUrl", capture).forEach { registerUnhook(it) }
            for (method in listOf("reload", "goBack", "goForward", "loadData", "loadDataWithBaseURL")) {
                XposedBridge.hookAllMethods(clazz, method, capture).forEach { registerUnhook(it) }
            }
        }
        // Preloaded articles may switch documents without creating another WebView.
        registerUnhook(XposedBridge.hookMethod(
            android.app.Activity::class.java.getDeclaredMethod("onResume"),
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? android.app.Activity ?: return
                    if (!activity.javaClass.name.contains("WebView")) return
                    val epoch = generation
                    for (delay in longArrayOf(0L, 100L, 300L)) {
                        handler.postDelayed({
                            if (running && generation == epoch) {
                                captured.entries.toList().forEach { (view, state) ->
                                    state.retryAt = 0
                                    evaluate(view, state)
                                }
                            }
                        }, delay)
                    }
                }
            }
        ))
        handler.removeCallbacks(sweep)
        handler.post(sweep)
        WeLogger.i(TAG, "v7 asynchronous DOM hider enabled: ${TargetProcesses.currentName}")
    }

    private fun invokeJs(view: Any, script: String, callback: ValueCallback<String>) {
        val method = view.javaClass.methods.firstOrNull {
            it.name == "evaluateJavascript" && it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1].isAssignableFrom(callback.javaClass)
        } ?: error("evaluateJavascript unavailable: ${view.javaClass.name}")
        method.invoke(view, script, callback)
    }

    private fun evaluate(view: Any, state: State) {
        val now = SystemClock.uptimeMillis()
        if (state.deadline != 0L) {
            if (now < state.deadline) return
            state.deadline = 0
            state.request = ++nextRequest // Ignore any late callback from the expired request.
            state.failures++
            state.retryAt = now + 15_000L
        }
        if (now < state.retryAt) return
        val request = ++nextRequest
        val epoch = generation
        val reference = WeakReference(view)
        state.request = request
        state.deadline = now + 2000L
        try {
            invokeJs(view, HIDE_JS, ValueCallback { result ->
                handler.post {
                    val current = reference.get() ?: return@post
                    if (!running || epoch != generation || captured[current] !== state || state.request != request) return@post
                    state.deadline = 0
                    val json = runCatching {
                        val value = JSONTokener(result ?: "null").nextValue()
                        if (value is String) JSONObject(value) else value as? JSONObject
                    }.getOrNull()
                    if (json == null) {
                        state.failures++
                        state.retryAt = SystemClock.uptimeMillis() + 15_000L
                        return@post
                    }
                    state.failures = 0
                    val article = json.optBoolean("article")
                    if (!article) { state.lastTotal = -1; state.lastReapplied = -1 }
                    state.retryAt = SystemClock.uptimeMillis() + 500L
                    val total = json.optInt("hidden", 0)
                    val reapplied = json.optInt("reapplied", 0)
                    if (article && (total != state.lastTotal || reapplied != state.lastReapplied)) {
                        state.lastTotal = total
                        state.lastReapplied = reapplied
                        WeLogger.i(TAG, "v7 article hidden=$total reapplied=$reapplied rules=${json.optJSONArray("changed")} class=${current.javaClass.name}")
                    }
                }
            })
        } catch (ex: Exception) {
            state.deadline = 0
            state.failures++
            state.retryAt = now + 15_000L
            if (state.failures == 1) WeLogger.w(TAG, "v7 evaluate unavailable: ${view.javaClass.name}", ex)
        }
    }

    override fun onDisable() {
        fun stopOnMain() {
            running = false
            generation++
            handler.removeCallbacks(sweep)
            captured.keys.toList().forEach { view ->
                runCatching { invokeJs(view, STOP_JS, ValueCallback<String> { }) }
            }
            captured.clear()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) stopOnMain()
        else handler.post { stopOnMain() }
    }
}
