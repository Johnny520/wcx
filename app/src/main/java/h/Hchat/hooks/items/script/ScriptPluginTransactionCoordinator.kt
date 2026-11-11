package h.Hchat.hooks.items.script

import android.content.Context
import h.Hchat.preferences.HchatStorage
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal object ScriptPluginTransactionCoordinator {
    private const val LOCK_FILE = "script_plugin_transactions.lock"
    private val processLock = ReentrantLock(true)
    private val lockDepth = ThreadLocal<Int>()

    fun <T> withPluginLocks(context: Context, pluginIds: Collection<String>, block: () -> T): T {
        pluginIds.forEach { require(it.isNotBlank()) { "插件 ID 不能为空" } }
        return processLock.withLock {
            val depth = lockDepth.get() ?: 0
            if (depth > 0) return@withLock block()

            val lockRoot = HchatStorage.storageDir(context.applicationContext ?: context)
            check(lockRoot.isDirectory) { "插件事务锁目录不可用" }
            val lockFile = File(lockRoot, LOCK_FILE)
            lockDepth.set(1)
            try {
                RandomAccessFile(lockFile, "rw").use { randomAccessFile ->
                    randomAccessFile.channel.use { channel ->
                        val fileLock = channel.lock()
                        try {
                            block()
                        } finally {
                            fileLock.release()
                        }
                    }
                }
            } finally {
                lockDepth.remove()
            }
        }
    }
}
