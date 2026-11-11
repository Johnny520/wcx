package me.hd.wauxv.data.bean.info;

import java.util.Collections;
import java.util.List;

public class GroupInfo {
    private final String roomId;
    private final String name;
    private final String nickname;
    private final String remarkName;
    private final String displayName;
    private final String owner;
    private final List<String> memberList;
    private final String rawDisplayNames;

    public GroupInfo(
            String roomId,
            String name,
            String owner,
            List<String> memberList,
            String rawDisplayNames
    ) {
        this(roomId, name, name, "", owner, memberList, rawDisplayNames);
    }

    public GroupInfo(
            String roomId,
            String name,
            String nickname,
            String remarkName,
            String owner,
            List<String> memberList,
            String rawDisplayNames
    ) {
        this.roomId = roomId == null ? "" : roomId;
        this.name = name == null ? "" : name;
        this.nickname = nickname == null ? "" : nickname;
        this.remarkName = remarkName == null ? "" : remarkName;
        this.displayName = buildDisplayName(this.roomId, this.name, this.remarkName);
        this.owner = owner == null ? "" : owner;
        this.memberList = memberList == null ? Collections.emptyList() : Collections.unmodifiableList(memberList);
        this.rawDisplayNames = rawDisplayNames == null ? "" : rawDisplayNames;
    }

    public String getRoomId() {
        return roomId;
    }

    public String getChatroomId() {
        return roomId;
    }

    public String getWxid() {
        return roomId;
    }

    public String getWxId() {
        return roomId;
    }

    public String getUserName() {
        return roomId;
    }

    public String getName() {
        return name;
    }

    public String getNickname() {
        return nickname;
    }

    public String getNickName() {
        return nickname;
    }

    public String getRemark() {
        return remarkName;
    }

    public String getRemarkName() {
        return remarkName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getOwner() {
        return owner;
    }

    public List<String> getMemberList() {
        return memberList;
    }

    public int getMemberCount() {
        return memberList.size();
    }

    public int memberCount() {
        return memberList.size();
    }

    public String getRawDisplayNames() {
        return rawDisplayNames;
    }

    @Override
    public String toString() {
        return "GroupInfo(roomId=" + roomId + ", name=" + name + ", remarkName=" + remarkName + ", memberCount=" + memberList.size() + ")";
    }

    private static String buildDisplayName(String roomId, String name, String remarkName) {
        if (!isEmpty(remarkName) && !remarkName.equals(name)) {
            return isEmpty(name) ? remarkName : remarkName + " (" + name + ")";
        }
        if (!isEmpty(name)) return name;
        if (!isEmpty(remarkName)) return remarkName;
        return roomId;
    }

    private static boolean isEmpty(String value) {
        return value == null || value.length() == 0;
    }
}
