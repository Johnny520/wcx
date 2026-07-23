//package com.Johnny.wcx.features.items.beautify.monet
//
//import android.annotation.SuppressLint
//import android.content.res.Resources
//import android.os.Build
//import com.reandroid.apk.ApkModule
//import com.reandroid.arsc.chunk.PackageBlock
//import com.Johnny.wcx.constants.PackageNames
//import com.Johnny.wcx.utils.HostInfo
//import com.Johnny.wcx.utils.WeLogger
//import java.io.File
//
///**
// * 在运行时把参考 overlay 模板改造成与当前微信版本匹配的 RRO overlay APK。
// *
// * ## 原理
// * RRO (Runtime Resource Overlay) 按 **资源名** 与目标包 (微信) 匹配, 命中则在运行时替换其值,
// * 无 idmap 时忽略未匹配的名字。模板里的颜色项全部引用 `@android:color/system_*` 框架动态色 (id 跨
// * 版本稳定) 或字面色, 从不引用微信自身的 id, 因此:
// * - 模板中某个颜色名如果在当前微信里存在 → 会被莫奈色覆盖;
// * - 不存在 → 被系统忽略, 无副作用。
// *
// * ## 为什么要运行时改造而不是直接用模板
// * 微信大量使用极短的混淆资源名 (如 `m`/`b`/`i`), 这些名字在不同版本里语义完全不同 (`m` 在 Play 版是
// * 白色, 在标准版 8.0.69 却是品牌绿)。直接套用模板会把莫奈色错误地应用到不相干的资源上。因此本类:
// * 1. 逐一校验模板里每个颜色名在 **当前微信** 中的原始色值是否与参考意图一致, 不一致就 **删除** 该项;
// * 2. 针对当前版本里被混淆的品牌/强调色, 按色值搜索其真实名字并 **新增** 覆盖项。
// */
//class MonetOverlayBuilder(
//    private val tables: MonetTables,
//    private val templateApk: File,
//    private val isNightCapable: Boolean = true,
//) {
//
//    private val hostRes: Resources = HostInfo.application.resources
//    private val hostPkg: String = HostInfo.packageName
//
//    /** 已解析的框架色名 -> id 缓存 (system_primary_light 等)。 */
//    private val frameworkIdCache = HashMap<String, Int>()
//
//    /** 统计信息, 供 UI 展示。 */
//    data class Result(
//        val outputApk: File,
//        val kept: Int,
//        val pruned: Int,
//        val added: Int,
//    )
//
//    fun build(outputApk: File): Result {
//        val apk = ApkModule.loadApkFile(templateApk)
//        val pkg = apk.tableBlock.pickOne()
//            ?: error("overlay template has no resource package")
//
//        val table = resolveTable()
//        var kept = 0
//        var pruned = 0
//        var added = 0
//
//        // 1) 遍历模板中的每个颜色项, 校验/裁剪。
//        val templateColorNames = collectColorNames(pkg)
//        for (name in templateColorNames) {
//            val rule = table.colors[name]
//            if (rule == null) {
//                // 模板里有、但当前版本表里没有的名字: 保守起见删除, 避免误伤。
//                // (品牌色的语义名如 Brand_100 会在表里, 不会被误删。)
//                if (pruneColor(pkg, name)) pruned++
//                continue
//            }
//            if (verifyLiveValue(name, rule)) {
//                kept++
//            } else {
//                if (pruneColor(pkg, name)) pruned++
//            }
//        }
//
//        // 2) 新增当前版本表里、但模板中不存在的名字 (通常是被混淆的品牌/强调色)。
//        for ((name, rule) in table.colors) {
//            if (name in templateColorNames) continue
//            if (!verifyLiveValue(name, rule)) continue
//            if (addColor(pkg, name, rule)) added++
//        }
//
//        apk.apkSignatureBlock = null
//        outputApk.parentFile?.mkdirs()
//        apk.writeApk(outputApk)
//
//        WeLogger.i(TAG, "overlay built: kept=$kept pruned=$pruned added=$added -> $outputApk")
//        return Result(outputApk, kept, pruned, added)
//    }
//
//    /** 选择与当前微信 versionCode 精确匹配的表, 否则回退到 generic 语义表。 */
//    private fun resolveTable(): MonetVersionTable {
//        val vc = HostInfo.versionCode.toString()
//        tables.versions[vc]?.let {
//            WeLogger.i(TAG, "using exact table for versionCode=$vc (${it.colors.size} colors)")
//            return it
//        }
//        WeLogger.w(TAG, "no exact table for versionCode=$vc, building from generic + brandByValue")
//        return buildGenericTable()
//    }
//
//    /**
//     * 未知版本兜底: 参考语义表 (按名 + 期望值校验) ∪ 品牌色按色值发现 ∪ 背景色按色对发现。
//     * 具体的值校验推迟到 [verifyLiveValue] / [addColor] 时进行。
//     */
//    private fun buildGenericTable(): MonetVersionTable {
//        val colors = HashMap<String, MonetColorRule>()
//        // 参考表里的语义名 (Brand_100/Link_100/BW_100 等), 期望值一并带上供校验。
//        colors.putAll(tables.generic)
//        // 按色值/色对发现被混淆的品牌色与背景色 (扫描当前微信 arsc)。
//        colors.putAll(discoverColorsByValue())
//        return MonetVersionTable(colors)
//    }
//
//    /**
//     * 扫描当前微信 arsc 的所有颜色资源, 按色值发现被混淆的语义色:
//     * - 品牌/强调色: default 配置色值命中 [MonetTables.brandByValue] (语义唯一, 安全)。
//     * - 背景色: (浅色值, 深色值) 命中 [MonetTables.surfByPair] (翻转色对可证明是背景, 安全)。
//     *
//     * 仅用于未知版本兜底; 已知版本走 [MonetTables.versions] 精确表, 不会进入此路径。
//     */
//    private fun discoverColorsByValue(): Map<String, MonetColorRule> {
//        if (tables.brandByValue.isEmpty() && tables.surfByPair.isEmpty()) return emptyMap()
//        val brandByValue = HashMap<Long, MonetColorRule>()
//        for ((v, rule) in tables.brandByValue) {
//            normalizeColor(v)?.let { brandByValue[it] = rule }
//        }
//        // surfByPair 键为 "lightArgb|nightArgb"。
//        val surfByPair = HashMap<Pair<Long, Long>, MonetColorRule>()
//        for ((k, rule) in tables.surfByPair) {
//            val parts = k.split('|')
//            if (parts.size != 2) continue
//            val l = normalizeColor(parts[0]) ?: continue
//            val n = normalizeColor(parts[1]) ?: continue
//            surfByPair[l to n] = rule
//        }
//
//        val result = HashMap<String, MonetColorRule>()
//        val hostArsc = loadHostColorArgb() ?: return result
//        for ((name, lightNight) in hostArsc) {
//            val (light, night) = lightNight
//            brandByValue[light]?.let { result[name] = it }
//            if (name !in result) surfByPair[light to night]?.let { result[name] = it }
//        }
//        WeLogger.i(TAG, "discovered ${result.size} colored names by value from host arsc")
//        return result
//    }
//
//    /**
//     * 读取当前微信 APK 的 arsc, 返回 (颜色资源名 -> (浅色 ARGB, 深色 ARGB))。
//     * 仅解析能直接得到 COLOR_ARGB8 的项 (品牌/背景色都是字面色, 满足条件)。
//     * 深色取 `-night` 配置, 无则回退到浅色值。
//     */
//    private fun loadHostColorArgb(): Map<String, Pair<Long, Long>>? {
//        val sourceDir = HostInfo.appInfo.sourceDir ?: return null
//        return runCatching {
//            // 只抽取 resources.arsc, 避免加载数百 MB 的微信 APK。
//            val arscBytes = java.util.zip.ZipFile(sourceDir).use { zip ->
//                val entry = zip.getEntry("resources.arsc")
//                    ?: error("resources.arsc not found in $sourceDir")
//                zip.getInputStream(entry).use { it.readBytes() }
//            }
//            val table = com.reandroid.arsc.chunk.TableBlock.load(arscBytes.inputStream())
//            val light = HashMap<String, Long>()
//            val night = HashMap<String, Long>()
//            for (pkg in table.listPackages()) {
//                val res = pkg.getResources("color")
//                while (res.hasNext()) {
//                    val re = res.next()
//                    val name = re.name ?: continue
//                    val entries = pkg.getEntries(re.resourceId)
//                    while (entries.hasNext()) {
//                        val entry = entries.next() ?: continue
//                        if (entry.isNull) continue
//                        if (entry.valueType != com.reandroid.arsc.value.ValueType.COLOR_ARGB8) continue
//                        val argb = entry.resValue.data.toLong() and 0xFFFFFFFFL
//                        val qualifiers = entry.resConfig?.qualifiers.orEmpty()
//                        if (qualifiers.contains("-night")) night[name] = argb
//                        else if (qualifiers.isEmpty()) light[name] = argb
//                    }
//                }
//            }
//            val out = HashMap<String, Pair<Long, Long>>()
//            for ((name, l) in light) {
//                out[name] = l to (night[name] ?: l)
//            }
//            out
//        }.onFailure { WeLogger.w(TAG, "failed to read host arsc for color discovery", it) }
//            .getOrNull()
//    }
//
//    /** 收集包内所有 color 资源名 (default 配置下非空)。 */
//    private fun collectColorNames(pkg: PackageBlock): Set<String> {
//        val names = LinkedHashSet<String>()
//        val it = pkg.getResources("color")
//        while (it.hasNext()) {
//            val re = it.next()
//            val name = re.name ?: continue
//            names.add(name)
//        }
//        return names
//    }
//
//    /**
//     * 校验某个覆盖名在当前微信中的原始色值是否与规则期望一致。
//     * - 若规则未带期望值 (exact 表), 只要求该名字在微信中存在。
//     * - 若带期望值 (generic 表), 要求 default 配置下的色值匹配, 防止混淆名漂移误伤。
//     */
//    private fun verifyLiveValue(name: String, rule: MonetColorRule): Boolean {
//        val id = hostColorId(name) ?: return false
//        val expected = rule.expectedValue ?: return true
//        val expectedArgb = normalizeColor(expected) ?: return true
//        val live = runCatching { hostRes.getColor(id, null) }.getOrNull() ?: return false
//        return live.toLong() and 0xFFFFFFFFL == expectedArgb
//    }
//
//    @SuppressLint("DiscouragedApi")
//    private fun hostColorId(name: String): Int? {
//        val id = hostRes.getIdentifier(name, "color", hostPkg)
//        return if (id != 0) id else null
//    }
//
//    /** 将 `#aarrggbb` / `#rrggbb` 归一化为无符号 ARGB long。 */
//    private fun normalizeColor(s: String): Long? {
//        val hex = s.trim().removePrefix("#")
//        val full = when (hex.length) {
//            6 -> "ff$hex"
//            8 -> hex
//            else -> return null
//        }
//        return full.toLongOrNull(16)
//    }
//
//    /** 删除一个颜色项 (所有配置置空), 使其不再与微信匹配。返回是否确有删除。 */
//    private fun pruneColor(pkg: PackageBlock, name: String): Boolean {
//        val re = pkg.getResource("color", name) ?: return false
//        var any = false
//        val it = pkg.getEntries(re.resourceId)
//        while (it.hasNext()) {
//            val e = it.next() ?: continue
//            if (!e.isNull) {
//                e.isNull = true
//                any = true
//            }
//        }
//        return any
//    }
//
//    /**
//     * 新增一个颜色覆盖项。目标只会是简单的框架引用 (primary/accent 系列, 见分析), 因此:
//     * 克隆一个已有的同目标框架引用项作为模板, 改名并指向目标框架色 id。
//     */
//    private fun addColor(pkg: PackageBlock, name: String, rule: MonetColorRule): Boolean {
//        val lightId = frameworkColorId(rule.light) ?: return false
//        val nightId = if (isNightCapable) frameworkColorId(rule.night) else null
//
//        // 直接创建 default 配置项。
//        val entry = pkg.getOrCreate("", "color", name) ?: return false
//        entry.setValueAsReference(lightId)
//
//        if (nightId != null && rule.night != rule.light) {
//            val nightEntry = pkg.getOrCreate("-night", "color", name) ?: return true
//            nightEntry.setValueAsReference(nightId)
//        }
//        return true
//    }
//
//    /**
//     * 解析目标 token 为可写入 arsc 的框架色 id。
//     * - `@android:color/system_*` -> 运行时 `getIdentifier` 得到 0x0106xxxx。
//     * - `#aarrggbb` 字面色暂不支持新增 (分析表明新增项从不用字面色)。
//     */
//    @SuppressLint("DiscouragedApi")
//    private fun frameworkColorId(token: String): Int? {
//        if (!token.startsWith("@android:color/")) return null
//        val name = token.removePrefix("@android:color/")
//        frameworkIdCache[name]?.let { return it }
//        val id = Resources.getSystem().getIdentifier(name, "color", "android")
//        if (id == 0) {
//            WeLogger.w(TAG, "cannot resolve framework color: $name")
//            return null
//        }
//        frameworkIdCache[name] = id
//        return id
//    }
//
//    companion object {
//        private const val TAG = "MonetOverlayBuilder"
//
//        /**
//         * 根据当前系统 SDK 选择模板, 返回 (临时文件名, 模板字节)。
//         * 模板以 [MonetEmbeddedAssets] 字面值内嵌 (模块进程无法访问自身 assets)。
//         */
//        fun templateBytes(): Pair<String, ByteArray> =
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
//                "template_api34.apk" to MonetEmbeddedAssets.TEMPLATE_API34
//            else
//                "template_api31.apk" to MonetEmbeddedAssets.TEMPLATE_API31
//
//        fun isWeChatHost(): Boolean = PackageNames.isWeChat(HostInfo.packageName)
//    }
//}
