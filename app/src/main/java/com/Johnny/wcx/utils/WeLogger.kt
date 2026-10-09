package com.Johnny.wcx.utils

import android.util.Log
import com.Johnny.wcx.BuildConfig
import com.Johnny.wcx.utils.fs.KnownPaths
import com.Johnny.wcx.utils.fs.createDirsSafe
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.div
import kotlin.math.min

object WeLogger {

    private const val TAG = BuildConfig.TAG

    private const val CHUNK_SIZE = 4000
    // A single verbose packet/database dump must not enqueue hundreds of thousands of chars.
    private const val MAX_CHUNKS = 16
    private const val MAX_LOG_FILE_BYTES = 4L * 1024 * 1024
    private const val MAX_RECORD_CHARS = 32_000
    private const val MAX_TOTAL_LOG_BYTES = 24L * 1024 * 1024
    private const val MAX_LOG_AGE_DAYS = 3L
    private const val QUEUE_CAPACITY = 2048
    private const val RESERVED_IMPORTANT_CAPACITY = 128
    private const val BATCH_SIZE = 64
    private const val FLUSH_TIMEOUT_MILLIS = 3000L

    private val timestampFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    private sealed interface WriteTask {
        data class Record(
            val level: String,
            val tag: String?,
            val msg: String,
            val throwable: Throwable?,
            val timestamp: LocalDateTime,
        ) : WriteTask

        class Flush(val completed: CountDownLatch) : WriteTask
        class Clear(val completed: CountDownLatch) : WriteTask
    }

    /**
     * A bounded queue keeps logging fire-and-forget even if storage is temporarily slow. A
     * dropped-record counter is emitted by the writer once it catches up, so loss is visible.
     */
    private val writeQueue = ArrayBlockingQueue<WriteTask>(QUEUE_CAPACITY, true)
    private val droppedRecords = AtomicLong()
    private val writerThread = Thread(::runWriter, "WeKit-Logger").apply {
        isDaemon = true
        start()
    }

    private var writer: BufferedWriter? = null
    private var currentLogDate: LocalDate? = null
    private var currentLogPath: java.nio.file.Path? = null
    private var currentLogBytes: Long = 0L

    // ========== File Logging Internals ==========

    private fun getOrRotateWriter(logDate: LocalDate): BufferedWriter? {
        if (writer != null && currentLogDate == logDate) return writer

        closeWriter()
        val logsDir = runCatching {
            (KnownPaths.moduleData / "logs").createDirsSafe()
        }.getOrNull() ?: return null

        pruneLogs(logsDir)
        val logPath = logsDir / "wcx-${dateFmt.format(logDate)}.log"
        return runCatching {
            BufferedWriter(
                OutputStreamWriter(
                    Files.newOutputStream(
                        logPath,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.APPEND,
                    ),
                    StandardCharsets.UTF_8,
                ),
            ).also {
                writer = it
                currentLogDate = logDate
                currentLogPath = logPath
                currentLogBytes = Files.size(logPath)
            }
        }.getOrNull()
    }

    private fun closeWriter() {
        writer?.runCatching { flush(); close() }
        writer = null
        currentLogDate = null
        currentLogPath = null
        currentLogBytes = 0L
    }

    private fun rotateCurrentLog() {
        val oldPath = currentLogPath
        val oldDate = currentLogDate
        closeWriter()
        if (oldPath != null && oldDate != null) {
            runCatching {
                if (Files.exists(oldPath) && Files.size(oldPath) > 0L) {
                    val dir = oldPath.parent
                    var rotated = dir.resolve("wcx-${dateFmt.format(oldDate)}-${System.currentTimeMillis()}.log")
                    var suffix = 1
                    while (Files.exists(rotated)) {
                        rotated = dir.resolve("wcx-${dateFmt.format(oldDate)}-${System.currentTimeMillis()}-$suffix.log")
                        suffix++
                    }
                    Files.move(oldPath, rotated, StandardCopyOption.REPLACE_EXISTING)
                }
            }.onFailure { Log.w(TAG, "failed to rotate run log", it) }
            runCatching { pruneLogs(oldPath.parent) }
        }
    }

    private fun pruneLogs(logsDir: java.nio.file.Path) {
        runCatching {
            val thresholdDate = LocalDate.now().minusDays(MAX_LOG_AGE_DAYS)
            val logFileRegex = Regex("""wcx-(\d{4}-\d{2}-\d{2})(?:-\d{13}(?:-\d+)?)?\.log""")
            val files = logsDir.toFile().listFiles()
                ?.filter { it.isFile && logFileRegex.matches(it.name) }
                ?.toMutableList()
                ?: return

            files.forEach { file ->
                val match = logFileRegex.matchEntire(file.name) ?: return@forEach
                val fileDate = runCatching { LocalDate.parse(match.groupValues[1], dateFmt) }.getOrNull()
                if (fileDate != null && fileDate.isBefore(thresholdDate)) {
                    file.delete()
                }
            }

            // Enforce a global disk budget as well as age-based retention. Oldest files go first.
            val remaining = logsDir.toFile().listFiles()
                ?.filter { it.isFile && logFileRegex.matches(it.name) }
                ?.sortedBy { it.lastModified() }
                ?: return
            var totalBytes = remaining.sumOf { it.length() }
            for (file in remaining) {
                if (totalBytes <= MAX_TOTAL_LOG_BYTES) break
                val length = file.length()
                if (file.delete()) totalBytes -= length
            }
        }
    }

    private fun clearRunLogsOnWriterThread() {
        closeWriter()
        droppedRecords.set(0L)
        val dir = logsDir ?: return
        val regex = Regex("""wcx-\d{4}-\d{2}-\d{2}(?:-\d{13}(?:-\d+)?)?\.log""")
        runCatching {
            dir.toFile().listFiles()?.filter { it.isFile && regex.matches(it.name) }?.forEach { it.delete() }
        }
    }

    private fun runWriter() {
        val batch = ArrayList<WriteTask>(BATCH_SIZE)

        while (!Thread.currentThread().isInterrupted) {
            val first = try {
                writeQueue.take()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            batch += first
            writeQueue.drainTo(batch, BATCH_SIZE - 1)

            var hasWrites = false
            batch.forEach { task ->
                when (task) {
                    is WriteTask.Record -> {
                        writeDroppedNotice(task.timestamp)
                        val written = writeRecord(task)
                        hasWrites = written || hasWrites
                        if (written && (task.level == "E" || task.level == "W" || task.level == "A")) {
                            flushWriter()
                            hasWrites = false
                        }
                    }

                    is WriteTask.Flush -> {
                        try {
                            writeDroppedNotice(LocalDateTime.now())
                            flushWriter()
                        } finally {
                            task.completed.countDown()
                        }
                        hasWrites = false
                    }

                    is WriteTask.Clear -> {
                        try {
                            clearRunLogsOnWriterThread()
                        } finally {
                            task.completed.countDown()
                        }
                        hasWrites = false
                    }
                }
            }

            if (hasWrites) flushWriter()
            batch.clear()
        }
    }

    private fun writeRecord(record: WriteTask.Record): Boolean {
        var w = getOrRotateWriter(record.timestamp.toLocalDate()) ?: return false
        return runCatching {
            val text = buildString {
                append(timestampFmt.format(record.timestamp))
                append(' ')
                append(record.level)
                append('/')
                append(TAG)
                append(' ')
                append(record.tag)
                append(": ")
                append(record.msg)
                if (record.throwable != null) {
                    append('\n')
                    append(Log.getStackTraceString(record.throwable))
                }
            }
            // Bound a single record too: stack traces or generated payloads can otherwise be huge.
            val boundedText = if (text.length > MAX_RECORD_CHARS) {
                text.take(MAX_RECORD_CHARS) + "\n[log record truncated at $MAX_RECORD_CHARS characters]"
            } else text
            val bytesToWrite = boundedText.toByteArray(StandardCharsets.UTF_8).size.toLong() + 1L

            if (currentLogBytes > 0L && currentLogBytes + bytesToWrite > MAX_LOG_FILE_BYTES) {
                rotateCurrentLog()
                w = getOrRotateWriter(record.timestamp.toLocalDate()) ?: return false
            }
            w.write(boundedText)
            w.write('\n'.code)
            currentLogBytes += bytesToWrite
            true
        }.getOrElse {
            Log.e(TAG, "failed to write log file", it)
            false
        }
    }

    private fun writeDroppedNotice(timestamp: LocalDateTime) {
        val count = droppedRecords.getAndSet(0)
        if (count == 0L) return

        writeRecord(
            WriteTask.Record(
                level = "W",
                tag = "WeLogger",
                msg = "dropped $count log record(s) because the async queue was full or reserved for important logs",
                throwable = null,
                timestamp = timestamp,
            )
        )
    }

    private fun flushWriter() {
        writer?.runCatching { flush() }
    }

    private fun enqueue(record: WriteTask.Record) {
        // Keep verbose diagnostics in logcat, but avoid persisting high-volume D/V traffic in
        // release builds. INFO/WARN/ERROR remain available in the in-app log viewer.
        if (record.level == "V" || (record.level == "D" && !BuildConfig.DEBUG)) return
        val isImportant = record.level == "E" || record.level == "W" || record.level == "A"
        val hasRoom = isImportant || writeQueue.remainingCapacity() > RESERVED_IMPORTANT_CAPACITY
        if (!hasRoom || !writeQueue.offer(record)) {
            droppedRecords.incrementAndGet()
        }
    }

    /**
     * Wait for all records currently queued, then flush the active writer. This is intentionally
     * blocking because it is used at explicit synchronization points such as crash handling and
     * before the log viewer reads the current file; normal log calls never wait for the writer.
     */
    fun flush() {
        if (Thread.currentThread() === writerThread) {
            writeDroppedNotice(LocalDateTime.now())
            flushWriter()
            return
        }

        val completed = CountDownLatch(1)
        val barrier = WriteTask.Flush(completed)
        val enqueued = try {
            writeQueue.offer(barrier, FLUSH_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!enqueued) {
            Log.w(TAG, "timed out while enqueueing log flush barrier")
            return
        }

        val finished = try {
            completed.await(FLUSH_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) {
            Log.w(TAG, "timed out while flushing log queue")
        }
    }

    // ========== File Logging: public accessors (for the log viewer UI) ==========

    /** The directory run logs are written to (`moduleData/logs`), created on first access. */
    val logsDir: java.nio.file.Path?
        get() = runCatching { (KnownPaths.moduleData / "logs").createDirsSafe() }.getOrNull()

    /**
     * All run-log segments (current daily file plus rotated size-limited segments), newest first.
     * Flushes the active writer first so the current file reflects recent entries before reading.
     */
    val allLogFiles: List<java.nio.file.Path>
        get() {
            flush()
            val dir = logsDir ?: return emptyList()
            val regex = Regex("""wcx-\d{4}-\d{2}-\d{2}(?:-\d{13}(?:-\d+)?)?\.log""")
            return runCatching {
                dir.toFile().listFiles()
                    ?.filter { it.isFile && regex.matches(it.name) }
                    ?.sortedByDescending { it.lastModified() }
                    ?.map { it.toPath() }
                    ?: emptyList()
            }.getOrDefault(emptyList())
        }

    /** Clears run logs on the writer thread so the open file handle cannot keep writing to a deleted file. */
    fun clearRunLogs() {
        if (Thread.currentThread() === writerThread) {
            clearRunLogsOnWriterThread()
            return
        }
        val completed = CountDownLatch(1)
        val enqueued = try {
            writeQueue.offer(WriteTask.Clear(completed), FLUSH_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!enqueued) {
            Log.w(TAG, "timed out while enqueueing run-log clear request")
            return
        }
        try {
            if (!completed.await(FLUSH_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "timed out while clearing run logs")
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    // ========== Tag + String ==========

    fun e(tag: String?, msg: String) {
        Log.e(TAG, "$tag: $msg")
        enqueue(WriteTask.Record("E", tag, msg, null, LocalDateTime.now()))
    }

    fun w(tag: String?, msg: String) {
        Log.w(TAG, "$tag: $msg")
        enqueue(WriteTask.Record("W", tag, msg, null, LocalDateTime.now()))
    }

    fun i(tag: String?, msg: String) {
        Log.i(TAG, "$tag: $msg")
        enqueue(WriteTask.Record("I", tag, msg, null, LocalDateTime.now()))
    }

    fun d(tag: String?, msg: String) {
        Log.d(TAG, "$tag: $msg")
        enqueue(WriteTask.Record("D", tag, msg, null, LocalDateTime.now()))
    }

    fun v(tag: String?, msg: String) {
        Log.v(TAG, "$tag: $msg")
        enqueue(WriteTask.Record("V", tag, msg, null, LocalDateTime.now()))
    }

    // ========== Tag + String + Throwable ==========

    fun e(tag: String?, msg: String, e: Throwable) {
        Log.e(TAG, "$tag: $msg", e)
        enqueue(WriteTask.Record("E", tag, msg, e, LocalDateTime.now()))
    }

    fun w(tag: String?, msg: String, e: Throwable) {
        Log.w(TAG, "$tag: $msg", e)
        enqueue(WriteTask.Record("W", tag, msg, e, LocalDateTime.now()))
    }

    fun i(tag: String?, msg: String, e: Throwable) {
        Log.i(TAG, "$tag: $msg", e)
        enqueue(WriteTask.Record("I", tag, msg, e, LocalDateTime.now()))
    }

    fun d(tag: String?, msg: String, e: Throwable) {
        Log.d(TAG, "$tag: $msg", e)
        enqueue(WriteTask.Record("D", tag, msg, e, LocalDateTime.now()))
    }

    fun v(tag: String?, msg: String, e: Throwable) {
        Log.v(TAG, "$tag: $msg", e)
        enqueue(WriteTask.Record("V", tag, msg, e, LocalDateTime.now()))
    }

    // ========== Stack Trace ==========

    val currentStackTrace: String
        get() {
            return Thread.currentThread().stackTrace
                .drop(2) // drop getStackTrace + this function
                .joinToString(separator = "\n") { element ->
                    "at ${element.className}.${element.methodName}(${element.fileName}:${element.lineNumber})"
                }
        }

    // ========== Chunked ==========

    fun logChunked(priority: Int, tag: String, msg: String) {
        if (msg.length <= CHUNK_SIZE) {
            Log.println(priority, TAG, "$tag: $msg")
            enqueue(WriteTask.Record(priority.toPriorityChar(), tag, msg, null, LocalDateTime.now()))
            return
        }

        val len = msg.length
        val chunkCount = (len + CHUNK_SIZE - 1) / CHUNK_SIZE
        if (chunkCount > MAX_CHUNKS) {
            val head = msg.substring(0, CHUNK_SIZE)
            val headMsg = "[chunked] too long ($len chars, $chunkCount chunks). head:\n$head"
            val truncMsg = "[chunked] truncated. consider writing to file for full dump."
            Log.println(priority, TAG, "$tag: $headMsg")
            Log.println(priority, TAG, "$tag: $truncMsg")
            val timestamp = LocalDateTime.now()
            enqueue(WriteTask.Record(priority.toPriorityChar(), tag, headMsg, null, timestamp))
            enqueue(WriteTask.Record(priority.toPriorityChar(), tag, truncMsg, null, timestamp))
            return
        }

        var i = 0
        var part = 1
        val timestamp = LocalDateTime.now()
        while (i < len) {
            val end = min(i + CHUNK_SIZE, len)
            val chunk = msg.substring(i, end)
            val partMsg = "[part $part/$chunkCount] $chunk"
            Log.println(priority, TAG, "$tag: $partMsg")
            enqueue(WriteTask.Record(priority.toPriorityChar(), tag, partMsg, null, timestamp))
            i += CHUNK_SIZE
            part++
        }
    }

    fun logChunkedI(tag: String, msg: String) = logChunked(Log.INFO, tag, msg)
    fun logChunkedD(tag: String, msg: String) = logChunked(Log.DEBUG, tag, msg)

    // ========== Helpers ==========

    private fun Int.toPriorityChar(): String = when (this) {
        Log.VERBOSE -> "V"
        Log.DEBUG -> "D"
        Log.INFO -> "I"
        Log.WARN -> "W"
        Log.ERROR -> "E"
        Log.ASSERT -> "A"
        else -> "?"
    }
}
