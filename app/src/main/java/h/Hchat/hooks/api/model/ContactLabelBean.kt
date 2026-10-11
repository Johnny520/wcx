package h.Hchat.hooks.api.model

class ContactLabelBean(
    labelId: String?,
    labelName: String?,
    userNameList: List<String>?
) {
    @JvmField val labelId: String = labelId.orEmpty()
    @JvmField val labelName: String = labelName.orEmpty()
    @JvmField val userNameList: List<String> = userNameList?.toList().orEmpty()

    fun getLabelId(): String = labelId

    fun getId(): String = labelId

    fun getLabelName(): String = labelName

    fun getName(): String = labelName

    fun getUserNameList(): List<String> = userNameList

    fun getUsernameList(): List<String> = userNameList

    fun getContactList(): List<String> = userNameList

    override fun toString(): String {
        return "ContactLabelBean(labelId=$labelId, labelName=$labelName, userNameList=$userNameList)"
    }
}
