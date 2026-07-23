package com.Johnny.wcx.features.items.contacts

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.Johnny.wcx.features.api.core.WeApi
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.android.showToast

@Feature(name = "跳转对话", categories = ["联系人与群组"], description = "打开指定微信 ID 的对话/好友主页/好友设置界面")
object OpenConversation : ClickableFeature() {

    override val noSwitchWidget = true

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var wxId by remember { mutableStateOf("") }
            AlertDialogContent(
                title = { Text("跳转对话") },
                text = {
                    TextField(
                        value = wxId,
                        onValueChange = { wxId = it },
                        label = { Text("微信 ID") })
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (wxId.isBlank()) {
                            showToast(context, "微信 ID 为空!")
                            return@TextButton
                        }
                        WeApi.openContact(context, wxId, WeApi.OpenContactDestination.HOMEPAGE)
                    }) { Text("好友主页") }

                    TextButton(onClick = {
                        if (wxId.isBlank()) {
                            showToast(context, "微信 ID 为空!")
                            return@TextButton
                        }
                        WeApi.openContact(context, wxId, WeApi.OpenContactDestination.SETTINGS)
                    }) { Text("好友设置") }

                    Button(onClick = {
                        if (wxId.isBlank()) {
                            showToast(context, "微信 ID 为空!")
                            return@Button
                        }
                        WeApi.openContact(context, wxId, WeApi.OpenContactDestination.CONVERSATION)
                    }) { Text("对话") }
                })
        }
    }
}
