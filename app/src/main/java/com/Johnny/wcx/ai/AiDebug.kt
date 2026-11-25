package com.Johnny.wcx.ai

import com.Johnny.wcx.BuildConfig
import com.Johnny.wcx.features.ai.AiConfig
import com.Johnny.wcx.utils.WeLogger
import kotlinx.serialization.json.JsonArray

/**
 * debug 面板的数据源：把「实际发给模型的提示词」落一份到
 * `/sdcard/Download/知友AI/prompts/`。
 *
 * 只读文件不依赖任何跨进程通道：微信进程自己能写 Download 下自己创建的文件
 * （之前实测过 —— 别人写的读不到，自己写没问题）。debug 专用。
 *
 * 内容三段：tools（完整 JSON schema）、system、user，外加一行思考开关状态。
 * tools 是请求体里独立的一段，不在 system/user 里；不写出来会让人以为「没有工具」。
 *
 * 内存里**不留**请求与响应：聊天内容属于隐私，落盘已经让人很难安心，
 * 进程内再存一份也只会在崩溃时进 core dump，而没有任何 UI 读它。
 *
 * 独立成文件：四个调用方（初始化、审查、建议、agent 循环）分属不同层，
 * 放在 `AiProfileInitializer` 里会让底层的循环反过来依赖业务流程。
 */
object AiDebug {

    /**
     * 写出请求部分。**响应必须用 [appendResponse] 追加到同一个文件**：
     * 只写请求的话，看文件的人会以为「模型没有调工具」——请求里带 tools 只代表
     * 「告诉模型有哪些工具可用」，是否调用取决于响应。
     *
     * @return 落盘路径；非 debug 或写入失败返回 null。
     */
    fun dumpPrompt(
        wxId: String,
        label: String,
        system: String,
        user: String,
        tools: JsonArray? = null,
        /** 本次请求是否带思考参数。dump 不含请求体，没有这一行就看不出开关状态。 */
        thinking: Boolean = false,
    ): String? {
        if (!BuildConfig.DEBUG) return null
        return runCatching {
            val root = java.io.File(
                android.os.Environment.getExternalStorageDirectory(),
                "Download/知友AI/prompts",
            )
            if (!root.isDirectory && !root.mkdirs()) return null
            val stamp = java.text.SimpleDateFormat("MMdd-HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            val file = java.io.File(
                root,
                safeName(wxId, "unknown") + "-" + safeName(label, "slice") + "-" + stamp + ".txt",
            )
            val toolText = tools?.toString() ?: "（本次没有下发工具）"
            file.writeText(
                "===== 请求 =====\n" +
                    "thinking: " + (if (thinking) "开（high）" else "关") + "\n\n" +
                    "===== tools =====\n" + toolText + "\n\n" +
                    "===== system =====\n" + system + "\n\n" +
                    "===== user =====\n" + user + "\n"
            )
            WeLogger.i("AiDebug", "提示词已保存：" + file.absolutePath)
            file.absolutePath
        }.getOrNull()
    }

    /**
     * 把一次往返的响应追加到 [path]。
     *
     * 记的是「模型实际回了什么」：`finish_reason`、思考字数、每个 tool_call 的名字与
     * 完整 arguments、以及纯文本回复。没有这一段就无法回答
     * 「这片到底调没调工具 / 写进去的标签长什么样」。
     */
    fun appendResponse(path: String?, result: AiConfig.ChatResult, round: Int = 1) {
        if (!BuildConfig.DEBUG || path.isNullOrBlank()) return
        runCatching {
            val section = buildString {
                append("\n\n===== 响应 · 第 ").append(round).append(" 轮 =====\n")
                append("HTTP ").append(result.responseCode)
                append(" · finish_reason: ").append(result.finishReason ?: "-").append("\n")
                result.reasoning?.let { append("思考 ").append(it.length).append(" 字\n") }
                val error = result.error
                if (error != null) {
                    append("错误：").append(error).append("\n")
                    return@buildString
                }
                append("工具调用：").append(result.toolCalls.size).append("\n")
                result.toolCalls.forEach { call ->
                    append("\n--- ").append(call.name).append(" ---\n")
                    append(call.arguments).append("\n")
                }
                result.text?.let { append("\n--- 正文 ---\n").append(it).append("\n") }
            }
            java.io.File(path).appendText(section)
        }
    }

    /** 文件名里保留中文（否则「2026 年 10 月」会变成 `2026___10__`，几乎没法对照）。 */
    private fun safeName(raw: String, fallback: String): String =
        raw.replace(Regex("""[\\/:*?"<>|\s]+"""), "_")
            .trim('_')
            .take(40)
            .ifBlank { fallback }
}
