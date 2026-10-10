package h.Hchat.hooks.api.payment

/**
 * 微信普通转账操作参数。
 *
 * 这些字段来自转账消息 XML 或转账详情页查询结果。普通领取和退回都走同一
 * transferoperation NetScene，只是 op 分别为 confirm/refuse。
 */
data class TransferOperationParams(
    val transactionId: String,
    val transId: String,
    val totalFee: Int,
    val username: String,
    val invalidTime: Int,
    val groupUsername: String = "",
    val recvAccountType: Int = 0,
    val bindSerial: String = "",
    val subRecvChannelId: Long = 0L,
    val leftButtonContinue: String = "",
    val transferAttach: String = "",
    val displayName: String = "",
    val subTitleClicked: Map<String, String>? = null
) {
    class Builder {
        private var transactionId: String = ""
        private var transId: String = ""
        private var totalFee: Int = 0
        private var username: String = ""
        private var invalidTime: Int = 0
        private var groupUsername: String = ""
        private var recvAccountType: Int = 0
        private var bindSerial: String = ""
        private var subRecvChannelId: Long = 0L
        private var leftButtonContinue: String = ""
        private var transferAttach: String = ""
        private var displayName: String = ""
        private var subTitleClicked: Map<String, String>? = null

        fun transactionId(value: String?) = apply { transactionId = value.orEmpty() }
        fun transId(value: String?) = apply { transId = value.orEmpty() }
        fun totalFee(value: Int) = apply { totalFee = value }
        fun username(value: String?) = apply { username = value.orEmpty() }
        fun invalidTime(value: Int) = apply { invalidTime = value }
        fun groupUsername(value: String?) = apply { groupUsername = value.orEmpty() }
        fun recvAccountType(value: Int) = apply { recvAccountType = value }
        fun bindSerial(value: String?) = apply { bindSerial = value.orEmpty() }
        fun subRecvChannelId(value: Long) = apply { subRecvChannelId = value }
        fun leftButtonContinue(value: String?) = apply { leftButtonContinue = value.orEmpty() }
        fun transferAttach(value: String?) = apply { transferAttach = value.orEmpty() }
        fun displayName(value: String?) = apply { displayName = value.orEmpty() }
        fun subTitleClicked(value: Map<String, String>?) = apply { subTitleClicked = value }

        fun build(): TransferOperationParams = TransferOperationParams(
            transactionId = transactionId,
            transId = transId,
            totalFee = totalFee,
            username = username,
            invalidTime = invalidTime,
            groupUsername = groupUsername,
            recvAccountType = recvAccountType,
            bindSerial = bindSerial,
            subRecvChannelId = subRecvChannelId,
            leftButtonContinue = leftButtonContinue,
            transferAttach = transferAttach,
            displayName = displayName,
            subTitleClicked = subTitleClicked
        )
    }
}
