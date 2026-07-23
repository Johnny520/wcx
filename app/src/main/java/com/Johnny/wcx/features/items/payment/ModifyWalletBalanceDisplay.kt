package com.Johnny.wcx.features.items.payment

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.nul
import com.Johnny.wcx.utils.reflection.BString
import com.Johnny.wcx.utils.reflection.bool

@Feature(name = "修改显示余额", categories = ["红包与支付"], description = "伪装钱包余额文字")
object ModifyWalletBalanceDisplay : ClickableFeature(), IResolveDex {

    private const val KEY_BALANCE = "fake_wallet_balance"

    private val methodWcPayMoneyLoadingViewSetMoneyCore by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.plugin.wallet_core.ui.view.WcPayMoneyLoadingView"
            paramTypes(BString, bool, bool, bool)
            addInvoke {
                declaredClass = "com.tencent.mm.plugin.wallet_core.ui.view.WcPayMoneyLoadingView"
                name = "setFirstMoney"
            }
        }
    }

    private var balance by prefOption(KEY_BALANCE, nul<String>())

    override fun onEnable() {
        methodWcPayMoneyLoadingViewSetMoneyCore.hookBefore {
            val balance = balance ?: return@hookBefore
            args[0] = balance
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var balanceInput by remember { mutableStateOf(balance ?: "") }

            AlertDialogContent(
                title = { Text("修改显示余额") },
                text = {
                    TextField(
                        value = balanceInput,
                        onValueChange = { balanceInput = it },
                        label = { Text("零钱余额 (留空不修改)") })
                },
                confirmButton = {
                    Button(onClick = {
                        balance = if (!balanceInput.isBlank())
                            balanceInput
                        else
                            null
                        onDismiss()
                    }) { Text("确定") }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } }
            )
        }
    }
}
