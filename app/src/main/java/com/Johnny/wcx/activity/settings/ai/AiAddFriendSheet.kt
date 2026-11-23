package com.Johnny.wcx.activity.settings.ai

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Check_circle
import com.composables.icons.materialsymbols.outlined.Search
import com.Johnny.wcx.ai.AiFriend
import com.Johnny.wcx.ai.AiFriends
import com.Johnny.wcx.ai.AiProfile
import com.Johnny.wcx.ai.AiProfileState
import com.Johnny.wcx.ai.AiProfileStore
import com.Johnny.wcx.ui.content.BottomSheetHost
import com.Johnny.wcx.ui.content.SheetTextAction
import com.Johnny.wcx.ui.style.WxPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 添加好友：**底部半屏浮层**，复用 [BottomSheetHost]。
 *
 * 为什么不做成整页：整页要占满 pager 的内容区，而底部导航条画在 pager 之后，
 * 于是确认按钮会被导航条压住（只能靠加底部留白绕开，属于「算出来的位置」）。
 * 半屏浮层由 `MainPagerScreen` 在导航条**之后**渲染，遮罩与卡片天然盖在导航条上，
 * 不需要任何留白补偿 —— 和「分类 / 已启用 / 全部功能」三个半屏同一套机制。
 *
 * 数据自己读（好友快照 + 已有档案 id），因此调用方只关心「打开 / 关掉了」。
 */
@Composable
internal fun AiAddFriendSheet(
    onDismiss: () -> Unit,
    /** 未配置时点提示条 -> 打开 AI 配置面板。 */
    onRequestConfig: () -> Unit = {},
    onConfirmed: (Int) -> Unit,
) {
    BackHandler { onDismiss() }

    var loading by remember { mutableStateOf(true) }
    var friends by remember { mutableStateOf<List<AiFriend>>(emptyList()) }
    var existing by remember { mutableStateOf<Set<String>>(emptySet()) }
    var query by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    // SnapshotStateMap：普通 Map 的改动不会触发条目重组，勾选状态不会亮。
    val selected = remember { androidx.compose.runtime.mutableStateMapOf<String, AiFriend>() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) {
            AiFriends.loadOrRefresh() to
                AiProfileStore.loadIndex().profiles.map { it.wxId }.toSet()
        }
        friends = loaded.first
        existing = loaded.second
        loading = false
    }

    val filtered = remember(friends, query, existing) {
        val keyword = query.trim()
        friends.asSequence()
            .filter { it.wxId !in existing }
            .filter {
                keyword.isEmpty() ||
                    it.nickname.contains(keyword, ignoreCase = true) ||
                    it.remark.contains(keyword, ignoreCase = true) ||
                    it.wxId.contains(keyword, ignoreCase = true) ||
                    it.quanPin.contains(keyword, ignoreCase = true)
            }
            .sortedWith(compareBy({ it.groupKey }, { it.sortKey }))
            .toList()
    }

    BottomSheetHost(
        onDismiss = onDismiss,
        heightFraction = 0.86f,
        header = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "添加好友",
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = WxPalette.textPrimary(),
                )
                Spacer(Modifier.weight(1f))
                SheetTextAction("关闭", onDismiss)
            }
        },
    ) {
        SearchBox(query = query, onQueryChange = { query = it })

        when {
            loading -> Box(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text("读取通讯录…", fontSize = 13.sp, color = WxPalette.textSecondary())
            }

            filtered.isEmpty() -> Box(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.TopCenter,
            ) {
                Text(
                    text = if (friends.isEmpty()) {
                        "没有读到好友列表，请确认已登录微信后重进此页"
                    } else {
                        "没有可添加的好友"
                    },
                    fontSize = 13.sp,
                    color = WxPalette.textSecondary(),
                    modifier = Modifier.padding(top = 40.dp),
                )
            }

            else -> LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 8.dp),
            ) {
                items(filtered, key = { it.wxId }) { friend ->
                    PickerRow(
                        friend = friend,
                        checked = selected.containsKey(friend.wxId),
                        onClick = {
                            if (selected.containsKey(friend.wxId)) selected.remove(friend.wxId)
                            else selected[friend.wxId] = friend
                        },
                    )
                }
            }
        }

        // 没填接口就先把话说清楚：勾了也不会自动整理。
        AiConfigHintBar(
            onClick = onRequestConfig,
            text = "还没填 AI 配置：添加后不会自动整理，先去填写",
        )

        // 确认条在卡片内部，卡片本身已带 navigationBarsPadding，所以这里只留一点间距。
        Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            AiPrimaryButton(
                text = if (selected.isEmpty()) "确认" else "确认（${selected.size}）",
                enabled = selected.isNotEmpty() && !saving,
                onClick = {
                    if (saving) return@AiPrimaryButton
                    saving = true
                    val picked = selected.values.toList()
                    scope.launch {
                        val created = withContext(Dispatchers.IO) { createProfiles(picked) }
                        saving = false
                        onConfirmed(created)
                    }
                },
            )
        }
    }
}

/** 建档：已存在的人跳过（并发打开两次也不会覆盖已有标签与记忆）。 */
private fun createProfiles(picked: List<AiFriend>): Int {
    val now = System.currentTimeMillis()
    var created = 0
    picked.forEach { friend ->
        if (AiProfileStore.readProfile(friend.wxId) != null) return@forEach
        val profile = AiProfile(
            wxId = friend.wxId,
            nickname = friend.nickname,
            remark = friend.remark,
            pyInitial = friend.pyInitial,
            quanPin = friend.quanPin,
            avatarUrl = friend.avatarUrl,
            state = AiProfileState.UNINITIALIZED,
            createdAt = now,
        )
        if (AiProfileStore.saveProfile(profile)) created++
    }
    return created
}

@Composable
private fun SearchBox(query: String, onQueryChange: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(WxPalette.insetSurface())
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = MaterialSymbols.Outlined.Search,
            contentDescription = null,
            tint = WxPalette.textTertiary(),
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text("搜索昵称 / 备注 / 微信号", fontSize = 14.sp, color = WxPalette.textTertiary())
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(fontSize = 14.sp, color = WxPalette.textPrimary()),
                cursorBrush = SolidColor(WxPalette.Primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PickerRow(friend: AiFriend, checked: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AiAvatar(model = friend.avatarUrl, name = friend.displayName, size = 40)
        Column(Modifier.weight(1f)) {
            Text(
                friend.displayName,
                fontSize = 15.sp,
                color = WxPalette.textPrimary(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (friend.remark.isNotBlank() && friend.nickname.isNotBlank()) {
                Text(
                    friend.nickname,
                    fontSize = 12.sp,
                    color = WxPalette.textSecondary(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            imageVector = MaterialSymbols.Outlined.Check_circle,
            contentDescription = null,
            tint = if (checked) WxPalette.Primary else WxPalette.textTertiary().copy(alpha = 0.4f),
            modifier = Modifier.size(22.dp),
        )
    }
}
