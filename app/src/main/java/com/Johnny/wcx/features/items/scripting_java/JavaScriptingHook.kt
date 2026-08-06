package com.Johnny.wcx.features.items.scripting_java

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import bsh.Interpreter
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import dev.ujhhgtg.reflekt.reflekt
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.WeDatabaseListenerApi
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.items.chat.ChatInputBarEnhancements
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.fs.KnownPaths
import com.Johnny.wcx.utils.fs.createDirsSafe
import com.Johnny.wcx.utils.openInSystem
import com.Johnny.wcx.utils.serialization.XmlUtils.extractXmlAttr
import com.Johnny.wcx.utils.serialization.XmlUtils.extractXmlTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.hd.wauxv.data.bean.MsgInfoBean
import me.hd.wauxv.data.bean.PayMsgBean
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

@Feature(name = "脚本引擎 (Java)", categories = ["脚本 (Java)"], description = "执行 Java 脚本")
object JavaScriptingHook : ClickableFeature(), IResolveDex, WeDatabaseListenerApi.IUpdateListener, WeDatabaseListenerApi.IInsertListener {

    private const val TAG = "JavaScriptingHook"
    private const val DISABLED_FLAG = "disabled.flag"

    // ── 示例脚本内容（首次创建目录时自动生成，用户删除后不再自动恢复） ──
    private const val DEMO_SCRIPT_CONTENT = """
import com.Johnny.wcx.utils.WeLogger;

/**
 * 示例脚本 - demo_sample
 * 
 * 脚本生命周期回调：
 *   onLoad()          - 脚本加载时调用（模块启动、脚本启用时）
 *   onUnload()        - 脚本卸载时调用（模块关闭、脚本禁用时）
 *   onHandleMsg(msg)  - 收到任何消息时调用（MsgInfoBean 对象）
 *   onClickSendBtn(text) - 点击发送按钮时调用（String 文本内容）
 *   onRecvPayMsg(payMsg) - 收到转账/红包时调用（PayMsgBean 对象）
 *   onNewFriend(wxid, ticket, scene) - 新朋友申请时调用
 *   onMemberChange(type, groupWxid, userWxid, userName) - 群成员变动时调用
 * 
 * 可用 API：
 *   sendText(talker, text)          - 发送文本消息
 *   sendImage(talker, path)         - 发送图片
 *   getFriendList()                 - 获取好友列表
 *   getGroupList()                  - 获取群列表
 *   getFriendName(wxid)             - 获取好友昵称
 *   getLoginWxid()                  - 获取当前登录微信号
 *   getTargetTalker()               - 获取当前聊天对象
 *   toast(message)                  - 弹出 Toast 提示
 *   log(message)                    - 输出日志
 *   getString(key, default)         - 读取持久化配置
 *   putString(key, value)           - 写入持久化配置
 *   hookBefore(member, consumer)    - 方法前 Hook
 *   hookAfter(member, consumer)     - 方法后 Hook
 *   get(url, headers, callback)     - HTTP GET 请求
 *   post(url, params, headers, callback) - HTTP POST 请求
 *   delay(ms, runnable)            - 延时执行
 */

// ── 脚本加载时调用 ──
void onLoad() {
    WeLogger.d("DemoScript", "示例脚本加载成功！");
    WeLogger.d("DemoScript", "当前登录微信号: " + getLoginWxid());
    toast("示例脚本已加载，可在脚本目录查看代码");
}

// ── 脚本卸载时调用 ──
void onUnload() {
    WeLogger.d("DemoScript", "示例脚本已卸载");
}

// ── 收到消息时调用 ──
void onHandleMsg(Object msg) {
    try {
        // 获取消息基本信息
        String content = msg.reflekt().getField("content");
        String talker = msg.reflekt().getField("talker");
        int type = msg.reflekt().getField("type");
        long createTime = msg.reflekt().getField("createTime");
        
        WeLogger.d("DemoScript", "收到消息 | 发送者: " + talker + " | 类型: " + type + " | 内容: " + content);
        
        // 示例：自动回复（如需启用，请删除下面的注释）
        // if (content != null && content.contains("你好")) {
        //     sendText(talker, "这是脚本自动回复：你好！");
        //     toast("已自动回复 " + getFriendName(talker));
        // }
    } catch (Exception e) {
        WeLogger.e("DemoScript", "处理消息出错: " + e.getMessage(), e);
    }
}

// ── 点击发送按钮时调用 ──
void onSend(String text) {
    WeLogger.d("DemoScript", "即将发送消息: " + text);
    // 返回 true 可拦截消息发送，返回 false 或不返回则正常发送
    // return false;
}

// ── 收到转账/红包时调用 ──
void onRecvPayMsg(Object payMsg) {
    try {
        String talker = payMsg.reflekt().getField("talker");
        int type = payMsg.reflekt().getField("type");
        WeLogger.d("DemoScript", "收到转账/红包 | 发送者: " + talker + " | 类型: " + type);
    } catch (Exception e) {
        WeLogger.e("DemoScript", "处理转账消息出错: " + e.getMessage(), e);
    }
}

// ── 新朋友申请时调用 ──
void onNewFriend(String wxid, String ticket, int scene) {
    WeLogger.d("DemoScript", "新朋友申请 | wxid: " + wxid + " | scene: " + scene);
    // 示例：自动通过好友申请（如需启用，请删除下面的注释）
    // if (scene == 3) {  // 3 = 搜索微信号添加
    //     verifyUser(wxid, ticket, scene);
    //     toast("已自动通过 " + wxid + " 的好友申请");
    // }
}

// ── 群成员变动时调用 ──
void onMemberChange(String type, String groupWxid, String userWxid, String userName) {
    WeLogger.d("DemoScript", "群成员变动 | 类型: " + type + " | 群: " + groupWxid + " | 用户: " + userName);
}
""".trimIndent()

    // ── 脚本目录（延迟初始化，首次创建时自动放入示例脚本） ──────────────
    private val SCRIPTS_DIR: Path by lazy {
        val dir = KnownPaths.moduleData / "scripts_java"
        val result = runCatching {
            val isNew = !dir.exists()
            dir.createDirectories()
            WeLogger.d(TAG, "scripts_java 目录已就绪: ${dir.toAbsolutePath().toString()}")

            // 首次创建目录时，自动生成示例脚本
            if (isNew) {
                val demoDir = dir / "demo_sample"
                demoDir.createDirectories()
                (demoDir / "main.java").writeText(DEMO_SCRIPT_CONTENT)
                (demoDir / "info.prop").writeText(
                    "name=示例脚本\n" +
                    "author=WCX\n" +
                    "type=demo\n" +
                    "version=1.0\n" +
                    "updateTime=2026-08-06\n" +
                    "description=带中文注释的示例脚本，展示脚本引擎各项功能用法"
                )
                WeLogger.d(TAG, "已自动创建示例脚本: demo_sample")
            }
        }
        if (result.isFailure) {
            WeLogger.e(TAG, "无法创建 scripts_java 目录: ${dir.toAbsolutePath().toString()}", result.exceptionOrNull())
        }
        dir
    }

    // ── 扫描状态（供 UI 展示） ────────────────────────────────────────────
    @Volatile
    private var lastScanError: String? = null
    @Volatile
    private var lastScanCount: Int = -1

    val scripts = ConcurrentHashMap<String, JavaPlugin>()

    private data class ScriptEntry(
        val dir: Path,
        val info: JavaPluginInfo,
        val enabled: Boolean,
    )

    private val methodPayMsg by dexMethod {
        matcher {
            usingEqStrings("[onRecv PayerMsg]，newMsg.msgType：%s")
        }
    }

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)

        WeMessageApi.methodMsgInfoHandleApiInsertMessage.hookAfter {
            val msgObj = args[0] ?: return@hookAfter
            val msgBean = MsgInfoBean(msgObj)
            JavaEngine.executeAllOnHandleMsg(scripts, msgBean)
        }

        ChatInputBarEnhancements.methodSendMessage.hookBefore {
            val chatFooter = thisObject.reflekt().firstField {
                type = ChatFooter::class
            }.get()!! as ChatFooter
            val text = chatFooter.lastText
            JavaEngine.executeAllOnClickSendBtn(scripts, this, text)
        }

        methodPayMsg.hookBefore {
            val g2Var = args[0] ?: return@hookBefore
            val payMsgBean = PayMsgBean(g2Var)
            JavaEngine.executeAllOnRecvPayMsg(scripts, payMsgBean)
        }

        CoroutineScope(Dispatchers.IO).launch {
            WeLogger.d(TAG, "========== 开始扫描 Java 脚本 ==========")
            WeLogger.d(TAG, "脚本目录: ${SCRIPTS_DIR.toAbsolutePath().toString()}")

            // 确保目录存在
            val dirReady = runCatching {
                if (!SCRIPTS_DIR.exists()) {
                    SCRIPTS_DIR.createDirectories()
                    WeLogger.d(TAG, "目录不存在，已自动创建: ${SCRIPTS_DIR.toAbsolutePath().toString()}")
                }
                SCRIPTS_DIR.exists()
            }.getOrDefault(false)

            if (!dirReady) {
                val errMsg = "脚本目录不可用: ${SCRIPTS_DIR.toAbsolutePath().toString()}，请检查文件权限"
                WeLogger.e(TAG, errMsg)
                lastScanError = errMsg
                lastScanCount = 0
                return@launch
            }

            // 扫描子目录
            val subDirs = safeListScriptDirs()
            WeLogger.d(TAG, "扫描到 ${subDirs.size} 个子目录")
            lastScanCount = 0

            for (scriptDir in subDirs) {
                val dirName = scriptDir.name
                WeLogger.d(TAG, "--- 检查脚本目录: '$dirName' ---")

                if (!isScriptEnabled(scriptDir)) {
                    WeLogger.d(TAG, "跳过 '$dirName': 已禁用 (disabled.flag 存在)")
                    continue
                }

                val mainFile = scriptDir / "main.java"
                val infoFile = scriptDir / "info.prop"

                if (!mainFile.exists()) {
                    WeLogger.w(TAG, "跳过 '$dirName': 缺少 main.java")
                    continue
                }
                if (!infoFile.exists()) {
                    WeLogger.w(TAG, "跳过 '$dirName': 缺少 info.prop")
                    continue
                }

                WeLogger.d(TAG, "'$dirName': main.java=${mainFile.toAbsolutePath().toString()} (${mainFile.toFile().length()} bytes)")
                WeLogger.d(TAG, "'$dirName': info.prop=${infoFile.toAbsolutePath().toString()} (${infoFile.toFile().length()} bytes)")

                val content = runCatching { mainFile.readText() }.getOrElse { e ->
                    WeLogger.e(TAG, "读取 main.java 失败 '$dirName'", e)
                    continue
                }
                val infoPropContent = runCatching { infoFile.readText() }.getOrElse { e ->
                    WeLogger.e(TAG, "读取 info.prop 失败 '$dirName'", e)
                    continue
                }

                val info = runCatching {
                    JavaPlugin.parseInfoProp(infoPropContent)
                }.getOrElse { e ->
                    WeLogger.e(TAG, "解析 info.prop 失败 '$dirName'", e)
                    continue
                }

                WeLogger.d(TAG, "成功加载脚本: name='${info.name}', author='${info.author ?: "未知"}', version='${info.version ?: "未知"}'")
                WeLogger.d(TAG, "脚本代码长度: ${content.length} 字符")

                val plugin = JavaPlugin(
                    name = dirName,
                    dir = scriptDir,
                    info = info,
                    content = content,
                    interpreter = Interpreter(null, "")
                )
                scripts[dirName] = plugin
                lastScanCount = scripts.size
            }

            WeLogger.d(TAG, "========== 扫描完成: 成功加载 ${scripts.size} 个脚本 ==========")
            JavaEngine.executeAllOnLoad(scripts)
        }
    }

    override fun onClick(context: ComponentActivity) {
        var showHelp by mutableStateOf(false)

        fun refreshAndShow() {
            val entries = listScriptEntries()
            showComposeDialog(context) {
                var showHelpState by remember { mutableStateOf(showHelp) }

                if (showHelpState) {
                    ScriptHelpScreen(
                        onDismiss = {
                            showHelpState = false
                            showHelp = false
                        },
                        onOpenScriptDir = { openScriptsDirectory(context) }
                    )
                } else {
                    AlertDialogContent(
                        title = { Text("Java 脚本") },
                        text = {
                            DefaultColumn {
                                // 显示扫描错误
                                val scanError = lastScanError
                                if (scanError != null) {
                                    Text(
                                        "⚠️ 脚本目录异常",
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Text(
                                        scanError,
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        "请确保模块已获取存储权限，或手动创建目录：\n${SCRIPTS_DIR.toAbsolutePath().toString()}",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                } else if (entries.isEmpty()) {
                                    val count = lastScanCount
                                    if (count == 0) {
                                        Text("暂无脚本，点击下方「使用说明」了解如何添加脚本")
                                        Text(
                                            "脚本目录: ${SCRIPTS_DIR.toAbsolutePath().toString()}",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    } else {
                                        Text("正在扫描脚本目录...")
                                    }
                                } else {
                                    LazyColumn(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 400.dp),
                                    ) {
                                        items(entries, key = { it.dir.name }) { entry ->
                                            var enabled by remember(entry.dir) { mutableStateOf(entry.enabled) }
                                            fun toggle() {
                                                val newState = !enabled
                                                if (setScriptEnabled(entry.dir, newState)) {
                                                    enabled = newState
                                                }
                                            }

                                            ListItem(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable { toggle() },
                                                headlineContent = { Text(entry.info.name) },
                                                supportingContent = {
                                                    Text(
                                                        buildList {
                                                            add(entry.dir.name)
                                                            add(if (enabled) "已启用" else "已禁用")
                                                            entry.info.version?.let { add("版本 $it") }
                                                            entry.info.author?.let { add("作者 $it") }
                                                        }.joinToString(" · ")
                                                    )
                                                },
                                                trailingContent = {
                                                    Switch(
                                                        checked = enabled,
                                                        onCheckedChange = null,
                                                    )
                                                },
                                            )
                                        }
                                    }
                                }
                                TextButton(onClick = {
                                    showHelpState = true
                                    showHelp = true
                                }) {
                                    Text("使用说明")
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = onDismiss) { Text("完成") }
                        },
                    )
                }
            }
        }

        refreshAndShow()
    }

    @Composable
    private fun ScriptHelpScreen(onDismiss: () -> Unit, onOpenScriptDir: () -> Unit) {
        val scrollState = rememberScrollState()
        AlertDialogContent(
            title = { Text("Java 脚本使用说明") },
            text = {
                DefaultColumn(Modifier.verticalScroll(scrollState)) {
                    Text("📁 脚本存放路径", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Android/data/com.tencent.mm/WCX/scripts_java/",
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "每个脚本是一个独立文件夹，包含 main.java（脚本代码）和 info.prop（脚本信息）",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )

                    Text("📝 脚本结构", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
                    Text("脚本目录结构：", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "scripts_java/\n" +
                                "└── my_script/\n" +
                                "    ├── main.java    # 脚本代码\n" +
                                "    └── info.prop    # 脚本信息",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )

                    Text("📋 info.prop 格式", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
                    Text(
                        "name=脚本名称\nauthor=作者\nversion=1.0\nupdateTime=2024-01-01",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )

                    Text("🎯 可用回调函数", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
                    Text(
                        "• onLoad() - 脚本加载时调用\n" +
                                "• onUnload() - 脚本卸载时调用\n" +
                                "• onHandleMsg(msg) - 收到消息时调用\n" +
                                "• onClickSendBtn(chatFooter, text) - 点击发送按钮时调用\n" +
                                "• onRecvPayMsg(payMsg) - 收到转账/红包时调用\n" +
                                "• onNewFriend(username, ticket, scene) - 新朋友申请时调用",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )

                    Text("💡 简单示例", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
                    Text(
                        "// 收到消息时打印日志\n" +
                                "import com.Johnny.wcx.utils.WeLogger;\n\n" +
                                "void onLoad() {\n" +
                                "    WeLogger.d(\"MyScript\", \"脚本加载成功\");\n" +
                                "}\n\n" +
                                "void onHandleMsg(Object msg) {\n" +
                                "    WeLogger.d(\"MyScript\", \"收到消息: \" + msg);\n" +
                                "}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )

                    Text("🔧 可用 API", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
                    Text(
                        "• WeMessageApi - 消息相关操作\n" +
                                "• WeDatabaseApi - 数据库操作\n" +
                                "• WeApi - 通用 API\n" +
                                "• WeLogger - 日志输出\n" +
                                "• JavaHookApi - Hook 相关 API",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onOpenScriptDir) { Text("打开脚本目录") }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("我知道了") }
            }
        )
    }

    private fun openScriptsDirectory(context: Context) {
        runCatching {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
            val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary:Android/data/com.tencent.mm/WCX/scripts_java")
            intent.setDataAndType(uri, "resource/folder")
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }.onFailure {
            WeLogger.w(TAG, "无法通过 SAF 打开脚本目录", it)
            com.Johnny.wcx.utils.android.showToast("请手动打开: ${SCRIPTS_DIR.toAbsolutePath().toString()}")
        }
    }

    // ── 安全列出脚本子目录（带异常处理） ──────────────────────────────────
    private fun safeListScriptDirs(): List<Path> = runCatching {
        SCRIPTS_DIR.listDirectoryEntries().filter { it.isDirectory() }
    }.getOrElse { e ->
        WeLogger.e(TAG, "列出脚本目录失败: ${SCRIPTS_DIR.toAbsolutePath().toString()}", e)
        lastScanError = "无法读取脚本目录: ${e.message}"
        emptyList()
    }

    private fun listScriptEntries(): List<ScriptEntry> = runCatching {
        safeListScriptDirs()
            .sortedBy { it.name }
            .mapNotNull { scriptDir ->
                val mainFile = scriptDir / "main.java"
                val infoFile = scriptDir / "info.prop"
                if (!mainFile.exists() || !infoFile.exists()) return@mapNotNull null

                val info = runCatching {
                    JavaPlugin.parseInfoProp(infoFile.readText())
                }.getOrElse { e ->
                    WeLogger.w(TAG, "解析 info.prop 失败 '${scriptDir.name}'", e)
                    return@mapNotNull null
                }
                ScriptEntry(
                    dir = scriptDir,
                    info = info,
                    enabled = isScriptEnabled(scriptDir),
                )
            }
    }.getOrElse { e ->
        WeLogger.e(TAG, "列出脚本条目失败", e)
        lastScanError = "扫描脚本失败: ${e.message}"
        emptyList()
    }

    private fun isScriptEnabled(scriptDir: Path): Boolean =
        !(scriptDir / DISABLED_FLAG).exists()

    private fun setScriptEnabled(scriptDir: Path, enabled: Boolean): Boolean = runCatching {
        val disabledFlag = scriptDir / DISABLED_FLAG
        if (enabled) {
            disabledFlag.deleteIfExists()
        } else {
            disabledFlag.writeText("")
        }
        true
    }.onFailure {
        WeLogger.w(TAG, "failed to ${if (enabled) "enable" else "disable"} script '${scriptDir.name}'", it)
    }.getOrDefault(false)

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        JavaHookApi.unhookEverything()
        JavaEngine.executeAllOnUnload(scripts)
        scripts.clear()
    }

    override fun onInsert(table: String, values: ContentValues) {
        if (table == "fmessage_msginfo") {
            val isSend = values.getAsInteger("isSend") ?: 0
            if (isSend == 0) {
                val msgContent = values.getAsString("msgContent") ?: ""
                val fromusername = extractXmlAttr(msgContent, "fromusername").takeIf { it.isNotEmpty() }
                    ?: extractXmlTag(msgContent, "fromusername")
                val ticket = extractXmlAttr(msgContent, "ticket").takeIf { it.isNotEmpty() }
                    ?: extractXmlTag(msgContent, "ticket")
                val sceneStr = extractXmlAttr(msgContent, "scene").takeIf { it.isNotEmpty() }
                    ?: extractXmlTag(msgContent, "scene")
                val scene = sceneStr.toIntOrNull() ?: 0

                JavaEngine.executeAllOnNewFriend(scripts, fromusername, ticket, scene)
            }
        }
    }

    override fun onUpdate(
        table: String,
        values: ContentValues,
        whereClause: String?,
        whereArgs: Array<String>?,
        conflictAlgorithm: Int
    ) {
        if (table != "chatroom") return
        val chatroomName = values.getAsString("chatroomname") ?: return
        val memberCount = values.getAsInteger("memberCount") ?: return
        val memberlist = values.getAsString("memberlist") ?: return
        if (memberlist.isBlank()) return

        val cursor = WeDatabaseApi.rawQuery(
            "SELECT memberlist, memberCount FROM chatroom WHERE chatroomname = ?",
            arrayOf(chatroomName)
        )
        if (cursor.moveToFirst()) {
            val oldMemberCount = cursor.getInt(cursor.getColumnIndexOrThrow("memberCount"))
            val oldMemberListStr = cursor.getString(cursor.getColumnIndexOrThrow("memberlist"))
            cursor.close()

            if (oldMemberCount == 0 || oldMemberListStr.isNullOrBlank()) return

            val oldMembers = oldMemberListStr.split(";").filter { it.isNotBlank() }.toSet()
            val newMembers = memberlist.split(";").filter { it.isNotBlank() }.toSet()

            if (memberCount > oldMemberCount) {
                val joined = newMembers - oldMembers
                joined.forEach { userWxid ->
                    val nickname = WeDatabaseApi.getDisplayName(userWxid)
                    JavaEngine.executeAllOnMemberChange(scripts, "join", chatroomName, userWxid, nickname)
                }
            } else if (memberCount < oldMemberCount) {
                val left = oldMembers - newMembers
                left.forEach { userWxid ->
                    val nickname = WeDatabaseApi.getDisplayName(userWxid)
                    JavaEngine.executeAllOnMemberChange(scripts, "left", chatroomName, userWxid, nickname)
                }
            }
        }
    }
}
