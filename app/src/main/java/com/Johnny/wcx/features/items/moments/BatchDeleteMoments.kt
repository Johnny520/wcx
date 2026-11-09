package com.Johnny.wcx.features.items.moments

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.DexClassDelegate
import com.Johnny.wcx.dexkit.dsl.dexClass
import com.Johnny.wcx.dexkit.dsl.dexMethod
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.matchers.ClassMatcher
import com.Johnny.wcx.features.api.core.WeApi
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.net.WeNetSceneApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.Johnny.wcx.ui.style.SoftDivider
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.style.DangerBanner
import com.Johnny.wcx.ui.style.SectionLabel
import com.Johnny.wcx.ui.style.SoftCard
import com.Johnny.wcx.ui.style.StatusPill
import com.Johnny.wcx.ui.style.StyleRow
import com.Johnny.wcx.ui.style.StyleTitle
import com.Johnny.wcx.ui.style.WxPalette
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.reflection.ClassLoaders
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.hookAfterDirectly
import org.json.JSONObject
import java.lang.reflect.Constructor
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow

/**
 * 批量删除自己的朋友圈。
 *
 * ## 原理
 * 微信删除朋友圈走网络接口 `/cgi-bin/micromsg-bin/mmsnsobjectop`，
 * 对应客户端类 `com.tencent.mm.plugin.sns.model.NetSceneSnsObjectOp`（8.0.80 混淆名 `r2`），
 * 构造器 `(long snsId, int opType)`。微信 UI 只提供逐条删除，但协议层面可以连续发。
 *
 * 本功能做的事：
 * 1. 从本地 `SnsInfo` 表枚举自己的朋友圈（按年份 / 数量筛选）
 * 2. 逐条构造 NetScene 并投递到微信网络队列
 * 3. 按可配置的间隔与批量暂停节流，降低风控概率
 *
 * ## opType 自动学习
 * 微信 8.0.80 中没有可查的 opType 常量类，因此本功能采用**观察学习**：
 * hook `NetSceneSnsObjectOp` 的 `(J,I)` 构造器，把你手动删除一条朋友圈时微信自己传入的
 * opType 记录下来，批量删除时复用同一个值。首次使用前需要手动删一条做校准。
 *
 * ## 风险
 * 高频删除朋友圈会触发微信风控：删除被拒、朋友圈发布/查看受限。
 * 默认节流为 5 秒/条、每 20 条暂停 60 秒、每日上限 200 条，建议不要放大。
 */
@SuppressLint("DiscouragedApi")
@Feature(
    name = "批量删除朋友圈",
    categories = ["朋友圈"],
    description = "按年份或数量批量删除自己发布的朋友圈\n自动学习删除参数 · 节流执行 · 支持中止"
)
object BatchDeleteMoments : ClickableFeature(), IResolveDex {

    /**
     * 纯操作型功能：点进去直接扫描/删除，不需要外层开关（用户反馈）。
     * noSwitchWidget=true 后分类页/功能页只显示条目与齿轮，不显示开关。
     */
    override val noSwitchWidget = true

    private const val TAG = "BatchDeleteMoments"

    /**
     * 最近一次枚举的解析统计，供「模块自检 → 功能活体」直接引用。
     * 只读快照，不参与任何业务逻辑。
     */
    @Volatile
    var lastParseStats: String? = null
        private set

    /**
     * 最近一次枚举到的第一条可用媒体地址，供「模块自检 → 网络与 CDN」做 Referer 实测。
     * 用真实地址而不是写死的测试图，结论才有意义。
     */
    @Volatile
    var lastProbeUrl: String? = null
        private set

    // ── 配置键 ────────────────────────────────────────────────────────────────
    private const val PREF_OP_TYPE = "sns_batch_delete_op_type"
    private const val PREF_INTERVAL = "sns_batch_delete_interval"
    private const val PREF_BATCH_SIZE = "sns_batch_delete_batch_size"
    private const val PREF_BATCH_PAUSE = "sns_batch_delete_batch_pause"
    private const val PREF_DAILY_LIMIT = "sns_batch_delete_daily_limit"
    private const val PREF_DAILY_DATE = "sns_batch_delete_daily_date"
    private const val PREF_DAILY_COUNT = "sns_batch_delete_daily_count"

    // ── DexKit 定位 ───────────────────────────────────────────────────────────
    /** NetSceneSnsObjectOp：cgi 特征串 + 包过滤，单串避免「同串不同方法」不命中。 */
    private val classSnsObjectOp by dexClass {
        searchPackages("com.tencent.mm.plugin.sns.model")
        matcher {
            usingEqStrings("/cgi-bin/micromsg-bin/mmsnsobjectop")
        }
    }

    /** onGYNetEnd：用于读取每条删除请求的服务端返回码。 */
    private val methodOpOnGYNetEnd by dexMethod {
        matcher {
            declaredClass(classSnsObjectOp.clazz)
            name = "onGYNetEnd"
        }
    }

    /**
     * SnsCore 的类定位（DexKit 多候选二次筛选）。
     *
     * 微信 8.0.80 起把 SnsCore 混淆成短名（实测 `p4`），原类名
     * `com.tencent.mm.plugin.sns.model.SnsCore` 已不存在；同时 TAG 串
     * 「MicroMsg.SnsCore」被它的一批内部类/匿名类同时引用，直接
     * `usingEqStrings` 会命中多个类并抛「Multiple classes found」。
     *
     * 这里放宽匹配后自己筛：排除内部类（类名含 `$`），并要求该类有
     * 「静态 0 参、返回 com.tencent.mm.plugin.sns.storage.* 里那个带
     * `delete(String,String,String[])` 的类」的方法——即 getSnsInfoStorage()。
     */
    private val delegateSnsCore = DexClassDelegate("BatchDeleteMoments:snsCore") { d, dexKit ->
        val candidates = runCatching {
            dexKit.findClass(
                FindClass.create()
                    .searchPackages("com.tencent.mm.plugin.sns.model")
                    .matcher(ClassMatcher.create().usingEqStrings("MicroMsg.SnsCore"))
            )
        }.getOrElse {
            WeLogger.w(TAG, "DexKit 定位 SnsCore 失败：${it.message}")
            emptyList()
        }
        val picked = candidates.firstOrNull { cd -> isSnsCoreLike(cd.name) }
        if (picked == null) {
            WeLogger.w(TAG, "DexKit 候选里没有 SnsCore：${candidates.joinToString { it.name }}")
            false
        } else {
            d.setDescriptor(picked.name)
            true
        }
    }

    init {
        registerDexDelegate(delegateSnsCore)
    }

    /**
     * SnsInfo DAO 里「按 snsId 取 SnsInfo」的方法（8.0.80 实测是 f2.M2(String)）。
     *
     * 朋友圈正文（ContentDesc）不在 SnsInfo 表的 content 列里——content 只存
     * id/username/contentStyle 这类基础字段，正文走 attrBuf 加密存放，必须由
     * SnsInfo 自己解密后经 getTimeLine() 取出。
     */
    private val methodSnsInfoById by dexMethod(allowMultiple = true, allowFailure = true) {
        searchPackages("com.tencent.mm.plugin.sns.storage")
        matcher {
            paramTypes("java.lang.String")
            returnType("com.tencent.mm.plugin.sns.storage.SnsInfo")
        }
    }

    /**
     * 按 snsId(long) 直接查 SnsInfo 的方法。
     *
     * 8.0.80 = com.tencent.mm.plugin.sns.storage.f2.V1，内部 SQL 就是
     * `select *,rowid from SnsInfo where SnsInfo.snsId=<id> limit 1`，
     * 比「把 snsId 转成字符串再查」稳得多（后者极易挑到按 localId 查的那个方法）。
     */
    private val methodSnsInfoBySnsId by dexMethod(allowMultiple = true, allowFailure = true) {
        searchPackages("com.tencent.mm.plugin.sns.storage")
        matcher {
            paramTypes("long")
            returnType("com.tencent.mm.plugin.sns.storage.SnsInfo")
        }
    }

    @Volatile
    private var cachedSnsDao: Any? = null

    /** 用 SnsInfoStorage 构造 DAO 实例（DAO 构造器只接受 storage）。 */
    private fun resolveSnsDao(storage: Any): Any? {
        cachedSnsDao?.let { return it }
        // 优先用委托自己解析出来的 Method 反查声明类（这条路径一定通）；
        // loadClass 只在两者都拿不到时兜底，且要兼容 descriptor / 点分两种写法。
        val cls = runCatching { methodSnsInfoById.method.declaringClass }.getOrNull()
            ?: runCatching { methodSnsInfoBySnsId.method.declaringClass }.getOrNull()
            ?: runCatching {
                val raw = methodSnsInfoById.candidates.firstOrNull()?.className
                    ?: methodSnsInfoBySnsId.candidates.firstOrNull()?.className
                    ?: return@runCatching null
                val dotted = if (raw.startsWith("L") && raw.endsWith(";")) {
                    raw.substring(1, raw.length - 1).replace('/', '.')
                } else raw.replace('/', '.')
                ClassLoaders.HOST.loadClass(dotted)
            }.getOrNull()
        if (cls == null) {
            WeLogger.w(TAG, "SnsInfo DAO 类定位失败（method 与 candidates 均为空）")
            return null
        }

        // 构造器参数类型不一定和 storage 的运行时类型完全相等（storage 可能是模型的包装对象），
        // 所以按 isAssignableFrom 挑，而不是要求精确类型。
        val ctor = runCatching {
            cls.declaredConstructors.firstOrNull {
                it.parameterCount == 1 && it.parameterTypes[0].isAssignableFrom(storage.javaClass)
            } ?: cls.getDeclaredConstructor(storage.javaClass)
        }.getOrNull()
        if (ctor == null) {
            WeLogger.w(
                TAG,
                "SnsInfo DAO 构造器未找到：${cls.name} <- ${storage.javaClass.name}，候选=" +
                    cls.declaredConstructors.joinToString(" | ") {
                        it.parameterTypes.joinToString(",") { p -> p.name }
                    },
            )
            return null
        }

        val dao = runCatching {
            ctor.isAccessible = true
            ctor.newInstance(storage)
        }.onFailure {
            WeLogger.w(TAG, "构造 SnsInfo DAO 失败（${cls.name} <- ${storage.javaClass.name}）：${it.message}")
        }.getOrNull()
        if (dao != null) WeLogger.i(TAG, "SnsInfo DAO 已就绪：${cls.name}")
        cachedSnsDao = dao
        return dao
    }

    /**
     * 按 snsId 取 SnsInfo 对象。
     *
     * 「(String)->SnsInfo」在这个包里不止一个方法（按 serverId 查的 getByServerStrId、按本地 id 查的
     * getByLocalId 等），只取第一个候选很容易拿到按本地 id 查的那个 —— 传 snsId 永远返回 null，
     * 表现就是列表里「有日期、没正文、没图」。这里把全部候选和两种参数写法都试一遍，谁非空用谁。
     */
    @Volatile
    private var cachedSnsInfoMethods: List<java.lang.reflect.Method>? = null

    @Volatile
    private var cachedSnsInfoByLongMethods: List<java.lang.reflect.Method>? = null

    private fun snsInfoOf(dao: Any, snsId: Long): Any? {
        // 直接在这个 DAO 实例的类上按签名找方法，完全绕开 DexKit 委托：
        // 委托的 candidates 只有在「本轮真的跑过查找」时才有值，从缓存恢复时是空的；
        // 而 .method 也可能落到按 localId 查的那个方法上（传 snsId 永远查不到）。
        // DAO 类（8.0.80 = com.tencent.mm.plugin.sns.storage.f2）上同时存在：
        //   V1(long)   select *,rowid from SnsInfo where SnsInfo.snsId=<id> limit 1
        //   M2(String) getByServerStrId
        //   I2(String) getByLocalId   ← 传 snsId 查不到
        // 按「1 个形参 + 返回 SnsInfo」筛出来，long 优先、String 次之，逐个试。
        val methods = cachedDaoQueryMethods ?: run {
            val found = runCatching {
                dao.javaClass.methods.filter { m ->
                    m.parameterCount == 1 &&
                        m.returnType.name.endsWith("SnsInfo") &&
                        (
                            m.parameterTypes[0] == java.lang.Long.TYPE ||
                                m.parameterTypes[0] == java.lang.Long::class.java ||
                                m.parameterTypes[0] == String::class.java
                            )
                }.sortedBy { if (it.parameterTypes[0] == String::class.java) 1 else 0 }
            }.getOrDefault(emptyList())
            cachedDaoQueryMethods = found
            WeLogger.i(
                TAG,
                "SnsInfo 查询方法 " + found.size + " 个：" +
                    found.joinToString(" | ") { it.name + "(" + it.parameterTypes[0].simpleName + ")" },
            )
            found
        }
        if (methods.isEmpty()) {
            WeLogger.w(TAG, "snsInfoOf: DAO 上没有可用的 SnsInfo 查询方法")
            return null
        }

        for (method in methods) {
            val paramType = method.parameterTypes[0]
            val args: List<Any> = when {
                paramType == java.lang.Long.TYPE || paramType == java.lang.Long::class.java ->
                    listOf(snsId)
                paramType == String::class.java ->
                    listOf(java.lang.Long.toUnsignedString(snsId), snsId.toString())
                else -> continue
            }
            runCatching { method.isAccessible = true }
            for (arg in args) {
                val result = runCatching { method.invoke(dao, arg) }.getOrNull()
                if (result != null) return result
            }
        }
        return null
    }

    @Volatile
    private var cachedDaoQueryMethods: List<java.lang.reflect.Method>? = null

    /** SnsInfo.getLocalid()，微信详情页用它定位；取不到时按无符号 snsId 拼。 */
    private fun snsInfoLocalId(snsInfo: Any?, snsId: Long): String {
        val fromInfo = runCatching {
            snsInfo?.javaClass?.getDeclaredMethod("getLocalid")?.apply { isAccessible = true }
                ?.invoke(snsInfo) as? String
        }.getOrNull()
        return fromInfo?.takeIf { it.isNotEmpty() }
            ?: ("sns_table_" + java.lang.Long.toUnsignedString(snsId))
    }

    /** SnsInfo.getTimeLine().ContentDesc —— 朋友圈正文。 */
    private fun snsInfoText(snsInfo: Any?): String {
        if (snsInfo == null) return ""
        val tl = runCatching {
            snsInfo.javaClass.getDeclaredMethod("getTimeLine").apply { isAccessible = true }
                .invoke(snsInfo)
        }.getOrNull() ?: return ""
        return runCatching { tl.javaClass.getField("ContentDesc").get(tl) as? String }
            .getOrNull().orEmpty().trim()
    }

    /**
     * 取这条朋友圈的图片缩略图地址。
     *
     * 正文在 TimeLineObject.ContentDesc，图片在 TimeLineObject.ContentObj.MediaList ——
     * SnsInfo.content 里那坨 XML 只有 id/username 等基础字段，图是拿不到的，必须走解密后的
     * TimeLineObject。protobuf 字段大小写在不同版本里有差异，这里逐个别名试，取到 http 地址为止，
     * 缩略图优先（体积小，列表里够用）。
     */
    private fun snsInfoImages(snsInfo: Any?, max: Int = 3): List<String> {
        val mediaList = snsInfoMediaList(snsInfo)
        if (mediaList != null) {
            val urls = mediaList.asSequence()
                .mapNotNull { pickMediaUrl2(it) }
                .distinct()
                .take(max)
                .toList()
            if (urls.isNotEmpty()) return urls
        }
        return snsInfoImagesFromToString(snsInfo, max)
    }

    /**
     * 兜底：直接从 TimeLineObject 的 toString() 里抓 http 地址。
     *
     * protobuf 生成类一般都重写了 toString，会把 url / thumbUrl 这类字段打出来。
     * 万一字段层级和我们预期的完全不一样（真机上 media=0 就是这种情况），
     * 这条路径还能救回一批图，总比整条都没有强。
     */
    private fun snsInfoImagesFromToString(snsInfo: Any?, max: Int = 3): List<String> {
        if (snsInfo == null) return emptyList()
        val timeLine = runCatching {
            snsInfo.javaClass.getDeclaredMethod("getTimeLine").apply { isAccessible = true }
                .invoke(snsInfo)
        }.getOrNull() ?: return emptyList()
        val dump = runCatching { timeLine.toString() }.getOrNull() ?: return emptyList()
        if (dump.length < 20) return emptyList()
        return Regex("https?://[^ \u0022\u0027<>]+")
            .findAll(dump)
            .map { it.value }
            .filter { it.startsWith("http") }
            .distinct()
            .take(max)
            .toList()
    }

    /**
     * 把抓到的地址整理成能直接加载的形式。
     *
     * 微信朋友圈 CDN 给的常常是 http://mmsns.qpic.cn/...，而模块没有开明文流量，
     * Android 9+ 会直接拦掉（表现就是有灰框、图不出来）。这里统一升到 https，
     * 顺便把 XML 里的 &amp; 还原成 &，否则带了查询串的地址也会 404。
     */
    private fun normalizeImageUrl(url: String): String {
        var v = url.trim().replace("&amp;", "&")
        if (v.startsWith("//")) v = "https:$v"
        return v
    }

    /** 兜底二：SnsInfo.content 那列 XML 里也带 media 信息，抓其中的图片地址。 */
    /**
     * 是否是「朋友圈自己的媒体图」。
     *
     * 朋友圈 XML 里不止有配图，还夹着小程序卡片缩略图（wxappthumb.tc.qq.com）、
     * 头像（qlogo / headimg）等。这些地址必然加载失败，会占满 take(3) 的名额，
     * 导致真正能显示的 mmsns 图片被挤掉——表现就是整片灰框。
     */
    private fun isMomentImageUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.contains("wxappthumb") || lower.contains("qlogo") || lower.contains("headimg")) return false
        return lower.contains("mmsns") || lower.contains("snsvideodownload")
    }

    private fun extractImageUrlsFromXml(xml: String?, max: Int = 3): List<String> {
        if (xml.isNullOrBlank()) return emptyList()
        val out = LinkedHashSet<String>()
        // 优先取媒体标签里的地址（朋友圈 XML 里图片是 <url>/<cdnurl>/<thumburl>）
        Regex(
            """<(?:url|cdnurl|thumburl|thumb)>\s*(?:<!\[CDATA\[)?\s*(https?://[^\]<\s]+)""",
            RegexOption.IGNORE_CASE,
        ).findAll(xml).forEach { m ->
            val v = m.groupValues.getOrNull(1).orEmpty().trim()
            if (v.startsWith("http") && isMomentImageUrl(v)) out.add(v)
        }
        // 标签没命中时再按域名兜底
        if (out.isEmpty()) {
            Regex("""https?://[^ "<>]+""").findAll(xml)
                .map { it.value }
                .filter { isMomentImageUrl(it) }
                .forEach { out.add(it) }
        }
        return out.map { normalizeImageUrl(it) }.take(max)
    }

    /** 含 private 在内的全部字段（含父类）。protobuf 字段未必是 public，必须这样取。 */
    private fun allFields(cls: Class<*>): List<java.lang.reflect.Field> {
        val out = ArrayList<java.lang.reflect.Field>()
        var current: Class<*>? = cls
        var guard = 0
        while (current != null && current != Any::class.java && guard < 8) {
            runCatching { out.addAll(current.declaredFields) }
            current = current.superclass
            guard++
        }
        return out
    }

    /**
     * 从 SnsInfo 里找出媒体列表。
     *
     * 层级通常是 TimeLineObject.ContentObj(SnsObject) → .ContentObj(SnsContentObj) → .MediaList，
     * 但真机日志显示按 public 字段递归找不到（media=0），所以这里改用 declaredFields + setAccessible
     * 全量遍历，并且放宽识别条件（元素类型名含 Media / 元素里能取到 http 地址）。
     */
    private fun snsInfoMediaList(snsInfo: Any?): List<*>? {
        if (snsInfo == null) return null
        val timeLine = runCatching {
            snsInfo.javaClass.getDeclaredMethod("getTimeLine").apply { isAccessible = true }
                .invoke(snsInfo)
        }.getOrNull() ?: return null
        if (!structureLogged) {
            structureLogged = true
            runCatching {
                WeLogger.i(
                    TAG,
                    "TimeLineObject 字段：" + allFields(timeLine.javaClass).joinToString(" | ") {
                        it.name + ":" + it.type.simpleName
                    },
                )
            }
        }
        return findMediaListIn(timeLine, 0)
    }

    @Volatile
    private var structureLogged = false

    private fun findMediaListIn(node: Any?, depth: Int): List<*>? {
        if (node == null || depth > 5) return null
        if (node is String || node is Number || node is Boolean || node is Collection<*>) return null
        val cls = node.javaClass
        if (cls.isPrimitive || cls.name.startsWith("java.") || cls.name.startsWith("kotlin.")) return null

        for (field in allFields(cls)) {
            runCatching { field.isAccessible = true }
            val value = runCatching { field.get(node) }.getOrNull() ?: continue
            if (value is List<*>) {
                val first = value.firstOrNull { it != null } ?: continue
                val elemName = first.javaClass.name
                val looksLikeMedia = elemName.contains("Media", ignoreCase = true) ||
                    elemName.contains("Img", ignoreCase = true) ||
                    (
                        !elemName.contains("Comment", ignoreCase = true) &&
                            !elemName.contains("User", ignoreCase = true) &&
                            pickMediaUrl(first) != null
                        )
                if (looksLikeMedia) return value
            } else if (!field.type.isPrimitive &&
                !field.type.name.startsWith("java.") &&
                !field.type.name.startsWith("android.") &&
                !field.type.name.startsWith("kotlin.")
            ) {
                // 不能只认 com.tencent.mm.* —— 8.0.80 里 TimeLineObject.ContentObj 的类型
                // 被微信重打包成 gd5.na0（媒体容器整个搬到短包名），按包名过滤会整棵子树跳过。
                findMediaListIn(value, depth + 1)?.let { return it }
            }
        }
        return null
    }

    /** 在媒体对象上找一个可直接加载的图片地址：优先缩略图，其次原图；只认 http(s)。 */
    private fun pickMediaUrl(media: Any): String? {
        val candidates = ArrayList<String>(4)
        for (field in allFields(media.javaClass)) {
            runCatching { field.isAccessible = true }
            val value = runCatching { field.get(media) }.getOrNull() ?: continue
            when (value) {
                // URL 字段可能是 String，也可能是 List<String>（多分辨率），两种都收
                is String -> {
                    if (isLoadableImageUrl(value)) candidates.add(value)
                }
                is List<*> -> {
                    for (item in value) {
                        if (item is String && isLoadableImageUrl(item)) candidates.add(item)
                    }
                }
            }
        }
        if (candidates.isEmpty()) return null
        val picked = candidates.firstOrNull { it.contains("thumb", ignoreCase = true) }
            ?: candidates.first()
        return normalizeImageUrl(picked)
    }

    private fun isLoadableImageUrl(value: String): Boolean {
        if (!value.startsWith("http")) return false
        if (value.contains("headimg", ignoreCase = true) ||
            value.contains("avatar", ignoreCase = true)
        ) return false
        return true
    }

    private fun pickMediaUrl2(media: Any?): String? =
        if (media == null) null else pickMediaUrl(media)

    /** 类里是否有「静态 0 参且返回朋友圈库对象」的方法。 */
    private fun isSnsCoreLike(name: String): Boolean {
        if (name.contains('$')) return false
        return runCatching {
            Class.forName(name, false, ClassLoaders.HOST).declaredMethods.any { m ->
                java.lang.reflect.Modifier.isStatic(m.modifiers) &&
                    m.parameterCount == 0 &&
                    isSnsInfoStorageType(m.returnType)
            }
        }.getOrDefault(false)
    }

    /** 返回类型是不是 SnsInfoStorage：它独有 `delete(String, String, String[])`。 */
    private fun isSnsInfoStorageType(t: Class<*>): Boolean = runCatching {
        t.name.startsWith("com.tencent.mm.plugin.sns.storage.") &&
            !t.name.endsWith(".SnsInfo") &&
            t.declaredMethods.any { m ->
                m.name == "delete" && m.parameterTypes.size == 3 &&
                    m.parameterTypes[0] == String::class.java &&
                    m.parameterTypes[1] == String::class.java &&
                    m.parameterTypes[2].name == "[Ljava.lang.String;"
            }
    }.getOrDefault(false)

    // ── 朋友圈数据库（SnsMicroMsg.db）─────────────────────────────────────────
    /**
     * 微信把 SnsInfo 表放在独立的 SnsMicroMsg.db 里，EnMicroMsg.db 中没有这张表，
     * 在主库上执行 `SELECT ... FROM SnsInfo` 会直接返回空。
     *
     * 取库路径：SnsCore.getSnsInfoStorage() 拿 SnsInfoStorage，再反射它的
     * SQLiteDatabase 字段。这里**不用 DexKit 定位类**——用类名字符串匹配会同时
     * 命中 SnsCore$23 / c5 / p4$$a / p4$$b / p4 五个类，DexKit 抛
     * 「Multiple classes found」导致整个功能装挂。因此这里先用原类名反射
     * （微信 <= 8.0.79 保留），失败再走 [delegateSnsCore] 的 DexKit 多候选筛选
     * （8.0.80 起混淆成短名，如 `p4`）。
     */
    private var snsStorageClassTried = false
    private var snsStorageClass: Class<*>? = null

    private fun snsCoreClass(): Class<*>? {
        if (!snsStorageClassTried) {
            snsStorageClassTried = true
            // 1) 原类名（微信 <= 8.0.79 保留）
            snsStorageClass = runCatching {
                Class.forName("com.tencent.mm.plugin.sns.model.SnsCore", false, ClassLoaders.HOST)
            }.getOrNull()
            // 2) DexKit 多候选二次筛选（8.0.80+ 混淆成短名）
            if (snsStorageClass == null) {
                snsStorageClass = runCatching { delegateSnsCore.clazz }.getOrElse {
                    WeLogger.w(TAG, "SnsCore 定位失败：${it.message}")
                    null
                }
            }
            snsStorageClass?.let { WeLogger.i(TAG, "SnsCore 已定位：${it.name}") }
        }
        return snsStorageClass
    }

    /** 调 getSnsInfoStorage()：优先按方法名，退化到「静态 0 参且返回类型名含 SnsInfoStorage」。 */
    private fun resolveSnsInfoStorage(): Any? {
        val cls = snsCoreClass() ?: return null
        val byName = runCatching {
            cls.declaredMethods.firstOrNull {
                java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                    it.parameterCount == 0 &&
                    it.name == "getSnsInfoStorage"
            }
        }.getOrNull()
        val target = byName ?: runCatching {
            cls.declaredMethods.firstOrNull { m ->
                java.lang.reflect.Modifier.isStatic(m.modifiers) &&
                    m.parameterCount == 0 &&
                    isSnsInfoStorageType(m.returnType)
            }
        }.getOrNull()
        if (target == null) {
            WeLogger.w(TAG, "SnsCore 里没找到 getSnsInfoStorage()")
            return null
        }
        return runCatching {
            target.isAccessible = true
            target.invoke(null)
        }.onFailure { WeLogger.e(TAG, "调用 getSnsInfoStorage 失败", it) }.getOrNull()
    }

    @Volatile
    private var cachedSnsDb: Any? = null
    @Volatile
    private var snsDbResolveFailed = false

    private fun resolveSnsDb(): Any? {
        cachedSnsDb?.let { return it }
        if (snsDbResolveFailed) return null
        val storage = resolveSnsInfoStorage()
        if (storage == null) {
            snsDbResolveFailed = true
            return null
        }
        val db = findDbIn(storage, 0)
        if (db == null) {
            WeLogger.w(TAG, "SnsInfoStorage 里没找到 SQLiteDatabase 字段")
        } else {
            cachedSnsDb = db
            WeLogger.i(TAG, "朋友圈库已定位：${db.javaClass.name}")
        }
        return db
    }

    /**
     * 在 SnsInfoStorage 里找可执行查询的对象。
     *
     * 微信 8.0.80 的库封装类被混淆成短名（实测 `gg5.k0`），方法名也一并混淆
     * （rawQuery 变成 `B`），所以不能只靠「字段类型是不是 SQLiteDatabase」
     * 判断，改为行为匹配：凡是持有「接受 (String, String[]) 且返回 Cursor」
     * 方法的对象，都当作可查询的库。
     */
    private fun findDbIn(obj: Any, depth: Int): Any? {
        if (depth > 3) return null
        for (f in obj.javaClass.declaredFields) {
            val v = runCatching {
                f.isAccessible = true
                f.get(obj)
            }.getOrNull() ?: continue
            val tn = f.type.name
            if (tn == "com.tencent.wcdb.database.SQLiteDatabase" ||
                tn == "android.database.sqlite.SQLiteDatabase" ||
                queryMethodOf(v) != null
            ) {
                WeLogger.i(TAG, "朋友圈库对象：${v.javaClass.name}（字段 ${f.name}）")
                return v
            }
        }
        for (f in obj.javaClass.declaredFields) {
            val v = runCatching {
                f.isAccessible = true
                f.get(obj)
            }.getOrNull() ?: continue
            if (v.javaClass.name.startsWith("com.tencent.mm.plugin.sns")) {
                findDbIn(v, depth + 1)?.let { return it }
            }
        }
        return null
    }

    /**
     * 找「(String, String[]) -> Cursor」的方法，即 rawQuery（方法名可能被混淆）。
     */
    private fun queryMethodOf(target: Any): java.lang.reflect.Method? = runCatching {
        target.javaClass.methods.firstOrNull { m ->
            m.parameterTypes.size == 2 &&
                m.parameterTypes[0] == String::class.java &&
                m.parameterTypes[1].name == "[Ljava.lang.String;" &&
                android.database.Cursor::class.java.isAssignableFrom(m.returnType) &&
                m.name != "query" &&
                m.name != "queryWithFactory"
        }
    }.getOrNull()

    /** 优先查朋友圈库，失败回退主库。 */
    private fun querySns(sql: String, args: Array<String>?): android.database.Cursor? {
        val db = resolveSnsDb()
        if (db != null) {
            // 1) 标准 rawQuery
            val cur = runCatching {
                val m = db.javaClass.getMethod(
                    "rawQuery",
                    String::class.java,
                    Array<String>::class.java,
                )
                m.invoke(db, sql, args) as? android.database.Cursor
            }.getOrNull()
            if (cur != null) return cur
            // 2) 8.0.80+ 方法名被混淆：按签名找 (String, String[]) -> Cursor
            val cur2 = runCatching {
                val m = queryMethodOf(db) ?: return@runCatching null
                m.isAccessible = true
                m.invoke(db, sql, args) as? android.database.Cursor
            }.onFailure { WeLogger.w(TAG, "朋友圈库查询异常：${db.javaClass.name}", it) }.getOrNull()
            if (cur2 != null) return cur2
            WeLogger.w(TAG, "朋友圈库不可查询，回退主库：${db.javaClass.name}")
        }
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            WeDatabaseApi.rawQuery(sql, args as Array<Any>?)
        }.getOrNull()
    }

    // ── 配置（持久化） ────────────────────────────────────────────────────────
    /** 学习到的 opType，-1 表示尚未学到。 */
    private var learnedOpType by WePrefs.prefOption(PREF_OP_TYPE, -1)
    private var intervalInput by WePrefs.prefOption(PREF_INTERVAL, "5000")
    private var batchSizeInput by WePrefs.prefOption(PREF_BATCH_SIZE, "20")
    private var batchPauseInput by WePrefs.prefOption(PREF_BATCH_PAUSE, "60000")
    private var dailyLimitInput by WePrefs.prefOption(PREF_DAILY_LIMIT, "200")

    // ── 运行状态 ──────────────────────────────────────────────────────────────
    private val running = AtomicBoolean(false)
    @Volatile private var cancelRequested = false

    /** 正在删除的 snsId：用于区分「模块触发」与「用户手动触发」，避免污染学习样本。 */
    private val deletingSnsIds = ConcurrentHashMap.newKeySet<Long>()

    /** 删除结果：snsId -> retcode（0 成功）。 */
    private val deleteResults = ConcurrentHashMap<Long, Int>()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    // ── 数据结构 ──────────────────────────────────────────────────────────────
    data class MomentItem(
        val snsId: Long,
        val createTime: Long,
        val createTimeText: String,
        /** 分组用：如「2023年5月」 */
        val yearMonth: String,
        val type: Int,
        /** 类型标签：图片 / 图片×3 / 视频 / 音乐 / 链接 / 文字，空串表示未知。 */
        val kind: String,
        /** 正文摘要（SnsInfo 解密后的 ContentDesc，回落 XML 标签）。 */
        val preview: String,
        /** 微信内部本地 id（形如 `sns_table_xxx`），详情页靠它定位这条朋友圈。 */
        val localId: String,
        /** 缩略图地址（最多 3 张，来自 TimeLineObject 里的 MediaList）。 */
        val images: List<String> = emptyList(),
        /** 这条朋友圈实际含有的媒体数量（即使拿不到 URL，也能据此提示「含 N 张图/视频」）。 */
        val imageCount: Int = 0,
    )

    enum class FilterMode { BY_YEAR, BY_COUNT }

    data class DeleteOptions(
        val mode: FilterMode = FilterMode.BY_YEAR,
        val yearFrom: Int = 2014,
        val yearTo: Int = Calendar.getInstance().get(Calendar.YEAR),
        val limit: Int = 100,
        val intervalMs: Long = 5000L,
        val batchSize: Int = 20,
        val batchPauseMs: Long = 60000L,
        val dailyLimit: Int = 200,
    )

    data class DeleteReport(
        val total: Int,
        val success: Int,
        val failed: Int,
        val skipped: Int,
        val cancelled: Boolean,
    )

    // ══════════════════════════════════════════════════════════════════════════
    // 生命周期 / 自动学习
    // ══════════════════════════════════════════════════════════════════════════

    override fun onEnable() {
        hookOpResult()
        hookOpTypeLearning()
    }

    /** 监听删除结果：从 onGYNetEnd 的 JSON 里取 retcode。 */
    private fun hookOpResult() {
        runCatching {
            methodOpOnGYNetEnd.hookAfter {
                val json = args.getOrNull(2) as? JSONObject ?: return@hookAfter
                val retCode = json.optInt("retcode", -1)
                // snsId 可能以 snsId / objectId 字段返回，两种都尝试
                val snsId = json.optLong("snsId", 0L).takeIf { it != 0L }
                    ?: json.optLong("objectId", 0L).takeIf { it != 0L }
                WeLogger.i(TAG, "NetSceneSnsObjectOp result: retcode=$retCode snsId=$snsId")

                if (snsId != null && deletingSnsIds.contains(snsId)) {
                    deleteResults[snsId] = retCode
                }
            }
        }.onFailure {
            WeLogger.e(TAG, "hook NetSceneSnsObjectOp.onGYNetEnd failed", it)
        }
    }

    /**
     * 学习 opType：hook `NetSceneSnsObjectOp(long, int)` 构造器。
     *
     * 只有非模块触发（用户手动删除）才更新样本，避免把历史值覆盖成错误值。
     */
    private fun hookOpTypeLearning() {
        runCatching {
            val cls = classSnsObjectOp.clazz
            val ctor = findSnsOpConstructor(cls) ?: run {
                WeLogger.w(TAG, "未找到 NetSceneSnsObjectOp(long,int) 构造器，opType 学习不可用")
                return
            }
            ctor.hookAfterDirectly {
                val snsId = (args.getOrNull(0) as? Long) ?: return@hookAfterDirectly
                val opType = (args.getOrNull(1) as? Int) ?: return@hookAfterDirectly
                if (deletingSnsIds.contains(snsId)) return@hookAfterDirectly

                if (learnedOpType != opType) {
                    learnedOpType = opType
                    WeLogger.i(TAG, "已学习 opType=$opType（来源：手动删除 snsId=$snsId）")
                }
            }
            WeLogger.i(TAG, "opType 学习 hook 已安装")
        }.onFailure {
            WeLogger.e(TAG, "hook NetSceneSnsObjectOp 构造器失败", it)
        }
    }

    private fun findSnsOpConstructor(cls: Class<*>): Constructor<*>? {
        return cls.declaredConstructors.firstOrNull { ctor ->
            val types = ctor.parameterTypes
            types.size == 2 &&
                (types[0] == Long::class.javaPrimitiveType || types[0] == java.lang.Long::class.java) &&
                (types[1] == Int::class.javaPrimitiveType || types[1] == java.lang.Integer::class.java)
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 枚举自己的朋友圈
    // ══════════════════════════════════════════════════════════════════════════

    /** 读取 SnsInfo 表的列名，避免硬编码列名在版本间失效。 */
    private fun snsInfoColumns(): Set<String> = runCatching {
        val cols = mutableSetOf<String>()
        querySns("PRAGMA table_info(SnsInfo)", null)?.use { c ->
            val nameIdx = c.getColumnIndex("name")
            while (c.moveToNext()) {
                if (nameIdx >= 0) cols.add(c.getString(nameIdx))
            }
        }
        cols
    }.getOrElse {
        WeLogger.e(TAG, "读取 SnsInfo 表结构失败", it)
        emptySet()
    }

    /**
     * 枚举自己的朋友圈。
     *
     * 依赖列：snsId / userName / createTime / type / content（缺列时降级跳过）。
     */
    fun loadMyMoments(options: DeleteOptions = DeleteOptions()): List<MomentItem> {
        val self = WeApi.selfWxId
        if (self.isNullOrEmpty()) {
            WeLogger.w(TAG, "selfWxId 为空，无法枚举朋友圈")
            return emptyList()
        }

        val cols = snsInfoColumns()
        if (cols.isEmpty()) return emptyList()

        val hasUser = "userName" in cols
        val hasTime = "createTime" in cols
        if (!hasTime) {
            WeLogger.w(TAG, "SnsInfo 缺少 createTime 列，无法按时间筛选")
            return emptyList()
        }

        val where = buildString {
            append(" WHERE ")
            if (hasUser) append("userName = ?")
            else append("1=1")
        }

        val sql = "SELECT snsId, createTime, type, content FROM SnsInfo$where ORDER BY createTime DESC"
        val args = if (hasUser) arrayOf(self) else null

        // 详情页要的 localId、以及正文 ContentDesc，都得走 SnsInfo 对象；
        // SnsInfo 由 DAO（f2）按 snsId 查出来，DAO 用 storage 构造。
        val storageObj = runCatching { resolveSnsInfoStorage() }.getOrNull()
        val dao = storageObj?.let { resolveSnsDao(it) }

        val result = mutableListOf<MomentItem>()
        var sampleLogged = false
        var urlLogged = false
        var droppedLogged = false
        runCatching {
            querySns(sql, args)?.use { c ->
                val iSnsId = c.getColumnIndex("snsId")
                val iTime = c.getColumnIndex("createTime")
                val iType = c.getColumnIndex("type")
                val iContent = c.getColumnIndex("content")
                if (iSnsId < 0 || iTime < 0) return@use

                while (c.moveToNext()) {
                    val snsId = c.getLong(iSnsId)
                    if (snsId == 0L) continue
                    val createTime = c.getLong(iTime)
                    val type = if (iType >= 0) c.getInt(iType) else 0
                    val rawXml = if (iContent >= 0) {
                        runCatching { c.getBlob(iContent)?.let { String(it, Charsets.UTF_8) } }.getOrNull()
                    } else null

                    // 正文只能靠 SnsInfo 解密后的 TimeLineObject 拿到
                    val snsInfo = dao?.let { runCatching { snsInfoOf(it, snsId) }.getOrNull() }
                    val bodyText = runCatching { snsInfoText(snsInfo) }.getOrDefault("")
                    // 只解析一次媒体列表：同一条既要用 URL 又要数量，重复递归没必要。
                    val mediaList = runCatching { snsInfoMediaList(snsInfo) }.getOrNull()
                    val imageCount = mediaList?.size ?: 0
                    val images = mediaList?.let { list ->
                        runCatching {
                            list.asSequence()
                                .mapNotNull { pickMediaUrl2(it) }
                                .distinct()
                                .take(3)
                                .toList()
                        }.getOrDefault(emptyList())
                    }.orEmpty()
                    // 兜底二：protobuf 结构拿不到图时，从 content 列 XML 里抓图片地址。
                    // 注意 images 也可能来自 toString() 兜底，里面会混进小程序卡片图，
                    // 所以出口统一再过一遍 isMomentImageUrl，避免整片灰框。
                    val rawCandidates = if (images.isEmpty()) {
                        runCatching { extractImageUrlsFromXml(rawXml) }.getOrDefault(emptyList())
                    } else images
                    // 小程序卡片的缩略图（wxappthumb）/公众号封面（mmbiz）也放进预览，
                    // 否则整屏都是「含 N 张图片/视频，暂无预览」灰字；真图（mmsns/视频号）
                    // 排在前面，避免被卡片图挤掉。
                    val candidateUrls = rawCandidates
                        .filter {
                            isMomentImageUrl(it) ||
                                it.contains("wxappthumb") ||
                                it.contains("mmbiz.")
                        }
                        .distinct()
                    val finalImages = candidateUrls
                        .sortedByDescending { url ->
                            url.contains("mmsns") || url.contains("snsvideodownload")
                        }
                        .take(3)
                    val droppedUrls = rawCandidates.filter { url -> candidateUrls.none { it == url } }
                    if (!droppedLogged && droppedUrls.isNotEmpty()) {
                        droppedLogged = true
                        WeLogger.i(
                            TAG,
                            "未进预览的媒体地址（样本）：" +
                                droppedUrls.take(3).joinToString(" | ") { u -> u.take(140) },
                        )
                    }
                    if (!sampleLogged) {
                        sampleLogged = true
                        WeLogger.i(
                            TAG,
                            "样本诊断：snsId=$snsId, snsInfo=${snsInfo != null}, " +
                                "bodyLen=${bodyText.length}, type=$type, " +
                                "media=$imageCount, urls=${images.size}, xmlUrls=${finalImages.size}",
                        )
                    }
                    // 单独打印「第一条真正带图的」地址：样本那条很可能本来就纯文字，
                    // 只有这条才能看出真实图片地址长什么样。
                    if (!urlLogged && finalImages.isNotEmpty()) {
                        urlLogged = true
                        WeLogger.i(TAG, "首条图片地址：${finalImages.first().take(160)}")
                    }
                    val localId = snsInfoLocalId(snsInfo, snsId)

                    result.add(
                        MomentItem(
                            snsId = snsId,
                            createTime = createTime,
                            createTimeText = formatTime(createTime),
                            yearMonth = formatYearMonth(createTime),
                            type = type,
                            kind = summarizeKind(rawXml),
                            preview = summarizeText(rawXml, bodyText),
                            localId = localId,
                            images = finalImages,
                            imageCount = imageCount,
                        )
                    )
                }
            }
        }.onFailure { WeLogger.e(TAG, "枚举朋友圈失败", it) }

        val statsLine = "共 ${result.size} 条：有正文 ${result.count { it.preview.isNotBlank() }}，" +
            "有图 ${result.count { it.images.isNotEmpty() }}" +
            "（self=$self, storage=${storageObj != null}, dao=${dao != null}）"
        lastParseStats = statsLine
        runCatching {
            lastProbeUrl = result.firstOrNull { it.images.isNotEmpty() }?.images?.firstOrNull()
        }
        WeLogger.i(TAG, "枚举到 $statsLine")
        return result
    }

    private fun passesFilter(createTime: Long, type: Int, content: String?, options: DeleteOptions): Boolean {
        if (createTime <= 0L) return false
        val cal = Calendar.getInstance().apply { timeInMillis = createTime * 1000 }
        val year = cal.get(Calendar.YEAR)
        if (options.mode == FilterMode.BY_YEAR) {
            if (year < options.yearFrom || year > options.yearTo) return false
        }
        return true
    }

    private fun formatTime(seconds: Long): String =
        runCatching { dateFormat.format(Date(seconds * 1000)) }.getOrDefault("")

    private val yearMonthFormat = SimpleDateFormat("yyyy年M月", Locale.US)

    private fun formatYearMonth(seconds: Long): String =
        runCatching { yearMonthFormat.format(Date(seconds * 1000)) }.getOrDefault("未知时间")

    /** 类型标签：从 content XML 的 contentStyle / media 数量推断。 */
    /**
     * 判断这条朋友圈「是什么」。
     *
     * 按内容类型自动切换，不写死：视频号 / 小程序 / 视频 / 音乐 / 图片 / 链接 / 纯文字。
     * 判断依据都取 XML 里的真实标签，越具体的类型越先判断。
     */
    private fun summarizeKind(raw: String?): String {
        val xml = raw ?: ""
        val lower = xml.lowercase()

        // 视频号分享（finderFeed / finderUsername / objectid + objectnonceid）
        if (lower.contains("finderfeed") || lower.contains("finderusername") ||
            (lower.contains("objectid") && lower.contains("objectnonceid"))
        ) {
            return "视频号"
        }
        // 小程序 / 公众号卡片
        if (lower.contains("<appinfo") || lower.contains("appname")) {
            return "小程序"
        }
        // 视频（视频朋友圈既有 videoid，也有 thumb/url）
        if (lower.contains("<videoid") || lower.contains("<videourl")) {
            return "视频"
        }
        // 音乐分享
        if (lower.contains("<musicurl") || lower.contains("<musictitle")) {
            return "音乐"
        }

        val style = xmlTag(xml, "contentStyle").toIntOrNull()
        val mediaCount = runCatching { Regex("<media>").findAll(xml).count() }.getOrDefault(0)
        return when (style) {
            1 -> "文字"
            2 -> if (mediaCount > 1) "图片×$mediaCount" else "图片"
            3 -> "视频"
            4 -> "音乐"
            5 -> "链接"
            else -> when {
                mediaCount > 0 -> if (mediaCount > 1) "图片×$mediaCount" else "图片"
                lower.contains("<url") -> "链接"
                else -> ""
            }
        }
    }

    private fun summarizeText(raw: String?, bodyText: String = ""): String {
        val xml = raw ?: ""
        val body = bodyText.ifBlank {
            listOf("ContentDesc", "contentDesc", "title", "description")
                .map { xmlTag(xml, it) }
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
        }.replace(Regex("\\s+"), " ").trim()
        return if (body.length > 60) body.take(60) + "…" else body
    }

    /** 取 XML 里第一个 <tag>…</tag> 的文本（含 CDATA / 常见实体反转义）。 */
    private fun xmlTag(xml: String, tag: String): String = runCatching {
        Regex("<$tag>(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?</$tag>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.getOrNull(1)
            ?.replace("&lt;", "<")
            ?.replace("&gt;", ">")
            ?.replace("&quot;", "\"")
            ?.replace("&apos;", "'")
            ?.replace("&amp;", "&")
            ?.trim()
            .orEmpty()
    }.getOrDefault("")

    /**
     * 打开微信原生的朋友圈详情页，让用户直接看到这条朋友圈的完整内容。
     *
     * 参数取自 8.0.80 的 SnsCommentDetailUI.onCreate：INTENT_SNSID / INTENT_SNS_LOCAL_ID，
     * localSnsId 由 "sns_table_" + snsId 拼成；INTENT_TALKER 是作者 wxid。
     */
    private fun openMomentDetail(context: Context, item: MomentItem) {
        runCatching {
            val intent = Intent().apply {
                setClassName(
                    context.packageName,
                    "com.tencent.mm.plugin.sns.ui.SnsCommentDetailUI",
                )
                putExtra("INTENT_SNSID", item.snsId)
                putExtra("INTENT_SNS_LOCAL_ID", item.localId)
                WeApi.selfWxId?.takeIf { it.isNotEmpty() }?.let { putExtra("INTENT_TALKER", it) }
                putExtra("INTENT_NEED_RPT_FEED", false)
                // 只有拿不到 Activity 上下文时才另起任务；否则留在当前任务栈，
                // 这样从朋友圈详情返回时直接回到本页的扫描列表，而不是微信主界面。
                if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            WeLogger.i(TAG, "跳转朋友圈详情：snsId=${item.snsId} localId=${item.localId}")
        }.onFailure {
            WeLogger.e(TAG, "跳转朋友圈详情失败：snsId=${item.snsId}", it)
            showToast("跳转失败：" + (it.message ?: it.javaClass.simpleName))
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 批量删除
    // ══════════════════════════════════════════════════════════════════════════

    /** 今日已删除条数（用于每日上限保护）。 */
    private fun todayCount(): Int {
        val today = dateFormat.format(Date())
        val storedDate = WePrefs.getStringOrDef(PREF_DAILY_DATE, "")
        if (storedDate != today) {
            WePrefs.putString(PREF_DAILY_DATE, today)
            WePrefs.putString(PREF_DAILY_COUNT, "0")
            return 0
        }
        return WePrefs.getStringOrDef(PREF_DAILY_COUNT, "0").toIntOrNull() ?: 0
    }

    private fun bumpTodayCount(delta: Int) {
        val current = todayCount()
        WePrefs.putString(PREF_DAILY_COUNT, (current + delta).toString())
    }

    fun isRunning(): Boolean = running.get()

    fun requestCancel() {
        cancelRequested = true
    }

    /**
     * 执行批量删除（后台线程，不阻塞 UI）。
     *
     * @return false 表示前置条件不满足（未学习 opType / 正在运行 / 超过每日上限）。
     */
    fun startDelete(
        items: List<MomentItem>,
        options: DeleteOptions,
        onProgress: (done: Int, total: Int, message: String) -> Unit,
        onFinished: (DeleteReport) -> Unit,
    ): Boolean {
        if (items.isEmpty()) {
            onProgress(0, 0, "没有符合条件的朋友圈")
            return false
        }
        if (!running.compareAndSet(false, true)) {
            onProgress(0, items.size, "已有删除任务在执行")
            return false
        }

        val alreadyToday = todayCount()
        val allow = (options.dailyLimit - alreadyToday).coerceAtLeast(0)
        if (allow <= 0) {
            running.set(false)
            onProgress(0, items.size, "已达到今日上限（${options.dailyLimit} 条）")
            return false
        }

        val queue = items.take(allow)
        cancelRequested = false
        deleteResults.clear()

        thread(name = "BatchDeleteMoments", isDaemon = true) {
            var success = 0
            var failed = 0
            var cancelled = false
            try {
                val ctor = findSnsOpConstructor(classSnsObjectOp.clazz)
                if (ctor == null) {
                    onFinished(DeleteReport(queue.size, 0, 0, queue.size, false))
                    return@thread
                }

                // 自动探测 opType：无需用户手动删除来「学习」。
                // 依次尝试 1→2→3，用第一条试删，命中即锁定该值继续。
                var startIndex = 0
                if (learnedOpType < 0 && queue.isNotEmpty()) {
                    onProgress(0, queue.size, "正在自动识别删除参数…")
                    val probed = probeOpType(ctor, queue.first().snsId)
                    if (probed > 0) {
                        learnedOpType = probed
                        success++
                        bumpTodayCount(1)
                        startIndex = 1
                        onProgress(1, queue.size, "参数已识别（opType=$probed），开始批量删除")
                    } else {
                        WeLogger.e(TAG, "opType 自动探测失败，全部候选均未成功")
                        onFinished(DeleteReport(queue.size, 0, queue.size, 0, false))
                        return@thread
                    }
                }

                queue.forEachIndexed { index, item ->
                    if (index < startIndex) return@forEachIndexed
                    if (cancelRequested) {
                        cancelled = true
                        return@forEachIndexed
                    }
                    // 每批之间长暂停，降低风控特征
                    if (index > 0 && index % options.batchSize == 0) {
                        onProgress(index, queue.size, "第 ${index / options.batchSize} 批完成，暂停 ${options.batchPauseMs / 1000}s")
                        sleepSafe(options.batchPauseMs)
                    }
                    if (index > 0) sleepSafe(options.intervalMs)

                    if (cancelRequested) {
                        cancelled = true
                        return@forEachIndexed
                    }

                    val ok = deleteOne(ctor, item.snsId)
                    if (ok) {
                        success++
                        bumpTodayCount(1)
                    } else {
                        failed++
                    }
                    onProgress(index + 1, queue.size, "${item.createTimeText} ${if (ok) "已删除" else "失败"}")
                }
            } catch (t: Throwable) {
                WeLogger.e(TAG, "批量删除异常中止", t)
            } finally {
                deletingSnsIds.clear()
                running.set(false)
                onFinished(
                    DeleteReport(
                        total = queue.size,
                        success = success,
                        failed = failed,
                        skipped = items.size - queue.size,
                        cancelled = cancelled,
                    )
                )
            }
        }
        return true
    }

    /** 试删一条，依次尝试候选 opType，成功返回该值。 */
    private fun probeOpType(ctor: Constructor<*>, snsId: Long): Int {
        for (candidate in intArrayOf(1, 2, 3)) {
            deleteResults.clear()
            deletingSnsIds.add(snsId)
            val sent = runCatching {
                WeNetSceneApi.sendNetScene(ctor.newInstance(snsId, candidate))
                true
            }.getOrElse {
                WeLogger.w(TAG, "opType=$candidate 发送失败", it)
                false
            }
            if (!sent) {
                deletingSnsIds.remove(snsId)
                continue
            }
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline) {
                val ret = deleteResults.remove(snsId)
                if (ret != null) {
                    if (ret == 0) {
                        deletingSnsIds.remove(snsId)
                        WeLogger.i(TAG, "opType 探测成功：$candidate")
                        return candidate
                    }
                    WeLogger.w(TAG, "opType=$candidate retcode=$ret，试下一个")
                    break
                }
                Thread.sleep(120)
            }
            deletingSnsIds.remove(snsId)
        }
        return -1
    }

    private fun sleepSafe(ms: Long) {
        if (ms <= 0) return
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (cancelRequested) return
            Thread.sleep(minOf(500L, (end - System.currentTimeMillis()).coerceAtLeast(1L)))
        }
    }

    /** 删除一条，阻塞等待结果（最多 15 秒）。 */
    private fun deleteOne(ctor: Constructor<*>, snsId: Long): Boolean {
        val opType = learnedOpType
        if (opType < 0) return false
        return try {
            deletingSnsIds.add(snsId)
            deleteResults.remove(snsId)
            val netScene = ctor.newInstance(snsId, opType)
            WeNetSceneApi.sendNetScene(netScene)

            // 等待 onGYNetEnd 回填结果
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline) {
                val ret = deleteResults.remove(snsId)
                if (ret != null) {
                    deletingSnsIds.remove(snsId)
                    WeLogger.i(TAG, "删除 snsId=$snsId retcode=$ret")
                    return ret == 0
                }
                Thread.sleep(120)
            }
            WeLogger.w(TAG, "删除 snsId=$snsId 超时未收到结果")
            deletingSnsIds.remove(snsId)
            false
        } catch (t: Throwable) {
            WeLogger.e(TAG, "删除 snsId=$snsId 抛异常", t)
            deletingSnsIds.remove(snsId)
            false
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 设置 UI
    // ══════════════════════════════════════════════════════════════════════════

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var interval by remember { mutableStateOf(intervalInput) }
            var batchSize by remember { mutableStateOf(batchSizeInput) }
            var batchPause by remember { mutableStateOf(batchPauseInput) }
            var dailyLimit by remember { mutableStateOf(dailyLimitInput) }

            AlertDialogContent(
                title = { StyleTitle("批量删除朋友圈") },
                text = {
                    DefaultColumn(Modifier.verticalScroll(rememberScrollState())) {

                        DangerBanner(
                            title = "⚠ 使用前必读",
                            message = "· 微信对「短时间内大量删除朋友圈」有风控：会出现删除失败、" +
                                "朋友圈发布/查看受限等限制。\n" +
                                "· 默认 5 秒/条、每 20 条停 60 秒、每日上限 200 条，请勿把间隔调到 1 秒以下。\n" +
                                "· 删除不可恢复，不会进入回收站。\n" +
                                "· 首次使用需先手动删除一条朋友圈，用于学习删除参数。"
                        )

                        SoftCard(gradient = WxPalette.GradMint) {
                            SectionLabel("删除参数")
                            StyleRow(
                                title = "opType（自动学习）",
                                subtitle = if (learnedOpType >= 0) "已学习：opType=$learnedOpType"
                                else "尚未学习：请先手动删除一条自己的朋友圈",
                                trailing = {
                                    StatusPill(
                                        text = if (learnedOpType >= 0) "已就绪" else "待学习",
                                        color = if (learnedOpType >= 0) WxPalette.Primary else WxPalette.Warning,
                                        background = if (learnedOpType >= 0) WxPalette.PrimarySoft else WxPalette.WarningSoft,
                                    )
                                },
                            )
                        }

                        SoftCard(gradient = WxPalette.GradBlue) {
                            SectionLabel("扫描范围")
                            StyleRow(
                                title = "全部朋友圈",
                                subtitle = "不做任何筛选，直接列出本账号发过的所有朋友圈\n列表会按「年 / 月」分组，逐条勾选后删除",
                            )
                        }

                        SoftCard(gradient = WxPalette.GradPurple) {
                            SectionLabel("节流参数（建议保持默认）")
                            TextField(
                                value = interval,
                                onValueChange = { interval = it.filter { c -> c.isDigit() }.take(6) },
                                label = { Text("每条间隔（毫秒）") },
                                supportingText = { Text("建议 ≥ 3000，过低容易触发风控") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                            )
                            TextField(
                                value = batchSize,
                                onValueChange = { batchSize = it.filter { c -> c.isDigit() }.take(3) },
                                label = { Text("每批条数") },
                                supportingText = { Text("删除多少条后暂停一次") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                            )
                            TextField(
                                value = batchPause,
                                onValueChange = { batchPause = it.filter { c -> c.isDigit() }.take(7) },
                                label = { Text("批次暂停（毫秒）") },
                                supportingText = { Text("建议 ≥ 60000") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                            )
                            TextField(
                                value = dailyLimit,
                                onValueChange = { dailyLimit = it.filter { c -> c.isDigit() }.take(4) },
                                label = { Text("每日上限（条）") },
                                supportingText = { Text("今日已删 ${todayCount()} 条") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        intervalInput = interval.ifBlank { "5000" }
                        batchSizeInput = batchSize.ifBlank { "20" }
                        batchPauseInput = batchPause.ifBlank { "60000" }
                        dailyLimitInput = dailyLimit.ifBlank { "200" }

                        val options = DeleteOptions(
                            mode = FilterMode.BY_YEAR,
                            yearFrom = 0,
                            yearTo = 9999,
                            limit = Int.MAX_VALUE,
                            intervalMs = intervalInput.toLongOrNull()?.coerceAtLeast(1000L) ?: 5000L,
                            batchSize = batchSizeInput.toIntOrNull()?.coerceAtLeast(1) ?: 20,
                            batchPauseMs = batchPauseInput.toLongOrNull()?.coerceAtLeast(1000L) ?: 60000L,
                            dailyLimit = dailyLimitInput.toIntOrNull()?.coerceAtLeast(1) ?: 200,
                        )
                        onDismiss()
                        scanThenPick(context, options)
                    }) { Text("开始扫描") }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } }
            )
        }
    }

    /**
     * 第一步：扫描。只负责把符合条件的条目列出来，不执行任何删除。
     * 扫描在 IO 线程完成，避免长列表查询卡住 UI。
     */
    private fun scanThenPick(context: Context, options: DeleteOptions) {
        showComposeDialog(context) {
            val scope = rememberCoroutineScope()
            var loading by remember { mutableStateOf(true) }
            var items by remember { mutableStateOf<List<MomentItem>>(emptyList()) }

            LaunchedEffect(Unit) {
                val loaded = runCatching {
                    withContext(Dispatchers.IO) { loadMyMoments(options) }
                }.getOrElse {
                    WeLogger.e(TAG, "扫描朋友圈失败", it)
                    emptyList()
                }
                items = loaded
                loading = false
            }

            if (loading) {
                AlertDialogContent(
                    title = { StyleTitle("正在扫描") },
                    text = { Text("正在读取你的朋友圈列表，请稍候…") },
                    confirmButton = {},
                    dismissButton = { TextButton(onDismiss) { Text("取消") } },
                )
                return@showComposeDialog
            }

            if (items.isEmpty()) {
                AlertDialogContent(
                    title = { StyleTitle("没有可删除的朋友圈") },
                    text = { Text("当前筛选条件下没有找到你自己的朋友圈。可以返回放宽年份范围或调大数量上限。") },
                    confirmButton = { Button(onClick = onDismiss) { Text("知道了") } },
                    dismissButton = {},
                )
                return@showComposeDialog
            }

            // 第二步：列表勾选。默认全选，用户可逐条取消。
            val selected = remember { mutableStateMapOf<Long, Boolean>() }
            if (selected.isEmpty()) items.forEach { selected[it.snsId] = true }

            val selectedCount = selected.count { it.value }
            val listBg = Color(0xFFF2F2F7)
            val textMain = Color(0xFF1C1C1E)
            val textSub = Color(0xFF8E8E93)

            AlertDialogContent(
                title = {
                    Column {
                        Text(
                            text = "选择要删除的朋友圈",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = textMain,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "共 ${items.size} 条 · 已选 $selectedCount 条",
                            fontSize = 12.sp,
                            color = textSub,
                        )
                    }
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(listBg, RoundedCornerShape(18.dp))
                            .padding(10.dp),
                    ) {
                        // 工具条（白色圆角卡片）
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.White, RoundedCornerShape(12.dp))
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = selectedCount == items.size,
                                onCheckedChange = { checked ->
                                    items.forEach { selected[it.snsId] = checked }
                                },
                            )
                            Text(
                                text = "全选",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = textMain,
                            )
                            Spacer(Modifier.weight(1f))
                            Box(
                                Modifier
                                    .background(WxPalette.PrimarySoft, RoundedCornerShape(50))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = "已选 $selectedCount / ${items.size}",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = WxPalette.Primary,
                                )
                            }
                        }

                        Text(
                            text = "点卡片可切换勾选；缩略图若加载失败请以微信原朋友圈为准",
                            fontSize = 11.sp,
                            color = textSub,
                            modifier = Modifier.padding(start = 6.dp, top = 8.dp, bottom = 0.dp),
                        )

                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().height(360.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(top = 4.dp, bottom = 6.dp),
                        ) {
                            val grouped = items.groupBy { it.yearMonth }
                            grouped.forEach { (month, monthItems) ->
                                item(key = "header_$month") {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 6.dp, top = 8.dp, bottom = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = month,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = textSub,
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = "· ${monthItems.size} 条",
                                            fontSize = 11.sp,
                                            color = textSub.copy(alpha = 0.7f),
                                        )
                                    }
                                }
                                items(monthItems.size, key = { monthItems[it].snsId }) { index ->
                                    val item = monthItems[index]
                                    MomentCard(
                                        item = item,
                                        checked = selected[item.snsId] == true,
                                        onCheckedChange = { selected[item.snsId] = it },
                                        onClick = { selected[item.snsId] = selected[item.snsId] != true },
                                        onOpenDetail = { openLocalMomentDetail(context, item) },
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val picked = items.filter { selected[it.snsId] == true }
                            if (picked.isEmpty()) {
                                showToast("还没有选择任何条目")
                                return@Button
                            }
                            onDismiss()
                            confirmAndDelete(context, picked, options)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("删除选中（$selectedCount）") }
                },
                dismissButton = { TextButton(onDismiss) { Text("返回") } },
            )
        }
    }

    /**
     * 图片缩略图：自己下载 + BitmapFactory 解码。
     *
     * 不用 Coil 的原因：模块跑在微信进程里，Coil3 的 singleton ImageLoader 依赖
     * Application/网络组件初始化，失败时 AsyncImage 只会静默留一个灰框，没有任何日志，
     * 排查成本极高。这里直接用 HttpURLConnection，失败写日志，行为完全可控。
     */
    @Composable
    private fun MomentThumb(
        url: String,
        cacheKey: String,
        modifier: Modifier = Modifier,
        contentScale: ContentScale = ContentScale.Crop,
    ) {
        var bitmap by remember(cacheKey) { mutableStateOf(thumbCache.get(cacheKey)) }
        var failed by remember(cacheKey) { mutableStateOf(false) }
        LaunchedEffect(cacheKey, url) {
            if (bitmap != null) return@LaunchedEffect
            val loaded = loadBitmap(cacheKey, url)
            bitmap = loaded
            failed = loaded == null
        }
        // 加载中显示「…」，失败显示「×」，成功才画图。
        // 这样界面上不会出现「什么都没有」的灰块，也便于区分「没下载到」和「没渲染」。
        Box(modifier, contentAlignment = Alignment.Center) {
            val bmp = bitmap
            when {
                bmp != null -> Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = contentScale,
                    modifier = Modifier.fillMaxSize(),
                )
                failed -> Text("×", fontSize = 16.sp, color = Color(0xFF8E8E93))
                else -> Text("…", fontSize = 12.sp, color = Color(0xFF8E8E93))
            }
        }
    }

    @Volatile
    private var bitmapSuccessLogged = false

    /** 已加载成功的缩略图（按字节数计量），避免同一张图滚动时反复下载。 */
    private val thumbCache = object : android.util.LruCache<String, android.graphics.Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).toInt().coerceAtMost(32 * 1024 * 1024),
    ) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap): Int = value.byteCount
    }

    private suspend fun loadBitmap(
        cacheKey: String,
        url: String,
    ): android.graphics.Bitmap? = withContext(Dispatchers.IO) {
        thumbCache.get(cacheKey)?.let { return@withContext it }
        val isVideo = url.contains("snsvideodownload", ignoreCase = true)
        // 微信 CDN 有 http-only 的老域名，也有只认 https 的；强制升级会整批失败，
        // 所以两种协议都试一遍，哪个能出图用哪个。
        val variants = LinkedHashSet<String>()
        variants.add(url)
        if (url.startsWith("https://")) variants.add("http://" + url.removePrefix("https://"))
        if (url.startsWith("http://")) variants.add("https://" + url.removePrefix("http://"))

        for (candidate in variants) {
            val bmp = fetchBitmapWithRetry(candidate)
            if (bmp != null) {
                if (!bitmapSuccessLogged) {
                    bitmapSuccessLogged = true
                    WeLogger.i(
                        TAG,
                        (if (isVideo) "视频封面加载成功 " else "图片加载成功 ") +
                            "${bmp.width}x${bmp.height}：$candidate",
                    )
                }
                runCatching { thumbCache.put(cacheKey, bmp) }
                return@withContext bmp
            }
        }
        WeLogger.w(
            TAG,
            (if (isVideo) "视频封面" else "图片") + "加载全部失败（http/https 都试过）：$url",
        )
        null
    }

    /** 网络类失败重试 2 次（间隔 300/600ms）；解码失败不重试，重试也没用。 */
    private suspend fun fetchBitmapWithRetry(url: String): android.graphics.Bitmap? {
        var lastError: Throwable? = null
        repeat(MAX_IMAGE_RETRY) { attempt ->
            if (attempt > 0) kotlinx.coroutines.delay(300L * attempt)
            val result = runCatching { fetchBitmap(url) }
            if (result.isSuccess) return result.getOrNull()
            val error = result.exceptionOrNull()
            // 协程被取消（界面关闭 / item 复用）不是网络问题，直接抛出去终止重试
            if (error is kotlinx.coroutines.CancellationException) throw error
            lastError = error
            WeLogger.w(
                TAG,
                "图片请求异常(第 ${attempt + 1} 次)：$url → " +
                    "${lastError?.javaClass?.simpleName}: ${lastError?.message}",
            )
        }
        return null
    }

    /**
     * 真正下载一张图。
     *
     * 用 OkHttp 而不是 HttpURLConnection：朋友圈图片 CDN（szmmsns.qpic.cn）**校验 Referer**，
     * 不带 Referer 直接返回 HTTP 400 / 0 字节；而 Android 的 HttpURLConnection 对 Referer
     * 这类受限头不可靠（跟随重定向时会被丢），表现就是「图片暂时无法查看」。
     * 实测：带 Referer → 200 + image/png；不带 → 400 + 空 body。
     *
     * 这里刻意用同步 execute（跑在 Dispatchers.IO 的 withContext 里）而不是 enqueue +
     * suspendCancellableCoroutine：后者在 LazyColumn 复用、弹窗关闭导致协程取消时，
     * 会把 "The coroutine scope left the composition" 当成网络失败抛出来，
     * 再被重试逻辑放大，结果每张图都失败。
     */
    private suspend fun fetchBitmap(url: String): android.graphics.Bitmap? =
        withContext(Dispatchers.IO) {
            if (!threadLogged) {
                threadLogged = true
                WeLogger.i(TAG, "图片请求线程：${Thread.currentThread().name}")
            }
            val request = okhttp3.Request.Builder()
                .url(url)
                .header("Referer", "https://servicewechat.com/")
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Version/4.0 Chrome/100.0.4896.58 Mobile Safari/537.36 MicroMessenger/8.0.80",
                )
                .build()

            imageClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    WeLogger.w(TAG, "图片 HTTP ${response.code}：$url")
                    return@withContext null
                }
                val bytes = readLimited(response.body, url)
                if (bytes == null || bytes.isEmpty()) {
                    WeLogger.w(TAG, "图片响应为空（0 字节）：$url")
                    return@withContext null
                }
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap == null) {
                    val head = bytes.take(16).joinToString("") { "%02X".format(it) }
                    WeLogger.w(TAG, "图片解码失败（${bytes.size} 字节，头 $head）：$url")
                }
                bitmap
            }
        }

    /**
     * 流式读取响应体并限制大小。小程序缩略图域名（wxappthumb）直连时会返回
     * 超大二进制（实测 79MB），直接 bytes() 会把内存打爆；超过上限视为垃圾数据放弃。
     */
    private fun readLimited(body: okhttp3.ResponseBody?, url: String): ByteArray? {
        if (body == null) return null
        val input = body.byteStream()
        val out = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        try {
            var total = 0
            while (total < MAX_IMAGE_BYTES) {
                val n = input.read(chunk)
                if (n < 0) break
                out.write(chunk, 0, n)
                total += n
            }
            if (total >= MAX_IMAGE_BYTES) {
                WeLogger.w(TAG, "图片响应过大（≥${MAX_IMAGE_BYTES / 1024 / 1024}MB），放弃：$url")
                return null
            }
            return out.toByteArray()
        } finally {
            runCatching { input.close() }
        }
    }

    @Volatile
    private var threadLogged = false

    private const val MAX_IMAGE_RETRY = 3

    /** 单张图片最大字节数，超过视为垃圾响应放弃解码。 */
    private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024

    private val imageClient: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * 本地朋友圈详情。
     *
     * 不跳转微信：把这条朋友圈的正文和图片直接搬到一个浮层里看，点左上角「‹」
     * 或系统返回即可回到列表（外层列表浮层不受影响，勾选状态全部保留）。
     */
    private fun openLocalMomentDetail(context: Context, item: MomentItem) {
        showComposeDialog(context) {
            AlertDialogContent(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "‹",
                                fontSize = 26.sp,
                                fontWeight = FontWeight.Bold,
                                color = WxPalette.Primary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onDismiss() }
                                    .padding(end = 8.dp, bottom = 2.dp),
                            )
                            Text(
                                text = "朋友圈详情",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1C1C1E),
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = item.createTimeText +
                                (if (item.kind.isNotEmpty()) " · ${item.kind}" else ""),
                            fontSize = 12.sp,
                            color = Color(0xFF8E8E93),
                        )
                    }
                },
                text = { MomentDetailBody(item) },
                confirmButton = {
                    Button(
                        onClick = { onDismiss() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("返回列表") }
                },
                dismissButton = {},
            )
        }
    }

    /** 详情正文区：文字全文 + 竖排大图，整块可滚动。 */
    @Composable
    private fun MomentDetailBody(item: MomentItem) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (item.preview.isBlank()) {
                Text("（这条朋友圈没有文字）", fontSize = 13.sp, color = Color(0xFF8E8E93))
            } else {
                Text(
                    text = item.preview,
                    fontSize = 15.sp,
                    lineHeight = 23.sp,
                    color = Color(0xFF1C1C1E),
                )
            }

            if (item.images.isEmpty()) {
                Text(
                    text = if (item.imageCount > 0) {
                        "这条含 ${item.imageCount} 张图片/视频，但没能取到可预览的地址"
                    } else "（没有可预览的图片）",
                    fontSize = 13.sp,
                    color = Color(0xFF8E8E93),
                )
            } else {
                item.images.forEach { url ->
                    MomentThumb(
                        url = url,
                        cacheKey = "${item.snsId}_$url",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFF2F2F7)),
                    )
                }
            }
        }
    }

    /**
     * 单条朋友圈卡片：日期 + 类型胶囊 + 正文两行，左侧勾选、右侧箭头提示可点。
     * 选中态用极浅的品牌色底，未选中是纯白卡片，靠灰底衬托层次。
     */
    @Composable
    private fun MomentCard(
        item: MomentItem,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
        onClick: () -> Unit,
        onOpenDetail: () -> Unit,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(if (checked) WxPalette.PrimarySoft else Color.White)
                .clickable(onClick = onClick)
                .padding(start = 2.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = onCheckedChange)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.createTimeText,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF1C1C1E),
                    )
                    if (item.kind.isNotEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            Modifier
                                .background(WxPalette.PrimarySoft, RoundedCornerShape(50))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = item.kind,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color = WxPalette.Primary,
                            )
                        }
                    }
                }
                if (item.preview.isNotBlank()) {
                    Text(
                        text = item.preview,
                        fontSize = 12.sp,
                        color = Color(0xFF3A3A3C),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                } else if (item.images.isEmpty()) {
                    Text(
                        text = when {
                            item.imageCount > 0 -> "（含 ${item.imageCount} 张图片/视频，暂无预览）"
                            item.kind == "小程序" -> "（小程序卡片，暂不支持预览）"
                            item.kind.isNotEmpty() -> "（${item.kind}，暂无预览）"
                            else -> "（无文字内容）"
                        },
                        fontSize = 12.sp,
                        color = Color(0xFF8E8E93),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }

                if (item.images.isEmpty() && item.imageCount > 0) {
                    Spacer(Modifier.height(5.dp))
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFFE5E5EA)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("图×${item.imageCount}", fontSize = 10.sp, color = Color(0xFF8E8E93))
                    }
                }
                // 缩略图：直接复用微信自己的 CDN 地址，Coil 走 OkHttp 拉取并做内存/磁盘缓存。
                // 加载失败时保持浅灰底色块，不影响勾选与删除。
                if (item.images.isNotEmpty()) {
                    Spacer(Modifier.height(5.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        item.images.forEach { url ->
                            MomentThumb(
                                url = url,
                                cacheKey = "${item.snsId}_$url",
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFFE5E5EA)),
                            )
                        }
                    }
                }
            }
            // 点这个箭头看详情（本地浮层，不跳转微信）。
            Text(
                text = "›",
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFFC7C7CC),
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onOpenDetail)
                    .padding(start = 12.dp, end = 2.dp, top = 8.dp, bottom = 8.dp),
            )
        }
    }

    /** 第三步：最终确认，展示条数与样例，确认后才真正开始删除。 */
    private fun confirmAndDelete(context: Context, picked: List<MomentItem>, options: DeleteOptions) {
        val sample = picked.take(3).joinToString("\n") { "· ${it.createTimeText}  ${it.preview}" }
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text("确认删除这 ${picked.size} 条") },
                text = {
                    Text(
                        "删除后不可恢复，且不会进入回收站。\n\n" +
                            "前 3 条：\n$sample\n\n" +
                            "间隔 ${options.intervalMs / 1000}s / 每 ${options.batchSize} 条暂停 " +
                            "${options.batchPauseMs / 1000}s / 今日已删 ${todayCount()} 条。\n\n" +
                            "任务在后台执行，关闭对话框不会中断。"
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        onDismiss()
                        val started = startDelete(
                            items = picked,
                            options = options,
                            onProgress = { done, total, message ->
                                if (done % 10 == 0 || done == total) {
                                    WeLogger.i(TAG, "进度 $done/$total：$message")
                                }
                            },
                            onFinished = { report ->
                                WeLogger.i(
                                    TAG,
                                    "批量删除完成：成功 ${report.success} / 失败 ${report.failed} / " +
                                        "跳过 ${report.skipped} / 中止=${report.cancelled}"
                                )
                                runCatching {
                                    showToast(
                                        "批量删除结束：成功 ${report.success}，失败 ${report.failed}" +
                                            if (report.cancelled) "（已中止）" else ""
                                    )
                                }
                            }
                        )
                        if (started) showToast("已开始删除 ${picked.size} 条，可在日志查看进度")
                    }) { Text("确认删除") }
                },
                dismissButton = { TextButton(onDismiss) { Text("再想想") } }
            )
        }
    }
}
