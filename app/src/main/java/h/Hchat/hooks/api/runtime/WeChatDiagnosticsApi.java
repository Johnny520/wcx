package h.Hchat.hooks.api.runtime;

import h.Hchat.dexkit.DexFinder;
import h.Hchat.hooks.api.core.WeChatApis;

/**
 * 微信公共 API 自检与诊断。
 */
public final class WeChatDiagnosticsApi {
    public interface Logger {
        void log(String message);
    }

    private final DexFinder dexFinder;
    private final Logger logger;

    public WeChatDiagnosticsApi(DexFinder dexFinder, Logger logger) {
        this.dexFinder = dexFinder;
        this.logger = logger;
    }

    public boolean isAvailable() {
        return true;
    }

    public void dumpReport() {
        String report = buildReport();
        String[] lines = report.split("\n");
        for (String line : lines) {
            if (line.length() > 0) log(line);
        }
    }

    public String buildReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("API自检开始");
        append(sb, "message.sender", WeChatApis.hasMessages());
        append(sb, "message.senderAt", WeChatApis.messages() != null
                && WeChatApis.messages().canSendAt());
        append(sb, "message.senderXml", WeChatApis.messages() != null
                && WeChatApis.messages().canSendXml());
        append(sb, "message.store", WeChatApis.hasMessageStore());
        append(sb, "message.parser", WeChatApis.hasMessageParser());
        append(sb, "message.events", WeChatApis.hasMessageEvents());
        append(sb, "message.types", true);
        append(sb, "message.changes", WeChatApis.hasMessageChanges());
        append(sb, "message.observe", WeChatApis.hasMessageObserve());
        append(sb, "contact.account", WeChatApis.hasAccount());
        append(sb, "contact.contacts", WeChatApis.hasContacts());
        append(sb, "contact.chatrooms", WeChatApis.hasChatrooms());
        append(sb, "contact.users", WeChatApis.hasUsers());
        append(sb, "contact.changes", WeChatApis.hasContactChanges());
        append(sb, "contact.chatroomChanges", WeChatApis.hasChatroomChanges());
        append(sb, "runtime.config", WeChatApis.hasConfig());
        append(sb, "runtime.database", WeChatApis.hasDatabase());
        append(sb, "runtime.databaseReady", databaseReady());
        append(sb, "runtime.databaseChanges", WeChatApis.hasDatabaseChanges());
        append(sb, "runtime.conversationChanges", WeChatApis.hasConversationChanges());
        append(sb, "runtime.storage", WeChatApis.hasStorage());
        append(sb, "runtime.network", WeChatApis.hasNetwork());
        append(sb, "runtime.permissions", WeChatApis.hasPermissions());
        append(sb, "runtime.tasks", WeChatApis.hasTasks());
        append(sb, "runtime.version", WeChatApis.hasVersion());
        append(sb, "payment.transfers", WeChatApis.hasTransfers());
        append(sb, "interaction.ui", WeChatApis.hasUi());
        append(sb, "interaction.notifier", WeChatApis.hasNotifyApi());
        append(sb, "interaction.media", WeChatApis.hasMedia());
        append(sb, "interaction.media.image", WeChatApis.media() != null
                && WeChatApis.media().images().canSendSilently());
        append(sb, "interaction.media.voice", WeChatApis.media() != null
                && WeChatApis.media().voices().canSendSilently());
        append(sb, "interaction.media.video", WeChatApis.media() != null
                && WeChatApis.media().videos().canSendSilently());
        append(sb, "interaction.currentActivity", WeChatApis.hasCurrentActivity());
        append(sb, "interaction.activityStart", WeChatApis.hasActivityStart());
        append(sb, "interaction.lifecycle", WeChatApis.hasLifecycle());
        append(sb, "interaction.chatPage", WeChatApis.hasChatPage());
        appendDex(sb);
        sb.append('\n').append("API自检结束");
        return sb.toString();
    }

    public String buildCompactReport() {
        if (dexFinder == null) {
            return "API自检: dexFinder=false";
        }
        return "API自检: message=" + WeChatApis.hasMessageObserve()
                + " dbChanges=" + WeChatApis.hasDatabaseChanges()
                + " chatPage=" + WeChatApis.hasChatPage()
                + " chatStart=" + methodName(dexFinder.chatPageStartMethod)
                + " chatEnter=" + methodName(dexFinder.chatPageFragmentEnterMethod)
                + " chatExit=" + methodName(dexFinder.chatPageFragmentExitMethod);
    }

    /**
     * Quiet compatibility check. Returns an empty string when all watched
     * capabilities are present, otherwise returns only the missing items.
     */
    public String buildCompatibilityIssues() {
        StringBuilder critical = new StringBuilder();
        StringBuilder optional = new StringBuilder();

        addMissing(critical, "message.observe", WeChatApis.hasMessageObserve());
        addMissing(critical, "message.sender", WeChatApis.hasMessages()
                && dexFinder != null && dexFinder.sendTextMsgClass != null);
        addMissing(critical, "message.store", WeChatApis.hasMessageStore());
        addMissing(critical, "contact.contacts", WeChatApis.hasContacts());
        addMissing(critical, "message.conversations", WeChatApis.hasConversations());
        addMissing(critical, "runtime.network", WeChatApis.hasNetwork()
                && dexFinder != null && dexFinder.netQueueClass != null);
        addMissing(critical, "runtime.databaseChanges", WeChatApis.hasDatabaseChanges());
        addMissing(critical, "runtime.version", WeChatApis.hasVersion());

        addMissing(optional, "contact.groupMemberDisplayName",
                dexFinder != null && dexFinder.groupMemberDisplayNameMethod != null);
        addMissing(optional, "message.senderAt",
                WeChatApis.messages() != null && WeChatApis.messages().canSendAt());
        addMissing(optional, "message.senderXml",
                WeChatApis.messages() != null && WeChatApis.messages().canSendXml());
        addMissing(optional, "interaction.media.image",
                WeChatApis.media() != null && WeChatApis.media().images().canSendSilently());
        addMissing(optional, "interaction.media.voice",
                WeChatApis.media() != null && WeChatApis.media().voices().canSendSilently());
        addMissing(optional, "interaction.media.video",
                WeChatApis.media() != null && WeChatApis.media().videos().canSendSilently());
        addMissing(optional, "interaction.chatPage.start",
                dexFinder != null && dexFinder.chatPageStartMethod != null);
        addMissing(optional, "interaction.chatPage.fragmentEnter",
                dexFinder != null && dexFinder.chatPageFragmentEnterMethod != null);
        addMissing(optional, "interaction.chatPage.fragmentExit",
                dexFinder != null && dexFinder.chatPageFragmentExitMethod != null);
        addMissing(optional, "dex.addMsg",
                dexFinder != null && dexFinder.addMsgClasses != null
                        && !dexFinder.addMsgClasses.isEmpty());
        addMissing(optional, "dex.receiveLuckyMoney",
                dexFinder != null && dexFinder.receiveLuckyMoneyClass != null);
        addMissing(optional, "dex.openLuckyMoney",
                dexFinder != null && dexFinder.openLuckyMoneyClass != null);
        addMissing(optional, "payment.transfers",
                WeChatApis.hasTransfers()
                        && dexFinder != null && dexFinder.transferOperationClass != null);
        addMissing(optional, "contact.verifyUser",
                dexFinder != null && dexFinder.verifyUserClass != null);

        if (critical.length() == 0 && optional.length() == 0) {
            return "";
        }

        StringBuilder report = new StringBuilder("版本兼容自检缺失");
        if (critical.length() > 0) {
            report.append(": critical=").append(critical);
        }
        if (optional.length() > 0) {
            report.append(critical.length() > 0 ? " " : ": ")
                    .append("optional=").append(optional);
        }
        return report.toString();
    }

    private boolean databaseReady() {
        WeChatDatabaseApi api = WeChatApis.database();
        return api != null && api.isReady();
    }

    private void appendDex(StringBuilder sb) {
        if (dexFinder == null) {
            sb.append('\n').append("dexFinder=false");
            return;
        }
        sb.append('\n').append("dex.addMsgClasses=")
                .append(dexFinder.addMsgClasses != null ? dexFinder.addMsgClasses.size() : 0);
        sb.append('\n').append("dex.receiveLuckyMoney=")
                .append(className(dexFinder.receiveLuckyMoneyClass));
        sb.append('\n').append("dex.openLuckyMoney=")
                .append(className(dexFinder.openLuckyMoneyClass));
        sb.append('\n').append("dex.netQueue=")
                .append(className(dexFinder.netQueueClass));
        sb.append('\n').append("dex.netQueueCandidates=")
                .append(dexFinder.netQueueCandidateClasses != null
                        ? dexFinder.netQueueCandidateClasses.size() : 0);
        sb.append('\n').append("dex.sendTextMsg=")
                .append(className(dexFinder.sendTextMsgClass));
        sb.append('\n').append("dex.sendXmlAppMsg=")
                .append(methodName(dexFinder.sendXmlAppMsgMethod));
        sb.append('\n').append("dex.appMsgParse=")
                .append(methodName(dexFinder.appMsgParseMethod));
        sb.append('\n').append("dex.serviceGetter=")
                .append(methodName(dexFinder.serviceGetterMethod));
        sb.append('\n').append("dex.sendImage=")
                .append(methodName(dexFinder.sendImageMethod));
        sb.append('\n').append("dex.sendFile=")
                .append(methodName(dexFinder.sendFileMethod));
        sb.append('\n').append("dex.sendVideoLegacy=")
                .append(methodName(dexFinder.sendVideoMethod));
        sb.append('\n').append("dex.sendVideoTask=")
                .append(className(dexFinder.sendVideoTaskClass));
        sb.append('\n').append("dex.transferOperation=")
                .append(className(dexFinder.transferOperationClass));
        sb.append('\n').append("dex.verifyUser=")
                .append(className(dexFinder.verifyUserClass));
        sb.append('\n').append("dex.voiceStart=")
                .append(methodName(dexFinder.voiceStartRecordMethod));
        sb.append('\n').append("dex.voicePath=")
                .append(methodName(dexFinder.voiceFullPathMethod));
        sb.append('\n').append("dex.voiceFinish=")
                .append(methodName(dexFinder.voiceFinishRecordMethod));
        sb.append('\n').append("dex.voiceUpload=")
                .append(className(dexFinder.voiceUploadClass));
        sb.append('\n').append("dex.emojiSend=")
                .append(methodName(dexFinder.emojiSendMethod));
        sb.append('\n').append("dex.emojiGetByMd5=")
                .append(methodName(dexFinder.emojiGetByMd5Method));
        sb.append('\n').append("dex.emojiCreateInfo=")
                .append(methodName(dexFinder.emojiCreateInfoMethod));
        sb.append('\n').append("dex.emojiAccPath=")
                .append(methodName(dexFinder.emojiAccPathMethod));
        sb.append('\n').append("dex.emojiCheckGif=")
                .append(methodName(dexFinder.emojiCheckGifMethod));
        sb.append('\n').append("dex.emojiFilePath=")
                .append(methodName(dexFinder.emojiFilePathMethod));
        sb.append('\n').append("dex.sqliteDbWrapper=")
                .append(className(dexFinder.sqliteDbWrapperClass));
        sb.append('\n').append("dex.groupMemberDisplayName=")
                .append(dexFinder.groupMemberDisplayNameMethod != null
                        ? dexFinder.groupMemberDisplayNameMethod.getDeclaringClass().getName()
                        + "#" + dexFinder.groupMemberDisplayNameMethod.getName()
                        : "null");
        sb.append('\n').append("dex.chatPageStart=")
                .append(methodName(dexFinder.chatPageStartMethod));
        sb.append('\n').append("dex.chatPageFragmentEnter=")
                .append(methodName(dexFinder.chatPageFragmentEnterMethod));
        sb.append('\n').append("dex.chatPageFragmentExit=")
                .append(methodName(dexFinder.chatPageFragmentExitMethod));
    }

    private void append(StringBuilder sb, String name, boolean available) {
        sb.append('\n').append(name).append('=').append(available);
    }

    private void addMissing(StringBuilder sb, String name, boolean available) {
        if (available) return;
        if (sb.length() > 0) sb.append(',');
        sb.append(name);
    }

    private String className(Class<?> clazz) {
        return clazz != null ? clazz.getName() : "null";
    }

    private String methodName(java.lang.reflect.Method method) {
        if (method == null) return "null";
        return method.getDeclaringClass().getName() + "#" + method.getName();
    }

    private void log(String message) {
        if (logger != null) logger.log("[WeChatDiagnosticsApi] " + message);
    }
}
