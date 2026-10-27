package com.Johnny.wcx.features.items.beautify

import android.app.Activity
import android.view.View
import androidx.activity.ComponentActivity
import com.Johnny.wcx.BuildConfig
import com.Johnny.wcx.activity.settings.SettingsNavigation
import com.Johnny.wcx.features.api.ui.WeChatMessageViewApi
import com.Johnny.wcx.features.api.ui.WeMainActivityBeautifyApi
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.FeaturesProvider
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import com.Johnny.wcx.utils.reflection.ClassLoaders
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.fs.createDirsSafe
import dev.ujhhgtg.reflekt.reflekt
import java.io.File
import java.nio.file.Files
import kotlin.io.path.absolutePathString
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries

/**
 * 主题工坊 —— 功能门面（2026-10-27 整体重写）。
 *
 * ## 这一层只做什么
 * 保留对外的**全部公开入口**（设置页调用的 18 个 API）、Feature 生命周期与「应用 / 恢复」这条主线；
 * 具体实现全部下沉到下面几个组件，互不纠缠：
 *
 * | 组件 | 职责 |
 * |---|---|
 * | [BuiltinThemes] / [ThemePaths] / [ThemeAliases] | 主题定义、素材路径、别名描述持久化 |
 * | [ThemeMaterials] | APK 素材提取、4 页壁纸解析、「当前页面该用哪张图」 |
 * | [ThemeBubbleKit] | 气泡备份 / 原子替换 / 9-patch 套用 |
 * | [ThemePages] / [ThemeSurface] | 页面分类、沉浸式窗口、生命周期挂载与还原 |
 * | [TabWallpaperRenderer] | DecorView 前景遮罩渲染（不塞子 View、不依赖布局、不拦点击） |
 *
 * **外观 / 入口 / 布局完全不变**：主题工坊页面仍是原来那套 UI，只是它背后调的东西换了。
 *
 * 历史：v245 起的六款内置主题（Hello Kitty / 小熊 / 线条小狗 / 吉伊卡哇 / 美食小狗 / 小兔子）、
 * 主题包导入导出、透明度 5~70%、气泡写入模块 assets、恢复默认自动还原备份 —— 全部保留。
 */
@Feature(
    name = "主题工坊",
    categories = ["界面美化"],
    description = "内置 6 款全局主题，全局沉浸壁纸（主页/通讯录/发现/我的 等 4 个界面，或全部界面）+ 对应气泡，支持透明度调节与备份恢复"
)
object ThemeStore : ClickableFeature(), WeChatMessageViewApi.ICreateViewListener {

    private const val TAG = "ThemeStore"

    private const val CUSTOM_BUBBLES_PREF_KEY = "自定义消息气泡"

    /** 当前功能名（与 @Feature.name 保持一致，初始化期 name 还没注入）。 */
    private const val NEW_FEATURE_NAME = "主题工坊"

    /** 改名前的老开关 key，用于把用户的旧状态迁过来。 */
    private const val LEGACY_FEATURE_NAME = "主题商城"

    init {
        // 功能从「主题商城」改名「主题工坊」。开关在 WePrefs 里以 name 为 key，
        // 这里只清掉旧 key，**不继承旧值** —— 主题总开关必须默认关闭，
        // 要开只能靠用户主动点「应用」（那时才写 true，否则应用完不生效）。
        runCatching {
            if (WePrefs.containsKey(LEGACY_FEATURE_NAME)) {
                WePrefs.remove(LEGACY_FEATURE_NAME)
                WeLogger.i(TAG, "已清理旧开关「$LEGACY_FEATURE_NAME」（主题工坊默认关闭）")
            }
        }.onFailure { WeLogger.w(TAG, "清理旧主题开关失败：${it.message}") }
    }

    // ══════════════════════════════════════════════════════════
    // 持久化
    // ══════════════════════════════════════════════════════════

    /** 当前生效主题 id（内置主题与主题包共用）。 */
    private var activeThemeId by prefOption("theme_store_active_id", "")

    /** 壁纸透明度（%），5~70。 */
    private var overlayOpacityPct by prefOption("theme_store_overlay_opacity", 40)

    // ══════════════════════════════════════════════════════════
    // Feature 生命周期
    // ══════════════════════════════════════════════════════════

    /**
     * 主界面切 Tab 时立即挂 / 换壁纸。
     *
     * 必须写在 Feature 子类里：`hookAfter` / `hookBefore` 是 BaseFeature 的**成员扩展**，
     * 搬到独立 object 里会 Unresolved（2026-10-27 重写时踩过）。
     */
    private fun hookTabSwitch() {
        // doOnCreate 只会被 hook 一次，重复调用不会重置状态（应用主题后会再调一次，抢时机）
        if (!doOnCreateHooked) {
            doOnCreateHooked = true
            runCatching {
                WeMainActivityBeautifyApi.methodDoOnCreate.hookAfter {
                    // hook 目标是 com.tencent.mm.ui.MainTabUI —— **不是 Activity**。
                    // 必须从它的 MMFragmentActivity 字段里取宿主 Activity，
                    // 写成 thisObject as? Activity 会永远是 null，整个 hook 第一行就 return
                    // （2026-10-27 的「4 页全用主页图、adapter 0 处」就是这么来的）。
                    val activity = runCatching {
                        thisObject.reflekt()
                            .firstField { type = "com.tencent.mm.ui.MMFragmentActivity" }
                            .get() as? Activity
                    }.getOrNull() ?: (thisObject as? Activity) ?: run {
                        WeLogger.w(TAG, "主界面 hook：取不到宿主 Activity（MainTabUI 结构变化），本次跳过")
                        return@hookAfter
                    }
                    if (!ThemePages.isLauncher(activity)) return@hookAfter
                    ThemeSurface.applyNow(activity)
                    // doOnCreate 时 DecorView 往往还没完成首次布局，下一帧再挂一次兜底
                    activity.window?.decorView?.post { ThemeSurface.applyNow(activity) }
                    val tabsAdapter = runCatching {
                        thisObject.reflekt().firstField { name = "mTabsAdapter" }.get()
                    }.getOrNull() ?: run {
                        WeLogger.w(TAG, "主界面 hook：未找到 mTabsAdapter，切页换图不可用（4 页会一直显示第 1 张）")
                        return@hookAfter
                    }
                    if (!hookedTabsAdapters.add(tabsAdapter)) return@hookAfter
                    WeLogger.i(TAG, "主界面 hook：已接上切页监听（${activity.javaClass.simpleName}）")
                runCatching {
                    tabsAdapter.reflekt().firstMethod { name = "onPageScrolled" }.hookBefore {
                        val position = args.getOrNull(0) as? Int ?: return@hookBefore
                        val offset = args.getOrNull(1) as? Float ?: return@hookBefore
                        ThemeSurface.onTabScrolled(activity, position, offset)
                    }
                    hookScrolledOk = true
                }.onFailure { WeLogger.w(TAG, "hook onPageScrolled 失败", it) }
                // 第二条切页信号：onPageSelected 是「页码最终落定」，
                // 只 hook onPageScrolled 时一旦 offset 判断不满足就永远不换图（4 页只显示主页那张）
                runCatching {
                    tabsAdapter.reflekt().firstMethod { name = "onPageSelected" }.hookAfter {
                        val position = args.getOrNull(0) as? Int ?: return@hookAfter
                        ThemeSurface.onTabSelected(activity, position)
                    }
                    hookSelectedOk = true
                    }.onFailure { WeLogger.w(TAG, "hook onPageSelected 失败", it) }
                }
            }.onFailure { WeLogger.w(TAG, "hookTabSwitch 失败", it) }
        }
    }

    /**
     * 两条切页信号是否挂上（诊断页直接看这两行，一眼定位「4 页不换图」）。
     *
     * 旧版 TabTheme 之所以 4 张图各自都对，就是 onPageSelected 回调里直接按 position 切
     * —— position 是微信亲口报的，不依赖读 ViewPager 字段。
     */
    /** doOnCreate 是否已挂过（避免重复注册 lambda）。 */
    @Volatile private var doOnCreateHooked = false

    /** 聊天页 ChattingUI 是否已 hook。 */
    @Volatile private var chattingPageHooked = false

    @Volatile private var chattingHookOk = false

    /** 聊天页 Fragment（真正从会话列表进入时走的路径）是否已 hook。 */
    @Volatile private var chattingFragmentHooked = false

    @Volatile private var chattingFragmentHookOk = false

    @Volatile private var chattingFragmentHookCount = 0

    @Volatile private var hookScrolledOk = false
    @Volatile private var hookSelectedOk = false

    /** 已挂过切页监听的 adapter，避免每次进主页重复 hook。 */
    private val hookedTabsAdapters: MutableSet<Any> =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<Any, Boolean>())

    /**
     * 聊天页 Fragment 锚点（**从会话列表进入聊天页的真实路径**）。
     *
     * 8.0.79 dex 实测（明文、未被 R8 混淆）：
     * - `com.tencent.mm.ui.chatting.ChattingUIFragment` extends `BaseChattingUIFragment` extends `MMFragment`
     * - `com.tencent.mm.ui.chatting.ChattingUI` extends `MMSecDataFragmentActivity`（外部/通知进入时才用）
     *
     * 微信对这几个类一直保留明文（R8 只重命名自己的业务类），因此它们是跨版本相对稳定的锚点；
     * 即便如此，ChatPageGuard 的纯 View 特征识别仍然保留作为兜底，任一路命中即可。
     */
    private fun hookChattingFragment() {
        if (chattingFragmentHooked) return
        chattingFragmentHooked = true
        var hooked = 0
        listOf(
            "com.tencent.mm.ui.chatting.ChattingUIFragment",
            "com.tencent.mm.ui.chatting.BaseChattingUIFragment"
        ).forEach { clsName ->
            runCatching {
                val clazz = ClassLoaders.HOST.loadClass(clsName)
                listOf("onCreate", "onResume", "onViewCreated").forEach { methodName ->
                    runCatching {
                        val method = clazz.reflekt().firstMethodOrNull { name = methodName }
                            ?: return@runCatching
                        method.hookAfter {
                            val view = runCatching {
                                thisObject.javaClass.getMethod("getView").invoke(thisObject) as? View
                            }.getOrNull()
                            ThemeSurface.onChattingFragmentShown(view)
                        }
                        hooked++
                    }.onFailure { WeLogger.w(TAG, "hook $clsName.$methodName 失败", it) }
                }
                listOf("onPause", "onDestroyView").forEach { methodName ->
                    runCatching {
                        val method = clazz.reflekt().firstMethodOrNull { name = methodName }
                            ?: return@runCatching
                        method.hookAfter { ThemeSurface.onChattingFragmentHidden() }
                        hooked++
                    }.onFailure { WeLogger.w(TAG, "hook $clsName.$methodName 失败", it) }
                }
            }.onFailure { WeLogger.w(TAG, "加载聊天页 Fragment 失败（$clsName）：${it.message}") }
        }
        // 泛化兜底：MMFragment 是所有微信 Fragment 的基类。
        // 万一上面两个具体类的方法是被子类 override（父类声明 hook 不到），
        // 这里按「类名里带 Chatting」兜住私聊 / 群聊 / 半屏聊天 / 公众号聊天等全部变体。
        runCatching {
            val mmFragment = ClassLoaders.HOST.loadClass("com.tencent.mm.ui.MMFragment")
            listOf("onResume", "onCreate").forEach { methodName ->
                runCatching {
                    val method = mmFragment.reflekt().firstMethodOrNull { name = methodName }
                        ?: return@runCatching
                    method.hookAfter {
                        val cn = thisObject.javaClass.name.orEmpty()
                        if (cn.contains("Chatting", ignoreCase = true)) {
                            val view = runCatching {
                                thisObject.javaClass.getMethod("getView").invoke(thisObject) as? View
                            }.getOrNull()
                            ThemeSurface.onChattingFragmentShown(view)
                        }
                    }
                    hooked++
                }.onFailure { WeLogger.w(TAG, "hook MMFragment.$methodName 失败", it) }
            }
        }.onFailure { WeLogger.w(TAG, "加载 MMFragment 失败：${it.message}") }

        chattingFragmentHookCount = hooked
        chattingFragmentHookOk = hooked > 0
        WeLogger.i(TAG, "聊天页 Fragment 锚点：已 hook $hooked 个方法（进入即撤壁纸 + 容器兜底）")
    }

    /**
     * 聊天页锚点：`com.tencent.mm.ui.chatting.ChattingUI`（8.0.79 实测类名，多年未变）。
     *
     * 它一出现就把主界面壁纸遮罩整个撤掉 —— 这比在主界面侧等 onPause / 等失焦可靠得多，
     * 因为聊天页无论是不是半透明窗口，它自己的 onCreate/onResume 一定会执行。
     */
    private fun hookChattingPage() {
        if (chattingPageHooked) return
        chattingPageHooked = true
        runCatching {
            val clazz = ClassLoaders.HOST.loadClass("com.tencent.mm.ui.chatting.ChattingUI")
            var hooked = 0
            listOf("onCreate", "onResume").forEach { methodName ->
                runCatching {
                    val method = clazz.reflekt().firstMethodOrNull { name = methodName }
                    if (method == null) {
                        WeLogger.w(TAG, "ChattingUI 里没有 $methodName 方法，跳过")
                        return@runCatching
                    }
                    method.hookAfter {
                        ThemeSurface.onChattingPageShown(thisObject as? Activity)
                    }
                    hooked++
                }.onFailure { WeLogger.w(TAG, "hook ChattingUI.$methodName 失败", it) }
            }
            runCatching {
                clazz.reflekt().firstMethodOrNull { name = "onDestroy" }?.let { method ->
                    method.hookAfter { ThemeSurface.onChattingPageClosed() }
                    hooked++
                }
            }.onFailure { WeLogger.w(TAG, "hook ChattingUI.onDestroy 失败", it) }
            chattingHookOk = hooked > 0
            WeLogger.i(TAG, "聊天页锚点：已 hook ChattingUI 的 $hooked 个方法（进入即撤掉主界面壁纸）")
        }.onFailure { WeLogger.w(TAG, "hook 聊天页 ChattingUI 失败：${it.message}") }
    }

    override fun onEnable() {
        // 清掉历史遗留的持久化失败计数（老版本写进去的脏值会让主题被永久锁死）
        runCatching { WePrefs.remove("theme_store_wallpaper_fail") }
        hookTabSwitch()
        hookChattingPage()
        hookChattingFragment()
        ThemeSurface.registerCallbacks(true)
        WeChatMessageViewApi.addListener(this)
        if (activeThemeId.isNotEmpty()) {
            // 进程重启后恢复：壁纸/沉浸式全局重挂（气泡文件已在 moduleAssets 持久存在）
            ThemeSurface.applyToAllLive()
            WeLogger.i(TAG, "已恢复主题：$activeThemeId")
        }
    }

    override fun onDisable() {
        restoreDefault()
        ThemeSurface.registerCallbacks(false)
        WeChatMessageViewApi.removeListener(this)
    }

    // ══════════════════════════════════════════════════════════
    // 入口
    // ══════════════════════════════════════════════════════════

    override fun onClick(context: ComponentActivity) {
        // 主题相关操作已全部并入「主题工坊」子页面，这里只做导航。
        SettingsNavigation.requestThemeWorkshop()
    }

    // ══════════════════════════════════════════════════════════
    // 应用主题
    // ══════════════════════════════════════════════════════════

    /**
     * 应用主题前确保相关 Feature 已启用。
     *
     * 曾经「点了内置主题没效果」的根因就在这里：只写了 activeThemeId，
     * 但「主题工坊」Feature 从未被开启 → hook 没装 → 页面上根本没人去画壁纸。
     */
    private fun ensureThemeFeaturesEnabled() {
        runCatching {
            if (!isEnabled) {
                WeLogger.i(TAG, "主题工坊未启用，随「应用主题」自动开启")
                // 必须同步写 WePrefs：Feature.startup() 是从 pref 读开关的，
                // 只改内存里的 isEnabled 的话，重启微信后开关又是关的 → 主题整片失效。
                WePrefs.putBool(NEW_FEATURE_NAME, true)
                isEnabled = true
            }
            FeaturesProvider.ALL_HOOK_ITEMS
                .filterIsInstance<SwitchFeature>()
                .firstOrNull { it.name == CUSTOM_BUBBLES_PREF_KEY }
                ?.takeIf { !it.isEnabled }
                ?.let {
                    WeLogger.i(TAG, "自定义消息气泡未启用，随「应用主题」自动开启")
                    WePrefs.putBool(CUSTOM_BUBBLES_PREF_KEY, true)
                    it.isEnabled = true
                }
        }.onFailure { WeLogger.w(TAG, "自动启用主题相关功能失败：${it.message}") }
    }

    /** 应用主题（内置或主题包），并同步一次壁纸透明度。 */
    fun applyThemeById(id: String) {
        // 用户主动应用 = 重新给一次机会，清掉本进程内的连续失败计数
        ThemeSurface.resetFailStreak()
        // 顺手确认一次切页 hook（幂等）：万一 onEnable 时还没到主界面 doOnCreate，这里补上
        hookTabSwitch()
        hookChattingPage()
        hookChattingFragment()
        applyThemeInternal(id)
        setOverlayOpacity(overlayOpacityPct)
    }

    private fun applyThemeInternal(id: String) {
        ensureThemeFeaturesEnabled()
        // 外部主题包优先：壁纸与左右气泡取自包内素材，缺哪项就跳过，不影响其它维度。
        ThemePack.find(id)?.let {
            applyPackInternal(it)
            return
        }
        val theme = BuiltinThemes.find(id) ?: run {
            WeLogger.w(TAG, "未知主题：$id")
            return
        }
        try {
            ThemeMaterials.ensureExtracted(theme)
            ThemeBubbleKit.backupCurrent()
            // 气泡写入模块 assets（left=对方 / right=自己），并开启「自定义消息气泡」开关
            ThemeBubbleKit.replaceAtomic(
                listOfNotNull(
                    ThemePaths.bubbleFile(theme, ThemePaths.LEFT_BUBBLE_FILE)
                        .takeIf { it.exists() && it.toFile().length() > 0 }
                        ?.let { it to ThemePaths.activeBubble(ThemePaths.LEFT_BUBBLE_FILE) },
                    ThemePaths.bubbleFile(theme, ThemePaths.RIGHT_BUBBLE_FILE)
                        .takeIf { it.exists() && it.toFile().length() > 0 }
                        ?.let { it to ThemePaths.activeBubble(ThemePaths.RIGHT_BUBBLE_FILE) },
                )
            )
            WePrefs.putBool(CUSTOM_BUBBLES_PREF_KEY, true)
            activeThemeId = id
            // 切到内置主题时清掉主题包游标，避免上一套主题包继续生效
            ThemePack.currentId = ""
            ThemeSurface.applyToAllLive()
            WeLogger.i(TAG, "已应用主题：${theme.name}")
        } catch (e: Throwable) {
            WeLogger.e(TAG, "applyThemeInternal 异常", e)
        }
    }

    /** 应用外部主题包（ThemePack）：壁纸 + 左右气泡。 */
    private fun applyPackInternal(pack: ThemePack.Pack) {
        try {
            val left = pack.bubbleOther()
            val right = pack.bubbleSelf()
            if (left != null || right != null) {
                ThemeBubbleKit.backupCurrent()
                // 原子替换：失败保留旧气泡；新主题没给的那一侧才删掉旧文件
                ThemeBubbleKit.replaceAtomic(
                    listOfNotNull(
                        left?.toPath()?.let { it to ThemePaths.activeBubble(ThemePaths.LEFT_BUBBLE_FILE) },
                        right?.toPath()?.let { it to ThemePaths.activeBubble(ThemePaths.RIGHT_BUBBLE_FILE) },
                    )
                )
                WePrefs.putBool(CUSTOM_BUBBLES_PREF_KEY, true)
            }
            activeThemeId = pack.id
            // 关键：壁纸读取走 ThemePack.active()（读 currentId）。
            // 只写 activeThemeId 不写 currentId，重启微信后壁纸永远解析不出来。
            ThemePack.currentId = pack.id
            ThemeSurface.applyToAllLive()
            WeLogger.i(
                TAG,
                "已应用主题包：${pack.name}（壁纸=${pack.wallpaper() != null}，气泡=${left != null}/${right != null}）"
            )
        } catch (e: Throwable) {
            WeLogger.e(TAG, "applyPackInternal 异常", e)
        }
    }

    /**
     * 把当前生效的主题（内置或主题包）打包成 zip，可直接分享给别人导入。
     * 内置主题会先按同样格式生成一个主题包再打包，保证两边格式一致。
     */
    fun exportActiveTheme(): File? {
        val id = activeThemeId
        if (id.isEmpty()) {
            WeLogger.w(TAG, "没有生效中的主题，无法导出")
            return null
        }
        ThemePack.find(id)?.let { return ThemePack.exportZip(it).getOrNull() }
        val theme = BuiltinThemes.find(id) ?: run {
            WeLogger.w(TAG, "未知主题：$id")
            return null
        }
        val pack = ThemePack.createPackFromFiles(
            name = theme.name,
            wallpaper = ThemePaths.wallpaperFile(theme).takeIf { it.exists() }?.toFile(),
            bubbleLeft = ThemePaths.bubbleFile(theme, ThemePaths.LEFT_BUBBLE_FILE)
                .takeIf { it.exists() }?.toFile(),
            bubbleRight = ThemePaths.bubbleFile(theme, ThemePaths.RIGHT_BUBBLE_FILE)
                .takeIf { it.exists() }?.toFile(),
        ).getOrNull() ?: return null
        return ThemePack.exportZip(pack).getOrNull()
    }

    // ══════════════════════════════════════════════════════════
    // 恢复默认 / 清缓存
    // ══════════════════════════════════════════════════════════

    /** 恢复默认：清空主题 id、还原窗口与壁纸层、还原应用主题前的气泡文件。 */
    private fun restoreDefault() {
        try {
            ThemeBubbleKit.restoreBackup()
            activeThemeId = ""
            ThemePack.currentId = ""
            ThemeSurface.removeAllLive()
        } catch (e: Throwable) {
            WeLogger.e(TAG, "restoreDefault 异常", e)
        }
    }

    fun restoreDefaultTheme() = restoreDefault()

    /**
     * 清除主题相关缓存（应急用）。
     *
     * 场景：换主题后旧气泡没被替换、或没在用主题却还留着残留气泡时，
     * 一键把「模块目录里的气泡文件 + 内置素材提取缓存 + 已挂的背景」全部清掉，
     * 让微信回到原生样式。不会删用户的主题包本身，也不会动气泡备份。
     */
    fun clearThemeCache() {
        runCatching {
            ThemeBubbleKit.clearActive()
            // 内置素材提取缓存：下次应用主题会重新从 APK 提取
            runCatching {
                ThemePaths.builtinDir.toFile().deleteRecursively()
                ThemePaths.builtinDir.createDirsSafe()
            }
            ThemeSurface.resetWallpaperLog()
            ThemeSurface.resetFailStreak()
            TabWallpaperRenderer.invalidateCache()
            // 撤掉已经挂在页面上的背景
            ThemeSurface.removeAllLive()
            // 关掉气泡接管，避免它继续生效
            WePrefs.putBool(CUSTOM_BUBBLES_PREF_KEY, false)
            WeLogger.i(TAG, "已清除主题相关缓存（气泡 / 内置素材 / 背景层）")
        }.onFailure { WeLogger.w(TAG, "清除主题缓存失败：${it.message}") }
    }

    /**
     * 关掉「随机聊天背景」时，立刻把已经挂在聊天页上的壁纸撤掉，
     * 不用等用户退出再进一次聊天界面。
     */
    fun clearWallpaperOnChattingPages() = ThemeSurface.clearChattingWallpapers()

    // ══════════════════════════════════════════════════════════
    // 透明度
    // ══════════════════════════════════════════════════════════

    /** 壁纸透明度（5~70）。 */
    fun overlayOpacity(): Int = overlayOpacityPct.coerceIn(ThemeSurface.MIN_OPACITY, ThemeSurface.MAX_OPACITY)

    fun setOverlayOpacity(pct: Int) {
        overlayOpacityPct = pct.coerceIn(ThemeSurface.MIN_OPACITY, ThemeSurface.MAX_OPACITY)
        ThemeSurface.refreshAlpha()
    }

    // ══════════════════════════════════════════════════════════
    // 渲染方式（2026-10-27 重写后只有一种实现：DecorView 前景遮罩）
    // ══════════════════════════════════════════════════════════

    fun renderModeName(): String = "前景遮罩（压在内容上，不改页面结构）"

    /** 设置页常驻展示的遮罩状态（挂没挂上、多久之前挂的）。 */
    fun maskBrief(): String = runCatching {
        val at = TabWallpaperRenderer.lastAt
        if (at == 0L) "未挂载"
        else "已挂载 · ${((System.currentTimeMillis() - at) / 1000).toString()} 秒前"
    }.getOrDefault("未知")

    /** 设置页点「壁纸渲染方式」：立即重写一次前景遮罩。 */
    fun reapplyNow() {
        invalidateWallpaperCache()
        ThemeSurface.applyToAllLive()
        WeLogger.i(TAG, "手动重写前景遮罩：${TabWallpaperRenderer.lastResult}")
    }

    /** 换主题 / 改素材 / 改透明度后清图缓存，避免拿到旧图。 */
    fun invalidateWallpaperCache() {
        TabWallpaperRenderer.invalidateCache()
        ThemeSurface.resetWallpaperLog()
        ThemeSurface.resetFailStreak()
    }

    // ══════════════════════════════════════════════════════════
    // 供「主题工坊」页面调用的公开入口
    // ══════════════════════════════════════════════════════════

    /** 内置主题（id to 名称）。 */
    fun builtinThemeItems(): List<Pair<String, String>> = BuiltinThemes.all.map { it.id to it.name }

    /** 内置主题的自定义名称（长按重命名后存这里，为空则用内置原名）。 */
    fun builtinAlias(id: String): String = ThemeAliases.name(id)

    fun setBuiltinAlias(id: String, name: String) = ThemeAliases.setName(id, name)

    /** 内置主题的自定义描述（可选）。 */
    fun builtinDesc(id: String): String = ThemeAliases.desc(id)

    fun setBuiltinDesc(id: String, desc: String) = ThemeAliases.setDesc(id, desc)

    /** 内置主题展示名 = 自定义别名优先。 */
    fun builtinDisplayName(id: String, fallback: String): String = ThemeAliases.displayName(id, fallback)

    /** 默认（内置）主题的缩略图文件；首次调用会把素材从 APK 提取到模块数据目录。 */
    fun builtinThumbnail(id: String): File? = ThemeMaterials.thumbnail(id)

    /** 默认主题的主题色，壁纸缺失时用来兜底显示。 */
    fun builtinColor(id: String): Long = BuiltinThemes.previewColor(id)

    fun activeThemeIdOrEmpty(): String = activeThemeId

    // ══════════════════════════════════════════════════════════
    // 气泡：消息视图创建时按主题气泡替换背景（9-patch）
    // ══════════════════════════════════════════════════════════

    override fun onCreateView(
        param: de.robv.android.xposed.XC_MethodHook.MethodHookParam,
        view: View
    ) {
        if (activeThemeId.isEmpty()) return
        try {
            ThemeBubbleKit.applyToMessageView(param, view)
        } catch (e: Throwable) {
            WeLogger.w(TAG, "onCreateView 应用气泡异常", e)
        }
    }

    // ══════════════════════════════════════════════════════════
    // 诊断
    // ══════════════════════════════════════════════════════════

    /**
     * 主题全链路状态，供「主题诊断」一键复制。
     *
     * 每一行都对应链路上的一环，出现「没图 / 没生效」直接按行定位，不需要再猜。
     */
    fun diagnose(): String = buildString {
        appendLine("== 主题工坊诊断（${BuildConfig.VERSION_NAME}）==")
        appendLine(
            "功能开关 = " + runCatching { WePrefs.getBoolOrFalse(NEW_FEATURE_NAME) }.getOrDefault(false) +
                "（key=$NEW_FEATURE_NAME）"
        )
        appendLine("activeThemeId = '$activeThemeId'")
        appendLine("ThemePack.currentId = '" + runCatching { ThemePack.currentId }.getOrDefault("异常") + "'")
        appendLine(
            "ThemePack.active() = " + runCatching {
                ThemePack.active()?.let { pack -> "${pack.id} / ${pack.name}" } ?: "无"
            }.getOrDefault("异常")
        )
        appendLine("渲染方式 = ${renderModeName()}")
        appendLine("遮罩状态 = ${maskBrief()}")
        appendLine(
            "最近一次写入 = tab=${TabWallpaperRenderer.lastTab} 结果=${TabWallpaperRenderer.lastResult}"
        )
        appendLine("  文件=${TabWallpaperRenderer.lastPath}")
        appendLine("壁纸透明度 = ${overlayOpacity()}%")
        appendLine("自定义气泡开关 = " + runCatching { WePrefs.getBoolOrFalse(CUSTOM_BUBBLES_PREF_KEY) }.getOrDefault(false))
        appendLine(
            "气泡文件存在 = left:" + ThemePaths.activeBubble(ThemePaths.LEFT_BUBBLE_FILE).exists() +
                " right:" + ThemePaths.activeBubble(ThemePaths.RIGHT_BUBBLE_FILE).exists() +
                " 备份=" + ThemePaths.hasBubbleBackup()
        )
        appendLine("连续挂载失败计数 = ${ThemeSurface.wallpaperFailStreak}")

        val files = runCatching { ThemeMaterials.tabWallpaperFiles(activeThemeId) }.getOrDefault(emptyList())
        appendLine("4 页素材：")
        listOf("主页", "通讯录", "发现", "我").forEachIndexed { index, label ->
            val file = files.getOrNull(index)
            appendLine(
                "  $label = " + if (file == null) {
                    "缺失"
                } else {
                    "${file.absolutePath} (${runCatching { file.length() }.getOrDefault(0L)} B)"
                }
            )
        }

        // 分页是否生效，就看这两行：页码读不到 / 切页 hook 没挂上，都会「4 页全是主页那张」
        appendLine("doOnCreate hook = ${if (doOnCreateHooked) "已注册" else "未注册（功能没开过）"}")
        appendLine(
            "聊天页 hook = ${if (chattingHookOk) "已挂 ChattingUI（进入即撤掉主界面壁纸）"
            else if (chattingPageHooked) "挂载失败（见日志）" else "未注册"}"
        )
        appendLine(
            "聊天页 Fragment 锚点 = " +
                (if (chattingFragmentHookOk) "已 hook $chattingFragmentHookCount 个方法"
                else "未挂上（靠 View 特征识别兜底）")
        )
        appendLine(
            "切页 hook = adapter ${hookedTabsAdapters.size} 处" +
                "，onPageSelected=${if (hookSelectedOk) "已挂" else "未挂"}" +
                "，onPageScrolled=${if (hookScrolledOk) "已挂" else "未挂"}" +
                "（只要 onPageSelected 是「已挂」，4 张图就各归各页）"
        )
        runCatching { ThemeSurface.liveActivities() }.getOrDefault(emptyList())
            .firstOrNull { ThemePages.isLauncher(it) }
            ?.let { launcher ->
                appendLine(
                    "当前 Tab 实测 = ${ThemeSurface.currentTabIndex(launcher)}" +
                        "（0=主页 1=通讯录 2=发现 3=我；永远是 0 就是页码没读到）"
                )
                appendLine(
                    "页码可读 = " + (if (ThemeSurface.pagerReadable(launcher)) "是（轮询兜底可用）"
                    else "否（只能靠 onPageSelected 回调换图）")
                )
            }

        // 最近一次用图的那张图，同目录下 4 张分页图到底在不在
        runCatching {
            val lastFile = TabWallpaperRenderer.lastPath
            val dir = lastFile?.let { java.io.File(it).parentFile }
            if (dir != null && dir.isDirectory) {
                appendLine(
                    "该目录的 4 张分页图 = " + listOf("chats", "contacts", "discover", "me").joinToString("，") { key ->
                        val f = java.io.File(dir, "wallpaper_$key.png")
                        "$key:" + if (f.exists()) "${f.length()}B" else "无"
                    }
                )
            }
        }

        appendLine("当前前台 Activity = ${runCatching { ThemeSurface.foregroundName() }.getOrDefault("?")}")
        appendLine("聊天页识别 = ${runCatching { ThemeSurface.chatPageStatus() }.getOrDefault("?")}")
        appendLine("最近事件（时间线，从旧到新）：")
        runCatching { ThemeSurface.recentEvents() }.getOrDefault(emptyList()).forEach { appendLine("  $it") }

        val acts = runCatching { ThemeSurface.liveActivities() }.getOrDefault(emptyList())
        appendLine("存活 Activity = " + acts.joinToString("、") { it.javaClass.simpleName })
        acts.forEach { act ->
            appendLine("· ${act.javaClass.simpleName}")
            appendLine(runCatching { ThemeSurface.describe(act) }.getOrDefault("  诊断异常"))
        }
    }
}
