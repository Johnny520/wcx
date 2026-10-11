package h.Hchat.hooks.api.contact;

import android.text.TextUtils;

import h.Hchat.dexkit.DexFinder;
import h.Hchat.hooks.api.model.WeChatChatroom;
import h.Hchat.hooks.api.model.WeChatContact;
import h.Hchat.hooks.api.net.WeChatNetworkDispatcher;
import h.Hchat.hooks.api.runtime.WeChatDatabaseApi;
import h.Hchat.utils.KavaReflector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 微信群聊 API。
 */
public final class WeChatChatroomApi {
    public static final int ROLE_MEMBER = 0;
    public static final int ROLE_ADMIN = 1;
    public static final int ROLE_OWNER = 2;
    private static final int ROOM_FLAG_ADMIN = 0x800;

    public interface Logger {
        void log(String message);
    }

    private final WeChatDatabaseApi databaseApi;
    private final WeChatContactApi contactApi;
    private final DexFinder dexFinder;
    private final WeChatNetworkDispatcher networkDispatcher;
    private final Logger logger;
    private final Map<String, Boolean> columnExistsCache = new ConcurrentHashMap<>();
    private volatile boolean networkHookInstalled;

    public WeChatChatroomApi(WeChatDatabaseApi databaseApi,
                             WeChatContactApi contactApi,
                             Logger logger) {
        this(databaseApi, contactApi, null, null, logger);
    }

    public WeChatChatroomApi(WeChatDatabaseApi databaseApi,
                             WeChatContactApi contactApi,
                             DexFinder dexFinder,
                             WeChatNetworkDispatcher networkDispatcher,
                             Logger logger) {
        this.databaseApi = databaseApi;
        this.contactApi = contactApi;
        this.dexFinder = dexFinder;
        this.networkDispatcher = networkDispatcher;
        this.logger = logger;
    }

    public boolean isAvailable() {
        return databaseApi != null && databaseApi.isAvailable()
                && contactApi != null && contactApi.isAvailable();
    }

    public WeChatChatroom getChatroom(String chatroomId) {
        if (!isGroup(chatroomId) || databaseApi == null) return null;
        try {
            List<Map<String, Object>> rows = databaseApi.query(
                    "SELECT chatroomname, memberlist, displayname, roomowner "
                            + "FROM chatroom WHERE chatroomname=? LIMIT 1",
                    new String[]{chatroomId});
            if (rows.isEmpty()) return null;
            Map<String, Object> row = rows.get(0);
            return new WeChatChatroom(
                    str(row, "chatroomname"),
                    getChatroomName(chatroomId),
                    str(row, "roomowner"),
                    splitMembers(str(row, "memberlist")),
                    str(row, "displayname"));
        } catch (Throwable e) {
            log("读取群聊失败: " + chatroomId + " " + e.getMessage());
            return null;
        }
    }

    public List<WeChatChatroom> getChatrooms() {
        List<WeChatChatroom> result = new ArrayList<>();
        if (contactApi == null) return result;
        for (WeChatContact contact : contactApi.getGroups()) {
            WeChatChatroom room = getChatroom(contact.wxId);
            if (room != null) {
                result.add(room);
            } else {
                result.add(new WeChatChatroom(
                        contact.wxId, contact.displayName(), "", new ArrayList<>(), ""));
            }
        }
        return result;
    }

    public String getChatroomName(String chatroomId) {
        if (TextUtils.isEmpty(chatroomId)) return "";
        String name = contactApi != null ? contactApi.getDisplayName(chatroomId) : "";
        return !TextUtils.isEmpty(name) ? name : chatroomId;
    }

    public String getOwner(String chatroomId) {
        if (TextUtils.isEmpty(chatroomId) || databaseApi == null) return "";
        try {
            String owner = databaseApi.queryFirstString(
                    "SELECT roomowner FROM chatroom WHERE chatroomname=? LIMIT 1",
                    new String[]{chatroomId},
                    "roomowner");
            if (!TextUtils.isEmpty(owner)) return owner.trim();
        } catch (Throwable e) {
            log("读取群主失败: " + chatroomId + " " + e.getMessage());
        }
        WeChatChatroom room = getChatroom(chatroomId);
        return room != null ? room.owner : "";
    }

    public List<String> getMemberIds(String chatroomId) {
        if (contactApi != null) return contactApi.getGroupMemberIds(chatroomId);
        WeChatChatroom room = getChatroom(chatroomId);
        return room != null ? room.memberIds : new ArrayList<>();
    }

    public int getMemberCount(String chatroomId) {
        return getMemberIds(chatroomId).size();
    }

    public boolean isMember(String chatroomId, String memberWxId) {
        if (TextUtils.isEmpty(memberWxId)) return false;
        for (String member : getMemberIds(chatroomId)) {
            if (memberWxId.equals(member)) return true;
        }
        return false;
    }

    public String getMemberDisplayName(String chatroomId, String memberWxId) {
        if (contactApi == null) return !TextUtils.isEmpty(memberWxId) ? memberWxId : "";
        return contactApi.getGroupMemberDisplayName(chatroomId, memberWxId);
    }
    public String getRoomDisplayName(String chatroomId, String memberWxId) {
        if (contactApi == null) return memberWxId != null ? memberWxId : "";
        return contactApi.getGroupMemberRoomDisplayName(chatroomId, memberWxId);
    }

    public int getMemberRole(String chatroomId, String memberWxId) {
        if (TextUtils.isEmpty(chatroomId) || TextUtils.isEmpty(memberWxId)) return ROLE_MEMBER;
        String owner = getOwner(chatroomId);
        if (!TextUtils.isEmpty(owner) && owner.equals(memberWxId)) return ROLE_OWNER;
        RoomDataMember member = getRoomDataMembers(chatroomId).get(memberWxId);
        return member != null && (member.roomFlag & ROOM_FLAG_ADMIN) != 0 ? ROLE_ADMIN : ROLE_MEMBER;
    }

    public String getMemberRoleName(String chatroomId, String memberWxId) {
        int role = getMemberRole(chatroomId, memberWxId);
        if (role == ROLE_OWNER) return "群主";
        if (role == ROLE_ADMIN) return "管理员";
        return "群员";
    }

    public String getMemberInviter(String chatroomId, String memberWxId) {
        if (TextUtils.isEmpty(chatroomId) || TextUtils.isEmpty(memberWxId)) return "";
        RoomDataMember member = getRoomDataMembers(chatroomId).get(memberWxId);
        if (member == null || TextUtils.isEmpty(member.inviterWxId)) return "";
        String inviter = member.inviterWxId.trim();
        return memberWxId.equals(inviter) ? "" : inviter;
    }

    public List<String> getAdminMemberIds(String chatroomId) {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, RoomDataMember> entry : getRoomDataMembers(chatroomId).entrySet()) {
            RoomDataMember member = entry.getValue();
            if (member != null && (member.roomFlag & ROOM_FLAG_ADMIN) != 0) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    public boolean addChatroomMember(String chatroomId, String addMember) {
        if (TextUtils.isEmpty(addMember)) return false;
        return addChatroomMember(chatroomId, Collections.singletonList(addMember));
    }

    public boolean addChatroomMember(String chatroomId, List<String> addMemberList) {
        if (TextUtils.isEmpty(chatroomId) || addMemberList == null || addMemberList.isEmpty()) {
            log("添加群成员失败: chatroom/member为空");
            return false;
        }
        if (dexFinder == null || dexFinder.addChatroomMemberCtor == null || networkDispatcher == null) {
            log("添加群成员失败: API未就绪");
            return false;
        }
        List<String> members = cleanMemberList(addMemberList);
        if (members.isEmpty()) {
            log("添加群成员失败: member为空");
            return false;
        }
        installNetworkHook();
        try {
            Object scene = KavaReflector.newInstance(
                    dexFinder.addChatroomMemberCtor, chatroomId, members, "", null);
            boolean result = networkDispatcher.send(scene);
            log("添加群成员" + (result ? "成功" : "失败") + ": " + chatroomId + " count=" + members.size());
            return result;
        } catch (Throwable e) {
            log("添加群成员异常: " + e.getMessage());
            return false;
        }
    }

    public boolean inviteChatroomMember(String chatroomId, String inviteMember) {
        if (TextUtils.isEmpty(inviteMember)) return false;
        return inviteChatroomMember(chatroomId, Collections.singletonList(inviteMember));
    }

    public boolean inviteChatroomMember(String chatroomId, List<String> inviteMemberList) {
        if (TextUtils.isEmpty(chatroomId) || inviteMemberList == null || inviteMemberList.isEmpty()) {
            log("邀请群成员失败: chatroom/member为空");
            return false;
        }
        if (dexFinder == null || dexFinder.inviteChatroomMemberCtor == null || networkDispatcher == null) {
            log("邀请群成员失败: API未就绪");
            return false;
        }
        List<String> members = cleanMemberList(inviteMemberList);
        if (members.isEmpty()) {
            log("邀请群成员失败: member为空");
            return false;
        }
        installNetworkHook();
        try {
            Object scene = KavaReflector.newInstance(
                    dexFinder.inviteChatroomMemberCtor, chatroomId, members, 0, null);
            boolean result = networkDispatcher.send(scene);
            log("邀请群成员" + (result ? "成功" : "失败") + ": " + chatroomId + " count=" + members.size());
            return result;
        } catch (Throwable e) {
            log("邀请群成员异常: " + e.getMessage());
            return false;
        }
    }

    public boolean delChatroomMember(String chatroomId, String delMember) {
        if (TextUtils.isEmpty(delMember)) return false;
        return delChatroomMember(chatroomId, Collections.singletonList(delMember));
    }

    public boolean delChatroomMember(String chatroomId, List<String> delMemberList) {
        if (TextUtils.isEmpty(chatroomId) || delMemberList == null || delMemberList.isEmpty()) {
            log("移除群成员失败: chatroom/member为空");
            return false;
        }
        if (dexFinder == null || dexFinder.delChatroomMemberCtor == null || networkDispatcher == null) {
            log("移除群成员失败: API未就绪");
            return false;
        }
        List<String> members = cleanMemberList(delMemberList);
        if (members.isEmpty()) {
            log("移除群成员失败: member为空");
            return false;
        }
        installNetworkHook();
        try {
            Object scene = KavaReflector.newInstance(
                    dexFinder.delChatroomMemberCtor, chatroomId, members, 0);
            boolean result = networkDispatcher.send(scene);
            log("移除群成员" + (result ? "成功" : "失败") + ": " + chatroomId + " count=" + members.size());
            return result;
        } catch (Throwable e) {
            log("移除群成员异常: " + e.getMessage());
            return false;
        }
    }

    public boolean isGroup(String wxId) {
        return contactApi != null && contactApi.isGroup(wxId);
    }

    private void installNetworkHook() {
        if (networkHookInstalled || networkDispatcher == null || dexFinder == null) return;
        if (dexFinder.netQueueClass == null && dexFinder.netQueueCandidateClasses.isEmpty()) return;
        networkDispatcher.hookNetworkQueue(dexFinder.netQueueClass, dexFinder.netQueueCandidateClasses);
        networkHookInstalled = true;
    }

    private List<String> cleanMemberList(List<String> members) {
        List<String> result = new ArrayList<>();
        if (members == null) return result;
        for (String member : members) {
            if (TextUtils.isEmpty(member) || result.contains(member)) continue;
            result.add(member);
        }
        return result;
    }

    private List<String> splitMembers(String memberList) {
        List<String> result = new ArrayList<>();
        if (TextUtils.isEmpty(memberList)) return result;
        for (String part : memberList.split(";")) {
            if (!TextUtils.isEmpty(part)) result.add(part);
        }
        return result;
    }

    private Map<String, RoomDataMember> getRoomDataMembers(String chatroomId) {
        Map<String, RoomDataMember> result = new LinkedHashMap<>();
        if (TextUtils.isEmpty(chatroomId) || databaseApi == null || !hasColumn("chatroom", "roomdata")) {
            return result;
        }
        try {
            List<Map<String, Object>> rows = databaseApi.query(
                    "SELECT roomdata FROM chatroom WHERE chatroomname=? LIMIT 1",
                    new String[]{chatroomId});
            if (rows.isEmpty()) return result;
            byte[] roomData = blob(rows.get(0), "roomdata");
            if (roomData == null || roomData.length == 0) return result;
            ProtoReader reader = new ProtoReader(roomData);
            while (!reader.isAtEnd()) {
                int tag = reader.readVarint32();
                int fieldNumber = tag >>> 3;
                int wireType = tag & 7;
                if (fieldNumber == 1 && wireType == 2) {
                    RoomDataMember member = parseRoomDataMember(reader.readBytes());
                    if (!TextUtils.isEmpty(member.username)) {
                        result.put(member.username, member);
                    }
                } else {
                    reader.skip(wireType);
                }
            }
        } catch (Throwable e) {
            log("解析群成员身份失败: " + e.getMessage());
        }
        return result;
    }

    private RoomDataMember parseRoomDataMember(byte[] memberData) {
        RoomDataMember member = new RoomDataMember();
        try {
            ProtoReader reader = new ProtoReader(memberData);
            while (!reader.isAtEnd()) {
                int tag = reader.readVarint32();
                int fieldNumber = tag >>> 3;
                int wireType = tag & 7;
                if (wireType == 2 && fieldNumber == 1) {
                    member.username = reader.readString().trim();
                } else if (wireType == 2 && fieldNumber == 2) {
                    member.displayName = reader.readString().trim();
                } else if (wireType == 0 && fieldNumber == 3) {
                    member.roomFlag = reader.readVarint32();
                } else if (wireType == 2 && fieldNumber == 4) {
                    member.inviterWxId = reader.readString().trim();
                } else {
                    reader.skip(wireType);
                }
            }
        } catch (Throwable ignored) {
            return new RoomDataMember();
        }
        return member;
    }

    private boolean hasColumn(String table, String column) {
        if (TextUtils.isEmpty(table) || TextUtils.isEmpty(column) || databaseApi == null) return false;
        String key = table + ":" + column;
        Boolean cached = columnExistsCache.get(key);
        if (cached != null) return cached;
        boolean exists = false;
        try {
            List<Map<String, Object>> rows = databaseApi.query("PRAGMA table_info(" + table + ")", null);
            for (Map<String, Object> row : rows) {
                if (column.equalsIgnoreCase(str(row, "name"))) {
                    exists = true;
                    break;
                }
            }
        } catch (Throwable e) {
            log("检测字段失败: " + table + "." + column + " " + e.getMessage());
        }
        if (exists) {
            columnExistsCache.put(key, true);
        } else {
            columnExistsCache.remove(key);
        }
        return exists;
    }

    private static byte[] blob(Map<String, Object> row, String key) {
        Object value = row != null ? row.get(key) : null;
        if (value instanceof byte[]) return (byte[]) value;
        if (value instanceof String) return hexStringToBytes((String) value);
        if (value == null) return null;
        try {
            Method method = value.getClass().getMethod("toByteArray");
            Object result = method.invoke(value);
            if (result instanceof byte[]) return (byte[]) result;
        } catch (Throwable ignored) {}
        try {
            Method method = value.getClass().getMethod("getBytes");
            Object result = method.invoke(value);
            if (result instanceof byte[]) return (byte[]) result;
        } catch (Throwable ignored) {}
        return null;
    }

    private static byte[] hexStringToBytes(String value) {
        if (TextUtils.isEmpty(value)) return null;
        String s = value.trim();
        if (s.startsWith("hex->")) s = s.substring(5);
        s = s.replace(" ", "").replace("\n", "").replace("\r", "");
        if (s.length() < 2 || (s.length() & 1) != 0) return null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) return null;
        }
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16);
            int lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) return null;
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static String str(Map<String, Object> row, String key) {
        Object value = row != null ? row.get(key) : null;
        return value != null ? String.valueOf(value) : "";
    }

    private void log(String message) {
        if (logger != null) logger.log("[WeChatChatroomApi] " + message);
    }

    private static final class RoomDataMember {
        String username = "";
        String displayName = "";
        String inviterWxId = "";
        int roomFlag;
    }

    private static final class ProtoReader {
        private final byte[] data;
        private int position;

        ProtoReader(byte[] data) {
            this.data = data != null ? data : new byte[0];
        }

        boolean isAtEnd() {
            return position >= data.length;
        }

        int readVarint32() {
            long value = readVarint64();
            if (value > Integer.MAX_VALUE) {
                throw new IllegalStateException("varint过大");
            }
            return (int) value;
        }

        long readVarint64() {
            long result = 0L;
            for (int shift = 0; shift < 64; shift += 7) {
                require(1);
                int b = data[position++] & 0xFF;
                result |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) return result;
            }
            throw new IllegalStateException("varint异常");
        }

        String readString() {
            byte[] bytes = readBytes();
            try {
                return new String(bytes, "UTF-8");
            } catch (Throwable ignored) {
                return new String(bytes);
            }
        }

        byte[] readBytes() {
            int length = readVarint32();
            if (length < 0 || length > data.length - position) {
                throw new IllegalStateException("bytes长度异常");
            }
            byte[] bytes = new byte[length];
            System.arraycopy(data, position, bytes, 0, length);
            position += length;
            return bytes;
        }

        void skip(int wireType) {
            switch (wireType) {
                case 0:
                    readVarint64();
                    return;
                case 1:
                    skipBytes(8);
                    return;
                case 2:
                    skipBytes(readVarint32());
                    return;
                case 5:
                    skipBytes(4);
                    return;
                default:
                    throw new IllegalStateException("不支持的wireType: " + wireType);
            }
        }

        private void skipBytes(int count) {
            if (count < 0) throw new IllegalStateException("跳过长度异常");
            require(count);
            position += count;
        }

        private void require(int count) {
            if (position + count > data.length) {
                throw new IllegalStateException("protobuf长度不足");
            }
        }
    }
}
