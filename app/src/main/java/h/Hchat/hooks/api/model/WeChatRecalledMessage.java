package h.Hchat.hooks.api.model;

/**
 * 撤回原消息信息。
 *
 * 数据来源可能是 message 表、GetSysCmdMsgInfo 表，或撤回 PB 参数。
 */
public final class WeChatRecalledMessage {
    public final long originSvrId;
    public final long newMsgId;
    public final String talker;
    public final String fromUserName;
    public final String toUserName;
    public final long createTime;
    public final String content;
    public final String msgSource;
    public final int flag;
    public final WeChatMessage message;

    public WeChatRecalledMessage(long originSvrId,
                                 long newMsgId,
                                 String talker,
                                 String fromUserName,
                                 String toUserName,
                                 long createTime,
                                 String content,
                                 String msgSource,
                                 int flag,
                                 WeChatMessage message) {
        this.originSvrId = originSvrId;
        this.newMsgId = newMsgId;
        this.talker = talker != null ? talker : "";
        this.fromUserName = fromUserName != null ? fromUserName : "";
        this.toUserName = toUserName != null ? toUserName : "";
        this.createTime = createTime;
        this.content = content != null ? content : "";
        this.msgSource = msgSource != null ? msgSource : "";
        this.flag = flag;
        this.message = message;
    }

    public static WeChatRecalledMessage fromMessage(WeChatMessage message) {
        if (message == null) return null;
        return new WeChatRecalledMessage(
                message.msgSvrId,
                0L,
                message.talker,
                message.sendTalker(),
                "",
                message.createTime,
                message.bodyContent(),
                message.getMsgSource(),
                message.flag,
                message);
    }

    public long bestCreateTime() {
        if (message != null && message.createTime > 0L) return message.createTime;
        return createTime;
    }

    public String bestSender() {
        if (!fromUserName.isEmpty()) return fromUserName;
        if (message != null) return message.sendTalker();
        return "";
    }

    public String bestContent() {
        if (message != null) return message.bodyContent();
        return content;
    }

    public WeChatRecalledMessage merge(WeChatRecalledMessage other) {
        if (other == null) return this;
        return new WeChatRecalledMessage(
                originSvrId > 0L ? originSvrId : other.originSvrId,
                newMsgId > 0L ? newMsgId : other.newMsgId,
                !talker.isEmpty() ? talker : other.talker,
                !fromUserName.isEmpty() ? fromUserName : other.fromUserName,
                !toUserName.isEmpty() ? toUserName : other.toUserName,
                bestCreateTime() > 0L ? bestCreateTime() : other.bestCreateTime(),
                !content.isEmpty() ? content : other.content,
                !msgSource.isEmpty() ? msgSource : other.msgSource,
                flag != 0 ? flag : other.flag,
                message != null ? message : other.message);
    }
}
