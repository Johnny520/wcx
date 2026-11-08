package h.Hchat.ui.miuix

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import h.Hchat.hooks.items.script.agent.ScriptPluginAgentSkills
import h.Hchat.utils.HLog
import java.lang.ref.WeakReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val NEW_SKILL_DOCUMENT = "---\nname: my-skill\ndescription: 请填写这个技能适合处理什么任务\n---\n\n# 使用步骤\n\n请填写 Agent 执行这类任务时应遵守的步骤、约束和检查方法。\n"

@Composable
internal fun ScriptPluginAgentSkillsPage(
    context: Context,
    onBack: () -> Unit,
    onBackHandlerChanged: ((() -> Unit)?) -> Unit,
    onUse: (String) -> Unit
) {
    val appContext = remember(context) { context.applicationContext ?: context }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val editorState = rememberLazyListState()
    var skills by remember { mutableStateOf<List<ScriptPluginAgentSkills.Skill>>(emptyList()) }
    var editing by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var document by remember { mutableStateOf(NEW_SKILL_DOCUMENT) }
    var savedDocument by remember { mutableStateOf(NEW_SKILL_DOCUMENT) }
    var busy by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmDeleteId by remember { mutableStateOf<String?>(null) }
    var cancelPicker by remember { mutableStateOf<(() -> Unit)?>(null) }
    var bottomBarHeightPx by remember { mutableStateOf(0) }
    val bottomBarHeight = with(LocalDensity.current) { bottomBarHeightPx.toDp() }

    fun showError(error: Throwable) {
        HLog.e("[Hchat:ScriptAgentSkills] 技能管理失败", error)
        errorMessage = error.message?.takeIf { it.isNotBlank() } ?: "操作失败，请重试"
        scope.launch { (if (editing) editorState else listState).animateScrollToItem(0) }
    }

    fun <T> perform(action: () -> T, onSuccess: (T) -> Unit) {
        if (busy) return
        busy = true
        errorMessage = ""
        notice = ""
        scope.launch {
            try {
                onSuccess(withContext(Dispatchers.IO) { action() })
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                showError(error)
            } finally {
                busy = false
            }
        }
    }

    fun refresh() {
        perform({ ScriptPluginAgentSkills.list(appContext) }) { skills = it }
    }

    fun leaveEditor() {
        editing = false
        confirmDeleteId = null
        confirmDiscard = false
        errorMessage = ""
    }

    fun back() {
        when {
            busy -> Toast.makeText(context, "正在处理，请稍候", Toast.LENGTH_SHORT).show()
            editing && document != savedDocument -> {
                confirmDiscard = true
                scope.launch { editorState.animateScrollToItem(0) }
            }
            editing -> leaveEditor()
            else -> onBack()
        }
    }

    val latestBack = rememberUpdatedState(newValue = { back() })
    DisposableEffect(Unit) {
        onBackHandlerChanged { latestBack.value.invoke() }
        onDispose {
            onBackHandlerChanged(null)
            cancelPicker?.invoke()
        }
    }
    LaunchedEffect(Unit) { refresh() }

    SettingsRouteTransition(
        targetState = editing,
        label = "ScriptAgentSkillsRoute",
        depthOf = { if (it) 1 else 0 }
    ) { isEditor ->
        val scrollBehavior = MiuixScrollBehavior()
        PageScaffold(
            title = if (isEditor) "编辑 Skill" else "Skill 管理",
            largeTitle = if (isEditor) "编辑 Skill" else "Skill 管理",
            scrollBehavior = scrollBehavior,
            onBack = { back() },
            bottomBar = {
                Box(modifier = Modifier.onSizeChanged { bottomBarHeightPx = it.height }) {
                BottomActionBar(
                    primaryText = if (busy) "正在处理…" else if (isEditor) "保存" else "新建 Skill",
                    onPrimaryClick = {
                        if (!busy) {
                            if (isEditor) {
                                val id = editingId
                                val content = document
                                perform({
                                    ScriptPluginAgentSkills.saveDocument(appContext, id, content)
                                    ScriptPluginAgentSkills.list(appContext)
                                }) {
                                    skills = it
                                    savedDocument = content
                                    notice = "已保存，下一次任务读取技能时生效"
                                    leaveEditor()
                                }
                            } else {
                                editingId = null
                                document = NEW_SKILL_DOCUMENT
                                savedDocument = NEW_SKILL_DOCUMENT
                                errorMessage = ""
                                notice = ""
                                confirmDiscard = false
                                confirmDeleteId = null
                                scope.launch { editorState.scrollToItem(0) }
                                editing = true
                            }
                        }
                    },
                    secondaryText = "返回",
                    onSecondaryClick = { back() }
                )
                }
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().imePadding()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                state = if (isEditor) editorState else listState,
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + bottomBarHeight + 16.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    SettingsCard {
                        Text(
                            text = if (isEditor) {
                                "保留顶部 --- 标记，name 填 1–64 位小写字母、数字或短横线，description 用中文说明适用任务。下方正文写步骤、约束和验证方法。技能提供指南与参考资源，不会自动执行脚本。"
                            } else {
                                "聊天输入 \$技能名，或让 Agent 按任务自动选择。技能在所有模型配置间共用；启用、修改或删除影响后续请求，已读入当前对话的内容不会被抹除。"
                            },
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
                if (errorMessage.isNotBlank() || notice.isNotBlank()) {
                    item {
                        SettingsCard {
                            Text(
                                text = if (errorMessage.isNotBlank()) "操作失败：$errorMessage" else notice,
                                color = MiuixTheme.colorScheme.onSurface,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                }
                if (isEditor) {
                    if (confirmDiscard) {
                        item {
                            SettingsCard {
                                Text(
                                    text = "有尚未保存的修改，是否放弃？",
                                    color = MiuixTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(16.dp)
                                )
                                SkillActionButtons("继续编辑", { confirmDiscard = false }, "放弃修改", { leaveEditor() })
                            }
                        }
                    }
                    item {
                        SettingsCard {
                            InputRow(
                                title = "SKILL.md",
                                summary = "保存时检查格式；导入包中的参考文件会保留",
                                value = document,
                                minLines = 16,
                                onValueChange = { if (!busy) document = it }
                            )
                        }
                    }
                } else {
                    item {
                        SettingsCard {
                            ActionRow("导入 Skill", "从系统文件选择器选择 SKILL.md 或技能 ZIP 包") {
                                if (!busy) {
                                    val activity = skillActivity(context)
                                    if (activity == null) {
                                        errorMessage = "当前页面无法打开系统文件选择器"
                                    } else {
                                        cancelPicker = SkillDocumentPicker.launch(activity) { result ->
                                            cancelPicker = null
                                            result.fold(onSuccess = { uri ->
                                                if (uri != null) {
                                                    perform({
                                                        val imported = ScriptPluginAgentSkills.importFile(appContext, uri)
                                                        imported.size to ScriptPluginAgentSkills.list(appContext)
                                                    }) { (count, allSkills) ->
                                                        skills = allSkills
                                                        notice = "已导入 $count 个技能，可在列表调整启用状态"
                                                    }
                                                }
                                            }, onFailure = { showError(it) })
                                        }
                                    }
                                }
                            }
                            InsetDivider()
                            ActionRow("刷新列表", if (busy) "正在处理…" else "重新读取已安装的技能") { refresh() }
                        }
                    }
                    item { SmallTitle(text = "已安装技能（${skills.size}）") }
                    if (skills.isEmpty()) {
                        item {
                            SettingsCard {
                                Text(
                                    text = if (busy) "正在读取技能…" else "暂无技能，可以新建或导入 SKILL.md。",
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        }
                    }
                    items(skills, key = { it.id }) { skill ->
                        SettingsCard {
                            SwitchRow(
                                checked = skill.enabled,
                                title = skill.name.ifBlank { skill.id },
                                summary = if (skill.error.isNotBlank()) "格式错误：${skill.error}" else skill.description,
                                enabled = !busy && (skill.error.isBlank() || skill.enabled),
                                onCheckedChange = { enabled ->
                                    perform({
                                        ScriptPluginAgentSkills.setEnabled(appContext, skill.id, enabled)
                                        ScriptPluginAgentSkills.list(appContext)
                                    }) {
                                        skills = it
                                        notice = "已${if (enabled) "启用" else "停用"}，后续请求生效"
                                    }
                                }
                            )
                            InsetDivider()
                            SkillActionButtons("编辑", {
                                perform({ ScriptPluginAgentSkills.readDocument(appContext, skill.id) }) {
                                    editingId = skill.id
                                    document = it
                                    savedDocument = it
                                    confirmDeleteId = null
                                    confirmDiscard = false
                                    scope.launch { editorState.scrollToItem(0) }
                                    editing = true
                                }
                            }, "使用", { onUse(skill.name) }, enabled = !busy,
                                secondEnabled = skill.enabled && skill.error.isBlank(),
                                third = "删除", onThird = { confirmDeleteId = skill.id })
                            if (confirmDeleteId == skill.id) {
                                InsetDivider()
                                Text(
                                    text = "删除这个技能及其参考文件？",
                                    color = MiuixTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(16.dp)
                                )
                                SkillActionButtons("取消", { confirmDeleteId = null }, "确认删除", {
                                    perform({
                                        ScriptPluginAgentSkills.delete(appContext, skill.id)
                                        ScriptPluginAgentSkills.list(appContext)
                                    }) {
                                        skills = it
                                        confirmDeleteId = null
                                        notice = "技能已删除"
                                    }
                                }, enabled = !busy)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SkillActionButtons(
    first: String,
    onFirst: () -> Unit,
    second: String,
    onSecond: () -> Unit,
    enabled: Boolean = true,
    secondEnabled: Boolean = true,
    third: String? = null,
    onThird: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        TextButton(text = first, onClick = onFirst, enabled = enabled, modifier = Modifier.weight(1f),
            colors = ButtonDefaults.textButtonColorsPrimary())
        TextButton(text = second, onClick = onSecond, enabled = enabled && secondEnabled, modifier = Modifier.weight(1f),
            colors = ButtonDefaults.textButtonColorsPrimary())
        if (third != null && onThird != null) {
            TextButton(text = third, onClick = onThird, enabled = enabled, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary())
        }
    }
}

private fun skillActivity(context: Context): Activity? {
    var current = context
    while (current is ContextWrapper) {
        if (current is Activity) return current
        val base = current.baseContext
        if (base === current) break
        current = base
    }
    return current as? Activity
}

private object SkillDocumentPicker {
    private const val REQUEST_CODE = 0x5262
    private val hookedClasses = HashSet<Class<*>>()
    private var pending: Pending? = null

    private class Pending(activity: Activity, val callback: (Result<Uri?>) -> Unit) {
        val owner = WeakReference(activity)
    }

    @Synchronized
    fun launch(activity: Activity, callback: (Result<Uri?>) -> Unit): () -> Unit {
        if (pending?.owner?.get()?.let { !it.isDestroyed && !it.isFinishing } == true) {
            callback(Result.failure(IllegalStateException("已有技能文件选择正在进行")))
            return {}
        }
        val request = Pending(activity, callback)
        pending = request
        try {
            val activityHooked = hook(activity.javaClass)
            val baseHooked = hook(Activity::class.java)
            check(activityHooked || baseHooked) { "无法接收系统文件选择结果" }
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                activity.startActivityForResult(intent, REQUEST_CODE)
            } catch (_: android.content.ActivityNotFoundException) {
                val fallback = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                activity.startActivityForResult(Intent.createChooser(fallback, "选择 SKILL.md 或 ZIP"), REQUEST_CODE)
            }
        } catch (error: Throwable) {
            pending = null
            callback(Result.failure(error))
        }
        return { synchronized(this) { if (pending === request) pending = null } }
    }

    @Synchronized
    private fun hook(clazz: Class<*>): Boolean {
        if (clazz in hookedClasses) return true
        return try {
            val hooks = XposedBridge.hookAllMethods(clazz, "onActivityResult", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.args.getOrNull(0) != REQUEST_CODE) return
                    val request = synchronized(this@SkillDocumentPicker) {
                        pending?.takeIf { it.owner.get() === param.thisObject }?.also { pending = null }
                    } ?: return
                    if (param.args.getOrNull(1) != Activity.RESULT_OK) {
                        request.callback(Result.success(null))
                        return
                    }
                    val data = param.args.getOrNull(2) as? Intent
                    val uri = data?.data ?: data?.clipData?.let { if (it.itemCount > 0) it.getItemAt(0).uri else null }
                    request.callback(if (uri == null) Result.failure(IllegalStateException("未取得所选文件")) else Result.success(uri))
                }
            })
            if (hooks.isEmpty()) false else {
                hookedClasses += clazz
                true
            }
        } catch (error: Throwable) {
            HLog.e("[Hchat:ScriptAgentSkills] 注册文件选择回调失败", error)
            false
        }
    }
}
