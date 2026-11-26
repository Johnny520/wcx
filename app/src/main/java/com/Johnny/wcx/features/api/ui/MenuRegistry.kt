package com.Johnny.wcx.features.api.ui

import com.Johnny.wcx.features.ai.AiConfig
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 菜单 provider 的唯一注册实现。
 *
 * 五个宿主菜单（聊天长按 / 朋友圈 / 视频号 / 主页「＋」/ 聊天输入栏）原先各自维护一份
 * `mutableMapOf<String, List<T>>` 或 `mutableSetOf<P>`，三处语义并不一致：
 * 注册是「同名覆盖」还是「去重追加」、容器线程是否安全、菜单项在注册时算还是打开时算。
 * 任何一次改动都容易漏掉其中一两处，表现为「某个菜单不刷新 / 注册了两次」。
 *
 * 这里只统一**注册与遍历**，菜单项类型（各自带不同上下文的回调）仍留在各 API 内。
 */
internal class MenuRegistry<T> {

    /** 一条注册记录。记住 owner，取菜单时才能按 AI 助手总开关整组剔除。 */
    private class Entry<T>(val owner: Any, val items: List<T>)

    private val entries = LinkedHashMap<String, Entry<T>>()

    @Volatile
    private var cached: List<T>? = null

    /**
     * 以 provider 类名为键：同一个功能重复注册只保留最新一份。
     *
     * [items] 在这里当场拍平缓存，因此**必须是静态定义**；依赖「当前聊天 / 当前页面」的
     * 判断要放进菜单项自己的 `isSupported`，在菜单打开时求值。
     */
    @Synchronized
    fun register(owner: Any, items: List<T>) {
        entries[owner.javaClass.name] = Entry(owner, items)
        cached = null
    }

    @Synchronized
    fun unregister(owner: Any) {
        if (entries.remove(owner.javaClass.name) != null) cached = null
    }

    /**
     * 拍平后的全部菜单项。
     *
     * 菜单每次弹出都会调一次，所以缓存结果、只在注册/移除时失效；
     * 返回的列表不可变，调用方遍历时不会被并发注册影响。
     */
    fun snapshot(): List<T> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            return entries.values.flatMap { it.items }.also { cached = it }
        }
    }
    /**
     * 只保留 owner 满足 [allow] 的菜单项。
     *
     * 宿主菜单弹出时用它剔除「AI 助手总开关已关闭」的 AI 菜单项；带过滤时不吃缓存
     * （菜单弹出的频率很低，注册表也很小，现算即可）。
     */
    fun snapshotFiltered(allow: (owner: Any) -> Boolean): List<T> = synchronized(this) {
        val out = ArrayList<T>()
        for (entry in entries.values) if (allow(entry.owner)) out.addAll(entry.items)
        out
    }
}

/**
 * AI 助手总开关关闭时，AI 助手提供的菜单项一律不出现在宿主菜单里。
 *
 * 判定按类名约定（模块内 AI 工具都在 features/items 下，且类名带 AI 前缀）：
 * `AiTodoExtract` / `AiMessagePolish` / `AiMomentsComment` → Ai 开头；
 * `ChatAiSummary` → ChatAi 开头；输入栏那批共用一个匿名 provider，
 * 其类名来自 `AiChatToolsKt`。非 AI 的菜单 provider 不会命中这三条。
 */
internal fun aiMenuOwnerAllowed(owner: Any): Boolean =
    AiConfig.isMasterEnabled || !looksLikeAiOwner(owner)

private fun looksLikeAiOwner(owner: Any): Boolean {
    // 注意不能用 Class.getSimpleName()：输入栏那批 provider 是匿名对象，
    // 匿名类的 simpleName 是空串。这里从全名截取，匿名类形如
    // `com.Johnny.wcx.features.items.chat.AiChatToolsKt$inputBarProvider$1`，同样以 Ai 开头。
    val name = owner.javaClass.name.substringAfterLast('.')
    return name.startsWith("Ai") || name.startsWith("ChatAi") || name.contains("AiChatTools")
}

/**
 * 适用于「provider 按上下文自己算菜单项」的菜单（主页「＋」、聊天输入栏）。
 *
 * provider 不保存菜单项，所以在打开菜单时才调用；这里只保证顺序稳定与遍历安全
 * （原实现一处用 `mutableSetOf`，在 hook 回调里遍历时有并发修改风险）。
 */
internal class ProviderRegistry<P> {

    private val providers = CopyOnWriteArrayList<P>()

    fun register(provider: P) {
        providers.addIfAbsent(provider)
    }

    fun unregister(provider: P) {
        providers.remove(provider)
    }

    fun isEmpty(): Boolean = providers.isEmpty()

    fun snapshot(): List<P> = providers.toList()
}
