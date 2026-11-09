package h.Hchat.hooks.items.script

import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * 脚本插件目录写权限保障。
 *
 * 原实现位于 Agent 工具集 h.Hchat.hooks.items.script.agent.ScriptPluginAgentWorkspaceTools，
 * Agent 能力下线后这里保留脚本导入/删除所必需的写权限修复逻辑（行为与原来一致）。
 */
object ScriptPluginWriteAccess {

    /** 确保脚本插件根目录与目标插件目录对当前微信进程可读写。 */
    fun ensureLegacyWriteAccess(root: File, target: File) {
        repairAccessEntry(root)
        if (target.exists()) repairAccessTree(target)
        ensureWritableDirectory(root, "脚本插件根目录")
        if (target.isDirectory) ensureWritableDirectory(target, "插件目录 ${target.name}")
    }

    private fun repairAccessTree(file: File) {
        if (!file.exists() || file.absoluteFile != file.canonicalFile) return
        repairAccessEntry(file)
        if (file.isDirectory) file.listFiles()?.forEach(::repairAccessTree)
    }

    private fun repairAccessEntry(file: File) {
        if (!file.exists()) return
        runCatching { file.setReadable(true, true) }
        runCatching { file.setWritable(true, true) }
        if (file.isDirectory) runCatching { file.setExecutable(true, true) }
        runCatching {
            val stat = Os.stat(file.absolutePath)
            val required = if (file.isDirectory) 0x1c0 else 0x180
            Os.chmod(file.absolutePath, (stat.st_mode and 0x0fff) or required)
        }
    }

    private fun ensureWritableDirectory(directory: File, label: String) {
        repairAccessEntry(directory)
        val probe = probeDirectoryWrite(directory)
        check(probe.success) {
            val stat = runCatching { Os.stat(directory.absolutePath) }.getOrNull()
            val owner = stat?.let { "uid=${it.st_uid}, gid=${it.st_gid}" } ?: "无法读取所有者"
            "$label 不可写，当前微信进程 uid=${android.os.Process.myUid()}，$owner，${probe.detail}"
        }
    }

    private fun probeDirectoryWrite(directory: File): DirectoryWriteProbe {
        if (!directory.isDirectory) return DirectoryWriteProbe(false, "目录不存在")
        if (!directory.canWrite() || !directory.canExecute()) {
            return DirectoryWriteProbe(false, "File.canWrite/canExecute=false")
        }
        val probe = File(directory, ".wechatai-script-access-${UUID.randomUUID().toString().take(8)}")
        return runCatching {
            FileOutputStream(probe).use { it.write(0) }
            check(probe.delete()) { "无法清理权限探针" }
            DirectoryWriteProbe(true, "写入探针成功")
        }.getOrElse { error ->
            runCatching { probe.delete() }
            DirectoryWriteProbe(
                false,
                "${error.javaClass.simpleName}: ${error.message.orEmpty().ifBlank { "未知写入错误" }}"
            )
        }
    }

    private data class DirectoryWriteProbe(
        val success: Boolean,
        val detail: String
    )
}
