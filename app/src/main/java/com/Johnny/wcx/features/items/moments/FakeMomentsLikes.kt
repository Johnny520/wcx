package com.Johnny.wcx.features.items.moments

import android.content.ContentValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tencent.mm.plugin.sns.ui.SnsCommentFooter
import com.tencent.mm.protocal.protobuf.SnsObject
import com.Johnny.reflekt.reflekt
import com.Johnny.reflekt.utils.createInstance
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.WeDatabaseListenerApi
import com.Johnny.wcx.features.api.ui.WeMomentsContextMenuApi
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.ContactsSelector
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.StarIcon
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.LinkedList

@Feature(name = "伪集赞", categories = ["朋友圈"], description = "自定义朋友圈点赞用户列表")
object FakeMomentsLikes : SwitchFeature(), WeMomentsContextMenuApi.IMenuItemsProvider,
    WeDatabaseListenerApi.IUpdateListener {

    private const val TAG = "FakeMomentsLikes"

    // 存储每个朋友圈动态的伪点赞用户配置 (snsId -> Set<WxId>)
    private val fakeLikeWxIds = mutableMapOf<Long, Set<String>>()
    private lateinit var parseFromMethod: Method
    private lateinit var snsUserProtobufClass: Class<*>
    private lateinit var snsUserProtobufClassWxIdField: Field

    override fun onEnable() {
        snsUserProtobufClass = SnsCommentFooter::class.java.getMethod("getCommentInfo").returnType
        snsUserProtobufClassWxIdField = snsUserProtobufClass.reflekt().firstField { type = String::class }.self
        parseFromMethod = SnsObject::class.reflekt().firstMethod { name = "parseFrom"; superclass() }.self
        WeMomentsContextMenuApi.addProvider(this)
        WeDatabaseListenerApi.addListener(this)
    }

    override fun onDisable() {
        WeMomentsContextMenuApi.removeProvider(this)
        WeDatabaseListenerApi.removeListener(this)
    }

    override fun getMenuItems(): List<WeMomentsContextMenuApi.MenuItem> {
        return listOf(
            WeMomentsContextMenuApi.MenuItem(
                777006,
                "伪点赞",
                StarIcon,
                { _, _ -> true }
            ) { moment ->
                val contacts = WeDatabaseApi.getContacts()
                val snsInfo = moment.snsInfo!!
                val snsId = snsInfo.reflekt().getField("field_snsId", true) as Long

                val currentSelected = fakeLikeWxIds[snsId] ?: emptySet()

                showComposeDialog(moment.activity) {
                    var countInput by remember { mutableStateOf("") }

                    AlertDialogContent(
                        title = { Text("伪点赞方式") },
                        text = {
                            DefaultColumn {
                                Button(
                                    onClick = {
                                        onDismiss()
                                        showComposeDialog(moment.activity) {
                                            ContactsSelector(
                                                title = "选择伪点赞用户",
                                                contacts = contacts,
                                                initialSelectedWxIds = currentSelected,
                                                onDismiss = onDismiss,
                                                onConfirm = { selectedWxids ->
                                                    if (selectedWxids.isEmpty()) {
                                                        fakeLikeWxIds.remove(snsId)
                                                        showToast("已清除伪点赞配置")
                                                    } else {
                                                        fakeLikeWxIds[snsId] = selectedWxids
                                                        showToast("已设置 ${selectedWxids.size} 个伪点赞")
                                                    }
                                                    onDismiss()
                                                }
                                            )
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("选择指定联系人") }

                                HorizontalDivider()

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    OutlinedTextField(
                                        value = countInput,
                                        onValueChange = { countInput = it.filter(Char::isDigit) },
                                        label = { Text("随机数量") },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Button(onClick = {
                                        val count = countInput.toIntOrNull() ?: 0
                                        if (count < 0) {
                                            showToast("请输入有效数量!")
                                            return@Button
                                        }
                                        if (count == 0) {
                                            fakeLikeWxIds.remove(snsId)
                                            showToast("已清除伪点赞配置")
                                        } else {
                                            val selected = contacts.shuffled().take(count).map { it.wxId }.toSet()
                                            fakeLikeWxIds[snsId] = selected
                                            showToast("已随机设置 ${selected.size} 个伪点赞")
                                        }
                                        onDismiss()
                                    }) { Text("确定") }
                                }
                            }
                        },
                        dismissButton = {
                            TextButton({
                                onDismiss()
                                fakeLikeWxIds.remove(snsId)
                                showToast("已清除伪点赞配置")
                            }) { Text("清除") }
                        },
                        confirmButton = { TextButton(onDismiss) { Text("取消") } }
                    )
                }
            }
        )
    }

    override fun onUpdate(table: String, values: ContentValues, whereClause: String?, whereArgs: Array<String>?, conflictAlgorithm: Int) {
        try {
            injectFakeLikes(table, values)
        } catch (e: Throwable) {
            WeLogger.e(TAG, "failed to handle database update", e)
        }
    }

    private fun injectFakeLikes(tableName: String, values: ContentValues) = runCatching {
        if (tableName != "SnsInfo") return@runCatching
        val snsId = values.get("snsId") as? Long ?: return@runCatching
        val fakeWxIds = fakeLikeWxIds[snsId] ?: emptySet()
        if (fakeWxIds.isEmpty()) return@runCatching

        val snsObj = SnsObject()
        parseFromMethod.invoke(snsObj, values.get("attrBuf") as? ByteArray ?: return@runCatching)

        val fakeList = LinkedList<Any>().apply {
            fakeWxIds.forEach { wxid ->
                snsUserProtobufClass.createInstance().apply {
                    snsUserProtobufClassWxIdField.set(this, wxid)
                    add(this)
                }
            }
        }

        snsObj.LikeUserList = fakeList
        snsObj.LikeUserListCount = fakeList.size
        snsObj.LikeCount = fakeList.size
        snsObj.LikeFlag = 1

        values.put("attrBuf", snsObj.toByteArray())
        WeLogger.i(TAG, "成功为朋友圈 $snsId 注入 ${fakeList.size} 个伪点赞")
    }.onFailure { WeLogger.e(TAG, "注入伪点赞失败", it) }
}
