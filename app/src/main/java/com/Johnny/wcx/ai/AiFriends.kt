package com.Johnny.wcx.ai

import com.Johnny.wcx.features.api.core.WeApi
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.utils.WeLogger

/**
 * 好友快照：把微信通讯录转成 AI 页需要的结构。
 *
 * 为什么不在页面里直接查数据库：
 * - `rcontact` 要 join `img_flag` 才有头像，每次进页面查一遍不划算；
 * - AI 页要标「已添加」、要按拼音分组，这些都能在快照上算完；
 * - 快照带 `exportedAt`，可以按时间判断要不要刷新，避免每次进页面都扫全表。
 *
 * **所有方法都是阻塞的**（查库 + 读写文件），调用方必须放到 IO 线程或协程里。
 */
object AiFriends {

    private const val TAG = "AiFriends"

    /** 快照有效期：一天。好友增减不频繁，进页面就刷会白等一次全表扫描。 */
    private const val MAX_AGE_MS = 24L * 60 * 60 * 1000

    /** 强制从数据库重新导出，返回导出条数；数据库未就绪返回 -1。 */
    fun refresh(): Int {
        if (!WeDatabaseApi.isReady) return -1
        val self = runCatching { WeApi.selfWxId }.getOrNull()
        val list = runCatching { WeDatabaseApi.getFriends() }
            .onFailure { WeLogger.w(TAG, "读取好友列表失败", it) }
            .getOrElse { return -1 }
            .asSequence()
            // FRIENDS 语句把「我自己」也算进来了，AI 页不能给自己建档。
            .filter { it.wxId.isNotBlank() && it.wxId != self }
            .map { contact ->
                AiFriend(
                    wxId = contact.wxId,
                    nickname = contact.nickname,
                    remark = contact.remarkName,
                    pyInitial = contact.initialNickname,
                    quanPin = contact.nicknamePinyin,
                    avatarUrl = contact.avatarUrl,
                )
            }
            .toList()

        return if (AiProfileStore.saveFriends(list)) list.size else -1
    }

    fun load(): List<AiFriend> = AiProfileStore.loadFriends().friends

    /**
     * 取好友快照：过期或为空时重新导出。
     *
     * 导出失败但本地有旧快照时返回旧的 —— 通讯录里多一个人少一个人，比整页空白要好。
     */
    fun loadOrRefresh(maxAgeMs: Long = MAX_AGE_MS): List<AiFriend> {
        val snapshot = AiProfileStore.loadFriends()
        val fresh = snapshot.friends.isNotEmpty() &&
            System.currentTimeMillis() - snapshot.exportedAt < maxAgeMs
        if (fresh) return snapshot.friends
        val count = refresh()
        return if (count >= 0) load() else snapshot.friends
    }
}
