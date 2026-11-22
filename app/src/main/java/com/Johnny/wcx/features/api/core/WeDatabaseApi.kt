package com.Johnny.wcx.features.api.core

import android.annotation.SuppressLint
import android.database.Cursor
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.tencent.wcdb.DatabaseErrorHandler
import com.tencent.wcdb.database.SQLiteCipherSpec
import com.tencent.wcdb.database.SQLiteDatabase
import dev.ujhhgtg.reflekt.reflekt
import com.Johnny.wcx.constants.Preferences
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexClass
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.api.core.models.SelfProfileField
import com.Johnny.wcx.features.api.core.models.WeContact
import com.Johnny.wcx.features.api.core.models.WeGroup
import com.Johnny.wcx.features.api.core.models.WeMessage
import com.Johnny.wcx.features.api.core.models.WeOfficialAccount
import com.Johnny.wcx.features.api.net.models.protobuf.ChatRoomDataProto
import com.Johnny.wcx.features.core.ApiFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.reflection.BString
import com.Johnny.wcx.utils.reflection.int
import com.Johnny.wcx.utils.strings.isGroupChatWxId
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import java.lang.reflect.Modifier

@OptIn(ExperimentalSerializationApi::class)
@SuppressLint("DiscouragedApi")
@Feature(name = "数据库服务", categories = ["API"], description = "提供数据库直接查询能力")
object WeDatabaseApi : ApiFeature(), IResolveDex {

    /** `methodGetStorage` 只是 DexKit 兜底；主路径用反射直接拿 Storage，解析不到不影响取库。 */
    override val supersededAnchors: Map<String, String> = mapOf(
        "WeDatabaseApi:methodGetStorage" to "DexKit 兜底路径，主路径用反射获取 Storage",
    )

    private val classMmKernel by dexClass {
        matcher {
            usingEqStrings("MicroMsg.MMKernel", "Kernel not null, has initialized.")
        }
    }
    private val methodGetStorage by dexMethod {
        matcher {
            declaredClass(classMmKernel.clazz)
            modifiers = Modifier.PUBLIC or Modifier.STATIC
            paramCount = 0
            // 返回类型必须是 CoreStorage，否则会误中 MMKernel 的单例 getter。
            // 8.0.80 实测：zp0.j1 里 j()=getInstance()（返回 MMKernel 自身）、x()=getCoreStorage()（返回 zp0.b0=CoreStorage），
            // 两者都满足 public+static+无参，仅靠 "Kernel not initialized by MMApplication!" 会命中 j()，
            // 导致 storageObj 是 MMKernel，随后 initializeDatabase 在其上找不到 SqliteDB 字段而整条「数据库服务」装挂。
            returnType = classCoreStorage.clazz.name
            usingStrings("Kernel not initialized by MMApplication!")
        }
    }
    private val classCoreStorage by dexClass {
        matcher {
            usingEqStrings(
                "MMKernel.CoreStorage",
                "CheckData path[%s] blocksize:%s blockcount:%s availcount:%s"
            )
        }
    }
    private val classConfigStorage by dexClass {
        searchPackages("com.tencent.mm.storage")
        matcher {
            usingEqStrings("MicroMsg.ConfigStorage", "shouldProcessEvent db is close :%s")
        }
    }
    private val classSqliteDbWrapper by dexClass {
        matcher {
            usingEqStrings("MicroMsg.SqliteDB", "sql is null ")
        }
    }

    lateinit var db: SQLiteDatabase

    /**
     * 上一次初始化用的 CoreStorage 实例。
     *
     * 微信切号会重建 storage；只有实例变化时才重新解析 db，避免每次 getCoreStorage
     * 都跑一轮反射（那是被频繁调用的方法）。
     */
    private var lastStorageRef: java.lang.ref.WeakReference<Any>? = null

    val isReady: Boolean get() = ::db.isInitialized

    /**
     * `message.createTime` 的单位倍数：`1` = 秒，`1000` = 毫秒。
     *
     * 不能假定秒：同一个微信版本在不同库上就见过两种（`ExportChatHistory` 直接把它塞进
     * `Date(long)`，`MessageTimeEnhancements` 用 `ofEpochMilli`，两处都按毫秒处理）。
     * 按秒算窗口时，毫秒值比上界大三个数量级，查询不报错但**永远返回 0 条** ——
     * 这是最容易被当成「没有记录」的静默错误。
     *
     * 探测方式：取全表 `MAX(createTime)` 的量级（秒值 10 位、毫秒值 13 位），一次查询缓存。
     */
    @Volatile
    private var messageTimeScaleCache = 0L

    /** 按需初始化失败后的冷却：3 秒内不重复尝试，避免高频查询把反射跑成热路径。 */
    private const val INIT_RETRY_COOLDOWN_MS = 3_000L

    @Volatile
    private var lastInitAttemptAt = 0L

    /**
     * 按需初始化数据库。
     *
     * 正常路径是 hook `MMKernel.getCoreStorage()`：微信第一次取 storage 时把 db 存下来。
     * 但**微信可能在模块加载之前就取过一次**（冷启动时 storage 往往在很早期创建），
     * 那样这个 hook 在整个进程生命周期里都不会再触发 —— db 一直是未初始化状态，
     * 所有查询都抛 `lateinit property db has not been initialized`，被 runCatching 吞成空表，
     * 表现就是「功能静默不工作」。
     *
     * 这里主动把同一个静态方法**调一次**，拿 storage 再走 [initializeDatabase]，
     * 与 hook 路径共用同一段解析逻辑，不改变任何既有行为（hook 仍优先，先到先得）。
     *
     * @return db 是否可用。失败会有冷却期，避免每次查询都做一轮反射。
     */
    @Synchronized
    fun ensureDatabase(): Boolean {
        if (isReady) return true
        val now = SystemClock.elapsedRealtime()
        if (now - lastInitAttemptAt < INIT_RETRY_COOLDOWN_MS) return isReady
        lastInitAttemptAt = now

        // 1) DexKit 锚点（部分版本可用），失败不影响下一步
        val fromAnchor = runCatching { methodGetStorage.method.invoke(null) }.getOrNull()

        // 2) 反射兜底：MMKernel 里「静态 + 无参 + 返回 CoreStorage」的方法
        val storage = fromAnchor ?: runCatching {
            val kernelCls = classMmKernel.clazz
            val coreStorageCls = classCoreStorage.clazz
            kernelCls.reflekt().firstMethodOrNull {
                parameterCount = 0
                modifiers?.let { Modifier.isStatic(it) } == true
                returnType = coreStorageCls
            }?.invoke(null)
        }.getOrNull()

        if (storage == null) {
            WeLogger.w(TAG, "数据库按需初始化失败：取不到 CoreStorage（锚点与反射均未命中）")
            return false
        }
        initializeDatabase(storage)
        if (!isReady) {
            WeLogger.w(TAG, "数据库按需初始化失败：拿到 ${storage.javaClass.name} 但未解析出 SQLiteDatabase")
        }
        return isReady
    }

    fun messageTimeScale(): Long {
        val cached = messageTimeScaleCache
        if (cached != 0L) return cached
        if (!isReady) return 1L
        val max = runCatching {
            executeQuery("SELECT MAX(createTime) AS t FROM message").firstOrNull()?.long("t") ?: 0L
        }.getOrDefault(0L)
        val scale = if (max > 10_000_000_000L) 1000L else 1L
        messageTimeScaleCache = scale
        WeLogger.d(TAG, "createTime 单位=" + (if (scale == 1000L) "毫秒" else "秒") + "（max=$max）")
        return scale
    }

    // ==== 供 h.Hchat 脚本子系统兜底使用 ====
    // Hchat 侧在微信 8.0.79 上 resolveDatabaseApi() 解析不到 CoreStorage / SqliteDB wrapper，
    // 导致脚本数据库、消息变更监听整体不可用。这里复用 WCX 侧已验证的解析结果作为兜底。

    /** 已初始化的 SQLiteDatabase；未就绪返回 null。 */
    @JvmStatic
    fun databaseOrNull(): SQLiteDatabase? = if (::db.isInitialized) db else null

    private const val TAG = "WeDatabaseApi"

    val coreStorage by lazy {
        classMmKernel.reflekt()
            .firstMethod {
                parameterCount = 0
                returnType = classCoreStorage.clazz
            }
            .invokeStatic()!!
    }

    val configStorage by lazy {
        coreStorage.reflekt()
            .firstMethod {
                parameterCount = 0
                returnType = classConfigStorage.clazz
            }
            .invoke()!!
    }

    fun getSelfProfileField(field: SelfProfileField, defValue: Any? = null): Any? =
        runCatching {
            configStorage.reflekt()
                .firstMethod {
                    parameters(Int::class, Any::class)
                    returnType = Any::class
                }
                .invoke(field.code, defValue)
        }.getOrElse {
            WeLogger.w(TAG, "getSelfProfileField failed for ${field.name}", it)
            defValue
        }

    private object SqlStatements {
        // 基础字段 - 联系人查询常用字段
        const val CONTACT_FIELDS = """
            r.username, r.alias, r.conRemark, r.nickname,
            r.pyInitial, r.quanPin, r.encryptUsername, i.reserved2 AS avatarUrl
        """

        // 基础字段 - 群聊查询常用字段
        const val GROUP_FIELDS =
            "r.username, r.nickname, r.pyInitial, r.quanPin, i.reserved2 AS avatarUrl"

        // 基础字段 - 公众号查询常用字段
        const val OFFICIAL_ACCOUNT_FIELDS =
            "r.username, r.alias, r.nickname, i.reserved2 AS avatarUrl"

        // 基础 JOIN 语句
        const val LEFT_JOIN_IMG_FLAG = "LEFT JOIN img_flag i ON r.username = i.username"

        // =========================================
        // 联系人查询
        // =========================================

        /** 所有账号 */
        val CONTACTS = """
            SELECT $CONTACT_FIELDS, r.type
            FROM rcontact r
            $LEFT_JOIN_IMG_FLAG
        """.trimIndent()

        /** 好友列表（排除群聊和公众号和系统账号和自己和假好友） */
        val FRIENDS = """
            SELECT $CONTACT_FIELDS, r.type
            FROM rcontact r
            $LEFT_JOIN_IMG_FLAG
            WHERE
                (
                    r.encryptUsername != '' -- 是真好友
                    OR
                    r.username = (SELECT value FROM userinfo WHERE id = 2) -- 是我自己
                )
                AND r.verifyFlag = 0
                AND (r.type & 1) != 0
                AND (r.type & 8) = 0
                AND (r.type & 32) = 0
                AND r.username NOT LIKE '%chatroom'
        """.trimIndent()

        /** 查询单个好友（包含微信内部id或自定义微信号alias匹配） */
        fun friend(wxid: String) = """
            SELECT $CONTACT_FIELDS, r.type
            FROM rcontact r
            $LEFT_JOIN_IMG_FLAG
            WHERE
                (r.username = '$wxid' OR r.alias = '$wxid')
                AND (
                    r.encryptUsername != '' -- 是真好友
                    OR
                    r.username = (SELECT value FROM userinfo WHERE id = 2) -- 是我自己
                )
                AND r.verifyFlag = 0
                AND (r.type & 1) != 0
                AND (r.type & 8) = 0
                AND (r.type & 32) = 0
                AND r.username NOT LIKE '%chatroom'
        """.trimIndent()

        /** 按消息量降序获取好友及消息数 */
        fun friendsOrderedByMessageCount(limit: Int) = """
            SELECT COUNT(*) AS msg_count, $CONTACT_FIELDS, r.type
            FROM rcontact r
            $LEFT_JOIN_IMG_FLAG
            JOIN message m ON r.username = m.talker
            WHERE
                (
                    r.encryptUsername != '' -- 是真好友
                    OR
                    r.username = (SELECT value FROM userinfo WHERE id = 2) -- 是我自己
                )
                AND r.verifyFlag = 0
                AND (r.type & 1) != 0
                AND (r.type & 8) = 0
                AND (r.type & 32) = 0
                AND r.username NOT LIKE '%chatroom'
            GROUP BY r.username
            ORDER BY msg_count DESC
            LIMIT $limit
        """.trimIndent()

        // =========================================
        // 群聊查询
        // =========================================

        /** 所有群聊 */
        val GROUPS = """
            SELECT $GROUP_FIELDS
            FROM rcontact r
            $LEFT_JOIN_IMG_FLAG
            WHERE r.username LIKE '%@chatroom'
        """.trimIndent()

        /** 单个群聊 */
        fun group(wxId: String) = """
            SELECT $GROUP_FIELDS
            FROM rcontact r
            $LEFT_JOIN_IMG_FLAG
            WHERE r.username = '$wxId'
        """.trimIndent()

        /** 获取群成员列表 */
        fun groupMembers(idsStr: String) = """
            SELECT $CONTACT_FIELDS
            FROM rcontact r
            $LEFT_JOIN_IMG_FLAG
            WHERE r.username IN ($idsStr)
        """.trimIndent()

        // =========================================
        // 公众号查询
        // =========================================

        /** 所有公众号 */
        val OFFICIAL_LIST = """
            SELECT $OFFICIAL_ACCOUNT_FIELDS
            FROM rcontact r
            $LEFT_JOIN_IMG_FLAG
            WHERE r.username LIKE 'gh_%'
        """.trimIndent()

        // =========================================
        // 消息查询
        // =========================================

        /** 分页获取消息 */
        fun messages(wxid: String, limit: Int, offset: Int) = """
            SELECT msgId, msgSvrId, talker, content, type, createTime, isSend
            FROM message
            WHERE talker='$wxid'
            ORDER BY createTime DESC
            LIMIT $limit OFFSET $offset
        """.trimIndent()

        /**
         * 按时间范围取消息。
         *
         * 与 [messages] 的差别：那条按 createTime 倒序分页，用于「最近 N 条」；
         * 取一段历史喂给模型时必须正序，否则模型读到的对话是反的。
         * [order] 只允许内部拼（调用方传的是布尔判断结果，不含用户输入），
         * [typeFilter] 同理由 **Int 集合**拼成，其余条件全部参数绑定。
         */
        fun messagesBetween(typeFilter: String, order: String = "ASC") = """
            SELECT msgId, msgSvrId, talker, content, type, createTime, isSend
            FROM message
            WHERE talker = ?
              AND createTime >= ?
              AND createTime < ?
              $typeFilter
            ORDER BY createTime $order
            LIMIT ?
        """.trimIndent()

        /**
         * 全量按关键词搜索消息正文。
         *
         * 与 [messagesBetween] 的差别：不再先取前 N 条再在内存里过滤 —— 那样只能搜到
         * 窗口边缘的一段，跨年的记录搜不全。这里让 SQLite 直接做 LIKE，时间只是可选边界。
         * `%` `_` `\` 由调用方转义，配合 `ESCAPE '\'`。
         */
        fun searchMessages(typeFilter: String) = """
            SELECT msgId, msgSvrId, talker, content, type, createTime, isSend
            FROM message
            WHERE talker = ?
              AND createTime >= ?
              AND createTime < ?
              AND content LIKE ? ESCAPE '\'
              $typeFilter
            ORDER BY createTime DESC
            LIMIT ?
        """.trimIndent()

        /** 与 [messagesBetween] 同一套过滤条件，只数总数，让调用方知道有没有被截断。 */
        fun countMessagesBetween(typeFilter: String) = """
            SELECT COUNT(*) AS cnt
            FROM message
            WHERE talker = ?
              AND createTime >= ?
              AND createTime < ?
              $typeFilter
        """.trimIndent()

        /**
         * 获取指定会话中特定发送者的消息
         * 支持群聊（通过 content 匹配对方，或通过 isSend 匹配自己）与单聊
         */
        fun messagesFromSender(convId: String, senderId: String, limit: Int, offset: Int) = """
            SELECT msgId, talker, content, type, createTime, isSend
            FROM message
            WHERE talker = '$convId'
              AND (
                  -- 情况 A: 群聊中其他人的消息，内容开头为 'senderId:'
                  (isSend = 0 AND (content LIKE '$senderId:%' OR content LIKE '$senderId:%'))
                  OR
                  -- 情况 B: 自己发送的消息，且请求的 senderId 就是我自己
                  (isSend = 1 AND '$senderId' = (SELECT value FROM userinfo WHERE id = 2))
                  OR
                  -- 情况 C: 单聊，且会话 ID 本身就是发送者 ID
                  ('$convId' = '$senderId')
              )
            ORDER BY createTime DESC
            LIMIT $limit OFFSET $offset
        """.trimIndent()

        /**
         * 获取指定会话中特定发送者的消息
         * 支持群聊（通过 content 匹配对方，或通过 isSend 匹配自己）与单聊
         */
        fun messagesFromSender(convId: String, senderId: String) = """
            SELECT msgId, msgSvrId, talker, content, type, createTime, isSend
            FROM message
            WHERE talker = '$convId'
              AND (
                  -- 情况 A: 群聊中其他人的消息，内容开头为 'senderId:'
                  (isSend = 0 AND (content LIKE '$senderId:%' OR content LIKE '$senderId:%'))
                  OR
                  -- 情况 B: 自己发送的消息，且请求的 senderId 就是我自己
                  (isSend = 1 AND '$senderId' = (SELECT value FROM userinfo WHERE id = 2))
                  OR
                  -- 情况 C: 单聊，且会话 ID 本身就是发送者 ID
                  ('$convId' = '$senderId')
              )
            ORDER BY createTime DESC
        """.trimIndent()

        // =========================================
        // 头像查询
        // =========================================

        /** 每个会话最近一条消息的时间 */
        val LAST_MESSAGE_TIMES = """
            SELECT talker, MAX(createTime) AS lastTime
            FROM message
            GROUP BY talker
        """.trimIndent()

        /** 获取头像 URL */
        fun avatar(wxid: String) = """
            SELECT i.reserved2 AS avatarUrl
            FROM img_flag i
            WHERE i.username = '$wxid'
        """.trimIndent()

        /** 获取群聊成员列表字符串 */
        const val GROUP_MEMBERS = "SELECT memberlist FROM chatroom WHERE chatroomname = '%s'"
    }

    override fun onEnable() {
        // 8.0.80 实测：MMKernel(zp0.j1) 里同时存在多个「public static 无参」方法——
        //   j() = getInstance()，返回 MMKernel 自身
        //   x() = getCoreStorage()，返回 CoreStorage(zp0.b0)
        // DexKit 的 method matcher 在这两个候选上无法稳定区分（线上日志出现
        // "CoreStorage(com.tencent.mm.ui.LauncherUI)" 说明 hook 落到了错误方法上），
        // 因此改为直接用反射按「无参 + 静态 + 返回 CoreStorage」定位目标方法再 hook。
        runCatching {
            val kernelCls = classMmKernel.clazz
            val coreStorageCls = classCoreStorage.clazz
            WeLogger.d(
                TAG,
                "锚点: MMKernel=${kernelCls.name}, CoreStorage=${coreStorageCls.name}"
            )
            val target = kernelCls.reflekt().firstMethodOrNull {
                parameterCount = 0
                modifiers?.let { Modifier.isStatic(it) } == true
                returnType = coreStorageCls
            }
            if (target == null) {
                WeLogger.w(TAG, "未找到返回 CoreStorage 的无参静态方法，数据库服务不可用")
                return@runCatching
            }
            WeLogger.d(TAG, "hook getCoreStorage -> ${kernelCls.name}#${target.name}")
            target.hookAfter {
                val storageObj = result ?: return@hookAfter
                // 同一 storage 实例直接返回（原逻辑是 db 初始化后永久跳过）。
                // 切号会换来新的 storage，此时才需要重新解析 —— 否则连 db 都还指着旧账号。
                if (lastStorageRef?.get() === storageObj) return@hookAfter
                initializeDatabase(storageObj)
            }
        }.onFailure { WeLogger.e(TAG, "hook getCoreStorage 失败", it) }

        // 兜底：DexKit 的 methodGetStorage（部分版本仍可用），失败不影响上面的反射路径。
        runCatching { methodGetStorage.method }.getOrNull()?.hookAfter {
            val storageObj = result ?: return@hookAfter
            if (lastStorageRef?.get() === storageObj) return@hookAfter
            initializeDatabase(storageObj)
        }

        if (Preferences.verboseLog) {
            SQLiteDatabase::class.reflekt().firstMethod {
                name = "openDatabase"
                parameters(BString, ByteArray::class, SQLiteCipherSpec::class, SQLiteDatabase.CursorFactory::class, int, DatabaseErrorHandler::class, int)
            }.hookBefore {
                val cipherSpec = args[2] as SQLiteCipherSpec?
                WeLogger.d(
                    TAG,
                    "openDatabase() called with: name=${args[0] as String?}, password=${String(args[1] as? ByteArray? ?: return@hookBefore)}, cipherSpec=${
                        cipherSpec.run {
                            "${this?.hmacAlgorithm},${this?.hmacEnabled},${this?.kdfAlgorithm},${this?.kdfIteration},${this?.pageSize}"
                        }
                    }"
                )
            }
        }
    }

    @Synchronized
    private fun initializeDatabase(storageObj: Any) {
        // 1) 已知 wrapper 类型精确匹配（8.0.79 及更早）。8.0.80 起
        //    "MicroMsg.SqliteDB" / "sql is null " 两个特征串已消失，classSqliteDbWrapper 解析失败，
        //    这里必须全程 runCatching，否则 NoSuchElementException 会让整个「数据库服务」装挂。
        val byType = runCatching {
            val wrapperCls = classSqliteDbWrapper.clazz
            storageObj.reflekt()
                .firstFieldOrNull {
                    type {
                        it == wrapperCls || it == wrapperCls.interfaces.getOrNull(0)
                    }
                }?.get()
        }.getOrNull()

        // 2) 结构兜底（不依赖任何字符串锚点）：
        //    CoreStorage 里"某个字段的类型，拥有 无参 且 返回 SQLiteDatabase 的方法"即为 wrapper。
        val byShape = if (byType != null) null else runCatching {
            storageObj.reflekt()
                .firstFieldOrNull {
                    type { cls ->
                        cls.declaredMethods.any { m ->
                            m.parameterCount == 0 && (
                                    m.returnType.name == "com.tencent.wcdb.database.SQLiteDatabase" ||
                                            m.returnType.name == "android.database.sqlite.SQLiteDatabase"
                                    )
                        }
                    }
                }?.get()
        }.getOrNull()

        val wrapperObj = byType ?: byShape
        if (wrapperObj == null) {
            WeLogger.w(
                TAG,
                "initializeDatabase: CoreStorage(${storageObj.javaClass.name}) 中未找到 SqliteDB wrapper，本次跳过"
            )
            return
        }

        val database = runCatching {
            wrapperObj.reflekt()
                .firstMethodOrNull {
                    parameterCount = 0
                    returnType = "com.tencent.wcdb.database.SQLiteDatabase"
                }?.invoke() as? SQLiteDatabase
        }.getOrNull()

        if (database == null) {
            WeLogger.w(TAG, "initializeDatabase: wrapper(${wrapperObj.javaClass.name}) 未取到 SQLiteDatabase")
            return
        }

        // 走到这里且 db 已就绪 = 换了一个 storage 实例（同实例被上面的守卫挡住了）。
        // 微信切号会重建 storage，因此这就是「切到另一个账号」；首次初始化不通知。
        val alreadyReady = ::db.isInitialized
        val previousPath = if (alreadyReady) dbPathOf(db) else null
        db = database
        lastStorageRef = java.lang.ref.WeakReference(storageObj)
        val currentPath = dbPathOf(database)
        WeLogger.i(TAG, "数据库服务就绪：${storageObj.javaClass.name} -> ${wrapperObj.javaClass.name}")
        if (alreadyReady) {
            WeLogger.i(TAG, "账号已切换：${previousPath ?: "?"} -> ${currentPath ?: "?"}")
            // 接收方会重新对账并刷新会话列表（reloadConversations 自己会切主线程）。
            Handler(Looper.getMainLooper()).post { notifyDatabaseSwitched() }
        }
    }

    /** wcdb 的 SQLiteDatabase 没有暴露 `path` 属性；`getPath()` 也只能反射取（部分版本隐藏）。 */
    private fun dbPathOf(database: SQLiteDatabase): String? =
        runCatching { database.javaClass.getMethod("getPath").invoke(database) as? String }.getOrNull()

    @Suppress("NOTHING_TO_INLINE")
    inline fun rawQuery(sql: String, args: Array<Any>? = null): Cursor = db.rawQuery(sql, args)

    @Suppress("NOTHING_TO_INLINE")
    inline fun delete(table: String, conditions: String, args: Array<String>? = null): Int = db.delete(table, conditions, args)

    fun executeQuery(statement: String, args: Array<Any>? = null): List<Map<String, Any?>> {
        // 读之前先确保 db 就绪：hook 可能因为「微信先取过 storage」而错过（见 ensureDatabase）。
        if (!isReady && !ensureDatabase()) {
            WeLogger.w(TAG, "sql skipped: 数据库未就绪")
            return emptyList()
        }
        val result = mutableListOf<Map<String, Any?>>()

        var cursor: Cursor? = null
        try {
            cursor = db.rawQuery(statement, args)
            if (cursor != null && cursor.moveToFirst()) {
                val columnNames = cursor.columnNames
                do {
                    val row = HashMap<String, Any?>()
                    for (i in columnNames.indices) {
                        val type = cursor.getType(i)
                        row[columnNames[i]] = when (type) {
                            Cursor.FIELD_TYPE_NULL -> ""
                            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
                            Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(i)
                            else -> cursor.getString(i)
                        }
                    }
                    result.add(row)
                } while (cursor.moveToNext())
            }
        } catch (e: Exception) {
            WeLogger.e(TAG, "sql query failed", e)
        } finally {
            cursor?.close()
        }
        return result
    }

    @Suppress("NOTHING_TO_INLINE")
    inline fun execStatement(statement: String, args: Array<Any>? = null) = db.execSQL(statement, args)

    /**
     * 获取【全部联系人】
     * 返回所有账号
     */
    fun getContacts(): List<WeContact> {
        return mapToContacts(executeQuery(SqlStatements.CONTACTS))
    }

    /**
     * 获取【好友】
     */
    fun getFriends(): List<WeContact> {
        return mapToContacts(executeQuery(SqlStatements.FRIENDS))
    }

    /**
     * 获取单条【好友】
     * @param wxId 微信原始 ID (wxid_xxx) 或 用户自定义微信号 (alias)
     * @return 匹配到的 WeContact 对象，若未找到或非好友则返回 null
     */
    fun getFriend(wxId: String): WeContact? {
        if (wxId.isEmpty()) return null
        try {
            val escapedWxid = wxId.replace("'", "''")
            val result = executeQuery(SqlStatements.friend(escapedWxid))

            if (result.isEmpty()) return null

            return mapToContacts(result).firstOrNull()
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to get friend details; wxid=$wxId", e)
            return null
        }
    }

    /**
     * 获取按聊天消息总数降序排列的好友列表
     * * @param limit 返回的最大结果数量限制
     * @return 包含 WeContact 到 消息总数 映射的 Map（保持降序排列）
     */
    fun getFriendsOrderedByMessageCount(limit: Int): Map<WeContact, Int> {
        if (limit <= 0) return emptyMap()
        try {
            val result = executeQuery(SqlStatements.friendsOrderedByMessageCount(limit))
            if (result.isEmpty()) return emptyMap()

            val contacts = mapToContacts(result)

            // 使用 mapIndexed 组合并转换为 Map，Kotlin 的 toMap() 会默认保留原本的降序遍历顺序
            return result.mapIndexed { index, row ->
                contacts[index] to row.int("msg_count")
            }.toMap()
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to get friends ordered by message count; limit=$limit", e)
            return emptyMap()
        }
    }

    /**
     * 获取单个群聊
     * @param wxId 群聊 wxId（xxx@chatroom）
     * @return WeGroup 对象，若未找到则返回 null
     */
    fun getGroup(wxId: String): WeGroup? {
        if (wxId.isEmpty() || !wxId.isGroupChatWxId) return null
        try {
            val escapedWxid = wxId.replace("'", "''")
            val result = executeQuery(SqlStatements.group(escapedWxid))

            if (result.isEmpty()) return null

            val row = result[0]
            return WeGroup(
                wxId = row.str("username"),
                nickname = row.str("nickname"),
                nicknameShortPinyin = row.str("pyInitial"),
                nicknamePinyin = row.str("quanPin"),
                avatarUrl = row.str("avatarUrl")
            )
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to get group; wxid=$wxId", e)
            return null
        }
    }

    fun getDisplayName(convId: String): String {
        if (convId.isEmpty()) error("convId is empty")
        try {
            val isGroup = convId.isGroupChatWxId
            // 参数化而不是拼字符串：wxid 里的引号不必再手工转义，也不会随字段变化出错。
            val sql = if (isGroup) {
                "SELECT r.nickname FROM rcontact r WHERE r.username = ?"
            } else {
                "SELECT r.conRemark, r.nickname FROM rcontact r WHERE r.username = ?"
            }
            val result = executeQuery(sql, arrayOf<Any>(convId))
            if (result.isEmpty()) return convId

            val row = result[0]
            return if (isGroup) {
                val nickname = row.str("nickname")
                nickname.ifEmpty { convId }
            } else {
                val conRemark = row.str("conRemark")
                val nickname = row.str("nickname")
                conRemark.ifEmpty { nickname.ifEmpty { convId } }
            }
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to get display name; wxid=$convId", e)
            return convId
        }
    }

    /**
     * 获取【群聊】
     */
    fun getGroups(): List<WeGroup> {
        return executeQuery(SqlStatements.GROUPS).map { row ->
            WeGroup(
                wxId = row.str("username"),
                nickname = row.str("nickname"),
                nicknameShortPinyin = row.str("pyInitial"),
                nicknamePinyin = row.str("quanPin"),
                avatarUrl = row.str("avatarUrl")
            )
        }
    }

    /**
     * 获取指定群聊的成员列表
     * @param groupId 群聊ID
     */
    fun getGroupMembers(groupId: String): List<WeContact> {
        if (!groupId.isGroupChatWxId) return emptyList()

        val roomSql = SqlStatements.GROUP_MEMBERS.format(groupId)
        val roomResult = executeQuery(roomSql)

        if (roomResult.isEmpty()) {
            WeLogger.w(TAG, "failed to get group info for $groupId")
            return emptyList()
        }

        val memberListStr = roomResult[0].str("memberlist")
        if (memberListStr.isEmpty()) return emptyList()

        val members = memberListStr.split(";").filter { it.isNotEmpty() }
        if (members.isEmpty()) return emptyList()

        val idsStr = members.joinToString(",") { "'$it'" }

        return mapToContacts(executeQuery(SqlStatements.groupMembers(idsStr)))
    }

    /**
     * 获取群成员在群中的群昵称（群备注）
     * 数据来自 chatroom.roomdata protobuf，没有设置群昵称时返回空字符串
     * @param groupId 群聊 wxId（xxx@chatroom）
     * @param memberId 成员 wxId
     * @return 群昵称，未设置时返回空字符串
     */
    fun getGroupMemberDisplayName(groupId: String, memberId: String): String {
        if (memberId.isEmpty()) return ""
        return getGroupMemberDisplayNames(groupId)[memberId] ?: ""
    }

    /**
     * 一次取出整群的群昵称表（wxid → 群名片）。
     *
     * 逐条调 [getGroupMemberDisplayName] 会把同一份 roomdata decode N 次；
     * 群总结这种一次要渲染几千条消息的场景必须走这里。
     */
    fun getGroupMemberDisplayNames(groupId: String): Map<String, String> {
        if (!groupId.isGroupChatWxId) return emptyMap()
        return try {
            val cursor = db.rawQuery(
                "SELECT roomdata FROM chatroom WHERE chatroomname = ?",
                arrayOf(groupId)
            )
            cursor.use {
                if (!it.moveToFirst()) return emptyMap()
                val blob = it.getBlob(0) ?: return emptyMap()
                val data = ProtoBuf.decodeFromByteArray<ChatRoomDataProto>(blob)
                data.members.asSequence()
                    .filter { member -> member.displayName.isNotBlank() }
                    .associate { member -> member.wxId to member.displayName }
            }
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to get group member display names; groupId=$groupId", e)
            emptyMap()
        }
    }

    /**
     * 获取邀请指定群成员进群的邀请者 wxId
     * 数据来自 chatroom.roomdata protobuf，群主或无邀请者信息时返回空字符串
     * @param groupId 群聊 wxId（xxx@chatroom）
     * @param memberId 成员 wxId
     * @return 邀请者 wxId，未记录时返回空字符串
     */
    fun getGroupMemberInviter(groupId: String, memberId: String): String {
        if (!groupId.isGroupChatWxId || memberId.isEmpty()) return ""
        try {
            val cursor = db.rawQuery(
                "SELECT roomdata FROM chatroom WHERE chatroomname = ?",
                arrayOf(groupId)
            )
            cursor.use { cursor ->
                if (cursor != null && cursor.moveToFirst()) {
                    val blob = cursor.getBlob(0) ?: return ""
                    val data = ProtoBuf.decodeFromByteArray<ChatRoomDataProto>(blob)
                    return data.members.firstOrNull { it.wxId == memberId }?.inviterWxId ?: ""
                }
            }
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to get group member inviter; groupId=$groupId, memberId=$memberId", e)
        }
        return ""
    }

    /**
     * 获取【公众号】
     */
    fun getOfficialAccounts(): List<WeOfficialAccount> {
        return executeQuery(SqlStatements.OFFICIAL_LIST).map { row ->
            WeOfficialAccount(
                wxId = row.str("username"),
                nickname = row.str("nickname"),
                avatarUrl = row.str("avatarUrl")
            )
        }
    }

    /**
     * 获取【消息】
     */
    fun getMessages(convId: String, pageIndex: Int = 1, pageSize: Int = 20): List<WeMessage> {
        if (convId.isEmpty()) return emptyList()
        val offset = (pageIndex - 1) * pageSize
        return executeQuery(SqlStatements.messages(convId, pageSize, offset)).map { row ->
            WeMessage(
                msgId = row.long("msgId"),
                msgSvrId = row.long("msgSvrId"),
                talker = row.str("talker"),
                content = row.str("content"),
                typeCode = row.int("type"),
                createTime = row.long("createTime"),
                isSend = row.int("isSend")
            )
        }
    }

    /**
     * 取一段时间内的消息，升序返回。
     *
     * 用于「聊天档案初始化」与「当天记录」这类区间查询：按自然年切分时，每次只取一年，
     * 不能沿用 [getMessages] 的倒序分页 —— 倒序喂给模型等于让它倒着读对话。
     *
     * @param fromSec 起始时间（秒，含）
     * @param toSec   截止时间（秒，不含）
     * @param limit   最多返回条数；调用方要自己用 [countMessagesBetween] 判断是否被截断
     * @param typeCodes 只取这些消息类型；null / 空集合表示不过滤（值为 Int，不会引入注入）
     */
    /**
     * 按时间范围取消息，默认升序。
     *
     * [newestFirst] 用于「窗口内条数超过上限、需要从最新一端截取」的场景：升序 + LIMIT
     * 只会拿到窗口最早的一段（例如整年记录只取到一月），这在实际使用中是个静默的错误，
     * 所以专门留了这个开关，由调用方明确选择从哪一端截。
     */
    fun getMessagesBetween(
        convId: String,
        fromSec: Long,
        toSec: Long,
        limit: Int = 800,
        typeCodes: Set<Int>? = null,
        newestFirst: Boolean = false,
    ): List<WeMessage> {
        if (convId.isEmpty() || toSec <= fromSec) return emptyList()
        // fromSec/toSec 一律按「秒」传，这里按库里实际单位换算。
        val scale = messageTimeScale()
        val args = arrayOf<Any>(convId, fromSec * scale, toSec * scale, limit.coerceIn(1, 200_000))
        val order = if (newestFirst) "DESC" else "ASC"
        return executeQuery(SqlStatements.messagesBetween(typeFilterOf(typeCodes), order), args)
            .map { row ->
                WeMessage(
                    msgId = row.long("msgId"),
                    msgSvrId = row.long("msgSvrId"),
                    talker = row.str("talker"),
                    content = row.str("content"),
                    typeCode = row.int("type"),
                    createTime = row.long("createTime"),
                    isSend = row.int("isSend")
                )
            }
    }

    /**
     * 全量关键词搜索消息正文，按时间倒序返回。
     *
     * 不能复用 [getMessagesBetween] + 内存过滤：那条路径先 LIMIT 再 filter，
     * 只能搜到窗口边缘的一段，跨年的记录搜不全。
     * `%` `_` `\` 会被转义，避免搜「100%」时变成通配符。
     */
    fun searchMessages(
        convId: String,
        keyword: String,
        fromSec: Long,
        toSec: Long,
        limit: Int = 200,
        typeCodes: Set<Int>? = null,
    ): List<WeMessage> {
        val trimmed = keyword.trim()
        if (convId.isEmpty() || trimmed.isEmpty() || toSec <= fromSec) return emptyList()
        val scale = messageTimeScale()
        val pattern = "%" + trimmed
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_") + "%"
        val args = arrayOf<Any>(
            convId,
            fromSec * scale,
            toSec * scale,
            pattern,
            limit.coerceIn(1, 2_000),
        )
        return executeQuery(SqlStatements.searchMessages(typeFilterOf(typeCodes)), args)
            .map { row ->
                WeMessage(
                    msgId = row.long("msgId"),
                    msgSvrId = row.long("msgSvrId"),
                    talker = row.str("talker"),
                    content = row.str("content"),
                    typeCode = row.int("type"),
                    createTime = row.long("createTime"),
                    isSend = row.int("isSend")
                )
            }
    }

    /** [getMessagesBetween] 的同条件计数，用于判断「返回条数 < 总数」即被截断。 */
    fun countMessagesBetween(
        convId: String,
        fromSec: Long,
        toSec: Long,
        typeCodes: Set<Int>? = null,
    ): Int {
        if (convId.isEmpty() || toSec <= fromSec) return 0
        val scale = messageTimeScale()
        val args = arrayOf<Any>(convId, fromSec * scale, toSec * scale)
        val row = executeQuery(
            SqlStatements.countMessagesBetween(typeFilterOf(typeCodes)), args
        ).firstOrNull() ?: return 0
        return row.int("cnt")
    }

    private fun typeFilterOf(typeCodes: Set<Int>?): String =
        if (typeCodes.isNullOrEmpty()) "" else "AND type IN (" + typeCodes.joinToString(",") + ")"

    /**
     * 获取指定会话中特定发送者的【消息】
     * @param convId 会话 ID（单聊为对方 wxid，群聊为 xxx@chatroom）
     * @param senderId 发送者 ID（wxid）
     */
    fun getMessagesFromSender(
        convId: String,
        senderId: String,
    ): List<WeMessage> {
        if (convId.isEmpty() || senderId.isEmpty()) return emptyList()

        try {
            // 防止 SQL 注入转义
            val escapedConvId = convId.replace("'", "''")
            val escapedSenderId = senderId.replace("'", "''")

            val sql = SqlStatements.messagesFromSender(escapedConvId, escapedSenderId)
            return executeQuery(sql).map { row ->
                WeMessage(
                    msgId = row.long("msgId"),
                    msgSvrId = row.long("msgSvrId"),
                    talker = row.str("talker"),
                    content = row.str("content"),
                    typeCode = row.int("type"),
                    createTime = row.long("createTime"),
                    isSend = row.int("isSend")
                )
            }
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to get messages from sender; convId=$convId, senderId=$senderId", e)
            return emptyList()
        }
    }

    /**
     * 获取每个会话最近一条消息的时间
     * @return 会话 wxId 到最近消息时间（毫秒时间戳）的映射
     */
    fun getLastMessageTimes(): Map<String, Long> {
        return try {
            executeQuery(SqlStatements.LAST_MESSAGE_TIMES).associate { row ->
                row.str("talker") to row.long("lastTime")
            }
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to get last message times", e)
            emptyMap()
        }
    }

    /**
     * 获取头像
     */
    fun getAvatarUrl(wxid: String): String {
        if (wxid.isEmpty()) return ""
        val result = executeQuery(SqlStatements.avatar(wxid))
        return if (result.isNotEmpty()) {
            result[0]["avatarUrl"] as? String ?: ""
        } else {
            ""
        }
    }

    private fun mapToContacts(data: List<Map<String, Any?>>): List<WeContact> {
        return data.map { row ->
            WeContact(
                wxId = row.str("username"),
                nickname = row.str("nickname"),
                customWxId = row.str("alias"),
                remarkName = row.str("conRemark"),
                initialNickname = row.str("pyInitial"),
                nicknamePinyin = row.str("quanPin"),
                avatarUrl = row.str("avatarUrl"),
                encryptedUsername = row.str("encryptUsername"),
                type = row.int("type")
            )
        }
    }

    private fun Map<String, Any?>.str(key: String): String = this[key]?.toString() ?: ""

    private fun Map<String, Any?>.long(key: String): Long {
        return when (val v = this[key]) {
            is Long -> v
            is Int -> v.toLong()
            else -> 0L
        }
    }

    private fun Map<String, Any?>.int(key: String): Int {
        return when (val v = this[key]) {
            is Int -> v
            is Long -> v.toInt()
            else -> 0
        }
    }

    /**
     * 读取群聊成员同步状态（chatroom 表），供 AutoDndAfterJoinGroup 使用。
     * WeKit 同签名；本模块用 executeQuery 实现。
     */
    fun getChatroomSyncState(roomId: String): com.Johnny.wcx.features.api.core.models.ChatroomSyncStateReadResult {
        if (roomId.isEmpty()) return com.Johnny.wcx.features.api.core.models.ChatroomSyncStateReadResult.Unavailable
        return try {
            val rows = executeQuery(
                "SELECT memberlist, chatroomVersion FROM chatroom WHERE chatroomname = ?",
                arrayOf(roomId as Any),
            )
            if (rows.isEmpty()) com.Johnny.wcx.features.api.core.models.ChatroomSyncStateReadResult.MissingRow
            else com.Johnny.wcx.features.api.core.models.ChatroomSyncStateReadResult.Available(
                com.Johnny.wcx.features.api.core.models.WeChatroomSyncState(
                    roomId = roomId,
                    memberIds = rows[0].str("memberlist").split(";").filter { it.isNotEmpty() }.toSet(),
                    // executeQuery 会把 INTEGER 一律读成 Long，不能直接 `as? Int`
                    // （那样这里永远是 null，去重 key 也就永远退化成只有 roomId）。
                    memberVersion = (rows[0]["chatroomVersion"] as? Number)?.toInt(),
                ),
            )
        } catch (e: Exception) {
            WeLogger.w(TAG, "getChatroomSyncState failed; roomId=$roomId", e)
            com.Johnny.wcx.features.api.core.models.ChatroomSyncStateReadResult.Unavailable
        }
    }

    // ── WXPRO 合入兼容层 ─────────────────────────────────────────────────────────
    // ConversationAggregation(WXPRO 版) 调用了本工程此前不存在的两个 API, 这里补最小实现:
    // - transaction: WXPRO 用 SQLite 事务包裹批量写; 本工程 execStatement 单条语句本身原子,
    //   批量写顺序执行即可(与旧版行为一致), 不引入真实事务。
    // - addDatabaseSwitchListener/removeDatabaseSwitchListener: 切号后 db 重建时通知，
    //   让归拢文件夹把行重新对账到新库（initializeDatabase 检测到库路径变化时触发）。
    @Volatile
    private var databaseSwitchListener: (() -> Unit)? = null

    /** 账号切换后通知注册方；异常只记日志，不影响数据库服务自身。 */
    private fun notifyDatabaseSwitched() {
        val listener = databaseSwitchListener ?: return
        runCatching { listener() }
            .onFailure { WeLogger.e(TAG, "database switch listener failed", it) }
    }

    fun addDatabaseSwitchListener(listener: () -> Unit) {
        databaseSwitchListener = listener
    }

    fun removeDatabaseSwitchListener(listener: () -> Unit) {
        if (databaseSwitchListener == listener) databaseSwitchListener = null
    }

    /**
     * 批量执行（**不开启事务**）。
     *
     * WXPRO 合入时需要一个包裹批量写的入口；本工程单条 `execStatement` 本身原子，
     * 顺序执行即可，与旧版行为一致。需要真正事务语义时不要用这里。
     */
    inline fun <T> runBatch(block: () -> T): T = block()

    /** @deprecated 名字暗示事务，实际不开启；改用 [runBatch]。 */
    @Deprecated("不会开启事务，请改用 runBatch", ReplaceWith("runBatch(block)"))
    inline fun <T> transaction(block: () -> T): T = block()
}
