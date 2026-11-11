package h.Hchat.hooks.api.model

import android.text.TextUtils

class WeChatContact(
    wxId: String?,
    nickname: String?,
    customWxId: String?,
    remarkName: String?,
    avatarUrl: String?,
    avatarBackupUrl: String?,
    encryptedUsername: String?,
    province: String?,
    city: String?,
    @JvmField val gender: Int,
    @JvmField val type: Int
) {
    @JvmField val wxId: String = wxId.orEmpty()
    @JvmField val nickname: String = nickname.orEmpty()
    @JvmField val customWxId: String = customWxId.orEmpty()
    @JvmField val remarkName: String = remarkName.orEmpty()
    @JvmField val avatarUrl: String = avatarUrl.orEmpty()
    @JvmField val avatarBackupUrl: String = avatarBackupUrl.orEmpty()
    @JvmField val encryptedUsername: String = encryptedUsername.orEmpty()
    @JvmField val province: String = province.orEmpty()
    @JvmField val city: String = city.orEmpty()

    fun displayName(): String {
        if (isGroup()) {
            if (!TextUtils.isEmpty(nickname)) return nickname
            return wxId
        }
        if (!TextUtils.isEmpty(remarkName) && !TextUtils.isEmpty(nickname)) {
            return "$remarkName ($nickname)"
        }
        if (!TextUtils.isEmpty(remarkName)) return remarkName
        if (!TextUtils.isEmpty(nickname)) return nickname
        return wxId
    }

    fun getWxid(): String = wxId

    fun getWxId(): String = wxId

    fun getUserName(): String = wxId

    fun getNickname(): String = nickname

    fun getNickName(): String = nickname

    fun getRemarkName(): String = remarkName

    fun getName(): String = displayName()

    fun getDisplayName(): String = displayName()

    fun getAlias(): String = customWxId

    fun getAvatarUrl(): String = avatarUrl

    fun getAvatarBackupUrl(): String = avatarBackupUrl

    fun getEncryptedUsername(): String = encryptedUsername

    fun getProvince(): String = province

    fun getCity(): String = city

    fun getRegion(): String {
        if (!TextUtils.isEmpty(province) && !TextUtils.isEmpty(city)) return "$province $city"
        if (!TextUtils.isEmpty(province)) return province
        if (!TextUtils.isEmpty(city)) return city
        return ""
    }

    fun getGender(): Int = gender

    fun getSex(): Int = gender

    fun isGroup(): Boolean = wxId.endsWith("@chatroom") || wxId.endsWith("@im.chatroom")

    fun isOfficialAccount(): Boolean = wxId.startsWith("gh_")

    fun isFriend(): Boolean = !isGroup() && !isOfficialAccount() && type and 1 != 0
}
