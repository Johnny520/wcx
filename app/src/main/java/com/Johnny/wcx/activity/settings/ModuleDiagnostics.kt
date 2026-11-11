package com.Johnny.wcx.activity.settings

import android.content.Context
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.core.FeaturesProvider
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.constants.Preferences
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.features.items.chat.AntiMessageRecall
import com.Johnny.wcx.features.items.moments.BatchDeleteMoments
import com.Johnny.wcx.utils.WeLogger
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * 各诊断页共用的「深度探针」。
 *
 * 设计原则：
 * 1. 只读 —— 不改开关、不写配置，纯观测；
 * 2. 全 runCatching —— 单项异常只影响自身结论，绝不拖垮整页自检；
 * 3. 网络项全部带超时（连接 3s / 读 8s），条数固定，避免卡住 UI。
 *
 * 使用方：模块自检（全量）、隐藏联系人诊断（只看 contacts 相关锚点）、
 * 朋友圈批量删除（解析统计与图片地址）。
 */
internal object ModuleDiagnostics {

    private const val TAG = "诊断探针"

    // ===================== 1. DexKit 锚点健康 =====================

    /** 一个 Dex 委托（锚点）的运行时状态。 */
    data class Anchor(
        val feature: String,
        val key: String,
        /** 是否已解析出描述符（= DexKit 匹配成功、结果已缓存）。 */
        val resolved: Boolean,
        /** 所属功能是否已真正装上 Hook（运行时真值）。 */
        val active: Boolean,
        val descriptor: String?,
    )

    /**
     * 汇总全部已注册的 Dex 锚点。
     *
     * [filter] 让各页面只取自己关心的部分（如隐藏联系人页只看 contacts / chat 相关）。
     * 返回的是「缓存状态」，不会触发 DexKit 重新扫描，因此可安全在 UI 线程调用。
     */
    fun anchors(filter: (feature: String, key: String) -> Boolean = { _, _ -> true }): List<Anchor> {
        val out = mutableListOf<Anchor>()
        runCatching {
            FeaturesProvider.ALL_HOOK_ITEMS.forEach { feature ->
                val featureName = runCatching { feature.name }.getOrDefault("未命名功能")
                val active = runCatching { feature.isActive }.getOrDefault(false)
                val delegates = runCatching { feature.dexDelegates }.getOrDefault(emptyList())
                delegates.forEach lit@{ delegate ->
                    val key = runCatching { delegate.key }.getOrDefault("未命名锚点")
                    if (!filter(featureName, key)) return@lit
                    val descriptor = runCatching { delegate.getDescriptorString() }.getOrNull()
                    out += Anchor(
                        feature = featureName,
                        key = key,
                        resolved = descriptor != null,
                        active = active,
                        descriptor = descriptor,
                    )
                }
            }
        }.onFailure { WeLogger.w(TAG, "锚点汇总失败：" + (it.message ?: it.javaClass.simpleName)) }
        return out
    }

    /** 统计：已解析 / 总数。 */
    fun anchorStats(anchors: List<Anchor>): Pair<Int, Int> =
        anchors.count { it.resolved } to anchors.size

    // ===================== 2. 冲突模块 =====================

    /** 已知会 Hook / 增强微信的模块包名 → 展示名。 */
    /**
     * 包名分两组：框架管理器只是「环境信息」，不算冲突；
     * 其它会 Hook 微信的模块才可能和 WCX 抢同一批方法。
     *
     * 只收录能确认存在的包名，不猜。新增条目必须同步 AndroidManifest 的 <queries>，
     * 否则 Android 11+ 查不到、永远显示「未安装」。
     */
    /** Xposed 框架管理器（Root 系）。 */
    private val XPOSED_MANAGERS = linkedMapOf(
        "io.github.lsposed.manager" to "LSPosed",
        "org.lsposed.manager" to "LSPosed（旧包名）",
        "org.meowcat.edxposed.manager" to "EdXposed",
        "com.solohsu.android.edxp.manager" to "EdXposed（旧管理器）",
        "de.robv.android.xposed.installer" to "Xposed Installer",
        "top.canyie.dreamland.manager" to "Dreamland",
        "me.weishu.exp" to "太极（Root/免 Root 双模式）",
    )

    /** Root 管理器：本身不 Hook 微信，但决定了 Xposed 框架跑在哪套 Root 上。 */
    private val ROOT_MANAGERS = linkedMapOf(
        "com.topjohnwu.magisk" to "Magisk",
        "io.github.huskydg.magisk" to "Magisk Delta（旧分支）",
        "me.weishu.kernelsu" to "KernelSU",
        "me.bmax.apatch" to "APatch",
        "com.sukisu.ultra" to "SukiSU Ultra",
        "org.sukisu.manager" to "SukiSU",
    )

    /** 免 Root 方案（内置注入 / 虚拟容器）。 */
    private val ROOTLESS_SOLUTIONS = linkedMapOf(
        "org.lsposed.lspatch" to "LSPatch",
        "io.va.exposed" to "VirtualXposed",
        "io.va.exposed32" to "VirtualXposed 32 位",
        "io.va.exposed64" to "VirtualXposed 64 位",
    )

    private val FRAMEWORK_PACKAGES: Map<String, String>
        get() = XPOSED_MANAGERS + ROOT_MANAGERS + ROOTLESS_SOLUTIONS

    private val MODULE_PACKAGES = linkedMapOf(
        "com.fkzhang.wechatxposed" to "微X模块",
        "com.fkzhang.wechatxposed.pro" to "微X模块 Pro",
        "me.hd.wauxv" to "WAuxv",
        "com.tencent.mm.wechatx" to "WeChatX",
        "top.mmjz.floatingclouds" to "浮云",
        "com.wcxlc.kimjaejiang" to "WCXLC（同源二改）",
        "com.johnny.wcx" to "WCX（当前模块）",
    )

    val knownModuleCount: Int get() = FRAMEWORK_PACKAGES.size + MODULE_PACKAGES.size

    data class InstalledModule(val pkg: String, val label: String, val version: String)

    private fun scan(context: Context, table: Map<String, String>): List<InstalledModule> = runCatching {
        val pm = context.packageManager
        table.mapNotNull { (pkg, label) ->
            runCatching {
                val info = pm.getPackageInfo(pkg, 0)
                InstalledModule(pkg, label, info.versionName ?: "?")
            }.getOrNull()
        }
    }.getOrElse {
        WeLogger.w(TAG, "扫描包信息失败：" + (it.message ?: it.javaClass.simpleName))
        emptyList()
    }

    /** 已安装的 Xposed 框架管理器（环境信息，不是冲突）。 */
    fun installedFrameworks(context: Context): List<InstalledModule> = scan(context, FRAMEWORK_PACKAGES)

    /**
     * 已安装的其它微信 Hook 模块（排除 WCX 自身）。
     * Android 11 起需要 AndroidManifest 的 <queries> 声明包名才能看到；
     * 未声明时 getPackageInfo 抛 NameNotFoundException，这里直接视为「未安装」，不会误报。
     */
    fun installedModules(context: Context): List<InstalledModule> =
        scan(context, MODULE_PACKAGES).filter { it.pkg != "com.johnny.wcx" }

        // ===================== 2.5 注入方式识别 =====================

    data class InjectionInfo(val method: String, val kind: String, val detail: String)

    /**
     * 运行时识别当前注入方式 —— 模块自己就跑在注入环境里，直接感知比扫管理器包名准：
     * 1. LSPatch：宿主 APK 内嵌 assets/lspatch/ 目录，或类加载器链里有 LSPatch Loader；
     * 2. 免 Root 虚拟容器（VirtualXposed / 太极免 Root）：微信的 dataDir 落在容器的沙箱路径里；
     * 3. 回退：按已装管理器推断 Root 系。
     */
    fun detectInjection(context: Context): InjectionInfo {
        val app = runCatching { HostInfo.application }.getOrNull()
        if (app != null) {
            val source = runCatching { app.applicationInfo.sourceDir }.getOrNull()
            if (source != null && zipHasEntryPrefix(source, "assets/lspatch/")) {
                return InjectionInfo(
                    "LSPatch（免 Root · APK 内置）", "免 Root",
                    "宿主 APK 内嵌了 LSPatch 注入文件：模块被直接打进微信安装包。" +
                        "更新模块后必须用 LSPatch 重新打包安装，直接装普通 APK 不会生效。",
                )
            }
            var cl: ClassLoader? = runCatching { app.classLoader }.getOrNull()
            while (cl != null) {
                if (cl.javaClass.name.contains("lspatch", ignoreCase = true)) {
                    return InjectionInfo(
                        "LSPatch（免 Root · APK 内置）", "免 Root",
                        "类加载器链中发现 LSPatch Loader。更新模块后必须用 LSPatch 重新打包安装。",
                    )
                }
                cl = cl.parent
            }
            val dataDir = runCatching { app.applicationInfo.dataDir }.getOrNull() ?: ""
            if (dataDir.contains("io.va.exposed")) {
                return InjectionInfo(
                    "VirtualXposed（免 Root · 虚拟容器）", "免 Root",
                    "微信运行在 VirtualXposed 虚拟容器内（dataDir=" + dataDir + "）。" +
                        "虚拟容器对 Xposed API 的兼容有限，功能异常时优先怀疑容器环境。",
                )
            }
            if (dataDir.contains("me.weishu")) {
                return InjectionInfo(
                    "太极（虚拟容器）", "免 Root / Root",
                    "微信运行在太极的虚拟环境内（dataDir=" + dataDir + "）。" +
                        "太极免 Root 模式走虚拟容器；配合 Magisk 模块则是 Root 模式。",
                )
            }
            // 泛化识别：类加载器链上出现已知框架关键词，即使管理器不在清单里也能认出来
            val loaderHits = detectViaClassLoader()
            if (loaderHits.isNotEmpty()) {
                return InjectionInfo(
                    "自动识别（类加载器特征）", "自动识别",
                    "类加载器链上命中框架特征：\n" + loaderHits.joinToString("\n") +
                        "\n\n这是清单之外的框架——把本条复制反馈给开发者，可以把它加进识别列表。",
                )
            }
        }
        // LSPosed（或兼容框架）成功注入过微信时，会把 API 版本写进跨进程缓存。
        // 缓存有值 = 有 Root 系 LSPosed 兼容框架在工作，即使管理器包名不在清单里也能认出来。
        val cachedApi = runCatching {
            WePrefs.getIntOrDef(Preferences.CACHED_LSP_API_VERSION, 0)
        }.getOrDefault(0)
        if (cachedApi > 0) {
            return InjectionInfo(
                "LSPosed 兼容框架（API " + cachedApi + "）", "Root",
                "微信进程内曾成功加载 LSPosed API " + cachedApi + "（来自跨进程缓存）。" +
                    "该框架本身不在识别列表里——把本条复制反馈给开发者补全。",
            )
        }
        val frameworks = installedFrameworks(context)
        return when {
            frameworks.any { it.label.contains("LSPatch") } ->
                InjectionInfo("LSPatch（免 Root）", "免 Root", "检测到已安装 LSPatch 管理器。")
            frameworks.any { it.label.contains("太极") } ->
                InjectionInfo("太极", "Root / 免 Root", "检测到已安装太极（Root 与免 Root 双版本共用此包名集合）。")
            frameworks.isNotEmpty() ->
                InjectionInfo(
                    frameworks.first().label + "（Root）", "Root",
                    "检测到 " + frameworks.joinToString("、") { it.label } +
                        "，模块经 Root 框架注入，更新模块后重启微信即可生效。",
                )
            else -> InjectionInfo(
                "未识别", "未知",
                "运行时未发现免 Root 注入特征，也没检测到已知框架管理器。" +
                    "如果模块功能一切正常，说明用的是识别列表之外的方案（如修改版安装包自带的注入器），" +
                    "欢迎把这段复制反馈给开发者补全识别列表。",
            )
        }
    }

    /** 只遍历 zip 条目名（读中央目录），不读内容，对大 APK 成本可忽略。 */
    private fun zipHasEntryPrefix(archivePath: String, prefix: String): Boolean = runCatching {
        java.util.zip.ZipFile(archivePath).use { zf ->
            zf.entries().asSequence().any { it.name.startsWith(prefix) }
        }
    }.getOrDefault(false)

    /**
     * 泛化识别关键词：覆盖已知框架与它们的底层 hook 引擎。
     * 命中即报告具体 loader 类名 —— 清单之外的框架也能自动认出来。
     */
    private val LOADER_KEYWORDS = listOf(
        "lspatch", "lsposed", "edxposed", "xposed", "taichi", "weishu",
        "dreamland", "pine", "sandhook", "epic", "whale", "zygisk",
    )

    /** 沿类加载器父链扫描框架特征，返回命中描述。 */
    fun detectViaClassLoader(): List<String> {
        val out = mutableSetOf<String>()
        var cl: ClassLoader? = runCatching { HostInfo.application.classLoader }.getOrNull()
        var depth = 0
        while (cl != null && depth < 8) {
            val name = cl.javaClass.name
            LOADER_KEYWORDS.firstOrNull { name.contains(it, ignoreCase = true) }?.let {
                out += "loader「" + name + "」（关键词 " + it + "）"
            }
            cl = cl.parent
            depth++
        }
        return out.toList()
    }

    // ===================== 3. 网络探针 =====================

    /**
     * 一条网络探针结论。
     *
     * [expectFail] = true 表示「失败才是预期结果」（例如不带 Referer 应当被 CDN 拒绝），
     * UI 上不计入异常。
     */
    data class NetResult(
        val label: String,
        val ok: Boolean,
        val detail: String,
        val expectFail: Boolean = false,
    )

    private val IMAGE_HOSTS = listOf(
        "szmmsns.qpic.cn",
        "shmmsns.qpic.cn",
        "wxappthumb.tc.qq.com",
        "mmbiz.qpic.cn",
    )

    private val probeClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /**
     * 网络与 CDN 体检。
     *
     * [imageUrl] 建议传朋友圈实际枚举到的图片地址；为空时会明确提示「没有可用的测试地址」，
     * 不会伪造结论。
     */
    /**
     * [withVpnCheck] 传 true 时额外检测 VPN 网络（ConnectivityManager 的 TRANSPORT_VPN）。
     * VPN / 全局代理是「图片 403」最常见的环境原因，这里直接给出对应解释，
     * 而不是只丢一句「异常」让用户猜。
     */
    fun network(context: Context, imageUrl: String?): List<NetResult> {
        val out = mutableListOf<NetResult>()

        val vpnOn = runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            cm.allNetworks.any { net ->
                val caps = cm.getNetworkCapabilities(net)
                caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
            }
        }.getOrDefault(false)
        out += NetResult(
            "VPN 网络",
            true,
            if (vpnOn) "检测到 VPN 正在运行。VPN 会接管全部流量，微信图片 CDN 在部分线路下会 403 / 超时；" +
                "如果图片显示不出来，先关掉 VPN，或把微信加进 VPN 的「分应用排除」名单。"
            else "未检测到 VPN",
            expectFail = vpnOn,
        )

        val proxyHost = System.getProperty("http.proxyHost")
        val proxyPort = System.getProperty("http.proxyPort")
        out += if (proxyHost.isNullOrBlank()) {
            NetResult("系统代理", true, "未启用")
        } else {
            NetResult(
                "系统代理",
                true,
                "检测到系统级 HTTP 代理 " + proxyHost + ":" + (proxyPort ?: "?") +
                    "。全局代理会把微信请求也带进代理通道，CDN 可能返回 403；" +
                    "如果是 VPN 应用设置的代理，把微信加入「分应用排除」名单即可。属提示，不算故障。",
                expectFail = true,
            )
        }

        IMAGE_HOSTS.forEach { host ->
            val started = System.currentTimeMillis()
            val ok = runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, 443), 3000)
                    true
                }
            }.getOrDefault(false)
            val cost = System.currentTimeMillis() - started
            out += NetResult(
                "连接 " + host,
                ok,
                if (ok) "成功（" + cost + "ms）" else "失败或超时（" + cost + "ms）—— DNS 或网络层面就不通",
            )
        }

        if (imageUrl.isNullOrBlank()) {
            out += NetResult(
                "图片实测",
                false,
                "没有可用的测试地址：先打开一次「批量删除朋友圈」，枚举到图片后这里会自动带上实测地址",
                expectFail = true,
            )
        } else {
            val withRef = httpProbe(imageUrl, true)
            out += NetResult("图片·带 Referer", withRef.first, withRef.second)

            val withoutRef = httpProbe(imageUrl, false)
            out += NetResult(
                "图片·不带 Referer",
                false,
                if (withoutRef.first) "HTTP 200 —— CDN 未校验 Referer（对加载无影响）"
                else withoutRef.second + "（期望 400：说明 CDN 校验 Referer，属正常）",
                expectFail = true,
            )
        }
        return out
    }

    private fun httpProbe(url: String, withReferer: Boolean): Pair<Boolean, String> = runCatching {
        val builder = okhttp3.Request.Builder().url(url)
        if (withReferer) builder.header("Referer", "https://servicewechat.com/")
        probeClient.newCall(builder.build()).execute().use { response ->
            val length = response.body?.contentLength() ?: -1L
            val codeText = "HTTP " + response.code
            val sizeText = if (length >= 0) "，" + length + " 字节" else ""
            response.isSuccessful to (codeText + sizeText)
        }
    }.getOrElse {
        false to ("请求异常：" + (it.message ?: it.javaClass.simpleName))
    }

    // ===================== 4. 功能活体 =====================

    data class Live(val label: String, val ok: Boolean, val detail: String, val inactive: Boolean = false)

    /**
     * 功能活体检测：不看开关状态，看这套能力在当前微信版本上是否真的能跑。
     *
     * 全部基于运行时真值（已装 Hook / 已解析锚点 / 实际解析出的数据），
     * 拿不到数据时如实说明「尚未采集」，不猜。
     */
    fun featureLive(): List<Live> {
        val out = mutableListOf<Live>()

        val dbReady = runCatching { WeDatabaseApi.isReady }.getOrDefault(false)
        out += Live(
            "微信数据库",
            dbReady,
            if (dbReady) "已就绪：会话 / 消息 / 联系人可读写"
            else "未就绪：所有依赖数据库的功能（隐藏联系人、消息处理等）都会失效",
        )

        val send = runCatching { WeMessageApi.sendTextDiagnostics() }.getOrNull()
        out += Live(
            "发送文本消息",
            send != null && !send.contains("失败"),
            (send ?: "自检调用失败（异常已吞，详见运行日志）").take(300),
        )

        val stats = BatchDeleteMoments.lastParseStats
        out += Live(
            "朋友圈解析",
            stats != null,
            stats ?: "尚未采集（不是故障）：本次微信进程还没打开过「批量删除朋友圈」，" +
                "打开一次列表就会自动采集解析结果并更新此状态",
            inactive = stats == null,
        )

        val recall = runCatching { AntiMessageRecall.isActive }.getOrDefault(false)
        out += Live(
            "防撤回",
            recall,
            if (recall) "Hook 已安装，可拦截撤回"
            else "Hook 未安装：功能未开启，或锚点已随微信版本漂移",
        )

        // ---- 功能回执：不看开关，看「开了之后到底有没有生效」 ----
        // 逐个已开启的功能核对真实运行状态：
        // ① 功能自己回报的运行时问题（注入失败、采集为空、找不到注入容器等）
        // ② 开关开着但 Hook 根本没装上（enable() 安装失败会回滚 isActive，日志里有堆栈）
        runCatching {
            FeaturesProvider.ALL_HOOK_ITEMS.forEach { feature ->
                if ((feature as? com.Johnny.wcx.features.core.SwitchFeature)?.isEnabled != true) return@forEach
                val label = feature.categories.firstOrNull()?.takeIf { it.isNotBlank() }
                    ?.let { "$it/${feature.name}" } ?: feature.name

                val issue = runCatching { feature.runtimeIssue() }.getOrNull()
                if (issue != null) {
                    out += Live(label, false, issue)
                    return@forEach
                }

                if (!feature.isActive) {
                    out += Live(
                        label, false,
                        "开关是开的，但 Hook 没有安装成功（启动阶段失败，或当前进程不安装 Hook），" +
                            "所以点了不会有任何反应。运行日志里搜「${feature.name}」能看到失败原因。",
                    )
                }
            }
        }

        return out
    }
}
