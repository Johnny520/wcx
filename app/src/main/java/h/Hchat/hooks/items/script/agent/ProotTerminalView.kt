package h.Hchat.hooks.items.script.agent

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import h.Hchat.utils.HLog

@SuppressLint("ViewConstructor")
// ☰ 会话菜单：新建会话 / 关闭会话（最后一个会话时即退出终端）/ 关闭终端（结束全部会话）
class ProotTerminalView(context: Context) : FrameLayout(context), TermuxSessionView {

    private val terminalView: TerminalView = TerminalView(ModuleResourceContext.of(context), null)

    private var session: TerminalSession? = null
    private var onFinished: ((Int) -> Unit)? = null
    private var viewPrepared = false

    var ctrlActive: Boolean = false
        private set
    var altActive: Boolean = false
        private set
    var shiftActive: Boolean = false
        private set

    fun setCtrlActive(active: Boolean) {
        ctrlActive = active
    }

    fun setAltActive(active: Boolean) {
        altActive = active
    }

    fun setShiftActive(active: Boolean) {
        shiftActive = active
    }

    fun setOnSessionFinished(cb: (Int) -> Unit) {
        onFinished = cb
    }

    private var onExitRequested: (() -> Unit)? = null

    fun setOnExitRequested(cb: () -> Unit) {
        onExitRequested = cb
    }

    fun start(): Boolean {
        runCatching {
            // libtermux.so 随 terminal-emulator aar 打包，直接按库名加载
            System.loadLibrary("termux")
        }.onFailure { HLog.e("[Hchat:Term] libtermux 预加载失败: ${it.message}", it) }

        prepareTerminalView()
        val holder = TermuxSessions.ensureCurrent(context) ?: return false
        display(holder, showKeyboard = true)
        return true
    }

    fun detach() {
        TermuxSessions.detachView(this)
        TerminalBridge.detach(this)
        session = null
    }

    private fun prepareTerminalView() {
        if (viewPrepared && terminalView.parent != null) return
        val density = resources.displayMetrics.density
        var fontSize = Math.round(12f * density)
        if (fontSize % 2 == 1) fontSize--
        terminalView.setTextSize(fontSize)
        applyFont()
        terminalView.isVerticalScrollBarEnabled = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val thumb = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0x66FFFFFF)
                setSize(dpToPx(4f).toInt(), -1)
            }
            runCatching { terminalView.verticalScrollbarThumbDrawable = thumb }
        }
        terminalView.keepScreenOn = true
        terminalView.setTerminalViewClient(viewClient)
        if (terminalView.parent == null) {
            addView(
                terminalView,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
            )
        }
        viewPrepared = true
    }

    // 换字体：TerminalView.setTypeface 内部自己重建渲染器并 updateSize
    private fun applyFont() {
        runCatching { terminalView.setTypeface(TerminalFonts.typeface(context)) }
            .onFailure { HLog.e("[Hchat:Term] 应用终端字体失败: ${it.message}", it) }
    }

    fun showFontMenu() {
        post {
            runCatching {
                val fonts = TerminalFonts.ALL
                val checked = fonts.indexOfFirst { it.id == TerminalFonts.selectedId(context) }
                AlertDialog.Builder(context)
                    .setTitle("终端字体")
                    .setSingleChoiceItems(fonts.map { it.label }.toTypedArray(), checked) { dialog, which ->
                        val picked = fonts[which]
                        if (picked.fileName.isEmpty() || TerminalFonts.isDownloaded(context, picked)) {
                            TerminalFonts.select(context, picked.id)
                            applyFont()
                            dialog.dismiss()
                            toast("已换成 ${picked.label}")
                        } else {
                            dialog.dismiss()
                            AlertDialog.Builder(context)
                                .setTitle("下载字体")
                                .setMessage("「${picked.label}」尚未下载（约 ${picked.sizeKb}KB），需要联网下载后使用，是否下载？")
                                .setPositiveButton("下载") { _, _ ->
                                    TerminalFonts.download(context, picked,
                                        onSuccess = {
                                            TerminalFonts.select(context, picked.id)
                                            applyFont()
                                            toast("已下载并使用 ${picked.label}")
                                        },
                                        onError = { msg -> toast("字体下载失败：$msg") })
                                }
                                .setNegativeButton("取消", null)
                                .show()
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }.onFailure { HLog.e("[Hchat:Term] 字体菜单弹出失败: ${it.message}", it) }
        }
    }

    private fun display(holder: TermuxSession, showKeyboard: Boolean) {
        val target = holder.session
        prepareTerminalView()
        session = target
        terminalView.setTerminalViewClient(viewClient)
        terminalView.attachSession(target)
        terminalView.isFocusable = true
        terminalView.isFocusableInTouchMode = true
        terminalView.onScreenUpdated()
        terminalView.post { terminalView.updateSize() }
        TermuxSessions.attachView(this)
        TerminalBridge.attach(this)
        if (showKeyboard) {
            terminalView.post {
                terminalView.requestFocus()
                showSoftKeyboard()
            }
            terminalView.postDelayed({ showSoftKeyboard() }, 350)
        }
    }


    override fun onTerminalTextChanged(session: TerminalSession) {
        if (terminalView.mTermSession === session) {
            terminalView.post { runCatching { terminalView.onScreenUpdated() } }
        }
    }

    override fun onTerminalSessionFinished(session: TerminalSession) {
        if (this.session !== session) return
        val code = runCatching { session.exitStatus }.getOrDefault(-1)
        onFinished?.invoke(code)
        toast("会话已结束")
    }

    override fun onTerminalCopyRequested(text: String?) = copyToClipboard(text)

    override fun onTerminalPasteRequested() = pasteFromClipboard()


    fun showSessionMenu() {
        post {
            runCatching {
                val all = TermuxSessions.list()
                val cur = TermuxSessions.current()
                val labels = ArrayList<String>(all.size + 3)
                all.forEach { item ->
                    val mark = if (item.id == cur?.id) "● " else "　"
                    labels.add("$mark${item.title}${if (item.isRunning) "" else "（已结束）"}")
                }
                val newIdx = all.size
                val closeIdx = if (cur != null) newIdx + 1 else -1
                val fontIdx = if (cur != null) newIdx + 2 else newIdx + 1
                val quitIdx = fontIdx + 1
                labels.add("＋ 新建会话")
                if (cur != null) {
                    labels.add(
                        if (all.size <= 1) "✕ 关闭会话（退出终端）"
                        else "✕ 关闭 ${cur.title}"
                    )
                }
                labels.add("换字体：${TerminalFonts.byId(TerminalFonts.selectedId(context)).label}")
                labels.add("⏻ 关闭终端（结束全部会话）")
                AlertDialog.Builder(context)
                    .setTitle(if (all.size > 1) "终端会话（${all.size} 个）" else "终端会话")
                    .setItems(labels.toTypedArray()) { _, which ->
                        when (which) {
                            newIdx -> newSession()
                            closeIdx -> closeCurrentSession()
                            fontIdx -> showFontMenu()
                            quitIdx -> closeTerminal()
                            else -> switchTo(all[which])
                        }
                    }
                    .show()
            }.onFailure { HLog.e("[Hchat:Term] 会话菜单弹出失败: ${it.message}", it) }
        }
    }

    private fun switchTo(holder: TermuxSession) {
        if (holder.session === session) {
            toast("已经在 ${holder.title}")
            return
        }
        TermuxSessions.select(holder.id)
        display(holder, showKeyboard = false)
        toast("已切换到 ${holder.title}")
    }

    private fun newSession() {
        val holder = TermuxSessions.create(context)
        if (holder == null) {
            toast("新建失败：终端环境未就绪")
            return
        }
        display(holder, showKeyboard = true)
        toast("已新建 ${holder.title}")
    }

    private fun closeCurrentSession() {
        val cur = TermuxSessions.current() ?: return
        if (TermuxSessions.list().size <= 1) {
            closeTerminal()
            return
        }
        TermuxSessions.close(cur.id)
        val next = TermuxSessions.current()
        if (next != null) display(next, showKeyboard = false)
        toast("已关闭 ${cur.title}")
    }

    private fun closeTerminal() {
        val count = TermuxSessions.list().size
        detach()
        TermuxSessions.closeSessions()
        toast(if (count > 1) "已关闭终端（结束 $count 个会话）" else "已关闭终端")
        onExitRequested?.invoke()
    }

    fun paste(text: String) {
        val s = session ?: return
        val bytes = text.toByteArray(Charsets.UTF_8)
        s.write(bytes, 0, bytes.size)
    }

    fun sendKey(sequence: String) = paste(sequence)

    fun clearScreen() = paste("clear\n")

    fun stopSelection() {
        runCatching { terminalView.stopTextSelectionMode() }
    }

    fun copyToClipboard(text: String?) {
        val value = text?.trim()
        if (value.isNullOrEmpty()) {
            toast("没有选中内容")
            return
        }
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (cm == null) {
            toast("复制失败：剪贴板不可用")
            return
        }
        runCatching { cm.setPrimaryClip(ClipData.newPlainText("Hchat 终端", value)) }
            .onSuccess { toast("已复制") }
            .onFailure {
                HLog.e("[Hchat:Term] 复制失败: ${it.message}", it)
                toast("复制失败")
            }
    }

    fun pasteFromClipboard() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = runCatching { cm?.primaryClip }.getOrNull()
        val text = if (clip != null && clip.itemCount > 0) {
            runCatching { clip.getItemAt(0).coerceToText(context)?.toString() }.getOrNull()
        } else {
            null
        }
        if (text.isNullOrEmpty()) {
            toast("剪贴板是空的")
            return
        }
        paste(text)
        toast("已粘贴")
    }

    fun selectedText(): String? = runCatching {
        val field = terminalView.javaClass.getDeclaredField("mTextSelectionCursorController")
        field.isAccessible = true
        val controller = field.get(terminalView) ?: return null
        val sel = IntArray(4)
        controller.javaClass.getMethod("getSelectors", IntArray::class.java).invoke(controller, sel)
        terminalView.mEmulator?.getSelectedText(sel[2], sel[0], sel[3], sel[1])?.trim()
    }.getOrNull()

    // 全选：把选区扩到整个回滚缓冲（顶部第一行 → 底部最后一行），之后点复制即全部内容
    fun selectAll() {
        post {
            runCatching {
                val emulator = terminalView.mEmulator ?: return@runCatching
                val screen = emulator.getScreen()
                val scrollRows = screen.activeRows - emulator.mRows
                val controllerMethod = terminalView.javaClass.getDeclaredMethod("getTextSelectionCursorController")
                controllerMethod.isAccessible = true
                val controller = controllerMethod.invoke(terminalView) ?: return@runCatching
                val clazz = controller.javaClass
                fun setSel(name: String, v: Int) {
                    val f = clazz.getDeclaredField(name)
                    f.isAccessible = true
                    f.setInt(controller, v)
                }
                setSel("mSelX1", 0)
                setSel("mSelY1", -scrollRows)
                setSel("mSelX2", emulator.mColumns - 1)
                setSel("mSelY2", emulator.mRows - 1)
                val activeField = clazz.getDeclaredField("mIsSelectingText")
                activeField.isAccessible = true
                activeField.setBoolean(controller, true)
                clazz.getMethod("render").invoke(controller)
                terminalView.invalidate()
                toast("已全选（共 ${scrollRows + emulator.mRows} 行），点复制即可")
            }.onFailure { HLog.e("[Hchat:Term] 全选失败: ${it.message}", it) }
        }
    }

    fun showMoreMenu() {
        post {
            runCatching {
                AlertDialog.Builder(context)
                    .setTitle("终端")
                    .setItems(arrayOf("全选", "粘贴", "清屏", "发送 Ctrl+C", "换字体…", "终端会话…")) { _, which ->
                        when (which) {
                            0 -> selectAll()
                            1 -> pasteFromClipboard()
                            2 -> clearScreen()
                            3 -> paste("\u0003")
                            4 -> showFontMenu()
                            5 -> showSessionMenu()
                        }
                    }
                    .show()
            }.onFailure { HLog.e("[Hchat:Term] 更多菜单弹出失败: ${it.message}", it) }
        }
    }

    private fun toast(message: String) {
        post {
            runCatching { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
        }
    }

    fun toggleCtrl(): Boolean {
        ctrlActive = !ctrlActive
        return ctrlActive
    }

    fun toggleAlt(): Boolean {
        altActive = !altActive
        return altActive
    }

    fun toggleSoftKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager ?: return
        if (imm.isActive(terminalView)) {
            imm.hideSoftInputFromWindow(terminalView.windowToken, 0)
        } else {
            showSoftKeyboard()
        }
    }

    fun requestTerminalFocus(): Boolean {
        terminalView.isFocusable = true
        terminalView.isFocusableInTouchMode = true
        return terminalView.requestFocus()
    }

    fun showSoftKeyboard() {
        requestTerminalFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager ?: return
        imm.showSoftInput(terminalView, 0)
        terminalView.postDelayed({
            if (!imm.isActive(terminalView)) {
                runCatching {
                    imm.toggleSoftInput(
                        android.view.inputmethod.InputMethodManager.SHOW_FORCED, 0
                    )
                }
            }
        }, 250)
    }

    fun isSessionRunning(): Boolean = session?.isRunning == true

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        TerminalBridge.detach(this)
    }

    private fun dpToPx(dp: Float): Float = dp * resources.displayMetrics.density

    private val viewClient = object : TerminalViewClient {
        override fun onScale(scale: Float): Float = scale

        override fun onSingleTapUp(e: MotionEvent?) {
            showSoftKeyboard()
        }

        override fun shouldBackButtonBeMappedToEscape(): Boolean = false
        override fun shouldEnforceCharBasedInput(): Boolean = true
        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
        override fun isTerminalViewSelected(): Boolean = true
        override fun copyModeChanged(copyMode: Boolean) {}

        override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
        override fun onLongPress(event: MotionEvent?): Boolean = false

        override fun readControlKey(): Boolean = ctrlActive
        override fun readAltKey(): Boolean = altActive
        override fun readShiftKey(): Boolean = shiftActive
        override fun readFnKey(): Boolean = false

        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false
        override fun onEmulatorSet() {
            terminalView.onScreenUpdated()
        }

        override fun logError(tag: String?, message: String?) {}
        override fun logWarn(tag: String?, message: String?) {}
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
        override fun logStackTrace(tag: String?, e: Exception?) {}
    }
}

object TerminalBridge {

    @Volatile
    private var ref: java.lang.ref.WeakReference<ProotTerminalView>? = null

    fun attach(view: ProotTerminalView) {
        ref = java.lang.ref.WeakReference(view)
    }

    fun detach(view: ProotTerminalView) {
        if (ref?.get() === view) ref = null
    }

    fun current(): ProotTerminalView? = ref?.get()
}
