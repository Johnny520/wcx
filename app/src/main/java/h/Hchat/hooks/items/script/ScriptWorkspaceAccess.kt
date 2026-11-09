package h.Hchat.hooks.items.script

import java.io.File

/**
 * 脚本目录写权限自愈。
 *
 * 原实现位于 Agent 工作区工具（ScriptPluginAgentWorkspaceTools）中；Agent 功能移除后，
 * 插件管理（重命名/删除）仍需要对旧版/外部创建的插件目录做权限修复，因此保留此精简实现。
 *
 * 说明：插件根目录通常位于应用专属外部存储（externalMediaDir）下，属当前进程所有，
 * java.io.File 的 setReadable/setWritable 即可完成修复，无需 root。
 */
internal object ScriptWorkspaceAccess {

    @JvmStatic
    fun ensureLegacyWriteAccess(root: File, target: File) {
        runCatching { repairTree(root) }
        runCatching {
            if (target.exists()) repairTree(target)
        }
    }

    private fun repairTree(dir: File) {
        if (!dir.exists()) return
        runCatching { dir.setReadable(true, false) }
        runCatching { dir.setWritable(true, false) }
        runCatching { dir.setExecutable(true, false) }
        val children = runCatching { dir.listFiles().orEmpty() }.getOrDefault(emptyArray())
        for (child in children) {
            if (child.isDirectory) {
                repairTree(child)
            } else {
                runCatching { child.setReadable(true, false) }
                runCatching { child.setWritable(true, false) }
            }
        }
    }
}
