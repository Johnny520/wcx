package h.Hchat.hooks.core

import h.Hchat.hooks.api.core.WechatApiFeature
import h.Hchat.hooks.items.audiotransform.AudioTransformFeature
import h.Hchat.hooks.items.antirecall.AntiRecallFeature
import h.Hchat.hooks.items.atallnotify.AtAllNotificationBlockFeature
import h.Hchat.hooks.items.automessageforward.AutoMessageForwardFeature
import h.Hchat.hooks.items.autoreply.AutoReplyFeature
import h.Hchat.hooks.items.autooriginal.AutoOriginalImageFeature
import h.Hchat.hooks.items.autovieworiginal.AutoViewOriginalFeature
import h.Hchat.hooks.items.callmedialimit.CallMediaLimitFeature
import h.Hchat.hooks.items.callmedialimit.CallRingtoneBlockFeature
import h.Hchat.hooks.items.chattime.ChatTimeStyleFeature
import h.Hchat.hooks.items.conversationgroup.ConversationGroupFeature
import h.Hchat.hooks.items.conversationtabs.ConversationTabsFeature
import h.Hchat.hooks.items.custombottombar.CustomBottomBarFeature
import h.Hchat.hooks.items.customfriendavatar.CustomFriendAvatarFeature
import h.Hchat.hooks.items.customnotify.CustomNotificationFeature
import h.Hchat.hooks.items.editmsg.EditMessageFeature
import h.Hchat.hooks.items.emojisave.EmojiSaveFeature
import h.Hchat.hooks.items.fakelocation.FakeLocationFeature
import h.Hchat.hooks.items.fakescancamera.FakeScanCameraFeature
import h.Hchat.hooks.items.fakevoiceduration.FakeVoiceDurationFeature
import h.Hchat.hooks.items.floatingshortcut.FloatingShortcutFeature
import h.Hchat.hooks.items.forwardlimit.RemoveForwardLimitFeature
import h.Hchat.hooks.items.gameemoji.GameEmojiFeature
import h.Hchat.hooks.items.groupleave.GroupLeaveMonitorFeature
import h.Hchat.hooks.items.grouplabel.GroupChatLabelFeature
import h.Hchat.hooks.items.grouplabel.QuickGroupChatLabelFeature
import h.Hchat.hooks.items.grouprename.GroupRenameMonitorFeature
import h.Hchat.hooks.items.hideavatar.HideChatAvatarFeature
import h.Hchat.hooks.items.hidemenu.HideChatMenuFeature
import h.Hchat.hooks.items.hotupdate.DisableHotUpdateFeature
import h.Hchat.hooks.items.hometextcolor.HomeTextColorFeature
import h.Hchat.hooks.items.inputhint.InputHintFeature
import h.Hchat.hooks.items.legacychatinput.LegacyChatInputFeature
import h.Hchat.hooks.items.linkparse.LinkParseFeature
import h.Hchat.hooks.items.keepalive.WeChatKeepAliveFeature
import h.Hchat.hooks.items.keywordnotify.KeywordNotificationFeature
import h.Hchat.hooks.items.membertitle.MemberTitleFeature
import h.Hchat.hooks.items.messageaffix.MessageAffixFeature
import h.Hchat.hooks.items.messagebubble.MessageBubbleFeature
import h.Hchat.hooks.items.messagetextcolor.MessageTextColorFeature
import h.Hchat.hooks.items.messageblock.MessageBlockFeature
import h.Hchat.hooks.items.messageforward.MessageForwardFeature
import h.Hchat.hooks.items.miniprogrambaselib.FakeMiniProgramBaseLibFeature
import h.Hchat.hooks.items.miniprogramsplashad.SkipGlobalMiniProgramSplashAdsFeature
import h.Hchat.hooks.items.miniprogramvideoad.SkipMiniProgramVideoAdsFeature
import h.Hchat.hooks.items.musicorder.QQMusicOrderFeature
import h.Hchat.hooks.items.moments.OriginalMomentsUploadFeature
import h.Hchat.hooks.items.moments.MomentsAutoLikeFeature
import h.Hchat.hooks.items.moments.MomentsAutoCommentFeature
import h.Hchat.hooks.items.moments.MomentsAutoForwardFeature
import h.Hchat.hooks.items.moments.MomentsAutoRefreshFeature
import h.Hchat.hooks.items.moments.MomentsBottomDetailFeature
import h.Hchat.hooks.items.moments.MomentsContactFilterFeature
import h.Hchat.hooks.items.moments.MomentsKeywordBlockFeature
import h.Hchat.hooks.items.moments.MomentsPostNotificationFeature
import h.Hchat.hooks.items.moments.RemoveMomentsAdsFeature
import h.Hchat.hooks.items.moments.SnsAntiRecallFeature
import h.Hchat.hooks.items.moments.MomentsUploadTailFeature
import h.Hchat.hooks.items.momentsfake.MomentsFakeInteractionFeature
import h.Hchat.hooks.items.multirecall.MultiRecallFeature
import h.Hchat.hooks.items.payment.core.AutoRedPacketFeature
import h.Hchat.hooks.items.payment.fakebalance.FakeWalletBalanceFeature
import h.Hchat.hooks.items.payment.gift.AutoGiftFeature
import h.Hchat.hooks.items.payment.transfer.AutoTransferFeature
import h.Hchat.hooks.items.patblock.PatBlockFeature
import h.Hchat.hooks.items.profileid.ProfileIdFeature
import h.Hchat.hooks.items.protobuf.ProtobufPacketFeature
import h.Hchat.hooks.items.quoteclear.QuoteDeleteClearFeature
import h.Hchat.hooks.items.quickcontactedit.QuickContactEditFeature
import h.Hchat.hooks.items.quickmoments.QuickMomentsFeature
import h.Hchat.hooks.items.quickread.QuickMarkReadFeature
import h.Hchat.hooks.items.quickterminate.QuickTerminateFeature
import h.Hchat.hooks.items.realtail.RealNameTailFeature
import h.Hchat.hooks.items.roundavatar.RoundAvatarFeature

import h.Hchat.hooks.items.script.ScriptPluginFeature
import h.Hchat.hooks.items.scheduledtask.ScheduledTaskFeature
import h.Hchat.hooks.items.selectedmessages.SelectedMessagesFeature
import h.Hchat.hooks.items.settings.SettingsFeature
import h.Hchat.hooks.items.shortvideo.FinderMediaDownloadFeature
import h.Hchat.hooks.items.statuslimit.StatusTextLimitFeature
import h.Hchat.hooks.items.swipequote.SwipeQuoteFeature
import h.Hchat.hooks.items.tablet.WeChatTabletFeature
import h.Hchat.hooks.items.textspeech.TextSpeechFeature
import h.Hchat.hooks.items.textvoice.TextVoiceFeature
import h.Hchat.hooks.items.typingreport.TypingReportBlockFeature
import h.Hchat.hooks.items.voiceforward.VoiceForwardFeature
import h.Hchat.hooks.items.hchatextra.HchatExtraFeature
import h.Hchat.hooks.items.voicepreview.VoicePreviewFeature
import h.Hchat.hooks.items.zombiecheck.ZombieCheckFeature

/**
 * 全部功能的集中注册表。
 */
object FeatureRegistry {
    @JvmStatic
    fun createDefaultManager(): FeatureManager {
        return FeatureManager()
            .register(SettingsFeature())
            .register(WechatApiFeature())

            .register(FloatingShortcutFeature())
            .register(CustomBottomBarFeature())
            .register(RoundAvatarFeature())
            .register(CustomFriendAvatarFeature())
            .register(RealNameTailFeature())
            .register(MemberTitleFeature())
            .register(AutoRedPacketFeature())
            .register(AutoGiftFeature())
            .register(WeChatTabletFeature())
            .register(AutoTransferFeature())
            .register(FakeWalletBalanceFeature())
            .register(AntiRecallFeature())
            .register(MultiRecallFeature())
            .register(DisableHotUpdateFeature())
            .register(ProfileIdFeature())
            .register(HideChatMenuFeature())
            .register(QuickContactEditFeature())
            .register(QuickGroupChatLabelFeature())
            .register(QuickMomentsFeature())
            .register(QuickMarkReadFeature())
            .register(QuickTerminateFeature())
            .register(AutoReplyFeature())
            .register(AutoMessageForwardFeature())
            .register(ConversationGroupFeature())
            .register(ConversationTabsFeature())
            .register(MessageAffixFeature())
            .register(TypingReportBlockFeature())
            .register(PatBlockFeature())
            .register(AutoOriginalImageFeature())
            .register(AutoViewOriginalFeature())
            .register(RemoveForwardLimitFeature())
            .register(AtAllNotificationBlockFeature())
            .register(CallMediaLimitFeature())
            .register(CallRingtoneBlockFeature())
            .register(CustomNotificationFeature())
            .register(KeywordNotificationFeature())
            .register(TextSpeechFeature())
            .register(TextVoiceFeature())
            .register(ZombieCheckFeature())
            .register(WeChatKeepAliveFeature())
            .register(QuoteDeleteClearFeature())
            .register(SwipeQuoteFeature())
            .register(AudioTransformFeature())
            .register(FakeVoiceDurationFeature())
            .register(ChatTimeStyleFeature())
            .register(InputHintFeature())
            .register(LegacyChatInputFeature())
            .register(LinkParseFeature())
            .register(MessageBubbleFeature())
            .register(MessageTextColorFeature())
            .register(HomeTextColorFeature())
            .register(HideChatAvatarFeature())
            .register(MessageBlockFeature())
            .register(FakeLocationFeature())
            .register(GameEmojiFeature())
            .register(FakeMiniProgramBaseLibFeature())
            .register(SkipMiniProgramVideoAdsFeature())
            .register(SkipGlobalMiniProgramSplashAdsFeature())
            .register(QQMusicOrderFeature())
            .register(MomentsAutoLikeFeature())
            .register(MomentsAutoCommentFeature())
            .register(MomentsAutoForwardFeature())
            .register(MomentsAutoRefreshFeature())
            .register(MomentsKeywordBlockFeature())
            .register(MomentsContactFilterFeature())
            .register(MomentsBottomDetailFeature())
            .register(MomentsPostNotificationFeature())
            .register(OriginalMomentsUploadFeature())
            .register(MomentsUploadTailFeature())
            .register(SnsAntiRecallFeature())
            .register(MomentsFakeInteractionFeature())
            .register(GroupChatLabelFeature())
            .register(GroupLeaveMonitorFeature())
            .register(GroupRenameMonitorFeature())
            .register(RemoveMomentsAdsFeature())
            .register(EditMessageFeature())
            .register(EmojiSaveFeature())
            .register(VoicePreviewFeature())
            .register(VoiceForwardFeature())
            .register(SelectedMessagesFeature())
            .register(MessageForwardFeature())
            .register(ScheduledTaskFeature())
            .register(FakeScanCameraFeature())
            .register(ProtobufPacketFeature())
            .register(StatusTextLimitFeature())
            .register(FinderMediaDownloadFeature())
            .register(HchatExtraFeature())
            .register(ScriptPluginFeature())
    }
}
