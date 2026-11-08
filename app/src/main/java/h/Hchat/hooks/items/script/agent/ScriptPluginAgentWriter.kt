package h.Hchat.hooks.items.script.agent

import android.content.Context
import android.system.Os
import h.Hchat.hooks.items.script.ScriptPluginRuntime
import h.Hchat.hooks.items.script.ScriptPluginSettings
import h.Hchat.hooks.items.script.ScriptPluginTransactionCoordinator
import h.Hchat.preferences.HchatStorage
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

object ScriptPluginAgentWriter {
    fun save(context: Context, draft: ScriptPluginAgentDraft, overwrite: Boolean): Result<File> {
        return runCatching {
            val normalized = ScriptPluginAgentValidator.normalize(draft)
            val validation = ScriptPluginAgentValidator.validate(normalized)
            check(validation.canSave) { validation.errors.joinToString("\n") { it.message } }
            ScriptPluginTransactionCoordinator.withPluginLocks(context, listOf(normalized.pluginId)) {
                val root = ScriptPluginRuntime.ensureDirs(context).canonicalFile
                val requested = File(root, normalized.pluginId).absoluteFile
                val target = requested.canonicalFile
                check(target.parentFile == root && requested == target) { "插件目录不在脚本插件根目录内" }
                if (target.exists() && !overwrite) throw IllegalStateException("插件已存在，需要确认覆盖")
                ScriptPluginAgentWorkspaceTools.ensureLegacyWriteAccess(root, target)

                ScriptPluginRuntime.setPluginEnabled(context, normalized.pluginId, false).getOrThrow()
                if (!target.isDirectory && !target.mkdirs()) check(target.isDirectory) { "创建插件目录失败" }
                atomicWrite(File(target, "main.java"), normalized.mainJava)
                atomicWrite(File(target, "info.prop"), normalized.infoProp)
                HchatStorage.preferences(context, ScriptPluginSettings.PREFS_NAME).edit()
                    .putBoolean(ScriptPluginSettings.pluginEnableKey(normalized.pluginId), false)
                    .commit()
                target
            }
        }
    }

    fun delete(context: Context, pluginId: String): Result<Unit> {
        return runCatching {
            val id = pluginId.trim()
            check(id.isNotBlank() && id != "." && id != "..") { "插件目录名不能为空" }
            check(id == ScriptPluginAgentValidator.safePluginId(id) && !id.contains("..")) {
                "插件目录名包含不允许的路径字符"
            }
            ScriptPluginTransactionCoordinator.withPluginLocks(context, listOf(id)) {
                val root = ScriptPluginRuntime.ensureDirs(context).canonicalFile
                val requested = File(root, id).absoluteFile
                val target = requested.canonicalFile
                check(target.parentFile == root && requested == target) { "插件目录不在脚本插件根目录内" }
                check(target.isDirectory) { "未找到插件目录: $id" }
                ScriptPluginAgentWorkspaceTools.ensureLegacyWriteAccess(root, target)

                ScriptPluginRuntime.setPluginEnabled(context, id, false).getOrThrow()
                deleteTree(target)
                HchatStorage.preferences(context, ScriptPluginSettings.PREFS_NAME).edit()
                    .remove(ScriptPluginSettings.pluginEnableKey(id))
                    .commit()
                Unit
            }
        }
    }

    private fun atomicWrite(target: File, content: String) {
        val temp = File(target.parentFile, ".${target.name}.agent.tmp")
        FileOutputStream(temp).use { output ->
            output.write(content.toByteArray(StandardCharsets.UTF_8))
            output.fd.sync()
        }
        try {
            Os.rename(temp.absolutePath, target.absolutePath)
        } catch (error: Throwable) {
            if (!temp.renameTo(target)) {
                temp.delete()
                throw IllegalStateException("写入 ${target.name} 失败", error)
            }
        }
    }

    private fun deleteTree(target: File) {
        val absolute = target.absoluteFile
        val canonical = target.canonicalFile
        if (absolute != canonical) {
            check(target.delete()) { "删除符号链接失败: ${target.name}" }
            return
        }
        if (target.isDirectory) {
            target.listFiles()?.forEach(::deleteTree)
        }
        check(target.delete()) { "删除插件文件失败: ${target.name}" }
    }
}
