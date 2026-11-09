package h.Hchat.ui.miuix

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ThumbnailUtils
import android.os.Build
import android.os.Handler
import android.os.Bundle
import android.os.Looper
import android.os.Parcelable
import android.os.SystemClock
import android.provider.OpenableColumns
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.DatePicker
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.MediaController
import android.widget.TimePicker
import android.widget.Toast
import android.widget.VideoView
import h.Hchat.BuildConfig
import com.Johnny.wcx.R
import h.Hchat.crash.CrashReportRuntime
import h.Hchat.crash.CrashReportSettings
import h.Hchat.crash.CrashReportSettingsProvider
import h.Hchat.hooks.api.ui.HchatAgentIconDrawable
import h.Hchat.hooks.items.floatingshortcut.FloatingShortcutFeature
import h.Hchat.hooks.items.script.market.PluginMarketClient
import h.Hchat.hooks.items.script.market.PluginMarketSettings
import h.Hchat.hooks.items.floatingshortcut.FloatingShortcutGlyph
import h.Hchat.hooks.items.floatingshortcut.FloatingShortcutGlyphDrawable
import h.Hchat.hooks.items.floatingshortcut.FloatingShortcutGlyphs
import h.Hchat.hooks.items.floatingshortcut.FloatingShortcutIconPickResult
import h.Hchat.hooks.items.floatingshortcut.FloatingShortcutIconPicker
import h.Hchat.hooks.items.floatingshortcut.FloatingShortcutIconStore
import h.Hchat.hooks.items.floatingshortcut.FloatingShortcutItem
import h.Hchat.hooks.items.floatingshortcut.FloatingShortcutRuntime
import h.Hchat.hooks.items.floatingshortcut.FloatingShortcutSettings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.media.WeChatFavoriteItem
import h.Hchat.hooks.api.message.WeChatMessageObserveApi
import h.Hchat.hooks.api.model.ContactLabelBean
import h.Hchat.hooks.api.model.WeChatContact
import h.Hchat.hooks.items.audiotransform.AudioTransformFeature
import h.Hchat.hooks.items.audiotransform.AudioTransformCancelledException
import h.Hchat.hooks.items.audiotransform.AudioTransformSegmenter
import h.Hchat.hooks.items.audiotransform.AudioTransformSettings
import h.Hchat.hooks.items.antirecall.AntiRecallFeature
import h.Hchat.hooks.items.antirecall.AntiRecallSettings
import h.Hchat.hooks.items.autoreply.AutoReplyFeature
import h.Hchat.hooks.items.autoreply.AutoReplyRule
import h.Hchat.hooks.items.autoreply.AutoReplyRuntime
import h.Hchat.hooks.items.autoreply.AutoReplySettings
import h.Hchat.hooks.items.autoreply.AutoReplyStep
import h.Hchat.hooks.items.autoreply.XiaozhiMcpStatus
import h.Hchat.hooks.items.autoreply.AutoReplyXiaozhiConfig
import h.Hchat.hooks.items.autoreply.AutoReplyZhiliaConfig
import h.Hchat.hooks.items.autoreply.XiaozhiAgentOption
import h.Hchat.hooks.items.autoreply.XiaozhiConsoleApi
import h.Hchat.hooks.items.autoreply.XiaozhiModelOption
import h.Hchat.hooks.items.autoreply.XiaozhiVoiceOption
import h.Hchat.hooks.items.autooriginal.AutoOriginalImageFeature
import h.Hchat.hooks.items.autooriginal.AutoOriginalImageSettings
import h.Hchat.hooks.items.autovieworiginal.AutoViewOriginalFeature
import h.Hchat.hooks.items.autovieworiginal.AutoViewOriginalSettings
import h.Hchat.hooks.items.atallnotify.AtAllNotificationBlockFeature
import h.Hchat.hooks.items.atallnotify.AtAllNotificationBlockSettings
import h.Hchat.hooks.items.automessageforward.AutoMessageForwardFeature
import h.Hchat.hooks.items.automessageforward.AutoMessageForwardRule
import h.Hchat.hooks.items.automessageforward.AutoMessageForwardSettings
import h.Hchat.hooks.items.callmedialimit.CallMediaLimitFeature
import h.Hchat.hooks.items.callmedialimit.CallMediaLimitSettings
import h.Hchat.hooks.items.callmedialimit.CallRingtoneBlockFeature
import h.Hchat.hooks.items.chattime.ChatTimeStyleFeature
import h.Hchat.hooks.items.chattime.ChatTimeStyleSettings
import h.Hchat.hooks.items.conversationgroup.ConversationGroup
import h.Hchat.hooks.items.conversationgroup.ConversationGroupFeature
import h.Hchat.hooks.items.conversationgroup.ConversationGroupPickerSupport
import h.Hchat.hooks.items.conversationgroup.ConversationGroupRuntime
import h.Hchat.hooks.items.conversationgroup.ConversationGroupStore
import h.Hchat.hooks.items.conversationtabs.ConversationTabsFeature
import h.Hchat.hooks.items.conversationtabs.ConversationTabsStore
import h.Hchat.hooks.items.custombottombar.CustomBottomBarFeature
import h.Hchat.hooks.items.custombottombar.FloatingBottomBarSettings
import h.Hchat.hooks.items.customnotify.CustomNotificationFeature
import h.Hchat.hooks.items.customnotify.CustomNotificationRule
import h.Hchat.hooks.items.customnotify.CustomNotificationRuntime
import h.Hchat.hooks.items.customfriendavatar.CustomFriendAvatarFeature
import h.Hchat.hooks.items.customfriendavatar.CustomFriendAvatarPicker
import h.Hchat.hooks.items.customfriendavatar.CustomFriendAvatarSettings
import h.Hchat.hooks.items.customfriendavatar.CustomFriendAvatarStore
import h.Hchat.hooks.items.customnotify.CustomNotificationSettings
import h.Hchat.hooks.items.editmsg.EditMessageFeature
import h.Hchat.hooks.items.editmsg.EditMessageSettings
import h.Hchat.hooks.items.emojisave.EmojiSaveFeature
import h.Hchat.hooks.items.emojisave.EmojiSaveSettings
import h.Hchat.hooks.items.fakelocation.FakeLocationFeature
import h.Hchat.hooks.items.fakelocation.FakeLocationSettings
import h.Hchat.hooks.items.fakescancamera.FakeScanCameraFeature
import h.Hchat.hooks.items.fakescancamera.FakeScanCameraSettings
import h.Hchat.hooks.items.fakevoiceduration.FakeVoiceDurationFeature
import h.Hchat.hooks.items.fakevoiceduration.FakeVoiceDurationSettings
import h.Hchat.hooks.items.forwardlimit.RemoveForwardLimitFeature
import h.Hchat.hooks.items.forwardlimit.RemoveForwardLimitSettings
import h.Hchat.hooks.items.gameemoji.GameEmojiFeature
import h.Hchat.hooks.items.gameemoji.GameEmojiSettings
import h.Hchat.hooks.items.groupleave.GroupLeaveMonitorFeature
import h.Hchat.hooks.items.groupleave.GroupLeaveReplyTemplate
import h.Hchat.hooks.items.groupleave.GroupLeaveReplyTemplateBinding
import h.Hchat.hooks.items.groupleave.GroupLeaveMonitorSettings
import h.Hchat.hooks.items.grouplabel.GroupChatLabel
import h.Hchat.hooks.items.grouplabel.GroupChatLabelFeature
import h.Hchat.hooks.items.grouplabel.GroupChatLabelStore
import h.Hchat.hooks.items.grouplabel.QuickGroupChatLabelFeature
import h.Hchat.hooks.items.grouplabel.QuickGroupChatLabelSettings
import h.Hchat.hooks.items.groupnicknamecolor.GroupNicknameColorSettings
import h.Hchat.hooks.items.groupnicknamecolor.GroupNicknameColorStore
import h.Hchat.hooks.items.grouprename.GroupRenameMonitorFeature
import h.Hchat.hooks.items.grouprename.GroupRenameReplyTemplate
import h.Hchat.hooks.items.grouprename.GroupRenameMonitorSettings
import h.Hchat.hooks.items.grouprename.GroupRenameTemplateBinding
import h.Hchat.hooks.items.hideavatar.HideChatAvatarFeature
import h.Hchat.hooks.items.hideavatar.HideChatAvatarSettings
import h.Hchat.hooks.items.hidemenu.HideChatMenuFeature
import h.Hchat.hooks.items.hidemenu.HideChatMenuSettings
import h.Hchat.hooks.items.hotupdate.DisableHotUpdateFeature
import h.Hchat.hooks.items.hotupdate.DisableHotUpdateSettings
import h.Hchat.hooks.items.hometextcolor.HomeTextColorFeature
import h.Hchat.hooks.items.hometextcolor.HomeTextColorSettings
import h.Hchat.hooks.items.inputhint.InputHintFeature
import h.Hchat.hooks.items.inputhint.InputHintSettings
import h.Hchat.hooks.items.inputhint.InputHintStats
import h.Hchat.hooks.items.legacychatinput.LegacyChatInputFeature
import h.Hchat.hooks.items.legacychatinput.LegacyChatInputSettings
import h.Hchat.hooks.items.inputhint.OutgoingMessageStatsRepository
import h.Hchat.hooks.items.linkparse.LinkParseFeature
import h.Hchat.hooks.items.linkparse.LinkParseSettings
import h.Hchat.hooks.items.keepalive.WeChatKeepAliveFeature
import h.Hchat.hooks.items.keepalive.WeChatKeepAliveRuntime
import h.Hchat.hooks.items.keepalive.WeChatKeepAliveSettings
import h.Hchat.hooks.items.keywordnotify.KeywordNotificationFeature
import h.Hchat.hooks.items.keywordnotify.KeywordNotificationRuntime
import h.Hchat.hooks.items.keywordnotify.KeywordNotificationSettings
import h.Hchat.hooks.items.keywordnotify.KeywordRule
import h.Hchat.hooks.items.zombiecheck.ZombieCheckController
import h.Hchat.hooks.items.zombiecheck.ZombieCheckActionResult
import h.Hchat.hooks.items.zombiecheck.ZombieCheckFeature
import h.Hchat.hooks.items.zombiecheck.ZombieCheckResultType
import h.Hchat.hooks.items.zombiecheck.ZombieCheckSettings
import h.Hchat.hooks.items.membertitle.MemberTitleFeature
import h.Hchat.hooks.items.membertitle.MemberTitleSettings
import h.Hchat.hooks.items.membertitle.MemberTitleStore
import h.Hchat.hooks.items.messageaffix.MessageAffixFeature
import h.Hchat.hooks.items.messageaffix.MessageAffixSettings
import h.Hchat.hooks.items.messagebubble.MessageBubbleFeature
import h.Hchat.hooks.items.messagebubble.MessageBubblePickResult
import h.Hchat.hooks.items.messagebubble.MessageBubblePicker
import h.Hchat.hooks.items.messagebubble.MessageBubbleSettings
import h.Hchat.hooks.items.messagebubble.MessageBubbleSlot
import h.Hchat.hooks.items.messagebubble.MessageBubbleStore
import h.Hchat.hooks.items.messagebubble.MessageBubbleKind
import h.Hchat.hooks.items.messagetextcolor.MessageTextColorFeature
import h.Hchat.hooks.items.messagetextcolor.MessageTextColorSettings
import h.Hchat.hooks.items.messageblock.MessageBlockBinding
import h.Hchat.hooks.items.messageblock.MessageBlockDefaultRule
import h.Hchat.hooks.items.messageblock.MessageBlockFeature
import h.Hchat.hooks.items.messageblock.MessageBlockSettings
import h.Hchat.hooks.items.messageblock.MessageBlockTemplate
import h.Hchat.hooks.items.messageforward.MessageForwardFeature
import h.Hchat.hooks.items.messageforward.MessageForwardSettings
import h.Hchat.hooks.items.miniprogrambaselib.FakeMiniProgramBaseLibFeature
import h.Hchat.hooks.items.miniprogrambaselib.FakeMiniProgramBaseLibSettings
import h.Hchat.hooks.items.miniprogramsplashad.SkipGlobalMiniProgramSplashAdsFeature
import h.Hchat.hooks.items.miniprogramsplashad.SkipGlobalMiniProgramSplashAdsSettings
import h.Hchat.hooks.items.miniprogramvideoad.SkipMiniProgramVideoAdsFeature
import h.Hchat.hooks.items.miniprogramvideoad.SkipMiniProgramVideoAdsSettings
import h.Hchat.hooks.items.musicorder.QQMusicOrderFeature
import h.Hchat.hooks.items.musicorder.QQMusicOrderSettings
import h.Hchat.hooks.items.moments.OriginalMomentsUploadFeature
import h.Hchat.hooks.items.moments.OriginalMomentsUploadSettings
import h.Hchat.hooks.items.moments.MomentsAutoCommentFeature
import h.Hchat.hooks.items.moments.MomentsAutoCommentSettings
import h.Hchat.hooks.items.moments.MomentsAutoLikeFeature
import h.Hchat.hooks.items.moments.MomentsAutoLikeSettings
import h.Hchat.hooks.items.moments.MomentsAutoForwardFeature
import h.Hchat.hooks.items.moments.MomentsAutoForwardSettings
import h.Hchat.hooks.items.moments.MomentsAutoRefreshFeature
import h.Hchat.hooks.items.moments.MomentsAutoRefreshSettings
import h.Hchat.hooks.items.moments.MomentsBottomDetailFeature
import h.Hchat.hooks.items.moments.MomentsBottomDetailSettings
import h.Hchat.hooks.items.moments.MomentsContactFilterFeature
import h.Hchat.hooks.items.moments.MomentsContactFilterSettings
import h.Hchat.hooks.items.moments.MomentsKeywordBlockFeature
import h.Hchat.hooks.items.moments.MomentsKeywordBlockSettings
import h.Hchat.hooks.items.moments.MomentsPostNotificationFeature
import h.Hchat.hooks.items.moments.MomentsPostNotificationSettings
import h.Hchat.hooks.items.moments.MomentsUploadTailFeature
import h.Hchat.hooks.items.moments.MomentsUploadTailPresets
import h.Hchat.hooks.items.moments.MomentsUploadTailSettings
import h.Hchat.hooks.items.moments.RemoveMomentsAdsFeature
import h.Hchat.hooks.items.moments.RemoveMomentsAdsSettings
import h.Hchat.hooks.items.moments.SnsAntiRecallFeature
import h.Hchat.hooks.items.moments.SnsAntiRecallSettings
import h.Hchat.hooks.items.momentsfake.MomentsFakeCommentSettingsProvider
import h.Hchat.hooks.items.momentsfake.MomentsFakeForwardRuntimeRegistry
import h.Hchat.hooks.items.momentsfake.MomentsFakeForwardSettingsProvider
import h.Hchat.hooks.items.momentsfake.MomentsFakeLikeSettingsProvider
import h.Hchat.hooks.items.momentsfake.MomentsFakeLikeCandidateRepository
import h.Hchat.hooks.items.momentsfake.MomentsFakeInteractionRuntimeRegistry
import h.Hchat.hooks.items.momentsfake.MomentsFakeInteractionSettings
import h.Hchat.hooks.items.multirecall.MultiRecallFeature
import h.Hchat.hooks.items.multirecall.MultiRecallSettings
import h.Hchat.hooks.items.payment.core.AutoRedPacketFeature
import h.Hchat.hooks.items.payment.core.PaymentTemplateTimeFormatter
import h.Hchat.hooks.items.payment.core.RedPacketReplyStep
import h.Hchat.hooks.items.payment.core.RedPacketRuleBinding
import h.Hchat.hooks.items.payment.core.RedPacketRuleConfig
import h.Hchat.hooks.items.payment.core.RedPacketRuleTemplate
import h.Hchat.hooks.items.payment.core.RedPacketSettings
import h.Hchat.hooks.items.payment.fakebalance.FakeWalletBalanceFeature
import h.Hchat.hooks.items.payment.fakebalance.FakeWalletBalanceSettings
import h.Hchat.hooks.items.payment.gift.AutoGiftFeature
import h.Hchat.hooks.items.payment.gift.GiftSettings
import h.Hchat.hooks.items.payment.transfer.AutoTransferFeature
import h.Hchat.hooks.items.payment.transfer.AutoTransferSettings
import h.Hchat.hooks.items.payment.transfer.TransferReceiveAccountStore
import h.Hchat.hooks.items.payment.transfer.TransferRuleBinding
import h.Hchat.hooks.items.payment.transfer.TransferRuleConfig
import h.Hchat.hooks.items.payment.transfer.TransferRuleTemplate
import h.Hchat.hooks.items.patblock.PatBlockFeature
import h.Hchat.hooks.items.patblock.PatBlockSettings
import h.Hchat.hooks.items.profileid.ProfileIdFeature
import h.Hchat.hooks.items.profileid.ProfileIdSettings
import h.Hchat.hooks.items.protobuf.ProtobufPacketFeature
import h.Hchat.hooks.items.protobuf.ProtobufPacketRuntime
import h.Hchat.hooks.items.protobuf.ProtobufPacketSettings
import h.Hchat.hooks.items.quoteclear.QuoteDeleteClearFeature
import h.Hchat.hooks.items.quoteclear.QuoteDeleteClearSettings
import h.Hchat.hooks.items.quickcontactedit.QuickContactEditFeature
import h.Hchat.hooks.items.quickcontactedit.QuickContactEditSettings
import h.Hchat.hooks.items.quickmoments.QuickMomentsFeature
import h.Hchat.hooks.items.quickmoments.QuickMomentsSettings
import h.Hchat.hooks.items.quickread.QuickMarkReadFeature
import h.Hchat.hooks.items.quickread.QuickMarkReadSettings
import h.Hchat.hooks.items.quickterminate.QuickTerminateFeature
import h.Hchat.hooks.items.quickterminate.QuickTerminateSettings
import h.Hchat.hooks.items.realtail.RealNameTailFeature
import h.Hchat.hooks.items.realtail.RealNameTailSettings
import h.Hchat.hooks.items.realtail.RealNameTailStore
import h.Hchat.hooks.items.roundavatar.RoundAvatarFeature
import h.Hchat.hooks.items.roundavatar.RoundAvatarSettings
import h.Hchat.hooks.items.script.ScriptPluginFeature
import h.Hchat.hooks.items.script.ScriptPluginManager
import h.Hchat.hooks.items.script.ScriptPluginRuntime
import h.Hchat.hooks.items.script.ScriptPluginSettings
import h.Hchat.hooks.items.script.market.PluginMarketHistoryVersion
import h.Hchat.hooks.items.script.market.PluginMarketInstaller
import h.Hchat.hooks.items.script.market.PluginMarketException
import h.Hchat.hooks.items.script.market.PluginMarketFile
import h.Hchat.hooks.items.script.market.PluginMarketComment
import h.Hchat.hooks.items.script.market.PluginMarketNotification
import h.Hchat.hooks.items.script.market.PluginMarketPlugin
import h.Hchat.hooks.items.script.market.PluginMarketRepository
import h.Hchat.hooks.items.script.market.PluginMarketReviewStatus
import h.Hchat.hooks.items.script.market.PluginMarketUserIdentity
import h.Hchat.hooks.items.scheduledtask.ScheduledTaskContentItem
import h.Hchat.hooks.items.scheduledtask.ScheduledTaskFeature
import h.Hchat.hooks.items.scheduledtask.ScheduledTaskItem
import h.Hchat.hooks.items.scheduledtask.ScheduledTaskRuntimeCoordinator
import h.Hchat.hooks.items.scheduledtask.ScheduledTaskSettings
import h.Hchat.hooks.items.selectedmessages.SelectedMessageSnapshot
import h.Hchat.hooks.items.selectedmessages.SelectedMessagesFeature
import h.Hchat.hooks.items.selectedmessages.SelectedMessagesRuntimeCoordinator
import h.Hchat.hooks.items.selectedmessages.SelectedMessagesSettings
import h.Hchat.hooks.items.settings.SettingsEntrySettings
import h.Hchat.hooks.items.settings.SettingsFeature
import h.Hchat.hooks.items.shortvideo.FinderMediaDownloadFeature
import h.Hchat.hooks.items.shortvideo.FinderMediaDownloadSettings
import h.Hchat.hooks.items.statuslimit.StatusTextLimitFeature
import h.Hchat.hooks.items.statuslimit.StatusTextLimitSettings
import h.Hchat.hooks.items.swipequote.SwipeQuoteFeature
import h.Hchat.hooks.items.swipequote.SwipeQuoteSettings
import h.Hchat.hooks.items.tablet.WeChatTabletFeature
import h.Hchat.hooks.items.tablet.WeChatTabletSettings
import h.Hchat.hooks.items.textspeech.TextSpeechFeature
import h.Hchat.hooks.items.textspeech.TextSpeechEngineCatalog
import h.Hchat.hooks.items.textspeech.TextSpeechSettings
import h.Hchat.hooks.items.textspeech.TextSpeechVoiceCatalog
import h.Hchat.hooks.items.textvoice.TextVoiceFeature
import h.Hchat.hooks.items.textvoice.TextVoiceSettings
import h.Hchat.hooks.items.typingreport.TypingReportBlockFeature
import h.Hchat.hooks.items.typingreport.TypingReportBlockSettings
import h.Hchat.hooks.items.voiceforward.VoiceForwardFeature
import h.Hchat.hooks.items.voiceforward.VoiceForwardSettings
import h.Hchat.hooks.items.hchatextra.GroupMemberHistorySettingsProvider
import h.Hchat.hooks.items.hchatextra.MessageDetailsSettingsProvider
import h.Hchat.hooks.items.hchatextra.RedPacketDetailsSettingsProvider
import h.Hchat.hooks.items.hchatextra.SkipWebRiskSettingsProvider
import h.Hchat.hooks.items.hchatextra.HchatExtraSettings
import h.Hchat.hooks.items.voicepreview.VoicePreviewFeature
import h.Hchat.hooks.items.voicepreview.VoicePreviewSettings
import h.Hchat.media.AudioTransformBridge
import h.Hchat.preferences.HchatStorage
import h.Hchat.preferences.TermsGate
import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.UIRegistry
import h.Hchat.utils.KavaReflector
import h.Hchat.utils.KeywordReplacementRule
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.json.JSONArray
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.BreadcrumbBar
import top.yukonga.miuix.kmp.basic.BreadcrumbItem
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.menu.WindowDropdownMenu
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.navBackStackOf
import top.yukonga.miuix.kmp.nav.transition.NavTransitions
import top.yukonga.miuix.kmp.utils.springAnimateToPage
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.window.WindowDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.lang.ref.WeakReference
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Calendar
import java.util.LinkedHashSet
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.random.Random
import com.Johnny.wcx.utils.WeLogger

internal const val UI_PREFS_NAME = "Hchat_miuix_ui"
internal const val KEY_FLOATING_NAV = "floating_nav"
internal const val KEY_GLASS_NAV = "glass_nav"
internal const val KEY_SEARCH_HISTORY = "search_history"
internal const val KEY_AGENT_HISTORY_GESTURE_HINT = "agent_history_gesture_hint"
internal const val SEARCH_HISTORY_LIMIT = 16
internal const val PRESS_RELEASE_DELAY_MS = 110L
internal const val RINGTONE_SYSTEM_REQUEST_CODE = 0x48435254
internal const val RINGTONE_FILE_REQUEST_CODE = 0x48435255
internal const val REDPACKET_REPLY_FILE_REQUEST_CODE = 0x48435256
internal const val AUTO_REPLY_FILE_REQUEST_CODE = 0x48435257
internal const val CONFIG_EXPORT_REQUEST_CODE = 0x48435258
internal const val CONFIG_IMPORT_REQUEST_CODE = 0x48435259
internal const val SCHEDULED_TASK_FILE_REQUEST_CODE = 0x4843525A
internal const val AUDIO_TRANSFORM_INPUT_REQUEST_CODE = 0x4843525B
internal const val AUDIO_TRANSFORM_OUTPUT_REQUEST_CODE = 0x4843525C
internal const val FAKE_LOCATION_WECHAT_PICKER_REQUEST_CODE = 0x4843525D
internal const val SCRIPT_AGENT_ATTACHMENT_REQUEST_CODE = 0x4843525E
internal const val PLUGIN_MARKET_EXTRA_FILE_REQUEST_CODE = 0x4843525F
internal const val SCRIPT_PLUGIN_EXPORT_REQUEST_CODE = 0x48435260
internal const val SCRIPT_PLUGIN_IMPORT_REQUEST_CODE = 0x48435261
internal const val MARKDOWN_LINK_TAG = "md_link"
internal val MARKDOWN_LINK_REGEX = Regex("""\[([^\]]+)]\(([^)\s]+)\)""")
internal val NAVIGATION_BUTTON_MIN_INSET = 24.dp
internal val NAVIGATION_BUTTON_EXTRA_GAP = 8.dp
internal const val REDPACKET_AT_SENDER_VARIABLE = "{@发红包的人}"

internal fun isLiquidGlassSupported(): Boolean = Build.VERSION.SDK_INT >= 33
internal val REDPACKET_AT_SENDER_VARIABLES = listOf(
    REDPACKET_AT_SENDER_VARIABLE,
    "{@sender}",
    "{@成员}"
)

internal val redPacketTemplateVariables = listOf(
    TemplateVariable("{amount}", "金额"),
    TemplateVariable("{talker}", "会话"),
    TemplateVariable("{sender}", "成员"),
    TemplateVariable("{time}", "时间")
)
internal val redPacketReplyTemplateVariables = redPacketTemplateVariables + TemplateVariable(
    REDPACKET_AT_SENDER_VARIABLE,
    "@发红包人"
)
internal val transferTemplateVariables = listOf(
    TemplateVariable("{amount}", "金额"),
    TemplateVariable("{talker}", "会话"),
    TemplateVariable("{sender}", "转账人"),
    TemplateVariable("{@转账的人}", "@转账人"),
    TemplateVariable("{time}", "时间")
)

internal val antiRecallTemplateVariables = listOf(
    TemplateVariable(AntiRecallSettings.VAR_RECALLER_NAME, "撤回者"),
    TemplateVariable(AntiRecallSettings.VAR_RECALL_TEXT, "文字内容"),
    TemplateVariable(AntiRecallSettings.VAR_SEND_TIME, "发送时间"),
    TemplateVariable(AntiRecallSettings.VAR_RECALL_TIME, "撤回时间")
)
internal val messageAffixTemplateVariables = listOf(
    TemplateVariable(MessageAffixSettings.VAR_SEND_TEXT, "原消息"),
    TemplateVariable(MessageAffixSettings.VAR_LINE, "换行"),
    TemplateVariable(MessageAffixSettings.VAR_SEND_TIME, "发送时间"),
    TemplateVariable(MessageAffixSettings.VAR_SEND_DURATION, "发送耗时"),
    TemplateVariable(MessageAffixSettings.VAR_TOTAL_MESSAGES, "发送总数"),
    TemplateVariable(MessageAffixSettings.VAR_TEXT_MESSAGES, "文字消息数"),
    TemplateVariable(MessageAffixSettings.VAR_TEXT_CHARACTERS, "文字字数"),
    TemplateVariable(MessageAffixSettings.VAR_EMOJI_MESSAGES, "表情消息数"),
    TemplateVariable(MessageAffixSettings.VAR_TRANSFER_MESSAGES, "转账消息数"),
    TemplateVariable(MessageAffixSettings.VAR_RED_PACKET_MESSAGES, "红包消息数"),
    TemplateVariable(MessageAffixSettings.VAR_FILE_MESSAGES, "文件消息数")
)
internal val inputHintTemplateVariables = listOf(
    TemplateVariable(InputHintSettings.VAR_TOTAL_MESSAGES, "发送总数"),
    TemplateVariable(InputHintSettings.VAR_TEXT_MESSAGES, "文字消息数"),
    TemplateVariable(InputHintSettings.VAR_TEXT_CHARACTERS, "文字字数"),
    TemplateVariable(InputHintSettings.VAR_EMOJI_MESSAGES, "表情消息数"),
    TemplateVariable(InputHintSettings.VAR_TRANSFER_MESSAGES, "转账消息数"),
    TemplateVariable(InputHintSettings.VAR_RED_PACKET_MESSAGES, "红包消息数"),
    TemplateVariable(InputHintSettings.VAR_FILE_MESSAGES, "文件消息数")
)
internal val keywordNotificationTemplateVariables = listOf(
    TemplateVariable("%keyword%", "关键词"),
    TemplateVariable("%sender%", "发送者"),
    TemplateVariable("%wxid%", "发送者ID"),
    TemplateVariable("%content%", "内容"),
    TemplateVariable("%type%", "消息类型")
)
internal val momentsPostNotificationTemplateVariables = listOf(
    TemplateVariable("%sender%", "发布者"),
    TemplateVariable("%wxid%", "发布者ID"),
    TemplateVariable("%type%", "类型"),
    TemplateVariable("%content%", "内容"),
    TemplateVariable("%snsid%", "朋友圈ID")
)
internal val momentsAutoCommentTemplateVariables = listOf(
    TemplateVariable(MomentsAutoCommentSettings.VAR_TIME, "时间")
)
internal val textSpeechTemplateVariables = listOf(
    TemplateVariable(TextSpeechSettings.VAR_SENDER_NICKNAME, "发送者昵称"),
    TemplateVariable(TextSpeechSettings.VAR_WECHAT_ID, "发送者微信号"),
    TemplateVariable(TextSpeechSettings.VAR_REMARK_NAME, "备注"),
    TemplateVariable(TextSpeechSettings.VAR_GROUP_NICKNAME, "群内昵称"),
    TemplateVariable(TextSpeechSettings.VAR_GROUP_NAME, "群聊名称"),
    TemplateVariable(TextSpeechSettings.VAR_CONVERSATION_NAME, "会话名称"),
    TemplateVariable(TextSpeechSettings.VAR_ANNOUNCEMENT_SOURCE, "播报来源"),
    TemplateVariable(TextSpeechSettings.VAR_MESSAGE_CONTENT, "消息正文"),
    TemplateVariable(TextSpeechSettings.VAR_MESSAGE_TYPE, "消息类型"),
    TemplateVariable(TextSpeechSettings.VAR_VOICE_DURATION, "语音时长"),
    TemplateVariable(TextSpeechSettings.VAR_MESSAGE_TIME, "消息时间"),
    TemplateVariable(TextSpeechSettings.VAR_SENDER_ID, "发送者ID"),
    TemplateVariable(TextSpeechSettings.VAR_CONVERSATION_ID, "会话ID")
)
internal val momentsBottomDetailTemplateVariables = listOf(
    TemplateVariable(MomentsBottomDetailSettings.VAR_ORIGINAL_TEXT, "微信原时间"),
    TemplateVariable(MomentsBottomDetailSettings.VAR_TIME, "自定义时间"),
    TemplateVariable(MomentsBottomDetailSettings.VAR_TYPE, "朋友圈类型"),
    TemplateVariable(MomentsBottomDetailSettings.VAR_SNS_ID, "朋友圈ID"),
    TemplateVariable(MomentsBottomDetailSettings.VAR_USER_NAME, "发布者ID")
)
internal val groupMemberTemplateVariables = listOf(
    TemplateVariable("%userName%", "微信昵称"),
    TemplateVariable("%groupNickname%", "群内昵称"),
    TemplateVariable("%userWxid%", "Wxid"),
    TemplateVariable("%realNameTail%", "实名尾字"),
    TemplateVariable("%gender%", "性别"),
    TemplateVariable("%region%", "地区"),
    TemplateVariable("%groupName%", "群名"),
    TemplateVariable("%time%", "时间"),
    TemplateVariable("[AtWx=%userWxid%]", "@成员"),
    TemplateVariable("[AtWx=]", "@其他人")
)
internal val groupRenameTemplateVariables = listOf(
    TemplateVariable("%userName%", "微信昵称"),
    TemplateVariable("%oldGroupNickname%", "旧群内昵称"),
    TemplateVariable("%newGroupNickname%", "新群内昵称"),
    TemplateVariable("%userWxid%", "Wxid"),
    TemplateVariable("%realNameTail%", "实名尾字"),
    TemplateVariable("%gender%", "性别"),
    TemplateVariable("%region%", "地区"),
    TemplateVariable("%groupName%", "群名"),
    TemplateVariable("%time%", "时间"),
    TemplateVariable("[AtWx=%userWxid%]", "@成员"),
    TemplateVariable("[AtWx=]", "@其他人")
)
internal val groupRenameNoticeVariables = listOf(
    TemplateVariable("%oldGroupNickname%", "旧群内昵称"),
    TemplateVariable("%newGroupNickname%", "新群内昵称"),
    TemplateVariable("%userName%", "微信昵称"),
    TemplateVariable("%userWxid%", "可点击Wxid"),
    TemplateVariable("%realNameTail%", "真实姓名尾巴"),
    TemplateVariable("%gender%", "性别"),
    TemplateVariable("%region%", "地区"),
    TemplateVariable("%groupName%", "群名"),
    TemplateVariable("%time%", "时间")
)
internal val groupLeaveNoticeVariables = listOf(
    TemplateVariable("%displayName%", "完整显示名"),
    TemplateVariable("%groupNickname%", "群内昵称"),
    TemplateVariable("%userName%", "微信昵称"),
    TemplateVariable("%remarkName%", "备注"),
    TemplateVariable("%userWxid%", "可点击Wxid"),
    TemplateVariable("%groupName%", "群名"),
    TemplateVariable("%time%", "时间")
)
internal val groupInviteNoticeVariables = listOf(
    TemplateVariable("%inviterName%", "邀请者名称"),
    TemplateVariable("%inviterGroupNickname%", "邀请者群昵称"),
    TemplateVariable("%inviterWxid%", "邀请者可点击Wxid"),
    TemplateVariable("%inviteeName%", "被邀请者名称"),
    TemplateVariable("%inviteeGroupNickname%", "被邀请者群昵称"),
    TemplateVariable("%inviteeWxid%", "被邀请者可点击Wxid"),
    TemplateVariable("%inviteCount%", "累计邀请次数"),
    TemplateVariable("%groupName%", "群名"),
    TemplateVariable("%time%", "时间")
)
internal val autoReplyTemplateVariables = listOf(
    TemplateVariable("%friendName%", "好友/成员名"),
    TemplateVariable("%senderName%", "发送者"),
    TemplateVariable("%senderWxid%", "发送者ID"),
    TemplateVariable("%talker%", "会话ID"),
    TemplateVariable("%groupName%", "群名"),
    TemplateVariable("%content%", "原消息"),
    TemplateVariable("%atSender%", "@发送者"),
    TemplateVariable("%atAll%", "@所有人")
)
internal val scheduledTaskTemplateVariables = listOf(
    TemplateVariable("%friendName%", "好友/成员名")
)

internal val groupRandomJoinTexts = listOf(
    "[AtWx=%userWxid%] 欢迎 %userName% 加入 %groupName%",
    "欢迎新朋友 %userName% 加入，大家请多关照",
    "欢迎 %userName%，记得看群公告",
    "新成员 %userName% 已加入群聊",
    "欢迎 %userName%，愿在 %groupName% 玩得开心"
)

internal val groupRandomLeftTexts = listOf(
    "有缘再会，祝 %userName% 一切顺利",
    "%userName% 已离开群聊，愿一切安好",
    "青山不改，绿水长流，后会有期",
    "我们会想念你的，%userName%",
    "%userName% 已退群，感谢曾经同行"
)

internal val groupRandomJoinCardTitles = listOf(
    "欢迎：%userName%",
    "群聊因你而精彩",
    "新成员到来：%userName%"
)

internal val groupRandomJoinCardDescs = listOf(
    "常来聊天",
    "群名称：%groupName%\n名片：%groupNickname%\n进群时间：%time%",
    "快来和大家一起玩\nID：%userWxid%"
)

internal val groupRandomLeftCardTitles = listOf(
    "成员离群通知",
    "%userName% 已离开",
    "祝你一切顺利"
)

internal val groupRandomLeftCardDescs = listOf(
    "我们有缘再见",
    "群名称：%groupName%\n名片：%groupNickname%\n离群时间：%time%",
    "相逢是缘，祝君安好"
)

internal object NavIcons {
    val Back: ImageVector = navIcon(
        name = "Rounded.ArrowBack",
        path = "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z"
    )
    val Attach: ImageVector = navIcon(
        name = "Rounded.AttachFile",
        path = "M16.5,6.5v11c0,2.21 -1.79,4 -4,4s-4,-1.79 -4,-4V5c0,-1.38 1.12,-2.5 2.5,-2.5s2.5,1.12 2.5,2.5v10.5c0,0.55 -0.45,1 -1,1s-1,-0.45 -1,-1V6.5H10v9c0,1.38 1.12,2.5 2.5,2.5s2.5,-1.12 2.5,-2.5V5c0,-2.21 -1.79,-4 -4,-4S7,2.79 7,5v12.5c0,3.04 2.46,5.5 5.5,5.5s5.5,-2.46 5.5,-5.5v-11h-1.5z"
    )
    val Add: ImageVector = navIcon(
        name = "Rounded.Add",
        path = "M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z"
    )
    val Compact: ImageVector = navIcon(
        name = "Rounded.Summarize",
        path = "M14,2H6c-1.1,0 -2,0.9 -2,2v16c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V8l-6,-6z M13,9V3.5L18.5,9H13z M8,13h8v2H8v-2z M8,17h8v2H8v-2z M8,9h3v2H8V9z"
    )
    val Close: ImageVector = navIcon(
        name = "Rounded.Close",
        path = "M18.3,5.71L12,12l6.3,6.29 -1.41,1.42L10.59,13.41 4.29,19.71 2.88,18.29 9.17,12 2.88,5.71 4.29,4.29 10.59,10.59 16.89,4.29z"
    )
    val History: ImageVector = navIcon(
        name = "Rounded.History",
        path = "M13,3c-4.97,0 -9,4.03 -9,9s4.03,9 9,9c4.63,0 8.44,-3.5 8.94,-8h-2.02c-0.49,3.39 -3.4,6 -6.92,6 -3.87,0 -7,-3.13 -7,-7s3.13,-7 7,-7c1.93,0 3.68,0.79 4.95,2.05L15,10h6V4l-2.63,2.63C16.73,4.42 14.95,3 13,3z M12,7v6l5,3 1,-1.64 -4,-2.36V7h-2z"
    )
    val Bell: ImageVector = navIcon(
        name = "Rounded.Notifications",
        path = "M12,22c1.1,0 2,-0.9 2,-2h-4c0,1.1 0.9,2 2,2z M18,16v-5c0,-3.07 -1.63,-5.64 -4.5,-6.32V4c0,-0.83 -0.67,-1.5 -1.5,-1.5S10.5,3.17 10.5,4v0.68C7.64,5.36 6,7.92 6,11v5l-2,2v1h16v-1l-2,-2z"
    )
    val Modules: ImageVector = navIcon(
        name = "Rounded.Extension",
        path = "M20.5,11H19V7c0,-1.1 -0.9,-2 -2,-2h-4V3.5C13,2.12 11.88,1 10.5,1S8,2.12 8,3.5V5H4c-1.1,0 -1.99,0.9 -1.99,2v3.8H3.5c1.49,0 2.7,1.21 2.7,2.7s-1.21,2.7 -2.7,2.7H2V20c0,1.1 0.9,2 2,2h3.8v-1.5c0,-1.49 1.21,-2.7 2.7,-2.7s2.7,1.21 2.7,2.7V22H17c1.1,0 2,-0.9 2,-2v-4h1.5c1.38,0 2.5,-1.12 2.5,-2.5S21.88,11 20.5,11z"
    )
    val Settings: ImageVector = navIcon(
        name = "Rounded.Settings",
        path = "M19.5,12c0,-0.23 -0.01,-0.45 -0.03,-0.68l1.86,-1.41c0.4,-0.3 0.51,-0.86 0.26,-1.3l-1.87,-3.23c-0.25,-0.44 -0.79,-0.62 -1.25,-0.42l-2.15,0.91c-0.37,-0.26 -0.76,-0.49 -1.17,-0.68l-0.29,-2.31C14.8,2.38 14.37,2 13.87,2h-3.73C9.63,2 9.2,2.38 9.14,2.88L8.85,5.19c-0.41,0.19 -0.8,0.42 -1.17,0.68L5.53,4.96c-0.46,-0.2 -1,-0.02 -1.25,0.42L2.41,8.62c-0.25,0.44 -0.14,0.99 0.26,1.3l1.86,1.41C4.51,11.55 4.5,11.77 4.5,12s0.01,0.45 0.03,0.68l-1.86,1.41c-0.4,0.3 -0.51,0.86 -0.26,1.3l1.87,3.23c0.25,0.44 0.79,0.62 1.25,0.42l2.15,-0.91c0.37,0.26 0.76,0.49 1.17,0.68l0.29,2.31c0.06,0.5 0.49,0.88 0.99,0.88h3.73c0.5,0 0.93,-0.38 0.99,-0.88l0.29,-2.31c0.41,-0.19 0.8,-0.42 1.17,-0.68l2.15,0.91c0.46,0.2 1,0.02 1.25,-0.42l1.87,-3.23c0.25,-0.44 0.14,-0.99 -0.26,-1.3l-1.86,-1.41C19.49,12.45 19.5,12.23 19.5,12z M12.04,15.5c-1.93,0 -3.5,-1.57 -3.5,-3.5s1.57,-3.5 3.5,-3.5s3.5,1.57 3.5,3.5S13.97,15.5 12.04,15.5z"
    )
    val More: ImageVector = navIcon(
        name = "Rounded.MoreVert",
        path = "M12,8c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2z M12,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z M12,16c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z"
    )
    val Import: ImageVector = navIcon(
        name = "Rounded.FileDownload",
        path = "M19,9h-4V3H9v6H5l7,7 7,-7z M5,18v2h14v-2H5z"
    )
    val Export: ImageVector = navIcon(
        name = "Rounded.FileUpload",
        path = "M9,16h6v-6h4l-7,-7 -7,7h4v6z M5,18v2h14v-2H5z"
    )
    val Practical: ImageVector = navIcon(
        name = "Rounded.GridView",
        path = "M5,3h4c1.1,0 2,0.9 2,2v4c0,1.1 -0.9,2 -2,2H5c-1.1,0 -2,-0.9 -2,-2V5c0,-1.1 0.9,-2 2,-2z M15,3h4c1.1,0 2,0.9 2,2v4c0,1.1 -0.9,2 -2,2h-4c-1.1,0 -2,-0.9 -2,-2V5c0,-1.1 0.9,-2 2,-2z M5,13h4c1.1,0 2,0.9 2,2v4c0,1.1 -0.9,2 -2,2H5c-1.1,0 -2,-0.9 -2,-2v-4c0,-1.1 0.9,-2 2,-2z M15,13h4c1.1,0 2,0.9 2,2v4c0,1.1 -0.9,2 -2,2h-4c-1.1,0 -2,-0.9 -2,-2v-4c0,-1.1 0.9,-2 2,-2z"
    )
    val Entertainment: ImageVector = navIcon(
        name = "Rounded.PlayCircle",
        path = "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10s10,-4.48 10,-10S17.52,2 12,2z M10,15.5v-7c0,-0.4 0.45,-0.64 0.78,-0.42l5.25,3.5c0.3,0.2 0.3,0.64 0,0.84l-5.25,3.5C10.45,16.14 10,15.9 10,15.5z"
    )
    val Play: ImageVector = navIcon(
        name = "Rounded.PlayArrow",
        path = "M8,5v14l11,-7z"
    )
    val Pause: ImageVector = navIcon(
        name = "Rounded.Pause",
        path = "M6,19h4V5H6v14z M14,5v14h4V5h-4z"
    )
    val Search: ImageVector = navIcon(
        name = "Rounded.Search",
        path = "M9.5,3C5.91,3 3,5.91 3,9.5S5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l4.42,4.42c0.29,0.29 0.77,0.29 1.06,0s0.29,-0.77 0,-1.06l-4.42,-4.42C15.91,12.09 16,10.82 16,9.5C16,5.91 13.09,3 9.5,3z M9.5,4.5c2.76,0 5,2.24 5,5s-2.24,5 -5,5s-5,-2.24 -5,-5s2.24,-5 5,-5z"
    )
    val Send: ImageVector = navIcon(
        name = "Rounded.Send",
        path = "M2.01,21L23,12 2.01,3 2,10l15,2 -15,2z"
    )
    val Stop: ImageVector = navIcon(
        name = "Rounded.Stop",
        path = "M6,6h12v12H6z"
    )
    val Refresh: ImageVector = navIcon(
        name = "Rounded.Refresh",
        path = "M17.65,6.35C16.2,4.9 14.21,4 12,4c-4.42,0 -7.99,3.58 -7.99,8s3.57,8 7.99,8c3.73,0 6.84,-2.55 7.73,-6h-2.08c-0.82,2.33 -3.04,4 -5.65,4 -3.31,0 -6,-2.69 -6,-6s2.69,-6 6,-6c1.66,0 3.14,0.69 4.22,1.78L13,11h7V4l-2.35,2.35z"
    )
    val Copy: ImageVector = navIcon(
        name = "Rounded.Copy",
        path = "M16,1H4c-1.1,0 -2,0.9 -2,2v14h2V4h12V1z M19,5H8c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h11c1.1,0 2,-0.9 2,-2V7c0,-1.1 -0.9,-2 -2,-2z"
    )
    val Quote: ImageVector = navIcon(
        name = "Rounded.Reply",
        path = "M10,9V5l-7,7 7,7v-4.1c5,0 8.5,1.6 11,5.1 -1,-5 -4,-10 -11,-11z"
    )
    val Volume: ImageVector = navIcon(
        name = "Rounded.VolumeUp",
        path = "M3,9v6h4l5,5V4L7,9H3z M16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02z M14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z"
    )
    val Edit: ImageVector = navIcon(
        name = "Rounded.Edit",
        path = "M3,17.25V21h3.75L17.81,9.94l-3.75,-3.75L3,17.25z M20.71,7.04c0.39,-0.39 0.39,-1.02 0,-1.41l-2.34,-2.34c-0.39,-0.39 -1.02,-0.39 -1.41,0l-1.83,1.83 3.75,3.75 1.83,-1.83z"
    )
    val Delete: ImageVector = navIcon(
        name = "Rounded.Delete",
        path = "M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6v12z M8,9h8v10H8V9z M15.5,4l-1,-1h-5l-1,1H5v2h14V4z"
    )
    val MoveUp: ImageVector = navIcon(
        name = "Rounded.KeyboardArrowUp",
        path = "M7.41,15.41L12,10.83l4.59,4.58L18,14l-6,-6 -6,6z"
    )
    val MoveDown: ImageVector = navIcon(
        name = "Rounded.KeyboardArrowDown",
        path = "M7.41,8.59L12,13.17l4.59,-4.58L18,10l-6,6 -6,-6z"
    )
    val Expand: ImageVector = navIcon(
        name = "Rounded.ChevronRight",
        path = "M9.29,6.71c-0.39,0.39 -0.39,1.02 0,1.41L13.17,12l-3.88,3.88c-0.39,0.39 -0.39,1.02 0,1.41 0.39,0.39 1.02,0.39 1.41,0l4.59,-4.59c0.39,-0.39 0.39,-1.02 0,-1.41L10.7,6.7c-0.38,-0.38 -1.02,-0.38 -1.41,0.01z"
    )
    val Pin: ImageVector = navIcon(
        name = "Rounded.PushPin",
        path = "M16,9V4l1,-1V2H7v1l1,1v5c0,1.66 -1.34,3 -3,3v2h6v7l1,1 1,-1v-7h6v-2c-1.66,0 -3,-1.34 -3,-3z"
    )
    val Lock: ImageVector = navIcon(
        name = "Rounded.Lock",
        path = "M18,8h-1V6c0,-2.76 -2.24,-5 -5,-5S7,3.24 7,6v2H6c-1.1,0 -2,0.9 -2,2v10c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V10c0,-1.1 -0.9,-2 -2,-2z M9,6c0,-1.66 1.34,-3 3,-3s3,1.34 3,3v2H9V6z M12,17c-1.1,0 -2,-0.9 -2,-2s0.9,-2 2,-2 2,0.9 2,2 -0.9,2 -2,2z"
    )
    val Unlock: ImageVector = navIcon(
        name = "Rounded.LockOpen",
        path = "M12,17c-1.1,0 -2,-0.9 -2,-2s0.9,-2 2,-2 2,0.9 2,2 -0.9,2 -2,2z M18,8h-8V6c0,-1.1 0.9,-2 2,-2 0.95,0 1.74,0.66 1.95,1.54l1.93,-0.52C15.43,3.28 13.86,2 12,2 9.79,2 8,3.79 8,6v2H6c-1.1,0 -2,0.9 -2,2v10c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V10c0,-1.1 -0.9,-2 -2,-2z"
    )
    val Drag: ImageVector = navIcon(
        name = "Rounded.DragHandle",
        path = "M4,10.5h16v-2H4v2z M4,15.5h16v-2H4v2z"
    )
    val Swipe: ImageVector = navIcon(
        name = "Rounded.SwapHoriz",
        path = "M6.99,11L3,15l3.99,4v-3H14v-2H6.99v-3z M21,9l-3.99,-4v3H10v2h7.01v3L21,9z"
    )
    val Branch: ImageVector = navIcon(
        name = "Rounded.AccountTree",
        path = "M17,11h3c0.55,0 1,-0.45 1,-1V4c0,-0.55 -0.45,-1 -1,-1h-6c-0.55,0 -1,0.45 -1,1v2H9.83C9.42,4.84 8.31,4 7,4 5.34,4 4,5.34 4,7s1.34,3 3,3c1.31,0 2.42,-0.84 2.83,-2H13v2c0,0.55 0.45,1 1,1h1v2H9.83C9.42,11.84 8.31,11 7,11c-1.66,0 -3,1.34 -3,3s1.34,3 3,3c1.31,0 2.42,-0.84 2.83,-2H15v5c0,0.55 0.45,1 1,1h4c0.55,0 1,-0.45 1,-1v-4c0,-0.55 -0.45,-1 -1,-1h-3v-4z"
    )
    val Info: ImageVector = navIcon(
        name = "Rounded.Info",
        path = "M11,17h2v-6h-2v6z M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2z M12,20c-4.41,0 -8,-3.59 -8,-8s3.59,-8 8,-8 8,3.59 8,8 -3.59,8 -8,8z M11,9h2V7h-2v2z"
    )
    val Terminal: ImageVector = navIcon(
        name = "Rounded.Terminal",
        path = "M20,4H4C2.9,4 2,4.9 2,6v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V6C22,4.9 21.1,4 20,4z M20,18H4V6h16V18z M18,15h-6v-1.5h6V15z M7.5,15l-1.06,-1.06L8.38,12L6.44,10.06L7.5,9l3,3L7.5,15z"
    )

    private fun navIcon(name: String, path: String): ImageVector {
        return ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).addPath(
            pathData = addPathNodes(path),
            fill = SolidColor(Color.Black)
        ).build()
    }
}

internal class SettingsBackHandlerRegistry {
    private val handlers = LinkedHashMap<Any, () -> Unit>()

    @Synchronized
    fun register(token: Any, handler: () -> Unit) {
        handlers.remove(token)
        handlers[token] = handler
    }

    @Synchronized
    fun unregister(token: Any) {
        handlers.remove(token)
    }

    fun handle(): Boolean {
        val handler = synchronized(this) { handlers.values.lastOrNull() } ?: return false
        handler()
        return true
    }

    @Synchronized
    fun clear() {
        handlers.clear()
    }
}

internal val LocalSettingsBackHandlerRegistry = staticCompositionLocalOf<SettingsBackHandlerRegistry?> { null }

/**
 * WCX：Compose LocalContext 可能是 ContextThemeWrapper 包装，直接 as? Activity 会失败导致
 * 设置浮层静默不显示。向上遍历 ContextWrapper 找到真实 Activity。
 */
internal fun Context.findHostActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}

@Composable
internal fun RegisterSettingsBackHandler(onBack: (() -> Unit)?) {
    val registry = LocalSettingsBackHandlerRegistry.current
    val currentOnBack by rememberUpdatedState(onBack)
    val token = remember { Any() }
    DisposableEffect(registry, token, onBack != null) {
        if (registry != null && onBack != null) {
            registry.register(token) { currentOnBack?.invoke() }
        }
        onDispose { registry?.unregister(token) }
    }
}

object MiuixSettingsPage {
    private const val PAGE_TAG = "Hchat:MiuixSettingsPage"

    private class SettingsPageHostTag(val floatingAgent: Boolean) {
        var close: (() -> Unit)? = null
        var collapseAgent: (() -> Unit)? = null
    }

    @OptIn(ExperimentalAnimationApi::class)
    @JvmStatic
    fun show(context: Context) {
        show(context, null, floatingAgent = false)
    }


    private const val TAG_WCX = "WCX-HchatUI"

    /**
     * WCX：若脚本设置浮层正处于打开状态则关闭它，并返回是否真的关闭了一个浮层。
     * 供宿主 Activity 的返回键处理兜底（防止 Xposed 层 back hook 在模块自身 Activity 上失效时浮层关不掉）。
     */
    @JvmStatic
    fun closeIfOpen(context: Context): Boolean {
        val activity = context.findHostActivity() ?: return false
        val decor = activity.window?.decorView as? ViewGroup ?: return false
        val existing = findExistingPage(decor) ?: return false
        val close = (existing.getTag(R.id.hchat_settings_page_host) as? SettingsPageHostTag)?.close
        return if (close != null) {
            runCatching { close() }.isSuccess
        } else {
            runCatching { decor.removeView(existing) }.isSuccess
        }
    }

    /**
     * WCX：直达「脚本插件」内容页（总开关 + 本地插件 + 在线市场 + 本地插件管理）。
     * scriptOnly=true：浮层只渲染脚本内容——不显示 Hchat 的主页/功能/设置 Tab、
     * 不做模块更新检测、标题不出现 Hchat 字样（用户只要脚本这一套）。
     */
    @JvmStatic
    fun showScriptPlugin(context: Context) {
        show(context, null, floatingAgent = false, initialTab = MainTab.PLUGIN, scriptOnly = true)
    }

    @JvmStatic
    fun showFeature(context: Context, featureId: String): Boolean {
        val provider = UIRegistry.get().getAllProviders()
            .firstOrNull { it.featureId() == featureId }
            ?: return false
        show(context, DetailPage.Feature(provider), floatingAgent = false)
        return true
    }


    @OptIn(ExperimentalAnimationApi::class)
    private fun show(
        context: Context,
        initialPage: DetailPage?,
        floatingAgent: Boolean,
        initialTab: MainTab = MainTab.PRACTICAL,
        scriptOnly: Boolean = false,
    ) {
        val activity = context.findHostActivity()
        if (activity == null) {
            WeLogger.e(TAG_WCX, "showScriptPlugin: 拿不到宿主 Activity（context=" + context.javaClass.name + "），浮层不显示")
            return
        }
        val decor = activity.window?.decorView as? ViewGroup
        if (decor == null) {
            WeLogger.e(TAG_WCX, "showScriptPlugin: 拿不到 decorView，浮层不显示")
            return
        }
        WeLogger.i(TAG_WCX, "showScriptPlugin: 打开浮层 activity=" + activity.javaClass.simpleName + " initialTab=" + initialTab)
        removeExistingPage(decor)

        val hostTag = SettingsPageHostTag(floatingAgent)
        val owner = EmbeddedComposeOwner()
        val detailBackStack = navBackStackOf(SettingsNavKey(initialPage))
        fun currentDetail(): DetailPage? = (detailBackStack.lastOrNull() as? SettingsNavKey)?.page
        fun openDetail(page: DetailPage?) {
            detailBackStack.add(SettingsNavKey(page))
        }
        fun popDetail() {
            if (detailBackStack.size > 1) detailBackStack.removeLastOrNull()
        }
        val readmeDialogPlugin = mutableStateOf<ScriptPluginRuntime.ScriptPlugin?>(null)
        val configVersion = mutableStateOf(0)
        val settingsBackHandlers = SettingsBackHandlerRegistry()
        lateinit var page: FrameLayout
        val activityBackHooks = ArrayList<XC_MethodHook.Unhook>()
        var consumingBack = false
        var closed = false
        fun unhookActivityBack() {
            activityBackHooks.forEach {
                try {
                    it.unhook()
                } catch (_: Throwable) {
                }
            }
            activityBackHooks.clear()
        }
        fun closePage() {
            if (closed) return
            closed = true
            hostTag.close = null
            hostTag.collapseAgent = null
            settingsBackHandlers.clear()
            unhookActivityBack()
            runCatching {
                page.findViewById<ComposeView>(android.R.id.content)?.disposeComposition()
            }.onFailure {
                h.Hchat.utils.HLog.e("[Hchat:MiuixSettings] 销毁页面 Composition 失败: ${it.message}", it)
            }
            runCatching {
                if (page.parent === decor) decor.removeView(page)
            }.onFailure {
                h.Hchat.utils.HLog.e("[Hchat:MiuixSettings] 移除页面失败: ${it.message}", it)
            }
            runCatching { owner.destroy() }.onFailure {
                h.Hchat.utils.HLog.e("[Hchat:MiuixSettings] 销毁页面 Owner 失败: ${it.message}", it)
            }
        }
        fun handleBack() {
            if (consumingBack) return
            consumingBack = true
            val selected = currentDetail()
            if (settingsBackHandlers.handle()) {
                // The current nested settings route owns this back event.
            } else if (selected == null) {
                closePage()
            } else {
                popDetail()
            }
            page.post { consumingBack = false }
        }
        hostTag.close = { closePage() }
        if (floatingAgent) hostTag.collapseAgent = { closePage() }
        fun installActivityBackHooks() {
            if (activityBackHooks.isNotEmpty()) return
            val keyHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val event = param.args.firstOrNull { it is KeyEvent } as? KeyEvent ?: return
                    if (event.keyCode != KeyEvent.KEYCODE_BACK || page.parent == null) return
                    if (event.action == KeyEvent.ACTION_UP) {
                        handleBack()
                    }
                    param.result = true
                }
            }
            val pressedHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (page.parent == null) return
                    handleBack()
                    param.result = null
                }
            }
            listOf(Activity::class.java, activity.javaClass).forEach { clazz ->
                try {
                    activityBackHooks.addAll(XposedBridge.hookAllMethods(clazz, "dispatchKeyEvent", keyHook))
                } catch (_: Throwable) {
                }
                try {
                    activityBackHooks.addAll(XposedBridge.hookAllMethods(clazz, "onKeyDown", keyHook))
                } catch (_: Throwable) {
                }
                try {
                    activityBackHooks.addAll(XposedBridge.hookAllMethods(clazz, "onKeyUp", keyHook))
                } catch (_: Throwable) {
                }
                try {
                    activityBackHooks.addAll(XposedBridge.hookAllMethods(clazz, "onBackPressed", pressedHook))
                } catch (_: Throwable) {
                }
            }
        }
        page = object : FrameLayout(activity) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) {
                        handleBack()
                    }
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            tag = PAGE_TAG
            setTag(R.id.hchat_settings_page_host, hostTag)
            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = Unit
                override fun onViewDetachedFromWindow(view: View) {
                    // 宿主销毁或外部移除页面也必须释放全局返回 Hook 和私有 Owner。
                    closePage()
                }
            })
            setBackgroundColor(if (isDarkMode(activity)) AndroidColor.BLACK else AndroidColor.WHITE)
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        owner.install(page)
        owner.attach()
        val selectedTab = mutableStateOf(initialTab)
        val termsAccepted = mutableStateOf(TermsGate.isAccepted(activity))
        val composeView = try {
            ComposeView(activity).apply {
                id = android.R.id.content
                owner.install(this)
                owner.installComposition(this)
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent {
                val mainListState = rememberLazyListState()
                val featureGroupListStates = remember { mutableMapOf<FeatureGroupEntry, LazyListState>() }
                CompositionLocalProvider(
                    LocalNavigationEventDispatcherOwner provides owner,
                    LocalSettingsBackHandlerRegistry provides settingsBackHandlers
                ) {
                    HchatMiuixTheme(activity) {
                        readmeDialogPlugin.value?.let { plugin ->
                            ScriptPluginSettingsMiuixContent.ScriptPluginReadmeDialog(
                                context = activity,
                                plugin = plugin,
                                onClose = { readmeDialogPlugin.value = null }
                            )
                        }
                        if (!termsAccepted.value) {
                            ScriptPluginSettingsMiuixContent.FirstUseAgreementDialog(
                                context = activity,
                                onCancel = { closePage() },
                                onAccepted = {
                                    termsAccepted.value = true
                                    Toast.makeText(activity, "已同意协议，重启微信后完整启用模块功能", Toast.LENGTH_LONG).show()
                                }
                            )
                        }
                        NavDisplay(
                            backStack = detailBackStack,
                            modifier = Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background),
                            transition = NavTransitions.MiuixDefault,
                            onBack = {
                                if (detailBackStack.size > 1) popDetail() else closePage()
                            }
                        ) {
                            entry<SettingsNavKey> { route ->
                                val targetPage = route.page
                            if (targetPage == null) {
                                MainSettingsPage(
                                    context = activity,
                                    scriptOnly = scriptOnly,
                                    providers = UIRegistry.get().getAllProviders(),
                                    listState = mainListState,
                                    selectedTab = selectedTab.value,
                                    configVersion = configVersion.value,
                                    onSelectedTab = { selectedTab.value = it },
                                    onOpenSearch = { openDetail(DetailPage.Search) },
                                    onOpenProvider = { openDetail(DetailPage.Feature(it)) },
                                    onOpenGroup = { openDetail(DetailPage.FeatureGroup(it)) },
                                    onOpenScriptPluginReadme = { readmeDialogPlugin.value = it },
                                    onOpenScriptPluginMarket = { openDetail(DetailPage.ScriptPluginMarket()) },
                                    onOpenScriptPluginManager = { openDetail(DetailPage.ScriptPluginManager()) },
                                    onConfigImported = { configVersion.value += 1 }
                                )
                            } else if (targetPage is DetailPage.Search) {
                                SettingsSearchPage(
                                    context = activity,
                                    providers = UIRegistry.get().getAllProviders(),
                                    onBack = { popDetail() },
                                    onOpenProvider = { provider, group ->
                                        openDetail(DetailPage.Feature(
                                            provider = provider,
                                            sourceGroup = group,
                                            returnToSearch = true
                                        ))
                                    },
                                    onOpenScriptPluginReadme = { readmeDialogPlugin.value = it }
                                )
                            } else if (targetPage is DetailPage.FeatureGroup) {
                                FeatureGroupSettingsPage(
                                    group = targetPage.group,
                                    listState = featureGroupListStates.getOrPut(targetPage.group) { LazyListState() },
                                    onBack = { popDetail() },
                                    onOpenProvider = {
                                        openDetail(DetailPage.Feature(it, targetPage.group))
                                    }
                                )
                            } else if (targetPage is DetailPage.Feature) {
                                FeatureSettingsPage(
                                    context = activity,
                                    provider = targetPage.provider,
                                    onBack = {
                                        popDetail()
                                    },
                                    onOpenScriptPluginMarket = {
                                        openDetail(DetailPage.ScriptPluginMarket(targetPage))
                                    },
                                    onOpenScriptPluginManager = {
                                        openDetail(DetailPage.ScriptPluginManager(targetPage))
                                    }
                                )
                            } else if (targetPage is DetailPage.ScriptPluginMarket) {
                                PluginMarketUi.ScriptPluginMarketPage(
                                    context = activity,
                                    onBack = { popDetail() }
                                )
                            } else if (targetPage is DetailPage.ScriptPluginManager) {
                                ScriptPluginSettingsMiuixContent.ScriptPluginManagerPage(
                                    context = activity,
                                    onBack = { popDetail() }
                                )
                            }
                            }
                        }
                    }
                }
                }
            }
        } catch (throwable: Throwable) {
            closePage()
            h.Hchat.utils.HLog.e(
                "[Hchat:MiuixSettings] 创建页面 ComposeView 失败: ${throwable.message}",
                throwable
            )
            WeLogger.e(TAG_WCX, "创建脚本设置浮层失败(ComposeView): ${throwable}", throwable)
            runCatching {
                android.widget.Toast.makeText(activity, "脚本页打开失败: ${throwable.message}", android.widget.Toast.LENGTH_LONG).show()
            }
            return
        }

        try {
            page.addView(
                composeView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            decor.addView(page)
            composeView.createComposition()
        } catch (throwable: Throwable) {
            closePage()
            h.Hchat.utils.HLog.e(
                "[Hchat:MiuixSettings] 创建页面 Composition 失败: ${throwable.message}",
                throwable
            )
            WeLogger.e(TAG_WCX, "脚本设置浮层渲染失败(Composition): ${throwable}", throwable)
            runCatching {
                android.widget.Toast.makeText(activity, "脚本页渲染失败: ${throwable.message}", android.widget.Toast.LENGTH_LONG).show()
            }
            return
        }
        WeLogger.i(TAG_WCX, "showScriptPlugin: 浮层已挂载")
        installActivityBackHooks()
        page.requestFocus()
    }

    private fun findExistingPage(decor: ViewGroup): View? {
        for (index in decor.childCount - 1 downTo 0) {
            val child = decor.getChildAt(index)
            if (child.tag == PAGE_TAG) return child
        }
        return null
    }

    private fun removeExistingPage(decor: ViewGroup) {
        val pages = (decor.childCount - 1 downTo 0)
            .map { decor.getChildAt(it) }
            .filter { it.tag == PAGE_TAG }
        pages.forEach { child ->
            val close = (child.getTag(R.id.hchat_settings_page_host) as? SettingsPageHostTag)?.close
            if (close != null) close() else decor.removeView(child)
        }
    }
}

internal object AvatarMemoryCache {
    private const val MAX_ENTRIES = 256
    private const val MAX_BYTES = 8L * 1024 * 1024
    private data class Entry(val bitmap: ImageBitmap, val bytes: Long)
    private val bitmaps = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private var totalBytes = 0L

    @Synchronized
    fun cached(value: String): Pair<Boolean, ImageBitmap?> {
        val entry = bitmaps[value] ?: return false to null
        return true to entry.bitmap
    }

    @Synchronized
    fun put(value: String, bitmap: ImageBitmap?) {
        if (bitmap == null) return
        val bytes = bitmap.asAndroidBitmap().allocationByteCount.toLong()
        bitmaps.remove(value)?.let { totalBytes -= it.bytes }
        if (bytes <= 0L || bytes > MAX_BYTES) return
        bitmaps[value] = Entry(bitmap, bytes)
        totalBytes += bytes
        val oldest = bitmaps.entries.iterator()
        while ((bitmaps.size > MAX_ENTRIES || totalBytes > MAX_BYTES) && oldest.hasNext()) {
            totalBytes -= oldest.next().value.bytes
            oldest.remove()
        }
        // 缓存淘汰只释放引用；Compose 中仍显示的头像不能主动 recycle。
    }
}

internal object WeChatAvatarPathCache {
    @Volatile
    private var avatarRoot: String? = null

    fun resolveAvatarRoot(): String? {
        avatarRoot?.let { return it }
        val api = WeChatApis.database() ?: return null
        val rows = try {
            api.query("PRAGMA database_list", null)
        } catch (_: Throwable) {
            emptyList()
        }
        for (row in rows) {
            val filePath = row["file"]?.toString().orEmpty()
            if (filePath.isBlank()) continue
            val dbFile = File(filePath)
            val parent = dbFile.parentFile ?: continue
            val avatarDir = File(parent, "avatar")
            if (avatarDir.isDirectory || parent.isDirectory) {
                val path = avatarDir.absolutePath
                avatarRoot = path
                return path
            }
        }
        return null
    }
}

internal object NativeFakeLocationPickerBridge {
    private val hookedClasses = HashSet<Class<*>>()
    private var pending: PendingResult? = null

    @Synchronized
    fun launch(activity: Activity, onResult: (Double, Double) -> Unit) {
        pending = PendingResult(activity, onResult)
        hookActivityResult(activity.javaClass)
        hookActivityResult(Activity::class.java)
        val intent = Intent().apply {
            setClassName(activity.packageName, "${activity.packageName}.plugin.location.ui.RedirectUI")
            putExtra("map_view_type", 8)
        }
        runCatching {
            activity.startActivityForResult(intent, FAKE_LOCATION_WECHAT_PICKER_REQUEST_CODE)
        }.onFailure {
            clearPending()
            Toast.makeText(activity, "启动微信地图失败: ${it.message.orEmpty()}", Toast.LENGTH_SHORT).show()
        }
    }

    @Synchronized
    private fun hookActivityResult(clazz: Class<*>) {
        if (hookedClasses.contains(clazz)) return
        runCatching {
            XposedBridge.hookAllMethods(clazz, "onActivityResult", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val requestCode = param.args.getOrNull(0) as? Int ?: return
                    if (requestCode != FAKE_LOCATION_WECHAT_PICKER_REQUEST_CODE) return
                    val result = takePending() ?: return
                    val resultCode = param.args.getOrNull(1) as? Int ?: return
                    val data = param.args.getOrNull(2) as? Intent
                    if (resultCode != Activity.RESULT_OK || data == null) return
                    val location = extractLocation(data)
                    if (location == null) {
                        Toast.makeText(result.activity, "解析微信地图选点失败", Toast.LENGTH_SHORT).show()
                    } else {
                        result.callback(location.first, location.second)
                    }
                }
            })
        }.onSuccess { unhooks ->
            if (unhooks.isNotEmpty()) hookedClasses.add(clazz)
        }.onFailure {
            h.Hchat.utils.HLog.e("[Hchat:FakeLocation] 地图选点结果 Hook 安装失败: ${clazz.name}", it)
        }
    }

    @Suppress("DEPRECATION")
    private fun extractLocation(data: Intent): Pair<Double, Double>? {
        val latitude = data.getDoubleExtra("kwebmap_slat", Double.NaN)
        val longitude = data.getDoubleExtra("kwebmap_lng", Double.NaN)
        validFakeLocation(latitude, longitude)?.let { return it }

        val locationIntent = runCatching {
            data.getParcelableExtra<Parcelable>("KLocationIntent")
        }.getOrNull() ?: return null
        val fieldLatitude = (KavaReflector.readField(locationIntent, "d") as? Number)?.toDouble()
        val fieldLongitude = (KavaReflector.readField(locationIntent, "e") as? Number)?.toDouble()
        if (fieldLatitude != null && fieldLongitude != null) {
            validFakeLocation(fieldLatitude, fieldLongitude)?.let { return it }
        }
        val locationData = KavaReflector.declaredMethods(locationIntent.javaClass)
            .asSequence()
            .filter { it.parameterTypes.isEmpty() && it.returnType == String::class.java }
            .mapNotNull { method -> KavaReflector.invoke(method, locationIntent) as? String }
            .firstOrNull { it.contains("lat", ignoreCase = true) && it.contains("lng", ignoreCase = true) }
            ?: locationIntent.toString()
        return parseNativeFakeLocation(locationData)
    }

    @Synchronized
    private fun takePending(): PendingResult? {
        val result = pending
        pending = null
        return result
    }

    @Synchronized
    private fun clearPending() {
        pending = null
    }

    private data class PendingResult(
        val activity: Activity,
        val callback: (Double, Double) -> Unit
    )
}

internal object RingtonePickerBridge {
    private val hookedClasses = HashSet<Class<*>>()
    private var onPicked: ((String) -> Unit)? = null
    private var currentActivity: Activity? = null

    @Synchronized
    fun launchSystem(activity: Activity, currentUri: String, onResult: (String) -> Unit) {
        onPicked = onResult
        currentActivity = activity
        hookActivityResult(activity.javaClass)
        hookActivityResult(Activity::class.java)
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "选择通知铃声")
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (currentUri.isNotBlank()) {
                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(currentUri))
            }
        }
        activity.startActivityForResult(intent, RINGTONE_SYSTEM_REQUEST_CODE)
    }

    @Synchronized
    fun launchFile(activity: Activity, onResult: (String) -> Unit) {
        onPicked = onResult
        currentActivity = activity
        hookActivityResult(activity.javaClass)
        hookActivityResult(Activity::class.java)
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }.preferSystemDocumentsUi(activity)
        try {
            activity.startActivityForResult(intent, RINGTONE_FILE_REQUEST_CODE)
        } catch (_: Throwable) {
            val fallback = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivityForResult(Intent.createChooser(fallback, "选择铃声文件"), RINGTONE_FILE_REQUEST_CODE)
        }
    }

    @Synchronized
    private fun hookActivityResult(clazz: Class<*>) {
        if (hookedClasses.contains(clazz)) return
        try {
            XposedBridge.hookAllMethods(clazz, "onActivityResult", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val requestCode = param.args.getOrNull(0) as? Int ?: return
                    if (requestCode != RINGTONE_SYSTEM_REQUEST_CODE && requestCode != RINGTONE_FILE_REQUEST_CODE) return
                    val resultCode = param.args.getOrNull(1) as? Int ?: return
                    if (resultCode != Activity.RESULT_OK) return
                    val data = param.args.getOrNull(2) as? Intent ?: return
                    val picked = extractPickedUri(data, requestCode)
                    takeReadPermission(data, picked)
                    onPicked?.invoke(picked?.toString().orEmpty())
                }
            })
            hookedClasses.add(clazz)
        } catch (_: Throwable) {
        }
    }

    private fun extractPickedUri(data: Intent, requestCode: Int): Uri? {
        if (requestCode == RINGTONE_SYSTEM_REQUEST_CODE) {
            @Suppress("DEPRECATION")
            (data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI) as? Uri)?.let { return it }
        }
        data.data?.let { return it }
        @Suppress("DEPRECATION")
        (data.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)?.let { return it }
        firstUriInClipData(data.clipData)?.let { return it }
        return firstUriInBundle(data.extras)
    }

    private fun firstUriInClipData(clipData: ClipData?): Uri? {
        if (clipData == null || clipData.itemCount <= 0) return null
        return try {
            clipData.getItemAt(0)?.uri
        } catch (_: Throwable) {
            null
        }
    }

    private fun firstUriInBundle(bundle: Bundle?): Uri? {
        if (bundle == null) return null
        return try {
            bundle.keySet().firstNotNullOfOrNull { key ->
                when (val value = bundle.get(key)) {
                    is Uri -> value
                    is Intent -> value.data ?: firstUriInBundle(value.extras)
                    is Bundle -> firstUriInBundle(value)
                    else -> null
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun takeReadPermission(data: Intent, uri: Uri?) {
        if (uri == null || uri.scheme != "content") return
        try {
            val flags = data.flags and
                (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            if ((flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) return
            currentActivity?.contentResolver
                ?.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Throwable) {
        }
    }
}

internal object PluginMarketExtraFilePickerBridge {
    private val hookedClasses = HashSet<Class<*>>()
    private var pending: PendingResult? = null

    @Synchronized
    fun launch(activity: Activity, onResult: (List<PluginMarketFile>) -> Unit) {
        pending = PendingResult(activity, onResult)
        hookActivityResult(activity.javaClass)
        hookActivityResult(Activity::class.java)
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }.preferSystemDocumentsUi(activity)
        try {
            activity.startActivityForResult(intent, PLUGIN_MARKET_EXTRA_FILE_REQUEST_CODE)
        } catch (_: Throwable) {
            val fallback = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivityForResult(
                Intent.createChooser(fallback, "选择插件附加文件"),
                PLUGIN_MARKET_EXTRA_FILE_REQUEST_CODE
            )
        }
    }

    @Synchronized
    private fun hookActivityResult(clazz: Class<*>) {
        if (!hookedClasses.add(clazz)) return
        runCatching {
            XposedBridge.hookAllMethods(clazz, "onActivityResult", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val requestCode = param.args.getOrNull(0) as? Int ?: return
                    if (requestCode != PLUGIN_MARKET_EXTRA_FILE_REQUEST_CODE) return
                    val result = takePending() ?: return
                    val resultCode = param.args.getOrNull(1) as? Int ?: return
                    if (resultCode != Activity.RESULT_OK) return
                    val data = param.args.getOrNull(2) as? Intent ?: return
                    val uris = pickedUris(data)
                    if (uris.isEmpty()) return
                    Thread({
                        val files = uris.mapNotNull { uri -> readFile(result.activity, uri) }
                            .distinctBy { it.name.lowercase(Locale.ROOT) }
                        if (files.isNotEmpty()) {
                            Handler(Looper.getMainLooper()).post { result.callback(files) }
                        }
                    }, "Hchat-Plugin-Market-Files").start()
                }
            })
        }.onFailure {
            hookedClasses.remove(clazz)
        }
    }

    private fun pickedUris(data: Intent): List<Uri> {
        val result = ArrayList<Uri>()
        data.data?.let(result::add)
        data.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) {
                runCatching { clip.getItemAt(index)?.uri }.getOrNull()?.let(result::add)
            }
        }
        return result.distinct()
    }

    private fun readFile(activity: Activity, uri: Uri): PluginMarketFile? {
        return runCatching {
            val displayName = queryDisplayName(activity, uri)
                ?.substringAfterLast('/')
                ?.substringAfterLast('\\')
                ?.ifBlank { null }
                ?: uri.lastPathSegment?.substringAfterLast('/').orEmpty()
            require(displayName.isNotBlank()) { "无法读取所选文件名" }
            val bytes = java.io.ByteArrayOutputStream().use { output ->
                activity.contentResolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= PluginMarketInstaller.MAX_EXTRA_FILE_BYTES) {
                            "$displayName 超过 ${PluginMarketInstaller.MAX_EXTRA_FILE_BYTES / 1024} KiB"
                        }
                        output.write(buffer, 0, count)
                    }
                } ?: error("无法打开所选文件")
                output.toByteArray()
            }
            PluginMarketInstaller.createExternalFile(displayName, bytes)
        }.getOrElse {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(activity, it.message ?: "读取附加文件失败", Toast.LENGTH_SHORT).show()
            }
            null
        }
    }

    @Synchronized
    private fun takePending(): PendingResult? {
        val result = pending
        pending = null
        return result
    }

    private data class PendingResult(
        val activity: Activity,
        val callback: (List<PluginMarketFile>) -> Unit
    )
}

internal fun Intent.preferSystemDocumentsUi(context: Context): Intent {
    val candidates = listOf(
        "com.google.android.documentsui",
        "com.android.documentsui"
    )
    for (packageName in candidates) {
        val copy = Intent(this).setPackage(packageName)
        val resolved = runCatching {
            context.packageManager.queryIntentActivities(copy, 0)
        }.getOrDefault(emptyList())
        if (resolved.isNotEmpty()) {
            setPackage(packageName)
            break
        }
    }
    return this
}

internal object RedPacketReplyFilePickerBridge {
    private val hookedClasses = HashSet<Class<*>>()
    private var onPicked: ((List<String>) -> Unit)? = null
    private var currentActivity: Activity? = null

    @Synchronized
    fun launch(activity: Activity, replyMode: Int, onResult: (List<String>) -> Unit) {
        onPicked = onResult
        currentActivity = activity
        hookActivityResult(activity.javaClass)
        hookActivityResult(Activity::class.java)
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }.preferSystemDocumentsUi(activity)
        try {
            activity.startActivityForResult(intent, REDPACKET_REPLY_FILE_REQUEST_CODE)
        } catch (_: Throwable) {
            val fallback = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivityForResult(
                Intent.createChooser(fallback, redPacketReplyPickerTitle(replyMode)),
                REDPACKET_REPLY_FILE_REQUEST_CODE
            )
        }
    }

    @Synchronized
    private fun hookActivityResult(clazz: Class<*>) {
        if (hookedClasses.contains(clazz)) return
        try {
            XposedBridge.hookAllMethods(clazz, "onActivityResult", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val requestCode = param.args.getOrNull(0) as? Int ?: return
                    if (requestCode != REDPACKET_REPLY_FILE_REQUEST_CODE) return
                    val resultCode = param.args.getOrNull(1) as? Int ?: return
                    if (resultCode != Activity.RESULT_OK) return
                    val data = param.args.getOrNull(2) as? Intent ?: return
                    val activity = currentActivity ?: return
                    val paths = pickedUris(data)
                        .mapNotNull { uri ->
                            takeReadPermission(activity, data, uri)
                            copyReplyFileToCache(activity, uri)
                        }
                        .distinct()
                    if (paths.isNotEmpty()) onPicked?.invoke(paths)
                }
            })
            hookedClasses.add(clazz)
        } catch (_: Throwable) {
        }
    }

    private fun pickedUris(data: Intent): List<Uri> {
        val result = ArrayList<Uri>()
        data.data?.let { result.add(it) }
        val clip = data.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) {
                try {
                    clip.getItemAt(i)?.uri?.let { result.add(it) }
                } catch (_: Throwable) {
                }
            }
        }
        @Suppress("DEPRECATION")
        (data.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)?.let { result.add(it) }
        return result.distinct()
    }

    private fun takeReadPermission(activity: Activity, data: Intent, uri: Uri) {
        if (uri.scheme != "content") return
        try {
            val flags = data.flags and
                (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            if ((flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) return
            activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Throwable) {
        }
    }

    private fun copyReplyFileToCache(context: Context, uri: Uri): String? {
        if ("file".equals(uri.scheme, ignoreCase = true)) {
            val path = uri.path.orEmpty()
            if (path.isNotBlank() && File(path).isFile) return path
        }
        return try {
            val name = safeReplyFileName(queryDisplayName(context, uri) ?: uri.lastPathSegment ?: "reply_file")
            val dir = File(context.filesDir, "Hchat/redpacket_reply").apply { mkdirs() }
            val target = File(dir, "${System.currentTimeMillis()}_$name")
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            target.absolutePath.takeIf { target.isFile && target.length() > 0L }
        } catch (_: Throwable) {
            null
        }
    }

    private fun safeReplyFileName(value: String): String {
        return value.replace(Regex("""[\\/:*?"<>|]"""), "_")
            .trim()
            .ifBlank { "reply_file" }
    }
}

internal object AutoReplyFilePickerBridge {
    private val hookedClasses = HashSet<Class<*>>()
    private var onPicked: ((List<String>) -> Unit)? = null
    private var currentActivity: Activity? = null

    @Synchronized
    fun launch(activity: Activity, replyMode: Int, onResult: (List<String>) -> Unit) {
        onPicked = onResult
        currentActivity = activity
        hookActivityResult(activity.javaClass)
        hookActivityResult(Activity::class.java)
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }.preferSystemDocumentsUi(activity)
        try {
            activity.startActivityForResult(intent, AUTO_REPLY_FILE_REQUEST_CODE)
        } catch (_: Throwable) {
            val fallback = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivityForResult(
                Intent.createChooser(fallback, autoReplyPickerTitle(replyMode)),
                AUTO_REPLY_FILE_REQUEST_CODE
            )
        }
    }

    @Synchronized
    private fun hookActivityResult(clazz: Class<*>) {
        if (hookedClasses.contains(clazz)) return
        try {
            XposedBridge.hookAllMethods(clazz, "onActivityResult", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val requestCode = param.args.getOrNull(0) as? Int ?: return
                    if (requestCode != AUTO_REPLY_FILE_REQUEST_CODE) return
                    val resultCode = param.args.getOrNull(1) as? Int ?: return
                    if (resultCode != Activity.RESULT_OK) return
                    val data = param.args.getOrNull(2) as? Intent ?: return
                    val activity = currentActivity ?: return
                    val paths = pickedUris(data)
                        .mapNotNull { uri ->
                            takeReadPermission(activity, data, uri)
                            copyReplyFileToCache(activity, uri)
                        }
                        .distinct()
                    if (paths.isNotEmpty()) onPicked?.invoke(paths)
                }
            })
            hookedClasses.add(clazz)
        } catch (_: Throwable) {
        }
    }

    private fun pickedUris(data: Intent): List<Uri> {
        val result = ArrayList<Uri>()
        data.data?.let { result.add(it) }
        val clip = data.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) {
                try {
                    clip.getItemAt(i)?.uri?.let { result.add(it) }
                } catch (_: Throwable) {
                }
            }
        }
        @Suppress("DEPRECATION")
        (data.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)?.let { result.add(it) }
        return result.distinct()
    }

    private fun takeReadPermission(activity: Activity, data: Intent, uri: Uri) {
        if (uri.scheme != "content") return
        try {
            val flags = data.flags and
                (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            if ((flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) return
            activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Throwable) {
        }
    }

    private fun copyReplyFileToCache(context: Context, uri: Uri): String? {
        if ("file".equals(uri.scheme, ignoreCase = true)) {
            val path = uri.path.orEmpty()
            if (path.isNotBlank() && File(path).isFile) return path
        }
        return try {
            val name = safeReplyFileName(queryDisplayName(context, uri) ?: uri.lastPathSegment ?: "reply_file")
            val dir = File(context.filesDir, "Hchat/auto_reply").apply { mkdirs() }
            val target = File(dir, "${System.currentTimeMillis()}_$name")
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            target.absolutePath.takeIf { target.isFile && target.length() > 0L }
        } catch (_: Throwable) {
            null
        }
    }

    private fun safeReplyFileName(value: String): String {
        return value.replace(Regex("""[\\/:*?"<>|]"""), "_")
            .trim()
            .ifBlank { "reply_file" }
    }
}

internal object ScheduledTaskFilePickerBridge {
    private val hookedClasses = HashSet<Class<*>>()
    private var onPicked: ((List<String>) -> Unit)? = null
    private var currentActivity: Activity? = null

    @Synchronized
    fun launch(activity: Activity, taskType: Int, onResult: (List<String>) -> Unit) {
        onPicked = onResult
        currentActivity = activity
        hookActivityResult(activity.javaClass)
        hookActivityResult(Activity::class.java)
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }.preferSystemDocumentsUi(activity)
        try {
            activity.startActivityForResult(intent, SCHEDULED_TASK_FILE_REQUEST_CODE)
        } catch (_: Throwable) {
            val fallback = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivityForResult(
                Intent.createChooser(fallback, scheduledTaskPickerTitle(taskType)),
                SCHEDULED_TASK_FILE_REQUEST_CODE
            )
        }
    }

    @Synchronized
    private fun hookActivityResult(clazz: Class<*>) {
        if (hookedClasses.contains(clazz)) return
        try {
            XposedBridge.hookAllMethods(clazz, "onActivityResult", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val requestCode = param.args.getOrNull(0) as? Int ?: return
                    if (requestCode != SCHEDULED_TASK_FILE_REQUEST_CODE) return
                    val resultCode = param.args.getOrNull(1) as? Int ?: return
                    if (resultCode != Activity.RESULT_OK) return
                    val data = param.args.getOrNull(2) as? Intent ?: return
                    val activity = currentActivity ?: return
                    val paths = scheduledTaskPickedUris(data)
                        .mapNotNull { uri ->
                            scheduledTaskTakeReadPermission(activity, data, uri)
                            copyScheduledTaskFileToCache(activity, uri)
                        }
                        .distinct()
                    if (paths.isNotEmpty()) onPicked?.invoke(paths)
                }
            })
            hookedClasses.add(clazz)
        } catch (_: Throwable) {
        }
    }

    private fun scheduledTaskPickedUris(data: Intent): List<Uri> {
        val result = ArrayList<Uri>()
        data.data?.let { result.add(it) }
        data.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) {
                try {
                    clip.getItemAt(index)?.uri?.let { result.add(it) }
                } catch (_: Throwable) {
                }
            }
        }
        @Suppress("DEPRECATION")
        (data.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)?.let { result.add(it) }
        return result.distinct()
    }

    private fun scheduledTaskTakeReadPermission(activity: Activity, data: Intent, uri: Uri) {
        if (uri.scheme != "content") return
        try {
            val flags = data.flags and
                (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            if ((flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) return
            activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Throwable) {
        }
    }

    private fun copyScheduledTaskFileToCache(context: Context, uri: Uri): String? {
        if ("file".equals(uri.scheme, ignoreCase = true)) {
            val path = uri.path.orEmpty()
            if (path.isNotBlank() && File(path).isFile) return path
        }
        return try {
            val name = safeScheduledTaskFileName(queryDisplayName(context, uri) ?: uri.lastPathSegment ?: "scheduled_task_file")
            val dir = File(context.filesDir, "Hchat/scheduled_task").apply { mkdirs() }
            val target = File(dir, "${System.currentTimeMillis()}_$name")
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            target.absolutePath.takeIf { target.isFile && target.length() > 0L }
        } catch (_: Throwable) {
            null
        }
    }

    private fun safeScheduledTaskFileName(value: String): String {
        return value.replace(Regex("""[\\/:*?"<>|]"""), "_")
            .trim()
            .ifBlank { "scheduled_task_file" }
    }
}

internal object AudioTransformDocumentBridge {
    private val hookedClasses = HashSet<Class<*>>()
    private var onInputPicked: ((String, String) -> Unit)? = null
    private var onOutputPicked: ((Uri, String) -> Unit)? = null
    private var currentActivity: Activity? = null

    @Synchronized
    fun launchInput(activity: Activity, onResult: (String, String) -> Unit) {
        onInputPicked = onResult
        currentActivity = activity
        hookActivityResult(activity.javaClass)
        hookActivityResult(Activity::class.java)
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }.preferSystemDocumentsUi(activity)
        try {
            activity.startActivityForResult(intent, AUDIO_TRANSFORM_INPUT_REQUEST_CODE)
        } catch (_: Throwable) {
            val fallback = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivityForResult(
                Intent.createChooser(fallback, "选择音频文件"),
                AUDIO_TRANSFORM_INPUT_REQUEST_CODE
            )
        }
    }

    @Synchronized
    fun launchOutput(
        activity: Activity,
        suggestedFileName: String,
        mimeType: String,
        onResult: (Uri, String) -> Unit
    ) {
        onOutputPicked = onResult
        currentActivity = activity
        hookActivityResult(activity.javaClass)
        hookActivityResult(Activity::class.java)
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
            putExtra(Intent.EXTRA_TITLE, suggestedFileName)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.preferSystemDocumentsUi(activity)
        try {
            activity.startActivityForResult(intent, AUDIO_TRANSFORM_OUTPUT_REQUEST_CODE)
        } catch (_: Throwable) {
            Toast.makeText(activity, "当前系统不支持创建输出文件", Toast.LENGTH_SHORT).show()
        }
    }

    @Synchronized
    private fun hookActivityResult(clazz: Class<*>) {
        if (hookedClasses.contains(clazz)) return
        try {
            XposedBridge.hookAllMethods(clazz, "onActivityResult", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val requestCode = param.args.getOrNull(0) as? Int ?: return
                    if (requestCode != AUDIO_TRANSFORM_INPUT_REQUEST_CODE &&
                        requestCode != AUDIO_TRANSFORM_OUTPUT_REQUEST_CODE
                    ) return
                    val resultCode = param.args.getOrNull(1) as? Int ?: return
                    if (resultCode != Activity.RESULT_OK) return
                    val data = param.args.getOrNull(2) as? Intent ?: return
                    val activity = currentActivity ?: return
                    val uri = data.data ?: return
                    if (requestCode == AUDIO_TRANSFORM_INPUT_REQUEST_CODE) {
                        takeReadPermission(activity, data, uri)
                        val copied = copyInputFileToCache(activity, uri) ?: return
                        onInputPicked?.invoke(copied.first, copied.second)
                    } else {
                        val displayName = queryDisplayName(activity, uri)
                            ?: uri.lastPathSegment
                            ?.substringAfterLast('/')
                            ?.substringAfterLast(':')
                            .orEmpty()
                        onOutputPicked?.invoke(uri, displayName.ifBlank { "output" })
                    }
                }
            })
            hookedClasses.add(clazz)
        } catch (_: Throwable) {
        }
    }

    private fun takeReadPermission(activity: Activity, data: Intent, uri: Uri) {
        if (uri.scheme != "content") return
        try {
            val flags = data.flags and
                (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            if ((flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) return
            activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Throwable) {
        }
    }

    private fun copyInputFileToCache(context: Context, uri: Uri): Pair<String, String>? {
        if ("file".equals(uri.scheme, ignoreCase = true)) {
            val path = uri.path.orEmpty()
            if (path.isNotBlank() && File(path).isFile) {
                val name = File(path).name.ifBlank { "audio_input" }
                return path to name
            }
        }
        return try {
            val name = safeAudioTransformFileName(queryDisplayName(context, uri) ?: uri.lastPathSegment ?: "audio_input")
            val dir = File(context.filesDir, "Hchat/audio_transform/input").apply { mkdirs() }
            val target = File(dir, "${System.currentTimeMillis()}_$name")
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            if (!target.isFile || target.length() <= 0L) return null
            target.absolutePath to name
        } catch (_: Throwable) {
            null
        }
    }

    private fun safeAudioTransformFileName(value: String): String {
        return value.replace(Regex("""[\\/:*?"<>|]"""), "_")
            .trim()
            .ifBlank { "audio_input" }
    }
}

internal object ScriptPluginDocumentBridge {
    private val hookedClasses = HashSet<Class<*>>()
    private var exportCallback: ((Uri) -> Unit)? = null
    private var importCallback: ((Uri) -> Unit)? = null
    private var exportOwner: WeakReference<Activity>? = null
    private var importOwner: WeakReference<Activity>? = null

    @Synchronized
    fun launchExport(activity: Activity, fileName: String, onPicked: (Uri) -> Unit) {
        if (exportCallback != null) {
            Toast.makeText(activity, "已有导出文件选择正在进行", Toast.LENGTH_SHORT).show()
            return
        }
        exportCallback = onPicked
        exportOwner = WeakReference(activity)
        hookActivityResult(activity.javaClass)
        hookActivityResult(Activity::class.java)
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, fileName)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }.preferSystemDocumentsUi(activity)
        try {
            activity.startActivityForResult(intent, SCRIPT_PLUGIN_EXPORT_REQUEST_CODE)
        } catch (_: Throwable) {
            exportCallback = null
            exportOwner = null
            Toast.makeText(activity, "当前系统不支持创建 ZIP 文件", Toast.LENGTH_SHORT).show()
        }
    }

    @Synchronized
    fun launchImport(activity: Activity, onPicked: (Uri) -> Unit) {
        if (importCallback != null) {
            Toast.makeText(activity, "已有导入文件选择正在进行", Toast.LENGTH_SHORT).show()
            return
        }
        importCallback = onPicked
        importOwner = WeakReference(activity)
        hookActivityResult(activity.javaClass)
        hookActivityResult(Activity::class.java)
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }.preferSystemDocumentsUi(activity)
        try {
            activity.startActivityForResult(intent, SCRIPT_PLUGIN_IMPORT_REQUEST_CODE)
        } catch (_: Throwable) {
            val fallback = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                activity.startActivityForResult(
                    Intent.createChooser(fallback, "选择插件 ZIP 文件"),
                    SCRIPT_PLUGIN_IMPORT_REQUEST_CODE
                )
            } catch (_: Throwable) {
                importCallback = null
                importOwner = null
                Toast.makeText(activity, "当前系统不支持选择 ZIP 文件", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @Synchronized
    private fun hookActivityResult(clazz: Class<*>) {
        if (hookedClasses.contains(clazz)) return
        try {
            XposedBridge.hookAllMethods(clazz, "onActivityResult", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val requestCode = param.args.getOrNull(0) as? Int ?: return
                    if (requestCode != SCRIPT_PLUGIN_EXPORT_REQUEST_CODE &&
                        requestCode != SCRIPT_PLUGIN_IMPORT_REQUEST_CODE
                    ) {
                        return
                    }
                    val owner = param.thisObject as? Activity ?: return
                    val callback = synchronized(this@ScriptPluginDocumentBridge) {
                        if (requestCode == SCRIPT_PLUGIN_EXPORT_REQUEST_CODE) {
                            val matches = exportOwner?.get() === owner
                            exportCallback.takeIf { matches }.also {
                                exportCallback = null
                                exportOwner = null
                            }
                        } else {
                            val matches = importOwner?.get() === owner
                            importCallback.takeIf { matches }.also {
                                importCallback = null
                                importOwner = null
                            }
                        }
                    } ?: return
                    val resultCode = param.args.getOrNull(1) as? Int ?: return
                    if (resultCode != Activity.RESULT_OK) return
                    val data = param.args.getOrNull(2) as? Intent ?: return
                    val uri = data.data ?: return
                    if (requestCode == SCRIPT_PLUGIN_IMPORT_REQUEST_CODE && uri.scheme == "content") {
                        runCatching {
                            val flags = data.flags and
                                (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                            if ((flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                                owner.contentResolver.takePersistableUriPermission(
                                    uri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                                )
                            }
                        }
                    }
                    callback(uri)
                }
            })
            hookedClasses.add(clazz)
        } catch (_: Throwable) {
        }
    }
}

internal object ConfigImportExportBridge {
    private val hookedClasses = HashSet<Class<*>>()
    private var onImported: (() -> Unit)? = null
    private var currentActivity: Activity? = null

    @Synchronized
    fun launchExport(activity: Activity) {
        currentActivity = activity
        hookActivityResult(activity.javaClass)
        hookActivityResult(Activity::class.java)
        val fileName = "Hchat_config_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".json"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, fileName)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }.preferSystemDocumentsUi(activity)
        try {
            activity.startActivityForResult(intent, CONFIG_EXPORT_REQUEST_CODE)
        } catch (_: Throwable) {
            Toast.makeText(activity, "当前系统不支持创建配置文件", Toast.LENGTH_SHORT).show()
        }
    }

    @Synchronized
    fun launchImport(activity: Activity, onDone: () -> Unit) {
        onImported = onDone
        currentActivity = activity
        hookActivityResult(activity.javaClass)
        hookActivityResult(Activity::class.java)
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.preferSystemDocumentsUi(activity)
        try {
            activity.startActivityForResult(intent, CONFIG_IMPORT_REQUEST_CODE)
        } catch (_: Throwable) {
            val fallback = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivityForResult(Intent.createChooser(fallback, "选择 Hchat 配置文件"), CONFIG_IMPORT_REQUEST_CODE)
        }
    }

    @Synchronized
    private fun hookActivityResult(clazz: Class<*>) {
        if (hookedClasses.contains(clazz)) return
        try {
            XposedBridge.hookAllMethods(clazz, "onActivityResult", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val requestCode = param.args.getOrNull(0) as? Int ?: return
                    if (requestCode != CONFIG_EXPORT_REQUEST_CODE && requestCode != CONFIG_IMPORT_REQUEST_CODE) return
                    val resultCode = param.args.getOrNull(1) as? Int ?: return
                    if (resultCode != Activity.RESULT_OK) return
                    val data = param.args.getOrNull(2) as? Intent ?: return
                    val uri = data.data ?: return
                    val activity = currentActivity ?: return
                    if (requestCode == CONFIG_EXPORT_REQUEST_CODE) {
                        val count = HchatConfigBackup.exportToUri(activity, uri)
                        if (count > 0) {
                            Toast.makeText(activity, "已导出 $count 项配置", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(activity, "导出失败", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        val count = HchatConfigBackup.importFromUri(activity, uri)
                        if (count > 0) {
                            onImported?.invoke()
                            Toast.makeText(activity, "已导入 $count 项配置，当前设置页已刷新", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(activity, "导入失败或文件无配置", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            })
            hookedClasses.add(clazz)
        } catch (_: Throwable) {
        }
    }
}

internal object HchatConfigBackup {
    private const val VERSION = 1
    private const val TYPE_BOOLEAN = "boolean"
    private const val TYPE_INT = "int"
    private const val TYPE_LONG = "long"
    private const val TYPE_FLOAT = "float"
    private const val TYPE_STRING = "string"
    private const val TYPE_STRING_SET = "string_set"
    private const val KEY_TYPE = "type"
    private const val KEY_VALUE = "value"

    private val prefsNames = listOf(
        UI_PREFS_NAME,
        CallMediaLimitSettings.PREFS_NAME,
        FakeLocationSettings.PREFS_NAME,
        FinderMediaDownloadSettings.PREFS_NAME,
        HchatExtraSettings.PREFS_NAME,
        StatusTextLimitSettings.PREFS_NAME,
        EditMessageSettings.PREFS_NAME,
        DisableHotUpdateSettings.PREFS_NAME,
        RemoveMomentsAdsSettings.PREFS_NAME,
        MomentsAutoCommentSettings.PREFS_NAME,
        MomentsAutoLikeSettings.PREFS_NAME,
        MomentsAutoForwardSettings.PREFS_NAME,
        MomentsAutoRefreshSettings.PREFS_NAME,
        MomentsContactFilterSettings.PREFS_NAME,
        MomentsBottomDetailSettings.PREFS_NAME,
        MomentsPostNotificationSettings.PREFS_NAME,
        MomentsFakeInteractionSettings.PREFS_NAME,
        ProfileIdSettings.PREFS_NAME,
        SettingsEntrySettings.PREFS_NAME,
        FloatingShortcutSettings.PREFS_NAME,
        QuickMarkReadSettings.PREFS_NAME,
        CustomNotificationSettings.PREFS_NAME,
        ConversationGroupStore.PREFS_NAME,
        ConversationTabsStore.PREFS_NAME,
        AutoReplySettings.PREFS_NAME,
        MessageAffixSettings.PREFS_NAME,
        InputHintSettings.PREFS_NAME,
        LegacyChatInputSettings.PREFS_NAME,
        LinkParseSettings.PREFS_NAME,
        AutoOriginalImageSettings.PREFS_NAME,
        AutoViewOriginalSettings.PREFS_NAME,
        AtAllNotificationBlockSettings.PREFS_NAME,
        KeywordNotificationSettings.PREFS_NAME,
        TextSpeechSettings.PREFS_NAME,
        TextVoiceSettings.PREFS_NAME,
        ZombieCheckSettings.PREFS_NAME,
        WeChatKeepAliveSettings.PREFS_NAME,
        QuoteDeleteClearSettings.PREFS_NAME,
        ChatTimeStyleSettings.PREFS_NAME,
        EmojiSaveSettings.PREFS_NAME,
        SwipeQuoteSettings.PREFS_NAME,
        AudioTransformSettings.PREFS_NAME,
        FakeVoiceDurationSettings.PREFS_NAME,
        MessageBubbleSettings.PREFS_NAME,
        MessageTextColorSettings.PREFS_NAME,
        HideChatAvatarSettings.PREFS_NAME,
        HideChatMenuSettings.PREFS_NAME,
        QuickContactEditSettings.PREFS_NAME,
        RoundAvatarSettings.PREFS_NAME,
        CustomFriendAvatarSettings.PREFS_NAME,
        MessageBlockSettings.PREFS_NAME,
        GroupLeaveMonitorSettings.PREFS_NAME,
        GroupRenameMonitorSettings.PREFS_NAME,
        MultiRecallSettings.PREFS_NAME,
        VoiceForwardSettings.PREFS_NAME,
        FakeScanCameraSettings.PREFS_NAME,
        RedPacketSettings.PREFS_NAME,
        AntiRecallSettings.PREFS_NAME,
        ProtobufPacketSettings.PREFS_NAME,
        ScriptPluginSettings.PREFS_NAME,
        WeChatTabletSettings.PREFS_NAME,
        AutoTransferSettings.PREFS_NAME,
        FakeWalletBalanceSettings.PREFS_NAME,
        MemberTitleSettings.PREFS_NAME,
        RealNameTailSettings.PREFS_NAME,
        GroupNicknameColorSettings.PREFS_NAME
    ).distinct()

    fun exportToUri(context: Context, uri: Uri): Int {
        return runCatching {
            val prefsJson = JSONObject()
            var itemCount = 0
            prefsNames.forEach { name ->
                val sp = HchatStorage.preferences(context, name)
                val entries = JSONObject()
                sp.all.forEach { (key, value) ->
                    encodePreferenceValue(value)?.let { encoded ->
                        entries.put(key, encoded)
                        itemCount++
                    }
                }
                if (entries.length() > 0) {
                    prefsJson.put(name, entries)
                }
            }
            val root = JSONObject().apply {
                put("format", "HchatConfigBackup")
                put("version", VERSION)
                put("appVersion", BuildConfig.VERSION_NAME)
                put("exportedAt", System.currentTimeMillis())
                put("prefs", prefsJson)
            }
            context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                output.write(root.toString(2).toByteArray(Charsets.UTF_8))
            } ?: return 0
            itemCount
        }.getOrDefault(0)
    }

    fun importFromUri(context: Context, uri: Uri): Int {
        return runCatching {
            val text = context.contentResolver.openInputStream(uri)?.use { input ->
                input.reader(Charsets.UTF_8).readText()
            }.orEmpty()
            if (text.isBlank()) return 0
            val root = JSONObject(text)
            if (root.optString("format") != "HchatConfigBackup") return 0
            val prefsJson = root.optJSONObject("prefs") ?: return 0
            var itemCount = 0
            prefsNames.forEach { name ->
                val entries = prefsJson.optJSONObject(name) ?: return@forEach
                val editor = HchatStorage.preferences(context, name).edit().clear()
                val keys = entries.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val encoded = entries.optJSONObject(key) ?: continue
                    if (decodePreferenceValue(editor, key, encoded)) {
                        itemCount++
                    }
                }
                editor.commit()
            }
            itemCount
        }.getOrDefault(0)
    }

    private fun encodePreferenceValue(value: Any?): JSONObject? {
        val obj = JSONObject()
        when (value) {
            is Boolean -> {
                obj.put(KEY_TYPE, TYPE_BOOLEAN)
                obj.put(KEY_VALUE, value)
            }
            is Int -> {
                obj.put(KEY_TYPE, TYPE_INT)
                obj.put(KEY_VALUE, value)
            }
            is Long -> {
                obj.put(KEY_TYPE, TYPE_LONG)
                obj.put(KEY_VALUE, value)
            }
            is Float -> {
                obj.put(KEY_TYPE, TYPE_FLOAT)
                obj.put(KEY_VALUE, value.toDouble())
            }
            is String -> {
                obj.put(KEY_TYPE, TYPE_STRING)
                obj.put(KEY_VALUE, value)
            }
            is Set<*> -> {
                obj.put(KEY_TYPE, TYPE_STRING_SET)
                obj.put(KEY_VALUE, JSONArray().apply {
                    value.filterIsInstance<String>().forEach { put(it) }
                })
            }
            else -> return null
        }
        return obj
    }

    private fun decodePreferenceValue(
        editor: SharedPreferences.Editor,
        key: String,
        encoded: JSONObject
    ): Boolean {
        return try {
            when (encoded.optString(KEY_TYPE)) {
                TYPE_BOOLEAN -> editor.putBoolean(key, encoded.optBoolean(KEY_VALUE))
                TYPE_INT -> editor.putInt(key, encoded.optInt(KEY_VALUE))
                TYPE_LONG -> editor.putLong(key, encoded.optLong(KEY_VALUE))
                TYPE_FLOAT -> editor.putFloat(key, encoded.optDouble(KEY_VALUE).toFloat())
                TYPE_STRING -> editor.putString(key, encoded.optString(KEY_VALUE, ""))
                TYPE_STRING_SET -> editor.putStringSet(key, decodeStringSet(encoded.optJSONArray(KEY_VALUE)))
                else -> return false
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun decodeStringSet(array: JSONArray?): Set<String> {
        if (array == null) return emptySet()
        val result = LinkedHashSet<String>()
        for (i in 0 until array.length()) {
            result.add(array.optString(i, ""))
        }
        return result
    }
}

@Composable
internal fun Modifier.responsiveTap(
    onClick: () -> Unit,
    onPressedChange: (Boolean) -> Unit = {}
): Modifier {
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnPressedChange by rememberUpdatedState(onPressedChange)
    return pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            currentOnPressedChange(true)
            val up = waitForUpOrCancellation()
            currentOnPressedChange(false)
            if (up != null) {
                currentOnClick()
            }
        }
    }
}

@Composable
internal fun rememberPressFeedbackColor(pressed: Boolean): Color {
    var feedbackVisible by remember { mutableStateOf(false) }
    LaunchedEffect(pressed) {
        if (pressed) {
            feedbackVisible = true
        } else {
            delay(PRESS_RELEASE_DELAY_MS)
            feedbackVisible = false
        }
    }
    val target = if (feedbackVisible) {
        MiuixTheme.colorScheme.onSurface.copy(alpha = 0.075f)
    } else {
        Color.Transparent
    }
    return animateColorAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = if (pressed) 90 else 210),
        label = "PressFeedback"
    ).value
}

@Composable
internal fun HchatMiuixTheme(context: Context, content: @Composable () -> Unit) {
    val colors = if (isDarkMode(context)) {
        darkColorScheme()
    } else {
        lightColorScheme()
    }
    MiuixTheme(colors = colors, content = content)
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
internal fun MainSettingsPage(
    context: Context,
    scriptOnly: Boolean = false,
    providers: List<FeatureSettingsProvider>,
    listState: LazyListState,
    selectedTab: MainTab,
    configVersion: Int,
    onSelectedTab: (MainTab) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenProvider: (FeatureSettingsProvider) -> Unit,
    onOpenGroup: (FeatureGroupEntry) -> Unit,
    onOpenScriptPluginReadme: (ScriptPluginRuntime.ScriptPlugin) -> Unit,
    onOpenScriptPluginMarket: () -> Unit,
    onOpenScriptPluginManager: () -> Unit,
    onConfigImported: () -> Unit
) {
    val uiPrefs = remember(configVersion) { HchatStorage.preferences(context, UI_PREFS_NAME) }
    val glassSupported = remember { isLiquidGlassSupported() }
    var floatingNav by remember(configVersion) { mutableStateOf(uiPrefs.getBoolean(KEY_FLOATING_NAV, true)) }
    var glassNav by remember(configVersion) { mutableStateOf(glassSupported && uiPrefs.getBoolean(KEY_GLASS_NAV, true)) }
    val entertainmentListState = rememberLazyListState()
    val pluginListState = rememberLazyListState()
    val settingsListState = rememberLazyListState()
    val practicalProviders = providers.filter { it.category() == FeatureSettingsProvider.CATEGORY_PRACTICAL }
    val entertainmentProviders = providers.filter { it.category() == FeatureSettingsProvider.CATEGORY_ENTERTAINMENT }
    val enhanceProviders = providers.filter {
        it.category() == FeatureSettingsProvider.CATEGORY_ENHANCE &&
            it.featureId() != ScriptPluginFeature.ID
    }
    val practicalGroups = remember(practicalProviders, enhanceProviders) {
        practicalFeatureGroups(practicalProviders, enhanceProviders)
    }
    val entertainmentGroups = remember(entertainmentProviders) {
        entertainmentFeatureGroups(entertainmentProviders)
    }
    val scrollBehavior = MiuixScrollBehavior()
    val scope = rememberCoroutineScope()
    var gateUpdate by remember { mutableStateOf<ModuleUpdateInfo?>(null) }
    var showGateUpdate by remember { mutableStateOf(false) }
    val gatePrefs = remember { HchatStorage.preferences(context, MODULE_UPDATE_PREFS) }
    // scriptOnly（WCX 脚本入口）：不做 Hchat 模块的更新检测/更新弹窗，用户只要脚本这一套
    LaunchedEffect(Unit) {
        if (scriptOnly) return@LaunchedEffect
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val root = PluginMarketClient.moduleUpdate(context).getOrThrow()
                ModuleUpdateInfo.fromJson(root, PluginMarketSettings.serviceUrl(context))
            }
        }
        result.onSuccess { info ->
            gateUpdate = info
            val hasNewerVersion = info.versionCode > BuildConfig.VERSION_CODE
            gatePrefs.edit().putBoolean("has_update", hasNewerVersion).apply()
            if (hasNewerVersion && gatePrefs.getInt(MODULE_UPDATE_IGNORED_CODE, -1) != info.versionCode) {
                showGateUpdate = true
            }
        }
    }
    gateUpdate?.takeIf { showGateUpdate && it.versionCode > BuildConfig.VERSION_CODE }?.let { update ->
        ModuleUpdateDialog(
            context = context,
            activity = context as? Activity,
            update = update,
            prefs = gatePrefs,
            onDismiss = { showGateUpdate = false }
        )
    }
    val tabs = MainTab.entries
    val pagerState = rememberPagerState(
        initialPage = selectedTab.ordinal,
        pageCount = { tabs.size }
    )
    LaunchedEffect(pagerState.currentPage) {
        tabs.getOrNull(pagerState.currentPage)?.let(onSelectedTab)
    }
    val activeTab = tabs.getOrElse(pagerState.currentPage) { MainTab.PRACTICAL }
    PageScaffold(
        title = if (scriptOnly) "脚本插件" else "Hchat",
        largeTitle = if (scriptOnly) "脚本插件" else "Hchat",
        scrollBehavior = scrollBehavior,
        bottomBar = {
            if (!scriptOnly) {
                MainNavigationBar(
                    context = context,
                    selectedTab = activeTab,
                    floating = floatingNav,
                    glass = glassSupported && glassNav,
                    backdrop = it,
                    onSelected = { tab ->
                        scope.launch { pagerState.springAnimateToPage(tab.ordinal) }
                    }
                )
            }
        }
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !scriptOnly,
            modifier = Modifier
                .fillMaxSize(),
            key = { tabs[it] }
        ) { page ->
            val tab = tabs[page]
            val activeListState = when (tab) {
                MainTab.PRACTICAL -> listState
                MainTab.ENTERTAINMENT -> entertainmentListState
                MainTab.PLUGIN -> pluginListState
                MainTab.SETTINGS -> settingsListState
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
                state = activeListState,
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 78.dp
                )
            ) {
                item {
                    if (!scriptOnly) SettingsSearchEntry(onClick = onOpenSearch)
                }
                when (tab) {
                    MainTab.PRACTICAL -> {
                        featureGroupMenu(
                            title = "实用功能",
                            groups = practicalGroups,
                            onOpenGroup = onOpenGroup
                        )
                        if (practicalProviders.isEmpty() && enhanceProviders.isEmpty()) {
                            item { SmallTitle(text = "实用功能") }
                            item { FeatureListCard(emptyList(), "暂无实用功能", onOpenProvider) }
                        }
                    }
                    MainTab.ENTERTAINMENT -> {
                        featureGroupMenu(
                            title = "娱乐功能",
                            groups = entertainmentGroups,
                            onOpenGroup = onOpenGroup
                        )
                        if (entertainmentProviders.isEmpty()) {
                            item { SmallTitle(text = "娱乐功能") }
                            item { FeatureListCard(emptyList(), "暂无娱乐功能", onOpenProvider) }
                        }
                    }
                    MainTab.PLUGIN -> {
                        item { SmallTitle(text = "脚本插件") }
                        item {
                            ScriptPluginSettingsMiuixContent.ScriptPluginSettingsContent(
                                context,
                                onOpenScriptPluginReadme,
                                onOpenScriptPluginMarket,
                                onOpenScriptPluginManager
                            )
                        }
                    }
                    MainTab.SETTINGS -> {
                        item { SmallTitle(text = "设置") }
                        item {
                            SettingsCard {
                                SwitchRow(
                                    checked = floatingNav,
                                    title = "悬浮底栏",
                                    summary = "使用悬浮样式的底部导航栏",
                                    onCheckedChange = {
                                        floatingNav = it
                                        uiPrefs.edit().putBoolean(KEY_FLOATING_NAV, it).apply()
                                    }
                                )
                                InsetDivider()
                                SwitchRow(
                                    checked = glassSupported && glassNav,
                                    title = "液态玻璃",
                                    summary = if (glassSupported) {
                                        "启用悬浮底栏的液态玻璃效果"
                                    } else {
                                        "Android 13 以下不支持液态玻璃效果"
                                    },
                                    enabled = glassSupported,
                                    onCheckedChange = {
                                        if (!glassSupported) return@SwitchRow
                                        glassNav = it
                                        uiPrefs.edit().putBoolean(KEY_GLASS_NAV, it).apply()
                                    }
                                )
                            }
                        }
                        item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "关于") }
                        item { AboutCard(context) }
                        item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "配置") }
                        item {
                            ConfigBackupCard(
                                context = context,
                                onImported = onConfigImported
                            )
                        }
                        item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "群组") }
                        item { GroupLinksCard(context) }
                        item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "致谢") }
                        item { ThanksCard(context) }
                        item {
                            Text(
                                modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                                text = "Hchat",
                                color = MiuixTheme.colorScheme.onBackgroundVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun List<FeatureSettingsProvider>.filterByIds(vararg ids: String): List<FeatureSettingsProvider> {
    val order = ids.toList()
    return filter { it.featureId() in order }
        .sortedBy { order.indexOf(it.featureId()) }
}

internal fun practicalFeatureGroups(
    practicalProviders: List<FeatureSettingsProvider>,
    enhanceProviders: List<FeatureSettingsProvider>
): List<FeatureGroupEntry> {
    return listOf(
        FeatureGroupEntry(
            title = "聊天",
            providers = practicalProviders.filterByIds(
                AntiRecallFeature.ID,
                MultiRecallFeature.ID,
                AutoReplyFeature.ID,
                MessageAffixFeature.ID,
                LegacyChatInputFeature.ID,
                LinkParseFeature.ID,
                TypingReportBlockFeature.ID,
                PatBlockFeature.ID,
                AutoOriginalImageFeature.ID,
                AutoViewOriginalFeature.ID,
                RemoveForwardLimitFeature.ID,
                MessageBlockFeature.ID,
                QuickMarkReadFeature.ID,
                MessageDetailsSettingsProvider.FEATURE_ID,
                QuoteDeleteClearFeature.ID,
                SwipeQuoteFeature.ID,
                EmojiSaveFeature.ID,
                CallMediaLimitFeature.ID,
                CallRingtoneBlockFeature.ID
            )
        ),
        FeatureGroupEntry(
            title = "语音",
            providers = practicalProviders.filterByIds(
                AudioTransformFeature.ID,
                TextVoiceFeature.ID,
                FakeVoiceDurationFeature.ID,
                VoicePreviewFeature.ID,
                VoiceForwardFeature.ID
            )
        ),
        FeatureGroupEntry(
            title = "红包转账",
            providers = practicalProviders.filterByIds(
                AutoRedPacketFeature.ID,
                AutoGiftFeature.ID,
                AutoTransferFeature.ID,
                FakeWalletBalanceFeature.ID,
                RedPacketDetailsSettingsProvider.FEATURE_ID
            )
        ),
        FeatureGroupEntry(title = "增强", providers = enhanceProviders),
        FeatureGroupEntry(
            title = "群组",
            providers = practicalProviders.filterByIds(
                GroupChatLabelFeature.ID,
                AtAllNotificationBlockFeature.ID,
                GroupLeaveMonitorFeature.ID,
                GroupRenameMonitorFeature.ID
            )
        ),
        FeatureGroupEntry(
            title = "朋友圈",
            providers = practicalProviders.filterByIds(
                MomentsAutoLikeFeature.ID,
                MomentsAutoCommentFeature.ID,
                MomentsAutoForwardFeature.ID,
                MomentsAutoRefreshFeature.ID,
                MomentsContactFilterFeature.ID,
                MomentsKeywordBlockFeature.ID,
                MomentsBottomDetailFeature.ID,
                MomentsPostNotificationFeature.ID,
                OriginalMomentsUploadFeature.ID,
                MomentsUploadTailFeature.ID,
                SnsAntiRecallFeature.ID,
                MomentsFakeLikeSettingsProvider.ID,
                MomentsFakeCommentSettingsProvider.ID,
                MomentsFakeForwardSettingsProvider.ID,
                RemoveMomentsAdsFeature.ID
            )
        ),
        FeatureGroupEntry(
            title = "美化",
            providers = practicalProviders.filterByIds(
                MessageBubbleFeature.ID,
                MessageTextColorFeature.ID,
                HomeTextColorFeature.ID,
                ChatTimeStyleFeature.ID,
                InputHintFeature.ID,
                HideChatAvatarFeature.ID,
                RoundAvatarFeature.ID,
                CustomFriendAvatarFeature.ID,
                CustomBottomBarFeature.ID,
                FloatingBottomBarSettings.FEATURE_ID
            )
        ),
        FeatureGroupEntry(
            title = "界面",
            providers = practicalProviders.filterByIds(
                SettingsFeature.ID,
                FloatingShortcutFeature.ID,
                ProfileIdFeature.ID,
                GroupMemberHistorySettingsProvider.FEATURE_ID,
                QuickContactEditFeature.ID,
                QuickGroupChatLabelFeature.ID,
                QuickMomentsFeature.ID,
                QuickTerminateFeature.ID,
                HideChatMenuFeature.ID
            )
        ),
        FeatureGroupEntry(
            title = "杂项",
            providers = practicalProviders.filterByIds(
                WeChatKeepAliveFeature.ID,
                WeChatTabletFeature.ID,
                DisableHotUpdateFeature.ID,
                FakeMiniProgramBaseLibFeature.ID,
                SkipMiniProgramVideoAdsFeature.ID,
                SkipGlobalMiniProgramSplashAdsFeature.ID,
                FakeLocationFeature.ID,
                GameEmojiFeature.ID,
                FakeScanCameraFeature.ID,
                FinderMediaDownloadFeature.ID,
                SkipWebRiskSettingsProvider.FEATURE_ID
            )
        )
    ).filter { it.providers.isNotEmpty() }
}

internal fun entertainmentFeatureGroups(
    entertainmentProviders: List<FeatureSettingsProvider>
): List<FeatureGroupEntry> {
    return listOf(
        FeatureGroupEntry(
            title = "点歌",
            providers = entertainmentProviders.filterByIds(QQMusicOrderFeature.ID)
        ),
        FeatureGroupEntry(
            title = "群聊",
            providers = entertainmentProviders.filterByIds(
                RealNameTailFeature.ID,
                GroupNicknameColorSettings.FEATURE_ID,
                MemberTitleFeature.ID
            )
        ),
        FeatureGroupEntry(
            title = "状态",
            providers = entertainmentProviders.filterByIds(StatusTextLimitFeature.ID)
        ),
        FeatureGroupEntry(
            title = "聊天",
            providers = entertainmentProviders.filterByIds(EditMessageFeature.ID)
        ),
        FeatureGroupEntry(
            title = "抓包",
            providers = entertainmentProviders.filterByIds(ProtobufPacketFeature.ID)
        ),
        FeatureGroupEntry(
            title = "调试",
            providers = entertainmentProviders.filterByIds(CrashReportSettingsProvider.ID)
        )
    ).filter { it.providers.isNotEmpty() }
}

@Composable
internal fun SettingsSearchEntry(onClick: () -> Unit) {
    SearchBarSurface(
        query = "",
        placeholder = "搜索功能和插件",
        readOnly = true,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        onClick = onClick,
        onQueryChange = {}
    )
}

@Composable
internal fun SettingsSearchPage(
    context: Context,
    providers: List<FeatureSettingsProvider>,
    onBack: () -> Unit,
    onOpenProvider: (FeatureSettingsProvider, FeatureGroupEntry?) -> Unit,
    onOpenScriptPluginReadme: (ScriptPluginRuntime.ScriptPlugin) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var pluginListVersion by remember { mutableStateOf(0) }
    val uiPrefs = remember { HchatStorage.preferences(context, UI_PREFS_NAME) }
    var searchHistory by remember { mutableStateOf(readSearchHistory(uiPrefs)) }
    val practicalProviders = providers.filter { it.category() == FeatureSettingsProvider.CATEGORY_PRACTICAL }
    val entertainmentProviders = providers.filter { it.category() == FeatureSettingsProvider.CATEGORY_ENTERTAINMENT }
    val enhanceProviders = providers.filter {
        it.category() == FeatureSettingsProvider.CATEGORY_ENHANCE &&
            it.featureId() != ScriptPluginFeature.ID
    }
    val groups = remember(practicalProviders, enhanceProviders, entertainmentProviders) {
        practicalFeatureGroups(practicalProviders, enhanceProviders) +
            entertainmentFeatureGroups(entertainmentProviders)
    }
    val featureResults = remember(query, groups, providers) {
        filterFeatureSearchResults(query, groups, providers)
    }
    val plugins = remember(pluginListVersion) {
        ScriptPluginManager.listForDisplay(context).map { it.plugin }
    }
    val pluginResults = remember(query, plugins) { filterPluginSearchResults(query, plugins) }
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    fun recordSearch() {
        val next = rememberSearchKeyword(uiPrefs, query)
        searchHistory = next
    }
    DisposableEffect(context) {
        val subscription = ScriptPluginRuntime.subscribePluginCatalog(context) {
            Handler(Looper.getMainLooper()).post { pluginListVersion++ }
        }
        onDispose { subscription.unsubscribe() }
    }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 74.dp,
                bottom = navigationButtonBottomInset() + NAVIGATION_BUTTON_EXTRA_GAP + 24.dp
            )
        ) {
            if (query.isBlank()) {
                item { SearchHistorySection(searchHistory, onSelect = { query = it }, onClear = {
                    saveSearchHistory(uiPrefs, emptyList())
                    searchHistory = emptyList()
                }) }
            } else {
                item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "功能(${featureResults.size})") }
                item {
                    FeatureSearchResultCard(
                        results = featureResults,
                        onOpenProvider = { provider, group ->
                            recordSearch()
                            onOpenProvider(provider, group)
                        }
                    )
                }
                item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "插件(${pluginResults.size})") }
                item {
                    PluginSearchResultCard(
                        plugins = pluginResults,
                        onOpenReadme = { plugin ->
                            recordSearch()
                            onOpenScriptPluginReadme(plugin)
                        }
                    )
                }
            }
        }
        SearchPageTopBar(
            query = query,
            focusRequester = focusRequester,
            onQueryChange = { query = it },
            onCancel = onBack
        )
    }
}

@Composable
internal fun SearchPageTopBar(
    query: String,
    focusRequester: FocusRequester,
    onQueryChange: (String) -> Unit,
    onCancel: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(MiuixTheme.colorScheme.background)
            .padding(
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp,
                start = 12.dp,
                end = 12.dp,
                bottom = 8.dp
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SearchBarSurface(
            query = query,
            placeholder = "搜索功能和插件",
            modifier = Modifier.weight(1f),
            focusRequester = focusRequester,
            onQueryChange = onQueryChange
        )
        Text(
            text = "取消",
            color = MiuixTheme.colorScheme.primary,
            fontSize = 16.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable { onCancel() }
                .padding(horizontal = 4.dp, vertical = 10.dp)
        )
    }
}

@Composable
internal fun SearchBarSurface(
    query: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    focusRequester: FocusRequester? = null,
    onClick: (() -> Unit)? = null,
    onQueryChange: (String) -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MiuixTheme.colorScheme.secondaryVariant)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            imageVector = NavIcons.Search,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceVariantSummary)
        )
        if (readOnly) {
            Text(
                text = query.ifEmpty { placeholder },
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 16.sp,
                modifier = Modifier.weight(1f).padding(start = 10.dp)
            )
        } else {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = MiuixTheme.colorScheme.onSurface,
                    fontSize = 16.sp
                ),
                cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                modifier = Modifier.weight(1f)
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                    .padding(start = 10.dp),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                text = placeholder,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                fontSize = 16.sp
                            )
                        }
                        innerTextField()
                    }
                }
            )
        }
    }
}

@Composable
internal fun SearchHistorySection(
    history: List<String>,
    onSelect: (String) -> Unit,
    onClear: () -> Unit
) {
    if (history.isEmpty()) {
        EmptyText("暂无搜索记录")
        return
    }
    SmallTitle(text = "搜索记录")
    Column(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        history.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { keyword ->
                    ContactFilterChip(
                        text = keyword,
                        selected = false,
                        onClick = { onSelect(keyword) },
                        modifier = Modifier.weight(1f)
                    )
                }
                repeat(3 - row.size) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
    Text(
        text = "清除搜索记录",
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        fontSize = 14.sp,
        modifier = Modifier.fillMaxWidth()
            .padding(top = 18.dp)
            .clickable { onClear() },
        textAlign = TextAlign.Center
    )
}

@Composable
internal fun FeatureSearchResultCard(
    results: List<FeatureSearchResult>,
    onOpenProvider: (FeatureSettingsProvider, FeatureGroupEntry?) -> Unit
) {
    SettingsCard {
        if (results.isEmpty()) {
            EmptyText("没有匹配功能")
        } else {
            results.forEachIndexed { index, result ->
                FeatureSearchResultRow(
                    result = result,
                    onClick = { onOpenProvider(result.provider, result.group) }
                )
                if (index < results.lastIndex) {
                    InsetDivider()
                }
            }
        }
    }
}

@Composable
internal fun FeatureSearchResultRow(result: FeatureSearchResult, onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val pressFeedbackColor = rememberPressFeedbackColor(pressed)
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(pressFeedbackColor)
            .responsiveTap(
                onClick = onClick,
                onPressedChange = { pressed = it }
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = result.provider.title(), color = MiuixTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
            Text(
                text = result.summary,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 12.sp
            )
        }
        Text(text = "›", color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 22.sp)
    }
}

@Composable
internal fun PluginSearchResultCard(
    plugins: List<ScriptPluginRuntime.ScriptPlugin>,
    onOpenReadme: (ScriptPluginRuntime.ScriptPlugin) -> Unit
) {
    SettingsCard {
        if (plugins.isEmpty()) {
            EmptyText("没有匹配插件")
        } else {
            plugins.forEachIndexed { index, plugin ->
                PluginSearchResultRow(
                    plugin = plugin,
                    onClick = { onOpenReadme(plugin) }
                )
                if (index < plugins.lastIndex) {
                    InsetDivider()
                }
            }
        }
    }
}

@Composable
internal fun PluginSearchResultRow(plugin: ScriptPluginRuntime.ScriptPlugin, onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val pressFeedbackColor = rememberPressFeedbackColor(pressed)
    val title = buildString {
        append(plugin.displayName ?: plugin.name.ifBlank { plugin.dir.name })
        if (plugin.version.isNotBlank()) {
            append("(")
            append(plugin.version)
            append(")")
        }
    }
    val summary = buildString {
        append(plugin.dir.name)
        if (plugin.author.isNotBlank()) {
            append("\n作者: ")
            append(plugin.author)
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(pressFeedbackColor)
            .responsiveTap(
                onClick = onClick,
                onPressedChange = { pressed = it }
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = title,
                    color = MiuixTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f, fill = false)
                )
                ClickHintTag()
            }
            Text(
                text = summary,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 12.sp
            )
        }
        Text(text = "›", color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 22.sp)
    }
}

internal fun LazyListScope.featureGroupMenu(
    title: String,
    groups: List<FeatureGroupEntry>,
    onOpenGroup: (FeatureGroupEntry) -> Unit
) {
    if (groups.isEmpty()) return
    item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = title) }
    item {
        SettingsCard {
            groups.forEachIndexed { index, group ->
                FeatureGroupRow(
                    group = group,
                    onClick = { onOpenGroup(group) }
                )
                if (index < groups.lastIndex) {
                    InsetDivider()
                }
            }
        }
    }
}

@Composable
internal fun FeatureGroupSettingsPage(
    group: FeatureGroupEntry,
    listState: LazyListState,
    onBack: () -> Unit,
    onOpenProvider: (FeatureSettingsProvider) -> Unit
) {
    val scrollBehavior = MiuixScrollBehavior()
    PageScaffold(
        title = group.title,
        largeTitle = group.title,
        scrollBehavior = scrollBehavior,
        bottomBar = {
            BottomActionBar("返回", onBack)
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "功能") }
            item { FeatureListCard(group.providers, "暂无功能", onOpenProvider) }
        }
    }
}

internal enum class MainTab {
    PRACTICAL,
    ENTERTAINMENT,
    PLUGIN,
    SETTINGS
}

internal data class MainNavItem(
    val tab: MainTab,
    val label: String,
    val icon: ImageVector
)

internal data class FeatureGroupEntry(
    val title: String,
    val providers: List<FeatureSettingsProvider>
)

internal data class FeatureSearchResult(
    val provider: FeatureSettingsProvider,
    val group: FeatureGroupEntry?,
    val summary: String
)

internal data class TemplateVariable(
    val token: String,
    val label: String
)

internal sealed class DetailPage {
    object Search : DetailPage()
    data class FeatureGroup(val group: FeatureGroupEntry) : DetailPage()
    data class Feature(
        val provider: FeatureSettingsProvider,
        val sourceGroup: FeatureGroupEntry? = null,
        val returnToSearch: Boolean = false
    ) : DetailPage()
    data class ScriptPluginMarket(val parentFeature: Feature? = null) : DetailPage()
    data class ScriptPluginManager(val parentFeature: Feature? = null) : DetailPage()
}

internal data class SettingsNavKey(val page: DetailPage?) : NavKey


internal fun filterFeatureSearchResults(
    query: String,
    groups: List<FeatureGroupEntry>,
    allProviders: List<FeatureSettingsProvider>
): List<FeatureSearchResult> {
    val keyword = query.trim()
    if (keyword.isEmpty()) return emptyList()
    val grouped = LinkedHashSet<String>()
    val results = ArrayList<FeatureSearchResult>()
    groups.forEach { group ->
        group.providers.forEach { provider ->
            val matchedTerms = matchedFeatureSubTerms(keyword, provider)
            if (matchesFeature(keyword, provider, group.title) || matchedTerms.isNotEmpty()) {
                grouped.add(provider.featureId())
                results += FeatureSearchResult(
                    provider = provider,
                    group = group,
                    summary = featureSearchSummary(group.title, provider, matchedTerms)
                )
            }
        }
    }
    allProviders.forEach { provider ->
        val matchedTerms = matchedFeatureSubTerms(keyword, provider)
        if (provider.featureId() !in grouped && (matchesFeature(keyword, provider, null) || matchedTerms.isNotEmpty())) {
            results += FeatureSearchResult(
                provider = provider,
                group = null,
                summary = featureSearchSummary(null, provider, matchedTerms)
            )
        }
    }
    return results
}

internal fun featureSearchSummary(
    groupTitle: String?,
    provider: FeatureSettingsProvider,
    matchedTerms: List<String>
): String {
    return buildString {
        if (!groupTitle.isNullOrBlank()) {
            append(groupTitle)
            append(" / ")
        }
        append(provider.subtitle())
        if (matchedTerms.isNotEmpty()) {
            append("\n匹配: ")
            append(matchedTerms.joinToString("、"))
        }
    }
}

internal fun matchesFeature(
    query: String,
    provider: FeatureSettingsProvider,
    groupTitle: String?
): Boolean {
    return containsSearchText(provider.title(), query) ||
        containsSearchText(provider.subtitle(), query) ||
        containsSearchText(provider.featureId(), query) ||
        containsSearchText(groupTitle.orEmpty(), query)
}

internal fun matchedFeatureSubTerms(query: String, provider: FeatureSettingsProvider): List<String> {
    return featureSubSearchTerms(provider.featureId())
        .filter { containsSearchText(it, query) }
        .distinct()
        .take(5)
}

internal fun featureSubSearchTerms(featureId: String): List<String> {
    return when (featureId) {
        AntiRecallFeature.ID -> listOf(
            "私聊防撤回", "群聊防撤回", "公众号防撤回", "撤回提示", "自定义撤回提示", "发送时间", "撤回时间", "时间格式", "语音防撤回", "图片防撤回", "视频防撤回"
        )
        MultiRecallFeature.ID -> listOf("多选撤回", "批量撤回", "多选消息", "分享菜单", "撤回自己消息")
        MessageForwardFeature.ID -> listOf("转发", "转发[H]", "朋友圈", "好友", "分享", "群发助手", "好友标签", "朋友圈转发", "个人主页朋友圈")
        SelectedMessagesFeature.ID -> listOf("群发助手", "群发助手[H]", "定时转发[H]", "多选消息", "定时转发", "模块群发", "微信原生群发助手", "群发助手间隔延迟", "群发间隔延迟")
        AutoReplyFeature.ID -> listOf(
            "自动回复规则", "任意消息", "关键词", "正则", "艾特我", "@我", "艾特全体", "@所有人", "回复步骤", "延迟回复",
            "回复冷却时间", "冷却秒数", "最大回复次数",
            "小智", "小智AI", "小智回复", "小智语音", "小智语音回复", "小智文字回复", "唤醒词", "语音输入", "TTS",
            "上下文", "固定ID", "清空上下文", "智谱", "模型", "Agent", "角色", "语音角色", "好友自动化", "新好友打招呼"
        )
        AutoMessageForwardFeature.ID -> listOf(
            "消息自动转发", "自动转发消息", "转发规则", "监听会话", "转发到", "好友", "群聊", "公众号",
            "文字", "图片", "语音", "视频", "表情", "文件", "链接", "音乐", "小程序", "关键词", "包含关键词", "排除关键词", "替换关键词", "替换规则"
        )
        ConversationGroupFeature.ID -> listOf(
            "会话分组", "聊天分组", "嵌套分组", "归拢会话", "隐藏会话", "移动会话", "分组管理"
        )
        ConversationTabsFeature.ID -> listOf(
            "标签分组", "顶栏分组", "全部会话", "未读会话", "群聊", "私聊", "公众号", "自定义标签", "标签图标"
        )
        MessageAffixFeature.ID -> listOf(
            "发送文本格式", "消息格式", "消息前后缀", "消息前缀", "消息后缀", "固定文字", "发送时间", "换行"
        )
        InputHintFeature.ID -> listOf(
            "输入框提示", "聊天输入框", "默认提示", "占位文字", "发送统计", "消息数量", "文字字数"
        )
        LegacyChatInputFeature.ID -> listOf(
            "恢复旧版聊天输入框", "旧版输入栏", "聊天输入栏样式", "输入栏长按语音"
        )
        LinkParseFeature.ID -> listOf(
            "链接解析", "抖音", "小红书", "公开作品链接", "允许他人链接解析的聊天", "群聊仅@我触发"
        )
        MessageBlockFeature.ID -> listOf(
            "屏蔽消息", "屏蔽规则", "默认规则", "私聊规则", "群聊规则", "公众号规则", "任意消息", "关键词", "正则",
            "艾特我", "@我", "艾特全体", "@所有人", "允许入库", "消息入库", "模板", "名单", "批量绑定", "标签好友", "未知类型"
        )
        QuickMarkReadFeature.ID -> listOf("快捷已读", "一键已读", "长按已读", "通知已读", "会话已读")
        AutoOriginalImageFeature.ID -> listOf("自动勾选原图", "原图发送", "聊天图片原图", "发送图片")
        AutoViewOriginalFeature.ID -> listOf(
            "自动查看原图", "查看原图", "自动查看原视频", "查看原视频", "聊天图片", "聊天视频", "原画质"
        )
        RemoveForwardLimitFeature.ID -> listOf("移除转发限制", "转发上限", "9个会话", "多选转发", "原生转发")
        TypingReportBlockFeature.ID -> listOf("拦截正在输入上报", "屏蔽正在输入", "正在输入", "输入状态", "输入上报")
        PatBlockFeature.ID -> listOf("禁止拍一拍", "屏蔽拍一拍", "双击头像", "头像双击")
        MessageDetailsSettingsProvider.FEATURE_ID -> listOf(
            "消息显示时间", "消息显示详情", "消息详情", "消息时间", "消息类型", "时间格式", "时间颜色", "间距", "字体大小"
        )
        GroupMemberHistorySettingsProvider.FEATURE_ID -> listOf("历史发言记录", "群成员历史发言", "群成员资料")
        AtAllNotificationBlockFeature.ID -> listOf("屏蔽艾特所有人", "屏蔽@所有人", "艾特全体", "通知拦截")
        QuoteDeleteClearFeature.ID -> listOf("删除键清引用", "清除引用", "引用消息", "删除引用")
        EmojiSaveFeature.ID -> listOf("保存表情", "表情保存", "动画表情", "长按菜单", "本地文件")
        SwipeQuoteFeature.ID -> listOf("滑动手势", "左滑引用", "右滑复读", "长按复读", "菜单复读", "+1", "复读语音", "语音时长", "滑动距离")
        CallMediaLimitFeature.ID -> listOf("通话媒体限制", "语音通话", "视频通话", "播放语音", "查看视频", "打开拍摄")
        CallRingtoneBlockFeature.ID -> listOf("屏蔽通话铃声", "呼入铃声", "呼出铃声", "来电铃声", "等待铃声", "语音通话", "视频通话")
        AudioTransformFeature.ID -> listOf(
            "音频转换",
            "音频转码",
            "语音转码",
            "silk",
            "mp3",
            "m4a",
            "语音发送",
            "切割语音",
            "分段语音",
            "裁剪语音",
            "自动分割",
            "开始时间",
            "结束时间",
            "试听",
            "播放音频"
        )
        TextVoiceFeature.ID -> listOf("文本转语音", "文字转语音", "在线语音", "发送文字转语音", "转语音播放", "#tts", "英文语音", "TTS引擎", "TTS角色", "语速", "播放速度")
        FakeVoiceDurationFeature.ID -> listOf("伪造语音时长", "语音显示时长", "假语音时长", "语音秒数")
        VoicePreviewFeature.ID -> listOf("语音消息预览", "预览语音", "播放语音", "语音时长", "长按语音")
        FakeMiniProgramBaseLibFeature.ID -> listOf("兼容低版本小程序", "伪装小程序宿主版本", "阻止小程序升级跳转", "微信版本较低", "官方升级页")
        SkipMiniProgramVideoAdsFeature.ID -> listOf("跳过小程序视频广告", "小程序广告", "视频广告")
        SkipGlobalMiniProgramSplashAdsFeature.ID -> listOf("跳过全局小程序开屏广告", "小程序开屏广告", "启动广告", "全局广告")
        FakeLocationFeature.ID -> listOf("虚拟定位", "模拟定位", "小程序定位", "微信地图", "地图选点", "搜索地点", "经纬度", "GPS")
        GameEmojiFeature.ID -> listOf("指定骰子猜拳", "自定义骰子", "骰子点数", "猜拳结果", "剪刀", "石头", "布", "发送时选择")
        VoiceForwardFeature.ID -> listOf("语音转发", "语音保存", "合并语音", "合并MP3", "转发弹窗", "好友列表", "群聊列表", "语音列表", "批量转发")
        ScheduledTaskFeature.ID -> listOf("定时任务", "定时发送", "群发任务", "计划发送", "循环发送", "每日发送", "每周发送", "朋友圈", "图文", "视文")
        AutoRedPacketFeature.ID -> listOf(
            "自动抢红包", "红包关键词", "红包规则", "规则模板", "回复步骤", "私聊红包回复", "群红包回复", "延迟抢红包", "仅群聊", "仅私聊", "排除名单", "领取后回复"
        )
        AutoTransferFeature.ID -> listOf(
            "自动收款", "转账关键词", "转账规则", "延迟到账", "自动领取转账", "自动收转账", "收款回复",
            "收款模板", "适用聊天", "批量套用", "随机延迟", "禁收时段", "收款通知", "收款播报", "媒体回复"
        )
        FakeWalletBalanceFeature.ID -> listOf("伪造零钱", "钱包余额", "零钱余额", "零钱通", "经营账户", "金额显示")
        RedPacketDetailsSettingsProvider.FEATURE_ID -> listOf("红包显示详情", "红包详情", "红包金额", "领取时间")
        CustomNotificationFeature.ID -> listOf(
            "自定义通知", "通知规则", "通知模板", "私聊通知", "群聊通知", "公众号通知", "艾特我", "@我", "艾特全体", "@所有人", "免打扰通知", "微信消息免打扰"
        )
        KeywordNotificationFeature.ID -> listOf(
            "关键词通知", "关键词列表", "关键词模板", "通知模板", "艾特我", "@我", "艾特全体", "@所有人", "允许入库", "消息入库"
        )
        TextSpeechFeature.ID -> listOf(
            "文字转语音播报", "文字转语音", "语音播报", "TTS引擎", "语音引擎", "播报角色", "TTS角色", "声音角色", "音色", "Voice", "自动播放语音", "播放语音消息", "原语音", "音键控制", "音量键控制", "播报发送人", "免打扰", "开始时间", "结束时间", "设置允许名单", "允许名单"
        )
        ZombieCheckFeature.ID -> listOf("僵尸粉检测", "好友关系检测", "单向好友", "删除好友", "异常好友", "好友标签")
        QQMusicOrderFeature.ID -> listOf("QQ点歌", "点歌", "音乐卡片", "点歌发送卡片", "点歌发送语音", "触发词", "歌手", "封面", "AppID", "允许点歌聊天")
        GroupChatLabelFeature.ID -> listOf("群聊标签", "群组分类", "群聊分类", "标签群聊", "批量选择群聊")
        QuickGroupChatLabelFeature.ID -> listOf("快捷设置群聊标签", "群聊标签", "会话长按菜单", "聊天分组", "群聊分类")
        GroupLeaveMonitorFeature.ID -> listOf("进退群监控", "进群邀请", "退群提醒", "监听群", "批量保护", "新群保护", "群成员变化")
        GroupRenameMonitorFeature.ID -> listOf("改名监控", "群昵称", "群内昵称", "改名提醒", "改名卡片", "改名模板", "批量套用", "改名系统消息")
        MomentsAutoLikeFeature.ID -> listOf("朋友圈自动点赞", "自动点赞", "点赞自己的朋友圈", "点赞白名单", "点赞黑名单", "同一人每天点赞数量", "每日点赞上限", "随机延迟", "发布时间限制", "关键词过滤", "链接朋友圈", "音乐朋友圈", "卡片朋友圈", "未知类型朋友圈")
        MomentsAutoCommentFeature.ID -> listOf("朋友圈自动评论", "自动评论", "评论内容", "时间变量", "评论时间", "时间格式", "评论自己的朋友圈", "评论白名单", "评论黑名单", "同一人每天评论数量", "每日评论上限", "评论延迟", "随机延迟", "发布时间限制", "关键词过滤", "评论日志", "链接朋友圈", "音乐朋友圈", "卡片朋友圈", "未知类型朋友圈")
        MomentsAutoForwardFeature.ID -> listOf("朋友圈自动转发", "静默转发", "指定好友", "每日上限", "包含关键词", "排除关键词", "替换关键词", "替换规则", "文案替换", "文案模板", "链接朋友圈", "音乐朋友圈", "卡片朋友圈", "未知类型朋友圈")
        MomentsAutoRefreshFeature.ID -> listOf("朋友圈自动刷新", "后台刷新", "原生刷新", "刷新间隔", "刷新时段")
        MomentsContactFilterFeature.ID -> listOf("朋友圈过滤", "好友过滤", "过滤好友", "只看好友", "朋友圈白名单", "朋友圈黑名单", "好友标签", "聊天分组")
        MomentsKeywordBlockFeature.ID -> listOf("朋友圈关键词屏蔽", "朋友圈屏蔽", "关键词过滤", "隐藏朋友圈")
        MomentsBottomDetailFeature.ID -> listOf("朋友圈底部详情", "朋友圈时间", "底部时间", "自定义时间", "时间格式", "可见范围")
        MomentsPostNotificationFeature.ID -> listOf("朋友圈发布通知", "朋友圈提醒", "好友发布朋友圈", "朋友圈Toast", "通知模板")
        OriginalMomentsUploadFeature.ID -> listOf("朋友圈原图", "原图上传", "原视频", "原视频上传", "不压缩", "朋友圈图片", "朋友圈视频")
        MomentsUploadTailFeature.ID -> listOf("朋友圈上传尾巴", "SDK ID", "SDK名称", "SDK来源", "第三方来源")
        SnsAntiRecallFeature.ID -> listOf(
            "朋友圈防撤回", "朋友圈评论防撤回", "评论防撤回", "已删除标签", "自定义撤回提示", "评论撤回提示", "提示开关", "三天可见", "个人主页朋友圈", "发现页朋友圈", "强制旧版个人主页朋友圈"
        )
        MomentsFakeLikeSettingsProvider.ID -> listOf("朋友圈伪集赞", "伪集赞[H]", "本地点赞", "使用非好友", "群成员", "随机排序", "自动勾选好友", "排除名单", "随机选择好友", "凭空生成点赞", "虚拟点赞人", "伪造点赞数量", "隐藏长按菜单", "自定义文本")
        MomentsFakeCommentSettingsProvider.ID -> listOf("朋友圈伪评论", "伪评论[H]", "使用非好友", "群成员", "多选好友", "全选好友", "随机评论", "添加新评论", "评论池", "评论时间", "评论顺序", "隐藏长按菜单", "自定义文本", "清除伪评论")
        MomentsFakeForwardSettingsProvider.ID -> listOf("朋友圈伪转发", "伪转发[H]", "本地朋友圈", "日期", "时间", "重复转发", "隐藏长按菜单", "自定义文本", "清空伪转发", "调试日志")
        RemoveMomentsAdsFeature.ID -> listOf("去除朋友圈广告", "朋友圈广告", "广告屏蔽")
        MessageBubbleFeature.ID -> listOf("消息气泡", "聊天气泡", "自定义气泡", "左侧气泡", "右侧气泡", "红包气泡", "转账气泡", "系统消息气泡", "浅色模式", "深色模式", "NinePatch", "九宫格")
        MessageTextColorFeature.ID -> listOf("消息文本颜色", "文字颜色", "聊天气泡文字", "取色器", "浅色模式", "深色模式")
        HomeTextColorFeature.ID -> listOf("首页文字颜色", "标题颜色", "副标题颜色", "渐变文字", "微信首页", "通讯录", "发现", "我")
        ChatTimeStyleFeature.ID -> listOf("会话时间样式", "聊天时间", "微信时间", "自定义时间", "隐藏时间", "时间格式")
        HideChatAvatarFeature.ID -> listOf("隐藏头像", "隐藏自己头像", "隐藏对方头像", "聊天头像", "群聊头像", "私聊头像")
        CustomBottomBarFeature.ID -> listOf(
            "自定义底栏", "底部导航", "微信首页", "修改图标", "修改标题", "隐藏标题", "隐藏底栏"
        )
        FloatingBottomBarSettings.FEATURE_ID -> listOf(
            "悬浮底栏", "底部导航", "微信首页", "液态玻璃", "模糊半径", "隐藏标签", "显示角标", "振动反馈", "振动强度"
        )
        RoundAvatarFeature.ID -> listOf("圆角头像", "圆形头像", "头像圆角", "头像弧度", "朋友圈头像", "公众号头像", "通知头像")
        SettingsFeature.ID -> listOf(
            "设置入口",
            "微信设置入口",
            "加号菜单入口",
            "长按加号",
            "启动器入口",
            "入口开关"
        )
        FloatingShortcutFeature.ID -> listOf(
            "悬浮快捷菜单", "悬浮按钮", "快捷入口", "插件 Agent", "展开 Agent", "收起 Agent",
            "按钮外观", "主按钮大小", "主按钮颜色", "副按钮大小", "副按钮颜色", "按钮渐变",
            "菜单名称颜色", "菜单名称大小", "菜单文字",
            "微信页面", "扫一扫", "朋友圈", "视频号", "收藏", "钱包"
        )
        ProfileIdFeature.ID -> listOf("显示ID", "好友ID", "群聊ID", "wxid", "复制ID", "资料页ID")
        QuickContactEditFeature.ID -> listOf("快捷设置备注和标签", "好友备注", "好友标签", "新建标签", "会话长按菜单", "朋友圈头像")
        QuickMomentsFeature.ID -> listOf("快捷查看朋友圈", "朋友圈", "会话长按菜单", "个人朋友圈", "聊天分组", "自己", "企业微信联系人")
        QuickTerminateFeature.ID -> listOf("快捷终止", "结束微信", "终止微信", "加号菜单", "关闭微信")
        HideChatMenuFeature.ID -> listOf("隐藏聊天菜单", "长按菜单", "隐藏菜单项", "提醒", "搜一搜", "收藏")
        WeChatKeepAliveFeature.ID -> listOf("微信保活", "常驻通知", "WakeLock", "Root保活", "保活服务")
        WeChatTabletFeature.ID -> listOf("平板模式", "Pad模式", "设备登录", "多设备登录", "登录为平板")
        DisableHotUpdateFeature.ID -> listOf("屏蔽热更新", "禁用热更新", "Tinker", "补丁", "热补丁")
        FakeScanCameraFeature.ID -> listOf("伪造扫码相机", "扫码相机", "扫一扫", "相册扫码", "扫码入口")
        SkipWebRiskSettingsProvider.FEATURE_ID -> listOf("跳过网页风险", "网页风险", "WebView", "风险拦截")
        EditMessageFeature.ID -> listOf("修改聊天记录", "编辑消息", "改消息", "消息XML", "发送XML")
        ProtobufPacketFeature.ID -> listOf("Protobuf抓包", "Protobuf发包", "抓包", "发包", "pb", "网络包", "重放")
        CrashReportSettingsProvider.ID -> listOf("捕获异常日志", "异常日志", "闪退日志", "Java异常", "Native异常", "ANR", "调试")
        StatusTextLimitFeature.ID -> listOf("解除状态词长度限制", "状态词", "微信状态", "状态长度", "字数限制")
        RealNameTailFeature.ID -> listOf("实名尾巴", "群聊尾巴", "实名后缀", "尾巴格式", "群成员实名")
        GroupNicknameColorSettings.FEATURE_ID -> listOf("群昵称自定义颜色", "群昵称颜色", "群内昵称颜色", "昵称渐变", "昵称粗细", "颜色取色器")
        MemberTitleFeature.ID -> listOf("群成员头衔", "头衔颜色", "自定义头衔", "头衔模板", "群昵称头衔", "颜色取色器")
        ScriptPluginFeature.ID -> listOf("脚本插件", "插件列表", "插件 Agent", "AI 生成插件", "插件 README", "插件配置", "启用插件", "禁用插件")
        else -> emptyList()
    }
}

internal fun filterPluginSearchResults(
    query: String,
    plugins: List<ScriptPluginRuntime.ScriptPlugin>
): List<ScriptPluginRuntime.ScriptPlugin> {
    val keyword = query.trim()
    if (keyword.isEmpty()) return emptyList()
    return plugins.filter { plugin ->
        containsSearchText(plugin.displayName.orEmpty(), keyword) ||
            containsSearchText(plugin.name, keyword) ||
            containsSearchText(plugin.id, keyword) ||
            containsSearchText(plugin.dir.name, keyword) ||
            containsSearchText(plugin.author, keyword) ||
            containsSearchText(plugin.version, keyword)
    }
}

internal fun containsSearchText(text: String, query: String): Boolean {
    return text.contains(query, ignoreCase = true)
}

internal fun readSearchHistory(sp: SharedPreferences): List<String> {
    val raw = sp.getString(KEY_SEARCH_HISTORY, "").orEmpty()
    if (raw.isBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val keyword = array.optString(index).trim()
                if (keyword.isNotEmpty() && keyword !in this) add(keyword)
                if (size >= SEARCH_HISTORY_LIMIT) break
            }
        }
    }.getOrDefault(emptyList())
}

internal fun saveSearchHistory(sp: SharedPreferences, history: List<String>) {
    val array = JSONArray()
    history.take(SEARCH_HISTORY_LIMIT).forEach { array.put(it) }
    sp.edit().putString(KEY_SEARCH_HISTORY, array.toString()).apply()
}

internal fun rememberSearchKeyword(sp: SharedPreferences, keyword: String): List<String> {
    val value = keyword.trim()
    if (value.isBlank()) return readSearchHistory(sp)
    val next = (listOf(value) + readSearchHistory(sp).filterNot { it.equals(value, ignoreCase = true) })
        .take(SEARCH_HISTORY_LIMIT)
    saveSearchHistory(sp, next)
    return next
}

internal sealed class FeatureSubRoute {
    object Main : FeatureSubRoute()
    data class ContactPicker(val request: ContactPickerRequest) : FeatureSubRoute()
    data class OptionPicker(val request: OptionPickerRequest) : FeatureSubRoute()
}

internal sealed class MomentsAutoLikeRoute {
    object Main : MomentsAutoLikeRoute()
    data class ContactPicker(val listMode: Int, val request: ContactPickerRequest) : MomentsAutoLikeRoute()
}

internal sealed class AtAllNotificationBlockRoute {
    object Main : AtAllNotificationBlockRoute()
    data class GroupPicker(val request: ContactPickerRequest) : AtAllNotificationBlockRoute()
}

internal sealed class MomentsAutoCommentRoute {
    object Main : MomentsAutoCommentRoute()
    data class ContactPicker(val listMode: Int, val request: ContactPickerRequest) : MomentsAutoCommentRoute()
}

internal sealed class MomentsAutoForwardRoute {
    object Main : MomentsAutoForwardRoute()
    object ReplacementRules : MomentsAutoForwardRoute()
    data class ContactPicker(val request: ContactPickerRequest) : MomentsAutoForwardRoute()
}

internal sealed class MomentsContactFilterRoute {
    object Main : MomentsContactFilterRoute()
    data class ContactPicker(val request: ContactPickerRequest) : MomentsContactFilterRoute()
}

internal sealed class MomentsPostNotificationRoute {
    object Main : MomentsPostNotificationRoute()
    data class ContactPicker(val request: ContactPickerRequest) : MomentsPostNotificationRoute()
}

internal sealed class RedPacketRoute {
    object Main : RedPacketRoute()
    object TemplateManager : RedPacketRoute()
    object ListManager : RedPacketRoute()
    object ReplySteps : RedPacketRoute()
    data class TemplateEditor(val request: RedPacketTemplateEditorRequest) : RedPacketRoute()
    data class BindingEditor(val request: RedPacketBindingEditorRequest) : RedPacketRoute()
    data class ContactPicker(val request: ContactPickerRequest) : RedPacketRoute()
    data class RuleContactPicker(val request: MessageBlockContactPickerRequest) : RedPacketRoute()
    data class OptionPicker(val request: OptionPickerRequest) : RedPacketRoute()
}

internal sealed class AutoTransferRoute {
    object Main : AutoTransferRoute()
    object TemplateManager : AutoTransferRoute()
    object BindingManager : AutoTransferRoute()
    object BatchApply : AutoTransferRoute()
    data class GlobalReplySteps(val target: TransferReplyTarget) : AutoTransferRoute()
    data class TemplateEditor(val request: TransferTemplateEditorRequest) : AutoTransferRoute()
    data class BindingEditor(val request: TransferBindingEditorRequest) : AutoTransferRoute()
    data class ContactPicker(val request: ContactPickerRequest) : AutoTransferRoute()
}

internal enum class TransferReplyTarget {
    PRIVATE,
    GROUP
}

internal sealed class MessageBlockRoute {
    object Main : MessageBlockRoute()
    object TemplateManager : MessageBlockRoute()
    object ListManager : MessageBlockRoute()
    data class DefaultRuleEditor(val kind: MessageBlockDefaultRuleKind) : MessageBlockRoute()
    data class TemplateEditor(val request: MessageBlockTemplateEditorRequest) : MessageBlockRoute()
    data class BindingEditor(val request: MessageBlockBindingEditorRequest) : MessageBlockRoute()
    data class BatchBindingEditor(val request: MessageBlockBatchBindingEditorRequest) : MessageBlockRoute()
    data class ContactPicker(val request: ContactPickerRequest) : MessageBlockRoute()
    data class GroupMemberPicker(val request: GroupMemberPickerRequest) : MessageBlockRoute()
}

internal sealed class GroupLeaveRoute {
    object Main : GroupLeaveRoute()
    object GroupManager : GroupLeaveRoute()
    object TemplateManager : GroupLeaveRoute()
    object BatchTemplateBinding : GroupLeaveRoute()
    data class TemplateEditor(val request: GroupLeaveTemplateEditorRequest) : GroupLeaveRoute()
    data class GroupEditor(val groupId: String, val label: String) : GroupLeaveRoute()
    data class ContactPicker(val request: ContactPickerRequest) : GroupLeaveRoute()
    data class FavoritePicker(val request: FavoritePickerRequest) : GroupLeaveRoute()
}

internal sealed class GroupChatLabelRoute {
    object Main : GroupChatLabelRoute()
    data class Editor(val label: GroupChatLabel, val existing: Boolean) : GroupChatLabelRoute()
    data class GroupPicker(val label: GroupChatLabel, val existing: Boolean) : GroupChatLabelRoute()
}

internal sealed class ConversationGroupRoute {
    object Main : ConversationGroupRoute()
    data class Editor(val group: ConversationGroup, val existing: Boolean) : ConversationGroupRoute()
    data class ConversationPicker(val group: ConversationGroup, val existing: Boolean) : ConversationGroupRoute()
    data class ParentPicker(val group: ConversationGroup, val existing: Boolean) : ConversationGroupRoute()
    data class AutomaticGrouping(val group: ConversationGroup) : ConversationGroupRoute()
    data class AutomaticPicker(
        val group: ConversationGroup,
        val kind: ConversationGroupAutomaticPickerKind
    ) : ConversationGroupRoute()
}

internal enum class ConversationGroupAutomaticPickerKind(
    val title: String,
    val emptyText: String
) {
    GROUPS("选择自动归拢群聊", "没有可选群聊"),
    OFFICIAL_INCLUDE("选择要包含的公众号", "没有可选公众号"),
    OFFICIAL_EXCLUDE("选择要排除的公众号", "没有可选公众号")
}

internal sealed class GroupRenameRoute {
    object Main : GroupRenameRoute()
    object GroupManager : GroupRenameRoute()
    object TemplateManager : GroupRenameRoute()
    object BatchTemplateBinding : GroupRenameRoute()
    data class GroupEditor(val groupId: String, val label: String) : GroupRenameRoute()
    data class TemplateEditor(val index: Int, val template: GroupRenameReplyTemplate, val canDelete: Boolean) : GroupRenameRoute()
    data class ContactPicker(val request: ContactPickerRequest, val fromMain: Boolean = false) : GroupRenameRoute()
}

internal sealed class ScheduledTaskRoute {
    object Main : ScheduledTaskRoute()
    object Editor : ScheduledTaskRoute()
    object ContactPicker : ScheduledTaskRoute()
    data class FavoritePicker(val index: Int, val request: FavoritePickerRequest) : ScheduledTaskRoute()
}

internal sealed class SelectedMessagesRoute {
    object Main : SelectedMessagesRoute()
    object Editor : SelectedMessagesRoute()
    object ContactPicker : SelectedMessagesRoute()
    data class FavoritePicker(val request: FavoritePickerRequest) : SelectedMessagesRoute()
}

internal sealed class AudioTransformRoute {
    object Main : AudioTransformRoute()
    object ContactPicker : AudioTransformRoute()
}

internal sealed class CustomNotificationRoute {
    object Main : CustomNotificationRoute()
    object RuleList : CustomNotificationRoute()
    object BatchEditor : CustomNotificationRoute()
    data class DefaultEditor(val kind: CustomNotificationDefaultKind) : CustomNotificationRoute()
    data class RuleEditor(val talker: String) : CustomNotificationRoute()
    data class ContactPicker(val request: ContactPickerRequest) : CustomNotificationRoute()
    data class GroupMemberPicker(val talker: String, val request: GroupMemberPickerRequest) : CustomNotificationRoute()
}

internal sealed class KeywordNotificationRoute {
    object Main : KeywordNotificationRoute()
    object KeywordList : KeywordNotificationRoute()
    object TemplateEditor : KeywordNotificationRoute()
    data class KeywordEditor(val oldKeyword: String?) : KeywordNotificationRoute()
    data class ContactPicker(val request: ContactPickerRequest, val includeMode: Boolean) : KeywordNotificationRoute()
}

internal sealed class TextSpeechRoute {
    object Main : TextSpeechRoute()
    data class ContactPicker(val request: ContactPickerRequest) : TextSpeechRoute()
}

internal enum class MessageBlockDefaultRuleKind {
    PRIVATE,
    GROUP,
    OFFICIAL
}

internal enum class CustomNotificationDefaultKind {
    PRIVATE,
    GROUP,
    OFFICIAL
}

internal data class AutoReplyFriendAutomationKeys(
    val labelNewFriendEnable: String,
    val labelDateEnable: String,
    val labelDateFormat: String,
    val labelExistingEnable: String,
    val labelSelectedNames: String,
    val remarkNewFriendEnable: String,
    val remarkNicknameSuffixEnable: String,
    val remarkDateEnable: String,
    val remarkDateFormat: String,
    val remarkCustomEnable: String,
    val remarkCustomText: String
)

internal sealed class AutoReplyEditorRoute {
    object Main : AutoReplyEditorRoute()
    data class OptionPicker(val request: OptionPickerRequest) : AutoReplyEditorRoute()
    data class ContactPicker(val request: ContactPickerRequest) : AutoReplyEditorRoute()
    data class GroupMemberPicker(val request: GroupMemberPickerRequest) : AutoReplyEditorRoute()
}

internal sealed class AutoReplyStepsRoute {
    object Main : AutoReplyStepsRoute()
    data class OptionPicker(val request: OptionPickerRequest) : AutoReplyStepsRoute()
    data class ContactPicker(val request: ContactPickerRequest) : AutoReplyStepsRoute()
    data class FavoritePicker(val request: FavoritePickerRequest) : AutoReplyStepsRoute()
}

internal sealed class AutoReplyAiRoute {
    object Main : AutoReplyAiRoute()
    object Xiaozhi : AutoReplyAiRoute()
    object Zhilia : AutoReplyAiRoute()
    object ZhiliaConfigs : AutoReplyAiRoute()
    object ZhiliaModels : AutoReplyAiRoute()
    data class OptionPicker(val request: OptionPickerRequest) : AutoReplyAiRoute()
}

internal sealed class RedPacketReplyStepsRoute {
    object Main : RedPacketReplyStepsRoute()
    data class StepEditor(val index: Int) : RedPacketReplyStepsRoute()
    data class FavoritePicker(val request: FavoritePickerRequest) : RedPacketReplyStepsRoute()
}

internal sealed class MessageBlockContactPickerRoute {
    object Labels : MessageBlockContactPickerRoute()
    data class Contacts(
        val filter: MessageBlockContactFilter,
        val label: MessageBlockLabelOption?
    ) : MessageBlockContactPickerRoute()
}

internal sealed class GroupMemberPickerRoute {
    object Groups : GroupMemberPickerRoute()
    data class Members(val group: ContactOption) : GroupMemberPickerRoute()
}

internal fun FeatureSubRoute.depth(): Int = when (this) {
    FeatureSubRoute.Main -> 0
    is FeatureSubRoute.ContactPicker,
    is FeatureSubRoute.OptionPicker -> 1
}

internal fun RedPacketRoute.depth(): Int = when (this) {
    RedPacketRoute.Main -> 0
    RedPacketRoute.TemplateManager,
    RedPacketRoute.ListManager,
    RedPacketRoute.ReplySteps,
    is RedPacketRoute.ContactPicker,
    is RedPacketRoute.OptionPicker -> 1
    is RedPacketRoute.TemplateEditor,
    is RedPacketRoute.BindingEditor,
    is RedPacketRoute.RuleContactPicker -> 2
}

internal fun AutoTransferRoute.depth(): Int = when (this) {
    AutoTransferRoute.Main -> 0
    AutoTransferRoute.TemplateManager,
    AutoTransferRoute.BindingManager,
    AutoTransferRoute.BatchApply,
    is AutoTransferRoute.GlobalReplySteps,
    is AutoTransferRoute.ContactPicker -> 1
    is AutoTransferRoute.TemplateEditor,
    is AutoTransferRoute.BindingEditor -> 2
}

internal fun MessageBlockRoute.depth(): Int = when (this) {
    MessageBlockRoute.Main -> 0
    MessageBlockRoute.TemplateManager,
    is MessageBlockRoute.DefaultRuleEditor,
    MessageBlockRoute.ListManager -> 1
    is MessageBlockRoute.TemplateEditor,
    is MessageBlockRoute.BindingEditor,
    is MessageBlockRoute.BatchBindingEditor,
    is MessageBlockRoute.ContactPicker,
    is MessageBlockRoute.GroupMemberPicker -> 2
}

internal fun GroupLeaveRoute.depth(): Int = when (this) {
    GroupLeaveRoute.Main -> 0
    GroupLeaveRoute.GroupManager,
    GroupLeaveRoute.TemplateManager,
    GroupLeaveRoute.BatchTemplateBinding,
    is GroupLeaveRoute.ContactPicker,
    is GroupLeaveRoute.FavoritePicker -> 1
    is GroupLeaveRoute.TemplateEditor,
    is GroupLeaveRoute.GroupEditor -> 2
}

internal fun ScheduledTaskRoute.depth(): Int = when (this) {
    ScheduledTaskRoute.Main -> 0
    ScheduledTaskRoute.Editor -> 1
    ScheduledTaskRoute.ContactPicker,
    is ScheduledTaskRoute.FavoritePicker -> 2
}

internal fun SelectedMessagesRoute.depth(): Int = when (this) {
    SelectedMessagesRoute.Main -> 0
    SelectedMessagesRoute.Editor -> 1
    SelectedMessagesRoute.ContactPicker,
    is SelectedMessagesRoute.FavoritePicker -> 2
}

internal fun AudioTransformRoute.depth(): Int = when (this) {
    AudioTransformRoute.Main -> 0
    AudioTransformRoute.ContactPicker -> 1
}

internal fun CustomNotificationRoute.depth(): Int = when (this) {
    CustomNotificationRoute.Main -> 0
    CustomNotificationRoute.RuleList,
    is CustomNotificationRoute.DefaultEditor,
    is CustomNotificationRoute.ContactPicker -> 1
    CustomNotificationRoute.BatchEditor,
    is CustomNotificationRoute.RuleEditor,
    is CustomNotificationRoute.GroupMemberPicker -> 2
}

internal fun KeywordNotificationRoute.depth(): Int = when (this) {
    KeywordNotificationRoute.Main -> 0
    KeywordNotificationRoute.KeywordList,
    KeywordNotificationRoute.TemplateEditor,
    is KeywordNotificationRoute.ContactPicker -> 1
    is KeywordNotificationRoute.KeywordEditor -> 2
}

internal fun TextSpeechRoute.depth(): Int = when (this) {
    TextSpeechRoute.Main -> 0
    is TextSpeechRoute.ContactPicker -> 1
}

internal fun autoReplyRouteDepth(route: String): Int = when (route) {
    "main" -> 0
    "rules",
    "autoAccept",
    "greetAccepted",
    "ai" -> 1
    "ruleEditor",
    "steps",
    "autoAcceptLabels",
    "greetAcceptedLabels" -> 2
    else -> 0
}

internal fun AutoReplyEditorRoute.depth(): Int = when (this) {
    AutoReplyEditorRoute.Main -> 0
    is AutoReplyEditorRoute.OptionPicker,
    is AutoReplyEditorRoute.ContactPicker,
    is AutoReplyEditorRoute.GroupMemberPicker -> 1
}

internal fun AutoReplyStepsRoute.depth(): Int = when (this) {
    AutoReplyStepsRoute.Main -> 0
    is AutoReplyStepsRoute.OptionPicker,
    is AutoReplyStepsRoute.ContactPicker,
    is AutoReplyStepsRoute.FavoritePicker -> 1
}

internal fun AutoReplyAiRoute.depth(): Int = when (this) {
    AutoReplyAiRoute.Main -> 0
    AutoReplyAiRoute.Xiaozhi,
    AutoReplyAiRoute.Zhilia -> 1
    AutoReplyAiRoute.ZhiliaConfigs,
    AutoReplyAiRoute.ZhiliaModels,
    is AutoReplyAiRoute.OptionPicker -> 2
}

internal fun RedPacketReplyStepsRoute.depth(): Int = when (this) {
    RedPacketReplyStepsRoute.Main -> 0
    is RedPacketReplyStepsRoute.StepEditor -> 1
    is RedPacketReplyStepsRoute.FavoritePicker -> 2
}

internal fun MessageBlockContactPickerRoute.depth(): Int = when (this) {
    MessageBlockContactPickerRoute.Labels -> 0
    is MessageBlockContactPickerRoute.Contacts -> if (label == null) 0 else 1
}

internal fun GroupMemberPickerRoute.depth(): Int = when (this) {
    GroupMemberPickerRoute.Groups -> 0
    is GroupMemberPickerRoute.Members -> 1
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
internal fun <T> SettingsRouteTransition(
    targetState: T,
    modifier: Modifier = Modifier.fillMaxSize(),
    label: String,
    depthOf: (T) -> Int,
    horizontalOnSameDepth: Boolean = true,
    content: @Composable (T) -> Unit
) {
    AnimatedContent(
        targetState = targetState,
        transitionSpec = {
            val initialDepth = depthOf(initialState)
            val targetDepth = depthOf(targetState)
            if (!horizontalOnSameDepth && initialDepth == targetDepth) {
                fadeIn(animationSpec = tween(120)) togetherWith
                    fadeOut(animationSpec = tween(90))
            } else {
                val forward = targetDepth >= initialDepth
                val enter = slideInHorizontally(
                    animationSpec = tween(240),
                    initialOffsetX = { width -> if (forward) width else -width / 4 }
                ) + fadeIn(animationSpec = tween(160))
                val exit = slideOutHorizontally(
                    animationSpec = tween(220),
                    targetOffsetX = { width -> if (forward) -width / 4 else width }
                ) + fadeOut(animationSpec = tween(140))
                enter togetherWith exit
            }
        },
        modifier = modifier.background(MiuixTheme.colorScheme.background),
        label = label
    ) { route ->
        content(route)
    }
}

@Composable
internal fun MainNavigationBar(
    context: Context,
    selectedTab: MainTab,
    floating: Boolean,
    glass: Boolean,
    backdrop: Backdrop,
    onSelected: (MainTab) -> Unit
) {
    val items = remember {
        listOf(
            MainNavItem(MainTab.PRACTICAL, "实用", NavIcons.Practical),
            MainNavItem(MainTab.ENTERTAINMENT, "娱乐", NavIcons.Entertainment),
            MainNavItem(MainTab.PLUGIN, "插件", NavIcons.Modules),
            MainNavItem(MainTab.SETTINGS, "设置", NavIcons.Settings)
        )
    }
    val selectedIndex = items.indexOfFirst { it.tab == selectedTab }.coerceAtLeast(0)
    val updateDot = HchatStorage.preferences(context, MODULE_UPDATE_PREFS).getBoolean("has_update", false) &&
        HchatStorage.preferences(context, MODULE_UPDATE_PREFS).getInt(MODULE_UPDATE_IGNORED_CODE, -1) != BuildConfig.VERSION_CODE
    val contentColor = MiuixTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = if (floating) 12.dp else 4.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        FloatingBottomBar(
            selectedIndex = selectedIndex,
            onSelected = { index -> onSelected(items[index].tab) },
            backdrop = backdrop,
            tabsCount = items.size,
            isBlurEnabled = glass,
            floating = floating
        ) { selectTab ->
            items.forEachIndexed { index, item ->
                val selected = index == selectedIndex
                FloatingBottomBarItem(
                    onClick = { selectTab(index) },
                    modifier = Modifier.defaultMinSize(minWidth = 76.dp)
                ) {
                    Box(contentAlignment = Alignment.TopEnd) {
                        Image(
                            imageVector = item.icon,
                            contentDescription = item.label,
                            colorFilter = ColorFilter.tint(contentColor),
                            modifier = Modifier.size(26.dp)
                        )
                        if (item.tab == MainTab.SETTINGS && updateDot) {
                            Box(
                                Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(Color.Red)
                            )
                        }
                    }
                    Text(
                        text = item.label,
                        color = contentColor,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
internal fun GiftMiuixPage(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit
) {
    val sp = remember { HchatStorage.preferences(context, GiftSettings.PREFS_NAME) }
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()
    val timeFormat = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }
    var successCount by remember { mutableStateOf(sp.getInt(GiftSettings.KEY_SUCCESS, 0)) }
    var failedCount by remember { mutableStateOf(sp.getInt(GiftSettings.KEY_FAILED, 0)) }
    var lastOrder by remember { mutableStateOf(sp.getString(GiftSettings.KEY_LAST_ORDER, "").orEmpty()) }
    var lastTime by remember { mutableStateOf(sp.getLong(GiftSettings.KEY_LAST_TIME, 0L)) }
    var delayText by remember { mutableStateOf(sp.getInt(GiftSettings.KEY_DELAY_VALUE, 0).toString()) }

    PageScaffold(
        title = provider.title(),
        largeTitle = provider.title(),
        scrollBehavior = scrollBehavior,
        bottomBar = { BottomActionBar("返回", onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "自动领取") }
            item {
                SettingsCard {
                    SwitchRow(
                        sp,
                        GiftSettings.KEY_ENABLE,
                        "自动抢礼物",
                        "收到礼物消息时自动静默领取",
                        false
                    )
                    SwitchRow(
                        sp,
                        GiftSettings.KEY_LOG_ENABLE,
                        "记录日志",
                        "在 LSPosed 日志中输出领取过程，便于排查问题",
                        false
                    )
                }
            }
            item { SmallTitle(text = "领取设置") }
            item {
                SettingsCard {
                    InputRow(
                        title = "延迟领取",
                        summary = "收到礼物后延迟多少毫秒再领取，0 为立即领取",
                        value = delayText,
                        onValueChange = { raw ->
                            val text = raw.filter { it.isDigit() }.take(6)
                            delayText = text
                            sp.edit()
                                .putInt(GiftSettings.KEY_DELAY_VALUE, text.toIntOrNull() ?: 0)
                                .putInt(GiftSettings.KEY_DELAY_UNIT, 0)
                                .apply()
                        }
                    )
                }
            }
            item { SmallTitle(text = "领取统计") }
            item {
                SettingsCard {
                    InfoRow("成功", successCount.toString())
                    InfoRow("失败", failedCount.toString())
                    InfoRow("最近订单", lastOrder.ifEmpty { "无" })
                    InfoRow("最近时间", if (lastTime > 0L) timeFormat.format(Date(lastTime)) else "无")
                    InfoRow("刷新统计", "") {
                        successCount = sp.getInt(GiftSettings.KEY_SUCCESS, 0)
                        failedCount = sp.getInt(GiftSettings.KEY_FAILED, 0)
                        lastOrder = sp.getString(GiftSettings.KEY_LAST_ORDER, "").orEmpty()
                        lastTime = sp.getLong(GiftSettings.KEY_LAST_TIME, 0L)
                    }
                }
            }
        }
    }
}

@Composable
internal fun FeatureSettingsPage(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit,
    onOpenScriptPluginMarket: () -> Unit = {},
    onOpenScriptPluginManager: () -> Unit = {}
) {
    when (provider.featureId()) {
        AutoRedPacketFeature.ID -> RedPacketMiuixPage(context, provider, onBack)
        AutoGiftFeature.ID -> GiftMiuixPage(context, provider, onBack)
        AutoTransferFeature.ID -> AutoTransferMiuixPage(context, provider, onBack)
        FakeWalletBalanceFeature.ID -> FakeWalletBalanceMiuixPage(context, provider, onBack)
        RealNameTailFeature.ID -> RealNameTailMiuixPage(context, provider, onBack)
        GroupNicknameColorSettings.FEATURE_ID -> GroupNicknameColorMiuixPage(context, provider, onBack)
        MemberTitleFeature.ID -> MemberTitleMiuixPage(context, provider, onBack)
        AntiRecallFeature.ID -> AntiRecallMiuixPage(context, provider, onBack)
        MultiRecallFeature.ID -> MultiRecallMiuixPage(context, provider, onBack)
        MessageForwardFeature.ID -> MessageForwardMiuixPage(context, provider, onBack)
        SelectedMessagesFeature.ID -> SelectedMessagesMiuixPage(context, provider, onBack)
        CallMediaLimitFeature.ID -> CallMediaLimitMiuixPage(context, provider, onBack)
        CallRingtoneBlockFeature.ID -> CallRingtoneBlockMiuixPage(context, provider, onBack)
        DisableHotUpdateFeature.ID -> DisableHotUpdateMiuixPage(context, provider, onBack)
        ProfileIdFeature.ID -> ProfileIdMiuixPage(context, provider, onBack)
        QuickContactEditFeature.ID -> QuickContactEditMiuixPage(context, provider, onBack)
        QuickGroupChatLabelFeature.ID -> QuickGroupChatLabelMiuixPage(context, provider, onBack)
        QuickMomentsFeature.ID -> QuickMomentsMiuixPage(context, provider, onBack)
        QuickTerminateFeature.ID -> QuickTerminateMiuixPage(context, provider, onBack)
        HideChatMenuFeature.ID -> HideChatMenuMiuixPage(context, provider, onBack)
        MessageAffixFeature.ID -> MessageAffixMiuixPage(context, provider, onBack)
        TypingReportBlockFeature.ID -> TypingReportBlockMiuixPage(context, provider, onBack)
        PatBlockFeature.ID -> PatBlockMiuixPage(context, provider, onBack)
        AutoOriginalImageFeature.ID -> AutoOriginalImageMiuixPage(context, provider, onBack)
        AutoViewOriginalFeature.ID -> AutoViewOriginalMiuixPage(context, provider, onBack)
        RemoveForwardLimitFeature.ID -> RemoveForwardLimitMiuixPage(context, provider, onBack)
        AtAllNotificationBlockFeature.ID -> AtAllNotificationBlockMiuixPage(context, provider, onBack)
        SettingsFeature.ID -> SettingsEntryMiuixPage(context, provider, onBack)
        FloatingShortcutFeature.ID -> FloatingShortcutMiuixPage(context, provider, onBack)
        MessageDetailsSettingsProvider.FEATURE_ID -> MessageDetailsConfigPage(
            context = context,
            sp = HchatStorage.preferences(context, HchatExtraSettings.PREFS_NAME),
            onBack = onBack
        )
        GroupMemberHistorySettingsProvider.FEATURE_ID -> HchatExtraToggleMiuixPage(
            context = context,
            provider = provider,
            key = HchatExtraSettings.KEY_GROUP_MEMBER_HISTORY,
            defaultValue = HchatExtraSettings.DEFAULT_GROUP_MEMBER_HISTORY,
            summary = "在群成员资料页添加历史发言记录入口",
            onBack = onBack
        )
        RedPacketDetailsSettingsProvider.FEATURE_ID -> HchatExtraToggleMiuixPage(
            context = context,
            provider = provider,
            key = HchatExtraSettings.KEY_RED_PACKET_DETAILS,
            defaultValue = HchatExtraSettings.DEFAULT_RED_PACKET_DETAILS,
            summary = "红包详情页显示金额、个数和领取时间",
            onBack = onBack
        )
        SkipWebRiskSettingsProvider.FEATURE_ID -> HchatExtraToggleMiuixPage(
            context = context,
            provider = provider,
            key = HchatExtraSettings.KEY_SKIP_WEB_RISK,
            defaultValue = HchatExtraSettings.DEFAULT_SKIP_WEB_RISK,
            summary = "跳过微信 WebView 高风险网页拦截提示",
            onBack = onBack
        )
        QuickMarkReadFeature.ID -> QuickMarkReadMiuixPage(context, provider, onBack)
        AutoReplyFeature.ID -> AutoReplyMiuixPage(context, provider, onBack)
        AutoMessageForwardFeature.ID -> AutoMessageForwardMiuixPage(context, provider, onBack)
        ConversationGroupFeature.ID -> ConversationGroupMiuixPage(context, provider, onBack)
        ConversationTabsFeature.ID -> ConversationTabsMiuixPage(context, onBack)
        CustomNotificationFeature.ID -> CustomNotificationMiuixPage(context, provider, onBack)
        KeywordNotificationFeature.ID -> KeywordNotificationMiuixPage(context, provider, onBack)
        TextSpeechFeature.ID -> TextSpeechMiuixPage(context, provider, onBack)
        ZombieCheckFeature.ID -> ZombieCheckMiuixPage(context, provider, onBack)
        QQMusicOrderFeature.ID -> QQMusicOrderMiuixPage(context, provider, onBack)
        WeChatKeepAliveFeature.ID -> WeChatKeepAliveMiuixPage(context, provider, onBack)
        QuoteDeleteClearFeature.ID -> QuoteDeleteClearMiuixPage(context, provider, onBack)
        EmojiSaveFeature.ID -> EmojiSaveMiuixPage(context, provider, onBack)
        SwipeQuoteFeature.ID -> SwipeQuoteMiuixPage(context, provider, onBack)
        AudioTransformFeature.ID -> AudioTransformSettingsPage(context, provider, onBack)
        TextVoiceFeature.ID -> TextVoiceMiuixPage(context, provider, onBack)
        FakeVoiceDurationFeature.ID -> FakeVoiceDurationMiuixPage(context, provider, onBack)
        VoicePreviewFeature.ID -> VoicePreviewMiuixPage(context, provider, onBack)
        FakeMiniProgramBaseLibFeature.ID -> FakeMiniProgramBaseLibMiuixPage(context, provider, onBack)
        SkipMiniProgramVideoAdsFeature.ID -> SkipMiniProgramVideoAdsMiuixPage(context, provider, onBack)
        SkipGlobalMiniProgramSplashAdsFeature.ID -> SkipGlobalMiniProgramSplashAdsMiuixPage(context, provider, onBack)
        FakeLocationFeature.ID -> FakeLocationMiuixPage(context, provider, onBack)
        GameEmojiFeature.ID -> GameEmojiMiuixPage(context, provider, onBack)
        MessageBubbleFeature.ID -> MessageBubbleMiuixPage(context, provider, onBack)
        MessageTextColorFeature.ID -> MessageTextColorMiuixPage(context, provider, onBack)
        HomeTextColorFeature.ID -> HomeTextColorMiuixPage(context, provider, onBack)
        ChatTimeStyleFeature.ID -> ChatTimeStyleMiuixPage(context, provider, onBack)
        InputHintFeature.ID -> InputHintMiuixPage(context, provider, onBack)
        LegacyChatInputFeature.ID -> LegacyChatInputMiuixPage(context, provider, onBack)
        LinkParseFeature.ID -> LinkParseMiuixPage(context, provider, onBack)
        HideChatAvatarFeature.ID -> HideChatAvatarMiuixPage(context, provider, onBack)
        CustomBottomBarFeature.ID -> CustomBottomBarMiuixPage(context, provider, onBack)
        FloatingBottomBarSettings.FEATURE_ID -> FloatingBottomBarMiuixPage(context, provider, onBack)
        RoundAvatarFeature.ID -> RoundAvatarMiuixPage(context, provider, onBack)
        CustomFriendAvatarFeature.ID -> CustomFriendAvatarMiuixPage(context, provider, onBack)
        MessageBlockFeature.ID -> MessageBlockMiuixPage(context, provider, onBack)
        GroupChatLabelFeature.ID -> GroupChatLabelMiuixPage(context, provider, onBack)
        GroupLeaveMonitorFeature.ID -> GroupLeaveMonitorMiuixPage(context, provider, onBack)
        GroupRenameMonitorFeature.ID -> GroupRenameMonitorMiuixPage(context, provider, onBack)
        MomentsAutoLikeFeature.ID -> MomentsAutoLikeMiuixPage(context, provider, onBack)
        MomentsAutoCommentFeature.ID -> MomentsAutoCommentPage(context, provider, onBack)
        MomentsAutoForwardFeature.ID -> MomentsAutoForwardMiuixPage(context, provider, onBack)
        MomentsAutoRefreshFeature.ID -> MomentsAutoRefreshMiuixPage(context, provider, onBack)
        MomentsContactFilterFeature.ID -> MomentsContactFilterMiuixPage(context, provider, onBack)
        MomentsKeywordBlockFeature.ID -> MomentsKeywordBlockMiuixPage(context, provider, onBack)
        MomentsBottomDetailFeature.ID -> MomentsBottomDetailMiuixPage(context, provider, onBack)
        MomentsPostNotificationFeature.ID -> MomentsPostNotificationMiuixPage(context, provider, onBack)
        OriginalMomentsUploadFeature.ID -> OriginalMomentsUploadMiuixPage(context, provider, onBack)
        MomentsUploadTailFeature.ID -> MomentsUploadTailMiuixPage(context, provider, onBack)
        SnsAntiRecallFeature.ID -> SnsAntiRecallMiuixPage(context, provider, onBack)
        MomentsFakeLikeSettingsProvider.ID,
        MomentsFakeCommentSettingsProvider.ID,
        MomentsFakeForwardSettingsProvider.ID -> MomentsFakeInteractionMiuixPage(context, provider, onBack)
        RemoveMomentsAdsFeature.ID -> RemoveMomentsAdsMiuixPage(context, provider, onBack)
        EditMessageFeature.ID -> EditMessageMiuixPage(context, provider, onBack)
        VoiceForwardFeature.ID -> VoiceForwardMiuixPage(context, provider, onBack)
        ScheduledTaskFeature.ID -> ScheduledTaskMiuixPage(context, provider, onBack)
        FakeScanCameraFeature.ID -> FakeScanCameraMiuixPage(context, provider, onBack)
        FinderMediaDownloadFeature.ID -> FinderMediaDownloadMiuixPage(context, provider, onBack)
        ProtobufPacketFeature.ID -> ProtobufPacketMiuixPage(context, provider, onBack)
        CrashReportSettingsProvider.ID -> CrashReportMiuixPage(context, provider, onBack)
        StatusTextLimitFeature.ID -> StatusTextLimitMiuixPage(context, provider, onBack)
        ScriptPluginFeature.ID -> ScriptPluginMiuixPage(
            context,
            provider,
            onBack,
            onOpenScriptPluginMarket,
            onOpenScriptPluginManager
        )
        WeChatTabletFeature.ID -> WeChatTabletMiuixPage(context, provider, onBack)
        else -> UnsupportedFeaturePage(provider, onBack)
    }
}

@Composable
internal fun MultiRecallMiuixPage(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit
) {
    val sp = remember { HchatStorage.preferences(context, MultiRecallSettings.PREFS_NAME) }
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()

    PageScaffold(
        title = provider.title(),
        largeTitle = provider.title(),
        scrollBehavior = scrollBehavior,
        bottomBar = {
            BottomActionBar("返回", onBack)
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "聊天消息") }
            item {
                SettingsCard {
                    SwitchRow(
                        sp,
                        MultiRecallSettings.KEY_ENABLE,
                        "多选撤回",
                        "在多选消息的分享菜单中显示批量撤回",
                        MultiRecallSettings.DEFAULT_ENABLE
                    )
                }
            }
        }
    }
}

@Composable
internal fun SelectedMessagesMiuixPage(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit
) {
    val sp = remember { HchatStorage.preferences(context, SelectedMessagesSettings.PREFS_NAME) }
    var route by remember { mutableStateOf<SelectedMessagesRoute>(SelectedMessagesRoute.Main) }
    var draft by remember {
        mutableStateOf(
            ScheduledTaskSettings.newDraft().copy(
                type = ScheduledTaskSettings.TYPE_IMAGE,
                items = listOf(ScheduledTaskContentItem(ScheduledTaskSettings.TYPE_IMAGE, "")),
                intervalSeconds = SelectedMessagesSettings.sendIntervalSeconds(context),
                mediaIntervalSeconds = SelectedMessagesSettings.sendIntervalSeconds(context)
            )
        )
    }
    var channel by remember { mutableStateOf(SelectedMessagesRuntimeCoordinator.CHANNEL_MODULE) }
    val mainListState = rememberLazyListState()
    val editorListState = rememberLazyListState()

    fun validationError(): String? {
        return SelectedMessagesRuntimeCoordinator.validationError(
            channel,
            scheduledTaskEditableItems(draft)
        )
    }

    fun openContactPicker() {
        validationError()?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            return
        }
        route = SelectedMessagesRoute.ContactPicker
    }

    fun startSend() {
        val error = validationError() ?: if (draft.targetIds.isEmpty()) "请选择群发对象" else null
        if (error != null) {
            Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
            return
        }
        val activity = context as? Activity
        if (activity == null) {
            Toast.makeText(context, "当前页面无法启动群发", Toast.LENGTH_SHORT).show()
            return
        }
        SelectedMessagesRuntimeCoordinator.sendCustom(
            activity = activity,
            channel = channel,
            items = scheduledTaskEditableItems(draft),
            targetIds = draft.targetIds,
            targetIntervalSeconds = draft.intervalSeconds,
            itemIntervalSeconds = draft.mediaIntervalSeconds
        )
    }

    SettingsRouteTransition(
        targetState = route,
        label = "SelectedMessagesRoute",
        depthOf = { it.depth() }
    ) { currentRoute ->
        when (currentRoute) {
            SelectedMessagesRoute.Main -> {
                SelectedMessagesMainPage(
                    provider = provider,
                    sp = sp,
                    listState = mainListState,
                    onBack = onBack,
                    onCompose = { route = SelectedMessagesRoute.Editor }
                )
            }
            SelectedMessagesRoute.Editor -> {
                SelectedMessagesCustomEditorPage(
                    draft = draft,
                    channel = channel,
                    listState = editorListState,
                    onBack = { route = SelectedMessagesRoute.Main },
                    onDraftChange = { draft = it },
                    onChannelChange = { next ->
                        if (next != channel) {
                            channel = next
                            draft = draft.copy(targetIds = emptyList())
                        }
                    },
                    onPickTargets = ::openContactPicker,
                    onPickFiles = { itemIndex, itemType ->
                        val activity = context as? Activity
                        if (activity == null) {
                            Toast.makeText(context, "当前页面无法打开文件选择器", Toast.LENGTH_SHORT).show()
                        } else {
                            ScheduledTaskFilePickerBridge.launch(activity, itemType) { paths ->
                                draft = updateScheduledTaskMediaItem(draft, itemIndex, itemType, paths)
                            }
                        }
                    },
                    onPickFavorite = { itemIndex ->
                        scheduledTaskEditableItems(draft).getOrNull(itemIndex)?.let { item ->
                            route = SelectedMessagesRoute.FavoritePicker(
                                FavoritePickerRequest(
                                    title = scheduledTaskPickerTitle(item.type),
                                    existingValue = item.value,
                                    onValue = { value ->
                                        draft = updateScheduledTaskContentItemValue(draft, itemIndex, value)
                                    },
                                    multiSelect = false,
                                    delimiter = "|"
                                )
                            )
                        }
                    },
                    onSend = ::startSend
                )
            }
            SelectedMessagesRoute.ContactPicker -> {
                val official = channel == SelectedMessagesRuntimeCoordinator.CHANNEL_OFFICIAL
                ContactPickerPage(
                    context = context,
                    request = ContactPickerRequest(
                        title = if (official) "选择官方群发好友" else "选择群发对象",
                        mode = if (official) ContactPickerMode.FRIENDS else ContactPickerMode.ALL_CHATS,
                        multiSelect = true,
                        existingValue = formatIds(draft.targetIds),
                        onValue = {},
                        enableLabels = true
                    ),
                    onBack = { route = SelectedMessagesRoute.Editor },
                    onConfirm = { selected ->
                        draft = draft.copy(targetIds = selected.map { it.id })
                        route = SelectedMessagesRoute.Editor
                    }
                )
            }
            is SelectedMessagesRoute.FavoritePicker -> {
                FavoritePickerPage(
                    request = currentRoute.request,
                    onBack = { route = SelectedMessagesRoute.Editor }
                )
            }
        }
    }
}

@Composable
internal fun SelectedMessagesMainPage(
    provider: FeatureSettingsProvider,
    sp: SharedPreferences,
    listState: LazyListState,
    onBack: () -> Unit,
    onCompose: () -> Unit
) {
    val scrollBehavior = MiuixScrollBehavior()
    var officialIntervalMinutes by remember {
        mutableStateOf(
            sp.getInt(
                SelectedMessagesSettings.KEY_OFFICIAL_INTERVAL_MINUTES,
                SelectedMessagesSettings.DEFAULT_OFFICIAL_INTERVAL_MINUTES
            ).coerceIn(0, SelectedMessagesSettings.MAX_OFFICIAL_INTERVAL_MINUTES).toString()
        )
    }
    var sendIntervalSeconds by remember {
        mutableStateOf(
            sp.getInt(
                SelectedMessagesSettings.KEY_SEND_INTERVAL_SECONDS,
                SelectedMessagesSettings.DEFAULT_SEND_INTERVAL_SECONDS
            ).coerceIn(0, SelectedMessagesSettings.MAX_SEND_INTERVAL_SECONDS).toString()
        )
    }
    PageScaffold(
        title = provider.title(),
        largeTitle = provider.title(),
        scrollBehavior = scrollBehavior,
        bottomBar = { BottomActionBar("返回", onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "多选消息") }
            item {
                SettingsCard {
                    SwitchRow(
                        sp,
                        SelectedMessagesSettings.KEY_ENABLE,
                        "群发助手",
                        "在多选消息菜单中显示群发助手[H]和定时转发[H]",
                        SelectedMessagesSettings.DEFAULT_ENABLE
                    )
                    SwitchRow(
                        sp,
                        SelectedMessagesSettings.KEY_BACKGROUND_SILENT_SEND,
                        "后台静默发送",
                        "群发时不显示发送进度窗口",
                        SelectedMessagesSettings.DEFAULT_BACKGROUND_SILENT_SEND
                    )
                }
            }
            item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "发送节奏") }
            item {
                SettingsCard {
                    NumberInputRow(
                        "群发助手间隔延迟",
                        "单位分钟，多个微信原生群发批次之间的等待时间",
                        officialIntervalMinutes
                    ) { value ->
                        officialIntervalMinutes = value
                        value.toIntOrNull()?.let { parsed ->
                            sp.edit().putInt(
                                SelectedMessagesSettings.KEY_OFFICIAL_INTERVAL_MINUTES,
                                parsed.coerceIn(0, SelectedMessagesSettings.MAX_OFFICIAL_INTERVAL_MINUTES)
                            ).apply()
                        }
                    }
                    InsetDivider()
                    NumberInputRow(
                        "群发间隔延迟",
                        "单位秒，群发多条内容或多个目标之间的等待时间",
                        sendIntervalSeconds
                    ) { value ->
                        sendIntervalSeconds = value
                        value.toIntOrNull()?.let { parsed ->
                            sp.edit().putInt(
                                SelectedMessagesSettings.KEY_SEND_INTERVAL_SECONDS,
                                parsed.coerceIn(0, SelectedMessagesSettings.MAX_SEND_INTERVAL_SECONDS)
                            ).apply()
                        }
                    }
                }
            }
            item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "内容群发") }
            item {
                SettingsCard {
                    ActionRow("自定义群发", "编辑内容并选择模块或微信原生通道") {
                        onCompose()
                    }
                }
            }
        }
    }
}

@Composable
internal fun SelectedMessagesCustomEditorPage(
    draft: ScheduledTaskItem,
    channel: Int,
    listState: LazyListState,
    onBack: () -> Unit,
    onDraftChange: (ScheduledTaskItem) -> Unit,
    onChannelChange: (Int) -> Unit,
    onPickTargets: () -> Unit,
    onPickFiles: (Int, Int) -> Unit,
    onPickFavorite: (Int) -> Unit,
    onSend: () -> Unit
) {
    val scrollBehavior = MiuixScrollBehavior()
    val official = channel == SelectedMessagesRuntimeCoordinator.CHANNEL_OFFICIAL
    val items = scheduledTaskEditableItems(draft)
    PageScaffold(
        title = "自定义群发",
        largeTitle = "自定义群发",
        scrollBehavior = scrollBehavior,
        bottomBar = {
            BottomActionBar(
                primaryText = "开始群发",
                onPrimaryClick = onSend,
                secondaryText = "返回",
                onSecondaryClick = onBack
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "发送目标") }
            item {
                SettingsCard {
                    PopupChoiceRow(
                        title = "发送通道",
                        summary = selectedMessagesChannelLabel(channel),
                        options = selectedMessagesChannelChoices(),
                        currentValue = channel.toString(),
                        onValueChanged = { value ->
                            onChannelChange(
                                value.toIntOrNull()
                                    ?: SelectedMessagesRuntimeCoordinator.CHANNEL_MODULE
                            )
                        }
                    )
                    InsetDivider()
                    ActionRow(
                        if (official) "选择好友" else "选择聊天",
                        scheduledTaskTargetSummary(draft.targetIds)
                    ) {
                        onPickTargets()
                    }
                }
            }
            item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "发送内容") }
            item {
                SettingsCard {
                    ScheduledTaskChatContentRows(
                        task = draft,
                        onTaskChange = onDraftChange,
                        onPickFiles = onPickFiles,
                        onPickFavorite = onPickFavorite,
                        massSendOnly = official
                    )
                }
            }
            if (!official) {
                item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "发送节奏") }
                item {
                    SettingsCard {
                        NumberInputRow(
                            "聊天间隔",
                            "单位秒，多个聊天之间的等待时间",
                            draft.intervalSeconds.toString()
                        ) {
                            onDraftChange(
                                draft.copy(intervalSeconds = it.toIntOrNull()?.coerceIn(0, 3600) ?: 0)
                            )
                        }
                        if (items.size > 1) {
                            InsetDivider()
                            NumberInputRow(
                                scheduledTaskItemIntervalTitle(),
                                "单位秒，同一聊天连续发送的等待时间",
                                draft.mediaIntervalSeconds.toString()
                            ) {
                                onDraftChange(
                                    draft.copy(
                                        mediaIntervalSeconds = it.toIntOrNull()?.coerceIn(0, 3600) ?: 0
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun selectedMessagesChannelChoices(): List<PopupChoice<String>> = listOf(
    PopupChoice("模块通道", SelectedMessagesRuntimeCoordinator.CHANNEL_MODULE.toString()),
    PopupChoice("微信原生群发助手", SelectedMessagesRuntimeCoordinator.CHANNEL_OFFICIAL.toString())
)

internal fun selectedMessagesChannelLabel(channel: Int): String {
    return if (channel == SelectedMessagesRuntimeCoordinator.CHANNEL_OFFICIAL) {
        "微信原生群发助手"
    } else {
        "模块通道"
    }
}

@Composable
internal fun MessageForwardMiuixPage(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit
) {
    val sp = remember { HchatStorage.preferences(context, MessageForwardSettings.PREFS_NAME) }
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()
    PageScaffold(
        title = provider.title(),
        largeTitle = provider.title(),
        scrollBehavior = scrollBehavior,
        bottomBar = { BottomActionBar("返回", onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "聊天消息") }
            item {
                SettingsCard {
                    SwitchRow(
                        sp,
                        MessageForwardSettings.KEY_ENABLE,
                        "转发菜单",
                        "在消息长按菜单中显示转发[H]",
                        MessageForwardSettings.DEFAULT_ENABLE
                    )
                    InsetDivider()
                    SwitchRow(
                        sp,
                        MessageForwardSettings.KEY_MULTI_MOMENTS_ENABLE,
                        "多选转发到朋友圈",
                        "在多选消息菜单中显示转发到朋友圈[H]",
                        MessageForwardSettings.DEFAULT_ENABLE
                    )
                }
            }
            item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "收藏") }
            item {
                SettingsCard {
                    SwitchRow(
                        sp,
                        MessageForwardSettings.KEY_FAVORITE_FORWARD_ENABLE,
                        "转发收藏",
                        "长按收藏后显示转发[H]",
                        MessageForwardSettings.DEFAULT_ENABLE
                    )
                }
            }
            item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "朋友圈") }
            item {
                SettingsCard {
                    SwitchRow(
                        sp,
                        MessageForwardSettings.KEY_SNS_FORWARD_ENABLE,
                        "朋友圈转发",
                        "在发现页或好友个人主页长按朋友圈后显示转发[H]",
                        MessageForwardSettings.DEFAULT_ENABLE
                    )
                }
            }
        }
    }
}

@Composable
internal fun CallMediaLimitMiuixPage(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit
) {
    val sp = remember { HchatStorage.preferences(context, CallMediaLimitSettings.PREFS_NAME) }
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()

    PageScaffold(
        title = provider.title(),
        largeTitle = provider.title(),
        scrollBehavior = scrollBehavior,
        bottomBar = {
            BottomActionBar("返回", onBack)
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "音视频通话") }
            item {
                SettingsCard {
                    SwitchRow(
                        sp,
                        CallMediaLimitSettings.KEY_ENABLE,
                        "移除通话媒体限制",
                        "通话时允许播放语音和视频，并打开聊天拍摄",
                        CallMediaLimitSettings.DEFAULT_ENABLE
                    )
                }
            }
        }
    }
}

@Composable
internal fun CallRingtoneBlockMiuixPage(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit
) {
    val sp = remember { HchatStorage.preferences(context, CallMediaLimitSettings.PREFS_NAME) }
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()

    PageScaffold(
        title = provider.title(),
        largeTitle = provider.title(),
        scrollBehavior = scrollBehavior,
        bottomBar = { BottomActionBar("返回", onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "通话铃声") }
            item {
                SettingsCard {
                    SwitchRow(
                        sp,
                        CallMediaLimitSettings.KEY_BLOCK_INCOMING_RINGTONE,
                        "屏蔽通话呼入铃声",
                        "收到微信语音或视频通话时不播放来电铃声",
                        CallMediaLimitSettings.DEFAULT_BLOCK_INCOMING_RINGTONE
                    )
                    InsetDivider()
                    SwitchRow(
                        sp,
                        CallMediaLimitSettings.KEY_BLOCK_OUTGOING_RINGTONE,
                        "屏蔽通话呼出铃声",
                        "拨打微信语音或视频通话时不播放等待铃声",
                        CallMediaLimitSettings.DEFAULT_BLOCK_OUTGOING_RINGTONE
                    )
                }
            }
        }
    }
}

@Composable
internal fun StatusTextLimitMiuixPage(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit
) {
    val sp = remember { HchatStorage.preferences(context, StatusTextLimitSettings.PREFS_NAME) }
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()

    PageScaffold(
        title = provider.title(),
        largeTitle = provider.title(),
        scrollBehavior = scrollBehavior,
        bottomBar = {
            BottomActionBar("返回", onBack)
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "个人状态") }
            item {
                SettingsCard {
                    SwitchRow(
                        sp,
                        StatusTextLimitSettings.KEY_ENABLE,
                        "解除状态词长度限制",
                        "开启后个人状态词可超过微信默认 10 字限制",
                        StatusTextLimitSettings.DEFAULT_ENABLE
                    )
                }
            }
        }
    }
}

@Composable
internal fun EditMessageMiuixPage(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit
) {
    val sp = remember { HchatStorage.preferences(context, EditMessageSettings.PREFS_NAME) }
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()

    PageScaffold(
        title = provider.title(),
        largeTitle = provider.title(),
        scrollBehavior = scrollBehavior,
        bottomBar = {
            BottomActionBar("返回", onBack)
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "聊天记录") }
            item {
                SettingsCard {
                    SwitchRow(
                        sp,
                        EditMessageSettings.KEY_ENABLE,
                        "修改聊天记录",
                        "长按文字、引用或转账消息后可修改本地记录",
                        EditMessageSettings.DEFAULT_ENABLE
                    )
                }
            }
        }
    }
}

@Composable
internal fun QuickContactEditMiuixPage(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit
) {
    val sp = remember { HchatStorage.preferences(context, QuickContactEditSettings.PREFS_NAME) }
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()

    PageScaffold(
        title = provider.title(),
        largeTitle = provider.title(),
        scrollBehavior = scrollBehavior,
        bottomBar = { BottomActionBar("返回", onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "会话列表") }
            item {
                SettingsCard {
                    SwitchRow(
                        sp,
                        QuickContactEditSettings.KEY_ENABLE,
                        "快捷设置备注和标签",
                        "长按好友、群聊会话或朋友圈头像时显示设置入口，聊天分组内同样生效",
                        QuickContactEditSettings.DEFAULT_ENABLE
                    )
                }
            }
        }
    }
}

@Composable
internal fun QuickGroupChatLabelMiuixPage(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit
) {
    val sp = remember { HchatStorage.preferences(context, QuickGroupChatLabelSettings.PREFS_NAME) }
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()

    PageScaffold(
        title = provider.title(),
        largeTitle = provider.title(),
        scrollBehavior = scrollBehavior,
        bottomBar = { BottomActionBar("返回", onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "会话列表") }
            item {
                SettingsCard {
                    SwitchRow(
                        sp,
                        QuickGroupChatLabelSettings.KEY_ENABLE,
                        "快捷设置群聊标签",
                        "长按群聊会话时显示群聊标签入口，聊天分组内同样生效",
                        QuickGroupChatLabelSettings.DEFAULT_ENABLE
                    )
                }
            }
        }
    }
}

@Composable
internal fun QuickMomentsMiuixPage(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit
) {
    val sp = remember { HchatStorage.preferences(context, QuickMomentsSettings.PREFS_NAME) }
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()

    PageScaffold(
        title = provider.title(),
        largeTitle = provider.title(),
        scrollBehavior = scrollBehavior,
        bottomBar = { BottomActionBar("返回", onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "会话列表") }
            item {
                SettingsCard {
                    SwitchRow(
                        sp,
                        QuickMomentsSettings.KEY_ENABLE,
                        "快捷查看朋友圈",
                        "长按本人、好友或企业微信联系人会话时显示朋友圈入口，聊天分组内同样生效",
                        QuickMomentsSettings.DEFAULT_ENABLE
                    )
                }
            }
        }
    }
}

@Composable
internal fun QuickTerminateMiuixPage(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit
) {
    val sp = remember { HchatStorage.preferences(context, QuickTerminateSettings.PREFS_NAME) }
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()

    PageScaffold(
        title = provider.title(),
        largeTitle = provider.title(),
        scrollBehavior = scrollBehavior,
        bottomBar = { BottomActionBar("返回", onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "加号菜单") }
            item {
                SettingsCard {
                    SwitchRow(
                        sp,
                        QuickTerminateSettings.KEY_ENABLE,
                        "快捷终止",
                        "在微信右上角加号菜单中添加快捷终止，重启微信后生效",
                        QuickTerminateSettings.DEFAULT_ENABLE
                    )
                }
            }
        }
    }
}

@Composable
internal fun TypingReportBlockMiuixPage(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit
) {
    val sp = remember { HchatStorage.preferences(context, TypingReportBlockSettings.PREFS_NAME) }
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()

    PageScaffold(
        title = provider.title(),
        largeTitle = provider.title(),
        scrollBehavior = scrollBehavior,
        bottomBar = {
            BottomActionBar("返回", onBack)
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "输入状态") }
            item {
                SettingsCard {
                    SwitchRow(
                        sp,
                        TypingReportBlockSettings.KEY_ENABLE,
                        "拦截正在输入上报",
                        "输入文字时不向对方显示正在输入状态",
                        TypingReportBlockSettings.DEFAULT_ENABLE
                    )
                }
            }
        }
    }
}

@Composable
internal fun MessageAffixMiuixPage(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit
) {
    val sp = remember { MessageAffixSettings.preferences(context) }
    var enabled by remember {
        mutableStateOf(sp.getBoolean(MessageAffixSettings.KEY_ENABLE, MessageAffixSettings.DEFAULT_ENABLE))
    }
    var textFormat by remember {
        mutableStateOf(MessageAffixSettings.textFormat(sp))
    }
    var timeFormat by remember {
        mutableStateOf(
            sp.getString(
                MessageAffixSettings.KEY_TIME_FORMAT,
                MessageAffixSettings.DEFAULT_TIME_FORMAT
            ).orEmpty()
        )
    }
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()

    PageScaffold(
        title = provider.title(),
        largeTitle = provider.title(),
        scrollBehavior = scrollBehavior,
        bottomBar = {
            BottomActionBar(
                primaryText = "保存",
                onPrimaryClick = {
                    when {
                        enabled && MessageAffixSettings.originalTextVariableCount(textFormat) != 1 -> {
                            Toast.makeText(
                                context,
                                "文本格式必须且只能包含一个原消息变量",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        enabled && !MessageAffixSettings.isValidTimeFormat(timeFormat) -> {
                            Toast.makeText(context, "时间格式无效", Toast.LENGTH_SHORT).show()
                        }
                        else -> {
                            textFormat = MessageAffixSettings.normalizeTextFormat(textFormat)
                            timeFormat = MessageAffixSettings.normalizeTimeFormat(timeFormat)
                            sp.edit()
                                .putBoolean(MessageAffixSettings.KEY_ENABLE, enabled)
                                .putString(MessageAffixSettings.KEY_TEXT_FORMAT, textFormat)
                                .putString(MessageAffixSettings.KEY_TIME_FORMAT, timeFormat)
                                .apply()
                            Toast.makeText(context, "设置已保存", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                secondaryText = "重置",
                onSecondaryClick = {
                    textFormat = MessageAffixSettings.DEFAULT_TEXT_FORMAT
                    timeFormat = MessageAffixSettings.DEFAULT_TIME_FORMAT
                },
                middleText = "取消",
                onMiddleClick = onBack
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "聊天消息") }
            item {
                SettingsCard {
                    SwitchRow(
                        checked = enabled,
                        title = "发送文本格式",
                        summary = "将聊天发送的文字按自定义格式处理"
                    ) {
                        enabled = it
                    }
                    if (enabled) {
                        InsetDivider()
                        VariableInputRow(
                            title = "文本格式",
                            summary = "点击下方中文变量插入到光标位置",
                            value = textFormat,
                            variables = messageAffixTemplateVariables,
                            minLines = 2
                        ) {
                            textFormat = it
                        }
                        InsetDivider()
                        InputRow("时间格式", "例如 HH:mm:ss 或 yyyy-MM-dd HH:mm:ss", timeFormat) {
                            timeFormat = it
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun InputHintMiuixPage(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit
) {
    val sp = remember { InputHintSettings.preferences(context) }
    var enabled by remember {
        mutableStateOf(sp.getBoolean(InputHintSettings.KEY_ENABLE, InputHintSettings.DEFAULT_ENABLE))
    }
    var statisticsEnabled by remember {
        mutableStateOf(
            sp.getBoolean(
                InputHintSettings.KEY_STATISTICS_ENABLE,
                InputHintSettings.DEFAULT_STATISTICS_ENABLE
            )
        )
    }
    var template by remember { mutableStateOf(InputHintSettings.template(sp)) }
    var stats by remember { mutableStateOf(InputHintStats()) }
    DisposableEffect(statisticsEnabled) {
        val subscription = if (statisticsEnabled) {
            stats = OutgoingMessageStatsRepository.current()
            OutgoingMessageStatsRepository.subscribe {
                stats = OutgoingMessageStatsRepository.current()
            }
        } else {
            stats = InputHintStats()
            null
        }
        onDispose { subscription?.unsubscribe() }
    }
    val preview = InputHintSettings.renderTemplate(template, stats)
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()

    PageScaffold(
        title = provider.title(),
        largeTitle = provider.title(),
        scrollBehavior = scrollBehavior,
        bottomBar = {
            BottomActionBar(
                primaryText = "保存",
                onPrimaryClick = {
                    template = InputHintSettings.normalizeTemplate(template)
                    sp.edit()
                        .putBoolean(InputHintSettings.KEY_ENABLE, enabled)
                        .putBoolean(InputHintSettings.KEY_STATISTICS_ENABLE, statisticsEnabled)
                        .putString(InputHintSettings.KEY_TEMPLATE, template)
                        .apply()
                    Toast.makeText(context, "设置已保存", Toast.LENGTH_SHORT).show()
                },
                secondaryText = "重置",
                onSecondaryClick = {
                    enabled = InputHintSettings.DEFAULT_ENABLE
                    statisticsEnabled = InputHintSettings.DEFAULT_STATISTICS_ENABLE
                    template = InputHintSettings.DEFAULT_TEMPLATE
                },
                middleText = "取消",
                onMiddleClick = onBack
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "聊天输入框") }
            item {
                SettingsCard {
                    SwitchRow(
                        checked = enabled,
                        title = "输入框提示",
                        summary = "自定义聊天输入框没有文字时显示的内容"
                    ) {
                        enabled = it
                    }
                    if (enabled) {
                        InsetDivider()
                        SwitchRow(
                            checked = statisticsEnabled,
                            title = "统计今日发送数量",
                            summary = "统计今天自己实际发送的消息"
                        ) {
                            statisticsEnabled = it
                        }
                        InsetDivider()
                        VariableInputRow(
                            title = "提示文本",
                            summary = if (statisticsEnabled) {
                                "点击下方中文变量插入到光标位置"
                            } else {
                                "输入自定义提示内容"
                            },
                            value = template,
                            variables = if (statisticsEnabled) inputHintTemplateVariables else emptyList(),
                            minLines = 2
                        ) {
                            template = it
                        }
                        InsetDivider()
                        InfoRow("当前提示", preview)
                    }
                }
            }
        }
    }
}

@Composable
internal fun LegacyChatInputMiuixPage(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit
) {
    val sp = remember { LegacyChatInputSettings.preferences(context) }
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()

    PageScaffold(
        title = provider.title(),
        largeTitle = provider.title(),
        scrollBehavior = scrollBehavior,
        bottomBar = { BottomActionBar("返回", onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "聊天输入区") }
            item {
                SettingsCard {
                    SwitchRow(
                        sp,
                        LegacyChatInputSettings.KEY_ENABLE,
                        provider.title(),
                        "使用微信 8.0.80（3220）的原生旧版输入栏配置。此项独立于输入框提示；切换后重新打开会话以重建输入区。",
                        LegacyChatInputSettings.DEFAULT_ENABLE
                    )
                }
            }
        }
    }
}

@Composable
internal fun PatBlockMiuixPage(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit
) {
    val sp = remember { HchatStorage.preferences(context, PatBlockSettings.PREFS_NAME) }
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()

    PageScaffold(
        title = provider.title(),
        largeTitle = provider.title(),
        scrollBehavior = scrollBehavior,
        bottomBar = { BottomActionBar("返回", onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "聊天头像") }
            item {
                SettingsCard {
                    SwitchRow(
                        sp,
                        PatBlockSettings.KEY_ENABLE,
                        "禁止拍一拍",
                        "双击聊天头像时不发送拍一拍",
                        PatBlockSettings.DEFAULT_ENABLE
                    )
                }
            }
        }
    }
}

@Composable
internal fun AutoOriginalImageMiuixPage(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit
) {
    val sp = remember { HchatStorage.preferences(context, AutoOriginalImageSettings.PREFS_NAME) }
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()

    PageScaffold(
        title = provider.title(),
        largeTitle = provider.title(),
        scrollBehavior = scrollBehavior,
        bottomBar = { BottomActionBar("返回", onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "聊天图片") }
            item {
                SettingsCard {
                    SwitchRow(
                        sp,
                        AutoOriginalImageSettings.KEY_ENABLE,
                        "自动勾选原图",
                        "进入聊天图片发送界面时自动选择原图",
                        AutoOriginalImageSettings.DEFAULT_ENABLE
                    )
                }
            }
        }
    }
}

@Composable
internal fun AutoViewOriginalMiuixPage(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit
) {
    val sp = remember { HchatStorage.preferences(context, AutoViewOriginalSettings.PREFS_NAME) }
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()

    PageScaffold(
        title = provider.title(),
        largeTitle = provider.title(),
        scrollBehavior = scrollBehavior,
        bottomBar = { BottomActionBar("返回", onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "聊天媒体") }
            item {
                SettingsCard {
                    SwitchRow(
                        sp,
                        AutoViewOriginalSettings.KEY_ENABLE,
                        "自动查看原图",
                        "打开聊天图片或视频时自动查看原图或原视频",
                        AutoViewOriginalSettings.DEFAULT_ENABLE
                    )
                }
            }
        }
    }
}

@Composable
internal fun RemoveForwardLimitMiuixPage(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit
) {
    val sp = remember { HchatStorage.preferences(context, RemoveForwardLimitSettings.PREFS_NAME) }
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()

    PageScaffold(
        title = provider.title(),
        largeTitle = provider.title(),
        scrollBehavior = scrollBehavior,
        bottomBar = { BottomActionBar("返回", onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "消息转发") }
            item {
                SettingsCard {
                    SwitchRow(
                        sp,
                        RemoveForwardLimitSettings.KEY_ENABLE,
                        "移除转发限制",
                        "允许微信原生转发选择超过 9 个会话；大量目标仍受微信发送能力限制",
                        RemoveForwardLimitSettings.DEFAULT_ENABLE
                    )
                }
            }
        }
    }
}

@Composable
internal fun AtAllNotificationBlockMiuixPage(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit
) {
    val sp = remember { HchatStorage.preferences(context, AtAllNotificationBlockSettings.PREFS_NAME) }
    var enabled by remember {
        mutableStateOf(sp.getBoolean(AtAllNotificationBlockSettings.KEY_ENABLE, AtAllNotificationBlockSettings.DEFAULT_ENABLE))
    }
    var selectedGroups by remember {
        mutableStateOf(AtAllNotificationBlockSettings.parseGroups(sp.getString(AtAllNotificationBlockSettings.KEY_GROUPS, "")))
    }
    var legacyAllGroups by remember { mutableStateOf(enabled && !sp.contains(AtAllNotificationBlockSettings.KEY_GROUPS)) }
    var route by remember { mutableStateOf<AtAllNotificationBlockRoute>(AtAllNotificationBlockRoute.Main) }
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()

    SettingsRouteTransition(
        targetState = route,
        label = "AtAllNotificationBlockRoute",
        depthOf = { if (it is AtAllNotificationBlockRoute.Main) 0 else 1 }
    ) { current ->
        when (current) {
            AtAllNotificationBlockRoute.Main -> PageScaffold(
                title = provider.title(),
                largeTitle = provider.title(),
                scrollBehavior = scrollBehavior,
                bottomBar = { BottomActionBar("返回", onBack) }
            ) { padding ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
                    state = listState,
                    contentPadding = PaddingValues(
                        top = padding.calculateTopPadding() + 8.dp,
                        bottom = padding.calculateBottomPadding() + 84.dp
                    )
                ) {
                    item { SmallTitle(text = "群聊通知") }
                    item {
                        SettingsCard {
                            SwitchRow(
                                checked = enabled,
                                title = "屏蔽艾特所有人",
                                summary = "仅拦截所选群聊的艾特所有人通知",
                                onCheckedChange = {
                                    enabled = it
                                    val editor = sp.edit().putBoolean(AtAllNotificationBlockSettings.KEY_ENABLE, it)
                                    if (it && !sp.contains(AtAllNotificationBlockSettings.KEY_GROUPS)) {
                                        editor.putString(AtAllNotificationBlockSettings.KEY_GROUPS, "")
                                        legacyAllGroups = false
                                    }
                                    editor.apply()
                                }
                            )
                            if (enabled) {
                                InsetDivider()
                                ActionRow(
                                    "选择屏蔽群聊",
                                    when {
                                        legacyAllGroups -> "当前屏蔽全部群聊，重新选择后按选择生效"
                                        selectedGroups.isEmpty() -> "未选择群聊，不会屏蔽通知"
                                        else -> "已选择 ${selectedGroups.size} 个群聊"
                                    }
                                ) {
                                    route = AtAllNotificationBlockRoute.GroupPicker(
                                        ContactPickerRequest(
                                            title = "选择屏蔽群聊",
                                            mode = ContactPickerMode.GROUPS,
                                            multiSelect = true,
                                            existingValue = formatIds(selectedGroups),
                                            onValue = {}
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
            is AtAllNotificationBlockRoute.GroupPicker -> ContactPickerPage(
                context = context,
                request = current.request,
                onBack = { route = AtAllNotificationBlockRoute.Main },
                onConfirm = { picked ->
                    selectedGroups = picked.map { it.id }.filter { it.isNotBlank() }.toSet()
                    legacyAllGroups = false
                    sp.edit()
                        .putString(AtAllNotificationBlockSettings.KEY_GROUPS, formatIds(selectedGroups))
                        .apply()
                    route = AtAllNotificationBlockRoute.Main
                }
            )
        }
    }
}

@Composable
internal fun DisableHotUpdateMiuixPage(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit
) {
    val sp = remember { HchatStorage.preferences(context, DisableHotUpdateSettings.PREFS_NAME) }
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()

    PageScaffold(
        title = provider.title(),
        largeTitle = provider.title(),
        scrollBehavior = scrollBehavior,
        bottomBar = {
            BottomActionBar("返回", onBack)
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "热更新") }
            item {
                SettingsCard {
                    SwitchRow(
                        sp,
                        DisableHotUpdateSettings.KEY_ENABLE,
                        "屏蔽热更新",
                        "阻止微信加载和应用热更新补丁",
                        DisableHotUpdateSettings.DEFAULT_ENABLE
                    )
                }
            }
        }
    }
}

@Composable
internal fun MomentsAutoLikeMiuixPage(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit
) {
    val sp = remember { HchatStorage.preferences(context, MomentsAutoLikeSettings.PREFS_NAME) }
    var route by remember { mutableStateOf<MomentsAutoLikeRoute>(MomentsAutoLikeRoute.Main) }
    var whitelist by remember { mutableStateOf(parseIds(sp.getString(MomentsAutoLikeSettings.KEY_WHITELIST, "").orEmpty())) }
    var blacklist by remember { mutableStateOf(parseIds(sp.getString(MomentsAutoLikeSettings.KEY_BLACKLIST, "").orEmpty())) }

    SettingsRouteTransition(
        targetState = route,
        label = "MomentsAutoLikeRoute",
        depthOf = { if (it is MomentsAutoLikeRoute.Main) 0 else 1 }
    ) { current ->
        when (current) {
            MomentsAutoLikeRoute.Main -> MomentsAutoLikeMainPage(
                provider = provider,
                sp = sp,
                whitelist = whitelist,
                blacklist = blacklist,
                onBack = onBack,
                onPickTargets = { listMode ->
                    val selected = if (listMode == MomentsAutoLikeSettings.LIST_BLACKLIST) blacklist else whitelist
                    route = MomentsAutoLikeRoute.ContactPicker(
                        listMode,
                        ContactPickerRequest(
                            title = if (listMode == MomentsAutoLikeSettings.LIST_BLACKLIST) "选择点赞黑名单" else "选择点赞白名单",
                            mode = ContactPickerMode.FRIENDS,
                            multiSelect = true,
                            existingValue = formatIds(selected),
                            onValue = {},
                            enableLabels = true
                        )
                    )
                }
            )
            is MomentsAutoLikeRoute.ContactPicker -> ContactPickerPage(
                context = context,
                request = current.request,
                onBack = { route = MomentsAutoLikeRoute.Main },
                onConfirm = { picked ->
                    val ids = picked.map { it.id }.filter { it.isNotBlank() }.toSet()
                    if (current.listMode == MomentsAutoLikeSettings.LIST_BLACKLIST) {
                        blacklist = ids
                        sp.edit().putString(MomentsAutoLikeSettings.KEY_BLACKLIST, formatIds(ids)).apply()
                    } else {
                        whitelist = ids
                        sp.edit().putString(MomentsAutoLikeSettings.KEY_WHITELIST, formatIds(ids)).apply()
                    }
                    route = MomentsAutoLikeRoute.Main
                }
            )
        }
    }
}

@Composable
internal fun MomentsAutoLikeMainPage(
    provider: FeatureSettingsProvider,
    sp: SharedPreferences,
    whitelist: Set<String>,
    blacklist: Set<String>,
    onBack: () -> Unit,
    onPickTargets: (Int) -> Unit
) {
    var enabled by remember { mutableStateOf(sp.getBoolean(MomentsAutoLikeSettings.KEY_ENABLE, false)) }
    var likeSelf by remember {
        mutableStateOf(sp.getBoolean(MomentsAutoLikeSettings.KEY_LIKE_SELF, MomentsAutoLikeSettings.DEFAULT_LIKE_SELF))
    }
    var listMode by remember {
        mutableStateOf(sp.getInt(MomentsAutoLikeSettings.KEY_LIST_MODE, MomentsAutoLikeSettings.DEFAULT_LIST_MODE))
    }
    var dailyLikeLimit by remember {
        mutableStateOf(
            sp.getInt(
                MomentsAutoLikeSettings.KEY_DAILY_LIKE_LIMIT,
                MomentsAutoLikeSettings.DEFAULT_DAILY_LIKE_LIMIT
            ).toString()
        )
    }
    var delayMode by remember {
        mutableStateOf(sp.getInt(MomentsAutoLikeSettings.KEY_DELAY_MODE, MomentsAutoLikeSettings.DEFAULT_DELAY_MODE))
    }
    var fixedDelay by remember {
        mutableStateOf(sp.getInt(MomentsAutoLikeSettings.KEY_FIXED_DELAY_SECONDS, MomentsAutoLikeSettings.DEFAULT_FIXED_DELAY_SECONDS).toString())
    }
    var randomMin by remember {
        mutableStateOf(sp.getInt(MomentsAutoLikeSettings.KEY_RANDOM_MIN_SECONDS, MomentsAutoLikeSettings.DEFAULT_RANDOM_MIN_SECONDS).toString())
    }
    var randomMax by remember {
        mutableStateOf(sp.getInt(MomentsAutoLikeSettings.KEY_RANDOM_MAX_SECONDS, MomentsAutoLikeSettings.DEFAULT_RANDOM_MAX_SECONDS).toString())
    }
    var timeWindow by remember {
        mutableStateOf(sp.getBoolean(MomentsAutoLikeSettings.KEY_TIME_WINDOW_ENABLE, MomentsAutoLikeSettings.DEFAULT_TIME_WINDOW_ENABLE))
    }
    var startTime by remember {
        mutableStateOf(sp.getString(MomentsAutoLikeSettings.KEY_START_TIME, MomentsAutoLikeSettings.DEFAULT_START_TIME).orEmpty())
    }
    var endTime by remember {
        mutableStateOf(sp.getString(MomentsAutoLikeSettings.KEY_END_TIME, MomentsAutoLikeSettings.DEFAULT_END_TIME).orEmpty())
    }
    var maxAge by remember {
        mutableStateOf(sp.getInt(MomentsAutoLikeSettings.KEY_MAX_AGE_HOURS, MomentsAutoLikeSettings.DEFAULT_MAX_AGE_HOURS).toString())
    }
    var allowText by remember { mutableStateOf(sp.getBoolean(MomentsAutoLikeSettings.KEY_ALLOW_TEXT, true)) }
    var allowImage by remember { mutableStateOf(sp.getBoolean(MomentsAutoLikeSettings.KEY_ALLOW_IMAGE, true)) }
    var allowVideo by remember { mutableStateOf(sp.getBoolean(MomentsAutoLikeSettings.KEY_ALLOW_VIDEO, true)) }
    var allowLink by remember { mutableStateOf(sp.getBoolean(MomentsAutoLikeSettings.KEY_ALLOW_LINK, false)) }
    var allowMusic by remember { mutableStateOf(sp.getBoolean(MomentsAutoLikeSettings.KEY_ALLOW_MUSIC, false)) }
    var allowOther by remember { mutableStateOf(sp.getBoolean(MomentsAutoLikeSettings.KEY_ALLOW_OTHER, false)) }
    var allowUnknown by remember { mutableStateOf(sp.getBoolean(MomentsAutoLikeSettings.KEY_ALLOW_UNKNOWN, false)) }
    var keywordsText by remember { mutableStateOf(sp.getString(MomentsAutoLikeSettings.KEY_KEYWORDS_TEXT, "").orEmpty()) }
    var keywordsImageText by remember { mutableStateOf(sp.getString(MomentsAutoLikeSettings.KEY_KEYWORDS_IMAGE_TEXT, "").orEmpty()) }
    var keywordsVideoText by remember { mutableStateOf(sp.getString(MomentsAutoLikeSettings.KEY_KEYWORDS_VIDEO_TEXT, "").orEmpty()) }
    var keywordsCardText by remember { mutableStateOf(sp.getString(MomentsAutoLikeSettings.KEY_KEYWORDS_CARD_TEXT, "").orEmpty()) }
    var keywordText by remember { mutableStateOf(sp.getBoolean(MomentsAutoLikeSettings.KEY_KEYWORD_TEXT, true)) }
    var keywordImage by remember { mutableStateOf(sp.getBoolean(MomentsAutoLikeSettings.KEY_KEYWORD_IMAGE, true)) }
    var keywordVideo by remember { mutableStateOf(sp.getBoolean(MomentsAutoLikeSettings.KEY_KEYWORD_VIDEO, true)) }
    var keywordCard by remember { mutableStateOf(sp.getBoolean(MomentsAutoLikeSettings.KEY_KEYWORD_CARD, true)) }
    var logEnabled by remember { mutableStateOf(sp.getBoolean(MomentsAutoLikeSettings.KEY_LOG_ENABLE, false)) }
    var logs by remember { mutableStateOf(sp.getString(MomentsAutoLikeSettings.KEY_LOGS, "").orEmpty()) }
    val list = if (listMode == MomentsAutoLikeSettings.LIST_BLACKLIST) blacklist else whitelist
    val listSummary = when {
        listMode == MomentsAutoLikeSettings.LIST_BLACKLIST && list.isEmpty() -> "未排除好友，将匹配全部好友"
        listMode == MomentsAutoLikeSettings.LIST_WHITELIST && list.isEmpty() -> "未选择好友，自动点赞不会执行"
        else -> "已选择 ${list.size} 位好友"
    }
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()

    PageScaffold(
        title = provider.title(),
        largeTitle = provider.title(),
        scrollBehavior = scrollBehavior,
        bottomBar = { BottomActionBar("返回", onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "自动点赞") }
            item {
                SettingsCard {
                    SwitchRow(enabled, "朋友圈自动点赞", "按下方规则处理新获取的朋友圈") {
                        enabled = it
                        sp.edit()
                            .putBoolean(MomentsAutoLikeSettings.KEY_ENABLE, it)
                            .putLong(
                                MomentsAutoLikeSettings.KEY_ENABLED_AT_SECONDS,
                                if (it) System.currentTimeMillis() / 1000L else 0L
                            )
                            .apply()
                    }
                    if (enabled) {
                        InsetDivider()
                        SwitchRow(likeSelf, "点赞自己的朋友圈", "自己的朋友圈不受好友名单限制") {
                            likeSelf = it
                            sp.edit().putBoolean(MomentsAutoLikeSettings.KEY_LIKE_SELF, it).apply()
                        }
                    }
                }
            }
            if (enabled) {
                item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "好友范围") }
                item {
                    SettingsCard {
                        PopupOptionRow(
                            title = "名单模式",
                            summary = if (listMode == MomentsAutoLikeSettings.LIST_BLACKLIST) "除黑名单外均可点赞" else "只点赞白名单好友",
                            options = optionItems(
                                "白名单" to MomentsAutoLikeSettings.LIST_WHITELIST,
                                "黑名单" to MomentsAutoLikeSettings.LIST_BLACKLIST
                            ),
                            currentValue = listMode,
                            onValueChanged = {
                                listMode = it
                                sp.edit().putInt(MomentsAutoLikeSettings.KEY_LIST_MODE, it).apply()
                            }
                        )
                        InsetDivider()
                        ActionRow(if (listMode == MomentsAutoLikeSettings.LIST_BLACKLIST) "黑名单" else "白名单", listSummary) {
                            onPickTargets(listMode)
                        }
                        InsetDivider()
                        NumberInputRow("同一人每天点赞数量", "每天最多点赞同一人的朋友圈条数，0 表示不限制", dailyLikeLimit) {
                            dailyLikeLimit = it
                            it.toIntOrNull()?.let { value ->
                                sp.edit()
                                    .putInt(MomentsAutoLikeSettings.KEY_DAILY_LIKE_LIMIT, value.coerceAtLeast(0))
                                    .apply()
                            }
                        }
                    }
                }
                item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "执行时间") }
                item {
                    SettingsCard {
                        PopupOptionRow(
                            title = "点赞延迟",
                            summary = if (delayMode == MomentsAutoLikeSettings.DELAY_RANDOM) "在范围内随机等待" else "每条等待固定时间",
                            options = optionItems(
                                "固定延迟" to MomentsAutoLikeSettings.DELAY_FIXED,
                                "随机延迟" to MomentsAutoLikeSettings.DELAY_RANDOM
                            ),
                            currentValue = delayMode,
                            onValueChanged = {
                                delayMode = it
                                sp.edit().putInt(MomentsAutoLikeSettings.KEY_DELAY_MODE, it).apply()
                            }
                        )
                        if (delayMode == MomentsAutoLikeSettings.DELAY_FIXED) {
                            InsetDivider()
                            NumberInputRow("等待时间", "单位秒，最少 0 秒", fixedDelay) {
                                fixedDelay = it
                                it.toIntOrNull()?.let { value ->
                                    sp.edit().putInt(MomentsAutoLikeSettings.KEY_FIXED_DELAY_SECONDS, value.coerceAtLeast(0)).apply()
                                }
                            }
                        } else {
                            InsetDivider()
                            NumberInputRow("最短等待", "单位秒，最少 0 秒", randomMin) {
                                randomMin = it
                                it.toIntOrNull()?.let { value ->
                                    sp.edit().putInt(MomentsAutoLikeSettings.KEY_RANDOM_MIN_SECONDS, value.coerceAtLeast(0)).apply()
                                }
                            }
                            InsetDivider()
                            NumberInputRow("最长等待", "单位秒，不能小于最短等待", randomMax) {
                                randomMax = it
                                it.toIntOrNull()?.let { value ->
                                    sp.edit().putInt(MomentsAutoLikeSettings.KEY_RANDOM_MAX_SECONDS, value.coerceAtLeast(0)).apply()
                                }
                            }
                        }
                        InsetDivider()
                        SwitchRow(timeWindow, "限制运行时段", "支持跨零点时段") {
                            timeWindow = it
                            sp.edit().putBoolean(MomentsAutoLikeSettings.KEY_TIME_WINDOW_ENABLE, it).apply()
                        }
                        if (timeWindow) {
                            InsetDivider()
                            TimeOfDayPickerRow("开始时间", startTime) {
                                startTime = it
                                sp.edit().putString(MomentsAutoLikeSettings.KEY_START_TIME, it).apply()
                            }
                            InsetDivider()
                            TimeOfDayPickerRow("结束时间", endTime) {
                                endTime = it
                                sp.edit().putString(MomentsAutoLikeSettings.KEY_END_TIME, it).apply()
                            }
                        }
                        InsetDivider()
                        NumberInputRow("发布时间限制", "仅点赞发布后指定小时内的朋友圈，最少 1 小时", maxAge) {
                            maxAge = it
                            it.toIntOrNull()?.let { value ->
                                sp.edit().putInt(MomentsAutoLikeSettings.KEY_MAX_AGE_HOURS, value.coerceAtLeast(1)).apply()
                            }
                        }
                    }
                }
                item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "内容过滤") }
                item {
                    SettingsCard {
                        SwitchRow(allowText, "文字朋友圈", "允许自动点赞") {
                            allowText = it; sp.edit().putBoolean(MomentsAutoLikeSettings.KEY_ALLOW_TEXT, it).apply()
                        }
                        InsetDivider()
                        SwitchRow(allowImage, "图片/图文朋友圈", "允许自动点赞") {
                            allowImage = it; sp.edit().putBoolean(MomentsAutoLikeSettings.KEY_ALLOW_IMAGE, it).apply()
                        }
                        InsetDivider()
                        SwitchRow(allowVideo, "视频/视文朋友圈", "允许自动点赞") {
                            allowVideo = it; sp.edit().putBoolean(MomentsAutoLikeSettings.KEY_ALLOW_VIDEO, it).apply()
                        }
                        InsetDivider()
                        SwitchRow(allowLink, "网页/链接朋友圈", "允许自动点赞") {
                            allowLink = it; sp.edit().putBoolean(MomentsAutoLikeSettings.KEY_ALLOW_LINK, it).apply()
                        }
                        InsetDivider()
                        SwitchRow(allowMusic, "音乐朋友圈", "允许自动点赞") {
                            allowMusic = it; sp.edit().putBoolean(MomentsAutoLikeSettings.KEY_ALLOW_MUSIC, it).apply()
                        }
                        InsetDivider()
                        SwitchRow(allowOther, "其他卡片朋友圈", "允许自动点赞") {
                            allowOther = it; sp.edit().putBoolean(MomentsAutoLikeSettings.KEY_ALLOW_OTHER, it).apply()
                        }
                        InsetDivider()
                        SwitchRow(allowUnknown, "未知类型朋友圈", "允许自动点赞，默认关闭") {
                            allowUnknown = it; sp.edit().putBoolean(MomentsAutoLikeSettings.KEY_ALLOW_UNKNOWN, it).apply()
                        }
                        InsetDivider()
                        SwitchRow(keywordText, "文字朋友圈关键词", "开启后按关键词排除") {
                            keywordText = it; sp.edit().putBoolean(MomentsAutoLikeSettings.KEY_KEYWORD_TEXT, it).apply()
                        }
                        if (keywordText) {
                            InsetDivider()
                            InputRow("文字排除关键词", "多个关键词用逗号或换行分隔", keywordsText, minLines = 2) {
                                keywordsText = it
                                sp.edit().putString(MomentsAutoLikeSettings.KEY_KEYWORDS_TEXT, it).apply()
                            }
                        }
                        InsetDivider()
                        SwitchRow(keywordImage, "图文朋友圈关键词", "开启后按关键词排除") {
                            keywordImage = it; sp.edit().putBoolean(MomentsAutoLikeSettings.KEY_KEYWORD_IMAGE, it).apply()
                        }
                        if (keywordImage) {
                            InsetDivider()
                            InputRow("图文排除关键词", "多个关键词用逗号或换行分隔", keywordsImageText, minLines = 2) {
                                keywordsImageText = it
                                sp.edit().putString(MomentsAutoLikeSettings.KEY_KEYWORDS_IMAGE_TEXT, it).apply()
                            }
                        }
                        InsetDivider()
                        SwitchRow(keywordVideo, "视文朋友圈关键词", "开启后按关键词排除") {
                            keywordVideo = it; sp.edit().putBoolean(MomentsAutoLikeSettings.KEY_KEYWORD_VIDEO, it).apply()
                        }
                        if (keywordVideo) {
                            InsetDivider()
                            InputRow("视文排除关键词", "多个关键词用逗号或换行分隔", keywordsVideoText, minLines = 2) {
                                keywordsVideoText = it
                                sp.edit().putString(MomentsAutoLikeSettings.KEY_KEYWORDS_VIDEO_TEXT, it).apply()
                            }
                        }
                        InsetDivider()
                        SwitchRow(keywordCard, "链接/卡片朋友圈关键词", "开启后按关键词排除") {
                            keywordCard = it; sp.edit().putBoolean(MomentsAutoLikeSettings.KEY_KEYWORD_CARD, it).apply()
                        }
                        if (keywordCard) {
                            InsetDivider()
                            InputRow("链接/卡片排除关键词", "多个关键词用逗号或换行分隔", keywordsCardText, minLines = 2) {
                                keywordsCardText = it
                                sp.edit().putString(MomentsAutoLikeSettings.KEY_KEYWORDS_CARD_TEXT, it).apply()
                            }
                        }
                    }
                }
                item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "运行记录") }
                item {
                    SettingsCard {
                        SwitchRow(logEnabled, "记录运行日志", "只保留最近 200 条") {
                            logEnabled = it
                            sp.edit().putBoolean(MomentsAutoLikeSettings.KEY_LOG_ENABLE, it).apply()
                        }
                        if (logEnabled) {
                            InsetDivider()
                            ActionRow("刷新日志", if (logs.isBlank()) "暂无记录" else "${logs.lineSequence().count()} 条记录") {
                                logs = sp.getString(MomentsAutoLikeSettings.KEY_LOGS, "").orEmpty()
                            }
                            if (logs.isNotBlank()) {
                                InsetDivider()
                                Text(
                                    text = logs.take(5000),
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                                )
                                InsetDivider()
                                ActionRow("清空日志", "删除当前自动点赞运行记录") {
                                    sp.edit().remove(MomentsAutoLikeSettings.KEY_LOGS).apply()
                                    logs = ""
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal object MomentsAutoCommentPage {
    @Composable
    operator fun invoke(
        context: Context,
        provider: FeatureSettingsProvider,
        onBack: () -> Unit
    ) {
    val sp = remember { HchatStorage.preferences(context, MomentsAutoCommentSettings.PREFS_NAME) }
    var route by remember { mutableStateOf<MomentsAutoCommentRoute>(MomentsAutoCommentRoute.Main) }
    var whitelist by remember { mutableStateOf(parseIds(sp.getString(MomentsAutoCommentSettings.KEY_WHITELIST, "").orEmpty())) }
    var blacklist by remember { mutableStateOf(parseIds(sp.getString(MomentsAutoCommentSettings.KEY_BLACKLIST, "").orEmpty())) }

    SettingsRouteTransition(
        targetState = route,
        label = "MomentsAutoCommentRoute",
        depthOf = { if (it is MomentsAutoCommentRoute.Main) 0 else 1 }
    ) { current ->
        when (current) {
            MomentsAutoCommentRoute.Main -> MainPage(
                provider = provider,
                sp = sp,
                whitelist = whitelist,
                blacklist = blacklist,
                onBack = onBack,
                onPickTargets = { listMode ->
                    val selected = if (listMode == MomentsAutoCommentSettings.LIST_BLACKLIST) blacklist else whitelist
                    route = MomentsAutoCommentRoute.ContactPicker(
                        listMode,
                        ContactPickerRequest(
                            title = if (listMode == MomentsAutoCommentSettings.LIST_BLACKLIST) "选择评论黑名单" else "选择评论白名单",
                            mode = ContactPickerMode.FRIENDS,
                            multiSelect = true,
                            existingValue = formatIds(selected),
                            onValue = {},
                            enableLabels = true
                        )
                    )
                }
            )
            is MomentsAutoCommentRoute.ContactPicker -> ContactPickerPage(
                context = context,
                request = current.request,
                onBack = { route = MomentsAutoCommentRoute.Main },
                onConfirm = { picked ->
                    val ids = picked.map { it.id }.filter { it.isNotBlank() }.toSet()
                    if (current.listMode == MomentsAutoCommentSettings.LIST_BLACKLIST) {
                        blacklist = ids
                        sp.edit().putString(MomentsAutoCommentSettings.KEY_BLACKLIST, formatIds(ids)).apply()
                    } else {
                        whitelist = ids
                        sp.edit().putString(MomentsAutoCommentSettings.KEY_WHITELIST, formatIds(ids)).apply()
                    }
                    route = MomentsAutoCommentRoute.Main
                }
            )
        }
    }
    }

    @Composable
    private fun MainPage(
        provider: FeatureSettingsProvider,
        sp: SharedPreferences,
        whitelist: Set<String>,
        blacklist: Set<String>,
        onBack: () -> Unit,
        onPickTargets: (Int) -> Unit
    ) {
    var enabled by remember { mutableStateOf(sp.getBoolean(MomentsAutoCommentSettings.KEY_ENABLE, false)) }
    var commentContent by remember {
        mutableStateOf(
            sp.getString(
                MomentsAutoCommentSettings.KEY_COMMENT_CONTENT,
                MomentsAutoCommentSettings.DEFAULT_COMMENT_CONTENT
            ).orEmpty()
        )
    }
    var timeFormat by remember {
        mutableStateOf(
            sp.getString(
                MomentsAutoCommentSettings.KEY_TIME_FORMAT,
                MomentsAutoCommentSettings.DEFAULT_TIME_FORMAT
            ).orEmpty()
        )
    }
    var commentSelf by remember {
        mutableStateOf(
            sp.getBoolean(
                MomentsAutoCommentSettings.KEY_COMMENT_SELF,
                MomentsAutoCommentSettings.DEFAULT_COMMENT_SELF
            )
        )
    }
    var listMode by remember {
        mutableStateOf(sp.getInt(MomentsAutoCommentSettings.KEY_LIST_MODE, MomentsAutoCommentSettings.DEFAULT_LIST_MODE))
    }
    var dailyCommentLimit by remember {
        mutableStateOf(
            sp.getInt(
                MomentsAutoCommentSettings.KEY_DAILY_COMMENT_LIMIT,
                MomentsAutoCommentSettings.DEFAULT_DAILY_COMMENT_LIMIT
            ).toString()
        )
    }
    var delayMode by remember {
        mutableStateOf(sp.getInt(MomentsAutoCommentSettings.KEY_DELAY_MODE, MomentsAutoCommentSettings.DEFAULT_DELAY_MODE))
    }
    var fixedDelay by remember {
        mutableStateOf(sp.getInt(MomentsAutoCommentSettings.KEY_FIXED_DELAY_SECONDS, MomentsAutoCommentSettings.DEFAULT_FIXED_DELAY_SECONDS).toString())
    }
    var randomMin by remember {
        mutableStateOf(sp.getInt(MomentsAutoCommentSettings.KEY_RANDOM_MIN_SECONDS, MomentsAutoCommentSettings.DEFAULT_RANDOM_MIN_SECONDS).toString())
    }
    var randomMax by remember {
        mutableStateOf(sp.getInt(MomentsAutoCommentSettings.KEY_RANDOM_MAX_SECONDS, MomentsAutoCommentSettings.DEFAULT_RANDOM_MAX_SECONDS).toString())
    }
    var timeWindow by remember {
        mutableStateOf(sp.getBoolean(MomentsAutoCommentSettings.KEY_TIME_WINDOW_ENABLE, MomentsAutoCommentSettings.DEFAULT_TIME_WINDOW_ENABLE))
    }
    var startTime by remember {
        mutableStateOf(sp.getString(MomentsAutoCommentSettings.KEY_START_TIME, MomentsAutoCommentSettings.DEFAULT_START_TIME).orEmpty())
    }
    var endTime by remember {
        mutableStateOf(sp.getString(MomentsAutoCommentSettings.KEY_END_TIME, MomentsAutoCommentSettings.DEFAULT_END_TIME).orEmpty())
    }
    var maxAge by remember {
        mutableStateOf(sp.getInt(MomentsAutoCommentSettings.KEY_MAX_AGE_HOURS, MomentsAutoCommentSettings.DEFAULT_MAX_AGE_HOURS).toString())
    }
    var allowText by remember { mutableStateOf(sp.getBoolean(MomentsAutoCommentSettings.KEY_ALLOW_TEXT, true)) }
    var allowImage by remember { mutableStateOf(sp.getBoolean(MomentsAutoCommentSettings.KEY_ALLOW_IMAGE, true)) }
    var allowVideo by remember { mutableStateOf(sp.getBoolean(MomentsAutoCommentSettings.KEY_ALLOW_VIDEO, true)) }
    var allowLink by remember { mutableStateOf(sp.getBoolean(MomentsAutoCommentSettings.KEY_ALLOW_LINK, false)) }
    var allowMusic by remember { mutableStateOf(sp.getBoolean(MomentsAutoCommentSettings.KEY_ALLOW_MUSIC, false)) }
    var allowOther by remember { mutableStateOf(sp.getBoolean(MomentsAutoCommentSettings.KEY_ALLOW_OTHER, false)) }
    var allowUnknown by remember { mutableStateOf(sp.getBoolean(MomentsAutoCommentSettings.KEY_ALLOW_UNKNOWN, false)) }
    var keywordsText by remember { mutableStateOf(sp.getString(MomentsAutoCommentSettings.KEY_KEYWORDS_TEXT, "").orEmpty()) }
    var keywordsImageText by remember { mutableStateOf(sp.getString(MomentsAutoCommentSettings.KEY_KEYWORDS_IMAGE_TEXT, "").orEmpty()) }
    var keywordsVideoText by remember { mutableStateOf(sp.getString(MomentsAutoCommentSettings.KEY_KEYWORDS_VIDEO_TEXT, "").orEmpty()) }
    var keywordsCardText by remember { mutableStateOf(sp.getString(MomentsAutoCommentSettings.KEY_KEYWORDS_CARD_TEXT, "").orEmpty()) }
    var keywordText by remember { mutableStateOf(sp.getBoolean(MomentsAutoCommentSettings.KEY_KEYWORD_TEXT, true)) }
    var keywordImage by remember { mutableStateOf(sp.getBoolean(MomentsAutoCommentSettings.KEY_KEYWORD_IMAGE, true)) }
    var keywordVideo by remember { mutableStateOf(sp.getBoolean(MomentsAutoCommentSettings.KEY_KEYWORD_VIDEO, true)) }
    var keywordCard by remember { mutableStateOf(sp.getBoolean(MomentsAutoCommentSettings.KEY_KEYWORD_CARD, true)) }
    var logEnabled by remember { mutableStateOf(sp.getBoolean(MomentsAutoCommentSettings.KEY_LOG_ENABLE, false)) }
    var logs by remember { mutableStateOf(sp.getString(MomentsAutoCommentSettings.KEY_LOGS, "").orEmpty()) }
    val list = if (listMode == MomentsAutoCommentSettings.LIST_BLACKLIST) blacklist else whitelist
    val listSummary = when {
        listMode == MomentsAutoCommentSettings.LIST_BLACKLIST && list.isEmpty() -> "未排除好友，将匹配全部好友"
        listMode == MomentsAutoCommentSettings.LIST_WHITELIST && list.isEmpty() -> "未选择好友，自动评论不会执行"
        else -> "已选择 ${list.size} 位好友"
    }
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()

    PageScaffold(
        title = provider.title(),
        largeTitle = provider.title(),
        scrollBehavior = scrollBehavior,
        bottomBar = { BottomActionBar("返回", onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "自动评论") }
            item {
                SettingsCard {
                    SwitchRow(enabled, "朋友圈自动评论", "按下方规则处理新获取的朋友圈") {
                        enabled = it
                        sp.edit()
                            .putBoolean(MomentsAutoCommentSettings.KEY_ENABLE, it)
                            .putLong(
                                MomentsAutoCommentSettings.KEY_ENABLED_AT_SECONDS,
                                if (it) System.currentTimeMillis() / 1000L else 0L
                            )
                            .apply()
                    }
                    if (enabled) {
                        InsetDivider()
                        VariableInputRow(
                            title = "评论内容",
                            summary = if (commentContent.isBlank()) "需要填写" else "点击下方中文变量插入到光标位置",
                            value = commentContent,
                            variables = momentsAutoCommentTemplateVariables,
                            minLines = 3
                        ) {
                            commentContent = it
                            sp.edit().putString(MomentsAutoCommentSettings.KEY_COMMENT_CONTENT, it).apply()
                        }
                        if (commentContent.contains(MomentsAutoCommentSettings.VAR_TIME)) {
                            InsetDivider()
                            InputRow(
                                title = "时间变量格式",
                                summary = if (MomentsAutoCommentSettings.isValidTimeFormat(timeFormat)) {
                                    "例如 HH:mm:ss 或 yyyy-MM-dd HH:mm:ss"
                                } else {
                                    "格式无效，当前输入不会保存"
                                },
                                value = timeFormat
                            ) {
                                timeFormat = it
                                if (MomentsAutoCommentSettings.isValidTimeFormat(it)) {
                                    sp.edit().putString(
                                        MomentsAutoCommentSettings.KEY_TIME_FORMAT,
                                        MomentsAutoCommentSettings.normalizeTimeFormat(it)
                                    ).apply()
                                }
                            }
                        }
                        InsetDivider()
                        SwitchRow(commentSelf, "评论自己的朋友圈", "自己的朋友圈不受好友名单限制") {
                            commentSelf = it
                            sp.edit().putBoolean(MomentsAutoCommentSettings.KEY_COMMENT_SELF, it).apply()
                        }
                    }
                }
            }
            if (enabled) {
                item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "好友范围") }
                item {
                    SettingsCard {
                        PopupOptionRow(
                            title = "名单模式",
                            summary = if (listMode == MomentsAutoCommentSettings.LIST_BLACKLIST) "除黑名单外均可评论" else "只评论白名单好友",
                            options = optionItems(
                                "白名单" to MomentsAutoCommentSettings.LIST_WHITELIST,
                                "黑名单" to MomentsAutoCommentSettings.LIST_BLACKLIST
                            ),
                            currentValue = listMode,
                            onValueChanged = {
                                listMode = it
                                sp.edit().putInt(MomentsAutoCommentSettings.KEY_LIST_MODE, it).apply()
                            }
                        )
                        InsetDivider()
                        ActionRow(if (listMode == MomentsAutoCommentSettings.LIST_BLACKLIST) "黑名单" else "白名单", listSummary) {
                            onPickTargets(listMode)
                        }
                        InsetDivider()
                        NumberInputRow("同一人每天评论数量", "每天最多评论同一人的朋友圈条数，0 表示不限制", dailyCommentLimit) {
                            dailyCommentLimit = it
                            it.toIntOrNull()?.let { value ->
                                sp.edit()
                                    .putInt(MomentsAutoCommentSettings.KEY_DAILY_COMMENT_LIMIT, value.coerceAtLeast(0))
                                    .apply()
                            }
                        }
                    }
                }
                item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "执行时间") }
                item {
                    SettingsCard {
                        PopupOptionRow(
                            title = "评论延迟",
                            summary = if (delayMode == MomentsAutoCommentSettings.DELAY_RANDOM) "在范围内随机等待" else "每条等待固定时间",
                            options = optionItems(
                                "固定延迟" to MomentsAutoCommentSettings.DELAY_FIXED,
                                "随机延迟" to MomentsAutoCommentSettings.DELAY_RANDOM
                            ),
                            currentValue = delayMode,
                            onValueChanged = {
                                delayMode = it
                                sp.edit().putInt(MomentsAutoCommentSettings.KEY_DELAY_MODE, it).apply()
                            }
                        )
                        if (delayMode == MomentsAutoCommentSettings.DELAY_FIXED) {
                            InsetDivider()
                            NumberInputRow("等待时间", "单位秒，最少 0 秒", fixedDelay) {
                                fixedDelay = it
                                it.toIntOrNull()?.let { value ->
                                    sp.edit().putInt(MomentsAutoCommentSettings.KEY_FIXED_DELAY_SECONDS, value.coerceAtLeast(0)).apply()
                                }
                            }
                        } else {
                            InsetDivider()
                            NumberInputRow("最短等待", "单位秒，最少 0 秒", randomMin) {
                                randomMin = it
                                it.toIntOrNull()?.let { value ->
                                    sp.edit().putInt(MomentsAutoCommentSettings.KEY_RANDOM_MIN_SECONDS, value.coerceAtLeast(0)).apply()
                                }
                            }
                            InsetDivider()
                            NumberInputRow("最长等待", "单位秒，不能小于最短等待", randomMax) {
                                randomMax = it
                                it.toIntOrNull()?.let { value ->
                                    sp.edit().putInt(MomentsAutoCommentSettings.KEY_RANDOM_MAX_SECONDS, value.coerceAtLeast(0)).apply()
                                }
                            }
                        }
                        InsetDivider()
                        SwitchRow(timeWindow, "限制运行时段", "支持跨零点时段") {
                            timeWindow = it
                            sp.edit().putBoolean(MomentsAutoCommentSettings.KEY_TIME_WINDOW_ENABLE, it).apply()
                        }
                        if (timeWindow) {
                            InsetDivider()
                            TimeOfDayPickerRow("开始时间", startTime) {
                                startTime = it
                                sp.edit().putString(MomentsAutoCommentSettings.KEY_START_TIME, it).apply()
                            }
                            InsetDivider()
                            TimeOfDayPickerRow("结束时间", endTime) {
                                endTime = it
                                sp.edit().putString(MomentsAutoCommentSettings.KEY_END_TIME, it).apply()
                            }
                        }
                        InsetDivider()
                        NumberInputRow("发布时间限制", "仅评论发布后指定小时内的朋友圈，最少 1 小时", maxAge) {
                            maxAge = it
                            it.toIntOrNull()?.let { value ->
                                sp.edit().putInt(MomentsAutoCommentSettings.KEY_MAX_AGE_HOURS, value.coerceAtLeast(1)).apply()
                            }
                        }
                    }
                }
                item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "内容过滤") }
                item {
                    SettingsCard {
                        SwitchRow(allowText, "文字朋友圈", "允许自动评论") {
                            allowText = it; sp.edit().putBoolean(MomentsAutoCommentSettings.KEY_ALLOW_TEXT, it).apply()
                        }
                        InsetDivider()
                        SwitchRow(allowImage, "图片/图文朋友圈", "允许自动评论") {
                            allowImage = it; sp.edit().putBoolean(MomentsAutoCommentSettings.KEY_ALLOW_IMAGE, it).apply()
                        }
                        InsetDivider()
                        SwitchRow(allowVideo, "视频/视文朋友圈", "允许自动评论") {
                            allowVideo = it; sp.edit().putBoolean(MomentsAutoCommentSettings.KEY_ALLOW_VIDEO, it).apply()
                        }
                        InsetDivider()
                        SwitchRow(allowLink, "网页/链接朋友圈", "允许自动评论") {
                            allowLink = it; sp.edit().putBoolean(MomentsAutoCommentSettings.KEY_ALLOW_LINK, it).apply()
                        }
                        InsetDivider()
                        SwitchRow(allowMusic, "音乐朋友圈", "允许自动评论") {
                            allowMusic = it; sp.edit().putBoolean(MomentsAutoCommentSettings.KEY_ALLOW_MUSIC, it).apply()
                        }
                        InsetDivider()
                        SwitchRow(allowOther, "其他卡片朋友圈", "允许自动评论") {
                            allowOther = it; sp.edit().putBoolean(MomentsAutoCommentSettings.KEY_ALLOW_OTHER, it).apply()
                        }
                        InsetDivider()
                        SwitchRow(allowUnknown, "未知类型朋友圈", "允许自动评论，默认关闭") {
                            allowUnknown = it; sp.edit().putBoolean(MomentsAutoCommentSettings.KEY_ALLOW_UNKNOWN, it).apply()
                        }
                        InsetDivider()
                        SwitchRow(keywordText, "文字朋友圈关键词", "开启后按关键词排除") {
                            keywordText = it; sp.edit().putBoolean(MomentsAutoCommentSettings.KEY_KEYWORD_TEXT, it).apply()
                        }
                        if (keywordText) {
                            InsetDivider()
                            InputRow("文字排除关键词", "多个关键词用逗号或换行分隔", keywordsText, minLines = 2) {
                                keywordsText = it
                                sp.edit().putString(MomentsAutoCommentSettings.KEY_KEYWORDS_TEXT, it).apply()
                            }
                        }
                        InsetDivider()
                        SwitchRow(keywordImage, "图文朋友圈关键词", "开启后按关键词排除") {
                            keywordImage = it; sp.edit().putBoolean(MomentsAutoCommentSettings.KEY_KEYWORD_IMAGE, it).apply()
                        }
                        if (keywordImage) {
                            InsetDivider()
                            InputRow("图文排除关键词", "多个关键词用逗号或换行分隔", keywordsImageText, minLines = 2) {
                                keywordsImageText = it
                                sp.edit().putString(MomentsAutoCommentSettings.KEY_KEYWORDS_IMAGE_TEXT, it).apply()
                            }
                        }
                        InsetDivider()
                        SwitchRow(keywordVideo, "视文朋友圈关键词", "开启后按关键词排除") {
                            keywordVideo = it; sp.edit().putBoolean(MomentsAutoCommentSettings.KEY_KEYWORD_VIDEO, it).apply()
                        }
                        if (keywordVideo) {
                            InsetDivider()
                            InputRow("视文排除关键词", "多个关键词用逗号或换行分隔", keywordsVideoText, minLines = 2) {
                                keywordsVideoText = it
                                sp.edit().putString(MomentsAutoCommentSettings.KEY_KEYWORDS_VIDEO_TEXT, it).apply()
                            }
                        }
                        InsetDivider()
                        SwitchRow(keywordCard, "链接/卡片朋友圈关键词", "开启后按关键词排除") {
                            keywordCard = it; sp.edit().putBoolean(MomentsAutoCommentSettings.KEY_KEYWORD_CARD, it).apply()
                        }
                        if (keywordCard) {
                            InsetDivider()
                            InputRow("链接/卡片排除关键词", "多个关键词用逗号或换行分隔", keywordsCardText, minLines = 2) {
                                keywordsCardText = it
                                sp.edit().putString(MomentsAutoCommentSettings.KEY_KEYWORDS_CARD_TEXT, it).apply()
                            }
                        }
                    }
                }
                item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "运行记录") }
                item {
                    SettingsCard {
                        SwitchRow(logEnabled, "记录运行日志", "只保留最近 200 条") {
                            logEnabled = it
                            sp.edit().putBoolean(MomentsAutoCommentSettings.KEY_LOG_ENABLE, it).apply()
                        }
                        if (logEnabled) {
                            InsetDivider()
                            ActionRow("刷新日志", if (logs.isBlank()) "暂无记录" else "${logs.lineSequence().count()} 条记录") {
                                logs = sp.getString(MomentsAutoCommentSettings.KEY_LOGS, "").orEmpty()
                            }
                            if (logs.isNotBlank()) {
                                InsetDivider()
                                Text(
                                    text = logs.take(5000),
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                                )
                                InsetDivider()
                                ActionRow("清空日志", "删除当前自动评论运行记录") {
                                    sp.edit().remove(MomentsAutoCommentSettings.KEY_LOGS).apply()
                                    logs = ""
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
internal fun MomentsAutoForwardMiuixPage(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit
) {
    val sp = remember { HchatStorage.preferences(context, MomentsAutoForwardSettings.PREFS_NAME) }
    var route by remember { mutableStateOf<MomentsAutoForwardRoute>(MomentsAutoForwardRoute.Main) }
    var targets by remember {
        mutableStateOf(
            parseIds(
                sp.getString(
                    MomentsAutoForwardSettings.KEY_TARGETS,
                    MomentsAutoForwardSettings.DEFAULT_TARGETS
                ).orEmpty()
            )
        )
    }
    var replacementRules by remember {
        mutableStateOf(
            MomentsAutoForwardSettings.decodeKeywordReplacements(
                sp.getString(
                    MomentsAutoForwardSettings.KEY_KEYWORD_REPLACEMENTS,
                    MomentsAutoForwardSettings.DEFAULT_KEYWORD_REPLACEMENTS
                )
            )
        )
    }
    val mainListState = rememberLazyListState()

    SettingsRouteTransition(
        targetState = route,
        label = "MomentsAutoForwardRoute",
        depthOf = { if (it is MomentsAutoForwardRoute.Main) 0 else 1 }
    ) { current ->
        when (current) {
            MomentsAutoForwardRoute.Main -> MomentsAutoForwardMainPage(
                provider = provider,
                sp = sp,
                listState = mainListState,
                targetCount = targets.size,
                replacementRuleCount = replacementRules.size,
                onBack = onBack,
                onOpenReplacementRules = { route = MomentsAutoForwardRoute.ReplacementRules },
                onPickTargets = {
                    route = MomentsAutoForwardRoute.ContactPicker(
                        ContactPickerRequest(
                            title = "选择转发好友",
                            mode = ContactPickerMode.FRIENDS,
                            multiSelect = true,
                            existingValue = formatIds(targets),
                            onValue = {},
                            enableLabels = true
                        )
                    )
                }
            )
            is MomentsAutoForwardRoute.ContactPicker -> ContactPickerPage(
                context = context,
                request = current.request,
                onBack = { route = MomentsAutoForwardRoute.Main },
                onConfirm = { picked ->
                    targets = picked.map { it.id }.filter { it.isNotBlank() }.toSet()
                    sp.edit().putString(MomentsAutoForwardSettings.KEY_TARGETS, formatIds(targets)).apply()
                    route = MomentsAutoForwardRoute.Main
                }
            )
            MomentsAutoForwardRoute.ReplacementRules -> KeywordReplacementRulesPage(
                context = context,
                initialRules = replacementRules,
                onBack = { route = MomentsAutoForwardRoute.Main },
                onSave = { updated ->
                    replacementRules = updated
                    sp.edit()
                        .putString(
                            MomentsAutoForwardSettings.KEY_KEYWORD_REPLACEMENTS,
                            MomentsAutoForwardSettings.encodeKeywordReplacements(updated)
                        )
                        .apply()
                    Toast.makeText(context, "替换规则已保存", Toast.LENGTH_SHORT).show()
                    route = MomentsAutoForwardRoute.Main
                }
            )
        }
    }
}

@Composable
internal fun MomentsAutoForwardMainPage(
    provider: FeatureSettingsProvider,
    sp: SharedPreferences,
    listState: LazyListState,
    targetCount: Int,
    replacementRuleCount: Int,
    onBack: () -> Unit,
    onOpenReplacementRules: () -> Unit,
    onPickTargets: () -> Unit
) {
    var enabled by remember {
        mutableStateOf(
            sp.getBoolean(
                MomentsAutoForwardSettings.KEY_ENABLE,
                MomentsAutoForwardSettings.DEFAULT_ENABLE
            )
        )
    }
    var allowText by remember {
        mutableStateOf(
            sp.getBoolean(
                MomentsAutoForwardSettings.KEY_ALLOW_TEXT,
                MomentsAutoForwardSettings.DEFAULT_ALLOW_TEXT
            )
        )
    }
    var allowImage by remember {
        mutableStateOf(
            sp.getBoolean(
                MomentsAutoForwardSettings.KEY_ALLOW_IMAGE,
                MomentsAutoForwardSettings.DEFAULT_ALLOW_IMAGE
            )
        )
    }
    var allowVideo by remember {
        mutableStateOf(
            sp.getBoolean(
                MomentsAutoForwardSettings.KEY_ALLOW_VIDEO,
                MomentsAutoForwardSettings.DEFAULT_ALLOW_VIDEO
            )
        )
    }
    var allowLivePhoto by remember {
        mutableStateOf(
            sp.getBoolean(
                MomentsAutoForwardSettings.KEY_ALLOW_LIVE_PHOTO,
                MomentsAutoForwardSettings.DEFAULT_ALLOW_LIVE_PHOTO
            )
        )
    }
    var allowLink by remember {
        mutableStateOf(
            sp.getBoolean(
                MomentsAutoForwardSettings.KEY_ALLOW_LINK,
                MomentsAutoForwardSettings.DEFAULT_ALLOW_LINK
            )
        )
    }
    var allowMusic by remember {
        mutableStateOf(
            sp.getBoolean(
                MomentsAutoForwardSettings.KEY_ALLOW_MUSIC,
                MomentsAutoForwardSettings.DEFAULT_ALLOW_MUSIC
            )
        )
    }
    var allowOther by remember {
        mutableStateOf(
            sp.getBoolean(
                MomentsAutoForwardSettings.KEY_ALLOW_OTHER,
                MomentsAutoForwardSettings.DEFAULT_ALLOW_OTHER
            )
        )
    }
    var allowUnknown by remember {
        mutableStateOf(
            sp.getBoolean(
                MomentsAutoForwardSettings.KEY_ALLOW_UNKNOWN,
                MomentsAutoForwardSettings.DEFAULT_ALLOW_UNKNOWN
            )
        )
    }
    var delayMode by remember {
        mutableStateOf(
            sp.getInt(
                MomentsAutoForwardSettings.KEY_DELAY_MODE,
                MomentsAutoForwardSettings.DEFAULT_DELAY_MODE
            )
        )
    }
    var fixedDelay by remember {
        mutableStateOf(
            sp.getInt(
                MomentsAutoForwardSettings.KEY_FIXED_DELAY_SECONDS,
                MomentsAutoForwardSettings.DEFAULT_FIXED_DELAY_SECONDS
            ).toString()
        )
    }
    var randomMin by remember {
        mutableStateOf(
            sp.getInt(
                MomentsAutoForwardSettings.KEY_RANDOM_MIN_SECONDS,
                MomentsAutoForwardSettings.DEFAULT_RANDOM_MIN_SECONDS
            ).toString()
        )
    }
    var randomMax by remember {
        mutableStateOf(
            sp.getInt(
                MomentsAutoForwardSettings.KEY_RANDOM_MAX_SECONDS,
                MomentsAutoForwardSettings.DEFAULT_RANDOM_MAX_SECONDS
            ).toString()
        )
    }
    var dailyLimit by remember {
        mutableStateOf(
            sp.getInt(
                MomentsAutoForwardSettings.KEY_DAILY_LIMIT,
                MomentsAutoForwardSettings.DEFAULT_DAILY_LIMIT
            ).toString()
        )
    }
    var includeKeywords by remember {
        mutableStateOf(
            sp.getString(
                MomentsAutoForwardSettings.KEY_INCLUDE_KEYWORDS,
                MomentsAutoForwardSettings.DEFAULT_INCLUDE_KEYWORDS
            ).orEmpty()
        )
    }
    var includeKeywordsEnabled by remember {
        mutableStateOf(MomentsAutoForwardSettings.includeKeywordsEnabled(sp))
    }
    var excludeKeywords by remember {
        mutableStateOf(
            sp.getString(
                MomentsAutoForwardSettings.KEY_EXCLUDE_KEYWORDS,
                MomentsAutoForwardSettings.DEFAULT_EXCLUDE_KEYWORDS
            ).orEmpty()
        )
    }
    var excludeKeywordsEnabled by remember {
        mutableStateOf(MomentsAutoForwardSettings.excludeKeywordsEnabled(sp))
    }
    var replaceKeywordsEnabled by remember {
        mutableStateOf(
            sp.getBoolean(
                MomentsAutoForwardSettings.KEY_REPLACE_KEYWORDS_ENABLE,
                MomentsAutoForwardSettings.DEFAULT_REPLACE_KEYWORDS_ENABLE
            )
        )
    }
    var contentTemplate by remember {
        mutableStateOf(
            sp.getString(
                MomentsAutoForwardSettings.KEY_CONTENT_TEMPLATE,
                MomentsAutoForwardSettings.DEFAULT_CONTENT_TEMPLATE
            ).orEmpty()
        )
    }
    var logEnabled by remember {
        mutableStateOf(
            sp.getBoolean(
                MomentsAutoForwardSettings.KEY_LOG_ENABLE,
                MomentsAutoForwardSettings.DEFAULT_LOG_ENABLE
            )
        )
    }
    var logs by remember {
        mutableStateOf(
            sp.getString(
                MomentsAutoForwardSettings.KEY_LOGS,
                MomentsAutoForwardSettings.DEFAULT_LOGS
            ).orEmpty()
        )
    }
    val templateVariables = remember {
        listOf(
            TemplateVariable(MomentsAutoForwardSettings.VARIABLE_CONTENT, "原文"),
            TemplateVariable(MomentsAutoForwardSettings.VARIABLE_SENDER, "发布者"),
            TemplateVariable(MomentsAutoForwardSettings.VARIABLE_WXID, "发布者ID"),
            TemplateVariable(MomentsAutoForwardSettings.VARIABLE_TYPE, "类型"),
            TemplateVariable(MomentsAutoForwardSettings.VARIABLE_SNS_ID, "朋友圈ID")
        )
    }
    val targetSummary = if (targetCount == 0) {
        "未选择好友，自动转发不会执行"
    } else {
        "已选择 $targetCount 位好友"
    }
    val scrollBehavior = MiuixScrollBehavior()

    PageScaffold(
        title = provider.title(),
        largeTitle = provider.title(),
        scrollBehavior = scrollBehavior,
        bottomBar = { BottomActionBar("返回", onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "自动转发") }
            item {
                SettingsCard {
                    SwitchRow(enabled, "朋友圈自动转发", "按规则静默转发指定好友的新朋友圈") {
                        enabled = it
                        sp.edit()
                            .putBoolean(MomentsAutoForwardSettings.KEY_ENABLE, it)
                            .putLong(
                                MomentsAutoForwardSettings.KEY_ENABLED_AT_SECONDS,
                                if (it) System.currentTimeMillis() / 1000L else 0L
                            )
                            .apply()
                    }
                }
            }
            if (enabled) {
                item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "转发好友") }
                item {
                    SettingsCard {
                        ActionRow("转发好友", targetSummary, onPickTargets)
                    }
                }
                item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "内容类型") }
                item {
                    SettingsCard {
                        SwitchRow(allowText, "文字", "允许自动转发") {
                            allowText = it
                            sp.edit().putBoolean(MomentsAutoForwardSettings.KEY_ALLOW_TEXT, it).apply()
                        }
                        InsetDivider()
                        SwitchRow(allowImage, "图片/图文", "允许自动转发") {
                            allowImage = it
                            sp.edit().putBoolean(MomentsAutoForwardSettings.KEY_ALLOW_IMAGE, it).apply()
                        }
                        InsetDivider()
                        SwitchRow(allowVideo, "视频/视文", "允许自动转发") {
                            allowVideo = it
                            sp.edit().putBoolean(MomentsAutoForwardSettings.KEY_ALLOW_VIDEO, it).apply()
                        }
                        InsetDivider()
                        SwitchRow(allowLivePhoto, "实况照片", "允许自动转发") {
                            allowLivePhoto = it
                            sp.edit().putBoolean(MomentsAutoForwardSettings.KEY_ALLOW_LIVE_PHOTO, it).apply()
                        }
                        InsetDivider()
                        SwitchRow(allowLink, "网页/链接", "按正文和链接转为文字朋友圈") {
                            allowLink = it
                            sp.edit().putBoolean(MomentsAutoForwardSettings.KEY_ALLOW_LINK, it).apply()
                        }
                        InsetDivider()
                        SwitchRow(allowMusic, "音乐", "按正文和链接转为文字朋友圈") {
                            allowMusic = it
                            sp.edit().putBoolean(MomentsAutoForwardSettings.KEY_ALLOW_MUSIC, it).apply()
                        }
                        InsetDivider()
                        SwitchRow(allowOther, "其他卡片", "按可解析内容转为文字朋友圈") {
                            allowOther = it
                            sp.edit().putBoolean(MomentsAutoForwardSettings.KEY_ALLOW_OTHER, it).apply()
                        }
                        InsetDivider()
                        SwitchRow(allowUnknown, "未知类型", "按可解析内容转发，默认关闭") {
                            allowUnknown = it
                            sp.edit().putBoolean(MomentsAutoForwardSettings.KEY_ALLOW_UNKNOWN, it).apply()
                        }
                    }
                }
                item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "执行方式") }
                item {
                    SettingsCard {
                        PopupOptionRow(
                            title = "转发延迟",
                            summary = if (delayMode == MomentsAutoForwardSettings.DELAY_RANDOM) {
                                "在范围内随机等待"
                            } else {
                                "每条等待固定时间"
                            },
                            options = optionItems(
                                "固定延迟" to MomentsAutoForwardSettings.DELAY_FIXED,
                                "随机延迟" to MomentsAutoForwardSettings.DELAY_RANDOM
                            ),
                            currentValue = delayMode,
                            onValueChanged = {
                                delayMode = it
                                sp.edit().putInt(MomentsAutoForwardSettings.KEY_DELAY_MODE, it).apply()
                            }
                        )
                        if (delayMode == MomentsAutoForwardSettings.DELAY_FIXED) {
                            InsetDivider()
                            NumberInputRow("等待时间", "单位秒，最少 0 秒", fixedDelay) {
                                fixedDelay = it
                                it.toIntOrNull()?.let { value ->
                                    sp.edit()
                                        .putInt(
                                            MomentsAutoForwardSettings.KEY_FIXED_DELAY_SECONDS,
                                            value.coerceAtLeast(0)
                                        )
                                        .apply()
                                }
                            }
                        } else {
                            InsetDivider()
                            NumberInputRow("最短等待", "单位秒，最少 0 秒", randomMin) {
                                randomMin = it
                                it.toIntOrNull()?.let { value ->
                                    sp.edit()
                                        .putInt(
                                            MomentsAutoForwardSettings.KEY_RANDOM_MIN_SECONDS,
                                            value.coerceAtLeast(0)
                                        )
                                        .apply()
                                }
                            }
                            InsetDivider()
                            NumberInputRow("最长等待", "单位秒，不能小于最短等待", randomMax) {
                                randomMax = it
                                it.toIntOrNull()?.let { value ->
                                    sp.edit()
                                        .putInt(
                                            MomentsAutoForwardSettings.KEY_RANDOM_MAX_SECONDS,
                                            value.coerceAtLeast(0)
                                        )
                                        .apply()
                                }
                            }
                        }
                        InsetDivider()
                        NumberInputRow("每日转发上限", "每天最多转发的朋友圈数量，0 表示不限制", dailyLimit) {
                            dailyLimit = it
                            it.toIntOrNull()?.let { value ->
                                sp.edit()
                                    .putInt(MomentsAutoForwardSettings.KEY_DAILY_LIMIT, value.coerceAtLeast(0))
                                    .apply()
                            }
                        }
                    }
                }
                item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "内容处理") }
                item {
                    SettingsCard {
                        SwitchRow(
                            includeKeywordsEnabled,
                            "包含关键词筛选",
                            "开启后，正文需命中任意关键词才转发"
                        ) {
                            includeKeywordsEnabled = it
                            sp.edit()
                                .putBoolean(MomentsAutoForwardSettings.KEY_INCLUDE_KEYWORDS_ENABLE, it)
                                .apply()
                        }
                        if (includeKeywordsEnabled) {
                            InsetDivider()
                            InputRow(
                                "包含关键词",
                                "多个关键词用逗号或换行分隔",
                                includeKeywords,
                                minLines = 2
                            ) {
                                includeKeywords = it
                                sp.edit().putString(MomentsAutoForwardSettings.KEY_INCLUDE_KEYWORDS, it).apply()
                            }
                        }
                        InsetDivider()
                        SwitchRow(
                            excludeKeywordsEnabled,
                            "排除关键词筛选",
                            "开启后，正文命中任意关键词时不转发，优先于包含规则"
                        ) {
                            excludeKeywordsEnabled = it
                            sp.edit()
                                .putBoolean(MomentsAutoForwardSettings.KEY_EXCLUDE_KEYWORDS_ENABLE, it)
                                .apply()
                        }
                        if (excludeKeywordsEnabled) {
                            InsetDivider()
                            InputRow(
                                "排除关键词",
                                "多个关键词用逗号或换行分隔",
                                excludeKeywords,
                                minLines = 2
                            ) {
                                excludeKeywords = it
                                sp.edit().putString(MomentsAutoForwardSettings.KEY_EXCLUDE_KEYWORDS, it).apply()
                            }
                        }
                        InsetDivider()
                        SwitchRow(
                            replaceKeywordsEnabled,
                            "替换关键词",
                            "转发前按多条规则替换朋友圈原文"
                        ) {
                            replaceKeywordsEnabled = it
                            sp.edit()
                                .putBoolean(MomentsAutoForwardSettings.KEY_REPLACE_KEYWORDS_ENABLE, it)
                                .apply()
                        }
                        if (replaceKeywordsEnabled) {
                            InsetDivider()
                            ActionRow(
                                "替换规则",
                                if (replacementRuleCount == 0) {
                                    "暂无规则"
                                } else {
                                    "已设置 $replacementRuleCount 条规则"
                                },
                                onOpenReplacementRules
                            )
                        }
                        InsetDivider()
                        VariableInputRow(
                            "文案模板",
                            "支持下方变量",
                            contentTemplate,
                            templateVariables,
                            minLines = 3
                        ) {
                            contentTemplate = it
                            sp.edit().putString(MomentsAutoForwardSettings.KEY_CONTENT_TEMPLATE, it).apply()
                        }
                    }
                }
                item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "运行记录") }
                item {
                    SettingsCard {
                        SwitchRow(logEnabled, "记录运行日志", "记录自动转发执行情况") {
                            logEnabled = it
                            sp.edit().putBoolean(MomentsAutoForwardSettings.KEY_LOG_ENABLE, it).apply()
                        }
                        if (logEnabled) {
                            InsetDivider()
                            ActionRow(
                                "刷新日志",
                                if (logs.isBlank()) "暂无记录" else "${logs.lineSequence().count()} 条记录"
                            ) {
                                logs = sp.getString(
                                    MomentsAutoForwardSettings.KEY_LOGS,
                                    MomentsAutoForwardSettings.DEFAULT_LOGS
                                ).orEmpty()
                            }
                            if (logs.isNotBlank()) {
                                InsetDivider()
                                Text(
                                    text = logs.take(5000),
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                                )
                                InsetDivider()
                                ActionRow("清空日志", "删除当前自动转发运行记录") {
                                    sp.edit().remove(MomentsAutoForwardSettings.KEY_LOGS).apply()
                                    logs = ""
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun KeywordReplacementRulesPage(
    context: Context,
    initialRules: List<KeywordReplacementRule>,
    onBack: () -> Unit,
    onSave: (List<KeywordReplacementRule>) -> Unit
) {
    var rules by remember(initialRules) { mutableStateOf(initialRules) }
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()

    fun updateRule(index: Int, update: (KeywordReplacementRule) -> KeywordReplacementRule) {
        if (index !in rules.indices) return
        rules = rules.toMutableList().also { next -> next[index] = update(next[index]) }
    }

    fun save() {
        val normalized = rules.map { it.copy(keyword = it.keyword.trim()) }
        if (normalized.any { it.keyword.isEmpty() }) {
            Toast.makeText(context, "原关键词不能为空", Toast.LENGTH_SHORT).show()
            return
        }
        val duplicate = normalized.groupingBy { it.keyword.lowercase(Locale.ROOT) }
            .eachCount()
            .entries
            .firstOrNull { it.value > 1 }
            ?.key
        if (duplicate != null) {
            Toast.makeText(context, "存在重复的原关键词", Toast.LENGTH_SHORT).show()
            return
        }
        onSave(normalized)
    }

    PageScaffold(
        title = "替换规则",
        largeTitle = "替换规则",
        scrollBehavior = scrollBehavior,
        bottomBar = { BottomActionBar("保存", ::save, "返回", onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "关键词替换") }
            if (rules.isEmpty()) {
                item {
                    SettingsCard {
                        InfoRow("替换规则", "暂无规则")
                    }
                }
            } else {
                rules.forEachIndexed { index, rule ->
                    item(key = "keyword-replacement-$index") {
                        SettingsCard {
                            InputRow(
                                "原关键词",
                                "匹配时不区分大小写",
                                rule.keyword
                            ) { value ->
                                updateRule(index) { it.copy(keyword = value) }
                            }
                            InsetDivider()
                            InputRow(
                                "替换内容",
                                "留空表示删除原关键词",
                                rule.replacement
                            ) { value ->
                                updateRule(index) { it.copy(replacement = value) }
                            }
                            InsetDivider()
                            ActionRow("删除本条", "移除第 ${index + 1} 条替换规则") {
                                rules = rules.toMutableList().also { it.removeAt(index) }
                            }
                        }
                    }
                }
            }
            item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "操作") }
            item {
                SettingsCard {
                    ActionRow("新增规则", "添加一条关键词替换规则") {
                        rules = rules + KeywordReplacementRule("", "")
                    }
                }
            }
        }
    }
}

@Composable
internal fun MomentsAutoRefreshMiuixPage(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit
) {
    val sp = remember { HchatStorage.preferences(context, MomentsAutoRefreshSettings.PREFS_NAME) }
    var enabled by remember { mutableStateOf(sp.getBoolean(MomentsAutoRefreshSettings.KEY_ENABLE, false)) }
    var interval by remember {
        mutableStateOf(sp.getInt(MomentsAutoRefreshSettings.KEY_INTERVAL_SECONDS, MomentsAutoRefreshSettings.DEFAULT_INTERVAL_SECONDS).toString())
    }
    var timeWindow by remember {
        mutableStateOf(sp.getBoolean(MomentsAutoRefreshSettings.KEY_TIME_WINDOW_ENABLE, MomentsAutoRefreshSettings.DEFAULT_TIME_WINDOW_ENABLE))
    }
    var startTime by remember {
        mutableStateOf(sp.getString(MomentsAutoRefreshSettings.KEY_START_TIME, MomentsAutoRefreshSettings.DEFAULT_START_TIME).orEmpty())
    }
    var endTime by remember {
        mutableStateOf(sp.getString(MomentsAutoRefreshSettings.KEY_END_TIME, MomentsAutoRefreshSettings.DEFAULT_END_TIME).orEmpty())
    }
    var lastTime by remember { mutableStateOf(sp.getLong(MomentsAutoRefreshSettings.KEY_LAST_TIME, 0L)) }
    var lastResult by remember { mutableStateOf(sp.getString(MomentsAutoRefreshSettings.KEY_LAST_RESULT, "").orEmpty()) }
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()

    PageScaffold(
        title = provider.title(),
        largeTitle = provider.title(),
        scrollBehavior = scrollBehavior,
        bottomBar = { BottomActionBar("返回", onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "后台刷新") }
            item {
                SettingsCard {
                    SwitchRow(enabled, "朋友圈自动刷新", "按设定间隔获取新的朋友圈内容") {
                        enabled = it
                        sp.edit().putBoolean(MomentsAutoRefreshSettings.KEY_ENABLE, it).apply()
                    }
                    if (enabled) {
                        InsetDivider()
                        NumberInputRow("刷新间隔", "单位秒，最少 0 秒", interval) {
                            interval = it
                            it.toIntOrNull()?.let { value ->
                                sp.edit().putInt(MomentsAutoRefreshSettings.KEY_INTERVAL_SECONDS, value.coerceAtLeast(0)).apply()
                            }
                        }
                        InsetDivider()
                        SwitchRow(timeWindow, "限制刷新时段", "支持跨零点时段") {
                            timeWindow = it
                            sp.edit().putBoolean(MomentsAutoRefreshSettings.KEY_TIME_WINDOW_ENABLE, it).apply()
                        }
                        if (timeWindow) {
                            InsetDivider()
                            TimeOfDayPickerRow("开始时间", startTime) {
                                startTime = it
                                sp.edit().putString(MomentsAutoRefreshSettings.KEY_START_TIME, it).apply()
                            }
                            InsetDivider()
                            TimeOfDayPickerRow("结束时间", endTime) {
                                endTime = it
                                sp.edit().putString(MomentsAutoRefreshSettings.KEY_END_TIME, it).apply()
                            }
                        }
                    }
                }
            }
            if (enabled) {
                item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "状态") }
                item {
                    SettingsCard {
                        ActionRow(
                            "刷新状态",
                            if (lastTime <= 0L) "尚未执行" else SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(lastTime)) + " · " + lastResult
                        ) {
                            lastTime = sp.getLong(MomentsAutoRefreshSettings.KEY_LAST_TIME, 0L)
                            lastResult = sp.getString(MomentsAutoRefreshSettings.KEY_LAST_RESULT, "").orEmpty()
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun MomentsContactFilterMiuixPage(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit
) {
    val sp = remember { HchatStorage.preferences(context, MomentsContactFilterSettings.PREFS_NAME) }
    var route by remember { mutableStateOf<MomentsContactFilterRoute>(MomentsContactFilterRoute.Main) }
    var targets by remember {
        mutableStateOf(
            parseIds(
                sp.getString(
                    MomentsContactFilterSettings.KEY_TARGETS,
                    MomentsContactFilterSettings.DEFAULT_TARGETS
                ).orEmpty()
            )
        )
    }

    SettingsRouteTransition(
        targetState = route,
        label = "MomentsContactFilterRoute",
        depthOf = { if (it is MomentsContactFilterRoute.Main) 0 else 1 }
    ) { current ->
        when (current) {
            MomentsContactFilterRoute.Main -> MomentsContactFilterMainPage(
                provider = provider,
                sp = sp,
                targets = targets,
                onBack = onBack,
                onPickTargets = {
                    route = MomentsContactFilterRoute.ContactPicker(
                        ContactPickerRequest(
                            title = "选择朋友圈过滤对象",
                            mode = ContactPickerMode.FRIENDS,
                            multiSelect = true,
                            existingValue = formatIds(targets),
                            onValue = {},
                            enableLabels = true,
                            enableScopeSelection = true
                        )
                    )
                }
            )
            is MomentsContactFilterRoute.ContactPicker -> ContactPickerPage(
                context = context,
                request = current.request,
                onBack = { route = MomentsContactFilterRoute.Main },
                onConfirm = { picked ->
                    targets = picked.map { it.id }.filter { it.isNotBlank() }.toSet()
                    sp.edit()
                        .putString(MomentsContactFilterSettings.KEY_TARGETS, formatIds(targets))
                        .apply()
                    route = MomentsContactFilterRoute.Main
                }
            )
        }
    }
}

@Composable
internal fun MomentsContactFilterMainPage(
    provider: FeatureSettingsProvider,
    sp: SharedPreferences,
    targets: Set<String>,
    onBack: () -> Unit,
    onPickTargets: () -> Unit
) {
    var enabled by remember {
        mutableStateOf(
            sp.getBoolean(
                MomentsContactFilterSettings.KEY_ENABLE,
                MomentsContactFilterSettings.DEFAULT_ENABLE
            )
        )
    }
    var mode by remember {
        mutableStateOf(
            sp.getInt(
                MomentsContactFilterSettings.KEY_MODE,
                MomentsContactFilterSettings.DEFAULT_MODE
            )
        )
    }
    val targetSummary = when {
        targets.isEmpty() -> "未选择好友，暂不筛选"
        mode == MomentsContactFilterSettings.MODE_INCLUDE_ONLY -> "只显示所选 ${targets.size} 位好友"
        else -> "隐藏所选 ${targets.size} 位好友"
    }
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()

    PageScaffold(
        title = provider.title(),
        largeTitle = provider.title(),
        scrollBehavior = scrollBehavior,
        bottomBar = { BottomActionBar("返回", onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "朋友圈") }
            item {
                SettingsCard {
                    SwitchRow(
                        checked = enabled,
                        title = "朋友圈过滤",
                        summary = "按好友范围过滤朋友圈内容"
                    ) {
                        enabled = it
                        sp.edit().putBoolean(MomentsContactFilterSettings.KEY_ENABLE, it).apply()
                    }
                    if (enabled) {
                        InsetDivider()
                        PopupOptionRow(
                            title = "过滤模式",
                            summary = if (mode == MomentsContactFilterSettings.MODE_INCLUDE_ONLY) {
                                "只看所选好友的朋友圈"
                            } else {
                                "过滤所选好友的朋友圈"
                            },
                            options = optionItems(
                                "过滤所选好友" to MomentsContactFilterSettings.MODE_EXCLUDE,
                                "只看所选好友" to MomentsContactFilterSettings.MODE_INCLUDE_ONLY
                            ),
                            currentValue = mode,
                            onValueChanged = {
                                mode = it
                                sp.edit().putInt(MomentsContactFilterSettings.KEY_MODE, it).apply()
                            }
                        )
                        InsetDivider()
                        ActionRow(
                            title = "过滤对象",
                            summary = targetSummary
                        ) {
                            onPickTargets()
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun MomentsKeywordBlockMiuixPage(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit
) {
    val sp = remember { HchatStorage.preferences(context, MomentsKeywordBlockSettings.PREFS_NAME) }
    var enabled by remember {
        mutableStateOf(
            sp.getBoolean(
                MomentsKeywordBlockSettings.KEY_ENABLE,
                MomentsKeywordBlockSettings.DEFAULT_ENABLE
            )
        )
    }
    var keywords by remember {
        mutableStateOf(
            sp.getString(
                MomentsKeywordBlockSettings.KEY_KEYWORDS,
                MomentsKeywordBlockSettings.DEFAULT_KEYWORDS
            ).orEmpty()
        )
    }
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()

    PageScaffold(
        title = provider.title(),
        largeTitle = provider.title(),
        scrollBehavior = scrollBehavior,
        bottomBar = { BottomActionBar("返回", onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "朋友圈") }
            item {
                SettingsCard {
                    SwitchRow(
                        checked = enabled,
                        title = "朋友圈关键词屏蔽",
                        summary = "隐藏正文命中任意关键词的朋友圈"
                    ) {
                        enabled = it
                        sp.edit().putBoolean(MomentsKeywordBlockSettings.KEY_ENABLE, it).apply()
                    }
                    if (enabled) {
                        InsetDivider()
                        InputRow(
                            title = "屏蔽关键词",
                            summary = "多个关键词用逗号或换行分隔",
                            value = keywords,
                            minLines = 3
                        ) {
                            keywords = it
                            sp.edit().putString(MomentsKeywordBlockSettings.KEY_KEYWORDS, it).apply()
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun MomentsBottomDetailMiuixPage(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit
) {
    val sp = remember { HchatStorage.preferences(context, MomentsBottomDetailSettings.PREFS_NAME) }
    var enabled by remember {
        mutableStateOf(
            sp.getBoolean(
                MomentsBottomDetailSettings.KEY_ENABLE,
                MomentsBottomDetailSettings.DEFAULT_ENABLE
            )
        )
    }
    var textFormat by remember {
        mutableStateOf(
            sp.getString(
                MomentsBottomDetailSettings.KEY_TEXT_FORMAT,
                MomentsBottomDetailSettings.DEFAULT_TEXT_FORMAT
            ).orEmpty()
        )
    }
    var timeFormat by remember {
        mutableStateOf(
            sp.getString(
                MomentsBottomDetailSettings.KEY_TIME_FORMAT,
                MomentsBottomDetailSettings.DEFAULT_TIME_FORMAT
            ).orEmpty()
        )
    }
    var hideGroupIcon by remember {
        mutableStateOf(
            sp.getBoolean(
                MomentsBottomDetailSettings.KEY_HIDE_GROUP_ICON,
                MomentsBottomDetailSettings.DEFAULT_HIDE_GROUP_ICON
            )
        )
    }
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()

    PageScaffold(
        title = provider.title(),
        largeTitle = provider.title(),
        scrollBehavior = scrollBehavior,
        bottomBar = {
            BottomActionBar(
                primaryText = "保存设置",
                onPrimaryClick = {
                    val timeFormatValid = MomentsBottomDetailSettings.isValidTimeFormat(timeFormat)
                    if (enabled && !timeFormatValid) {
                        Toast.makeText(context, "时间格式无效", Toast.LENGTH_SHORT).show()
                    } else {
                        val normalizedText = MomentsBottomDetailSettings.normalizeTextFormat(textFormat)
                        val normalizedTime = if (timeFormatValid) {
                            MomentsBottomDetailSettings.normalizeTimeFormat(timeFormat)
                        } else {
                            MomentsBottomDetailSettings.normalizeTimeFormat(
                                sp.getString(
                                    MomentsBottomDetailSettings.KEY_TIME_FORMAT,
                                    MomentsBottomDetailSettings.DEFAULT_TIME_FORMAT
                                )
                            )
                        }
                        textFormat = normalizedText
                        timeFormat = normalizedTime
                        sp.edit()
                            .putBoolean(MomentsBottomDetailSettings.KEY_ENABLE, enabled)
                            .putString(MomentsBottomDetailSettings.KEY_TEXT_FORMAT, normalizedText)
                            .putString(MomentsBottomDetailSettings.KEY_TIME_FORMAT, normalizedTime)
                            .putBoolean(MomentsBottomDetailSettings.KEY_HIDE_GROUP_ICON, hideGroupIcon)
                            .apply()
                        Toast.makeText(context, "设置已保存", Toast.LENGTH_SHORT).show()
                    }
                },
                secondaryText = "返回",
                onSecondaryClick = onBack
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "朋友圈底部详情") }
            item {
                SettingsCard {
                    SwitchRow(
                        checked = enabled,
                        title = "朋友圈底部详情",
                        summary = "在朋友圈底部显示自定义时间和详情"
                    ) {
                        enabled = it
                    }
                }
            }
            if (enabled) {
                item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "格式") }
                item {
                    SettingsCard {
                        VariableInputRow(
                            title = "文本格式",
                            summary = "留空使用默认格式",
                            value = textFormat,
                            variables = momentsBottomDetailTemplateVariables,
                            onValueChange = { textFormat = it }
                        )
                        InsetDivider()
                        InputRow(
                            "时间格式",
                            "使用日期格式，例如 yyyy-MM-dd HH:mm:ss",
                            timeFormat
                        ) { timeFormat = it }
                        InsetDivider()
                        SwitchRow(
                            checked = hideGroupIcon,
                            title = "隐藏可见范围",
                            summary = "隐藏朋友圈底部的可见范围图标"
                        ) {
                            hideGroupIcon = it
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun MomentsPostNotificationMiuixPage(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit
) {
    val sp = remember { HchatStorage.preferences(context, MomentsPostNotificationSettings.PREFS_NAME) }
    var route by remember { mutableStateOf<MomentsPostNotificationRoute>(MomentsPostNotificationRoute.Main) }
    var targets by remember {
        mutableStateOf(parseIds(sp.getString(MomentsPostNotificationSettings.KEY_TARGETS, "").orEmpty()))
    }
    SettingsRouteTransition(
        targetState = route,
        label = "MomentsPostNotificationRoute",
        depthOf = { if (it is MomentsPostNotificationRoute.Main) 0 else 1 }
    ) { current ->
        when (current) {
            MomentsPostNotificationRoute.Main -> MomentsPostNotificationMainPage(
                provider = provider,
                sp = sp,
                targetCount = targets.size,
                onBack = onBack,
                onPickTargets = {
                    route = MomentsPostNotificationRoute.ContactPicker(
                        ContactPickerRequest(
                            title = "选择朋友圈提醒好友",
                            mode = ContactPickerMode.FRIENDS,
                            multiSelect = true,
                            existingValue = formatIds(targets),
                            onValue = {},
                            enableLabels = true
                        )
                    )
                }
            )
            is MomentsPostNotificationRoute.ContactPicker -> ContactPickerPage(
                context = context,
                request = current.request,
                onBack = { route = MomentsPostNotificationRoute.Main },
                onConfirm = { picked ->
                    targets = picked.map { it.id }.filter { it.isNotBlank() }.toSet()
                    sp.edit().putString(MomentsPostNotificationSettings.KEY_TARGETS, formatIds(targets)).apply()
                    route = MomentsPostNotificationRoute.Main
                }
            )
        }
    }
}

@Composable
internal fun MomentsPostNotificationMainPage(
    provider: FeatureSettingsProvider,
    sp: SharedPreferences,
    targetCount: Int,
    onBack: () -> Unit,
    onPickTargets: () -> Unit
) {
    var enabled by remember { mutableStateOf(sp.getBoolean(MomentsPostNotificationSettings.KEY_ENABLE, false)) }
    var systemNotification by remember {
        mutableStateOf(sp.getBoolean(MomentsPostNotificationSettings.KEY_SYSTEM_NOTIFICATION, MomentsPostNotificationSettings.DEFAULT_SYSTEM_NOTIFICATION))
    }
    var toast by remember {
        mutableStateOf(sp.getBoolean(MomentsPostNotificationSettings.KEY_TOAST, MomentsPostNotificationSettings.DEFAULT_TOAST))
    }
    var titleTemplate by remember {
        mutableStateOf(sp.getString(MomentsPostNotificationSettings.KEY_TITLE_TEMPLATE, MomentsPostNotificationSettings.DEFAULT_TITLE_TEMPLATE).orEmpty())
    }
    var bodyTemplate by remember {
        mutableStateOf(sp.getString(MomentsPostNotificationSettings.KEY_BODY_TEMPLATE, MomentsPostNotificationSettings.DEFAULT_BODY_TEMPLATE).orEmpty())
    }
    var toastTemplate by remember {
        mutableStateOf(sp.getString(MomentsPostNotificationSettings.KEY_TOAST_TEMPLATE, MomentsPostNotificationSettings.DEFAULT_TOAST_TEMPLATE).orEmpty())
    }
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()
    PageScaffold(
        title = provider.title(),
        largeTitle = provider.title(),
        scrollBehavior = scrollBehavior,
        bottomBar = { BottomActionBar("返回", onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "发布提醒") }
            item {
                SettingsCard {
                    SwitchRow(enabled, "朋友圈发布通知", "指定好友发布新朋友圈时提醒") {
                        enabled = it
                        sp.edit()
                            .putBoolean(MomentsPostNotificationSettings.KEY_ENABLE, it)
                            .putLong(
                                MomentsPostNotificationSettings.KEY_ENABLED_AT_SECONDS,
                                if (it) System.currentTimeMillis() / 1000L else 0L
                            )
                            .apply()
                    }
                    if (enabled) {
                        InsetDivider()
                        ActionRow("提醒好友", if (targetCount == 0) "未选择好友" else "已选择 $targetCount 位好友", onPickTargets)
                        InsetDivider()
                        SwitchRow(systemNotification, "系统通知", "显示在系统通知栏") {
                            systemNotification = it
                            sp.edit().putBoolean(MomentsPostNotificationSettings.KEY_SYSTEM_NOTIFICATION, it).apply()
                        }
                        InsetDivider()
                        SwitchRow(toast, "Toast提醒", "在微信界面短暂显示提醒") {
                            toast = it
                            sp.edit().putBoolean(MomentsPostNotificationSettings.KEY_TOAST, it).apply()
                        }
                    }
                }
            }
            if (enabled && (systemNotification || toast)) {
                item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "提醒模板") }
                item {
                    SettingsCard {
                        if (systemNotification) {
                            VariableInputRow("通知标题", "留空使用默认标题", titleTemplate, momentsPostNotificationTemplateVariables) {
                                titleTemplate = it
                                sp.edit().putString(MomentsPostNotificationSettings.KEY_TITLE_TEMPLATE, it).apply()
                            }
                            InsetDivider()
                            VariableInputRow("通知内容", "留空使用发布者、类型和完整正文", bodyTemplate, momentsPostNotificationTemplateVariables, minLines = 2) {
                                bodyTemplate = it
                                sp.edit().putString(MomentsPostNotificationSettings.KEY_BODY_TEMPLATE, it).apply()
                            }
                        }
                        if (toast) {
                            if (systemNotification) InsetDivider()
                            VariableInputRow("Toast内容", "留空使用默认提醒", toastTemplate, momentsPostNotificationTemplateVariables, minLines = 2) {
                                toastTemplate = it
                                sp.edit().putString(MomentsPostNotificationSettings.KEY_TOAST_TEMPLATE, it).apply()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun OriginalMomentsUploadMiuixPage(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit
) {
    val sp = remember { HchatStorage.preferences(context, OriginalMomentsUploadSettings.PREFS_NAME) }
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()

    PageScaffold(
        title = provider.title(),
        largeTitle = provider.title(),
        scrollBehavior = scrollBehavior,
        bottomBar = {
            BottomActionBar("返回", onBack)
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "朋友圈") }
            item {
                SettingsCard {
                    SwitchRow(
                        sp,
                        OriginalMomentsUploadSettings.KEY_ENABLE,
                        "原图上传",
                        "发布朋友圈图片和视频时尽量跳过微信压缩",
                        OriginalMomentsUploadSettings.DEFAULT_ENABLE
                    )
                }
            }
        }
    }
}

@Composable
internal fun MomentsUploadTailMiuixPage(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit
) {
    val sp = remember { HchatStorage.preferences(context, MomentsUploadTailSettings.PREFS_NAME) }
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()
    var enabled by remember {
        mutableStateOf(
            sp.getBoolean(
                MomentsUploadTailSettings.KEY_ENABLE,
                MomentsUploadTailSettings.DEFAULT_ENABLE
            )
        )
    }
    var sdkId by remember {
        mutableStateOf(
            sp.getString(
                MomentsUploadTailSettings.KEY_SDK_ID,
                MomentsUploadTailSettings.DEFAULT_SDK_ID
            ).orEmpty()
        )
    }
    var sdkAppName by remember {
        mutableStateOf(
            sp.getString(
                MomentsUploadTailSettings.KEY_SDK_APP_NAME,
                MomentsUploadTailSettings.DEFAULT_SDK_APP_NAME
            ).orEmpty()
        )
    }

    var showPresetPicker by remember { mutableStateOf(false) }
    var presetQuery by remember { mutableStateOf("") }

    PageScaffold(
        title = provider.title(),
        largeTitle = provider.title(),
        scrollBehavior = scrollBehavior,
        bottomBar = {
            BottomActionBar("返回", onBack)
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "朋友圈") }
            item {
                SettingsCard {
                    SwitchRow(
                        enabled,
                        "朋友圈上传尾巴",
                        "发布时附带指定的 SDK 来源"
                    ) {
                        enabled = it
                        sp.edit().putBoolean(MomentsUploadTailSettings.KEY_ENABLE, it).apply()
                    }
                    if (enabled) {
                        InsetDivider()
                        ActionRow(
                            "选择内置尾巴",
                            MomentsUploadTailPresets.matchingPreset(sdkId, sdkAppName)?.name ?: "自定义"
                        ) {
                            presetQuery = ""
                            showPresetPicker = true
                        }
                        InsetDivider()
                        InputRow("SDK ID", "输入第三方 SDK 标识", sdkId) {
                            sdkId = it
                            sp.edit().putString(MomentsUploadTailSettings.KEY_SDK_ID, it).apply()
                        }
                        InsetDivider()
                        InputRow("SDK 名称", "输入显示的 SDK 名称", sdkAppName) {
                            sdkAppName = it
                            sp.edit()
                                .putString(MomentsUploadTailSettings.KEY_SDK_APP_NAME, it)
                                .apply()
                        }
                    }
                }
            }
        }
    }

    MomentsUploadTailPresetPicker(
        show = showPresetPicker,
        query = presetQuery,
        onQueryChange = { presetQuery = it },
        onDismiss = { showPresetPicker = false },
        onPresetSelected = { preset ->
            sdkId = preset.appId
            sdkAppName = preset.name
            MomentsUploadTailPresets.saveSelection(sp, preset)
            showPresetPicker = false
        }
    )
}

@Composable
internal fun SnsAntiRecallMiuixPage(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit
) {
    val sp = remember { HchatStorage.preferences(context, SnsAntiRecallSettings.PREFS_NAME) }
    var momentMarkText by remember {
        mutableStateOf(
            sp.getString(
                SnsAntiRecallSettings.KEY_CUSTOM_MARK_TEXT,
                SnsAntiRecallSettings.DEFAULT_CUSTOM_MARK_TEXT
            ).orEmpty()
        )
    }
    var commentMarkText by remember {
        mutableStateOf(
            sp.getString(
                SnsAntiRecallSettings.KEY_COMMENT_CUSTOM_MARK_TEXT,
                SnsAntiRecallSettings.DEFAULT_COMMENT_CUSTOM_MARK_TEXT
            ).orEmpty()
        )
    }
    var momentMarkEnabled by remember {
        mutableStateOf(
            sp.getBoolean(
                SnsAntiRecallSettings.KEY_CUSTOM_MARK_ENABLE,
                SnsAntiRecallSettings.DEFAULT_CUSTOM_MARK_ENABLE
            )
        )
    }
    var commentMarkEnabled by remember {
        mutableStateOf(
            sp.getBoolean(
                SnsAntiRecallSettings.KEY_COMMENT_CUSTOM_MARK_ENABLE,
                SnsAntiRecallSettings.DEFAULT_COMMENT_CUSTOM_MARK_ENABLE
            )
        )
    }
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()

    PageScaffold(
        title = provider.title(),
        largeTitle = provider.title(),
        scrollBehavior = scrollBehavior,
        bottomBar = {
            BottomActionBar("返回", onBack)
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "朋友圈") }
            item {
                SettingsCard {
                    SwitchRow(
                        sp,
                        SnsAntiRecallSettings.KEY_ENABLE,
                        "朋友圈防撤回",
                        "已缓存的朋友圈在对方删除或限制可见范围后继续显示",
                        SnsAntiRecallSettings.DEFAULT_ENABLE
                    )
                    InsetDivider()
                    SwitchRow(
                        checked = momentMarkEnabled,
                        title = "自定义朋友圈撤回提示",
                        summary = "开启后可自定义朋友圈正文被删除时的提示文案"
                    ) { checked ->
                        momentMarkEnabled = checked
                        sp.edit().putBoolean(SnsAntiRecallSettings.KEY_CUSTOM_MARK_ENABLE, checked).apply()
                    }
                    if (momentMarkEnabled) {
                        InsetDivider()
                        InputRow("朋友圈提示文案", "默认 [已删除]", momentMarkText) { momentMarkText = it }
                    }
                    InsetDivider()
                    SwitchRow(
                        sp,
                        SnsAntiRecallSettings.KEY_COMMENT_ENABLE,
                        "朋友圈评论防撤回",
                        "已缓存的朋友圈评论被删除后继续显示，并标记已删除",
                        SnsAntiRecallSettings.DEFAULT_COMMENT_ENABLE
                    )
                    InsetDivider()
                    SwitchRow(
                        checked = commentMarkEnabled,
                        title = "自定义评论撤回提示",
                        summary = "开启后可自定义朋友圈评论被删除时的提示文案"
                    ) { checked ->
                        commentMarkEnabled = checked
                        sp.edit().putBoolean(SnsAntiRecallSettings.KEY_COMMENT_CUSTOM_MARK_ENABLE, checked).apply()
                    }
                    if (commentMarkEnabled) {
                        InsetDivider()
                        InputRow("评论提示文案", "默认 [已删除]", commentMarkText) { commentMarkText = it }
                    }
                    InsetDivider()
                    SwitchRow(
                        sp,
                        SnsAntiRecallSettings.KEY_FORCE_LEGACY_PROFILE,
                        "强制旧版个人主页朋友圈",
                        "Flutter 个人主页看不到已删除朋友圈时，改用微信旧版 SnsUserUI",
                        SnsAntiRecallSettings.DEFAULT_FORCE_LEGACY_PROFILE
                    )
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            sp.edit()
                .putString(
                    SnsAntiRecallSettings.KEY_CUSTOM_MARK_TEXT,
                    momentMarkText.ifBlank { SnsAntiRecallSettings.DEFAULT_CUSTOM_MARK_TEXT }
                )
                .putString(
                    SnsAntiRecallSettings.KEY_COMMENT_CUSTOM_MARK_TEXT,
                    commentMarkText.ifBlank { SnsAntiRecallSettings.DEFAULT_COMMENT_CUSTOM_MARK_TEXT }
                )
                .apply()
        }
    }
}

@Composable
internal fun MomentsFakeInteractionMiuixPage(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit
) {
    val sp = remember {
        HchatStorage.preferences(context, MomentsFakeInteractionSettings.PREFS_NAME)
    }
    val isLike = provider.featureId() == MomentsFakeLikeSettingsProvider.ID
    val isForward = provider.featureId() == MomentsFakeForwardSettingsProvider.ID
    val enableKey = when {
        isLike -> MomentsFakeInteractionSettings.KEY_FAKE_LIKE_ENABLE
        isForward -> MomentsFakeInteractionSettings.KEY_FAKE_FORWARD_ENABLE
        else -> MomentsFakeInteractionSettings.KEY_FAKE_COMMENT_ENABLE
    }
    val defaultEnabled = when {
        isLike -> MomentsFakeInteractionSettings.DEFAULT_FAKE_LIKE_ENABLE
        isForward -> MomentsFakeInteractionSettings.DEFAULT_FAKE_FORWARD_ENABLE
        else -> MomentsFakeInteractionSettings.DEFAULT_FAKE_COMMENT_ENABLE
    }
    val pendingRestoreKey = if (isLike) {
        MomentsFakeInteractionSettings.KEY_PENDING_RESTORE_LIKES
    } else {
        MomentsFakeInteractionSettings.KEY_PENDING_RESTORE_COMMENTS
    }
    val useNonFriendsKey = if (isLike) {
        MomentsFakeInteractionSettings.KEY_FAKE_LIKE_USE_NON_FRIENDS
    } else {
        MomentsFakeInteractionSettings.KEY_FAKE_COMMENT_USE_NON_FRIENDS
    }
    val defaultUseNonFriends = if (isLike) {
        MomentsFakeInteractionSettings.DEFAULT_FAKE_LIKE_USE_NON_FRIENDS
    } else {
        MomentsFakeInteractionSettings.DEFAULT_FAKE_COMMENT_USE_NON_FRIENDS
    }
    var enabled by remember(provider.featureId()) {
        mutableStateOf(
            sp.getBoolean(enableKey, defaultEnabled)
        )
    }
    var useNonFriends by remember(provider.featureId()) {
        mutableStateOf(
            sp.getBoolean(useNonFriendsKey, defaultUseNonFriends)
        )
    }
    var randomCommentContent by remember(provider.featureId()) {
        mutableStateOf(
            sp.getBoolean(
                MomentsFakeInteractionSettings.KEY_FAKE_COMMENT_RANDOM_CONTENT,
                MomentsFakeInteractionSettings.DEFAULT_FAKE_COMMENT_RANDOM_CONTENT
            )
        )
    }
    var commentContents by remember(provider.featureId()) {
        mutableStateOf(MomentsFakeInteractionSettings.commentContents(sp))
    }
    var randomOrder by remember(provider.featureId()) {
        mutableStateOf(
            sp.getBoolean(
                MomentsFakeInteractionSettings.KEY_FAKE_LIKE_RANDOM_ORDER,
                MomentsFakeInteractionSettings.DEFAULT_FAKE_LIKE_RANDOM_ORDER
            )
        )
    }
    var autoSelect by remember(provider.featureId()) {
        mutableStateOf(
            sp.getBoolean(
                MomentsFakeInteractionSettings.KEY_FAKE_LIKE_AUTO_SELECT,
                MomentsFakeInteractionSettings.DEFAULT_FAKE_LIKE_AUTO_SELECT
            )
        )
    }
    var autoSelectCount by remember(provider.featureId()) {
        mutableStateOf(
            sp.getInt(
                MomentsFakeInteractionSettings.KEY_FAKE_LIKE_AUTO_SELECT_COUNT,
                MomentsFakeInteractionSettings.DEFAULT_FAKE_LIKE_AUTO_SELECT_COUNT
            ).toString()
        )
    }
    var excludedIds by remember(provider.featureId()) {
        mutableStateOf(
            sp.getStringSet(
                MomentsFakeInteractionSettings.KEY_FAKE_LIKE_EXCLUDED_IDS,
                emptySet()
            ).orEmpty().toSet()
        )
    }
    val menuTextKey = when {
        isLike -> MomentsFakeInteractionSettings.KEY_FAKE_LIKE_MENU_TEXT
        isForward -> MomentsFakeInteractionSettings.KEY_FAKE_FORWARD_MENU_TEXT
        else -> MomentsFakeInteractionSettings.KEY_FAKE_COMMENT_MENU_TEXT
    }
    val defaultMenuText = when {
        isLike -> MomentsFakeInteractionSettings.DEFAULT_FAKE_LIKE_MENU_TEXT
        isForward -> MomentsFakeInteractionSettings.DEFAULT_FAKE_FORWARD_MENU_TEXT
        else -> MomentsFakeInteractionSettings.DEFAULT_FAKE_COMMENT_MENU_TEXT
    }
    val hideMenuKey = when {
        isLike -> MomentsFakeInteractionSettings.KEY_FAKE_LIKE_HIDE_MENU
        isForward -> MomentsFakeInteractionSettings.KEY_FAKE_FORWARD_HIDE_MENU
        else -> MomentsFakeInteractionSettings.KEY_FAKE_COMMENT_HIDE_MENU
    }
    val defaultHideMenu = when {
        isLike -> MomentsFakeInteractionSettings.DEFAULT_FAKE_LIKE_HIDE_MENU
        isForward -> MomentsFakeInteractionSettings.DEFAULT_FAKE_FORWARD_HIDE_MENU
        else -> MomentsFakeInteractionSettings.DEFAULT_FAKE_COMMENT_HIDE_MENU
    }
    var hideMenu by remember(provider.featureId()) {
        mutableStateOf(sp.getBoolean(hideMenuKey, defaultHideMenu))
    }
    var menuText by remember(provider.featureId()) {
        mutableStateOf(sp.getString(menuTextKey, defaultMenuText).orEmpty().ifBlank { defaultMenuText })
    }
    var forwardDebugLog by remember(provider.featureId()) {
        mutableStateOf(
            sp.getBoolean(
                MomentsFakeInteractionSettings.KEY_FAKE_FORWARD_DEBUG_LOG,
                MomentsFakeInteractionSettings.DEFAULT_FAKE_FORWARD_DEBUG_LOG
            )
        )
    }
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()

    PageScaffold(
        title = provider.title(),
        largeTitle = provider.title(),
        scrollBehavior = scrollBehavior,
        bottomBar = { BottomActionBar("返回", onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = provider.title()) }
            item {
                SettingsCard {
                    SwitchRow(
                        checked = enabled,
                        title = provider.title(),
                        summary = provider.subtitle()
                    ) {
                        enabled = it
                        sp.edit().putBoolean(enableKey, it).commit()
                        MomentsFakeInteractionRuntimeRegistry.reapplyAll()
                    }
                }
            }
            if (enabled && isLike) {
                item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "生成方式") }
                item {
                    SettingsCard {
                        SwitchRow(
                            checked = useNonFriends,
                            title = "使用非好友",
                            summary = "仅在伪集赞选择器中额外加入群成员"
                        ) {
                            useNonFriends = it
                            sp.edit().putBoolean(useNonFriendsKey, it).commit()
                        }
                        InsetDivider()
                        SwitchRow(
                            checked = randomOrder,
                            title = "随机排序",
                            summary = "保存时随机排列点赞人的显示顺序"
                        ) {
                            randomOrder = it
                            sp.edit().putBoolean(
                                MomentsFakeInteractionSettings.KEY_FAKE_LIKE_RANDOM_ORDER,
                                it
                            ).commit()
                        }
                        InsetDivider()
                        SwitchRow(
                            checked = autoSelect,
                            title = "自动勾选好友",
                            summary = "长按伪集赞后自动勾选指定人数"
                        ) {
                            autoSelect = it
                            sp.edit().putBoolean(
                                MomentsFakeInteractionSettings.KEY_FAKE_LIKE_AUTO_SELECT,
                                it
                            ).commit()
                        }
                        if (autoSelect) {
                            InsetDivider()
                            NumberInputRow(
                                title = "自动勾选数量",
                                summary = "最少 1 人，不设上限",
                                value = autoSelectCount
                            ) { next ->
                                val count = next.toIntOrNull()?.coerceAtLeast(1)
                                autoSelectCount = count?.toString().orEmpty()
                                count?.let {
                                    sp.edit().putInt(
                                        MomentsFakeInteractionSettings.KEY_FAKE_LIKE_AUTO_SELECT_COUNT,
                                        it
                                    ).apply()
                                }
                            }
                        }
                        InsetDivider()
                        ActionRow(
                            title = "设置排除名单",
                            summary = if (excludedIds.isEmpty()) "未设置" else "已排除 ${excludedIds.size} 人",
                            onClick = {
                                showMomentsFakeLikeExclusionPicker(
                                    context = context,
                                    includeNonFriends = useNonFriends,
                                    selectedIds = excludedIds
                                ) { selected ->
                                    excludedIds = selected
                                    sp.edit().putStringSet(
                                        MomentsFakeInteractionSettings.KEY_FAKE_LIKE_EXCLUDED_IDS,
                                        selected
                                    ).commit()
                                }
                            }
                        )
                    }
                }
            }
            if (enabled && !isLike && !isForward) {
                item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "评论人范围") }
                item {
                    SettingsCard {
                        SwitchRow(
                            checked = useNonFriends,
                            title = "使用非好友",
                            summary = "仅在伪评论选择器中额外加入群成员"
                        ) {
                            useNonFriends = it
                            sp.edit().putBoolean(useNonFriendsKey, it).commit()
                        }
                        InsetDivider()
                        SwitchRow(
                            checked = randomCommentContent,
                            title = "随机评论",
                            summary = if (commentContents.isEmpty()) {
                                "评论池为空时仍使用手动输入"
                            } else {
                                "为每位选中的好友从 ${commentContents.size} 条评论中随机选择"
                            }
                        ) {
                            randomCommentContent = it
                            sp.edit().putBoolean(
                                MomentsFakeInteractionSettings.KEY_FAKE_COMMENT_RANDOM_CONTENT,
                                it
                            ).commit()
                        }
                        InsetDivider()
                        ActionRow(
                            title = "添加新评论",
                            summary = if (commentContents.isEmpty()) "尚未添加" else "当前 ${commentContents.size} 条"
                        ) {
                            showFakeCommentContentEditor(context, "") { content ->
                                val next = commentContents + content
                                if (MomentsFakeInteractionSettings.saveCommentContents(sp, next)) {
                                    commentContents = next
                                }
                            }
                        }
                        commentContents.forEachIndexed { index, content ->
                            InsetDivider()
                            ActionRow(
                                title = "评论 ${index + 1}",
                                summary = content
                            ) {
                                showFakeCommentContentActions(
                                    context = context,
                                    content = content,
                                    onEdit = { edited ->
                                        val next = commentContents.toMutableList().also { it[index] = edited }
                                        if (MomentsFakeInteractionSettings.saveCommentContents(sp, next)) {
                                            commentContents = next
                                        }
                                    },
                                    onDelete = {
                                        val next = commentContents.toMutableList().also { it.removeAt(index) }
                                        if (MomentsFakeInteractionSettings.saveCommentContents(sp, next)) {
                                            commentContents = next
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
            if (enabled) {
                item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "长按菜单") }
                item {
                    SettingsCard {
                        SwitchRow(
                            checked = hideMenu,
                            title = "隐藏长按菜单",
                            summary = when {
                                isLike -> "隐藏伪集赞入口，已伪造的点赞仍然显示"
                                isForward -> "隐藏伪转发入口，已保存的本地朋友圈仍然显示"
                                else -> "隐藏伪评论入口，已伪造的评论仍然显示"
                            }
                        ) {
                            hideMenu = it
                            sp.edit().putBoolean(hideMenuKey, it).commit()
                        }
                        if (!hideMenu) {
                            InsetDivider()
                            InputRow(
                                title = when {
                                    isLike -> "伪集赞自定义文本"
                                    isForward -> "伪转发自定义文本"
                                    else -> "伪评论自定义文本"
                                },
                                summary = "完整替换朋友圈长按菜单文字",
                                value = menuText,
                                onValueChange = { next ->
                                    menuText = next
                                    sp.edit().putString(menuTextKey, next).apply()
                                }
                            )
                        }
                    }
                }
            }
            if (isForward) {
                item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "诊断") }
                item {
                    SettingsCard {
                        SwitchRow(
                            checked = forwardDebugLog,
                            title = "调试日志",
                            summary = "输出伪转发创建、分页、刷新和清理诊断到 LSPosed 日志"
                        ) {
                            if (sp.edit().putBoolean(
                                    MomentsFakeInteractionSettings.KEY_FAKE_FORWARD_DEBUG_LOG,
                                    it
                                ).commit()
                            ) {
                                forwardDebugLog = it
                            }
                        }
                    }
                }
                item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "操作") }
                item {
                    SettingsCard {
                        ActionRow(
                            title = "清空伪转发",
                            summary = "删除全部已登记的本地伪转发，不影响真实朋友圈",
                            onClick = {
                                confirmClearMomentsFakeForwards(context) {
                                    val scheduled = MomentsFakeForwardRuntimeRegistry.clearAll { success ->
                                        Toast.makeText(
                                            context,
                                            if (success) "已清空伪转发" else "部分记录删除失败，请稍后重试",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                    Toast.makeText(
                                        context,
                                        if (scheduled) "正在清理..." else "微信运行时尚未就绪",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        )
                    }
                }
            } else {
                item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "操作") }
                item {
                    SettingsCard {
                        ActionRow(
                            title = if (isLike) "清除伪集赞" else "清除伪评论",
                        summary = if (isLike) {
                            "清空已保存的本地点赞，保留当前功能设置"
                        } else {
                            "清空已保存的本地评论，保留当前功能设置"
                        },
                        onClick = {
                            confirmClearMomentsFakeInteractions(context, isLike) {
                                scheduleMomentsFakeInteractionRestore(context, sp, isLike)
                            }
                        }
                    )
                    InsetDivider()
                    ActionRow(
                        title = "恢复默认",
                        summary = if (isLike) {
                            "关闭伪集赞并清空已保存的本地点赞"
                        } else {
                            "关闭伪评论并清空已保存的本地评论"
                        },
                        onClick = {
                            val editor = sp.edit()
                                .putBoolean(enableKey, defaultEnabled)
                                .putBoolean(pendingRestoreKey, true)
                            if (isLike) {
                                editor
                                    .remove(MomentsFakeInteractionSettings.KEY_FAKE_LIKE_USE_NON_FRIENDS)
                                    .remove(MomentsFakeInteractionSettings.KEY_FAKE_LIKE_RANDOM_ORDER)
                                    .remove(MomentsFakeInteractionSettings.KEY_FAKE_LIKE_AUTO_SELECT)
                                    .remove(MomentsFakeInteractionSettings.KEY_FAKE_LIKE_AUTO_SELECT_COUNT)
                                    .remove(MomentsFakeInteractionSettings.KEY_FAKE_LIKE_EXCLUDED_IDS)
                                    .remove(MomentsFakeInteractionSettings.KEY_FAKE_LIKE_HIDE_MENU)
                                    .remove(MomentsFakeInteractionSettings.KEY_FAKE_LIKE_MENU_TEXT)
                            } else {
                                editor
                                    .remove(MomentsFakeInteractionSettings.KEY_FAKE_COMMENT_USE_NON_FRIENDS)
                                    .remove(MomentsFakeInteractionSettings.KEY_FAKE_COMMENT_HIDE_MENU)
                                    .remove(MomentsFakeInteractionSettings.KEY_FAKE_COMMENT_MENU_TEXT)
                                    .remove(MomentsFakeInteractionSettings.KEY_FAKE_COMMENT_RANDOM_CONTENT)
                                    .remove(MomentsFakeInteractionSettings.KEY_FAKE_COMMENT_CONTENTS)
                            }
                            val settingsRestored = editor.commit()
                            if (!settingsRestored) {
                                Toast.makeText(context, "恢复默认失败", Toast.LENGTH_SHORT).show()
                            } else {
                                enabled = defaultEnabled
                                hideMenu = defaultHideMenu
                                menuText = defaultMenuText
                                useNonFriends = defaultUseNonFriends
                                randomCommentContent = MomentsFakeInteractionSettings.DEFAULT_FAKE_COMMENT_RANDOM_CONTENT
                                commentContents = emptyList()
                                if (isLike) {
                                    randomOrder = MomentsFakeInteractionSettings.DEFAULT_FAKE_LIKE_RANDOM_ORDER
                                    autoSelect = MomentsFakeInteractionSettings.DEFAULT_FAKE_LIKE_AUTO_SELECT
                                    autoSelectCount = MomentsFakeInteractionSettings.DEFAULT_FAKE_LIKE_AUTO_SELECT_COUNT.toString()
                                    excludedIds = emptySet()
                                }
                                scheduleMomentsFakeInteractionRestore(context, sp, isLike, "已恢复默认")
                            }
                        }
                    )
                }
            }
            }
        }
    }
}

internal fun confirmClearMomentsFakeForwards(
    context: Context,
    onConfirmed: () -> Unit
) {
    val activity = context as? Activity
        ?: WeChatApis.currentActivity()?.currentActivity() as? Activity
        ?: return
    VoiceForwardMiuixDialog.showConfirm(
        activity = activity,
        title = "清空伪转发",
        message = "删除全部已登记的本地伪转发？真实朋友圈不会受影响。",
        onResult = { if (it) onConfirmed() },
        onDismiss = {}
    )
}

internal fun showMomentsFakeLikeExclusionPicker(
    context: Context,
    includeNonFriends: Boolean,
    selectedIds: Set<String>,
    onConfirm: (Set<String>) -> Unit
) {
    val activity = context as? Activity
        ?: WeChatApis.currentActivity()?.currentActivity() as? Activity
        ?: return
    val loading = VoiceForwardMiuixDialog.showLoading(
        activity = activity,
        title = "设置排除名单",
        message = if (includeNonFriends) "正在载入好友和非好友..." else "正在载入好友列表...",
        onDismiss = {}
    )
    Thread({
        val contacts = runCatching {
            MomentsFakeLikeCandidateRepository.loadForLikes(includeNonFriends)
        }.getOrDefault(emptyList())
        activity.runOnUiThread {
            loading.close()
            activity.window?.decorView?.postOnAnimation {
                if (activity.isFinishing || activity.isDestroyed) return@postOnAnimation
                if (contacts.isEmpty()) {
                    Toast.makeText(activity, "没有可选择的联系人", Toast.LENGTH_SHORT).show()
                    return@postOnAnimation
                }
                val visibleIds = contacts.mapTo(hashSetOf()) { it.id }
                val hiddenSelectedIds = selectedIds.filterNotTo(linkedSetOf()) {
                    visibleIds.contains(it)
                }
                VoiceForwardMiuixDialog.showContacts(
                    activity = activity,
                    contacts = contacts,
                    title = "设置排除名单",
                    confirmText = "保存",
                    showGroupFilter = false,
                    initialSelectedIds = selectedIds,
                    allowEmpty = true,
                    showClearSelectionAction = true,
                    onConfirm = { selected ->
                        onConfirm(hiddenSelectedIds + selected.mapTo(linkedSetOf()) { it.id })
                    },
                    onDismiss = {}
                )
            }
        }
    }, "Hchat-MomentsFakeLikeExclude").apply { isDaemon = true }.start()
}

internal fun confirmClearMomentsFakeInteractions(
    context: Context,
    isLike: Boolean,
    onConfirmed: () -> Unit
) {
    val activity = context as? Activity
        ?: WeChatApis.currentActivity()?.currentActivity() as? Activity
        ?: return
    VoiceForwardMiuixDialog.showConfirm(
        activity = activity,
        title = if (isLike) "清除伪集赞" else "清除伪评论",
        message = if (isLike) "清空全部朋友圈已保存的本地点赞？" else "清空全部朋友圈已保存的本地评论？",
        onResult = { if (it) onConfirmed() },
        onDismiss = {}
    )
}

internal fun showFakeCommentContentEditor(
    context: Context,
    initialValue: String,
    onSaved: (String) -> Unit
) {
    val activity = context as? Activity
        ?: WeChatApis.currentActivity()?.currentActivity() as? Activity
        ?: return
    VoiceForwardMiuixDialog.showTextInput(
        activity = activity,
        title = if (initialValue.isBlank()) "添加新评论" else "修改评论内容",
        summary = "保存后可用于随机评论",
        initialValue = initialValue,
        placeholder = "请输入评论内容",
        maxLength = 1000,
        singleLine = false,
        onConfirm = onSaved,
        onDismiss = {}
    )
}

internal fun showFakeCommentContentActions(
    context: Context,
    content: String,
    onEdit: (String) -> Unit,
    onDelete: () -> Unit
) {
    val activity = context as? Activity
        ?: WeChatApis.currentActivity()?.currentActivity() as? Activity
        ?: return
    VoiceForwardMiuixDialog.showChoices(
        activity = activity,
        title = "评论内容",
        summary = content,
        choices = listOf(
            "修改" to "编辑这条随机评论内容",
            "删除" to "从评论池移除这条内容"
        ),
        onSelected = { index ->
            if (index == 0) {
                showFakeCommentContentEditor(context, content, onEdit)
            } else {
                VoiceForwardMiuixDialog.showConfirm(
                    activity = activity,
                    title = "删除评论内容",
                    message = "从随机评论池删除这条内容？",
                    onResult = { if (it) onDelete() },
                    onDismiss = {}
                )
            }
        },
        onDismiss = {}
    )
}

internal fun scheduleMomentsFakeInteractionRestore(
    context: Context,
    sp: SharedPreferences,
    isLike: Boolean,
    successText: String = "已清除"
) {
    val pendingKey = if (isLike) {
        MomentsFakeInteractionSettings.KEY_PENDING_RESTORE_LIKES
    } else {
        MomentsFakeInteractionSettings.KEY_PENDING_RESTORE_COMMENTS
    }
    if (!sp.edit().putBoolean(pendingKey, true).commit()) {
        Toast.makeText(context, "保存清理状态失败", Toast.LENGTH_SHORT).show()
        return
    }
    val onRestored: (Boolean) -> Unit = { restored ->
        Toast.makeText(
            context,
            if (restored) successText else "缓存将在下次启动时继续清理",
            Toast.LENGTH_SHORT
        ).show()
    }
    val scheduled = if (isLike) {
        MomentsFakeInteractionRuntimeRegistry.restoreLikes(onRestored)
    } else {
        MomentsFakeInteractionRuntimeRegistry.restoreComments(onRestored)
    }
    Toast.makeText(
        context,
        if (scheduled) "正在清理..." else "缓存将在下次启动时清理",
        Toast.LENGTH_SHORT
    ).show()
}

@Composable
internal fun RemoveMomentsAdsMiuixPage(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit
) {
    val sp = remember { HchatStorage.preferences(context, RemoveMomentsAdsSettings.PREFS_NAME) }
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()

    PageScaffold(
        title = provider.title(),
        largeTitle = provider.title(),
        scrollBehavior = scrollBehavior,
        bottomBar = {
            BottomActionBar("返回", onBack)
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "朋友圈") }
            item {
                SettingsCard {
                    SwitchRow(
                        sp,
                        RemoveMomentsAdsSettings.KEY_ENABLE,
                        "去除朋友圈广告",
                        "阻止朋友圈广告信息解析和展示",
                        RemoveMomentsAdsSettings.DEFAULT_ENABLE
                    )
                }
            }
        }
    }
}

@Composable
internal fun FinderMediaDownloadMiuixPage(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit
) {
    val sp = remember { HchatStorage.preferences(context, FinderMediaDownloadSettings.PREFS_NAME) }
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()

    PageScaffold(
        title = provider.title(),
        largeTitle = provider.title(),
        scrollBehavior = scrollBehavior,
        bottomBar = { BottomActionBar("返回", onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "视频号") }
            item {
                SettingsCard {
                    SwitchRow(
                        sp,
                        FinderMediaDownloadSettings.KEY_ENABLE,
                        "视频号媒体下载",
                        "在视频号分享菜单增加复制链接和下载入口，媒体保存到 Hchat/Finder",
                        FinderMediaDownloadSettings.DEFAULT_ENABLE
                    )
                }
            }
        }
    }
}

@Composable
internal fun HchatExtraToggleMiuixPage(
    context: Context,
    provider: FeatureSettingsProvider,
    key: String,
    defaultValue: Boolean,
    summary: String,
    onBack: () -> Unit
) {
    val sp = remember { HchatStorage.preferences(context, HchatExtraSettings.PREFS_NAME) }
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()

    PageScaffold(
        title = provider.title(),
        largeTitle = provider.title(),
        scrollBehavior = scrollBehavior,
        bottomBar = { BottomActionBar("返回", onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "功能开关") }
            item {
                SettingsCard {
                    SwitchRow(
                        sp,
                        key,
                        provider.title(),
                        summary,
                        defaultValue
                    )
                }
            }
        }
    }
}

@Composable
internal fun MessageDetailsConfigPage(
    context: Context,
    sp: SharedPreferences,
    onBack: () -> Unit
) {
    var enabled by remember {
        mutableStateOf(
            sp.getBoolean(
                HchatExtraSettings.KEY_MESSAGE_DETAILS,
                HchatExtraSettings.DEFAULT_MESSAGE_DETAILS
            )
        )
    }
    var marginLeft by remember {
        mutableStateOf(
            sp.getInt(
                HchatExtraSettings.KEY_MESSAGE_DETAILS_MARGIN_LEFT,
                HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_MARGIN_HORIZONTAL
            ).toString()
        )
    }
    var marginTop by remember {
        mutableStateOf(
            sp.getInt(
                HchatExtraSettings.KEY_MESSAGE_DETAILS_MARGIN_TOP,
                HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_MARGIN_VERTICAL
            ).toString()
        )
    }
    var marginRight by remember {
        mutableStateOf(
            sp.getInt(
                HchatExtraSettings.KEY_MESSAGE_DETAILS_MARGIN_RIGHT,
                HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_MARGIN_HORIZONTAL
            ).toString()
        )
    }
    var marginBottom by remember {
        mutableStateOf(
            sp.getInt(
                HchatExtraSettings.KEY_MESSAGE_DETAILS_MARGIN_BOTTOM,
                HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_MARGIN_VERTICAL
            ).toString()
        )
    }
    var lightBg by remember {
        mutableStateOf(
            sp.getString(
                HchatExtraSettings.KEY_MESSAGE_DETAILS_LIGHT_BG,
                HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_LIGHT_BG
            ).orEmpty()
        )
    }
    var lightText by remember {
        mutableStateOf(
            sp.getString(
                HchatExtraSettings.KEY_MESSAGE_DETAILS_LIGHT_TEXT,
                HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_LIGHT_TEXT
            ).orEmpty()
        )
    }
    var darkBg by remember {
        mutableStateOf(
            sp.getString(
                HchatExtraSettings.KEY_MESSAGE_DETAILS_DARK_BG,
                HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_DARK_BG
            ).orEmpty()
        )
    }
    var darkText by remember {
        mutableStateOf(
            sp.getString(
                HchatExtraSettings.KEY_MESSAGE_DETAILS_DARK_TEXT,
                HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_DARK_TEXT
            ).orEmpty()
        )
    }
    var format by remember {
        mutableStateOf(
            sp.getString(
                HchatExtraSettings.KEY_MESSAGE_DETAILS_FORMAT,
                HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_FORMAT
            ).orEmpty()
        )
    }
    var timeFormat by remember {
        mutableStateOf(
            sp.getString(
                HchatExtraSettings.KEY_MESSAGE_DETAILS_TIME_FORMAT,
                HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_TIME_FORMAT
            ).orEmpty()
        )
    }
    var textSize by remember {
        mutableStateOf(
            sp.getInt(
                HchatExtraSettings.KEY_MESSAGE_DETAILS_TEXT_SIZE,
                HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_TEXT_SIZE
            ).toString()
        )
    }
    var formatContent by remember {
        mutableStateOf(
            sp.getBoolean(
                HchatExtraSettings.KEY_MESSAGE_DETAILS_FORMAT_CONTENT,
                HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_FORMAT_CONTENT
            )
        )
    }

    fun resetValues() {
        enabled = HchatExtraSettings.DEFAULT_MESSAGE_DETAILS
        marginLeft = HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_MARGIN_HORIZONTAL.toString()
        marginTop = HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_MARGIN_VERTICAL.toString()
        marginRight = HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_MARGIN_HORIZONTAL.toString()
        marginBottom = HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_MARGIN_VERTICAL.toString()
        lightBg = HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_LIGHT_BG
        lightText = HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_LIGHT_TEXT
        darkBg = HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_DARK_BG
        darkText = HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_DARK_TEXT
        format = HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_FORMAT
        timeFormat = HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_TIME_FORMAT
        textSize = HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_TEXT_SIZE.toString()
        formatContent = HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_FORMAT_CONTENT
    }

    fun saveValues() {
        val savedLightBg = cleanMessageDetailsColor(lightBg, HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_LIGHT_BG)
        val savedLightText = cleanMessageDetailsColor(lightText, HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_LIGHT_TEXT)
        val savedDarkBg = cleanMessageDetailsColor(darkBg, HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_DARK_BG)
        val savedDarkText = cleanMessageDetailsColor(darkText, HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_DARK_TEXT)
        sp.edit()
            .putBoolean(HchatExtraSettings.KEY_MESSAGE_DETAILS, enabled)
            .putInt(
                HchatExtraSettings.KEY_MESSAGE_DETAILS_MARGIN_LEFT,
                marginLeft.toIntOrNull()?.coerceIn(
                    HchatExtraSettings.MESSAGE_DETAILS_MARGIN_MIN,
                    HchatExtraSettings.MESSAGE_DETAILS_MARGIN_MAX
                ) ?: HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_MARGIN_HORIZONTAL
            )
            .putInt(
                HchatExtraSettings.KEY_MESSAGE_DETAILS_MARGIN_TOP,
                marginTop.toIntOrNull()?.coerceIn(
                    HchatExtraSettings.MESSAGE_DETAILS_MARGIN_MIN,
                    HchatExtraSettings.MESSAGE_DETAILS_MARGIN_MAX
                ) ?: HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_MARGIN_VERTICAL
            )
            .putInt(
                HchatExtraSettings.KEY_MESSAGE_DETAILS_MARGIN_RIGHT,
                marginRight.toIntOrNull()?.coerceIn(
                    HchatExtraSettings.MESSAGE_DETAILS_MARGIN_MIN,
                    HchatExtraSettings.MESSAGE_DETAILS_MARGIN_MAX
                ) ?: HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_MARGIN_HORIZONTAL
            )
            .putInt(
                HchatExtraSettings.KEY_MESSAGE_DETAILS_MARGIN_BOTTOM,
                marginBottom.toIntOrNull()?.coerceIn(
                    HchatExtraSettings.MESSAGE_DETAILS_MARGIN_MIN,
                    HchatExtraSettings.MESSAGE_DETAILS_MARGIN_MAX
                ) ?: HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_MARGIN_VERTICAL
            )
            .putString(HchatExtraSettings.KEY_MESSAGE_DETAILS_LIGHT_BG, savedLightBg)
            .putString(HchatExtraSettings.KEY_MESSAGE_DETAILS_LIGHT_TEXT, savedLightText)
            .putString(HchatExtraSettings.KEY_MESSAGE_DETAILS_DARK_BG, savedDarkBg)
            .putString(HchatExtraSettings.KEY_MESSAGE_DETAILS_DARK_TEXT, savedDarkText)
            .putString(HchatExtraSettings.KEY_MESSAGE_DETAILS_FORMAT, format.ifBlank { HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_FORMAT })
            .putString(HchatExtraSettings.KEY_MESSAGE_DETAILS_TIME_FORMAT, timeFormat.ifBlank { HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_TIME_FORMAT })
            .putInt(
                HchatExtraSettings.KEY_MESSAGE_DETAILS_TEXT_SIZE,
                (textSize.toIntOrNull() ?: HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_TEXT_SIZE)
                    .coerceIn(
                        HchatExtraSettings.MESSAGE_DETAILS_TEXT_SIZE_MIN,
                        HchatExtraSettings.MESSAGE_DETAILS_TEXT_SIZE_MAX
                    )
            )
            .putBoolean(HchatExtraSettings.KEY_MESSAGE_DETAILS_FORMAT_CONTENT, formatContent)
            .apply()
        lightBg = savedLightBg
        lightText = savedLightText
        darkBg = savedDarkBg
        darkText = savedDarkText
        Toast.makeText(context, "设置已保存", Toast.LENGTH_SHORT).show()
    }

    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()
    PageScaffold(
        title = "消息显示时间",
        largeTitle = "消息显示时间",
        scrollBehavior = scrollBehavior,
        bottomBar = {
            BottomActionBar(
                primaryText = "保存设置",
                onPrimaryClick = { saveValues() },
                secondaryText = "返回",
                onSecondaryClick = onBack
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "基础") }
            item {
                SettingsCard {
                    SwitchRow(
                        checked = enabled,
                        title = "消息显示时间",
                        summary = "固定贴在头像上方，按收发方向对齐显示",
                        onCheckedChange = { enabled = it }
                    )
                    InsetDivider()
                    SwitchRow(
                        checked = formatContent,
                        title = "内容格式化",
                        summary = "打开详情时格式化 XML 内容",
                        onCheckedChange = { formatContent = it }
                    )
                }
            }
            item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "颜色") }
            item {
                SettingsCard {
                    ColorPickerRow(
                        "浅色背景",
                        "浅色模式消息时间背景",
                        lightBg,
                        allowGradient = false,
                        onReset = { lightBg = HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_LIGHT_BG }
                    ) { lightBg = it.take(9) }
                    InsetDivider()
                    ColorPickerRow(
                        "浅色文字",
                        "浅色模式消息时间文字",
                        lightText,
                        allowGradient = false,
                        onReset = { lightText = HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_LIGHT_TEXT }
                    ) { lightText = it.take(9) }
                    InsetDivider()
                    ColorPickerRow(
                        "深色背景",
                        "深色模式消息时间背景",
                        darkBg,
                        allowGradient = false,
                        onReset = { darkBg = HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_DARK_BG }
                    ) { darkBg = it.take(9) }
                    InsetDivider()
                    ColorPickerRow(
                        "深色文字",
                        "深色模式消息时间文字",
                        darkText,
                        allowGradient = false,
                        onReset = { darkText = HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_DARK_TEXT }
                    ) { darkText = it.take(9) }
                }
            }
            item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "文本") }
            item {
                SettingsCard {
                    VariableInputRow(
                        title = "文本格式",
                        summary = "显示在消息旁的内容模板",
                        value = format,
                        variables = messageDetailsVariables(),
                        onValueChange = { format = it }
                    )
                    InsetDivider()
                    PopupChoiceRow(
                        title = "时间显示",
                        summary = messageDetailsTimeFormatChoices(timeFormat).first { it.value == timeFormat }.label,
                        options = messageDetailsTimeFormatChoices(timeFormat),
                        currentValue = timeFormat,
                        onValueChanged = { timeFormat = it }
                    )
                    InsetDivider()
                    InputRow("自定义时间格式", "yyyy 年、MM 月、dd 日；例如 yyyy-MM-dd HH:mm:ss", timeFormat, onValueChange = { timeFormat = it })
                }
            }
            item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "布局") }
            item {
                SettingsCard {
                    NumberInputRow("字体大小", "单位 sp，范围 8–16", textSize, onValueChange = { textSize = it })
                    InsetDivider()
                    NumberInputRow(
                        "左间距", "范围 -6–12dp，负数向左偏移", marginLeft,
                        onValueChange = { marginLeft = it }, allowNegative = true
                    )
                    InsetDivider()
                    NumberInputRow(
                        "上间距", "范围 -6–12dp，负数向上偏移", marginTop,
                        onValueChange = { marginTop = it }, allowNegative = true
                    )
                    InsetDivider()
                    NumberInputRow(
                        "右间距", "范围 -6–12dp，负数向右偏移", marginRight,
                        onValueChange = { marginRight = it }, allowNegative = true
                    )
                    InsetDivider()
                    NumberInputRow(
                        "下间距", "与头像之间的间距，范围 -6–12dp", marginBottom,
                        onValueChange = { marginBottom = it }, allowNegative = true
                    )
                }
            }
            item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "操作") }
            item {
                SettingsCard {
                    ActionRow("恢复默认", "重置本页全部消息显示时间设置") {
                        resetValues()
                        Toast.makeText(context, "已恢复默认，保存后生效", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}

internal fun cleanMessageDetailsColor(value: String, fallback: String): String {
    return MemberTitleStore.cleanColor(value).ifEmpty { fallback }
}

internal fun messageDetailsTimeFormatChoices(current: String): List<PopupChoice<String>> {
    val choices = listOf(
        PopupChoice(label = "时分秒 · 14:30:05", value = "HH:mm:ss"),
        PopupChoice(label = "月日 时分秒 · 09-11 14:30:05", value = "MM-dd HH:mm:ss"),
        PopupChoice(label = "年月日 时分秒 · 2026-09-11 14:30:05", value = "yyyy-MM-dd HH:mm:ss"),
        PopupChoice(label = "中文年月日 · 2026年9月11日 14:30:05", value = "yyyy年M月d日 HH:mm:ss"),
        PopupChoice(label = "仅年月日 · 2026-09-11", value = "yyyy-MM-dd")
    )
    return if (choices.any { it.value == current }) choices
    else choices + PopupChoice(label = "自定义 · "+ current.ifBlank { "默认时分秒" }, value = current)
}

internal fun messageDetailsVariables(): List<TemplateVariable> {
    return listOf(
        TemplateVariable("\${time}", "时间"),
        TemplateVariable("\${relativeTime}", "相对时间"),
        TemplateVariable("\${type}", "消息类型（含文件、红包、小程序等）"),
        TemplateVariable("\${appType}", "卡片子类型编号（非卡片为空）"),
        TemplateVariable("\${baseType}", "基础消息类型编号"),
        TemplateVariable("\${direction}", "收发方向"),
        TemplateVariable("\${talker}", "会话 ID"),
        TemplateVariable("\${createTime}", "消息时间戳（毫秒）"),
        TemplateVariable("\${rawAtUserList}", "原始艾特名单"),
        TemplateVariable("\${typeDec}", "类型编号（十进制）"),
        TemplateVariable("\${typeHex}", "类型编号（十六进制）"),
        TemplateVariable("\${msgId}", "本地消息编号"),
        TemplateVariable("\${msgSvrId}", "服务端消息编号"),
        TemplateVariable("\${atUserList}", "艾特对象"),
        TemplateVariable("\${mentionedUsers}", "提及摘要")
    )
}

@Composable
internal fun ProfileIdMiuixPage(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit
) {
    val sp = remember { HchatStorage.preferences(context, ProfileIdSettings.PREFS_NAME) }
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()

    PageScaffold(
        title = provider.title(),
        largeTitle = provider.title(),
        scrollBehavior = scrollBehavior,
        bottomBar = {
            BottomActionBar("返回", onBack)
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "资料页") }
            item {
                SettingsCard {
                    SwitchRow(
                        sp,
                        ProfileIdSettings.KEY_ENABLE,
                        "显示好友/群聊ID",
                        "在好友和群聊资料页显示可点击复制的 ID",
                        ProfileIdSettings.DEFAULT_ENABLE
                    )
                }
            }
        }
    }
}

@Composable
internal fun HideChatMenuMiuixPage(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit
) {
    val sp = remember { HideChatMenuSettings.preferences(context) }
    var enabled by remember {
        mutableStateOf(
            sp.getBoolean(HideChatMenuSettings.KEY_ENABLE, HideChatMenuSettings.DEFAULT_ENABLE)
        )
    }
    var titles by remember {
        mutableStateOf(
            sp.getString(HideChatMenuSettings.KEY_TITLES, HideChatMenuSettings.DEFAULT_TITLES)
                .orEmpty()
        )
    }
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()

    fun save() {
        val normalized = HideChatMenuSettings.parseTitles(titles).joinToString(",")
        titles = normalized
        sp.edit()
            .putBoolean(HideChatMenuSettings.KEY_ENABLE, enabled)
            .putString(HideChatMenuSettings.KEY_TITLES, normalized)
            .apply()
        Toast.makeText(context, "设置已保存", Toast.LENGTH_SHORT).show()
    }

    PageScaffold(
        title = provider.title(),
        largeTitle = provider.title(),
        scrollBehavior = scrollBehavior,
        bottomBar = {
            BottomActionBar(
                primaryText = "保存设置",
                onPrimaryClick = { save() },
                secondaryText = "返回",
                onSecondaryClick = onBack
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "聊天菜单") }
            item {
                SettingsCard {
                    SwitchRow(
                        checked = enabled,
                        title = "隐藏长按菜单",
                        summary = "隐藏聊天消息长按菜单中的指定项目",
                        onCheckedChange = { enabled = it }
                    )
                    if (enabled) {
                        InsetDivider()
                        InputRow(
                            "隐藏菜单项",
                            "输入菜单显示名称，多个项目用逗号、分号或换行分隔",
                            titles,
                            minLines = 2,
                            onValueChange = { titles = it }
                        )
                    }
                }
            }
            item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "操作") }
            item {
                SettingsCard {
                    ActionRow("恢复默认", "恢复初始菜单名称示例") {
                        titles = HideChatMenuSettings.DEFAULT_TITLES
                        Toast.makeText(context, "已恢复默认，保存后生效", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}

@Composable
internal fun SettingsEntryMiuixPage(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit
) {
    val sp = remember { HchatStorage.preferences(context, SettingsEntrySettings.PREFS_NAME) }
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()

    PageScaffold(
        title = provider.title(),
        largeTitle = provider.title(),
        scrollBehavior = scrollBehavior,
        bottomBar = {
            BottomActionBar("返回", onBack)
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "入口") }
            item {
                SettingsCard {
                    SwitchRow(
                        sp,
                        SettingsEntrySettings.KEY_PLUS_MENU_ENABLE,
                        "注入加号菜单",
                        "在微信右上角加号菜单中显示 Hchat 入口，重启微信后生效",
                        SettingsEntrySettings.DEFAULT_PLUS_MENU_ENABLE
                    )
                    InsetDivider()
                    SwitchRow(
                        sp,
                        SettingsEntrySettings.KEY_PLUS_LONG_PRESS_ENABLE,
                        "长按加号入口",
                        "长按微信右上角加号打开 Hchat 设置，重启微信后生效",
                        SettingsEntrySettings.DEFAULT_PLUS_LONG_PRESS_ENABLE
                    )
                }
            }
        }
    }
}


internal data class FloatingShortcutEditorRequest(
    val original: FloatingShortcutItem?,
    val draft: FloatingShortcutItem
)

internal enum class FloatingShortcutRoute {
    MAIN,
    APPEARANCE
}

@Composable
internal fun FloatingShortcutMiuixPage(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit
) {
    val sp = remember { HchatStorage.preferences(context, FloatingShortcutSettings.PREFS_NAME) }
    var enabled by remember { mutableStateOf(FloatingShortcutRuntime.isEnabled(context)) }
    var scope by remember {
        mutableStateOf(
            sp.getString(FloatingShortcutSettings.KEY_SCOPE, FloatingShortcutSettings.DEFAULT_SCOPE)
                ?: FloatingShortcutSettings.DEFAULT_SCOPE
        )
    }
    var displayMode by remember {
        mutableStateOf(
            sp.getString(
                FloatingShortcutSettings.KEY_DISPLAY_MODE,
                FloatingShortcutSettings.DEFAULT_DISPLAY_MODE
            ) ?: FloatingShortcutSettings.DEFAULT_DISPLAY_MODE
        )
    }
    var expandDirection by remember {
        mutableStateOf(
            sp.getString(
                FloatingShortcutSettings.KEY_EXPAND_DIRECTION,
                FloatingShortcutSettings.DEFAULT_EXPAND_DIRECTION
            ) ?: FloatingShortcutSettings.DEFAULT_EXPAND_DIRECTION
        )
    }
    var bubbleIcon by remember {
        mutableStateOf(sp.getString(FloatingShortcutSettings.KEY_BUBBLE_ICON, "").orEmpty())
    }
    var bubbleDarkIcon by remember {
        mutableStateOf(sp.getString(FloatingShortcutSettings.KEY_BUBBLE_DARK_ICON, "").orEmpty())
    }
    var shortcuts by remember { mutableStateOf(FloatingShortcutSettings.loadItems(context)) }
    var editor by remember { mutableStateOf<FloatingShortcutEditorRequest?>(null) }
    var route by remember { mutableStateOf(FloatingShortcutRoute.MAIN) }
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()

    fun saveShortcuts(value: List<FloatingShortcutItem>) {
        shortcuts = value
        FloatingShortcutSettings.saveItems(context, value)
    }

    fun pickIcon(key: String, onSaved: (String) -> Unit) {
        val activity = (context as? Activity) ?: WeChatApis.currentActivity()?.currentActivity()
        if (activity == null) {
            Toast.makeText(context, "当前页面无法打开图片选择器", Toast.LENGTH_SHORT).show()
            return
        }
        FloatingShortcutIconPicker.launch(activity, key) { result ->
            when (result) {
                is FloatingShortcutIconPickResult.Saved -> onSaved(result.path)
                FloatingShortcutIconPickResult.FAILED ->
                    Toast.makeText(context, "图标读取失败", Toast.LENGTH_SHORT).show()
                FloatingShortcutIconPickResult.CANCELLED -> Unit
            }
        }
    }

    fun dismissEditor() {
        val request = editor ?: return
        if (request.draft.iconPath != request.original?.iconPath) {
            FloatingShortcutIconStore.delete(context, request.draft.iconPath)
        }
        if (request.draft.darkIconPath != request.original?.darkIconPath) {
            FloatingShortcutIconStore.delete(context, request.draft.darkIconPath)
        }
        editor = null
    }

    SettingsRouteTransition(
        targetState = route,
        label = "FloatingShortcutRoute",
        depthOf = { if (it == FloatingShortcutRoute.MAIN) 0 else 1 }
    ) { currentRoute ->
        when (currentRoute) {
            FloatingShortcutRoute.MAIN -> PageScaffold(
        title = provider.title(),
        largeTitle = provider.title(),
        scrollBehavior = scrollBehavior,
        bottomBar = { BottomActionBar("返回", onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "悬浮入口") }
            item {
                SettingsCard {
                    SwitchRow(
                        checked = enabled,
                        title = "悬浮快捷菜单",
                        summary = "在微信页面显示可自由拖动和停放的快捷入口",
                        onCheckedChange = {
                            enabled = it
                            FloatingShortcutRuntime.setEnabled(context, it)
                        }
                    )
                }
            }
            if (enabled) {
                item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "显示方式") }
                item {
                    SettingsCard {
                        PopupChoiceRow(
                            title = "显示范围",
                            summary = if (scope == FloatingShortcutSettings.SCOPE_ALL) {
                                "微信所有页面"
                            } else {
                                "仅微信主页"
                            },
                            options = listOf(
                                PopupChoice("仅微信主页", FloatingShortcutSettings.SCOPE_HOME),
                                PopupChoice("所有微信页面", FloatingShortcutSettings.SCOPE_ALL)
                            ),
                            currentValue = scope,
                            onValueChanged = {
                                scope = it
                                sp.edit().putString(FloatingShortcutSettings.KEY_SCOPE, it).apply()
                            }
                        )
                        InsetDivider()
                        PopupChoiceRow(
                            title = "快捷项样式",
                            summary = when (displayMode) {
                                FloatingShortcutSettings.DISPLAY_TEXT -> "仅文字"
                                FloatingShortcutSettings.DISPLAY_BOTH -> "图标和文字"
                                else -> "仅图标"
                            },
                            options = listOf(
                                PopupChoice("仅图标", FloatingShortcutSettings.DISPLAY_ICON),
                                PopupChoice("仅文字", FloatingShortcutSettings.DISPLAY_TEXT),
                                PopupChoice("图标和文字", FloatingShortcutSettings.DISPLAY_BOTH)
                            ),
                            currentValue = displayMode,
                            onValueChanged = {
                                displayMode = it
                                sp.edit().putString(FloatingShortcutSettings.KEY_DISPLAY_MODE, it).apply()
                            }
                        )
                        InsetDivider()
                        PopupChoiceRow(
                            title = "展开方向",
                            summary = when (expandDirection) {
                                FloatingShortcutSettings.EXPAND_DOWN -> "向下展开"
                                FloatingShortcutSettings.EXPAND_LEFT -> "向左展开"
                                FloatingShortcutSettings.EXPAND_RIGHT -> "向右展开"
                                else -> "向上展开"
                            },
                            options = listOf(
                                PopupChoice("向上展开", FloatingShortcutSettings.EXPAND_UP),
                                PopupChoice("向下展开", FloatingShortcutSettings.EXPAND_DOWN),
                                PopupChoice("向左展开", FloatingShortcutSettings.EXPAND_LEFT),
                                PopupChoice("向右展开", FloatingShortcutSettings.EXPAND_RIGHT)
                            ),
                            currentValue = expandDirection,
                            onValueChanged = {
                                expandDirection = it
                                sp.edit().putString(FloatingShortcutSettings.KEY_EXPAND_DIRECTION, it).apply()
                            }
                        )
                        InsetDivider()
                        ActionRow(
                            title = "按钮外观",
                            summary = "设置按钮渐变、大小和菜单名称样式",
                            onClick = { route = FloatingShortcutRoute.APPEARANCE }
                        )
                    }
                }
                item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "主按钮图标") }
                item {
                    SettingsCard {
                        FloatingShortcutIconActionRow(
                            title = "浅色模式图标",
                            iconPath = bubbleIcon,
                            defaultSummary = "未选择时使用内置快捷菜单图标",
                            onSelect = {
                                pickIcon("bubble-${System.nanoTime()}") { path ->
                                    val previous = bubbleIcon
                                    bubbleIcon = path
                                    sp.edit().putString(FloatingShortcutSettings.KEY_BUBBLE_ICON, path).apply()
                                    FloatingShortcutIconStore.delete(context, previous)
                                }
                            },
                            onReset = if (bubbleIcon.isBlank()) null else {
                                {
                                    val previous = bubbleIcon
                                    bubbleIcon = ""
                                    sp.edit().remove(FloatingShortcutSettings.KEY_BUBBLE_ICON).apply()
                                    FloatingShortcutIconStore.delete(context, previous)
                                }
                            }
                        )
                        InsetDivider()
                        FloatingShortcutIconActionRow(
                            title = "深色模式图标",
                            iconPath = bubbleDarkIcon,
                            defaultSummary = "未选择时沿用浅色模式图标",
                            onSelect = {
                                pickIcon("bubble-dark-${System.nanoTime()}") { path ->
                                    val previous = bubbleDarkIcon
                                    bubbleDarkIcon = path
                                    sp.edit().putString(FloatingShortcutSettings.KEY_BUBBLE_DARK_ICON, path).apply()
                                    FloatingShortcutIconStore.delete(context, previous)
                                }
                            },
                            onReset = if (bubbleDarkIcon.isBlank()) null else {
                                {
                                    val previous = bubbleDarkIcon
                                    bubbleDarkIcon = ""
                                    sp.edit().remove(FloatingShortcutSettings.KEY_BUBBLE_DARK_ICON).apply()
                                    FloatingShortcutIconStore.delete(context, previous)
                                }
                            }
                        )
                    }
                }
                item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "快捷项") }
                item {
                    SettingsCard {
                        if (shortcuts.isEmpty()) {
                            Text(
                                text = "暂无快捷项",
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
                                textAlign = TextAlign.Center
                            )
                        } else {
                            shortcuts.forEachIndexed { index, item ->
                                if (index > 0) InsetDivider()
                                FloatingShortcutSettingsItemRow(
                                    item = item,
                                    canMoveUp = index > 0,
                                    canMoveDown = index < shortcuts.lastIndex,
                                    onEnabledChange = { checked ->
                                        saveShortcuts(shortcuts.toMutableList().apply {
                                            this[index] = item.copy(enabled = checked)
                                        })
                                    },
                                    onMoveUp = {
                                        if (index > 0) {
                                            saveShortcuts(shortcuts.toMutableList().apply {
                                                val moved = removeAt(index)
                                                add(index - 1, moved)
                                            })
                                        }
                                    },
                                    onMoveDown = {
                                        if (index < shortcuts.lastIndex) {
                                            saveShortcuts(shortcuts.toMutableList().apply {
                                                val moved = removeAt(index)
                                                add(index + 1, moved)
                                            })
                                        }
                                    },
                                    onEdit = { editor = FloatingShortcutEditorRequest(item, item) },
                                    onDelete = {
                                        FloatingShortcutIconStore.delete(context, item.iconPath)
                                        FloatingShortcutIconStore.delete(context, item.darkIconPath)
                                        saveShortcuts(shortcuts.filterNot { it.id == item.id })
                                    }
                                )
                            }
                        }
                        InsetDivider()
                        ActionRow(
                            title = "新增快捷项",
                            summary = "添加微信页面、模块设置或插件 Agent 入口",
                            onClick = {
                                editor = FloatingShortcutEditorRequest(
                                    original = null,
                                    draft = FloatingShortcutItem(
                                        id = "shortcut_${System.currentTimeMillis()}_${shortcuts.size}",
                                        title = "",
                                        actionType = FloatingShortcutSettings.ACTION_ACTIVITY,
                                        target = ""
                                    )
                                )
                            }
                        )
                    }
                }
            }
        }
    }
            FloatingShortcutRoute.APPEARANCE -> FloatingShortcutAppearanceMiuixPage(
                context = context,
                onBack = { route = FloatingShortcutRoute.MAIN }
            )
        }
    }

    editor?.let { request ->
        val draft = request.draft
        val valid = draft.title.isNotBlank() &&
            (draft.actionType != FloatingShortcutSettings.ACTION_ACTIVITY || draft.target.isNotBlank())
        WindowDialog(
            show = true,
            title = if (request.original == null) "新增快捷项" else "编辑快捷项",
            onDismissRequest = ::dismissEditor,
            content = {
                Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    InputRow(
                        title = "名称",
                        summary = "显示在展开菜单中的名称",
                        value = draft.title,
                        onValueChange = { editor = request.copy(draft = draft.copy(title = it)) }
                    )
                    PopupChoiceRow(
                        title = "动作类型",
                        summary = when (draft.actionType) {
                            FloatingShortcutSettings.ACTION_MODULE_SETTINGS -> "打开 Hchat 设置"
                            else -> "打开微信页面"
                        },
                        options = listOf(
                            PopupChoice("微信页面", FloatingShortcutSettings.ACTION_ACTIVITY),
                            PopupChoice("模块设置", FloatingShortcutSettings.ACTION_MODULE_SETTINGS),
                        ),
                        currentValue = draft.actionType,
                        onValueChanged = { type ->
                            editor = request.copy(
                                draft = draft.copy(
                                    actionType = type,
                                    target = if (type == FloatingShortcutSettings.ACTION_ACTIVITY) draft.target else ""
                                )
                            )
                        }
                    )
                    if (draft.actionType == FloatingShortcutSettings.ACTION_ACTIVITY) {
                        InputRow(
                            title = "Activity 类名",
                            summary = "填写微信页面的完整 Activity 类名",
                            value = draft.target,
                            onValueChange = { editor = request.copy(draft = draft.copy(target = it)) }
                        )
                    }
                    FloatingShortcutIconActionRow(
                        title = "浅色模式图标",
                        iconPath = draft.iconPath,
                        defaultSummary = "未选择时使用内置图标",
                        onSelect = {
                            pickIcon("item-${draft.id}-${System.nanoTime()}") { path ->
                                if (draft.iconPath != request.original?.iconPath) {
                                    FloatingShortcutIconStore.delete(context, draft.iconPath)
                                }
                                editor = request.copy(draft = draft.copy(iconPath = path))
                            }
                        },
                        onReset = if (draft.iconPath.isBlank()) null else {
                            {
                                if (draft.iconPath != request.original?.iconPath) {
                                    FloatingShortcutIconStore.delete(context, draft.iconPath)
                                }
                                editor = request.copy(draft = draft.copy(iconPath = ""))
                            }
                        }
                    )
                    FloatingShortcutIconActionRow(
                        title = "深色模式图标",
                        iconPath = draft.darkIconPath,
                        defaultSummary = "未选择时沿用浅色模式图标",
                        onSelect = {
                            pickIcon("item-dark-${draft.id}-${System.nanoTime()}") { path ->
                                if (draft.darkIconPath != request.original?.darkIconPath) {
                                    FloatingShortcutIconStore.delete(context, draft.darkIconPath)
                                }
                                editor = request.copy(draft = draft.copy(darkIconPath = path))
                            }
                        },
                        onReset = if (draft.darkIconPath.isBlank()) null else {
                            {
                                if (draft.darkIconPath != request.original?.darkIconPath) {
                                    FloatingShortcutIconStore.delete(context, draft.darkIconPath)
                                }
                                editor = request.copy(draft = draft.copy(darkIconPath = ""))
                            }
                        }
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextButton(
                            text = "取消",
                            onClick = ::dismissEditor,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                        TextButton(
                            text = "保存",
                            onClick = {
                                if (!valid) {
                                    Toast.makeText(context, "请填写完整的快捷项信息", Toast.LENGTH_SHORT).show()
                                    return@TextButton
                                }
                                val saved = draft.copy(
                                    title = draft.title.trim(),
                                    target = draft.target.trim()
                                )
                                val original = request.original
                                if (original != null && original.iconPath != saved.iconPath) {
                                    FloatingShortcutIconStore.delete(context, original.iconPath)
                                }
                                if (original != null && original.darkIconPath != saved.darkIconPath) {
                                    FloatingShortcutIconStore.delete(context, original.darkIconPath)
                                }
                                val next = shortcuts.toMutableList()
                                val index = next.indexOfFirst { it.id == saved.id }
                                if (index >= 0) next[index] = saved else next.add(saved)
                                saveShortcuts(next)
                                editor = null
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                    }
                }
            }
        )
    }
}

@Composable
internal fun FloatingShortcutAppearanceMiuixPage(
    context: Context,
    onBack: () -> Unit
) {
    val sp = remember { HchatStorage.preferences(context, FloatingShortcutSettings.PREFS_NAME) }
    var bubbleSize by remember {
        mutableStateOf(
            sp.getInt(
                FloatingShortcutSettings.KEY_BUBBLE_SIZE,
                FloatingShortcutSettings.DEFAULT_BUBBLE_SIZE
            ).coerceIn(FloatingShortcutSettings.MIN_BUTTON_SIZE, FloatingShortcutSettings.MAX_BUTTON_SIZE)
        )
    }
    var bubbleColor by remember {
        mutableStateOf(
            sp.getString(
                FloatingShortcutSettings.KEY_BUBBLE_COLOR,
                FloatingShortcutSettings.DEFAULT_BUBBLE_COLOR
            ) ?: FloatingShortcutSettings.DEFAULT_BUBBLE_COLOR
        )
    }
    var actionSize by remember {
        mutableStateOf(
            sp.getInt(
                FloatingShortcutSettings.KEY_ACTION_SIZE,
                FloatingShortcutSettings.DEFAULT_ACTION_SIZE
            ).coerceIn(FloatingShortcutSettings.MIN_BUTTON_SIZE, FloatingShortcutSettings.MAX_BUTTON_SIZE)
        )
    }
    var actionColor by remember {
        mutableStateOf(
            sp.getString(
                FloatingShortcutSettings.KEY_ACTION_COLOR,
                FloatingShortcutSettings.DEFAULT_ACTION_COLOR
            ) ?: FloatingShortcutSettings.DEFAULT_ACTION_COLOR
        )
    }
    var labelTextSize by remember {
        mutableStateOf(
            sp.getInt(
                FloatingShortcutSettings.KEY_LABEL_TEXT_SIZE,
                FloatingShortcutSettings.DEFAULT_LABEL_TEXT_SIZE
            ).coerceIn(
                FloatingShortcutSettings.MIN_LABEL_TEXT_SIZE,
                FloatingShortcutSettings.MAX_LABEL_TEXT_SIZE
            )
        )
    }
    var labelColor by remember {
        mutableStateOf(
            sp.getString(
                FloatingShortcutSettings.KEY_LABEL_COLOR,
                FloatingShortcutSettings.DEFAULT_LABEL_COLOR
            ) ?: FloatingShortcutSettings.DEFAULT_LABEL_COLOR
        )
    }
    val currentBubbleSize by rememberUpdatedState(bubbleSize)
    val currentBubbleColor by rememberUpdatedState(bubbleColor)
    val currentActionSize by rememberUpdatedState(actionSize)
    val currentActionColor by rememberUpdatedState(actionColor)
    val currentLabelTextSize by rememberUpdatedState(labelTextSize)
    val currentLabelColor by rememberUpdatedState(labelColor)
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()

    fun persistAppearance() {
        sp.edit()
            .putInt(FloatingShortcutSettings.KEY_BUBBLE_SIZE, currentBubbleSize)
            .putString(FloatingShortcutSettings.KEY_BUBBLE_COLOR, currentBubbleColor.trim())
            .putInt(FloatingShortcutSettings.KEY_ACTION_SIZE, currentActionSize)
            .putString(FloatingShortcutSettings.KEY_ACTION_COLOR, currentActionColor.trim())
            .putInt(FloatingShortcutSettings.KEY_LABEL_TEXT_SIZE, currentLabelTextSize)
            .putString(FloatingShortcutSettings.KEY_LABEL_COLOR, currentLabelColor.trim())
            .apply()
    }

    DisposableEffect(sp) {
        onDispose(::persistAppearance)
    }

    PageScaffold(
        title = "按钮外观",
        largeTitle = "按钮外观",
        scrollBehavior = scrollBehavior,
        bottomBar = {
            BottomActionBar(
                primaryText = "返回",
                onPrimaryClick = {
                    persistAppearance()
                    onBack()
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "主按钮") }
            item {
                SettingsCard {
                    FloatingShortcutSizeSlider("主按钮大小", bubbleSize) { bubbleSize = it }
                    InsetDivider()
                    ColorPickerRow(
                        title = "主按钮颜色",
                        summary = "圆形背景色",
                        value = bubbleColor,
                        onReset = { bubbleColor = FloatingShortcutSettings.DEFAULT_BUBBLE_COLOR }
                    ) { bubbleColor = it.take(19) }
                }
            }
            item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "副按钮") }
            item {
                SettingsCard {
                    FloatingShortcutSizeSlider("副按钮大小", actionSize) { actionSize = it }
                    InsetDivider()
                    ColorPickerRow(
                        title = "副按钮颜色",
                        summary = "留空时跟随深浅色模式",
                        value = actionColor,
                        onReset = { actionColor = FloatingShortcutSettings.DEFAULT_ACTION_COLOR }
                    ) { actionColor = it.take(19) }
                }
            }
            item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "菜单名称") }
            item {
                SettingsCard {
                    FloatingShortcutTextSizeSlider("菜单名称大小", labelTextSize) { labelTextSize = it }
                    InsetDivider()
                    ColorPickerRow(
                        title = "菜单名称颜色",
                        summary = "留空时跟随深浅色模式",
                        value = labelColor,
                        onReset = { labelColor = FloatingShortcutSettings.DEFAULT_LABEL_COLOR }
                    ) { labelColor = it.take(19) }
                }
            }
            item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "重置") }
            item {
                SettingsCard {
                    ActionRow(
                        title = "恢复默认外观",
                        summary = "恢复按钮和菜单名称的默认外观",
                        onClick = {
                            bubbleSize = FloatingShortcutSettings.DEFAULT_BUBBLE_SIZE
                            bubbleColor = FloatingShortcutSettings.DEFAULT_BUBBLE_COLOR
                            actionSize = FloatingShortcutSettings.DEFAULT_ACTION_SIZE
                            actionColor = FloatingShortcutSettings.DEFAULT_ACTION_COLOR
                            labelTextSize = FloatingShortcutSettings.DEFAULT_LABEL_TEXT_SIZE
                            labelColor = FloatingShortcutSettings.DEFAULT_LABEL_COLOR
                        }
                    )
                }
            }
        }
    }
}

@Composable
internal fun FloatingShortcutTextSizeSlider(
    title: String,
    value: Int,
    onValueChange: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(
            text = "$title ${value}sp",
            color = MiuixTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium
        )
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            valueRange = FloatingShortcutSettings.MIN_LABEL_TEXT_SIZE.toFloat()..
                FloatingShortcutSettings.MAX_LABEL_TEXT_SIZE.toFloat(),
            steps = FloatingShortcutSettings.MAX_LABEL_TEXT_SIZE -
                FloatingShortcutSettings.MIN_LABEL_TEXT_SIZE - 1,
            showKeyPoints = true,
            keyPoints = listOf(10f, 12f, 14f, 16f, 18f, 20f, 24f)
        )
    }
}

@Composable
internal fun FloatingShortcutSizeSlider(
    title: String,
    value: Int,
    onValueChange: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(
            text = "$title ${value}dp",
            color = MiuixTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium
        )
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            valueRange = FloatingShortcutSettings.MIN_BUTTON_SIZE.toFloat()..
                FloatingShortcutSettings.MAX_BUTTON_SIZE.toFloat(),
            steps = FloatingShortcutSettings.MAX_BUTTON_SIZE -
                FloatingShortcutSettings.MIN_BUTTON_SIZE - 1,
            showKeyPoints = true,
            keyPoints = listOf(36f, 44f, 52f, 60f, 64f)
        )
    }
}

@Composable
internal fun FloatingShortcutSettingsItemRow(
    item: FloatingShortcutItem,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FloatingShortcutIconPreview(
                if (isSystemInDarkTheme() && item.darkIconPath.isNotBlank()) item.darkIconPath else item.iconPath,
                item.title,
                defaultGlyph = FloatingShortcutGlyphs.forItem(item)
            )
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    text = item.title,
                    color = MiuixTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = when (item.actionType) {
                        FloatingShortcutSettings.ACTION_MODULE_SETTINGS -> "模块设置"
                        else -> item.target
                    },
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Switch(checked = item.enabled, onCheckedChange = onEnabledChange)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FloatingShortcutItemAction(NavIcons.MoveUp, "上移", canMoveUp, onMoveUp)
            FloatingShortcutItemAction(NavIcons.MoveDown, "下移", canMoveDown, onMoveDown)
            FloatingShortcutItemAction(NavIcons.Edit, "编辑", true, onEdit)
            FloatingShortcutItemAction(NavIcons.Delete, "删除", true, onDelete, destructive = true)
        }
    }
}

@Composable
internal fun FloatingShortcutItemAction(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    destructive: Boolean = false
) {
    val color = when {
        !enabled -> MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.35f)
        destructive -> Color(0xFFD93025)
        else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    Image(
        imageVector = icon,
        contentDescription = description,
        colorFilter = ColorFilter.tint(color),
        modifier = Modifier.padding(start = 4.dp).size(34.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(7.dp)
    )
}

@Composable
internal fun FloatingShortcutIconActionRow(
    title: String,
    iconPath: String,
    defaultSummary: String,
    onSelect: () -> Unit,
    onReset: (() -> Unit)?
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FloatingShortcutIconPreview(iconPath, title)
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(text = title, color = MiuixTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
            Text(
                text = if (iconPath.isBlank()) defaultSummary else File(iconPath).name,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        TextButton(
            text = "选择",
            onClick = onSelect,
            colors = ButtonDefaults.textButtonColorsPrimary()
        )
        if (onReset != null) {
            TextButton(
                text = "默认",
                onClick = onReset,
                colors = ButtonDefaults.textButtonColorsPrimary()
            )
        }
    }
}

@Composable
internal fun FloatingShortcutIconPreview(
    path: String,
    description: String,
    defaultGlyph: FloatingShortcutGlyph? = null
) {
    val previewSizePx = with(LocalDensity.current) { 40.dp.roundToPx() }
    val bitmap = remember(path, previewSizePx) {
        path.takeIf { it.isNotBlank() }?.let { decodeFavoriteImage(it, previewSizePx) }
    }
    val fallbackTint = MiuixTheme.colorScheme.onSecondaryVariant.toArgb()
    Box(
        modifier = Modifier.size(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MiuixTheme.colorScheme.secondaryVariant),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = description,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Image(
                imageVector = NavIcons.Practical,
                contentDescription = description,
                colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSecondaryVariant),
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
internal fun QuickMarkReadMiuixPage(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit
) {
    val sp = remember { HchatStorage.preferences(context, QuickMarkReadSettings.PREFS_NAME) }
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()

    PageScaffold(
        title = provider.title(),
        largeTitle = provider.title(),
        scrollBehavior = scrollBehavior,
        bottomBar = {
            BottomActionBar("返回", onBack)
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "快捷已读") }
            item {
                SettingsCard {
                    SwitchRow(
                        sp,
                        QuickMarkReadSettings.KEY_DRAG_READ,
                        "拖拽已读",
                        "在微信底部未读角标向上拖动后，清空全部会话未读",
                        QuickMarkReadSettings.DEFAULT_DRAG_READ
                    )
                    InsetDivider()
                    SwitchRow(
                        sp,
                        QuickMarkReadSettings.KEY_PLUS_MENU_READ,
                        "注入加号菜单已读",
                        "在右上角加号菜单添加“全部已读”，重启微信后生效",
                        QuickMarkReadSettings.DEFAULT_PLUS_MENU_READ
                    )
                }
            }
        }
    }
}

@Composable
internal fun WeChatKeepAliveMiuixPage(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit
) {
    val sp = remember { HchatStorage.preferences(context, WeChatKeepAliveSettings.PREFS_NAME) }
    var enabled by remember { mutableStateOf(sp.getBoolean(WeChatKeepAliveSettings.KEY_ENABLE, WeChatKeepAliveSettings.DEFAULT_ENABLE)) }
    var foregroundService by remember { mutableStateOf(sp.getBoolean(WeChatKeepAliveSettings.KEY_FOREGROUND_SERVICE, WeChatKeepAliveSettings.DEFAULT_FOREGROUND_SERVICE)) }
    var wakeLock by remember { mutableStateOf(sp.getBoolean(WeChatKeepAliveSettings.KEY_WAKE_LOCK, WeChatKeepAliveSettings.DEFAULT_WAKE_LOCK)) }
    var rootWhitelist by remember { mutableStateOf(sp.getBoolean(WeChatKeepAliveSettings.KEY_ROOT_DOZE_WHITELIST, WeChatKeepAliveSettings.DEFAULT_ROOT_DOZE_WHITELIST)) }
    var watchdog by remember { mutableStateOf(sp.getBoolean(WeChatKeepAliveSettings.KEY_WATCHDOG, WeChatKeepAliveSettings.DEFAULT_WATCHDOG)) }
    var rootAppOps by remember { mutableStateOf(sp.getBoolean(WeChatKeepAliveSettings.KEY_ROOT_APP_OPS, WeChatKeepAliveSettings.DEFAULT_ROOT_APP_OPS)) }
    var networkHeartbeat by remember { mutableStateOf(sp.getBoolean(WeChatKeepAliveSettings.KEY_NETWORK_HEARTBEAT, WeChatKeepAliveSettings.DEFAULT_NETWORK_HEARTBEAT)) }
    var ignoringBattery by remember { mutableStateOf(WeChatKeepAliveRuntime.isIgnoringBatteryOptimizations(context)) }
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()

    fun saveBoolean(key: String, value: Boolean) {
        sp.edit().putBoolean(key, value).apply()
        WeChatKeepAliveRuntime.apply(context)
    }

    fun openBatterySettings() {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { Toast.makeText(context, "无法打开电池优化设置", Toast.LENGTH_SHORT).show() }
    }

    PageScaffold(
        title = provider.title(),
        largeTitle = provider.title(),
        scrollBehavior = scrollBehavior,
        bottomBar = { BottomActionBar("返回", onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "强保活") }
            item {
                SettingsCard {
                    SwitchRow(
                        checked = enabled,
                        title = "启用微信强保活",
                        summary = "开启后尝试保持微信息屏运行",
                        onCheckedChange = {
                            enabled = it
                            saveBoolean(WeChatKeepAliveSettings.KEY_ENABLE, it)
                        }
                    )
                    InsetDivider()
                    SwitchRow(
                        checked = foregroundService,
                        title = "前台服务保活",
                        summary = "通过常驻通知提高后台存活率",
                        onCheckedChange = {
                            foregroundService = it
                            saveBoolean(WeChatKeepAliveSettings.KEY_FOREGROUND_SERVICE, it)
                        }
                    )
                    InsetDivider()
                    SwitchRow(
                        checked = wakeLock,
                        title = "WakeLock 保活",
                        summary = "息屏后保持 CPU 运行，耗电会增加",
                        onCheckedChange = {
                            wakeLock = it
                            saveBoolean(WeChatKeepAliveSettings.KEY_WAKE_LOCK, it)
                        }
                    )
                    InsetDivider()
                    SwitchRow(
                        checked = rootWhitelist,
                        title = "Root Doze 白名单",
                        summary = "有 Root 时执行 deviceidle 白名单命令",
                        onCheckedChange = {
                            rootWhitelist = it
                            saveBoolean(WeChatKeepAliveSettings.KEY_ROOT_DOZE_WHITELIST, it)
                        }
                    )
                    InsetDivider()
                    SwitchRow(
                        checked = rootAppOps,
                        title = "Root 后台限制放行",
                        summary = "有 Root 时放行微信后台运行相关 AppOps",
                        onCheckedChange = {
                            rootAppOps = it
                            saveBoolean(WeChatKeepAliveSettings.KEY_ROOT_APP_OPS, it)
                        }
                    )
                    InsetDivider()
                    SwitchRow(
                        checked = watchdog,
                        title = "看门狗拉起微信",
                        summary = "前台服务存活时定期尝试拉起微信",
                        onCheckedChange = {
                            watchdog = it
                            saveBoolean(WeChatKeepAliveSettings.KEY_WATCHDOG, it)
                        }
                    )
                    InsetDivider()
                    SwitchRow(
                        checked = networkHeartbeat,
                        title = "网络心跳保活",
                        summary = "定时发起轻量网络请求保持链路活跃",
                        onCheckedChange = {
                            networkHeartbeat = it
                            saveBoolean(WeChatKeepAliveSettings.KEY_NETWORK_HEARTBEAT, it)
                        }
                    )
                }
            }
            item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "系统限制") }
            item {
                SettingsCard {
                    InfoRow(
                        "电池优化白名单",
                        if (ignoringBattery) "微信已在白名单" else "微信可能仍受系统省电影响"
                    )
                    InsetDivider()
                    ActionRow("打开电池优化设置", "建议把微信设置为不限制") {
                        openBatterySettings()
                    }
                    InsetDivider()
                    ActionRow("刷新状态", if (ignoringBattery) "当前已忽略优化" else "当前未忽略优化") {
                        ignoringBattery = WeChatKeepAliveRuntime.isIgnoringBatteryOptimizations(context)
                        Toast.makeText(context, "状态已刷新", Toast.LENGTH_SHORT).show()
                    }
                    InsetDivider()
                    ActionRow("立即应用保活", "重新启动服务并应用 WakeLock / Root 白名单") {
                        WeChatKeepAliveRuntime.apply(context)
                        ignoringBattery = WeChatKeepAliveRuntime.isIgnoringBatteryOptimizations(context)
                        Toast.makeText(context, "已应用", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}

@Composable
internal fun CustomNotificationMiuixPage(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit
) {
    val settings = remember { CustomNotificationSettings(context) }
    val sp = remember { HchatStorage.preferences(context, CustomNotificationSettings.PREFS_NAME) }
    var route by remember { mutableStateOf<CustomNotificationRoute>(CustomNotificationRoute.Main) }
    var rules by remember { mutableStateOf(settings.rules()) }
    var defaultPrivateRule by remember { mutableStateOf(settings.defaultPrivateRule()) }
    var defaultGroupRule by remember { mutableStateOf(settings.defaultGroupRule()) }
    var defaultOfficialRule by remember { mutableStateOf(settings.defaultOfficialRule()) }
    val ruleListState = rememberLazyListState()
    var ruleQuery by remember { mutableStateOf("") }
    val ruleEditorListState = rememberLazyListState()
    val defaultEditorListState = rememberLazyListState()

    fun saveRules(next: List<CustomNotificationRule>) {
        val clean = next.distinctBy { it.talker }
        rules = clean
        settings.saveRules(clean)
    }

    fun saveDefaultRule(rule: CustomNotificationRule) {
        when {
            rule.official -> {
                defaultOfficialRule = rule
                settings.saveDefaultOfficialRule(rule)
            }
            rule.group -> {
                defaultGroupRule = rule
                settings.saveDefaultGroupRule(rule)
            }
            else -> {
                defaultPrivateRule = rule
                settings.saveDefaultPrivateRule(rule)
            }
        }
    }

    SettingsRouteTransition(
        targetState = route,
        label = "CustomNotificationRoute",
        depthOf = { it.depth() }
    ) { currentRoute ->
        when (currentRoute) {
            CustomNotificationRoute.Main -> CustomNotificationMainPage(
                context = context,
                provider = provider,
                sp = sp,
                rules = rules,
                defaultPrivateRule = defaultPrivateRule,
                defaultGroupRule = defaultGroupRule,
                defaultOfficialRule = defaultOfficialRule,
                onBack = onBack,
                onOpenRules = { route = CustomNotificationRoute.RuleList },
                onEditDefault = { route = CustomNotificationRoute.DefaultEditor(it) }
            )
            is CustomNotificationRoute.DefaultEditor -> {
                val rule = when (currentRoute.kind) {
                    CustomNotificationDefaultKind.PRIVATE -> defaultPrivateRule
                    CustomNotificationDefaultKind.GROUP -> defaultGroupRule
                    CustomNotificationDefaultKind.OFFICIAL -> defaultOfficialRule
                }
                CustomNotificationRuleEditorPage(
                    context = context,
                    initialRule = rule,
                    listState = defaultEditorListState,
                    isDefault = true,
                    onBack = { route = CustomNotificationRoute.Main },
                    onSave = { saved ->
                        saveDefaultRule(saved)
                        Toast.makeText(context, "默认规则已保存", Toast.LENGTH_SHORT).show()
                        route = CustomNotificationRoute.Main
                    },
                    onDelete = {},
                    onPickOnlyMembers = {},
                    onPickBlockMembers = {}
                )
            }
            CustomNotificationRoute.RuleList -> CustomNotificationRuleListPage(
                context = context,
                rules = rules,
                query = ruleQuery,
                onQueryChange = { ruleQuery = it },
                listState = ruleListState,
                onBack = { route = CustomNotificationRoute.Main },
                onSaveRules = ::saveRules,
                onEditRule = { route = CustomNotificationRoute.RuleEditor(it) },
                onToggleRule = { rule, enabled ->
                    saveRules(rules.map { if (it.talker == rule.talker) it.copy(enabled = enabled) else it })
                    Toast.makeText(context, if (enabled) "已启用该会话规则" else "已关闭该会话规则", Toast.LENGTH_SHORT).show()
                },
                onBatchEdit = { route = CustomNotificationRoute.BatchEditor },
                onAddRules = {
                    route = CustomNotificationRoute.ContactPicker(
                        ContactPickerRequest(
                            title = "添加通知会话",
                            mode = ContactPickerMode.ALL_CHATS,
                            multiSelect = true,
                            existingValue = "",
                            onValue = {},
                            enableLabels = true
                        )
                    )
                }
            )
            CustomNotificationRoute.BatchEditor -> CustomNotificationBatchEditorPage(
                ruleCount = rules.size,
                onBack = { route = CustomNotificationRoute.RuleList },
                onApply = { update ->
                    if (!update.hasChanges()) {
                        Toast.makeText(context, "请选择要应用的配置", Toast.LENGTH_SHORT).show()
                    } else {
                        saveRules(rules.map { rule -> update.apply(rule) })
                        Toast.makeText(context, "批量配置已应用", Toast.LENGTH_SHORT).show()
                        route = CustomNotificationRoute.RuleList
                    }
                }
            )
            is CustomNotificationRoute.RuleEditor -> {
                val rule = rules.firstOrNull { it.talker == currentRoute.talker }
                if (rule == null) {
                    CustomNotificationMissingRulePage(
                        onBack = { route = CustomNotificationRoute.RuleList }
                    )
                } else {
                    CustomNotificationRuleEditorPage(
                        context = context,
                        initialRule = rule,
                        listState = ruleEditorListState,
                        onBack = { route = CustomNotificationRoute.RuleList },
                        onSave = { saved ->
                            saveRules(rules.map { if (it.talker == saved.talker) saved else it })
                            Toast.makeText(context, "规则已保存", Toast.LENGTH_SHORT).show()
                            route = CustomNotificationRoute.RuleList
                        },
                        onDelete = {
                            saveRules(rules.filterNot { it.talker == rule.talker })
                            Toast.makeText(context, "规则已删除", Toast.LENGTH_SHORT).show()
                            route = CustomNotificationRoute.RuleList
                        },
                        onPickOnlyMembers = { value ->
                            route = CustomNotificationRoute.GroupMemberPicker(
                                talker = rule.talker,
                                request = GroupMemberPickerRequest(
                                    title = "仅显示成员通知",
                                    existingValue = memberRulesToGroupEntries(rule.talker, value),
                                    onValue = {}
                                )
                            )
                        },
                        onPickBlockMembers = { value ->
                            route = CustomNotificationRoute.GroupMemberPicker(
                                talker = rule.talker,
                                request = GroupMemberPickerRequest(
                                    title = "屏蔽成员通知",
                                    existingValue = memberRulesToGroupEntries(rule.talker, value),
                                    onValue = {}
                                )
                            )
                        }
                    )
                }
            }
            is CustomNotificationRoute.ContactPicker -> ContactPickerPage(
                context = context,
                request = currentRoute.request,
                onBack = { route = CustomNotificationRoute.RuleList },
                onConfirm = { picked ->
                    if (picked.isEmpty()) {
                        Toast.makeText(context, "未选择会话", Toast.LENGTH_SHORT).show()
                        route = CustomNotificationRoute.RuleList
                    } else {
                        val byTalker = rules.associateBy { it.talker }.toMutableMap()
                        var added = 0
                        var updated = 0
                        picked.forEach { option ->
                            val old = byTalker[option.id]
                            if (old == null) {
                                added++
                                byTalker[option.id] = customNotificationRuleFromContact(option)
                            } else {
                                updated++
                                byTalker[option.id] = old.copy(
                                    label = option.label,
                                    group = option.group,
                                    official = option.official
                                )
                            }
                        }
                        saveRules(byTalker.values.sortedWith(compareBy<CustomNotificationRule> { !it.enabled }.thenBy { it.label.lowercase(Locale.US) }))
                        val message = when {
                            added > 0 && updated > 0 -> "已添加 $added 个会话，更新 $updated 个会话"
                            added > 0 -> "已添加 $added 个会话"
                            else -> "已更新 $updated 个会话"
                        }
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        route = CustomNotificationRoute.RuleList
                    }
                }
            )
            is CustomNotificationRoute.GroupMemberPicker -> GroupMemberPickerPage(
                context = context,
                request = currentRoute.request,
                onBack = { route = CustomNotificationRoute.RuleEditor(currentRoute.talker) },
                onConfirm = { entries ->
                    val talker = currentRoute.talker
                    val memberValue = groupEntriesToMemberRules(talker, entries)
                    val title = currentRoute.request.title
                    rules.firstOrNull { it.talker == talker }?.let { old ->
                        val nextRule = if (title.contains("屏蔽")) {
                            old.copy(blockMembers = memberValue)
                        } else {
                            old.copy(onlyMembers = memberValue)
                        }
                        saveRules(rules.map { if (it.talker == talker) nextRule else it })
                        Toast.makeText(context, "成员规则已保存", Toast.LENGTH_SHORT).show()
                    }
                    route = CustomNotificationRoute.RuleEditor(talker)
                }
            )
        }
    }
}

@Composable
internal fun AutoReplyMiuixPage(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit
) {
    val sp = remember { HchatStorage.preferences(context, AutoReplySettings.PREFS_NAME) }
    val settings = remember { AutoReplySettings(context) }
    var rules by remember { mutableStateOf(settings.rules()) }
    var route by remember { mutableStateOf("main") }
    var editingRuleId by remember { mutableStateOf<String?>(null) }
    var editingRuleDraft by remember { mutableStateOf<AutoReplyRule?>(null) }
    var editingSteps by remember { mutableStateOf<Pair<String, List<AutoReplyStep>>?>(null) }
    val ruleListState = rememberLazyListState()
    var ruleQuery by remember { mutableStateOf("") }
    val ruleEditorListState = rememberLazyListState()
    val autoAcceptListState = rememberLazyListState()
    val greetAcceptedListState = rememberLazyListState()
    val autoAcceptLabelListState = rememberLazyListState()
    val greetAcceptedLabelListState = rememberLazyListState()
    val autoAcceptAutomationKeys = remember {
        AutoReplyFriendAutomationKeys(
            AutoReplySettings.KEY_AUTO_ACCEPT_LABEL_NEW_FRIEND_ENABLE,
            AutoReplySettings.KEY_AUTO_ACCEPT_LABEL_DATE_ENABLE,
            AutoReplySettings.KEY_AUTO_ACCEPT_LABEL_DATE_FORMAT,
            AutoReplySettings.KEY_AUTO_ACCEPT_LABEL_EXISTING_ENABLE,
            AutoReplySettings.KEY_AUTO_ACCEPT_LABEL_SELECTED_NAMES,
            AutoReplySettings.KEY_AUTO_ACCEPT_REMARK_NEW_FRIEND_ENABLE,
            AutoReplySettings.KEY_AUTO_ACCEPT_REMARK_NICKNAME_SUFFIX_ENABLE,
            AutoReplySettings.KEY_AUTO_ACCEPT_REMARK_DATE_ENABLE,
            AutoReplySettings.KEY_AUTO_ACCEPT_REMARK_DATE_FORMAT,
            AutoReplySettings.KEY_AUTO_ACCEPT_REMARK_CUSTOM_ENABLE,
            AutoReplySettings.KEY_AUTO_ACCEPT_REMARK_CUSTOM_TEXT
        )
    }
    val greetAcceptedAutomationKeys = remember {
        AutoReplyFriendAutomationKeys(
            AutoReplySettings.KEY_GREET_ACCEPTED_LABEL_NEW_FRIEND_ENABLE,
            AutoReplySettings.KEY_GREET_ACCEPTED_LABEL_DATE_ENABLE,
            AutoReplySettings.KEY_GREET_ACCEPTED_LABEL_DATE_FORMAT,
            AutoReplySettings.KEY_GREET_ACCEPTED_LABEL_EXISTING_ENABLE,
            AutoReplySettings.KEY_GREET_ACCEPTED_LABEL_SELECTED_NAMES,
            AutoReplySettings.KEY_GREET_ACCEPTED_REMARK_NEW_FRIEND_ENABLE,
            AutoReplySettings.KEY_GREET_ACCEPTED_REMARK_NICKNAME_SUFFIX_ENABLE,
            AutoReplySettings.KEY_GREET_ACCEPTED_REMARK_DATE_ENABLE,
            AutoReplySettings.KEY_GREET_ACCEPTED_REMARK_DATE_FORMAT,
            AutoReplySettings.KEY_GREET_ACCEPTED_REMARK_CUSTOM_ENABLE,
            AutoReplySettings.KEY_GREET_ACCEPTED_REMARK_CUSTOM_TEXT
        )
    }

    fun saveRules(next: List<AutoReplyRule>) {
        rules = next
        settings.saveRules(next)
    }

    val autoReplyRoute = if (editingSteps != null) "steps" else route
    SettingsRouteTransition(
        targetState = autoReplyRoute,
        label = "AutoReplyRouteTransition",
        depthOf = { autoReplyRouteDepth(it) }
    ) { currentRoute ->
        when (currentRoute) {
            "steps" -> {
                val request = editingSteps ?: return@SettingsRouteTransition
            AutoReplyStepsPage(
                title = request.first,
                initialSteps = request.second,
                onBack = { editingSteps = null },
                onSave = { steps ->
                    when (route) {
                        "autoAccept" -> settings.saveAutoAcceptSteps(steps)
                        "greetAccepted" -> settings.saveGreetAcceptedSteps(steps)
                        else -> {
                            val id = editingRuleId
                            if (id != null) {
                                val updated = (editingRuleDraft ?: rules.firstOrNull { it.id == id })
                                    ?.copy(steps = steps)
                                if (updated != null) {
                                    editingRuleDraft = updated
                                    saveRules(rules.map { if (it.id == id) updated else it })
                                }
                            }
                        }
                    }
                    Toast.makeText(context, "回复步骤已保存", Toast.LENGTH_SHORT).show()
                    editingSteps = null
                }
            )
            }
        "rules" -> AutoReplyRuleListPage(
            rules = rules,
            query = ruleQuery,
            onQueryChange = { ruleQuery = it },
            listState = ruleListState,
            onBack = { route = "main" },
            onAdd = {
                val rule = AutoReplyRule(
                    id = System.currentTimeMillis().toString(),
                    name = "规则 ${rules.size + 1}"
                )
                saveRules(rules + rule)
                editingRuleId = rule.id
                editingRuleDraft = rule
                route = "ruleEditor"
            },
            onEdit = {
                editingRuleId = it.id
                editingRuleDraft = it
                route = "ruleEditor"
            },
            onToggle = { rule, enabled -> saveRules(rules.map { if (it.id == rule.id) it.copy(enabled = enabled) else it }) },
            onDelete = { rule ->
                saveRules(rules.filterNot { it.id == rule.id })
                Toast.makeText(context, "规则已删除", Toast.LENGTH_SHORT).show()
            },
            onBatchDelete = { targets ->
                val targetIds = targets.mapTo(HashSet()) { it.id }
                saveRules(rules.filterNot { it.id in targetIds })
                Toast.makeText(context, "已删除 ${targets.size} 条规则", Toast.LENGTH_SHORT).show()
            }
        )
        "ruleEditor" -> {
            val rule = editingRuleDraft ?: rules.firstOrNull { it.id == editingRuleId }
            if (rule == null) {
                editingRuleDraft = null
                route = "rules"
            } else {
                AutoReplyRuleEditorPage(
                    rule = rule,
                    listState = ruleEditorListState,
                    onBack = {
                        editingRuleDraft = null
                        route = "rules"
                    },
                    onSave = { saved ->
                        saveRules(rules.map { if (it.id == saved.id) saved else it })
                        editingRuleDraft = null
                        Toast.makeText(context, "规则已保存", Toast.LENGTH_SHORT).show()
                        route = "rules"
                    },
                    onDraftChange = { editingRuleDraft = it },
                    onEditSteps = { draft ->
                        editingRuleDraft = draft
                        editingSteps = "规则回复" to draft.steps
                    }
                )
            }
        }
        "autoAccept" -> AutoReplyFriendPage(
            context = context,
            title = "好友请求处理",
            sp = sp,
            enabledKey = AutoReplySettings.KEY_AUTO_ACCEPT_ENABLE,
            delayKey = AutoReplySettings.KEY_AUTO_ACCEPT_DELAY_MS,
            tagEnabledKey = AutoReplySettings.KEY_AUTO_ACCEPT_TAG_ENABLE,
            tagNameKey = AutoReplySettings.KEY_AUTO_ACCEPT_TAG_NAME,
            steps = settings.autoAcceptSteps(),
            listState = autoAcceptListState,
            onBack = { route = "main" },
            automationKeys = autoAcceptAutomationKeys,
            onSelectLabels = { route = "autoAcceptLabels" },
            onEditSteps = { editingSteps = "好友请求回复" to settings.autoAcceptSteps() }
        )
        "autoAcceptLabels" -> AutoReplyLabelPickerPage(
            context = context,
            sp = sp,
            selectedKey = AutoReplySettings.KEY_AUTO_ACCEPT_LABEL_SELECTED_NAMES,
            listState = autoAcceptLabelListState,
            onBack = { route = "autoAccept" }
        )
        "greetAccepted" -> AutoReplyFriendPage(
            context = context,
            title = "通过后欢迎语",
            sp = sp,
            enabledKey = AutoReplySettings.KEY_GREET_ACCEPTED_ENABLE,
            delayKey = AutoReplySettings.KEY_GREET_ACCEPTED_DELAY_MS,
            tagEnabledKey = AutoReplySettings.KEY_GREET_ACCEPTED_TAG_ENABLE,
            tagNameKey = AutoReplySettings.KEY_GREET_ACCEPTED_TAG_NAME,
            steps = settings.greetAcceptedSteps(),
            listState = greetAcceptedListState,
            onBack = { route = "main" },
            automationKeys = greetAcceptedAutomationKeys,
            onSelectLabels = { route = "greetAcceptedLabels" },
            onEditSteps = { editingSteps = "通过后欢迎语" to settings.greetAcceptedSteps() }
        )
        "greetAcceptedLabels" -> AutoReplyLabelPickerPage(
            context = context,
            sp = sp,
            selectedKey = AutoReplySettings.KEY_GREET_ACCEPTED_LABEL_SELECTED_NAMES,
            listState = greetAcceptedLabelListState,
            onBack = { route = "greetAccepted" }
        )
        "ai" -> AutoReplyAiPage(context, sp, onBack = { route = "main" })
        else -> AutoReplyMainPage(
            provider = provider,
            sp = sp,
            rules = rules,
            onBack = onBack,
            onOpenRules = { route = "rules" },
            onOpenAutoAccept = { route = "autoAccept" },
            onOpenGreetAccepted = { route = "greetAccepted" },
            onOpenAi = { route = "ai" }
        )
        }
    }
}

@Composable
internal fun AutoMessageForwardMiuixPage(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit
) {
    AutoMessageForwardSettingsUi.Page(context, provider, onBack)
}

internal object AutoMessageForwardSettingsUi {
@Composable
fun Page(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit
) {
    val settings = remember { AutoMessageForwardSettings }
    var enabled by remember { mutableStateOf(settings.isEnabled(context)) }
    var rules by remember { mutableStateOf(settings.rules(context)) }
    var route by remember { mutableStateOf(AutoMessageForwardRoute.MAIN) }
    var draft by remember { mutableStateOf<AutoMessageForwardRule?>(null) }
    var pickerField by remember { mutableStateOf<AutoMessageForwardContactField?>(null) }
    var ruleQuery by remember { mutableStateOf("") }
    val ruleListState = rememberLazyListState()
    val editorListState = rememberLazyListState()

    fun saveRules(next: List<AutoMessageForwardRule>) {
        rules = next
        settings.saveRules(context, next)
    }

    SettingsRouteTransition(
        targetState = route,
        label = "AutoMessageForwardRoute",
        depthOf = {
            when (it) {
                AutoMessageForwardRoute.MAIN -> 0
                AutoMessageForwardRoute.RULES -> 1
                AutoMessageForwardRoute.EDITOR -> 2
                AutoMessageForwardRoute.CONTACTS,
                AutoMessageForwardRoute.MEMBERS,
                AutoMessageForwardRoute.TYPES,
                AutoMessageForwardRoute.REPLACEMENTS -> 3
            }
        }
    ) { currentRoute ->
        when (currentRoute) {
            AutoMessageForwardRoute.MAIN -> AutoMessageForwardMainPage(
                provider = provider,
                enabled = enabled,
                rules = rules,
                onBack = onBack,
                onEnabledChange = {
                    enabled = it
                    settings.setEnabled(context, it)
                },
                onOpenRules = { route = AutoMessageForwardRoute.RULES }
            )
            AutoMessageForwardRoute.RULES -> AutoMessageForwardRuleListPage(
                rules = rules,
                listState = ruleListState,
                query = ruleQuery,
                onQueryChange = { ruleQuery = it },
                onBack = { route = AutoMessageForwardRoute.MAIN },
                onAdd = {
                    draft = settings.newRule(rules.size + 1)
                    route = AutoMessageForwardRoute.EDITOR
                },
                onEdit = {
                    draft = it
                    route = AutoMessageForwardRoute.EDITOR
                },
                onToggle = { rule, checked ->
                    saveRules(rules.map { if (it.id == rule.id) rule.copy(enabled = checked) else it })
                },
                onDelete = { rule ->
                    saveRules(rules.filterNot { it.id == rule.id })
                    Toast.makeText(context, "规则已删除", Toast.LENGTH_SHORT).show()
                },
                onBatchDelete = { selected ->
                    val ids = selected.mapTo(HashSet()) { it.id }
                    saveRules(rules.filterNot { it.id in ids })
                    Toast.makeText(context, "已删除 ${selected.size} 条规则", Toast.LENGTH_SHORT).show()
                }
            )
            AutoMessageForwardRoute.EDITOR -> {
                val current = draft
                if (current == null) {
                    route = AutoMessageForwardRoute.RULES
                } else {
                    AutoMessageForwardRuleEditorPage(
                        rule = current,
                        listState = editorListState,
                        onBack = { route = AutoMessageForwardRoute.RULES },
                        onDraftChange = { draft = it },
                        onPickContacts = { next, field ->
                            draft = next
                            pickerField = field
                            route = AutoMessageForwardRoute.CONTACTS
                        },
                        onPickMembers = { next ->
                            draft = next
                            route = AutoMessageForwardRoute.MEMBERS
                        },
                        onPickTypes = { next ->
                            draft = next
                            route = AutoMessageForwardRoute.TYPES
                        },
                        onPickReplacementRules = { next ->
                            draft = next
                            route = AutoMessageForwardRoute.REPLACEMENTS
                        },
                        onSave = { saved ->
                            val clean = saved.copy(
                                name = saved.name.trim(),
                                sourceMemberIds = retainAutoMessageForwardSourceMembers(
                                    saved.sourceMemberIds,
                                    saved.sourceIds
                                )
                            )
                            when {
                                clean.name.isBlank() -> Toast.makeText(context, "请输入规则名称", Toast.LENGTH_SHORT).show()
                                clean.sourceIds.isEmpty() -> Toast.makeText(context, "请选择监听会话", Toast.LENGTH_SHORT).show()
                                clean.targetIds.isEmpty() -> Toast.makeText(context, "请选择转发会话", Toast.LENGTH_SHORT).show()
                                clean.messageKinds.isEmpty() -> Toast.makeText(context, "至少选择一种消息类型", Toast.LENGTH_SHORT).show()
                                else -> {
                                    saveRules(
                                        if (rules.any { it.id == clean.id }) {
                                            rules.map { if (it.id == clean.id) clean else it }
                                        } else {
                                            rules + clean
                                        }
                                    )
                                    draft = null
                                    Toast.makeText(context, "规则已保存", Toast.LENGTH_SHORT).show()
                                    route = AutoMessageForwardRoute.RULES
                                }
                            }
                        }
                    )
                }
            }
            AutoMessageForwardRoute.CONTACTS -> {
                val current = draft
                val field = pickerField
                if (current == null || field == null) {
                    route = AutoMessageForwardRoute.EDITOR
                } else {
                    val sources = field == AutoMessageForwardContactField.SOURCES
                    val contactRequest = remember(current.id, field, current.sourceIds, current.targetIds) {
                        ContactPickerRequest(
                            title = if (sources) "选择监听会话" else "选择转发会话",
                            mode = if (sources) ContactPickerMode.ALL_CHATS else ContactPickerMode.BOTH,
                            multiSelect = true,
                            existingValue = formatIds(if (sources) current.sourceIds else current.targetIds),
                            onValue = { value ->
                                val selected = parseIds(value)
                                draft = if (sources) current.copy(
                                    sourceIds = selected,
                                    sourceMemberIds = retainAutoMessageForwardSourceMembers(current.sourceMemberIds, selected)
                                )
                                else current.copy(targetIds = selected)
                                pickerField = null
                                route = AutoMessageForwardRoute.EDITOR
                            },
                            enableLabels = true
                        )
                    }
                    ContactPickerPage(
                        context = context,
                        request = contactRequest,
                        onBack = {
                            pickerField = null
                            route = AutoMessageForwardRoute.EDITOR
                        },
                        onConfirm = { selected ->
                            val value = formatIds(selected.map { it.id })
                            val ids = parseIds(value)
                            draft = if (sources) current.copy(
                                sourceIds = ids,
                                sourceMemberIds = retainAutoMessageForwardSourceMembers(current.sourceMemberIds, ids)
                            )
                            else current.copy(targetIds = ids)
                            pickerField = null
                            route = AutoMessageForwardRoute.EDITOR
                        }
                    )
                }
            }
            AutoMessageForwardRoute.MEMBERS -> {
                val current = draft
                val sourceGroups = current?.sourceIds.orEmpty().filterTo(linkedSetOf(), ::isAutoMessageForwardGroupId)
                if (current == null || sourceGroups.isEmpty()) {
                    route = AutoMessageForwardRoute.EDITOR
                } else {
                    val request = remember(current.id, current.sourceMemberIds, sourceGroups) {
                        GroupMemberPickerRequest(
                            title = "选择监听群成员",
                            existingValue = formatGroupMemberEntries(current.sourceMemberIds),
                            onValue = {},
                            allowedGroupIds = sourceGroups
                        )
                    }
                    GroupMemberPickerPage(
                        context = context,
                        request = request,
                        onBack = { route = AutoMessageForwardRoute.EDITOR },
                        onConfirm = { entries ->
                            draft = current.copy(sourceMemberIds = entries.toSet())
                            Toast.makeText(context, "已选择 ${entries.size} 个群成员", Toast.LENGTH_SHORT).show()
                            route = AutoMessageForwardRoute.EDITOR
                        }
                    )
                }
            }
            AutoMessageForwardRoute.TYPES -> {
                val current = draft
                if (current == null) {
                    route = AutoMessageForwardRoute.EDITOR
                } else {
                    AutoMessageForwardTypePickerPage(
                        selectedKinds = current.messageKinds,
                        onBack = { route = AutoMessageForwardRoute.EDITOR },
                        onConfirm = {
                            draft = current.copy(messageKinds = it)
                            route = AutoMessageForwardRoute.EDITOR
                        }
                    )
                }
            }
            AutoMessageForwardRoute.REPLACEMENTS -> {
                val current = draft
                if (current == null) {
                    route = AutoMessageForwardRoute.EDITOR
                } else {
                    KeywordReplacementRulesPage(
                        context = context,
                        initialRules = current.keywordReplacements,
                        onBack = { route = AutoMessageForwardRoute.EDITOR },
                        onSave = { updated ->
                            draft = current.copy(keywordReplacements = updated)
                            Toast.makeText(context, "替换规则已保存", Toast.LENGTH_SHORT).show()
                            route = AutoMessageForwardRoute.EDITOR
                        }
                    )
                }
            }
        }
    }
}

@Composable
internal fun AutoMessageForwardMainPage(
    provider: FeatureSettingsProvider,
    enabled: Boolean,
    rules: List<AutoMessageForwardRule>,
    onBack: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onOpenRules: () -> Unit
) {
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()
    PageScaffold(
        title = provider.title(),
        largeTitle = provider.title(),
        scrollBehavior = scrollBehavior,
        bottomBar = { BottomActionBar("返回", onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "消息自动转发") }
            item {
                SettingsCard {
                    SwitchRow(
                        checked = enabled,
                        title = "启用消息自动转发",
                        summary = "默认不转发自己发送的消息，可在每条规则中单独开启",
                        onCheckedChange = onEnabledChange
                    )
                    InsetDivider()
                    ActionRow(
                        title = "转发规则",
                        summary = if (rules.isEmpty()) "暂无规则" else "${rules.size} 条规则，${rules.count { it.enabled }} 条启用",
                        onClick = onOpenRules
                    )
                }
            }
        }
    }
}

@Composable
internal fun AutoMessageForwardRuleListPage(
    rules: List<AutoMessageForwardRule>,
    listState: LazyListState,
    query: String,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (AutoMessageForwardRule) -> Unit,
    onToggle: (AutoMessageForwardRule, Boolean) -> Unit,
    onDelete: (AutoMessageForwardRule) -> Unit,
    onBatchDelete: (List<AutoMessageForwardRule>) -> Unit
) {
    val context = LocalContext.current
    var batchDeleteMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val visible = rules.filter { rule ->
        val lower = query.trim().lowercase(Locale.US)
        lower.isBlank() ||
            rule.name.lowercase(Locale.US).contains(lower) ||
            rule.sourceIds.any { it.lowercase(Locale.US).contains(lower) } ||
            rule.sourceMemberIds.any { it.lowercase(Locale.US).contains(lower) } ||
            rule.targetIds.any { it.lowercase(Locale.US).contains(lower) } ||
            autoMessageForwardRuleSummary(rule).lowercase(Locale.US).contains(lower)
    }
    val visibleIds = visible.mapTo(LinkedHashSet()) { it.id }
    val selected = rules.filter { it.id in selectedIds }
    val allVisibleSelected = visibleIds.isNotEmpty() && visibleIds.all { it in selectedIds }
    val scrollBehavior = MiuixScrollBehavior()
    PageScaffold(
        title = "转发规则",
        largeTitle = "转发规则",
        scrollBehavior = scrollBehavior,
        bottomBar = {
            if (batchDeleteMode) {
                BottomActionBar(
                    primaryText = "删除所选（${selected.size}）",
                    onPrimaryClick = {
                        if (selected.isEmpty()) Toast.makeText(context, "请先选择规则", Toast.LENGTH_SHORT).show()
                        else showDeleteConfirm = true
                    },
                    secondaryText = "取消",
                    onSecondaryClick = {
                        batchDeleteMode = false
                        selectedIds = emptySet()
                    },
                    middleText = if (visibleIds.isEmpty()) null else if (allVisibleSelected) "取消全选" else "全选",
                    onMiddleClick = if (visibleIds.isEmpty()) null else {
                        {
                            selectedIds = if (allVisibleSelected) selectedIds - visibleIds else selectedIds + visibleIds
                        }
                    }
                )
            } else {
                BottomActionBar(
                    primaryText = "新增规则",
                    onPrimaryClick = onAdd,
                    secondaryText = "返回",
                    onSecondaryClick = onBack,
                    middleText = if (rules.isEmpty()) null else "批量删除",
                    onMiddleClick = if (rules.isEmpty()) null else {
                        {
                            batchDeleteMode = true
                            selectedIds = emptySet()
                        }
                    }
                )
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 84.dp)
        ) {
            item {
                SettingsCard {
                    InputRow("搜索", "按规则名、会话、群成员或消息类型筛选", query, onValueChange = onQueryChange)
                }
            }
            if (visible.isEmpty()) {
                item { SettingsCard { EmptyText("暂无规则") } }
            } else {
                visible.forEachIndexed { index, rule ->
                    item {
                        SmallTitle(
                            modifier = Modifier.padding(top = if (index == 0) 10.dp else 18.dp),
                            text = "规则 ${index + 1}"
                        )
                    }
                    item {
                        SettingsCard {
                            if (batchDeleteMode) {
                                OptionChoiceRow(
                                    item = OptionItem(rule.name.ifBlank { "未命名规则" }, index, autoMessageForwardRuleSummary(rule)),
                                    selected = rule.id in selectedIds,
                                    onClick = {
                                        selectedIds = if (rule.id in selectedIds) selectedIds - rule.id else selectedIds + rule.id
                                    }
                                )
                            } else {
                                SwitchRow(
                                    checked = rule.enabled,
                                    title = rule.name.ifBlank { "未命名规则" },
                                    summary = autoMessageForwardRuleSummary(rule),
                                    onCheckedChange = { onToggle(rule, it) }
                                )
                                InsetDivider()
                                ActionRow("编辑", "修改监听会话、转发会话和消息类型") { onEdit(rule) }
                                InsetDivider()
                                ActionRow("删除", "移除此规则") { onDelete(rule) }
                            }
                        }
                    }
                }
            }
        }
    }
    BatchDeleteConfirmDialog(
        show = showDeleteConfirm,
        message = "将删除已选的 ${selected.size} 条转发规则，此操作不可撤销。",
        labels = selected.map { it.name.ifBlank { "未命名规则" } },
        onDismiss = { showDeleteConfirm = false },
        onConfirm = {
            val targets = selected
            showDeleteConfirm = false
            batchDeleteMode = false
            selectedIds = emptySet()
            onBatchDelete(targets)
        }
    )
}

@Composable
internal fun AutoMessageForwardRuleEditorPage(
    rule: AutoMessageForwardRule,
    listState: LazyListState,
    onBack: () -> Unit,
    onDraftChange: (AutoMessageForwardRule) -> Unit,
    onPickContacts: (AutoMessageForwardRule, AutoMessageForwardContactField) -> Unit,
    onPickMembers: (AutoMessageForwardRule) -> Unit,
    onPickTypes: (AutoMessageForwardRule) -> Unit,
    onPickReplacementRules: (AutoMessageForwardRule) -> Unit,
    onSave: (AutoMessageForwardRule) -> Unit
) {
    val context = LocalContext.current
    val current = rule
    val scrollBehavior = MiuixScrollBehavior()
    PageScaffold(
        title = "编辑转发规则",
        largeTitle = "编辑转发规则",
        scrollBehavior = scrollBehavior,
        bottomBar = {
            BottomActionBar(
                primaryText = "保存",
                onPrimaryClick = { onSave(current) },
                secondaryText = "返回",
                onSecondaryClick = onBack
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 84.dp)
        ) {
            item { SmallTitle(text = "基础") }
            item {
                SettingsCard {
                    SwitchRow(current.enabled, "启用规则", "关闭后保留配置但不触发") { onDraftChange(current.copy(enabled = it)) }
                    InsetDivider()
                    SwitchRow(current.forwardOwnMessages, "转发自己发送的消息", "默认关闭；模块自动转发的消息不会再次触发") {
                        onDraftChange(current.copy(forwardOwnMessages = it))
                    }
                    InsetDivider()
                    SwitchRow(current.followSourceRecall, "跟随原消息撤回", "原消息撤回后，撤回该规则自动转发的消息") {
                        onDraftChange(current.copy(followSourceRecall = it))
                    }
                    InsetDivider()
                    InputRow("规则名称", "用于列表里识别规则", current.name) { onDraftChange(current.copy(name = it)) }
                    InsetDivider()
                    SwitchRow(current.delayEnabled, "延迟发送", "开启后按设置时间等待再转发") {
                        onDraftChange(current.copy(delayEnabled = it))
                    }
                    if (current.delayEnabled) {
                        InsetDivider()
                        NumberInputRow("延迟时间", "单位秒，0 表示立即发送", current.delaySeconds.toString()) {
                            onDraftChange(current.copy(delaySeconds = it.toLongOrNull()?.coerceAtLeast(0L) ?: 0L))
                        }
                    }
                }
            }
            item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "会话") }
            item {
                SettingsCard {
                    ActionRow("监听会话", autoMessageForwardIdSummary(current.sourceIds, "未选择")) {
                        onPickContacts(current, AutoMessageForwardContactField.SOURCES)
                    }
                    InsetDivider()
                    ActionRow(
                        "群成员范围",
                        if (current.sourceMemberIds.isEmpty()) "不限群成员" else "已选择 ${current.sourceMemberIds.size} 个群成员"
                    ) {
                        if (current.sourceIds.none(::isAutoMessageForwardGroupId)) {
                            Toast.makeText(context, "请先选择监听群聊", Toast.LENGTH_SHORT).show()
                        } else {
                            onPickMembers(current)
                        }
                    }
                    InsetDivider()
                    ActionRow("转发会话", autoMessageForwardIdSummary(current.targetIds, "未选择")) {
                        onPickContacts(current, AutoMessageForwardContactField.TARGETS)
                    }
                }
            }
            item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "消息类型") }
            item {
                SettingsCard {
                    ActionRow("选择消息类型", autoMessageForwardTypeSummary(current.messageKinds)) {
                        onPickTypes(current)
                    }
                    InsetDivider()
                    SwitchRow(current.includeKeywordsEnabled, "包含关键词", "开启后仅转发命中关键词的消息") {
                        onDraftChange(current.copy(includeKeywordsEnabled = it))
                    }
                    if (current.includeKeywordsEnabled) {
                        InsetDivider()
                        InputRow("包含关键词内容", "多个关键词用 |、逗号或换行分隔", current.includeKeywords, minLines = 2) {
                            onDraftChange(current.copy(includeKeywords = it))
                        }
                    }
                    InsetDivider()
                    SwitchRow(current.excludeKeywordsEnabled, "排除关键词", "开启后不转发命中关键词的消息") {
                        onDraftChange(current.copy(excludeKeywordsEnabled = it))
                    }
                    if (current.excludeKeywordsEnabled) {
                        InsetDivider()
                        InputRow("排除关键词内容", "多个关键词用 |、逗号或换行分隔", current.excludeKeywords, minLines = 2) {
                            onDraftChange(current.copy(excludeKeywords = it))
                        }
                    }
                    InsetDivider()
                    SwitchRow(current.replaceKeywordsEnabled, "替换关键词", "转发普通文字前按多条规则替换正文") {
                        onDraftChange(current.copy(replaceKeywordsEnabled = it))
                    }
                    if (current.replaceKeywordsEnabled) {
                        InsetDivider()
                        ActionRow(
                            "替换规则",
                            if (current.keywordReplacements.isEmpty()) {
                                "暂无规则"
                            } else {
                                "已设置 ${current.keywordReplacements.size} 条规则"
                            }
                        ) {
                            onPickReplacementRules(current)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AutoMessageForwardTypePickerPage(
    selectedKinds: Set<String>,
    onBack: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    var selected by remember(selectedKinds) { mutableStateOf(selectedKinds) }
    val options = autoMessageForwardTypeOptions()
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()
    PageScaffold(
        title = "选择消息类型",
        largeTitle = "选择消息类型",
        scrollBehavior = scrollBehavior,
        bottomBar = {
            BottomActionBar(
                primaryText = "保存",
                onPrimaryClick = { onConfirm(selected) },
                secondaryText = "返回",
                onSecondaryClick = onBack,
                middleText = if (selected.size == options.size) "取消全选" else "全选",
                onMiddleClick = {
                    selected = if (selected.size == options.size) emptySet() else options.mapTo(LinkedHashSet()) { it.first }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 84.dp)
        ) {
            item {
                SettingsCard {
                    options.forEachIndexed { index, option ->
                        if (index > 0) InsetDivider()
                        SwitchRow(
                            checked = option.first in selected,
                            title = option.second,
                            summary = option.third
                        ) {
                            selected = if (it) selected + option.first else selected - option.first
                        }
                    }
                }
            }
        }
    }
}

internal fun autoMessageForwardRuleSummary(rule: AutoMessageForwardRule): String {
    return buildString {
        append("监听 ${rule.sourceIds.size} 个会话 → 转发到 ${rule.targetIds.size} 个会话 · ${autoMessageForwardTypeSummary(rule.messageKinds)}")
        if (rule.sourceMemberIds.isNotEmpty()) append(" · 指定 ${rule.sourceMemberIds.size} 个群成员")
        if (rule.forwardOwnMessages) append(" · 含自己发送")
        if (rule.followSourceRecall) append(" · 跟随撤回")
        if (rule.delayEnabled && rule.delaySeconds > 0L) append(" · 延迟 ${rule.delaySeconds} 秒")
        if ((rule.includeKeywordsEnabled && rule.includeKeywords.isNotBlank()) ||
            (rule.excludeKeywordsEnabled && rule.excludeKeywords.isNotBlank()) ||
            (rule.replaceKeywordsEnabled && rule.keywordReplacements.isNotEmpty())
        ) {
            append(" · 已设置关键词")
        }
    }
}

internal fun autoMessageForwardIdSummary(ids: Set<String>, empty: String): String {
    return if (ids.isEmpty()) empty else "已选择 ${ids.size} 个会话"
}

internal fun retainAutoMessageForwardSourceMembers(
    memberIds: Set<String>,
    sourceIds: Set<String>
): Set<String> = memberIds.filterTo(linkedSetOf()) { it.substringBefore('/') in sourceIds }

internal fun isAutoMessageForwardGroupId(value: String): Boolean =
    value.endsWith("@chatroom") || value.endsWith("@im.chatroom")

internal fun autoMessageForwardTypeSummary(kinds: Set<String>): String {
    val options = autoMessageForwardTypeOptions().associate { it.first to it.second }
    val labels = kinds.mapNotNull { options[it] }
    return when {
        labels.isEmpty() -> "未选择"
        labels.size == options.size -> "全部支持类型"
        labels.size <= 3 -> labels.joinToString("、")
        else -> "${labels.take(3).joinToString("、")}等 ${labels.size} 类"
    }
}

internal fun autoMessageForwardTypeOptions(): List<Triple<String, String, String>> {
    return listOf(
        Triple(WeChatMessageObserveApi.Kind.TEXT, "文字", "普通文字消息"),
        Triple(WeChatMessageObserveApi.Kind.IMAGE, "图片", "图片消息"),
        Triple(WeChatMessageObserveApi.Kind.VOICE, "语音", "语音消息"),
        Triple(WeChatMessageObserveApi.Kind.VIDEO, "视频", "视频消息"),
        Triple(WeChatMessageObserveApi.Kind.EMOJI, "表情", "动画表情消息"),
        Triple(WeChatMessageObserveApi.Kind.QUOTE, "引用", "引用消息"),
        Triple(WeChatMessageObserveApi.Kind.FILE, "文件", "已下载到本地的文件"),
        Triple(WeChatMessageObserveApi.Kind.LINK, "链接", "网页链接卡片"),
        Triple(WeChatMessageObserveApi.Kind.MUSIC, "音乐", "音乐卡片"),
        Triple(WeChatMessageObserveApi.Kind.APP, "小程序及卡片", "其它可转发的应用消息"),
        Triple(WeChatMessageObserveApi.Kind.LOCATION, "位置", "位置消息"),
        Triple(WeChatMessageObserveApi.Kind.SHARE_CARD, "名片", "联系人名片"),
        Triple(WeChatMessageObserveApi.Kind.NOTE, "聊天记录", "聊天记录等笔记消息"),
        Triple(WeChatMessageObserveApi.Kind.VIDEO_NUMBER_VIDEO, "视频号", "视频号消息")
    )
}
}

@Composable
internal fun AutoReplyMainPage(
    provider: FeatureSettingsProvider,
    sp: SharedPreferences,
    rules: List<AutoReplyRule>,
    onBack: () -> Unit,
    onOpenRules: () -> Unit,
    onOpenAutoAccept: () -> Unit,
    onOpenGreetAccepted: () -> Unit,
    onOpenAi: () -> Unit
) {
    var excludedTalkersEnabled by remember {
        mutableStateOf(sp.getBoolean(AutoReplySettings.KEY_EXCLUDED_TALKERS_ENABLE, false))
    }
    var excludedTalkers by remember {
        mutableStateOf(sp.getString(AutoReplySettings.KEY_EXCLUDED_TALKERS, "").orEmpty())
    }
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()
    PageScaffold(
        title = provider.title(),
        largeTitle = provider.title(),
        scrollBehavior = scrollBehavior,
        bottomBar = { BottomActionBar("返回", onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "聊天自动回复") }
            item {
                SettingsCard {
                    SwitchRow(sp, AutoReplySettings.KEY_ENABLE, "启用自动回复", "开启后按规则回复收到的消息", AutoReplySettings.DEFAULT_ENABLE)
                    InsetDivider()
                    SwitchRow(excludedTalkersEnabled, "排除指定会话", "开启后不回复指定会话的消息") {
                        excludedTalkersEnabled = it
                        sp.edit().putBoolean(AutoReplySettings.KEY_EXCLUDED_TALKERS_ENABLE, it).apply()
                    }
                    if (excludedTalkersEnabled) {
                        InsetDivider()
                        InputRow("排除会话 ID", "多个 ID 用逗号、分号或换行分隔", excludedTalkers, minLines = 2) {
                            excludedTalkers = it
                            sp.edit().putString(AutoReplySettings.KEY_EXCLUDED_TALKERS, it).apply()
                        }
                    }
                    InsetDivider()
                    ActionRow("回复规则", if (rules.isEmpty()) "暂无规则" else "${rules.size} 条规则，${rules.count { it.enabled }} 条启用", onOpenRules)
                    InsetDivider()
                    ActionRow("好友请求处理", "自动同意好友申请并按步骤发送欢迎内容", onOpenAutoAccept)
                    InsetDivider()
                    ActionRow("通过后欢迎语", "对方通过你的好友请求后发送欢迎内容", onOpenGreetAccepted)
                    InsetDivider()
                    ActionRow("AI 配置", "配置小智AI和智聊AI，多智聊模型可切换", onOpenAi)
                }
            }
        }
    }
}

@Composable
internal fun AutoReplyRuleListPage(
    rules: List<AutoReplyRule>,
    query: String,
    onQueryChange: (String) -> Unit,
    listState: LazyListState,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (AutoReplyRule) -> Unit,
    onToggle: (AutoReplyRule, Boolean) -> Unit,
    onDelete: (AutoReplyRule) -> Unit,
    onBatchDelete: (List<AutoReplyRule>) -> Unit
) {
    val context = LocalContext.current
    var batchDeleteMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val visible = rules.filter {
        val lower = query.trim().lowercase(Locale.US)
        lower.isBlank() ||
            it.name.lowercase(Locale.US).contains(lower) ||
            it.keyword.lowercase(Locale.US).contains(lower) ||
            it.excludedKeywords.lowercase(Locale.US).contains(lower)
    }
    val visibleIds = visible.mapTo(LinkedHashSet()) { it.id }
    val allVisibleSelected = visibleIds.isNotEmpty() && visibleIds.all { it in selectedIds }
    val selectedRules = rules.filter { it.id in selectedIds }
    val scrollBehavior = MiuixScrollBehavior()
    PageScaffold(
        title = "回复规则",
        largeTitle = "回复规则",
        scrollBehavior = scrollBehavior,
        bottomBar = {
            if (batchDeleteMode) {
                BottomActionBar(
                    primaryText = "删除所选（${selectedRules.size}）",
                    onPrimaryClick = {
                        if (selectedRules.isEmpty()) {
                            Toast.makeText(context, "请先选择规则", Toast.LENGTH_SHORT).show()
                        } else {
                            showDeleteConfirm = true
                        }
                    },
                    secondaryText = "取消",
                    onSecondaryClick = {
                        batchDeleteMode = false
                        selectedIds = emptySet()
                    },
                    middleText = if (visibleIds.isEmpty()) null else if (allVisibleSelected) "取消全选" else "全选",
                    onMiddleClick = if (visibleIds.isEmpty()) null else {
                        {
                            selectedIds = if (allVisibleSelected) {
                                selectedIds - visibleIds
                            } else {
                                selectedIds + visibleIds
                            }
                        }
                    }
                )
            } else {
                BottomActionBar(
                    primaryText = "新增规则",
                    onPrimaryClick = onAdd,
                    secondaryText = "返回",
                    onSecondaryClick = onBack,
                    middleText = if (rules.isEmpty()) null else "批量删除",
                    onMiddleClick = if (rules.isEmpty()) null else {
                        {
                            batchDeleteMode = true
                            selectedIds = emptySet()
                        }
                    }
                )
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 84.dp)
        ) {
            item {
                SettingsCard {
                    InputRow("搜索", "按规则名或关键词筛选", query, onValueChange = onQueryChange)
                }
            }
            if (visible.isEmpty()) {
                item { SettingsCard { EmptyText("暂无规则") } }
            } else {
                visible.forEachIndexed { index, rule ->
                    item {
                        SmallTitle(
                            modifier = Modifier.padding(top = if (index == 0) 10.dp else 18.dp),
                            text = "规则 ${index + 1}"
                        )
                    }
                    item {
                        SettingsCard {
                            if (batchDeleteMode) {
                                OptionChoiceRow(
                                    item = OptionItem(
                                        label = rule.name.ifBlank { "未命名规则" },
                                        value = index,
                                        summary = describeAutoReplyRule(rule)
                                    ),
                                    selected = rule.id in selectedIds,
                                    onClick = {
                                        selectedIds = if (rule.id in selectedIds) {
                                            selectedIds - rule.id
                                        } else {
                                            selectedIds + rule.id
                                        }
                                    }
                                )
                            } else {
                                SwitchRow(
                                    checked = rule.enabled,
                                    title = rule.name.ifBlank { "未命名规则" },
                                    summary = describeAutoReplyRule(rule),
                                    onCheckedChange = { onToggle(rule, it) }
                                )
                                InsetDivider()
                                ActionRow("编辑", "修改触发条件和回复步骤") { onEdit(rule) }
                                InsetDivider()
                                ActionRow("删除", "移除此规则") { onDelete(rule) }
                            }
                        }
                    }
                }
            }
        }
    }
    BatchDeleteConfirmDialog(
        show = showDeleteConfirm,
        message = "将删除已选的 ${selectedRules.size} 条回复规则，此操作不可撤销。",
        labels = selectedRules.map { it.name.ifBlank { "未命名规则" } },
        onDismiss = { showDeleteConfirm = false },
        onConfirm = {
            val targets = selectedRules
            showDeleteConfirm = false
            batchDeleteMode = false
            selectedIds = emptySet()
            onBatchDelete(targets)
        }
    )
}

@Composable
internal fun AutoReplyRuleEditorPage(
    rule: AutoReplyRule,
    listState: LazyListState,
    onBack: () -> Unit,
    onSave: (AutoReplyRule) -> Unit,
    onDraftChange: (AutoReplyRule) -> Unit,
    onEditSteps: (AutoReplyRule) -> Unit
) {
    val context = LocalContext.current
    var current by remember(rule) { mutableStateOf(rule) }
    var optionPicker by remember { mutableStateOf<OptionPickerRequest?>(null) }
    var contactPicker by remember { mutableStateOf<ContactPickerRequest?>(null) }
    var groupMemberPicker by remember { mutableStateOf<GroupMemberPickerRequest?>(null) }
    val editorRoute = when {
        optionPicker != null -> AutoReplyEditorRoute.OptionPicker(optionPicker!!)
        contactPicker != null -> AutoReplyEditorRoute.ContactPicker(contactPicker!!)
        groupMemberPicker != null -> AutoReplyEditorRoute.GroupMemberPicker(groupMemberPicker!!)
        else -> AutoReplyEditorRoute.Main
    }
    SettingsRouteTransition(
        targetState = editorRoute,
        label = "AutoReplyEditorRoute",
        depthOf = { it.depth() }
    ) { route ->
        when (route) {
            is AutoReplyEditorRoute.OptionPicker -> {
                val request = route.request
                OptionPickerPage(
                    request = request,
                    onBack = { optionPicker = null },
                    onSelected = { request.onSelected(it); optionPicker = null }
                )
            }
            is AutoReplyEditorRoute.ContactPicker -> {
                val request = route.request
                ContactPickerPage(
                    context = context,
                    request = request,
                    onBack = { contactPicker = null },
                    onConfirm = { selected ->
                        request.onValue(formatIds(selected.map { it.id }))
                        Toast.makeText(context, "已选择 ${selected.size} 项", Toast.LENGTH_SHORT).show()
                        contactPicker = null
                    }
                )
            }
            is AutoReplyEditorRoute.GroupMemberPicker -> {
                val request = route.request
                GroupMemberPickerPage(
                    context = context,
                    request = request,
                    onBack = { groupMemberPicker = null },
                    onConfirm = { entries ->
                        request.onValue(formatIds(entries))
                        Toast.makeText(context, "已选择 ${entries.size} 个群成员", Toast.LENGTH_SHORT).show()
                        groupMemberPicker = null
                    }
                )
            }
            AutoReplyEditorRoute.Main -> AutoReplyRuleEditorContent(
                rule = current,
                listState = listState,
                onRuleChange = {
                    current = it
                    onDraftChange(it)
                },
                onBack = onBack,
                onSave = { onSave(current) },
                onEditSteps = { onEditSteps(current) },
                onOptionPicker = { optionPicker = it },
                onContactPicker = { contactPicker = it },
                onGroupMemberPicker = { groupMemberPicker = it }
            )
        }
    }
}

@Composable
internal fun AutoReplyRuleEditorContent(
    rule: AutoReplyRule,
    listState: LazyListState,
    onRuleChange: (AutoReplyRule) -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onEditSteps: () -> Unit,
    onOptionPicker: (OptionPickerRequest) -> Unit,
    onContactPicker: (ContactPickerRequest) -> Unit,
    onGroupMemberPicker: (GroupMemberPickerRequest) -> Unit
) {
    val current = rule
    val scrollBehavior = MiuixScrollBehavior()
    PageScaffold(
        title = "编辑规则",
        largeTitle = "编辑规则",
        scrollBehavior = scrollBehavior,
        bottomBar = {
            BottomActionBar(
                primaryText = "保存",
                onPrimaryClick = onSave,
                secondaryText = "返回",
                onSecondaryClick = onBack
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 84.dp)
        ) {
            item { SmallTitle(text = "基础") }
            item {
                SettingsCard {
                    SwitchRow(current.enabled, "启用规则", "关闭后保留配置但不触发") { onRuleChange(current.copy(enabled = it)) }
                    InsetDivider()
                    InputRow("规则名称", "用于列表里识别规则", current.name) { onRuleChange(current.copy(name = it)) }
                    InsetDivider()
                    PopupOptionRow(
                        title = "匹配方式",
                        summary = autoReplyMatchLabel(current.matchType),
                        options = autoReplyMatchOptions(),
                        currentValue = current.matchType,
                        onValueChanged = {
                            onRuleChange(
                                current.copy(
                                    matchType = it,
                                    keyword = if (it == AutoReplySettings.MATCH_ANY) "" else current.keyword
                                )
                            )
                        }
                    )
                    if (current.matchType != AutoReplySettings.MATCH_ANY) {
                        InsetDivider()
                        InputRow("关键词", "多个关键词用 |、逗号或换行分隔", current.keyword, minLines = 2) {
                            onRuleChange(current.copy(keyword = it))
                        }
                    } else {
                        InsetDivider()
                        InputRow("排除关键词", "包含任一关键词时不触发，多个用 |、逗号或换行分隔", current.excludedKeywords, minLines = 2) {
                            onRuleChange(current.copy(excludedKeywords = it))
                        }
                    }
                }
            }
            item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "范围") }
            item {
                SettingsCard {
                    PopupOptionRow(
                        title = "生效范围",
                        summary = autoReplyTargetLabel(current.targetMode),
                        options = autoReplyTargetOptions(),
                        currentValue = current.targetMode,
                        onValueChanged = { onRuleChange(current.copy(targetMode = it)) }
                    )
                    if (current.targetMode == AutoReplySettings.TARGET_SPECIFIC) {
                        InsetDivider()
                        ActionRow("选择指定好友", autoReplySelectedIdSummary(formatIds(current.targetIds.filter { !isAutoReplyGroupId(it) }))) {
                            onContactPicker(ContactPickerRequest(
                                title = "选择指定好友",
                                mode = ContactPickerMode.FRIENDS,
                                multiSelect = true,
                                existingValue = formatIds(current.targetIds.filter { !isAutoReplyGroupId(it) }),
                                onValue = { onRuleChange(current.copy(targetIds = replaceAutoReplyTypedIds(current.targetIds, parseIds(it), group = false))) },
                                enableLabels = true
                            ))
                        }
                        InsetDivider()
                        ActionRow("选择指定群聊", autoReplySelectedIdSummary(formatIds(current.targetIds.filter { isAutoReplyGroupId(it) }))) {
                            onContactPicker(ContactPickerRequest(
                                title = "选择指定群聊",
                                mode = ContactPickerMode.GROUPS,
                                multiSelect = true,
                                existingValue = formatIds(current.targetIds.filter { isAutoReplyGroupId(it) }),
                                onValue = { onRuleChange(current.copy(targetIds = replaceAutoReplyTypedIds(current.targetIds, parseIds(it), group = true))) }
                            ))
                        }
                        InsetDivider()
                        ActionRow("选择指定群成员", autoReplySelectedIdSummary(formatIds(current.includedGroupMembers))) {
                            onGroupMemberPicker(GroupMemberPickerRequest(
                                title = "选择指定群成员",
                                existingValue = formatIds(current.includedGroupMembers),
                                onValue = { onRuleChange(current.copy(includedGroupMembers = parseIds(it))) }
                            ))
                        }
                    } else {
                        InsetDivider()
                        ActionRow("选择排除好友", autoReplySelectedIdSummary(formatIds(current.excludedIds.filter { !isAutoReplyGroupId(it) }))) {
                            onContactPicker(ContactPickerRequest(
                                title = "选择排除好友",
                                mode = ContactPickerMode.FRIENDS,
                                multiSelect = true,
                                existingValue = formatIds(current.excludedIds.filter { !isAutoReplyGroupId(it) }),
                                onValue = { onRuleChange(current.copy(excludedIds = replaceAutoReplyTypedIds(current.excludedIds, parseIds(it), group = false))) },
                                enableLabels = true
                            ))
                        }
                        InsetDivider()
                        ActionRow("选择排除群聊", autoReplySelectedIdSummary(formatIds(current.excludedIds.filter { isAutoReplyGroupId(it) }))) {
                            onContactPicker(ContactPickerRequest(
                                title = "选择排除群聊",
                                mode = ContactPickerMode.GROUPS,
                                multiSelect = true,
                                existingValue = formatIds(current.excludedIds.filter { isAutoReplyGroupId(it) }),
                                onValue = { onRuleChange(current.copy(excludedIds = replaceAutoReplyTypedIds(current.excludedIds, parseIds(it), group = true))) }
                            ))
                        }
                        InsetDivider()
                        ActionRow("选择排除群成员", autoReplySelectedIdSummary(formatIds(current.excludedGroupMembers))) {
                            onGroupMemberPicker(GroupMemberPickerRequest(
                                title = "选择排除群成员",
                                existingValue = formatIds(current.excludedGroupMembers),
                                onValue = { onRuleChange(current.copy(excludedGroupMembers = parseIds(it))) }
                            ))
                        }
                    }
                }
            }
            item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "触发") }
            item {
                SettingsCard {
                    PopupOptionRow(
                        title = "@ 触发",
                        summary = autoReplyAtLabel(current.atTrigger),
                        options = autoReplyAtOptions(),
                        currentValue = current.atTrigger,
                        onValueChanged = { onRuleChange(current.copy(atTrigger = it)) }
                    )
                    InsetDivider()
                    PopupOptionRow(
                        title = "拍一拍",
                        summary = autoReplyPatLabel(current.patTrigger),
                        options = autoReplyPatOptions(),
                        currentValue = current.patTrigger,
                        onValueChanged = { onRuleChange(current.copy(patTrigger = it)) }
                    )
                    InsetDivider()
                    TimeOfDayPickerRow("开始时间", current.startTime, allowEmpty = true) {
                        onRuleChange(current.copy(startTime = it))
                    }
                    InsetDivider()
                    TimeOfDayPickerRow("结束时间", current.endTime, allowEmpty = true) {
                        onRuleChange(current.copy(endTime = it))
                    }
                    InsetDivider()
                    NumberInputRow("最大回复次数", "0 表示不限制，按规则/会话/发送者统计", current.maxReplyCount.toString()) {
                        onRuleChange(current.copy(maxReplyCount = it.toIntOrNull()?.coerceAtLeast(0) ?: 0))
                    }
                    InsetDivider()
                    NumberInputRow("回复冷却时间", "单位秒，0 表示不限制；同一规则在同一会话内冷却", current.cooldownSeconds.toString()) {
                        onRuleChange(current.copy(cooldownSeconds = it.toLongOrNull()?.coerceAtLeast(0L) ?: 0L))
                    }
                }
            }
            item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "回复") }
            item {
                SettingsCard {
                    SwitchRow(current.replyAsQuote, "引用回复", "文本和 AI 可引用原消息回复") { onRuleChange(current.copy(replyAsQuote = it)) }
                    InsetDivider()
                    ActionRow("回复步骤", describeAutoReplySteps(current.steps), onEditSteps)
                }
            }
        }
    }
}

@Composable
internal fun AutoReplyFriendPage(
    context: Context,
    title: String,
    sp: SharedPreferences,
    enabledKey: String,
    delayKey: String,
    tagEnabledKey: String,
    tagNameKey: String,
    steps: List<AutoReplyStep>,
    listState: LazyListState,
    onBack: () -> Unit,
    automationKeys: AutoReplyFriendAutomationKeys?,
    onSelectLabels: (() -> Unit)?,
    onEditSteps: () -> Unit
) {
    var delayMs by remember { mutableStateOf(sp.getLong(delayKey, 2000L).coerceAtLeast(0L).toString()) }
    var tagName by remember { mutableStateOf(sp.getString(tagNameKey, "") ?: "") }
    var labelDateFormat by remember {
        mutableStateOf(sp.getString(automationKeys?.labelDateFormat ?: "", "yyyy-MM-dd") ?: "yyyy-MM-dd")
    }
    val selectedLabelNames = sp.getString(automationKeys?.labelSelectedNames ?: "", "") ?: ""
    var remarkDateFormat by remember {
        mutableStateOf(sp.getString(automationKeys?.remarkDateFormat ?: "", "yyMMdd") ?: "yyMMdd")
    }
    var remarkCustomText by remember {
        mutableStateOf(sp.getString(automationKeys?.remarkCustomText ?: "", "") ?: "")
    }
    val hasAutomation = automationKeys != null && onSelectLabels != null
    var labelDateEnabled by remember { mutableStateOf(automationKeys?.let { sp.getBoolean(it.labelDateEnable, false) } ?: false) }
    var labelExistingEnabled by remember { mutableStateOf(automationKeys?.let { sp.getBoolean(it.labelExistingEnable, false) } ?: false) }
    var remarkDateEnabled by remember { mutableStateOf(automationKeys?.let { sp.getBoolean(it.remarkDateEnable, false) } ?: false) }
    var remarkCustomEnabled by remember { mutableStateOf(automationKeys?.let { sp.getBoolean(it.remarkCustomEnable, false) } ?: false) }
    var tagEnabled by remember { mutableStateOf(sp.getBoolean(tagEnabledKey, false)) }
    val scrollBehavior = MiuixScrollBehavior()
    PageScaffold(
        title = title,
        largeTitle = title,
        scrollBehavior = scrollBehavior,
        bottomBar = {
            BottomActionBar(
                primaryText = "保存",
                onPrimaryClick = {
                    sp.edit()
                        .putLong(delayKey, delayMs.toLongOrNull()?.coerceIn(0L, 600000L) ?: 0L)
                        .putString(tagNameKey, tagName.trim())
                        .also { editor ->
                            automationKeys?.let { keys ->
                                editor.putString(keys.labelDateFormat, labelDateFormat.trim().ifBlank { "yyyy-MM-dd" })
                                editor.putString(keys.remarkDateFormat, remarkDateFormat.trim().ifBlank { "yyMMdd" })
                                editor.putString(keys.remarkCustomText, remarkCustomText.trim())
                            }
                        }
                        .apply()
                    Toast.makeText(context, "设置已保存", Toast.LENGTH_SHORT).show()
                },
                secondaryText = "返回",
                onSecondaryClick = onBack
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 84.dp)
        ) {
            item {
                SettingsCard {
                    SwitchRow(sp, enabledKey, "启用", "开启后自动处理这个场景", false)
                    InsetDivider()
                    NumberInputRow("延迟发送", "单位 ms，通过好友后等待再发欢迎内容", delayMs) { delayMs = it }
                    if (hasAutomation) {
                        val keys = automationKeys!!
                        InsetDivider()
                        SwitchRow(sp, keys.labelNewFriendEnable, "自动标签新加好友", "给好友添加“新加好友”标签", false)
                        InsetDivider()
                        SwitchRow(labelDateEnabled, "自动标签日期", "按日期格式生成一个标签") {
                            labelDateEnabled = it
                            sp.edit().putBoolean(keys.labelDateEnable, it).apply()
                        }
                        if (labelDateEnabled) {
                            InsetDivider()
                            InputRow("标签日期格式", "例如 yyyy-MM-dd", labelDateFormat) { labelDateFormat = it }
                        }
                        InsetDivider()
                        SwitchRow(labelExistingEnabled, "自动标签已有标签", "把好友加入已选择的微信标签") {
                            labelExistingEnabled = it
                            sp.edit().putBoolean(keys.labelExistingEnable, it).apply()
                        }
                        if (labelExistingEnabled) {
                            InsetDivider()
                            ActionRow("选择标签", autoReplyLabelSummary(selectedLabelNames), onSelectLabels!!)
                        }
                        InsetDivider()
                        SwitchRow(sp, keys.remarkNewFriendEnable, "自动备注新加好友", "备注里加入“新加好友”", false)
                        InsetDivider()
                        SwitchRow(sp, keys.remarkNicknameSuffixEnable, "加昵称后面", "备注格式为微信昵称 + 备注内容", false)
                        InsetDivider()
                        SwitchRow(remarkDateEnabled, "自动备注日期", "备注里加入日期") {
                            remarkDateEnabled = it
                            sp.edit().putBoolean(keys.remarkDateEnable, it).apply()
                        }
                        if (remarkDateEnabled) {
                            InsetDivider()
                            InputRow("备注日期格式", "例如 yyMMdd", remarkDateFormat) { remarkDateFormat = it }
                        }
                        InsetDivider()
                        SwitchRow(remarkCustomEnabled, "自动备注自定义文本", "备注里加入自定义内容") {
                            remarkCustomEnabled = it
                            sp.edit().putBoolean(keys.remarkCustomEnable, it).apply()
                        }
                        if (remarkCustomEnabled) {
                            InsetDivider()
                            InputRow("备注自定义文本", "例如 渠道A", remarkCustomText) { remarkCustomText = it }
                        }
                    } else {
                        InsetDivider()
                        SwitchRow(tagEnabled, "自动打标签", "处理后把好友加入指定标签") {
                            tagEnabled = it
                            sp.edit().putBoolean(tagEnabledKey, it).apply()
                        }
                        if (tagEnabled) {
                            InsetDivider()
                            InputRow("标签名称", "标签需要微信里已存在", tagName) { tagName = it }
                        }
                    }
                    InsetDivider()
                    ActionRow("回复步骤", describeAutoReplySteps(steps), onEditSteps)
                }
            }
        }
    }
}

@Composable
internal fun AutoReplyLabelPickerPage(
    context: Context,
    sp: SharedPreferences,
    selectedKey: String,
    listState: LazyListState,
    onBack: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var labels by remember { mutableStateOf<List<ContactLabelBean>>(emptyList()) }
    var selected by remember {
        mutableStateOf(splitAutoReplyNames(sp.getString(selectedKey, "") ?: "").toSet())
    }
    LaunchedEffect(Unit) {
        loading = true
        error = ""
        val api = WeChatApis.contact().contacts()
        if (api == null || !api.isAvailable) {
            labels = emptyList()
            error = "联系人标签不可用"
            loading = false
        } else {
            Thread({
                val result = runCatching { api.getContactLabelList() }
                Handler(Looper.getMainLooper()).post {
                    labels = result.getOrDefault(emptyList())
                        .filter { it.labelName.isNotBlank() || it.labelId.isNotBlank() }
                        .sortedBy { it.labelName.ifBlank { it.labelId }.lowercase(Locale.US) }
                    error = result.exceptionOrNull()?.message.orEmpty()
                    loading = false
                }
            }, "HchatAutoReplyLabels").start()
        }
    }
    val visible = labels.filter { label ->
        val name = label.labelName.ifBlank { label.labelId }
        val key = query.trim().lowercase(Locale.US)
        key.isBlank() || name.lowercase(Locale.US).contains(key)
    }
    val scrollBehavior = MiuixScrollBehavior()
    PageScaffold(
        title = "选择标签",
        largeTitle = "选择标签",
        scrollBehavior = scrollBehavior,
        bottomBar = {
            BottomActionBar(
                primaryText = "保存",
                onPrimaryClick = {
                    sp.edit()
                        .putString(selectedKey, selected.joinToString(";;;"))
                        .apply()
                    Toast.makeText(context, "标签已保存", Toast.LENGTH_SHORT).show()
                    onBack()
                },
                secondaryText = "返回",
                onSecondaryClick = onBack
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 84.dp)
        ) {
            item { SmallTitle(text = "搜索") }
            item {
                SettingsCard {
                    InputRow("关键词", "按标签名筛选", query) { query = it }
                }
            }
            item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "标签") }
            when {
                loading -> item { SettingsCard { EmptyText("正在载入标签...") } }
                error.isNotEmpty() -> item { SettingsCard { EmptyText(error) } }
                visible.isEmpty() -> item { SettingsCard { EmptyText("没有匹配标签") } }
                else -> visible.forEach { label ->
                    val name = label.labelName.ifBlank { label.labelId }
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
                            cornerRadius = 14.dp
                        ) {
                            AutoReplyLabelRow(
                                name = name,
                                summary = "${label.userNameList.size} 人",
                                selected = selected.contains(name),
                                onClick = {
                                    selected = if (selected.contains(name)) selected - name else selected + name
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AutoReplyLabelRow(
    name: String,
    summary: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    val pressFeedbackColor = rememberPressFeedbackColor(pressed)
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(pressFeedbackColor)
            .responsiveTap(
                onClick = onClick,
                onPressedChange = { pressed = it }
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MiuixTheme.colorScheme.secondaryVariant),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = name.take(1).ifEmpty { "签" },
                color = MiuixTheme.colorScheme.onSecondaryVariant,
                fontWeight = FontWeight.Bold
            )
        }
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(text = name, color = MiuixTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
            Text(text = summary, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 12.sp)
        }
        SelectionMark(selected = selected, multiSelect = true)
    }
}

internal fun autoReplyLabelSummary(value: String): String {
    val names = splitAutoReplyNames(value)
    return if (names.isEmpty()) "未选择" else names.take(3).joinToString("、") + if (names.size > 3) " 等 ${names.size} 个" else ""
}

@Composable
internal fun XiaozhiCaptchaSvgView(svg: String) {
    AndroidView(
        modifier = Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MiuixTheme.colorScheme.secondaryVariant),
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(AndroidColor.TRANSPARENT)
                settings.javaScriptEnabled = false
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
            }
        },
        update = { view ->
            view.loadDataWithBaseURL(null, svg, "image/svg+xml", "UTF-8", null)
        }
    )
}

internal fun xiaozhiAgentSummary(agentId: String, agents: List<XiaozhiAgentOption>): String {
    if (agentId.isBlank()) return "未选择，点击拉取"
    val agent = agents.firstOrNull { it.id == agentId }
    return if (agent == null) "已保存 ID: $agentId" else "${agent.name} / ${agent.assistantName.ifBlank { agent.id }}"
}

internal fun xiaozhiModelSummary(model: String, models: List<XiaozhiModelOption>): String {
    if (model.isBlank()) return "未选择，点击拉取"
    val item = models.firstOrNull { it.id == model }
    return if (item == null) "已保存: $model" else "${item.name} / ${item.id}"
}

internal fun xiaozhiVoiceSummary(voice: String, voices: List<XiaozhiVoiceOption>): String {
    if (voice.isBlank()) return "未选择，留空使用控制台当前角色"
    val item = voices.firstOrNull { it.id == voice }
    return if (item == null) "已保存: $voice" else "${item.name} / ${item.id}"
}

internal fun splitAutoReplyNames(value: String): List<String> =
    value.split(";;;", "|", "\n")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

@Composable
internal fun AutoReplyAiPage(context: Context, sp: SharedPreferences, onBack: () -> Unit) {
    val settings = remember { AutoReplySettings(context) }
    val scope = rememberCoroutineScope()
    var xiaozhiServe by remember { mutableStateOf(settings.xiaozhiConfig().serveUrl) }
    var xiaozhiOta by remember { mutableStateOf(settings.xiaozhiConfig().otaUrl) }
    var xiaozhiConsole by remember { mutableStateOf(settings.xiaozhiConfig().consoleUrl) }
    var xiaozhiConsolePhone by remember { mutableStateOf(settings.xiaozhiConfig().consolePhone) }
    var xiaozhiConsoleToken by remember { mutableStateOf(settings.xiaozhiConfig().consoleToken) }
    var xiaozhiConsoleAgentId by remember { mutableStateOf(settings.xiaozhiConfig().consoleAgentId) }
    var xiaozhiConsoleModel by remember { mutableStateOf(settings.xiaozhiConfig().consoleModel) }
    var xiaozhiVoiceRole by remember { mutableStateOf(settings.xiaozhiConfig().voiceRole) }
    var xiaozhiMusicMcp by remember { mutableStateOf(settings.xiaozhiConfig().musicMcpEnabled) }
    var xiaozhiMcpBridge by remember { mutableStateOf(settings.xiaozhiConfig().mcpBridgeEnabled) }
    var xiaozhiMcpEndpoint by remember { mutableStateOf(settings.xiaozhiConfig().mcpEndpointUrl) }
    var xiaozhiMcpKugouEnabled by remember { mutableStateOf(settings.xiaozhiConfig().mcpKugouEnabled) }
    var xiaozhiMcpKugouPluginId by remember { mutableStateOf(settings.xiaozhiConfig().mcpKugouPluginId) }
    var xiaozhiMcpKugouFunction by remember { mutableStateOf(settings.xiaozhiConfig().mcpKugouFunctionName) }
    var xiaozhiMcpReadySeconds by remember { mutableStateOf(settings.xiaozhiConfig().mcpReadySeconds.toString()) }
    var xiaozhiMcpIdleSeconds by remember { mutableStateOf(settings.xiaozhiConfig().mcpIdleSeconds.toString()) }
    var xiaozhiMcpStatus by remember { mutableStateOf(XiaozhiMcpStatus(false, "未查询", "点击刷新查询小智控制台")) }
    var configs by remember { mutableStateOf(settings.zhiliaConfigs()) }
    var activeName by remember { mutableStateOf(settings.activeZhiliaName()) }
    val activeConfig = configs.firstOrNull { it.name == activeName } ?: configs.firstOrNull() ?: settings.activeZhiliaConfig()
    var cfgName by remember(activeName, configs) { mutableStateOf(activeConfig.name) }
    var apiKey by remember(activeName, configs) { mutableStateOf(activeConfig.apiKey) }
    var apiBase by remember(activeName, configs) { mutableStateOf(activeConfig.apiBaseUrl) }
    var apiPath by remember(activeName, configs) { mutableStateOf(activeConfig.apiPath) }
    var model by remember(activeName, configs) { mutableStateOf(activeConfig.model) }
    var prompt by remember(activeName, configs) { mutableStateOf(activeConfig.systemPrompt) }
    var contextLimit by remember(activeName, configs) { mutableStateOf(activeConfig.contextLimit.toString()) }
    var modelQuery by remember { mutableStateOf("") }
    var fetchedModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var favoriteModels by remember(apiBase) { mutableStateOf(settings.favoriteModels(apiBase)) }
    var loadingText by remember { mutableStateOf("") }
    var testResult by remember { mutableStateOf("") }
    var xiaozhiBindResult by remember { mutableStateOf("") }
    var xiaozhiCaptchaSvg by remember { mutableStateOf("") }
    var xiaozhiCaptchaCode by remember { mutableStateOf("") }
    var xiaozhiSmsCode by remember { mutableStateOf("") }
    var xiaozhiConsoleResult by remember { mutableStateOf("") }
    var xiaozhiAgents by remember { mutableStateOf<List<XiaozhiAgentOption>>(emptyList()) }
    var xiaozhiModels by remember { mutableStateOf<List<XiaozhiModelOption>>(emptyList()) }
    var xiaozhiVoices by remember { mutableStateOf<List<XiaozhiVoiceOption>>(emptyList()) }
    var route by remember { mutableStateOf<AutoReplyAiRoute>(AutoReplyAiRoute.Main) }
    var zhiliaPendingName by remember { mutableStateOf(activeName) }
    var pendingModel by remember { mutableStateOf(model) }
    val mainListState = rememberLazyListState()
    val xiaozhiListState = rememberLazyListState()
    val zhiliaListState = rememberLazyListState()
    val zhiliaConfigListState = rememberLazyListState()
    val zhiliaModelListState = rememberLazyListState()

    fun currentZhiliaConfig(): AutoReplyZhiliaConfig {
        return AutoReplyZhiliaConfig(
            name = cfgName,
            apiKey = apiKey.trim(),
            apiBaseUrl = apiBase.trim(),
            apiPath = apiPath.trim(),
            model = model.trim(),
            systemPrompt = prompt,
            contextLimit = contextLimit.toIntOrNull() ?: AutoReplySettings.DEFAULT_AI_CONTEXT_LIMIT
        ).normalized()
    }

    fun currentXiaozhiConfig(): AutoReplyXiaozhiConfig {
        return AutoReplyXiaozhiConfig(
            serveUrl = xiaozhiServe,
            otaUrl = xiaozhiOta,
            consoleUrl = xiaozhiConsole,
            consolePhone = xiaozhiConsolePhone,
            consoleToken = xiaozhiConsoleToken,
            consoleAgentId = xiaozhiConsoleAgentId,
            consoleModel = xiaozhiConsoleModel,
            voiceRole = xiaozhiVoiceRole,
            musicMcpEnabled = xiaozhiMusicMcp,
            mcpBridgeEnabled = xiaozhiMcpBridge,
            mcpEndpointUrl = xiaozhiMcpEndpoint,
            mcpKugouEnabled = xiaozhiMcpKugouEnabled,
            mcpKugouPluginId = xiaozhiMcpKugouPluginId,
            mcpKugouFunctionName = xiaozhiMcpKugouFunction,
            mcpReadySeconds = xiaozhiMcpReadySeconds.toIntOrNull()?.coerceIn(1, 30)
                ?: AutoReplySettings.DEFAULT_XIAOZHI_MCP_READY_SECONDS,
            mcpIdleSeconds = xiaozhiMcpIdleSeconds.toIntOrNull()?.coerceIn(10, 600)
                ?: AutoReplySettings.DEFAULT_XIAOZHI_MCP_IDLE_SECONDS
        )
    }

    fun saveXiaozhi(showToast: Boolean) {
        settings.saveXiaozhiConfig(currentXiaozhiConfig())
        if (showToast) Toast.makeText(context, "小智AI配置已保存", Toast.LENGTH_SHORT).show()
    }

    fun xiaozhiNeedToken(): Boolean {
        if (xiaozhiConsoleToken.trim().isBlank()) {
            xiaozhiConsoleResult = "请先登录小智控制台"
            Toast.makeText(context, "请先登录小智控制台", Toast.LENGTH_SHORT).show()
            return true
        }
        return false
    }

    fun refreshXiaozhiCaptcha(updateStatus: Boolean = true) {
        if (updateStatus) xiaozhiConsoleResult = "正在刷新图形验证码..."
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { XiaozhiConsoleApi.fetchCaptchaSvg() }
            }
            result.onSuccess {
                xiaozhiCaptchaSvg = it
                xiaozhiCaptchaCode = ""
                if (updateStatus) xiaozhiConsoleResult = "请输入图形验证码"
            }.onFailure {
                xiaozhiConsoleResult = "刷新图形验证码失败: ${it.message}"
            }
        }
    }

    fun sendXiaozhiSmsCode() {
        val phone = xiaozhiConsolePhone.trim()
        val captcha = xiaozhiCaptchaCode.trim()
        if (phone.isBlank() || captcha.isBlank()) {
            Toast.makeText(context, "请填写手机号和图形验证码", Toast.LENGTH_SHORT).show()
            return
        }
        settings.saveXiaozhiConfig(currentXiaozhiConfig())
        xiaozhiConsoleResult = "正在发送短信验证码..."
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { XiaozhiConsoleApi.sendSmsCode(phone, captcha) }
            }
            result.onSuccess {
                xiaozhiConsoleResult = it
                xiaozhiCaptchaCode = ""
                refreshXiaozhiCaptcha(updateStatus = false)
            }.onFailure {
                xiaozhiConsoleResult = "发送短信验证码失败: ${it.message}"
                xiaozhiCaptchaCode = ""
                refreshXiaozhiCaptcha(updateStatus = false)
            }
            Toast.makeText(context, xiaozhiConsoleResult, Toast.LENGTH_SHORT).show()
        }
    }

    fun loginXiaozhiConsole() {
        val phone = xiaozhiConsolePhone.trim()
        val sms = xiaozhiSmsCode.trim()
        if (phone.isBlank() || sms.isBlank()) {
            Toast.makeText(context, "请填写手机号和短信验证码", Toast.LENGTH_SHORT).show()
            return
        }
        xiaozhiConsoleResult = "正在登录小智控制台..."
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { XiaozhiConsoleApi.phoneLogin(phone, sms) }
            }
            result.onSuccess { token ->
                xiaozhiConsoleToken = token
                xiaozhiSmsCode = ""
                xiaozhiCaptchaCode = ""
                settings.saveXiaozhiConfig(currentXiaozhiConfig())
                xiaozhiConsoleResult = "登录成功，token 已保存"
                Toast.makeText(context, "小智控制台登录成功", Toast.LENGTH_SHORT).show()
                val agentsResult = withContext(Dispatchers.IO) {
                    runCatching { XiaozhiConsoleApi.fetchAgents(token) }
                }
                agentsResult.onSuccess { agents ->
                    xiaozhiAgents = agents
                    agents.firstOrNull()?.let { agent ->
                        if (xiaozhiConsoleAgentId.isBlank()) xiaozhiConsoleAgentId = agent.id
                        if (xiaozhiConsoleModel.isBlank()) xiaozhiConsoleModel = agent.model
                        if (xiaozhiVoiceRole.isBlank()) xiaozhiVoiceRole = agent.voice
                        settings.saveXiaozhiConfig(currentXiaozhiConfig())
                    }
                    xiaozhiConsoleResult = "登录成功，已拉取 ${agents.size} 个智能体"
                }.onFailure {
                    xiaozhiConsoleResult = "登录成功，拉取智能体失败: ${it.message}"
                }
            }.onFailure {
                xiaozhiConsoleResult = "登录失败: ${it.message}"
            }
        }
    }

    fun fetchXiaozhiAgents(openPicker: Boolean) {
        if (xiaozhiNeedToken()) return
        xiaozhiConsoleResult = "正在拉取智能体..."
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { XiaozhiConsoleApi.fetchAgents(xiaozhiConsoleToken) }
            }
            result.onSuccess { agents ->
                xiaozhiAgents = agents
                xiaozhiConsoleResult = "已拉取 ${agents.size} 个智能体"
                if (openPicker && agents.isNotEmpty()) {
                    val currentIndex = agents.indexOfFirst { it.id == xiaozhiConsoleAgentId }.coerceAtLeast(0)
                    route = AutoReplyAiRoute.OptionPicker(
                        OptionPickerRequest(
                            title = "选择小智智能体",
                            options = agents.mapIndexed { index, agent ->
                                OptionItem(
                                    label = agent.name,
                                    value = index,
                                    summary = listOf(agent.assistantName, agent.model, agent.voice).filter { it.isNotBlank() }.joinToString(" / ")
                                )
                            },
                            currentValue = currentIndex,
                            onSelected = { selected ->
                                agents.getOrNull(selected.value)?.let { agent ->
                                    xiaozhiConsoleAgentId = agent.id
                                    xiaozhiConsoleModel = agent.model
                                    xiaozhiVoiceRole = agent.voice
                                    settings.saveXiaozhiConfig(currentXiaozhiConfig())
                                }
                                route = AutoReplyAiRoute.Xiaozhi
                            }
                        )
                    )
                }
            }.onFailure {
                xiaozhiConsoleResult = "拉取智能体失败: ${it.message}"
            }
        }
    }

    fun fetchXiaozhiModels(openPicker: Boolean) {
        if (xiaozhiNeedToken()) return
        xiaozhiConsoleResult = "正在拉取模型列表..."
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { XiaozhiConsoleApi.fetchModels(xiaozhiConsoleToken) }
            }
            result.onSuccess { models ->
                xiaozhiModels = models
                xiaozhiConsoleResult = "已拉取 ${models.size} 个模型"
                if (openPicker && models.isNotEmpty()) {
                    val currentIndex = models.indexOfFirst { it.id == xiaozhiConsoleModel }.coerceAtLeast(0)
                    route = AutoReplyAiRoute.OptionPicker(
                        OptionPickerRequest(
                            title = "选择小智模型",
                            options = models.mapIndexed { index, item -> OptionItem(item.name, index, item.id) },
                            currentValue = currentIndex,
                            onSelected = { selected ->
                                models.getOrNull(selected.value)?.let { item ->
                                    xiaozhiConsoleModel = item.id
                                    settings.saveXiaozhiConfig(currentXiaozhiConfig())
                                }
                                route = AutoReplyAiRoute.Xiaozhi
                            }
                        )
                    )
                }
            }.onFailure {
                xiaozhiConsoleResult = "拉取模型失败: ${it.message}"
            }
        }
    }

    fun fetchXiaozhiVoices(openPicker: Boolean) {
        if (xiaozhiNeedToken()) return
        xiaozhiConsoleResult = "正在拉取语音角色..."
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { XiaozhiConsoleApi.fetchVoices(xiaozhiConsoleToken) }
            }
            result.onSuccess { voices ->
                xiaozhiVoices = voices
                xiaozhiConsoleResult = "已拉取 ${voices.size} 个语音角色"
                if (openPicker && voices.isNotEmpty()) {
                    val currentIndex = voices.indexOfFirst { it.id == xiaozhiVoiceRole }.coerceAtLeast(0)
                    route = AutoReplyAiRoute.OptionPicker(
                        OptionPickerRequest(
                            title = "选择语音角色",
                            options = voices.mapIndexed { index, item ->
                                OptionItem(item.name, index, listOf(item.id, item.languages.joinToString(",")).filter { it.isNotBlank() }.joinToString(" / "))
                            },
                            currentValue = currentIndex,
                            onSelected = { selected ->
                                voices.getOrNull(selected.value)?.let { item ->
                                    xiaozhiVoiceRole = item.id
                                    settings.saveXiaozhiConfig(currentXiaozhiConfig())
                                }
                                route = AutoReplyAiRoute.Xiaozhi
                            }
                        )
                    )
                }
            }.onFailure {
                xiaozhiConsoleResult = "拉取语音角色失败: ${it.message}"
            }
        }
    }

    fun saveXiaozhiConsoleAgentConfig() {
        if (xiaozhiNeedToken()) return
        val agentId = xiaozhiConsoleAgentId.trim()
        if (agentId.isBlank()) {
            Toast.makeText(context, "请先选择智能体", Toast.LENGTH_SHORT).show()
            return
        }
        settings.saveXiaozhiConfig(currentXiaozhiConfig())
        xiaozhiConsoleResult = "正在保存到小智控制台..."
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    XiaozhiConsoleApi.saveAgentConfig(
                        token = xiaozhiConsoleToken,
                        agentId = agentId,
                        model = xiaozhiConsoleModel,
                        voice = xiaozhiVoiceRole
                    )
                }
            }
            result.onSuccess { agent ->
                xiaozhiConsoleAgentId = agent.id
                xiaozhiConsoleModel = agent.model
                xiaozhiVoiceRole = agent.voice
                settings.saveXiaozhiConfig(currentXiaozhiConfig())
                xiaozhiConsoleResult = "已保存到控制台: ${agent.name}"
                Toast.makeText(context, "小智控制台配置已保存", Toast.LENGTH_SHORT).show()
            }.onFailure {
                xiaozhiConsoleResult = "保存到控制台失败: ${it.message}"
            }
        }
    }

    fun saveZhilia(showToast: Boolean) {
        val saved = currentZhiliaConfig()
        val next = configs.filterNot { it.name == activeName || it.name == saved.name } + saved
        settings.saveZhiliaConfigs(next, saved.name)
        configs = settings.zhiliaConfigs()
        activeName = saved.name
        if (sp.getBoolean(AutoReplySettings.KEY_AI_CLEAR_CONTEXT_ON_SAVE, AutoReplySettings.DEFAULT_AI_CLEAR_CONTEXT_ON_SAVE)) {
            AutoReplyRuntime.clearAiHistories()
        }
        if (showToast) Toast.makeText(context, "智聊AI配置已保存", Toast.LENGTH_SHORT).show()
    }

    fun switchZhiliaTo(name: String) {
        if (name.isBlank()) {
            Toast.makeText(context, "请选择配置", Toast.LENGTH_SHORT).show()
            return
        }
        if (name == activeName) {
            zhiliaPendingName = activeName
            Toast.makeText(context, "已是当前配置", Toast.LENGTH_SHORT).show()
            return
        }
        saveZhilia(showToast = false)
        settings.saveZhiliaConfigs(settings.zhiliaConfigs(), name)
        configs = settings.zhiliaConfigs()
        activeName = settings.activeZhiliaName()
        zhiliaPendingName = activeName
        Toast.makeText(context, "已切换到 $activeName", Toast.LENGTH_SHORT).show()
    }

    fun uniqueConfigName(base: String): String {
        val names = configs.map { it.name }.toSet()
        if (base !in names) return base
        var index = 2
        while ("$base$index" in names) index++
        return "$base$index"
    }

    val visibleModels = remember(fetchedModels, modelQuery, favoriteModels) {
        val keyword = modelQuery.trim().lowercase(Locale.US)
        fetchedModels
            .filter { keyword.isBlank() || it.lowercase(Locale.US).contains(keyword) }
            .sortedWith(compareByDescending<String> { it in favoriteModels }.thenBy { it.lowercase(Locale.US) })
    }

    SettingsRouteTransition(
        targetState = route,
        label = "AutoReplyAiRoute",
        depthOf = { it.depth() }
    ) { currentRoute ->
        when (currentRoute) {
            AutoReplyAiRoute.Main -> {
                val scrollBehavior = MiuixScrollBehavior()
                PageScaffold(
                    title = "AI 配置",
                    largeTitle = "AI 配置",
                    scrollBehavior = scrollBehavior,
                    bottomBar = { BottomActionBar("返回", onBack) }
                ) { padding ->
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
                        state = mainListState,
                        contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 84.dp)
                    ) {
                        item { SmallTitle(text = "配置分组") }
                        item {
                            SettingsCard {
                                ActionRow("小智AI配置", xiaozhiServe.ifBlank { "未设置 WebSocket 地址" }) {
                                    route = AutoReplyAiRoute.Xiaozhi
                                }
                                InsetDivider()
                                ActionRow("智聊AI配置", "当前启用：$activeName") {
                                    route = AutoReplyAiRoute.Zhilia
                                }
                            }
                        }
                    }
                }
            }
            AutoReplyAiRoute.Xiaozhi -> {
                val scrollBehavior = MiuixScrollBehavior()
                PageScaffold(
                    title = "小智AI配置",
                    largeTitle = "小智AI配置",
                    scrollBehavior = scrollBehavior,
                    bottomBar = {
                        BottomActionBar(
                            primaryText = "保存小智",
                            onPrimaryClick = { saveXiaozhi(showToast = true) },
                            secondaryText = "返回",
                            onSecondaryClick = { route = AutoReplyAiRoute.Main }
                        )
                    }
                ) { padding ->
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
                        state = xiaozhiListState,
                        contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 84.dp)
                    ) {
                        item { SmallTitle(text = "连接地址") }
                        item {
                            SettingsCard {
                                InputRow("WebSocket 地址", "小智服务地址", xiaozhiServe) { xiaozhiServe = it }
                                InsetDivider()
                                InputRow("OTA 地址", "小智 OTA 地址", xiaozhiOta) { xiaozhiOta = it }
                                InsetDivider()
                                InputRow("控制台地址", "用于查看或绑定设备", xiaozhiConsole) { xiaozhiConsole = it }
                            }
                        }
                        item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "设备") }
                        item {
                            SettingsCard {
                                InfoRow("UUID", AutoReplyRuntime.xiaozhiDeviceUuid(context))
                                InsetDivider()
                                InfoRow("MAC 地址", AutoReplyRuntime.xiaozhiDeviceMac(context))
                                InsetDivider()
                                ActionRow("绑定设备", xiaozhiBindResult.ifBlank { "请求 OTA 获取验证码或绑定状态" }) {
                                    xiaozhiBindResult = "正在请求..."
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            AutoReplyRuntime.bindXiaozhiDevice(context, currentXiaozhiConfig())
                                        }
                                        xiaozhiBindResult = result
                                        Toast.makeText(context, result.lineSequence().firstOrNull().orEmpty(), Toast.LENGTH_SHORT).show()
                                    }
                                }
                                val xiaozhiBindLink = remember(xiaozhiBindResult) { extractFirstHttpUrl(xiaozhiBindResult) }
                                if (xiaozhiBindLink != null) {
                                    InsetDivider()
                                    InfoRow("绑定链接", xiaozhiBindLink) {
                                        openExternalLink(context, xiaozhiBindLink)
                                    }
                                }
                            }
                        }
                        item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "控制台登录") }
                        item {
                            SettingsCard {
                                InfoRow("登录状态", if (xiaozhiConsoleToken.isBlank()) "未登录" else "已保存 token")
                                InsetDivider()
                                InputRow("手机号", "登录成功后会记住手机号", xiaozhiConsolePhone) { xiaozhiConsolePhone = it }
                                InsetDivider()
                                ActionRow("刷新图形验证码", if (xiaozhiCaptchaSvg.isBlank()) "先刷新，再输入图片上的字符" else "已刷新，输入下方图形验证码") {
                                    refreshXiaozhiCaptcha()
                                }
                                if (xiaozhiCaptchaSvg.isNotBlank()) {
                                    InsetDivider()
                                    XiaozhiCaptchaSvgView(xiaozhiCaptchaSvg)
                                }
                                InsetDivider()
                                InputRow("图形验证码", "输入上方图片里的字符", xiaozhiCaptchaCode) { xiaozhiCaptchaCode = it }
                                InsetDivider()
                                ActionRow("发送短信验证码", "需要先填写手机号和图形验证码") {
                                    sendXiaozhiSmsCode()
                                }
                                InsetDivider()
                                InputRow("短信验证码", "输入手机收到的验证码", xiaozhiSmsCode) { xiaozhiSmsCode = it }
                                InsetDivider()
                                ActionRow("登录并保存 token", "登录成功后自动保存 token 并拉取智能体") {
                                    loginXiaozhiConsole()
                                }
                                if (xiaozhiConsoleResult.isNotBlank()) {
                                    InsetDivider()
                                    InfoRow("控制台状态", xiaozhiConsoleResult)
                                }
                            }
                        }
                        item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "控制台配置") }
                        item {
                            SettingsCard {
                                ActionRow("选择智能体", xiaozhiAgentSummary(xiaozhiConsoleAgentId, xiaozhiAgents)) {
                                    fetchXiaozhiAgents(openPicker = true)
                                }
                                InsetDivider()
                                InfoRow("Agent ID", xiaozhiConsoleAgentId.ifBlank { "未选择" })
                                InsetDivider()
                                ActionRow("选择模型", xiaozhiModelSummary(xiaozhiConsoleModel, xiaozhiModels)) {
                                    fetchXiaozhiModels(openPicker = true)
                                }
                                InsetDivider()
                                ActionRow("选择语音角色", xiaozhiVoiceSummary(xiaozhiVoiceRole, xiaozhiVoices)) {
                                    fetchXiaozhiVoices(openPicker = true)
                                }
                                InsetDivider()
                                ActionRow("保存到小智控制台", "读取当前智能体配置后只替换模型和语音角色") {
                                    saveXiaozhiConsoleAgentConfig()
                                }
                            }
                        }
                        item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "回复") }
                        item {
                            SettingsCard {
                                InputRow("语音角色ID", "留空使用控制台当前角色", xiaozhiVoiceRole) { xiaozhiVoiceRole = it }
                                InsetDivider()
                                SwitchRow(
                                    checked = xiaozhiMusicMcp,
                                    title = "提示官方 Music 工具",
                                    summary = "点歌类问题会提示小智优先使用控制台启用的官方 Music MCP",
                                    onCheckedChange = { xiaozhiMusicMcp = it }
                                )
                            }
                        }
                        item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "MCP桥接") }
                        item {
                            SettingsCard {
                                SwitchRow(
                                    checked = xiaozhiMcpBridge,
                                    title = "启用MCP桥接实验",
                                    summary = "把 Hchat 能力暴露给小智调用；端点地址包含 token，请勿外传",
                                    onCheckedChange = { xiaozhiMcpBridge = it }
                                )
                                InsetDivider()
                                InputRow(
                                    "MCP Endpoint",
                                    "wss://api.xiaozhi.me/mcp/?token=...；留空不连接",
                                    xiaozhiMcpEndpoint
                                ) { xiaozhiMcpEndpoint = it }
                                InsetDivider()
                                XiaozhiMcpStatusRow(xiaozhiMcpStatus)
                                InsetDivider()
                                ActionRow("刷新连接状态", xiaozhiMcpStatus.detail) {
                                    if (xiaozhiConsoleToken.trim().isBlank() || xiaozhiConsoleAgentId.trim().isBlank()) {
                                        Toast.makeText(context, "请先登录并选择智能体", Toast.LENGTH_SHORT).show()
                                    } else {
                                        xiaozhiMcpStatus = XiaozhiMcpStatus(false, "查询中", "正在查询小智控制台...")
                                        scope.launch {
                                            val result = withContext(Dispatchers.IO) {
                                                runCatching {
                                                    XiaozhiConsoleApi.fetchMcpEndpointStatus(
                                                        xiaozhiConsoleToken,
                                                        xiaozhiConsoleAgentId
                                                    )
                                                }
                                            }
                                            result.onSuccess {
                                                xiaozhiMcpStatus = it
                                            }.onFailure {
                                                xiaozhiMcpStatus = XiaozhiMcpStatus(false, "查询失败", it.message.orEmpty())
                                            }
                                        }
                                    }
                                }
                                InsetDivider()
                                InputRow(
                                    "就绪等待时长",
                                    "断开后首次触发等待 MCP ready 的秒数，默认 5，可填 1-30",
                                    xiaozhiMcpReadySeconds
                                ) { xiaozhiMcpReadySeconds = it.filter { ch -> ch.isDigit() }.take(2) }
                                InsetDivider()
                                InputRow(
                                    "自动断开时长",
                                    "空闲多少秒后断开 MCP，默认 90，可填 10-600",
                                    xiaozhiMcpIdleSeconds
                                ) { xiaozhiMcpIdleSeconds = it.filter { ch -> ch.isDigit() }.take(3) }
                                InsetDivider()
                                SwitchRow(
                                    checked = xiaozhiMcpKugouEnabled,
                                    title = "调用点歌工具",
                                    summary = "让小智理解自然语言后调用本地点歌插件发送音乐卡片",
                                    onCheckedChange = { xiaozhiMcpKugouEnabled = it }
                                )
                                if (xiaozhiMcpKugouEnabled) {
                                    InsetDivider()
                                    InputRow(
                                        "点歌插件ID",
                                        "例如 QQ点歌",
                                        xiaozhiMcpKugouPluginId
                                    ) { xiaozhiMcpKugouPluginId = it }
                                    InsetDivider()
                                    InputRow(
                                        "点歌函数名",
                                        "例如 queryKugouMusic",
                                        xiaozhiMcpKugouFunction
                                    ) { xiaozhiMcpKugouFunction = it }
                                }
                            }
                        }
                        item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "运行状态") }
                        item {
                            SettingsCard {
                                ActionRow("立即清空上下文", "清除当前运行中的小智对话记忆") {
                                    AutoReplyRuntime.clearAiHistories()
                                    Toast.makeText(context, "小智上下文已清空", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                }
            }
            AutoReplyAiRoute.Zhilia -> {
                val scrollBehavior = MiuixScrollBehavior()
                PageScaffold(
                    title = "智聊AI配置",
                    largeTitle = "智聊AI配置",
                    scrollBehavior = scrollBehavior,
                    bottomBar = {
                        BottomActionBar(
                            primaryText = "保存智聊",
                            onPrimaryClick = { saveZhilia(showToast = true) },
                            secondaryText = "返回",
                            onSecondaryClick = { route = AutoReplyAiRoute.Main }
                        )
                    }
                ) { padding ->
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
                        state = zhiliaListState,
                        contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 84.dp)
                    ) {
                        item { SmallTitle(text = "当前配置") }
                        item {
                            SettingsCard {
                                InfoRow("当前启用", activeName)
                                InsetDivider()
                                InputRow("配置名称", "例如 DeepSeek 主账号", cfgName) { cfgName = it }
                                InsetDivider()
                                InputRow("API Key", "OpenAI 兼容接口密钥", apiKey) { apiKey = it }
                                InsetDivider()
                                InputRow("API 地址", "例如 https://api.xxx.com/v1", apiBase) { apiBase = it }
                                InsetDivider()
                                InputRow("API 路径", "默认 /chat/completions", apiPath) { apiPath = it }
                                InsetDivider()
                                InputRow("模型", "例如 deepseek-ai/DeepSeek-V3", model) { model = it }
                                InsetDivider()
                                InputRow("系统指令", "AI 角色设定和回复要求", prompt, minLines = 4) { prompt = it }
                                InsetDivider()
                                NumberInputRow("上下文轮数", "0 表示不保留上下文", contextLimit) { contextLimit = it }
                                InsetDivider()
                                SwitchRow(sp, AutoReplySettings.KEY_AI_STREAM, "流式请求", "失败时会自动尝试非流式", AutoReplySettings.DEFAULT_AI_STREAM)
                                InsetDivider()
                                SwitchRow(sp, AutoReplySettings.KEY_AI_CLEAR_CONTEXT_ON_SAVE, "保存后清空上下文", "修改人设或模型后避免继续沿用旧对话", AutoReplySettings.DEFAULT_AI_CLEAR_CONTEXT_ON_SAVE)
                            }
                        }
                        item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "配置管理") }
                        item {
                            SettingsCard {
                                ActionRow("配置列表", "${configs.size} 个配置，当前启用：$activeName") {
                                    zhiliaPendingName = activeName
                                    route = AutoReplyAiRoute.ZhiliaConfigs
                                }
                            }
                        }
                        item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "模型工具") }
                        item {
                            SettingsCard {
                                ActionRow("模型选择", model.ifBlank { "未设置模型" }) {
                                    pendingModel = model
                                    route = AutoReplyAiRoute.ZhiliaModels
                                }
                                InsetDivider()
                                ActionRow("测试连通性", testResult.ifBlank { "测试当前模型的流式和非流式请求" }) {
                                    val config = currentZhiliaConfig().toAiConfig(sp.getBoolean(AutoReplySettings.KEY_AI_STREAM, AutoReplySettings.DEFAULT_AI_STREAM))
                                    testResult = "正在测试..."
                                    scope.launch {
                                        testResult = withContext(Dispatchers.IO) {
                                            AutoReplyRuntime.testAiConnectivity(config)
                                        }
                                        Toast.makeText(context, testResult, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                        item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "运行状态") }
                        item {
                            SettingsCard {
                                ActionRow("立即清空上下文", "清除当前运行中的 AI 对话记忆") {
                                    AutoReplyRuntime.clearAiHistories()
                                    Toast.makeText(context, "AI 上下文已清空", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                }
            }
            AutoReplyAiRoute.ZhiliaConfigs -> {
                val scrollBehavior = MiuixScrollBehavior()
                PageScaffold(
                    title = "智聊配置列表",
                    largeTitle = "智聊配置列表",
                    scrollBehavior = scrollBehavior,
                    bottomBar = {
                        BottomActionBar(
                            primaryText = "切换到所选",
                            onPrimaryClick = { switchZhiliaTo(zhiliaPendingName) },
                            secondaryText = "返回",
                            onSecondaryClick = { route = AutoReplyAiRoute.Zhilia }
                        )
                    }
                ) { padding ->
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
                        state = zhiliaConfigListState,
                        contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 84.dp)
                    ) {
                        item { SmallTitle(text = "配置") }
                        configs.forEach { config ->
                            item {
                                SettingsCard(modifier = Modifier.padding(bottom = 8.dp)) {
                                    ActionRow(
                                        title = config.name,
                                        summary = buildString {
                                            append(config.model.ifBlank { "未设置模型" })
                                            if (config.name == activeName) append(" · 当前")
                                            if (config.name == zhiliaPendingName && config.name != activeName) append(" · 已选择")
                                        }
                                    ) {
                                        zhiliaPendingName = config.name
                                        Toast.makeText(context, "已选择 ${config.name}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                        item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "管理") }
                        item {
                            SettingsCard {
                                ActionRow("新增空配置", "创建后留在此页，可再手动切换") {
                                    val newName = uniqueConfigName("新配置")
                                    val next = configs + AutoReplyZhiliaConfig(newName, "", AutoReplySettings.DEFAULT_AI_API_BASE, AutoReplySettings.DEFAULT_AI_API_PATH, AutoReplySettings.DEFAULT_AI_MODEL, "", AutoReplySettings.DEFAULT_AI_CONTEXT_LIMIT)
                                    settings.saveZhiliaConfigs(next, activeName)
                                    configs = settings.zhiliaConfigs()
                                    zhiliaPendingName = newName
                                    Toast.makeText(context, "已新增 $newName", Toast.LENGTH_SHORT).show()
                                }
                                InsetDivider()
                                ActionRow("复制当前输入", "按智聊编辑页当前输入复制配置") {
                                    val newName = uniqueConfigName("${cfgName.ifBlank { activeName }}_副本")
                                    val next = configs + currentZhiliaConfig().copy(name = newName)
                                    settings.saveZhiliaConfigs(next, activeName)
                                    configs = settings.zhiliaConfigs()
                                    zhiliaPendingName = newName
                                    Toast.makeText(context, "已复制 $newName", Toast.LENGTH_SHORT).show()
                                }
                                if (configs.size > 1) {
                                    InsetDivider()
                                    ActionRow("删除当前配置", "删除正在编辑的配置，至少保留一个") {
                                        val next = configs.filterNot { it.name == activeName || it.name == cfgName }
                                        val nextActive = next.firstOrNull()?.name ?: AutoReplySettings.DEFAULT_ZHILIA_CONFIG_NAME
                                        settings.saveZhiliaConfigs(next, nextActive)
                                        configs = settings.zhiliaConfigs()
                                        activeName = settings.activeZhiliaName()
                                        zhiliaPendingName = activeName
                                        Toast.makeText(context, "已删除当前配置", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    }
                }
            }
            AutoReplyAiRoute.ZhiliaModels -> {
                val scrollBehavior = MiuixScrollBehavior()
                PageScaffold(
                    title = "智聊模型选择",
                    largeTitle = "智聊模型选择",
                    scrollBehavior = scrollBehavior,
                    bottomBar = {
                        BottomActionBar(
                            primaryText = "使用所选模型",
                            onPrimaryClick = {
                                model = pendingModel
                                Toast.makeText(context, "已选择模型: $pendingModel", Toast.LENGTH_SHORT).show()
                            },
                            secondaryText = "返回",
                            onSecondaryClick = { route = AutoReplyAiRoute.Zhilia }
                        )
                    }
                ) { padding ->
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
                        state = zhiliaModelListState,
                        contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 84.dp)
                    ) {
                        item { SmallTitle(text = "拉取模型") }
                        item {
                            SettingsCard {
                                ActionRow("拉取模型列表", if (loadingText.isBlank()) "从当前 API 地址获取 /models" else loadingText) {
                                    val base = apiBase
                                    val key = apiKey
                                    loadingText = "正在拉取模型..."
                                    scope.launch {
                                        val models = withContext(Dispatchers.IO) {
                                            AutoReplyRuntime.fetchAiModels(base, key)
                                        }
                                        fetchedModels = models
                                        loadingText = ""
                                        Toast.makeText(context, if (models.isEmpty()) "未获取到模型" else "已获取 ${models.size} 个模型", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                        if (visibleModels.isNotEmpty()) {
                            item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "模型列表 · ${visibleModels.size}") }
                            item {
                                SettingsCard(modifier = Modifier.padding(bottom = 8.dp)) {
                                    InputRow("搜索", "输入模型名过滤", modelQuery) { modelQuery = it }
                                }
                            }
                            visibleModels.take(80).forEach { itemModel ->
                                item {
                                    ZhiliaModelSelectCard(
                                        modelName = itemModel,
                                        currentModel = model,
                                        pendingModel = pendingModel,
                                        favorite = itemModel in favoriteModels,
                                        onSelect = {
                                            pendingModel = itemModel
                                            Toast.makeText(context, "已选择 $itemModel", Toast.LENGTH_SHORT).show()
                                        },
                                        onToggleFavorite = {
                                            val next = if (itemModel in favoriteModels) favoriteModels - itemModel else favoriteModels + itemModel
                                            favoriteModels = next
                                            settings.saveFavoriteModels(apiBase, next)
                                            Toast.makeText(context, if (itemModel in next) "已收藏模型" else "已取消收藏", Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                }
                            }
                        } else {
                            item { SettingsCard { EmptyText("暂无模型列表，先点击“拉取模型列表”。") } }
                        }
                    }
                }
            }
            is AutoReplyAiRoute.OptionPicker -> {
                OptionPickerPage(
                    request = currentRoute.request,
                    onBack = { route = AutoReplyAiRoute.Xiaozhi },
                    onSelected = { currentRoute.request.onSelected(it) }
                )
            }
        }
    }
}

@Composable
internal fun ZhiliaModelSelectCard(
    modelName: String,
    currentModel: String,
    pendingModel: String,
    favorite: Boolean,
    onSelect: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    val selected = modelName == pendingModel
    val current = modelName == currentModel
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
        cornerRadius = 14.dp
    ) {
        var pressed by remember { mutableStateOf(false) }
        val pressFeedbackColor = rememberPressFeedbackColor(pressed)
        Column(
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(pressFeedbackColor)
                .responsiveTap(
                    onClick = onSelect,
                    onPressedChange = { pressed = it }
                )
                .padding(horizontal = 16.dp, vertical = 13.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = modelName, color = MiuixTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
                    val state = buildString {
                        if (current) append("当前")
                        if (selected && !current) {
                            if (isNotEmpty()) append(" · ")
                            append("已选择")
                        }
                        if (favorite) {
                            if (isNotEmpty()) append(" · ")
                            append("已收藏")
                        }
                        if (isEmpty()) append("点击选择")
                    }
                    Text(text = state, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 12.sp)
                }
                Text(
                    text = if (selected) "已选" else "选择",
                    color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            InsetDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onToggleFavorite() }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    text = if (favorite) "取消收藏" else "收藏",
                    color = MiuixTheme.colorScheme.primary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
internal fun AutoReplyStepsPage(
    title: String,
    initialSteps: List<AutoReplyStep>,
    onBack: () -> Unit,
    onSave: (List<AutoReplyStep>) -> Unit
) {
    val context = LocalContext.current
    var steps by remember(initialSteps) { mutableStateOf(initialSteps.ifEmpty { listOf(AutoReplyStep()) }) }
    var optionPicker by remember { mutableStateOf<OptionPickerRequest?>(null) }
    var contactPicker by remember { mutableStateOf<ContactPickerRequest?>(null) }
    var favoritePicker by remember { mutableStateOf<FavoritePickerRequest?>(null) }
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()
    fun update(index: Int, step: AutoReplyStep) {
        steps = steps.toMutableList().also { if (index in it.indices) it[index] = step }
    }
    val stepsRoute = when {
        favoritePicker != null -> AutoReplyStepsRoute.FavoritePicker(favoritePicker!!)
        optionPicker != null -> AutoReplyStepsRoute.OptionPicker(optionPicker!!)
        contactPicker != null -> AutoReplyStepsRoute.ContactPicker(contactPicker!!)
        else -> AutoReplyStepsRoute.Main
    }
    SettingsRouteTransition(
        targetState = stepsRoute,
        label = "AutoReplyStepsRoute",
        depthOf = { it.depth() }
    ) { currentRoute ->
        when (currentRoute) {
            is AutoReplyStepsRoute.OptionPicker -> {
                val request = currentRoute.request
                OptionPickerPage(
                    request = request,
                    onBack = { optionPicker = null },
                    onSelected = { request.onSelected(it); optionPicker = null }
                )
            }
            is AutoReplyStepsRoute.ContactPicker -> {
                val request = currentRoute.request
                ContactPickerPage(
                    context = context,
                    request = request,
                    onBack = { contactPicker = null },
                    onConfirm = { selected ->
                        request.onValue(formatIds(selected.map { it.id }))
                        Toast.makeText(context, "已选择 ${selected.size} 项", Toast.LENGTH_SHORT).show()
                        contactPicker = null
                    }
                )
            }
            is AutoReplyStepsRoute.FavoritePicker -> {
                FavoritePickerPage(
                    request = currentRoute.request,
                    onBack = { favoritePicker = null }
                )
            }
            AutoReplyStepsRoute.Main -> PageScaffold(
                title = title,
                largeTitle = title,
                scrollBehavior = scrollBehavior,
                bottomBar = {
                    BottomActionBar(
                        primaryText = "保存回复",
                        onPrimaryClick = { onSave(steps.filter { AutoReplySettings.isAiReplyMode(it.mode) || it.content.isNotBlank() }) },
                        secondaryText = "返回",
                        onSecondaryClick = onBack
                    )
                }
            ) { padding ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
                    state = listState,
                    contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 84.dp)
                ) {
                    steps.forEachIndexed { index, step ->
                        item { SmallTitle(modifier = Modifier.padding(top = if (index == 0) 0.dp else 10.dp), text = "第 ${index + 1} 条") }
                        item {
                            SettingsCard {
                                PopupOptionRow(
                                    title = "回复类型",
                                    summary = autoReplyStepLabel(step.mode),
                                    options = autoReplyStepOptions(),
                                    currentValue = step.mode,
                                    onValueChanged = {
                                        update(
                                            index,
                                            step.copy(
                                                mode = it,
                                                content = when {
                                                    AutoReplySettings.isAiReplyMode(it) -> ""
                                                    it == step.mode -> step.content
                                                    it == AutoReplySettings.REPLY_FAVORITE ||
                                                        step.mode == AutoReplySettings.REPLY_FAVORITE -> ""
                                                    else -> step.content
                                                }
                                            )
                                        )
                                    }
                                )
                                InsetDivider()
                                if (!AutoReplySettings.isAiReplyMode(step.mode)) {
                                    AutoReplyStepContentEditor(
                                        context = context,
                                        step = step,
                                        onStepChange = { update(index, it) },
                                        onContactPicker = { contactPicker = it },
                                        onFavoritePicker = { favoritePicker = it }
                                    )
                                    InsetDivider()
                                }
                                NumberInputRow("发送前延迟", "单位 ms", step.delayMs.toString()) {
                                    update(index, step.copy(delayMs = it.toLongOrNull()?.coerceIn(0L, 600000L) ?: 0L))
                                }
                                InsetDivider()
                                SwitchRow(step.randomDelay, "随机追加延迟", "发送前额外随机等待 0-2 秒") {
                                    update(index, step.copy(randomDelay = it))
                                }
                                InsetDivider()
                                RedPacketReplyStepActions(
                                    canMoveUp = index > 0,
                                    canMoveDown = index < steps.lastIndex,
                                    onMoveUp = {
                                        steps = steps.toMutableList().also {
                                            val item = it.removeAt(index)
                                            it.add(index - 1, item)
                                        }
                                    },
                                    onMoveDown = {
                                        steps = steps.toMutableList().also {
                                            val item = it.removeAt(index)
                                            it.add(index + 1, item)
                                        }
                                    },
                                    onDelete = { steps = steps.toMutableList().also { it.removeAt(index) }.ifEmpty { listOf(AutoReplyStep()) } }
                                )
                            }
                        }
                    }
                    item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "操作") }
                    item {
                        SettingsCard {
                            ActionRow("新增回复", "添加一条按顺序发送的回复") {
                                steps = steps + AutoReplyStep(id = System.currentTimeMillis().toString(), content = "你好")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AutoReplyStepContentEditor(
    context: Context,
    step: AutoReplyStep,
    onStepChange: (AutoReplyStep) -> Unit,
    onContactPicker: (ContactPickerRequest) -> Unit,
    onFavoritePicker: (FavoritePickerRequest) -> Unit
) {
    when {
        step.mode == AutoReplySettings.REPLY_TEXT || step.mode == AutoReplySettings.REPLY_XML -> {
            VariableInputRow(
                title = if (step.mode == AutoReplySettings.REPLY_XML) "XML 内容" else "回复内容",
                summary = autoReplyStepContentHint(step.mode),
                value = step.content,
                variables = autoReplyTemplateVariables,
                minLines = 4
            ) {
                onStepChange(step.copy(content = it))
            }
        }
        autoReplyStepUsesContactPicker(step.mode) -> {
            val mode = if (step.mode == AutoReplySettings.REPLY_CARD) ContactPickerMode.FRIENDS else ContactPickerMode.GROUPS
            ActionRow(
                title = autoReplyPickerTitle(step.mode),
                summary = autoReplySelectedIdSummary(step.content)
            ) {
                onContactPicker(
                    ContactPickerRequest(
                        title = autoReplyPickerTitle(step.mode),
                        mode = mode,
                        multiSelect = true,
                        existingValue = formatIds(autoReplySplitContent(step.content)),
                        onValue = { onStepChange(step.copy(content = it)) },
                        enableLabels = step.mode == AutoReplySettings.REPLY_CARD
                    )
                )
            }
            if (step.content.isNotBlank()) {
                InsetDivider()
                ActionRow("清空已选", "移除当前已选择内容") {
                    onStepChange(step.copy(content = ""))
                }
            }
        }
        step.mode == AutoReplySettings.REPLY_FAVORITE -> {
            ActionRow(
                title = autoReplyPickerTitle(step.mode),
                summary = favoriteSummary(step.content)
            ) {
                onFavoritePicker(
                    FavoritePickerRequest(
                        title = autoReplyPickerTitle(step.mode),
                        existingValue = step.content,
                        onValue = { onStepChange(step.copy(content = it)) },
                        multiSelect = true,
                        delimiter = ";;;"
                    )
                )
            }
            if (step.content.isNotBlank()) {
                InsetDivider()
                ActionRow("清空已选收藏", "移除当前回复类型的收藏") {
                    onStepChange(step.copy(content = ""))
                }
            }
        }
        autoReplyStepUsesFilePicker(step.mode) -> {
            ActionRow(
                title = autoReplyPickerTitle(step.mode),
                summary = autoReplyFileSummary(step.content)
            ) {
                val activity = context as? Activity
                if (activity == null) {
                    Toast.makeText(context, "当前页面无法打开文件选择器", Toast.LENGTH_SHORT).show()
                    return@ActionRow
                }
                AutoReplyFilePickerBridge.launch(activity, step.mode) { paths ->
                    val merged = (autoReplySplitContent(step.content) + paths)
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .distinct()
                    if (merged.isNotEmpty()) {
                        onStepChange(step.copy(content = merged.joinToString(";;;")))
                        Toast.makeText(context, "已选择 ${paths.size} 个文件", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            if (step.content.isNotBlank()) {
                InsetDivider()
                ActionRow("清空已选文件", "移除当前回复类型的文件") {
                    onStepChange(step.copy(content = ""))
                }
            }
        }
        else -> {
            InputRow("回复内容", autoReplyStepContentHint(step.mode), step.content, minLines = 2) {
                onStepChange(step.copy(content = it))
            }
        }
    }
}

internal fun describeAutoReplyRule(rule: AutoReplyRule): String {
    val state = if (rule.enabled) "启用" else "关闭"
    val keyword = if (rule.matchType == AutoReplySettings.MATCH_ANY) "任意消息" else rule.keyword.ifBlank { "未填关键词" }
    return buildList {
        add(state)
        add(autoReplyMatchLabel(rule.matchType))
        add(keyword)
        add(autoReplyTargetLabel(rule.targetMode))
        if (rule.cooldownSeconds > 0L) add("冷却 ${rule.cooldownSeconds} 秒")
        add(describeAutoReplySteps(rule.steps))
    }.joinToString(" · ")
}

internal fun describeAutoReplySteps(steps: List<AutoReplyStep>): String {
    if (steps.isEmpty()) return "暂无回复"
    return steps.take(3).joinToString("、") { autoReplyStepLabel(it.mode) } +
        if (steps.size > 3) " 等 ${steps.size} 条" else "，共 ${steps.size} 条"
}

internal fun replaceAutoReplyTypedIds(old: Set<String>, picked: Set<String>, group: Boolean): Set<String> {
    return (old.filter { isAutoReplyGroupId(it) != group } + picked)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()
}

internal fun isAutoReplyGroupId(value: String): Boolean =
    value.endsWith("@chatroom") || value.endsWith("@im.chatroom")

internal fun conversationRuleCategoryForId(value: String): ConversationRuleCategory {
    val normalized = value.trim().lowercase(Locale.US)
    return when {
        normalized.startsWith("gh_") || normalized.endsWith("@app") || normalized == "newsapp" -> {
            ConversationRuleCategory.OFFICIAL
        }
        normalized.endsWith("@chatroom") || normalized.endsWith("@im.chatroom") -> {
            ConversationRuleCategory.GROUP
        }
        else -> ConversationRuleCategory.FRIEND
    }
}

internal fun conversationRuleCategoryForTarget(value: String): ConversationRuleCategory {
    return conversationRuleCategoryForId(value.substringBefore('/'))
}

internal fun conversationRuleCategoryMatches(
    rule: CustomNotificationRule,
    category: ConversationRuleCategory
): Boolean {
    if (category == ConversationRuleCategory.ALL) return true
    val actual = when {
        rule.official -> ConversationRuleCategory.OFFICIAL
        rule.group -> ConversationRuleCategory.GROUP
        else -> ConversationRuleCategory.FRIEND
    }
    return actual == category
}

internal fun conversationRuleCategoryMatches(
    binding: MessageBlockBinding,
    category: ConversationRuleCategory
): Boolean {
    if (category == ConversationRuleCategory.ALL) return true
    val actual = if (binding.targetType == MessageBlockSettings.TARGET_GROUP_MEMBER) {
        ConversationRuleCategory.GROUP
    } else {
        conversationRuleCategoryForTarget(binding.targetId)
    }
    return actual == category
}

internal fun conversationRuleCategoryMatches(
    binding: RedPacketRuleBinding,
    category: ConversationRuleCategory
): Boolean {
    return category == ConversationRuleCategory.ALL ||
        conversationRuleCategoryForId(binding.targetId) == category
}

internal fun conversationRuleCategoryMatches(
    binding: TransferRuleBinding,
    category: ConversationRuleCategory
): Boolean {
    return category == ConversationRuleCategory.ALL ||
        conversationRuleCategoryForId(binding.targetId) == category
}

internal fun autoReplyMatchOptions(): List<OptionItem> = listOf(
    OptionItem("模糊匹配", AutoReplySettings.MATCH_FUZZY, "内容包含关键词就触发"),
    OptionItem("全字匹配", AutoReplySettings.MATCH_EXACT, "消息内容与关键词完全相同"),
    OptionItem("正则匹配", AutoReplySettings.MATCH_REGEX, "关键词按正则表达式处理"),
    OptionItem("任意消息", AutoReplySettings.MATCH_ANY, "收到符合范围的消息就触发")
)

internal fun autoReplyMatchLabel(value: Int): String = autoReplyMatchOptions().firstOrNull { it.value == value }?.label ?: "模糊匹配"

internal fun autoReplyTargetOptions(): List<OptionItem> = listOf(
    OptionItem("全部聊天", AutoReplySettings.TARGET_ALL, "好友、群聊和公众号都可触发"),
    OptionItem("仅私聊", AutoReplySettings.TARGET_PRIVATE, "只回复好友私聊"),
    OptionItem("仅群聊", AutoReplySettings.TARGET_GROUP, "只回复群消息"),
    OptionItem("指定名单", AutoReplySettings.TARGET_SPECIFIC, "只回复指定 wxid、群号或群成员"),
    OptionItem("仅公众号", AutoReplySettings.TARGET_OFFICIAL, "只回复公众号消息")
)

internal fun autoReplyTargetLabel(value: Int): String = autoReplyTargetOptions().firstOrNull { it.value == value }?.label ?: "全部聊天"

internal fun autoReplyAtOptions(): List<OptionItem> = listOf(
    OptionItem("不限", AutoReplySettings.AT_NONE),
    OptionItem("@我触发", AutoReplySettings.AT_ME),
    OptionItem("@全体触发", AutoReplySettings.AT_ALL)
)

internal fun autoReplyAtLabel(value: Int): String = autoReplyAtOptions().firstOrNull { it.value == value }?.label ?: "不限"

internal fun autoReplyPatOptions(): List<OptionItem> = listOf(
    OptionItem("不限", AutoReplySettings.PAT_NONE),
    OptionItem("被拍一拍触发", AutoReplySettings.PAT_ME)
)

internal fun autoReplyPatLabel(value: Int): String = autoReplyPatOptions().firstOrNull { it.value == value }?.label ?: "不限"

internal fun autoReplyStepOptions(): List<OptionItem> = listOf(
    OptionItem("发送文字", AutoReplySettings.REPLY_TEXT, "支持变量和 | 分隔多条"),
    OptionItem("发送图片", AutoReplySettings.REPLY_IMAGE, "从系统文件管理器选择"),
    OptionItem("发送语音", AutoReplySettings.REPLY_VOICE, "从系统文件管理器选择"),
    OptionItem("随机语音", AutoReplySettings.REPLY_VOICE_RANDOM_FOLDER, "从已选语音随机发送"),
    OptionItem("发送表情", AutoReplySettings.REPLY_EMOJI, "从系统文件管理器选择"),
    OptionItem("发送视频", AutoReplySettings.REPLY_VIDEO, "从系统文件管理器选择"),
    OptionItem("发送名片", AutoReplySettings.REPLY_CARD, "从好友列表选择"),
    OptionItem("发送文件", AutoReplySettings.REPLY_FILE, "从系统文件管理器选择"),
    OptionItem("发送收藏", AutoReplySettings.REPLY_FAVORITE, "从最近收藏选择"),
    OptionItem("邀请进群", AutoReplySettings.REPLY_INVITE_GROUP, "从群聊列表选择"),
    OptionItem("发送 XML", AutoReplySettings.REPLY_XML, "AppMsg/XML 原文"),
    OptionItem("智聊AI回复", AutoReplySettings.REPLY_ZHILIA_AI, "使用智聊/OpenAI 兼容配置生成回复"),
    OptionItem("小智AI回复", AutoReplySettings.REPLY_XIAOZHI_AI, "使用小智 WebSocket 生成文字回复"),
    OptionItem("小智语音回复", AutoReplySettings.REPLY_XIAOZHI_VOICE, "使用小智 TTS 生成语音回复")
)

internal fun autoReplyStepLabel(value: Int): String = autoReplyStepOptions().firstOrNull { it.value == value }?.label ?: "发送文字"

internal fun autoReplyStepContentHint(value: Int): String = when (value) {
    AutoReplySettings.REPLY_TEXT -> "多条文本用 | 分隔；下方变量可点击插入"
    AutoReplySettings.REPLY_XML -> "填写 XML 原文；下方变量可点击插入"
    AutoReplySettings.REPLY_CARD -> "选择要分享的好友名片"
    AutoReplySettings.REPLY_INVITE_GROUP -> "选择要邀请对方加入的群聊"
    AutoReplySettings.REPLY_FAVORITE -> "从最近收藏选择，可多选后按顺序发送"
    AutoReplySettings.REPLY_VOICE_RANDOM_FOLDER -> "选择多个语音文件后随机发送一个"
    else -> "填写文件绝对路径，每行一个或用 ;;; 分隔"
}

internal fun autoReplyStepUsesFilePicker(mode: Int): Boolean = mode in setOf(
    AutoReplySettings.REPLY_IMAGE,
    AutoReplySettings.REPLY_VOICE,
    AutoReplySettings.REPLY_VOICE_RANDOM_FOLDER,
    AutoReplySettings.REPLY_EMOJI,
    AutoReplySettings.REPLY_VIDEO,
    AutoReplySettings.REPLY_FILE
)

internal fun autoReplyStepUsesContactPicker(mode: Int): Boolean =
    mode == AutoReplySettings.REPLY_CARD || mode == AutoReplySettings.REPLY_INVITE_GROUP

internal fun autoReplyPickerTitle(mode: Int): String = when (mode) {
    AutoReplySettings.REPLY_IMAGE -> "选择图片"
    AutoReplySettings.REPLY_VOICE -> "选择语音"
    AutoReplySettings.REPLY_VOICE_RANDOM_FOLDER -> "选择随机语音"
    AutoReplySettings.REPLY_EMOJI -> "选择表情文件"
    AutoReplySettings.REPLY_VIDEO -> "选择视频"
    AutoReplySettings.REPLY_CARD -> "选择名片好友"
    AutoReplySettings.REPLY_FILE -> "选择文件"
    AutoReplySettings.REPLY_FAVORITE -> "选择收藏"
    AutoReplySettings.REPLY_INVITE_GROUP -> "选择群聊"
    else -> "选择文件"
}

internal fun scheduledTaskPickerTitle(taskType: Int): String = when (taskType) {
    ScheduledTaskSettings.TYPE_IMAGE -> "选择图片"
    ScheduledTaskSettings.TYPE_VIDEO -> "选择视频"
    ScheduledTaskSettings.TYPE_VOICE -> "选择语音文件"
    ScheduledTaskSettings.TYPE_EMOJI -> "选择表情文件"
    ScheduledTaskSettings.TYPE_FILE -> "选择文件"
    ScheduledTaskSettings.TYPE_FAVORITE -> "选择收藏"
    else -> "选择文件"
}

internal fun autoReplyFileSummary(value: String): String {
    val items = autoReplySplitContent(value)
    if (items.isEmpty()) return "未选择"
    val names = items.take(3).map { File(it).name.ifBlank { it } }
    return names.joinToString("、") + if (items.size > 3) " 等 ${items.size} 个文件" else "，共 ${items.size} 个文件"
}

internal fun autoReplySelectedIdSummary(value: String): String {
    val count = autoReplySplitContent(value).size
    return if (count == 0) "未选择" else "已选择 $count 项"
}

internal fun autoReplySplitContent(value: String): List<String> {
    return value.split(";;;")
        .flatMap { it.split('\n') }
        .flatMap { it.split('|') }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
}

@Composable
internal fun CustomNotificationMainPage(
    context: Context,
    provider: FeatureSettingsProvider,
    sp: SharedPreferences,
    rules: List<CustomNotificationRule>,
    defaultPrivateRule: CustomNotificationRule,
    defaultGroupRule: CustomNotificationRule,
    defaultOfficialRule: CustomNotificationRule,
    onBack: () -> Unit,
    onOpenRules: () -> Unit,
    onEditDefault: (CustomNotificationDefaultKind) -> Unit
) {
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()
    PageScaffold(
        title = provider.title(),
        largeTitle = provider.title(),
        scrollBehavior = scrollBehavior,
        bottomBar = { BottomActionBar("返回", onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "自定义通知") }
            item {
                SettingsCard {
                    SwitchRow(
                        sp,
                        CustomNotificationSettings.KEY_ENABLE,
                        "启用自定义通知",
                        "开启后按会话专属规则或默认规则接管微信通知",
                        CustomNotificationSettings.DEFAULT_ENABLE
                    )
                    InsetDivider()
                    ActionRow(
                        "默认私聊通知",
                        customNotificationRuleSummary(context, defaultPrivateRule)
                    ) {
                        onEditDefault(CustomNotificationDefaultKind.PRIVATE)
                    }
                    InsetDivider()
                    ActionRow(
                        "默认群聊通知",
                        customNotificationRuleSummary(context, defaultGroupRule)
                    ) {
                        onEditDefault(CustomNotificationDefaultKind.GROUP)
                    }
                    InsetDivider()
                    ActionRow(
                        "默认公众号通知",
                        customNotificationRuleSummary(context, defaultOfficialRule)
                    ) {
                        onEditDefault(CustomNotificationDefaultKind.OFFICIAL)
                    }
                    InsetDivider()
                    ActionRow(
                        "会话规则",
                        if (rules.isEmpty()) "未配置会话" else "${rules.size} 个会话，${rules.count { it.enabled }} 个启用"
                    ) {
                        onOpenRules()
                    }
                }
            }
        }
    }
}

@Composable
internal fun CustomNotificationRuleListPage(
    context: Context,
    rules: List<CustomNotificationRule>,
    query: String,
    onQueryChange: (String) -> Unit,
    listState: LazyListState,
    onBack: () -> Unit,
    onSaveRules: (List<CustomNotificationRule>) -> Unit,
    onEditRule: (String) -> Unit,
    onToggleRule: (CustomNotificationRule, Boolean) -> Unit,
    onBatchEdit: () -> Unit,
    onAddRules: () -> Unit
) {
    var category by remember { mutableStateOf(ConversationRuleCategory.ALL) }
    var batchDeleteMode by remember { mutableStateOf(false) }
    var selectedTalkers by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val scrollBehavior = MiuixScrollBehavior()
    val lower = query.trim().lowercase(Locale.US)
    val visible = rules.filter {
        conversationRuleCategoryMatches(it, category) && (
            lower.isBlank() ||
                it.label.lowercase(Locale.US).contains(lower) ||
                it.talker.lowercase(Locale.US).contains(lower)
            )
    }.sortedWith(compareBy<CustomNotificationRule> { !it.enabled }.thenBy { it.group }.thenBy { it.label.lowercase(Locale.US) })
    val visibleTalkers = visible.mapTo(LinkedHashSet()) { it.talker }
    val allVisibleSelected = visibleTalkers.isNotEmpty() && visibleTalkers.all { it in selectedTalkers }
    val selectedRules = rules.filter { it.talker in selectedTalkers }
    PageScaffold(
        title = "会话规则",
        largeTitle = "会话规则",
        scrollBehavior = scrollBehavior,
        bottomBar = {
            if (batchDeleteMode) {
                BottomActionBar(
                    primaryText = "删除所选（${selectedRules.size}）",
                    onPrimaryClick = {
                        if (selectedRules.isEmpty()) {
                            Toast.makeText(context, "请先选择会话规则", Toast.LENGTH_SHORT).show()
                        } else {
                            showDeleteConfirm = true
                        }
                    },
                    secondaryText = "取消",
                    onSecondaryClick = {
                        batchDeleteMode = false
                        selectedTalkers = emptySet()
                    },
                    middleText = if (visibleTalkers.isEmpty()) null else if (allVisibleSelected) "取消全选" else "全选",
                    onMiddleClick = if (visibleTalkers.isEmpty()) null else {
                        {
                            selectedTalkers = if (allVisibleSelected) {
                                selectedTalkers - visibleTalkers
                            } else {
                                selectedTalkers + visibleTalkers
                            }
                        }
                    }
                )
            } else {
                BottomActionBar(
                    primaryText = "添加会话",
                    onPrimaryClick = onAddRules,
                    secondaryText = "返回",
                    onSecondaryClick = onBack,
                    middleText = if (rules.isEmpty()) null else "批量删除",
                    onMiddleClick = if (rules.isEmpty()) null else {
                        {
                            batchDeleteMode = true
                            selectedTalkers = emptySet()
                        }
                    }
                )
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item {
                SettingsCard {
                    ConversationRuleCategoryTabs(
                        selected = category,
                        onSelected = { category = it }
                    )
                }
            }
            item {
                SettingsCard {
                    InputRow("搜索", "昵称 / wxid / 群号", query, onValueChange = onQueryChange)
                }
            }
            if (rules.isNotEmpty() && !batchDeleteMode) {
                item {
                    SettingsCard {
                        ActionRow("批量配置", "统一修改全部 ${rules.size} 个会话规则", onBatchEdit)
                    }
                }
            }
            item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = if (visible.isEmpty()) "规则" else "规则 · ${visible.size} 项") }
            when {
                rules.isEmpty() -> item { SettingsCard { EmptyText("暂无会话规则，点击底部“添加会话”。") } }
                visible.isEmpty() -> item { SettingsCard { EmptyText("没有匹配结果") } }
                else -> visible.forEach { rule ->
                    item {
                        SettingsCard(modifier = Modifier.padding(bottom = 8.dp)) {
                            if (batchDeleteMode) {
                                OptionChoiceRow(
                                    item = OptionItem(
                                        label = customNotificationRuleTitle(rule),
                                        value = 0,
                                        summary = customNotificationRuleSummary(context, rule)
                                    ),
                                    selected = rule.talker in selectedTalkers,
                                    onClick = {
                                        selectedTalkers = if (rule.talker in selectedTalkers) {
                                            selectedTalkers - rule.talker
                                        } else {
                                            selectedTalkers + rule.talker
                                        }
                                    }
                                )
                            } else {
                                ActionRow(
                                    title = customNotificationRuleTitle(rule),
                                    summary = customNotificationRuleSummary(context, rule)
                                ) {
                                    onEditRule(rule.talker)
                                }
                                InsetDivider()
                                SwitchRow(
                                    checked = rule.enabled,
                                    title = "启用此规则",
                                    summary = if (rule.enabled) "当前会话已接管通知" else "当前会话不接管通知"
                                ) {
                                    onToggleRule(rule, it)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    BatchDeleteConfirmDialog(
        show = showDeleteConfirm,
        message = "将删除已选的 ${selectedRules.size} 个会话规则，此操作不可撤销。",
        labels = selectedRules.map { customNotificationRuleTitle(it) },
        onDismiss = { showDeleteConfirm = false },
        onConfirm = {
            val targets = selectedRules
            val targetTalkers = targets.mapTo(HashSet()) { it.talker }
            showDeleteConfirm = false
            batchDeleteMode = false
            selectedTalkers = emptySet()
            onSaveRules(rules.filterNot { it.talker in targetTalkers })
            Toast.makeText(context, "已删除 ${targets.size} 个会话规则", Toast.LENGTH_SHORT).show()
        }
    )
}

@Composable
internal fun CustomNotificationBatchEditorPage(
    ruleCount: Int,
    onBack: () -> Unit,
    onApply: (CustomNotificationBatchUpdate) -> Unit
) {
    var applyEnabled by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(true) }
    var applyMode by remember { mutableStateOf(false) }
    var dnd by remember { mutableStateOf(false) }
    var applyVibrate by remember { mutableStateOf(false) }
    var vibrate by remember { mutableStateOf(true) }
    var applySound by remember { mutableStateOf(false) }
    var sound by remember { mutableStateOf(true) }
    var applyMarkRead by remember { mutableStateOf(false) }
    var markRead by remember { mutableStateOf(true) }
    var applyQuickReply by remember { mutableStateOf(false) }
    var quickReply by remember { mutableStateOf(false) }
    var applyQuoteQuickReply by remember { mutableStateOf(false) }
    var quoteQuickReply by remember { mutableStateOf(false) }
    var applyMergeByTalker by remember { mutableStateOf(false) }
    var mergeByTalker by remember { mutableStateOf(false) }
    var applyShowDetail by remember { mutableStateOf(false) }
    var showDetail by remember { mutableStateOf(true) }
    var applyIgnoreWechatDnd by remember { mutableStateOf(false) }
    var ignoreWechatDnd by remember { mutableStateOf(false) }
    var applyMute by remember { mutableStateOf(false) }
    var muteEnable by remember { mutableStateOf(false) }
    var muteStart by remember { mutableStateOf(CustomNotificationSettings.DEFAULT_MUTE_START) }
    var muteEnd by remember { mutableStateOf(CustomNotificationSettings.DEFAULT_MUTE_END) }
    var applyAtRules by remember { mutableStateOf(false) }
    var blockAtAll by remember { mutableStateOf(false) }
    var blockAtMe by remember { mutableStateOf(false) }
    val scrollBehavior = MiuixScrollBehavior()
    PageScaffold(
        title = "批量配置",
        largeTitle = "批量配置",
        scrollBehavior = scrollBehavior,
        bottomBar = {
            BottomActionBar(
                primaryText = "应用到 $ruleCount 项",
                onPrimaryClick = {
                    onApply(
                        CustomNotificationBatchUpdate(
                            applyEnabled = applyEnabled,
                            enabled = enabled,
                            applyMode = applyMode,
                            dnd = dnd,
                            applyVibrate = applyVibrate,
                            vibrate = vibrate,
                            applySound = applySound,
                            sound = sound,
                            applyMarkRead = applyMarkRead,
                            markRead = markRead,
                            applyQuickReply = applyQuickReply,
                            quickReply = quickReply,
                            applyQuoteQuickReply = applyQuoteQuickReply,
                            quoteQuickReply = quoteQuickReply,
                            applyMergeByTalker = applyMergeByTalker,
                            mergeByTalker = mergeByTalker,
                            applyShowDetail = applyShowDetail,
                            showDetail = showDetail,
                            applyIgnoreWechatDnd = applyIgnoreWechatDnd,
                            ignoreWechatDnd = ignoreWechatDnd,
                            applyMute = applyMute,
                            muteEnable = muteEnable,
                            muteStart = muteStart,
                            muteEnd = muteEnd,
                            applyAtRules = applyAtRules,
                            blockAtAll = blockAtAll,
                            blockAtMe = blockAtMe
                        )
                    )
                },
                secondaryText = "返回",
                onSecondaryClick = onBack
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "应用范围") }
            item { SettingsCard { EmptyText("只会修改已勾选“应用”的项目，未勾选的设置保持原样。群聊专属的 @ 规则只影响群聊规则。") } }
            item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "基础") }
            item {
                SettingsCard {
                    SwitchRow(applyEnabled, "应用启用状态", "批量开关会话规则") { applyEnabled = it }
                    if (applyEnabled) {
                        InsetDivider()
                        SwitchRow(enabled, "启用规则", "关闭后不再接管对应会话通知") { enabled = it }
                    }
                    InsetDivider()
                    SwitchRow(applyMode, "应用免打扰", "批量设置是否弹自定义通知") { applyMode = it }
                    if (applyMode) {
                        InsetDivider()
                        SwitchRow(dnd, "免打扰", "开启后不弹自定义通知，只拦截原生通知") { dnd = it }
                    }
                    InsetDivider()
                    SwitchRow(applyIgnoreWechatDnd, "应用微信免打扰策略", "批量设置是否忽略微信原生会话免打扰") {
                        applyIgnoreWechatDnd = it
                    }
                    if (applyIgnoreWechatDnd) {
                        InsetDivider()
                        SwitchRow(
                            ignoreWechatDnd,
                            "忽略微信自带的消息免打扰",
                            "开启后，对应会话在微信中设置免打扰仍会弹出自定义通知"
                        ) { ignoreWechatDnd = it }
                    }
                }
            }
            item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "提醒") }
            item {
                SettingsCard {
                    SwitchRow(applyVibrate, "应用震动", "批量修改震动状态") { applyVibrate = it }
                    if (applyVibrate) {
                        InsetDivider()
                        SwitchRow(vibrate, "震动", "通知时震动") { vibrate = it }
                    }
                    InsetDivider()
                    SwitchRow(applySound, "应用铃声", "批量修改铃声状态，不修改每个会话已选铃声文件") { applySound = it }
                    if (applySound) {
                        InsetDivider()
                        SwitchRow(sound, "铃声", "通知时播放铃声") { sound = it }
                    }
                    InsetDivider()
                    SwitchRow(applyMarkRead, "应用已读按钮", "批量修改通知栏已读按钮") { applyMarkRead = it }
                    if (applyMarkRead) {
                        InsetDivider()
                        SwitchRow(markRead, "已读按钮", "通知栏直接标记当前会话已读") { markRead = it }
                    }
                    InsetDivider()
                    SwitchRow(applyQuickReply, "应用快捷回复", "批量修改通知栏快捷回复") { applyQuickReply = it }
                    if (applyQuickReply) {
                        InsetDivider()
                        SwitchRow(quickReply, "快捷回复", "通知栏直接回复文本消息") { quickReply = it }
                    }
                    InsetDivider()
                    SwitchRow(applyQuoteQuickReply, "应用引用消息回复", "批量修改快捷回复是否引用原消息") {
                        applyQuoteQuickReply = it
                    }
                    if (applyQuoteQuickReply) {
                        InsetDivider()
                        SwitchRow(quoteQuickReply, "引用消息回复", "快捷回复时引用触发通知的原消息") {
                            quoteQuickReply = it
                        }
                    }
                    InsetDivider()
                    SwitchRow(applyMergeByTalker, "应用通知展示方式", "批量设置同会话通知合并或分散") { applyMergeByTalker = it }
                    if (applyMergeByTalker) {
                        InsetDivider()
                        SwitchRow(mergeByTalker, "合并同会话通知", "同一 wxid 的新消息更新到一条通知") { mergeByTalker = it }
                    }
                    InsetDivider()
                    SwitchRow(applyShowDetail, "应用消息详情", "批量修改通知内容是否显示详情") { applyShowDetail = it }
                    if (applyShowDetail) {
                        InsetDivider()
                        SwitchRow(showDetail, "显示消息详情", "关闭后只显示收到一条新消息") { showDetail = it }
                    }
                }
            }
            item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "静默与群聊") }
            item {
                SettingsCard {
                    SwitchRow(applyMute, "应用静默时段", "批量修改时段静默") { applyMute = it }
                    if (applyMute) {
                        InsetDivider()
                        SwitchRow(muteEnable, "开启时段静默", "指定时间内不弹通知") { muteEnable = it }
                        if (muteEnable) {
                            InsetDivider()
                            TimeOfDayPickerRow("开始时间", muteStart) { muteStart = it }
                            InsetDivider()
                            TimeOfDayPickerRow("结束时间", muteEnd) { muteEnd = it }
                        }
                    }
                    InsetDivider()
                    SwitchRow(applyAtRules, "应用群聊 @ 规则", "只对群聊规则生效") { applyAtRules = it }
                    if (applyAtRules) {
                        InsetDivider()
                        SwitchRow(blockAtAll, "屏蔽@所有人", "命中 @所有人 时不弹通知") { blockAtAll = it }
                        InsetDivider()
                        SwitchRow(blockAtMe, "屏蔽@我", "命中 @我 时不弹通知") { blockAtMe = it }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CustomNotificationMissingRulePage(onBack: () -> Unit) {
    val scrollBehavior = MiuixScrollBehavior()
    PageScaffold(
        title = "会话规则",
        largeTitle = "会话规则",
        scrollBehavior = scrollBehavior,
        bottomBar = { BottomActionBar("返回", onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SettingsCard { EmptyText("该规则已不存在") } }
        }
    }
}

@Composable
internal fun CustomNotificationRuleEditorPage(
    context: Context,
    initialRule: CustomNotificationRule,
    listState: LazyListState,
    isDefault: Boolean = false,
    onBack: () -> Unit,
    onSave: (CustomNotificationRule) -> Unit,
    onDelete: () -> Unit,
    onPickOnlyMembers: (String) -> Unit,
    onPickBlockMembers: (String) -> Unit
) {
    var enabled by remember(initialRule) { mutableStateOf(initialRule.enabled) }
    var dnd by remember(initialRule) { mutableStateOf(initialRule.mode == CustomNotificationSettings.MODE_DND) }
    var vibrate by remember(initialRule) { mutableStateOf(initialRule.vibrate) }
    var sound by remember(initialRule) { mutableStateOf(initialRule.sound) }
    var markRead by remember(initialRule) { mutableStateOf(initialRule.markRead) }
    var quickReply by remember(initialRule) { mutableStateOf(initialRule.quickReply) }
    var quoteQuickReply by remember(initialRule) { mutableStateOf(initialRule.quoteQuickReply) }
    var mergeByTalker by remember(initialRule) { mutableStateOf(initialRule.mergeByTalker) }
    var showDetail by remember(initialRule) { mutableStateOf(initialRule.showDetail) }
    var ignoreWechatDnd by remember(initialRule) { mutableStateOf(initialRule.ignoreWechatDnd) }
    var muteEnable by remember(initialRule) { mutableStateOf(initialRule.muteEnable) }
    var muteStart by remember(initialRule) { mutableStateOf(initialRule.muteStart) }
    var muteEnd by remember(initialRule) { mutableStateOf(initialRule.muteEnd) }
    var ringtone by remember(initialRule) { mutableStateOf(initialRule.ringtone) }
    var blockAtAll by remember(initialRule) { mutableStateOf(initialRule.blockAtAll) }
    var blockAtMe by remember(initialRule) { mutableStateOf(initialRule.blockAtMe) }
    var onlyMembers by remember(initialRule) { mutableStateOf(initialRule.onlyMembers) }
    var blockMembers by remember(initialRule) { mutableStateOf(initialRule.blockMembers) }
    val scrollBehavior = MiuixScrollBehavior()

    PageScaffold(
        title = when {
            isDefault && initialRule.official -> "默认公众号通知"
            isDefault && initialRule.group -> "默认群聊通知"
            isDefault -> "默认私聊通知"
            initialRule.group -> "群聊通知配置"
            initialRule.official -> "公众号通知配置"
            else -> "私聊通知配置"
        },
        largeTitle = initialRule.label.ifBlank { initialRule.talker },
        scrollBehavior = scrollBehavior,
        bottomBar = {
            BottomActionBar(
                primaryText = "保存",
                onPrimaryClick = {
                    onSave(
                        initialRule.copy(
                            enabled = enabled,
                            mode = if (dnd) CustomNotificationSettings.MODE_DND else CustomNotificationSettings.MODE_NOTIFY,
                            vibrate = vibrate,
                            sound = sound,
                            markRead = markRead,
                            quickReply = quickReply,
                            quoteQuickReply = quoteQuickReply,
                            mergeByTalker = mergeByTalker,
                            showDetail = showDetail,
                            ignoreWechatDnd = ignoreWechatDnd,
                            muteEnable = muteEnable,
                            muteStart = CustomNotificationSettings.normalizeTime(muteStart, CustomNotificationSettings.DEFAULT_MUTE_START),
                            muteEnd = CustomNotificationSettings.normalizeTime(muteEnd, CustomNotificationSettings.DEFAULT_MUTE_END),
                            ringtone = ringtone,
                            blockAtAll = blockAtAll,
                            blockAtMe = blockAtMe,
                            onlyMembers = if (isDefault) "" else CustomNotificationSettings.normalizeMemberRules(onlyMembers),
                            blockMembers = if (isDefault) "" else CustomNotificationSettings.normalizeMemberRules(blockMembers)
                        )
                    )
                },
                secondaryText = "返回",
                onSecondaryClick = onBack
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "会话") }
            item {
                SettingsCard {
                    if (isDefault) {
                        InfoRow(
                            "类型",
                            when {
                                initialRule.official -> "未单独配置的公众号"
                                initialRule.group -> "未单独配置的群聊"
                                else -> "未单独配置的私聊"
                            }
                        )
                    } else {
                        InfoRow("名称", initialRule.label.ifBlank { initialRule.talker })
                        InsetDivider()
                        InfoRow("ID", initialRule.talker)
                    }
                }
            }
            item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "通知") }
            item {
                SettingsCard {
                    SwitchRow(
                        enabled,
                        if (isDefault) "启用默认规则" else "启用此会话规则",
                        if (isDefault) "开启后接管未单独配置的对应会话通知" else "关闭后不再接管该会话通知"
                    ) { enabled = it }
                    InsetDivider()
                    SwitchRow(dnd, "免打扰", "开启后不弹自定义通知，也会拦截原生通知") { dnd = it }
                    InsetDivider()
                    SwitchRow(
                        ignoreWechatDnd,
                        "忽略微信自带的消息免打扰",
                        "开启后，该会话在微信中设置免打扰仍会弹出自定义通知"
                    ) { ignoreWechatDnd = it }
                    InsetDivider()
                    SwitchRow(vibrate, "震动", "通知时震动") { vibrate = it }
                    InsetDivider()
                    SwitchRow(sound, "铃声", "通知时播放系统或自定义铃声") { sound = it }
                    InsetDivider()
                    SwitchRow(markRead, "已读按钮", "通知栏直接标记当前会话已读") { markRead = it }
                    InsetDivider()
                    SwitchRow(quickReply, "快捷回复", "通知栏直接回复文本消息") { quickReply = it }
                    if (quickReply) {
                        InsetDivider()
                        SwitchRow(quoteQuickReply, "引用消息回复", "快捷回复时引用触发通知的原消息") {
                            quoteQuickReply = it
                        }
                    }
                    InsetDivider()
                    SwitchRow(mergeByTalker, "合并同会话通知", "同一 wxid 的新消息更新到一条通知") { mergeByTalker = it }
                    InsetDivider()
                    SwitchRow(showDetail, "通知显示消息详情", "关闭后只显示收到一条新消息") { showDetail = it }
                }
            }
            item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "铃声") }
            item {
                SettingsCard {
                    ActionRow("选择系统铃声", ringtoneDisplayName(context, ringtone, RedPacketSettings.NOTIFY_SOUND_MODE_SYSTEM)) {
                        val activity = context as? Activity
                        if (activity == null) {
                            Toast.makeText(context, "当前页面无法打开铃声选择器", Toast.LENGTH_SHORT).show()
                        } else {
                            RingtonePickerBridge.launchSystem(activity, ringtone) { picked ->
                                ringtone = CustomNotificationRuntime.freezeRingtoneUri(context, picked)
                                Toast.makeText(context, "铃声已选择，保存后生效", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    InsetDivider()
                    ActionRow("从文件选择铃声", if (ringtone.isBlank()) "未选择" else ringtoneDisplayName(context, ringtone, RedPacketSettings.NOTIFY_SOUND_MODE_CUSTOM)) {
                        val activity = context as? Activity
                        if (activity == null) {
                            Toast.makeText(context, "当前页面无法打开文件选择器", Toast.LENGTH_SHORT).show()
                        } else {
                            RingtonePickerBridge.launchFile(activity) { picked ->
                                ringtone = CustomNotificationRuntime.freezeRingtoneUri(context, picked)
                                Toast.makeText(context, "铃声已选择，保存后生效", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    if (ringtone.isNotBlank()) {
                        InsetDivider()
                        ActionRow("清空铃声", "恢复跟随系统") {
                            ringtone = ""
                            Toast.makeText(context, "铃声已清空，保存后生效", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "静默时段") }
            item {
                SettingsCard {
                    SwitchRow(muteEnable, "开启时段静默", "指定时间内不弹通知") { muteEnable = it }
                    if (muteEnable) {
                        InsetDivider()
                        TimeOfDayPickerRow("开始时间", muteStart) { muteStart = it }
                        InsetDivider()
                        TimeOfDayPickerRow("结束时间", muteEnd) { muteEnd = it }
                    }
                }
            }
            if (initialRule.group) {
                item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "群聊过滤") }
                item {
                    SettingsCard {
                        if (!isDefault) {
                            ActionRow("仅显示成员通知", memberRulesSummary(onlyMembers)) {
                                onPickOnlyMembers(onlyMembers)
                            }
                            InsetDivider()
                            ActionRow("屏蔽成员通知", memberRulesSummary(blockMembers)) {
                                onPickBlockMembers(blockMembers)
                            }
                            InsetDivider()
                        }
                        SwitchRow(blockAtAll, "屏蔽@所有人", "命中 @所有人 时不弹通知") { blockAtAll = it }
                        InsetDivider()
                        SwitchRow(blockAtMe, "屏蔽@我", "命中 @我 时不弹通知") { blockAtMe = it }
                    }
                }
            }
            if (!isDefault) {
                item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "操作") }
                item {
                    SettingsCard {
                        ActionRow("删除规则", "移除该会话自定义通知配置") {
                            onDelete()
                        }
                    }
                }
            }
        }
    }
}

internal fun customNotificationRuleFromContact(option: ContactOption): CustomNotificationRule {
    return CustomNotificationRule(
        id = option.id,
        talker = option.id,
        label = option.label.ifBlank { option.id },
        group = option.group,
        official = option.official
    )
}

internal fun customNotificationRuleTitle(rule: CustomNotificationRule): String {
    val prefix = when {
        rule.group -> "群聊"
        rule.official -> "公众号"
        else -> "私聊"
    }
    val state = if (rule.enabled) "" else "（关闭）"
    return "$prefix · ${rule.label.ifBlank { rule.talker }}$state"
}

internal fun customNotificationRuleSummary(context: Context, rule: CustomNotificationRule): String {
    val items = ArrayList<String>()
    if (!rule.enabled) items += "未启用"
    if (rule.mode == CustomNotificationSettings.MODE_DND) items += "免打扰"
    if (rule.sound) items += if (rule.ringtone.isBlank()) "铃声" else ringtoneDisplayName(context, rule.ringtone, RedPacketSettings.NOTIFY_SOUND_MODE_CUSTOM)
    if (rule.vibrate) items += "震动"
    if (rule.markRead) items += "已读按钮"
    if (rule.quickReply) items += if (rule.quoteQuickReply) "引用快捷回复" else "快捷回复"
    if (rule.mergeByTalker) items += "合并通知"
    if (rule.ignoreWechatDnd) items += "忽略微信免打扰"
    if (rule.muteEnable) items += "${rule.muteStart}-${rule.muteEnd}静默"
    if (rule.group) {
        if (rule.onlyMembers.isNotBlank()) items += "仅${CustomNotificationSettings.splitMemberRules(rule.onlyMembers).size}成员"
        if (rule.blockMembers.isNotBlank()) items += "屏蔽${CustomNotificationSettings.splitMemberRules(rule.blockMembers).size}成员"
        if (rule.blockAtAll) items += "屏蔽@所有人"
        if (rule.blockAtMe) items += "屏蔽@我"
    }
    return items.ifEmpty { listOf("点击配置通知规则") }.joinToString(" / ")
}

internal fun memberRulesSummary(value: String): String {
    val count = CustomNotificationSettings.splitMemberRules(value).size
    return if (count == 0) "未设置" else "已设置 $count 项"
}

internal fun memberRulesToGroupEntries(groupId: String, value: String): String {
    return CustomNotificationSettings.splitMemberRules(value)
        .map { member -> groupMemberEntry(groupId, member) }
        .joinToString(",")
}

internal fun groupEntriesToMemberRules(groupId: String, entries: List<String>): String {
    val members = entries.mapNotNull { entry ->
        val normalized = entry.trim()
        val separators = charArrayOf('/', '#', ':', '：')
        val index = separators.map { normalized.indexOf(it) }
            .filter { it > 0 }
            .minOrNull()
            ?: return@mapNotNull normalized.takeIf { it.isNotBlank() }
        val group = normalized.substring(0, index).trim()
        val member = normalized.substring(index + 1).trim()
        if (group == groupId && member.isNotBlank()) member else null
    }
    return members.distinct().joinToString(",")
}

internal data class CustomNotificationBatchUpdate(
    val applyEnabled: Boolean,
    val enabled: Boolean,
    val applyMode: Boolean,
    val dnd: Boolean,
    val applyVibrate: Boolean,
    val vibrate: Boolean,
    val applySound: Boolean,
    val sound: Boolean,
    val applyMarkRead: Boolean,
    val markRead: Boolean,
    val applyQuickReply: Boolean,
    val quickReply: Boolean,
    val applyQuoteQuickReply: Boolean,
    val quoteQuickReply: Boolean,
    val applyMergeByTalker: Boolean,
    val mergeByTalker: Boolean,
    val applyShowDetail: Boolean,
    val showDetail: Boolean,
    val applyIgnoreWechatDnd: Boolean,
    val ignoreWechatDnd: Boolean,
    val applyMute: Boolean,
    val muteEnable: Boolean,
    val muteStart: String,
    val muteEnd: String,
    val applyAtRules: Boolean,
    val blockAtAll: Boolean,
    val blockAtMe: Boolean
) {
    fun hasChanges(): Boolean {
        return applyEnabled || applyMode || applyVibrate || applySound || applyMarkRead || applyQuickReply || applyQuoteQuickReply || applyMergeByTalker ||
            applyShowDetail || applyIgnoreWechatDnd || applyMute || applyAtRules
    }

    fun apply(rule: CustomNotificationRule): CustomNotificationRule {
        var next = rule
        if (applyEnabled) next = next.copy(enabled = enabled)
        if (applyMode) next = next.copy(mode = if (dnd) CustomNotificationSettings.MODE_DND else CustomNotificationSettings.MODE_NOTIFY)
        if (applyVibrate) next = next.copy(vibrate = vibrate)
        if (applySound) next = next.copy(sound = sound)
        if (applyMarkRead) next = next.copy(markRead = markRead)
        if (applyQuickReply) next = next.copy(quickReply = quickReply)
        if (applyQuoteQuickReply) next = next.copy(quoteQuickReply = quoteQuickReply)
        if (applyMergeByTalker) next = next.copy(mergeByTalker = mergeByTalker)
        if (applyShowDetail) next = next.copy(showDetail = showDetail)
        if (applyIgnoreWechatDnd) next = next.copy(ignoreWechatDnd = ignoreWechatDnd)
        if (applyMute) {
            next = next.copy(
                muteEnable = muteEnable,
                muteStart = CustomNotificationSettings.normalizeTime(muteStart, CustomNotificationSettings.DEFAULT_MUTE_START),
                muteEnd = CustomNotificationSettings.normalizeTime(muteEnd, CustomNotificationSettings.DEFAULT_MUTE_END)
            )
        }
        if (applyAtRules && next.group) {
            next = next.copy(blockAtAll = blockAtAll, blockAtMe = blockAtMe)
        }
        return next
    }
}

@Composable
internal fun KeywordNotificationMiuixPage(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit
) {
    val settings = remember { KeywordNotificationSettings(context) }
    val sp = remember { HchatStorage.preferences(context, KeywordNotificationSettings.PREFS_NAME) }
    var route by remember { mutableStateOf<KeywordNotificationRoute>(KeywordNotificationRoute.Main) }
    var keywords by remember { mutableStateOf(settings.keywords()) }
    var excludeContacts by remember { mutableStateOf(settings.excludeContacts()) }
    var includeContacts by remember { mutableStateOf(settings.includeContacts()) }
    val keywordMainListState = rememberLazyListState()
    val keywordListState = rememberLazyListState()
    var keywordQuery by remember { mutableStateOf("") }

    fun saveKeywords(next: List<KeywordRule>) {
        val clean = next.map { it.copy(keyword = it.keyword.trim()) }
            .filter { it.keyword.isNotBlank() }
            .distinctBy { it.keyword }
        keywords = clean
        settings.saveKeywords(clean)
    }

    fun saveContacts(includeMode: Boolean, ids: Set<String>) {
        if (includeMode) {
            includeContacts = ids
            settings.saveIncludeContacts(ids)
        } else {
            excludeContacts = ids
            settings.saveExcludeContacts(ids)
        }
    }

    SettingsRouteTransition(
        targetState = route,
        label = "KeywordNotificationRoute",
        depthOf = { it.depth() }
    ) { currentRoute ->
        when (currentRoute) {
            KeywordNotificationRoute.Main -> KeywordNotificationMainPage(
                context = context,
                provider = provider,
                sp = sp,
                keywords = keywords,
                excludeContacts = excludeContacts,
                includeContacts = includeContacts,
                listState = keywordMainListState,
                onBack = onBack,
                onOpenKeywords = { route = KeywordNotificationRoute.KeywordList },
                onOpenTemplates = { route = KeywordNotificationRoute.TemplateEditor },
                onPickContacts = { includeMode ->
                    val current = if (includeMode) includeContacts else excludeContacts
                    route = KeywordNotificationRoute.ContactPicker(
                        request = ContactPickerRequest(
                            title = if (includeMode) "选择仅生效聊天" else "选择排除聊天",
                            mode = ContactPickerMode.BOTH,
                            multiSelect = true,
                            existingValue = current.joinToString(","),
                            onValue = {},
                            enableLabels = true
                        ),
                        includeMode = includeMode
                    )
                },
                onClearContacts = { includeMode ->
                    saveContacts(includeMode, emptySet())
                    Toast.makeText(context, if (includeMode) "已清空仅生效名单" else "已清空排除名单", Toast.LENGTH_SHORT).show()
                }
            )
            KeywordNotificationRoute.KeywordList -> KeywordNotificationKeywordListPage(
                keywords = keywords,
                query = keywordQuery,
                onQueryChange = { keywordQuery = it },
                listState = keywordListState,
                onBack = { route = KeywordNotificationRoute.Main },
                onAdd = { route = KeywordNotificationRoute.KeywordEditor(null) },
                onEdit = { route = KeywordNotificationRoute.KeywordEditor(it.keyword) },
                onClear = {
                    saveKeywords(emptyList())
                    Toast.makeText(context, "已清空关键词", Toast.LENGTH_SHORT).show()
                }
            )
            is KeywordNotificationRoute.KeywordEditor -> {
                val old = currentRoute.oldKeyword?.let { oldKeyword -> keywords.firstOrNull { it.keyword == oldKeyword } }
                KeywordNotificationKeywordEditorPage(
                    initial = old,
                    onBack = { route = KeywordNotificationRoute.KeywordList },
                    onSave = { saved ->
                        if (saved.keyword.isBlank()) {
                            Toast.makeText(context, "关键词不能为空", Toast.LENGTH_SHORT).show()
                        } else if (currentRoute.oldKeyword != saved.keyword && keywords.any { it.keyword == saved.keyword }) {
                            Toast.makeText(context, "该关键词已存在", Toast.LENGTH_SHORT).show()
                        } else {
                            val next = if (currentRoute.oldKeyword == null) {
                                keywords + saved
                            } else {
                                keywords.map { if (it.keyword == currentRoute.oldKeyword) saved else it }
                            }
                            saveKeywords(next)
                            Toast.makeText(context, "关键词已保存", Toast.LENGTH_SHORT).show()
                            route = KeywordNotificationRoute.KeywordList
                        }
                    },
                    onDelete = if (old == null) {
                        null
                    } else {
                        {
                            saveKeywords(keywords.filterNot { it.keyword == old.keyword })
                            Toast.makeText(context, "关键词已删除", Toast.LENGTH_SHORT).show()
                            route = KeywordNotificationRoute.KeywordList
                        }
                    }
                )
            }
            KeywordNotificationRoute.TemplateEditor -> KeywordNotificationTemplatePage(
                context = context,
                sp = sp,
                onBack = { route = KeywordNotificationRoute.Main }
            )
            is KeywordNotificationRoute.ContactPicker -> ContactPickerPage(
                context = context,
                request = currentRoute.request,
                onBack = { route = KeywordNotificationRoute.Main },
                onConfirm = { picked ->
                    val ids = picked.map { it.id }.filter { it.isNotBlank() }.toSet()
                    saveContacts(currentRoute.includeMode, ids)
                    Toast.makeText(context, "名单已保存", Toast.LENGTH_SHORT).show()
                    route = KeywordNotificationRoute.Main
                }
            )
        }
    }
}

@Composable
internal fun TextSpeechMiuixPage(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit
) {
    val settings = remember { TextSpeechSettings(context) }
    val sp = remember { HchatStorage.preferences(context, TextSpeechSettings.PREFS_NAME) }
    var route by remember { mutableStateOf<TextSpeechRoute>(TextSpeechRoute.Main) }
    var allowedContacts by remember { mutableStateOf(settings.allowedContacts()) }
    val mainListState = rememberLazyListState()

    fun saveAllowedContacts(ids: Set<String>) {
        allowedContacts = ids
        settings.saveAllowedContacts(ids)
    }

    SettingsRouteTransition(
        targetState = route,
        label = "TextSpeechRoute",
        depthOf = { it.depth() }
    ) { currentRoute ->
        when (currentRoute) {
            TextSpeechRoute.Main -> TextSpeechMainPage(
                provider = provider,
                sp = sp,
                allowedContacts = allowedContacts,
                listState = mainListState,
                onBack = onBack,
                onPickContacts = {
                    route = TextSpeechRoute.ContactPicker(
                        ContactPickerRequest(
                            title = "设置允许名单",
                            mode = ContactPickerMode.BOTH,
                            multiSelect = true,
                            existingValue = allowedContacts.joinToString("|"),
                            onValue = {},
                            enableLabels = true
                        )
                    )
                }
            )
            is TextSpeechRoute.ContactPicker -> ContactPickerPage(
                context = context,
                request = currentRoute.request,
                onBack = { route = TextSpeechRoute.Main },
                onConfirm = { picked ->
                    saveAllowedContacts(
                        picked.map { it.id }
                            .filter { it.isNotBlank() }
                            .toSet()
                    )
                    Toast.makeText(context, "允许名单已保存", Toast.LENGTH_SHORT).show()
                    route = TextSpeechRoute.Main
                }
            )
        }
    }
}

@Composable
internal fun TextSpeechMainPage(
    provider: FeatureSettingsProvider,
    sp: SharedPreferences,
    allowedContacts: Set<String>,
    listState: LazyListState,
    onBack: () -> Unit,
    onPickContacts: () -> Unit
) {
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()
    var enabled by remember {
        mutableStateOf(sp.getBoolean(TextSpeechSettings.KEY_ENABLE, TextSpeechSettings.DEFAULT_ENABLE))
    }
    var ttsEngine by remember {
        mutableStateOf(
            sp.getString(TextSpeechSettings.KEY_TTS_ENGINE, TextSpeechSettings.DEFAULT_TTS_ENGINE)
                ?: TextSpeechSettings.DEFAULT_TTS_ENGINE
        )
    }
    var ttsVoice by remember {
        mutableStateOf(
            sp.getString(TextSpeechSettings.KEY_TTS_VOICE, TextSpeechSettings.DEFAULT_TTS_VOICE)
                ?: TextSpeechSettings.DEFAULT_TTS_VOICE
        )
    }
    var ttsVoiceChoices by remember { mutableStateOf<List<PopupChoice<String>>>(emptyList()) }
    var ttsVoiceLoading by remember { mutableStateOf(true) }
    var ttsVoiceError by remember { mutableStateOf("") }
    var ttsFallbackEnginePackage by remember { mutableStateOf("") }
    val installedTtsEngines = remember(context) { TextSpeechEngineCatalog.installed(context) }
    val ttsFallbackEngineLabel = remember(installedTtsEngines, ttsFallbackEnginePackage) {
        installedTtsEngines.firstOrNull { it.packageName == ttsFallbackEnginePackage }
            ?.label
            ?: ttsFallbackEnginePackage
    }
    val ttsEngineOptions = remember(installedTtsEngines, ttsEngine) {
        buildList {
            add(PopupChoice("跟随系统默认", TextSpeechSettings.DEFAULT_TTS_ENGINE))
            installedTtsEngines.forEach { add(PopupChoice(it.label, it.packageName)) }
            if (ttsEngine.isNotBlank() && installedTtsEngines.none { it.packageName == ttsEngine }) {
                add(PopupChoice("已不可用（$ttsEngine）", ttsEngine))
            }
        }
    }
    val ttsVoiceOptions = remember(ttsVoiceChoices, ttsVoice) {
        buildList {
            add(PopupChoice("跟随引擎默认", TextSpeechSettings.DEFAULT_TTS_VOICE))
            addAll(ttsVoiceChoices)
            if (ttsVoice.isNotBlank() && ttsVoiceChoices.none { it.value == ttsVoice }) {
                add(PopupChoice("已不可用（$ttsVoice）", ttsVoice))
            }
        }
    }
    DisposableEffect(context, ttsEngine) {
        ttsVoiceLoading = true
        ttsVoiceError = ""
        ttsFallbackEnginePackage = ""
        ttsVoiceChoices = emptyList()
        val handle = TextSpeechVoiceCatalog.load(context, ttsEngine) { result ->
            ttsVoiceChoices = result.options.map { PopupChoice(it.label, it.name) }
            ttsVoiceError = result.error
            ttsFallbackEnginePackage = if (result.usedFallback) {
                result.activeEnginePackage.ifBlank { "其它可用引擎" }
            } else {
                ""
            }
            ttsVoiceLoading = false
        }
        onDispose { handle.cancel() }
    }
    var volumeControl by remember {
        mutableStateOf(
            sp.getBoolean(
                TextSpeechSettings.KEY_VOLUME_CONTROL,
                TextSpeechSettings.DEFAULT_VOLUME_CONTROL
            )
        )
    }
    var playVoiceMessages by remember {
        mutableStateOf(
            sp.getBoolean(
                TextSpeechSettings.KEY_PLAY_VOICE_MESSAGES,
                TextSpeechSettings.DEFAULT_PLAY_VOICE_MESSAGES
            )
        )
    }
    var customAnnouncement by remember {
        mutableStateOf(
            sp.getBoolean(
                TextSpeechSettings.KEY_ANNOUNCE_SENDER,
                TextSpeechSettings.DEFAULT_ANNOUNCE_SENDER
            )
        )
    }
    var announcementTemplate by remember {
        mutableStateOf(
            sp.getString(
                TextSpeechSettings.KEY_ANNOUNCEMENT_TEMPLATE,
                TextSpeechSettings.DEFAULT_ANNOUNCEMENT_TEMPLATE
            ) ?: TextSpeechSettings.DEFAULT_ANNOUNCEMENT_TEMPLATE
        )
    }
    var timeFormat by remember {
        mutableStateOf(
            sp.getString(TextSpeechSettings.KEY_TIME_FORMAT, TextSpeechSettings.DEFAULT_TIME_FORMAT)
                ?: TextSpeechSettings.DEFAULT_TIME_FORMAT
        )
    }
    var respectWechatDnd by remember {
        mutableStateOf(
            sp.getBoolean(
                TextSpeechSettings.KEY_RESPECT_WECHAT_DND,
                TextSpeechSettings.DEFAULT_RESPECT_WECHAT_DND
            )
        )
    }
    var quietEnabled by remember {
        mutableStateOf(
            sp.getBoolean(
                TextSpeechSettings.KEY_QUIET_ENABLE,
                TextSpeechSettings.DEFAULT_QUIET_ENABLE
            )
        )
    }
    var quietStart by remember {
        mutableStateOf(
            sp.getString(TextSpeechSettings.KEY_QUIET_START, TextSpeechSettings.DEFAULT_QUIET_START)
                ?: TextSpeechSettings.DEFAULT_QUIET_START
        )
    }
    var quietEnd by remember {
        mutableStateOf(
            sp.getString(TextSpeechSettings.KEY_QUIET_END, TextSpeechSettings.DEFAULT_QUIET_END)
                ?: TextSpeechSettings.DEFAULT_QUIET_END
        )
    }

    PageScaffold(
        title = provider.title(),
        largeTitle = provider.title(),
        scrollBehavior = scrollBehavior,
        bottomBar = { BottomActionBar("返回", onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "播报") }
            item {
                SettingsCard {
                    SwitchRow(
                        checked = enabled,
                        title = "自动播放语音",
                        summary = "播报允许名单内收到的消息"
                    ) {
                        enabled = it
                        sp.edit().putBoolean(TextSpeechSettings.KEY_ENABLE, it).apply()
                    }
                    if (enabled) {
                        InsetDivider()
                        PopupChoiceRow(
                            title = "语音引擎",
                            summary = "选择 TTS 播报使用的语音引擎",
                            options = ttsEngineOptions,
                            currentValue = ttsEngine,
                            onValueChanged = {
                                ttsEngine = it
                                ttsVoice = TextSpeechSettings.DEFAULT_TTS_VOICE
                                sp.edit()
                                    .putString(TextSpeechSettings.KEY_TTS_ENGINE, it)
                                    .putString(
                                        TextSpeechSettings.KEY_TTS_VOICE,
                                        TextSpeechSettings.DEFAULT_TTS_VOICE
                                    )
                                    .apply()
                            }
                        )
                        InsetDivider()
                        PopupChoiceRow(
                            title = "播报角色",
                            summary = when {
                                ttsVoiceLoading -> "正在读取所选引擎的播报角色"
                                ttsVoiceError.isNotBlank() -> "读取失败：$ttsVoiceError"
                                ttsFallbackEnginePackage.isNotBlank() ->
                                    "系统默认不可用，已临时使用 $ttsFallbackEngineLabel"
                                ttsVoiceChoices.isEmpty() -> "所选引擎未提供可选角色，将跟随引擎默认"
                                else -> "选择所选引擎提供的播报角色"
                            },
                            options = ttsVoiceOptions,
                            currentValue = ttsVoice,
                            enabled = !ttsVoiceLoading,
                            onValueChanged = {
                                ttsVoice = it
                                sp.edit().putString(TextSpeechSettings.KEY_TTS_VOICE, it).apply()
                            }
                        )
                        InsetDivider()
                        SwitchRow(
                            checked = playVoiceMessages,
                            title = "播放语音消息",
                            summary = "收到语音时直接播放原语音，不使用 TTS"
                        ) {
                            playVoiceMessages = it
                            sp.edit()
                                .putBoolean(TextSpeechSettings.KEY_PLAY_VOICE_MESSAGES, it)
                                .apply()
                        }
                        InsetDivider()
                        SwitchRow(
                            checked = volumeControl,
                            title = "音键控制",
                            summary = "音量减：暂停\n音量加：继续\n暂停时再按音量减：跳过"
                        ) {
                            volumeControl = it
                            sp.edit().putBoolean(TextSpeechSettings.KEY_VOLUME_CONTROL, it).apply()
                        }
                        InsetDivider()
                        SwitchRow(
                            checked = customAnnouncement,
                            title = "自定义播报内容",
                            summary = "分别使用消息对应的昵称、群聊和正文变量"
                        ) {
                            customAnnouncement = it
                            sp.edit().putBoolean(TextSpeechSettings.KEY_ANNOUNCE_SENDER, it).apply()
                        }
                        if (customAnnouncement) {
                            InsetDivider()
                            VariableInputRow(
                                title = "播报内容格式",
                                summary = "点击下方中文变量插入到光标位置",
                                value = announcementTemplate,
                                variables = textSpeechTemplateVariables,
                                minLines = 3
                            ) {
                                announcementTemplate = it
                                sp.edit()
                                    .putString(TextSpeechSettings.KEY_ANNOUNCEMENT_TEMPLATE, it)
                                    .apply()
                            }
                            if (announcementTemplate.contains(TextSpeechSettings.VAR_MESSAGE_TIME)) {
                                InsetDivider()
                                InputRow(
                                    title = "消息时间格式",
                                    summary = if (TextSpeechSettings.isValidTimeFormat(timeFormat)) {
                                        "例如 HH:mm:ss 或 yyyy-MM-dd HH:mm:ss"
                                    } else {
                                        "格式无效，当前输入不会保存"
                                    },
                                    value = timeFormat
                                ) {
                                    timeFormat = it
                                    if (TextSpeechSettings.isValidTimeFormat(it)) {
                                        sp.edit().putString(
                                            TextSpeechSettings.KEY_TIME_FORMAT,
                                            TextSpeechSettings.normalizeTimeFormat(it)
                                        ).apply()
                                    }
                                }
                            }
                            InsetDivider()
                            ActionRow(
                                title = "恢复默认格式",
                                summary = TextSpeechSettings.DEFAULT_ANNOUNCEMENT_TEMPLATE
                            ) {
                                announcementTemplate = TextSpeechSettings.DEFAULT_ANNOUNCEMENT_TEMPLATE
                                timeFormat = TextSpeechSettings.DEFAULT_TIME_FORMAT
                                sp.edit()
                                    .putString(
                                        TextSpeechSettings.KEY_ANNOUNCEMENT_TEMPLATE,
                                        TextSpeechSettings.DEFAULT_ANNOUNCEMENT_TEMPLATE
                                    )
                                    .putString(
                                        TextSpeechSettings.KEY_TIME_FORMAT,
                                        TextSpeechSettings.DEFAULT_TIME_FORMAT
                                    )
                                    .apply()
                            }
                        }
                        InsetDivider()
                        SwitchRow(
                            checked = respectWechatDnd,
                            title = "免打扰不播报",
                            summary = "私聊或群聊开启微信消息免打扰后停止播报"
                        ) {
                            respectWechatDnd = it
                            sp.edit()
                                .putBoolean(TextSpeechSettings.KEY_RESPECT_WECHAT_DND, it)
                                .apply()
                        }
                        InsetDivider()
                        SwitchRow(
                            checked = quietEnabled,
                            title = "定时免打扰",
                            summary = "指定时段内暂停自动播报"
                        ) {
                            quietEnabled = it
                            sp.edit().putBoolean(TextSpeechSettings.KEY_QUIET_ENABLE, it).apply()
                        }
                        if (quietEnabled) {
                            InsetDivider()
                            TimeOfDayPickerRow("开始时间", quietStart) {
                                quietStart = it
                                sp.edit().putString(TextSpeechSettings.KEY_QUIET_START, it).apply()
                            }
                            InsetDivider()
                            TimeOfDayPickerRow("结束时间", quietEnd) {
                                quietEnd = it
                                sp.edit().putString(TextSpeechSettings.KEY_QUIET_END, it).apply()
                            }
                        }
                        InsetDivider()
                        ActionRow(
                            title = "设置允许名单",
                            summary = if (allowedContacts.isEmpty()) {
                                "未设置"
                            } else {
                                "已选择 ${allowedContacts.size} 个聊天"
                            },
                            onClick = onPickContacts
                        )
                    }
                }
            }
        }
    }
}

internal enum class ZombieCheckSelection {
    TARGETS,
    EXCLUDED,
    DELETE
}

internal data class ZombieCheckDeleteTarget(val wxid: String, val name: String)

internal sealed class ZombieCheckRoute {
    object Main : ZombieCheckRoute()
    data class ContactPicker(
        val selection: ZombieCheckSelection,
        val request: ContactPickerRequest
    ) : ZombieCheckRoute()
}

@Composable
internal fun ZombieCheckMiuixPage(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit
) {
    val settings = remember { ZombieCheckSettings(context) }
    var route by remember { mutableStateOf<ZombieCheckRoute>(ZombieCheckRoute.Main) }
    var targetIds by remember { mutableStateOf(settings.targetIds()) }
    var excludedIds by remember { mutableStateOf(settings.excludedIds()) }
    var pendingDeleteTargets by remember { mutableStateOf(emptyList<ZombieCheckDeleteTarget>()) }

    SettingsRouteTransition(
        targetState = route,
        label = "ZombieCheckRoute",
        depthOf = { if (it is ZombieCheckRoute.Main) 0 else 1 }
    ) { currentRoute ->
        when (currentRoute) {
            ZombieCheckRoute.Main -> ZombieCheckMainPage(
                context = context,
                provider = provider,
                targetIds = targetIds,
                excludedIds = excludedIds,
                pendingDeleteTargets = pendingDeleteTargets,
                onBack = onBack,
                onPickTargets = {
                    route = ZombieCheckRoute.ContactPicker(
                        ZombieCheckSelection.TARGETS,
                        ContactPickerRequest(
                            title = "选择检测好友",
                            mode = ContactPickerMode.FRIENDS,
                            multiSelect = true,
                            existingValue = targetIds.joinToString("|"),
                            onValue = {},
                            enableLabels = true
                        )
                    )
                },
                onPickExcluded = {
                    route = ZombieCheckRoute.ContactPicker(
                        ZombieCheckSelection.EXCLUDED,
                        ContactPickerRequest(
                            title = "选择排除好友",
                            mode = ContactPickerMode.FRIENDS,
                            multiSelect = true,
                            existingValue = excludedIds.joinToString("|"),
                            onValue = {},
                            enableLabels = true
                        )
                    )
                },
                onPickDelete = {
                    val detectedIds = ZombieCheckController.snapshot().results
                        .filter { it.type == ZombieCheckResultType.DEAD }
                        .map { it.wxid }
                        .toSet()
                    route = ZombieCheckRoute.ContactPicker(
                        ZombieCheckSelection.DELETE,
                        ContactPickerRequest(
                            title = "批量删除好友",
                            mode = ContactPickerMode.FRIENDS,
                            multiSelect = true,
                            existingValue = detectedIds.joinToString("|"),
                            onValue = {},
                            enableLabels = true
                        )
                    )
                },
                onDismissDelete = { pendingDeleteTargets = emptyList() },
                onConfirmDelete = { clearRecord, delaySeconds ->
                    val result = ZombieCheckController.deleteFriends(
                        pendingDeleteTargets.map { it.wxid },
                        clearRecord,
                        delaySeconds
                    )
                    pendingDeleteTargets = emptyList()
                    result
                }
            )
            is ZombieCheckRoute.ContactPicker -> ContactPickerPage(
                context = context,
                request = currentRoute.request,
                onBack = { route = ZombieCheckRoute.Main },
                onConfirm = { picked ->
                    val ids = picked.map { it.id }.filter { it.isNotBlank() }.toSet()
                    when (currentRoute.selection) {
                        ZombieCheckSelection.TARGETS -> {
                            targetIds = ids
                            settings.saveTargetIds(ids)
                        }
                        ZombieCheckSelection.EXCLUDED -> {
                            excludedIds = ids
                            settings.saveExcludedIds(ids)
                        }
                        ZombieCheckSelection.DELETE -> {
                            pendingDeleteTargets = picked.map {
                                ZombieCheckDeleteTarget(it.id, it.label.ifBlank { it.id })
                            }
                        }
                    }
                    route = ZombieCheckRoute.Main
                }
            )
        }
    }
}

@Composable
internal fun ZombieCheckMainPage(
    context: Context,
    provider: FeatureSettingsProvider,
    targetIds: Set<String>,
    excludedIds: Set<String>,
    pendingDeleteTargets: List<ZombieCheckDeleteTarget>,
    onBack: () -> Unit,
    onPickTargets: () -> Unit,
    onPickExcluded: () -> Unit,
    onPickDelete: () -> Unit,
    onDismissDelete: () -> Unit,
    onConfirmDelete: (Boolean, Int) -> ZombieCheckActionResult
) {
    val sp = remember { HchatStorage.preferences(context, ZombieCheckSettings.PREFS_NAME) }
    var snapshot by remember { mutableStateOf(ZombieCheckController.snapshot()) }
    var enabled by remember { mutableStateOf(sp.getBoolean(ZombieCheckSettings.KEY_ENABLE, ZombieCheckSettings.DEFAULT_ENABLE)) }
    var autoTag by remember { mutableStateOf(sp.getBoolean(ZombieCheckSettings.KEY_AUTO_TAG, ZombieCheckSettings.DEFAULT_AUTO_TAG)) }
    var labelName by remember { mutableStateOf(sp.getString(ZombieCheckSettings.KEY_LABEL_NAME, ZombieCheckSettings.DEFAULT_LABEL_NAME).orEmpty()) }
    var autoDelete by remember { mutableStateOf(sp.getBoolean(ZombieCheckSettings.KEY_AUTO_DELETE, ZombieCheckSettings.DEFAULT_AUTO_DELETE)) }
    var clearRecord by remember { mutableStateOf(sp.getBoolean(ZombieCheckSettings.KEY_CLEAR_RECORD, ZombieCheckSettings.DEFAULT_CLEAR_RECORD)) }
    var keepAwake by remember { mutableStateOf(sp.getBoolean(ZombieCheckSettings.KEY_KEEP_AWAKE, ZombieCheckSettings.DEFAULT_KEEP_AWAKE)) }
    var minDelay by remember { mutableStateOf(sp.getInt(ZombieCheckSettings.KEY_MIN_DELAY_SECONDS, ZombieCheckSettings.DEFAULT_MIN_DELAY_SECONDS).toString()) }
    var maxDelay by remember { mutableStateOf(sp.getInt(ZombieCheckSettings.KEY_MAX_DELAY_SECONDS, ZombieCheckSettings.DEFAULT_MAX_DELAY_SECONDS).toString()) }
    var timeout by remember { mutableStateOf(sp.getInt(ZombieCheckSettings.KEY_TIMEOUT_SECONDS, ZombieCheckSettings.DEFAULT_TIMEOUT_SECONDS).toString()) }
    var retries by remember { mutableStateOf(sp.getInt(ZombieCheckSettings.KEY_MAX_RETRIES, ZombieCheckSettings.DEFAULT_MAX_RETRIES).toString()) }
    var deleteDelay by remember { mutableStateOf(sp.getInt(ZombieCheckSettings.KEY_DELETE_DELAY_SECONDS, ZombieCheckSettings.DEFAULT_DELETE_DELAY_SECONDS).toString()) }
    var confirmAutoDelete by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()

    LaunchedEffect(Unit) {
        while (true) {
            snapshot = ZombieCheckController.snapshot()
            delay(500L)
        }
    }

    fun saveInt(key: String, value: String, range: IntRange) {
        value.toIntOrNull()?.coerceIn(range.first, range.last)?.let {
            sp.edit().putInt(key, it).apply()
        }
    }

    fun toastResult(result: ZombieCheckActionResult) {
        Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
        snapshot = ZombieCheckController.snapshot()
    }

    val notableResults = snapshot.results.filter { it.type != ZombieCheckResultType.NORMAL }.asReversed()

    PageScaffold(
        title = provider.title(),
        largeTitle = provider.title(),
        scrollBehavior = scrollBehavior,
        bottomBar = { BottomActionBar("返回", onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "检测") }
            item {
                SettingsCard {
                    SwitchRow(
                        checked = enabled,
                        title = "启用僵尸粉检测",
                        summary = "使用微信转账下单接口核验好友关系，不会确认付款",
                        onCheckedChange = {
                            enabled = it
                            sp.edit().putBoolean(ZombieCheckSettings.KEY_ENABLE, it).apply()
                            if (!it && (snapshot.running || snapshot.deleting)) {
                                toastResult(ZombieCheckController.pause())
                            }
                        }
                    )
                    InsetDivider()
                    InfoRow("状态", if (snapshot.ready) snapshot.status else "检测接口定位中")
                    InsetDivider()
                    InfoRow(
                        "进度",
                        "已检测 ${snapshot.checkedCount}/${snapshot.totalCount}，异常 ${snapshot.deadCount}，失败 ${snapshot.unknownCount}"
                    )
                    if (snapshot.deleting || snapshot.deleteTotalCount > 0) {
                        InsetDivider()
                        InfoRow(
                            "删除进度",
                            "已处理 ${snapshot.deleteCompletedCount}/${snapshot.deleteTotalCount}，已提交 ${snapshot.deleteSuccessCount}，失败 ${snapshot.deleteFailureCount}"
                        )
                    }
                    if (snapshot.currentName.isNotBlank()) {
                        InsetDivider()
                        InfoRow("当前好友", snapshot.currentName)
                    }
                    InsetDivider()
                    ActionRow("检测范围", if (targetIds.isEmpty()) "全部好友" else "已选择 ${targetIds.size} 位好友", onPickTargets)
                    InsetDivider()
                    ActionRow("排除好友", if (excludedIds.isEmpty()) "未排除" else "已排除 ${excludedIds.size} 位好友", onPickExcluded)
                    InsetDivider()
                    ActionRow(
                        if (snapshot.running || snapshot.deleting) "停止当前任务" else "开始 / 继续检测",
                        when {
                            snapshot.deleting -> "停止后不再删除剩余好友"
                            snapshot.running -> "暂停后保留当前队列"
                            else -> "按当前范围继续未完成进度"
                        }
                    ) {
                        toastResult(
                            if (snapshot.running || snapshot.deleting) {
                                ZombieCheckController.pause()
                            } else {
                                ZombieCheckController.start()
                            }
                        )
                    }
                    InsetDivider()
                    ActionRow("重置检测进度", "清空断点、结果和运行日志") {
                        toastResult(ZombieCheckController.reset())
                    }
                }
            }

            item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "异常处理") }
            item {
                SettingsCard {
                    SwitchRow(
                        checked = autoTag,
                        title = "自动追加标签",
                        summary = "保留好友现有标签后追加指定标签",
                        onCheckedChange = {
                            autoTag = it
                            sp.edit().putBoolean(ZombieCheckSettings.KEY_AUTO_TAG, it).apply()
                        }
                    )
                    if (autoTag) {
                        InsetDivider()
                        InputRow("标签名称", "检测到好友关系异常时追加", labelName) {
                            labelName = it
                            sp.edit().putString(ZombieCheckSettings.KEY_LABEL_NAME, it.trim()).apply()
                        }
                    }
                    InsetDivider()
                    SwitchRow(
                        checked = autoDelete,
                        title = "自动删除异常好友",
                        summary = "高风险操作，默认关闭；暂停检测会取消尚未执行的删除",
                        onCheckedChange = {
                            if (it) {
                                confirmAutoDelete = true
                            } else {
                                autoDelete = false
                                sp.edit().putBoolean(ZombieCheckSettings.KEY_AUTO_DELETE, false).apply()
                            }
                        }
                    )
                    InsetDivider()
                    SwitchRow(
                        checked = clearRecord,
                        title = "同时清理聊天记录",
                        summary = "同时用于自动删除和批量删除",
                        onCheckedChange = {
                            clearRecord = it
                            sp.edit().putBoolean(ZombieCheckSettings.KEY_CLEAR_RECORD, it).apply()
                        }
                    )
                    InsetDivider()
                    NumberInputRow("删除间隔", "自动删除和批量删除的等待秒数，0-300", deleteDelay) {
                        deleteDelay = it
                        saveInt(ZombieCheckSettings.KEY_DELETE_DELAY_SECONDS, it, 0..300)
                    }
                    InsetDivider()
                    ActionRow(
                        "批量删除好友",
                        when {
                            snapshot.deleting -> "正在处理 ${snapshot.deleteCompletedCount}/${snapshot.deleteTotalCount}"
                            snapshot.deadCount > 0 -> "可按标签筛选，默认选中 ${snapshot.deadCount} 位异常好友"
                            else -> "从好友或微信标签中多选后删除"
                        }
                    ) {
                        if (snapshot.running || snapshot.deleting) {
                            Toast.makeText(context, "请先停止当前任务", Toast.LENGTH_SHORT).show()
                        } else {
                            onPickDelete()
                        }
                    }
                    InsetDivider()
                    SwitchRow(
                        checked = keepAwake,
                        title = "检测期间保持 CPU 运行",
                        summary = "使用 WakeLock，长时间检测会增加耗电",
                        onCheckedChange = {
                            keepAwake = it
                            sp.edit().putBoolean(ZombieCheckSettings.KEY_KEEP_AWAKE, it).apply()
                        }
                    )
                }
            }

            item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "请求参数") }
            item {
                SettingsCard {
                    NumberInputRow("最小间隔", "单位秒，0-60", minDelay) {
                        minDelay = it
                        saveInt(ZombieCheckSettings.KEY_MIN_DELAY_SECONDS, it, 0..60)
                    }
                    InsetDivider()
                    NumberInputRow("最大间隔", "单位秒，0-120", maxDelay) {
                        maxDelay = it
                        saveInt(ZombieCheckSettings.KEY_MAX_DELAY_SECONDS, it, 0..120)
                    }
                    InsetDivider()
                    NumberInputRow("请求超时", "单位秒，5-60", timeout) {
                        timeout = it
                        saveInt(ZombieCheckSettings.KEY_TIMEOUT_SECONDS, it, 5..60)
                    }
                    InsetDivider()
                    NumberInputRow("超时重试", "每位好友最多重试 0-5 次", retries) {
                        retries = it
                        saveInt(ZombieCheckSettings.KEY_MAX_RETRIES, it, 0..5)
                    }
                }
            }

            item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "异常结果 · ${notableResults.size}") }
            item {
                SettingsCard {
                    if (notableResults.isEmpty()) {
                        InfoRow("暂无异常结果", "正常好友不会逐项显示")
                    } else {
                        notableResults.take(50).forEachIndexed { index, result ->
                            if (index > 0) InsetDivider()
                            val resultType = if (result.type == ZombieCheckResultType.DEAD) "好友关系异常" else "检测失败"
                            val resultSummary = if (result.message.isBlank()) resultType else "$resultType · ${result.message}"
                            ZombieCheckResultRow(
                                result.name,
                                resultSummary
                            )
                        }
                    }
                }
            }

            if (snapshot.logs.isNotEmpty()) {
                item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "运行日志") }
                item {
                    SettingsCard {
                        snapshot.logs.takeLast(12).asReversed().forEachIndexed { index, log ->
                            if (index > 0) InsetDivider()
                            InfoRow("记录", log)
                        }
                    }
                }
            }
        }
    }

    if (confirmAutoDelete) {
        WindowDialog(
            show = true,
            title = "启用自动删除",
            onDismissRequest = { confirmAutoDelete = false },
            content = {
                Column {
                    Text(
                        text = "检测依据来自微信支付接口返回文案。支付风控或服务端文案变化可能产生检测失败，请先小范围验证结果。",
                        color = MiuixTheme.colorScheme.onSurface,
                        fontSize = 14.sp
                    )
                    TextButton(
                        text = "确认启用",
                        onClick = {
                            autoDelete = true
                            sp.edit().putBoolean(ZombieCheckSettings.KEY_AUTO_DELETE, true).apply()
                            confirmAutoDelete = false
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                    TextButton(
                        text = "取消",
                        onClick = { confirmAutoDelete = false },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        )
    }

    if (pendingDeleteTargets.isNotEmpty()) {
        WindowDialog(
            show = true,
            title = "确认批量删除",
            onDismissRequest = onDismissDelete,
            content = {
                Column {
                    Text(
                        text = buildString {
                            append("将删除已选的 ${pendingDeleteTargets.size} 位好友")
                            if (clearRecord) append("，并同时清理聊天记录")
                            append("。此操作不可撤销。")
                            val names = pendingDeleteTargets.take(6).joinToString("、") { it.name }
                            if (names.isNotBlank()) append("\n\n$names")
                            if (pendingDeleteTargets.size > 6) append(" 等")
                        },
                        color = MiuixTheme.colorScheme.onSurface,
                        fontSize = 14.sp
                    )
                    TextButton(
                        text = "确认删除",
                        onClick = {
                            val result = onConfirmDelete(
                                clearRecord,
                                deleteDelay.toIntOrNull()?.coerceIn(0, 300)
                                    ?: ZombieCheckSettings.DEFAULT_DELETE_DELAY_SECONDS
                            )
                            toastResult(result)
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                    TextButton(
                        text = "取消",
                        onClick = onDismissDelete,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        )
    }
}
