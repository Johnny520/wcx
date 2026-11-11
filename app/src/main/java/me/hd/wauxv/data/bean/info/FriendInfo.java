package me.hd.wauxv.data.bean.info;

import android.text.TextUtils;

public class FriendInfo {
    private final String wxid;
    private final String nickname;
    private final String remark;
    private final String alias;
    private final String avatarUrl;
    private final String avatarBackupUrl;
    private final String encryptedUsername;
    private final String province;
    private final String city;
    private final int gender;
    private final int type;

    public FriendInfo(
            String wxid,
            String nickname,
            String remark,
            String alias,
            String avatarUrl,
            String avatarBackupUrl,
            String encryptedUsername,
            String province,
            String city,
            int gender,
            int type
    ) {
        this.wxid = safe(wxid);
        this.nickname = safe(nickname);
        this.remark = safe(remark);
        this.alias = safe(alias);
        this.avatarUrl = safe(avatarUrl);
        this.avatarBackupUrl = safe(avatarBackupUrl);
        this.encryptedUsername = safe(encryptedUsername);
        this.province = safe(province);
        this.city = safe(city);
        this.gender = gender;
        this.type = type;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public String getWxid() {
        return wxid;
    }

    public String getWxId() {
        return wxid;
    }

    public String getUserName() {
        return wxid;
    }

    public String getUsername() {
        return wxid;
    }

    public String getNickname() {
        return nickname;
    }

    public String getNickName() {
        return nickname;
    }

    public String getRemark() {
        return remark;
    }

    public String getRemarkName() {
        return remark;
    }

    public String getName() {
        if (!TextUtils.isEmpty(remark) && !TextUtils.isEmpty(nickname)) {
            return remark + " (" + nickname + ")";
        }
        if (!TextUtils.isEmpty(remark)) return remark;
        if (!TextUtils.isEmpty(nickname)) return nickname;
        return wxid;
    }

    public String getDisplayName() {
        return getName();
    }

    public String getAlias() {
        return alias;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public String getAvatarBackupUrl() {
        return avatarBackupUrl;
    }

    public String getEncryptedUsername() {
        return encryptedUsername;
    }

    public String getProvince() {
        return province;
    }

    public String getCity() {
        return city;
    }

    public String getRegion() {
        if (!TextUtils.isEmpty(province) && !TextUtils.isEmpty(city)) return province + " " + city;
        if (!TextUtils.isEmpty(province)) return province;
        if (!TextUtils.isEmpty(city)) return city;
        return "";
    }

    public int getGender() {
        return gender;
    }

    public int getSex() {
        return gender;
    }

    public int getType() {
        return type;
    }

    public boolean isGroup() {
        return wxid.endsWith("@chatroom") || wxid.endsWith("@im.chatroom");
    }

    public boolean isOfficialAccount() {
        return wxid.startsWith("gh_");
    }

    @Override
    public String toString() {
        return "FriendInfo(wxid=" + wxid + ", nickname=" + nickname + ", remark=" + remark + ")";
    }
}
