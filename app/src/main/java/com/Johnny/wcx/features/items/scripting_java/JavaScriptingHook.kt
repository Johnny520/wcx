package com.Johnny.wcx.features.items.scripting_java

import android.content.ContentValues
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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

    private val SCRIPTS_DIR = (KnownPaths.moduleData / "scripts_java").createDirsSafe()

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
            WeLogger.d(TAG, "loading java scripts...")
            for (scriptDir in SCRIPTS_DIR.listDirectoryEntries().filter { it.isDirectory() }) {
                val dirName = scriptDir.name
                if (!isScriptEnabled(scriptDir)) {
                    WeLogger.d(TAG, "skipping '$dirName': disabled")
                    continue
                }

                val mainFile = scriptDir / "main.java"
                val infoFile = scriptDir / "info.prop"
                if (!mainFile.exists() || !infoFile.exists()) {
                    WeLogger.w(TAG, "skipping '$dirName': missing main.java or info.prop")
                    continue
                }

                val content = runCatching { mainFile.readText() }.getOrElse { continue }
                val infoPropContent = runCatching { infoFile.readText() }.getOrElse { continue }
                val info = JavaPlugin.parseInfoProp(infoPropContent)
                WeLogger.d(TAG, "loaded script, name='${info.name}', length=${content.length}")

                val plugin = JavaPlugin(
                    name = dirName,
                    dir = scriptDir,
                    info = info,
                    content = content,
                    interpreter = Interpreter(null, "")
                )
                scripts[dirName] = plugin
            }

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
                                if (entries.isEmpty()) {
                                    Text("暂无脚本，点击下方「使用说明」了解如何添加脚本")
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
        val scrollState = androidx.compose.foundation.rememberScrollState()
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
            com.Johnny.wcx.utils.android.showToast("请手动打开路径: Android/data/com.tencent.mm/WCX/scripts_java/")
        }
    }

    private fun listScriptEntries(): List<ScriptEntry> =
        SCRIPTS_DIR.listDirectoryEntries()
            .filter { it.isDirectory() }
            .sortedBy { it.name }
            .mapNotNull { scriptDir ->
                val mainFile = scriptDir / "main.java"
                val infoFile = scriptDir / "info.prop"
                if (!mainFile.exists() || !infoFile.exists()) return@mapNotNull null

                val info = runCatching {
                    JavaPlugin.parseInfoProp(infoFile.readText())
                }.getOrNull() ?: return@mapNotNull null
                ScriptEntry(
                    dir = scriptDir,
                    info = info,
                    enabled = isScriptEnabled(scriptDir),
                )
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
