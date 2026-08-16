package com.Johnny.wcx.features.api.core

import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexConstructor
import com.Johnny.wcx.features.api.net.WeNetSceneApi
import com.Johnny.wcx.features.core.ApiFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.utils.WeLogger

@Feature(name = "支付服务", categories = ["API"], description = "提供转账确认与退还能力")
object WePaymentApi : ApiFeature(), IResolveDex {

    private const val TAG = "WePaymentApi"

    private val ctorNetSceneTransferOperation by dexConstructor {
        searchPackages("com.tencent.mm.plugin.remittance.model")
        matcher {
            declaredClass {
                usingEqStrings("Micromsg.NetSceneTenpayRemittanceConfirm", "/cgi-bin/mmpay-bin/transferoperation")
            }
            usingEqStrings("account click info , key is %s, value is %s")
        }
    }

    fun confirmTransfer(transactionId: String, transferId: String, payerUsername: String, invalidTime: Int): Boolean {
        return executeTransferOperation("confirm", transactionId, transferId, payerUsername, invalidTime)
    }

    fun refuseTransfer(transactionId: String, transferId: String, payerUsername: String, invalidTime: Int): Boolean {
        return executeTransferOperation("refuse", transactionId, transferId, payerUsername, invalidTime)
    }

    private fun executeTransferOperation(
        operation: String,
        transactionId: String,
        transferId: String,
        payerUsername: String,
        invalidTime: Int
    ): Boolean {
        return try {
            val ctor = ctorNetSceneTransferOperation.constructor
            val netScene = when (ctor.parameterCount) {
                10 -> ctor.newInstance(transactionId, transferId, 0, operation, payerUsername, invalidTime, "", null, 1, null)
                12 -> ctor.newInstance(transactionId, transferId, 0, operation, payerUsername, invalidTime, "", null, 1, null, 0L, "")
                13 -> ctor.newInstance(transactionId, transferId, 0, operation, payerUsername, invalidTime, "", null, 1, null, 0L, "", "")
                14 -> ctor.newInstance(transactionId, transferId, 0, operation, payerUsername, invalidTime, "", null, 1, "", null, 0L, "", "")
                else -> error("unknown NetSceneTransferOperation constructor variant")
            }
            WeNetSceneApi.sendNetScene(netScene)
            true
        } catch (e: Exception) {
            WeLogger.e(TAG, "${operation}Transfer failed", e)
            false
        }
    }
}
