package h.Hchat.ui.miuix

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import h.Hchat.hooks.items.script.ScriptPluginManager
import h.Hchat.hooks.items.script.ScriptPluginRuntime
import h.Hchat.hooks.items.script.ScriptPluginSettings
import h.Hchat.hooks.items.script.market.PluginMarketPlugin
import h.Hchat.hooks.items.script.market.PluginMarketRepository
import h.Hchat.hooks.items.script.market.PluginMarketSettings
import h.Hchat.preferences.HchatStorage
import h.Hchat.preferences.TermsGate
import h.Hchat.ui.FeatureSettingsProvider
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashSet
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text as M3Text
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.window.Dialog
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import java.time.format.DateTimeFormatter
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import androidx.compose.ui.focus.FocusRequester
import java.time.Instant
import androidx.compose.ui.platform.LocalContext
import java.time.LocalDateTime
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import java.time.OffsetDateTime
import h.Hchat.hooks.items.script.market.PluginMarketComment
import h.Hchat.hooks.items.script.market.PluginMarketException
import h.Hchat.hooks.items.script.market.PluginMarketFile
import h.Hchat.hooks.items.script.market.PluginMarketHistoryVersion
import h.Hchat.hooks.items.script.market.PluginMarketInstaller
import h.Hchat.hooks.items.script.market.PluginMarketNotification
import h.Hchat.hooks.items.script.market.PluginMarketReviewStatus
import h.Hchat.hooks.items.script.market.PluginMarketUserIdentity
import top.yukonga.miuix.kmp.basic.PullToRefresh
import androidx.compose.ui.text.style.TextAlign
import h.Hchat.hooks.api.core.WeChatApis
import top.yukonga.miuix.kmp.menu.WindowDropdownMenu
import java.time.ZoneId
import java.time.ZoneOffset
import android.provider.OpenableColumns
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.RowScope
import top.yukonga.miuix.kmp.basic.Card


private const val PRESS_RELEASE_DELAY_MS = 110L
private const val RINGTONE_SYSTEM_REQUEST_CODE = 0x48435254
private const val RINGTONE_FILE_REQUEST_CODE = 0x48435255
private const val REDPACKET_REPLY_FILE_REQUEST_CODE = 0x48435256
private const val AUTO_REPLY_FILE_REQUEST_CODE = 0x48435257
private const val CONFIG_EXPORT_REQUEST_CODE = 0x48435258
private const val CONFIG_IMPORT_REQUEST_CODE = 0x48435259
private const val SCHEDULED_TASK_FILE_REQUEST_CODE = 0x4843525A
private const val AUDIO_TRANSFORM_INPUT_REQUEST_CODE = 0x4843525B
private const val AUDIO_TRANSFORM_OUTPUT_REQUEST_CODE = 0x4843525C
private const val FAKE_LOCATION_WECHAT_PICKER_REQUEST_CODE = 0x4843525D
private const val SCRIPT_AGENT_ATTACHMENT_REQUEST_CODE = 0x4843525E
private const val PLUGIN_MARKET_EXTRA_FILE_REQUEST_CODE = 0x4843525F
private const val SCRIPT_PLUGIN_EXPORT_REQUEST_CODE = 0x48435260
private const val SCRIPT_PLUGIN_IMPORT_REQUEST_CODE = 0x48435261
private const val MARKDOWN_LINK_TAG = "md_link"
private val MARKDOWN_LINK_REGEX = Regex("""\[([^\]]+)]\(([^)\s]+)\)""")
private val NAVIGATION_BUTTON_MIN_INSET = 24.dp
private val NAVIGATION_BUTTON_EXTRA_GAP = 8.dp

private object NavIcons {
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
    val Visibility: ImageVector = navIcon(
        name = "Rounded.Visibility",
        path = "M12,4.5C7,4.5 2.73,7.61 1,12c1.73,4.39 6,7.5 11,7.5s9.27,-3.11 11,-7.5c-1.73,-4.39 -6,-7.5 -11,-7.5zM12,17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5zM12,9c-1.66,0 -3,1.34 -3,3s1.34,3 3,3 3,-1.34 3,-3 -1.34,-3 -3,-3z"
    )
    val VisibilityOff: ImageVector = navIcon(
        name = "Rounded.VisibilityOff",
        path = "M12,7c2.76,0 5,2.24 5,5 0,0.65 -0.13,1.26 -0.36,1.83l2.92,2.92c1.51,-1.26 2.7,-2.89 3.43,-4.75 -1.73,-4.39 -6,-7.5 -11,-7.5 -1.4,0 -2.74,0.25 -3.98,0.7l2.16,2.16C10.74,7.13 11.35,7 12,7zM2,4.27l2.28,2.28 0.46,0.46C3.08,8.3 1.78,10.02 1,12c1.73,4.39 6,7.5 11,7.5 1.55,0 3.03,-0.3 4.38,-0.84l0.42,0.42L19.73,22 21,20.73 3.27,3 2,4.27zM7.53,9.8l1.55,1.55c-0.05,0.21 -0.08,0.43 -0.08,0.65 0,1.66 1.34,3 3,3 0.22,0 0.44,-0.03 0.65,-0.08l1.55,1.55c-0.67,0.33 -1.41,0.53 -2.2,0.53 -2.76,0 -5,-2.24 -5,-5 0,-0.79 0.2,-1.53 0.53,-2.2zM11.84,9.02l3.15,3.15 0.02,-0.16c0,-1.66 -1.34,-3 -3,-3l-0.17,0.01z"
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

private class SettingsBackHandlerRegistry {
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

private val LocalSettingsBackHandlerRegistry = staticCompositionLocalOf<SettingsBackHandlerRegistry?> { null }

@Composable
private fun RegisterSettingsBackHandler(onBack: (() -> Unit)?) {
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

private fun Intent.preferSystemDocumentsUi(context: Context): Intent {
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

/** Compose 的 LocalContext 常被 ContextThemeWrapper/ContextWrapper 包裹，这里向上解包到真正承载它的 Activity。 */
private fun Context.findActivity(): Activity? {
    var current: Context? = this
    var depth = 0
    while (current != null && depth < 10) {
        if (current is Activity) return current
        current = (current as? ContextWrapper)?.baseContext
        depth++
    }
    return null
}

private object ScriptPluginDocumentBridge {
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

@Composable
private fun Modifier.responsiveTap(
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
private fun rememberPressFeedbackColor(pressed: Boolean): Color {
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

private data class FeatureGroupEntry(
    val title: String,
    val providers: List<FeatureSettingsProvider>
)

private data class FeatureSearchResult(
    val provider: FeatureSettingsProvider,
    val group: FeatureGroupEntry?,
    val summary: String
)

private data class TemplateVariable(
    val token: String,
    val label: String
)

private sealed class DetailPage {
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

@Composable
private fun ClickHintTag() {
    Text(
        text = "单击",
        color = MiuixTheme.colorScheme.primary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        modifier = Modifier
            .defaultMinSize(minWidth = 40.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

private object ScriptPluginSettingsMiuixContent {

@Composable
fun ScriptPluginSettingsContent(
    context: Context,
    onOpenReadme: (ScriptPluginRuntime.ScriptPlugin) -> Unit,
    onOpenMarket: () -> Unit,
    onOpenManager: () -> Unit,
    onOpenDiagnostics: () -> Unit = {},
    onOpenFaq: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val sp = remember { HchatStorage.preferences(context, ScriptPluginSettings.PREFS_NAME) }
    val pluginRootPath = remember(context) { ScriptPluginRuntime.scriptDir(context).absolutePath }
    var pluginListVersion by remember { mutableStateOf(0) }
    val managedPlugins = remember(pluginListVersion) { ScriptPluginManager.listForDisplay(context) }
    val plugins = remember(managedPlugins) { managedPlugins.map { it.plugin } }
    val pinnedIds = remember(managedPlugins) {
        managedPlugins.filter { it.pinned }.mapTo(LinkedHashSet()) { it.plugin.id }
    }
    var globalEnabled by remember {
        mutableStateOf(sp.getBoolean(ScriptPluginSettings.KEY_ENABLE, ScriptPluginSettings.DEFAULT_ENABLE))
    }
    var pluginEnabledStates by remember(plugins) {
        mutableStateOf(plugins.associate { it.id to ScriptPluginRuntime.isPluginEnabled(context, it.id) })
    }
    var showPluginRootDialog by remember { mutableStateOf(false) }
    var actionPlugin by remember { mutableStateOf<ScriptPluginRuntime.ScriptPlugin?>(null) }
    var renamePlugin by remember { mutableStateOf<ScriptPluginRuntime.ScriptPlugin?>(null) }
    var deletePlugin by remember { mutableStateOf<ScriptPluginRuntime.ScriptPlugin?>(null) }
    DisposableEffect(context) {
        val subscription = ScriptPluginRuntime.subscribePluginCatalog(context) {
            Handler(Looper.getMainLooper()).post {
                pluginEnabledStates = ScriptPluginRuntime.listPlugins(context)
                    .associate { it.id to ScriptPluginRuntime.isPluginEnabled(context, it.id) }
                globalEnabled = sp.getBoolean(
                    ScriptPluginSettings.KEY_ENABLE,
                    ScriptPluginSettings.DEFAULT_ENABLE
                )
                pluginListVersion++
            }
        }
        onDispose { subscription.unsubscribe() }
    }

    Column {
        // 脚本功能诊断入口：脚本开关开不起来/脚本不工作时，从这里直接看状态与原因。
        val apiReady = runCatching { WeChatApis.database() }.getOrNull() != null
        val readinessDetail = runCatching { ScriptPluginRuntime.scriptApiReadinessDetail() }
            .getOrNull().orEmpty()
        SettingsCard(modifier = Modifier.padding(top = 10.dp)) {
            ActionRow(
                "脚本功能诊断",
                if (apiReady) "运行时正常，可查看状态与日志" else "未就绪：${readinessDetail.ifBlank { "微信 API 未解析" }}",
            ) { onOpenDiagnostics() }
        }
        SettingsCard(modifier = Modifier.padding(top = 10.dp)) {
            ActionRow(
                "脚本常见问题",
                "开关打不开 / 插件启用失败 / 改脚本要不要重启，看这里",
            ) { onOpenFaq() }
        }
        SettingsCard(modifier = Modifier.padding(top = 10.dp)) {
            PathSwitchRow(
                globalEnabled,
                "插件总开关",
                "启动时自动加载已启用插件\n相关说明:\n请确认插件安全再进行加载,\n否则造成的后果需自行承担。",
                onInfoClick = { showPluginRootDialog = true },
            ) { next ->
                val old = globalEnabled
                globalEnabled = next
                Thread({
                    val result = ScriptPluginRuntime.setGlobalEnabled(context, next)
                    Handler(Looper.getMainLooper()).post {
                        if (result.isFailure) {
                            globalEnabled = old
                            Toast.makeText(
                                context,
                                "切换失败: ${result.exceptionOrNull()?.message}",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            pluginListVersion++
                        }
                    }
                }, "WCX-Script-Global").start()
            }
        }
        if (showPluginRootDialog) {
            ScriptPluginPathDialog(
                context = context,
                title = "插件目录",
                path = pluginRootPath,
                onClose = { showPluginRootDialog = false }
            )
        }
        SettingsCard(modifier = Modifier.padding(top = 10.dp)) {
            ActionRow("在线插件", "浏览、安装或上传社区脚本插件") {
                onOpenMarket()
            }
        }
        SettingsCard(modifier = Modifier.padding(top = 10.dp)) {
            ActionRow("本地插件管理", "排序、置顶、导入、导出或批量管理插件") {
                onOpenManager()
            }
        }
        SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "本地插件(${plugins.size})")
        SettingsCard {
            if (plugins.isEmpty()) {
                Text(
                    text = "暂无插件",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
                )
            } else {
                plugins.forEachIndexed { index, plugin ->
                    val checked = pluginEnabledStates[plugin.id]
                        ?: ScriptPluginRuntime.isPluginEnabled(context, plugin.id)
                    val summary = buildString {
                        append(plugin.dir.name)
                        append("\n")
                        append("作者: ")
                        append(plugin.author.ifBlank { "未知" })
                        append(" | 更新于: ")
                        append(plugin.updateTime.ifBlank { "未知" })
                    }
                    val title = buildString {
                        append(plugin.displayName ?: "未知")
                        append("(")
                        append(plugin.version.ifBlank { "未知" })
                        append(")")
                    }
                    ScriptPluginRow(
                        checked = checked,
                        title = title,
                        summary = summary,
                        showSettings = ScriptPluginRuntime.canOpenSettings(plugin),
                        onOpenReadme = { onOpenReadme(plugin) },
                        onOpenSettings = {
                            // 开关是开的、但本次启动未加载成功时，plugins 里没有它 → 先补加载再打开，
                            // 避免「开关明明开着却提示插件未开启」。放到后台线程，避免 eval 阻塞 UI。
                            Thread({
                                var result = ScriptPluginRuntime.callOpenSettings(plugin.id)
                                if (result.isFailure && ScriptPluginRuntime.isPluginEnabled(context, plugin.id)) {
                                    runCatching { ScriptPluginRuntime.reloadPlugin(context, plugin.id) }
                                    result = ScriptPluginRuntime.callOpenSettings(plugin.id)
                                }
                                val finalResult = result
                                val enabled = ScriptPluginRuntime.isPluginEnabled(context, plugin.id)
                                Handler(Looper.getMainLooper()).post {
                                    if (finalResult.isFailure) {
                                        val msg = finalResult.exceptionOrNull()?.message.orEmpty()
                                        val text = if (msg.contains("插件未开启") && enabled) {
                                            "插件尚未加载成功（微信数据库/预热可能未就绪），请稍后重试，或到「脚本功能诊断」点一键修复"
                                        } else msg.ifBlank { "打开设置失败" }
                                        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
                                    }
                                }
                            }, "WCX-Script-OpenSettings").start()
                        },
                        onOpenManager = { actionPlugin = plugin },
                        onCheckedChange = { next ->
                            val oldMap = pluginEnabledStates
                            pluginEnabledStates = pluginEnabledStates + (plugin.id to next)
                            Thread({
                                val result = ScriptPluginRuntime.setPluginEnabled(context, plugin.id, next)
                                Handler(Looper.getMainLooper()).post {
                                    if (result.isFailure) {
                                        pluginEnabledStates = oldMap
                                        val reason = result.exceptionOrNull()
                                            ?.let { "${it.javaClass.simpleName}: ${it.message.orEmpty()}" }
                                            .orEmpty()
                                            .take(200)
                                        Toast.makeText(
                                            context,
                                            "加载[${plugin.displayName ?: "未知"}]失败: $reason",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            }, "WCX-Script-${plugin.id}").start()
                        }
                    )
                    if (index != plugins.lastIndex) InsetDivider()
                }
            }
        }
        actionPlugin?.let { plugin ->
            ScriptPluginActionDialog(
                plugin = plugin,
                pinned = plugin.id in pinnedIds,
                onDismiss = { actionPlugin = null },
                onPinChanged = { pinned ->
                    actionPlugin = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            ScriptPluginManager.setPinned(context, listOf(plugin.id), pinned)
                        }
                        result.fold(
                            onSuccess = {
                                pluginListVersion++
                                Toast.makeText(
                                    context,
                                    if (pinned) "已置顶" else "已取消置顶",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            onFailure = { showScriptPluginManagerError(context, it) }
                        )
                    }
                },
                onRename = {
                    actionPlugin = null
                    showAfterDialogDismiss(context) { renamePlugin = plugin }
                },
                onExport = {
                    actionPlugin = null
                    showAfterDialogDismiss(context) {
                        launchScriptPluginExport(
                            context = context,
                            plugins = listOf(plugin)
                        )
                    }
                },
                onDelete = {
                    actionPlugin = null
                    showAfterDialogDismiss(context) { deletePlugin = plugin }
                }
            )
        }
        renamePlugin?.let { plugin ->
            ScriptPluginRenameDialog(
                plugin = plugin,
                onDismiss = { renamePlugin = null },
                onConfirm = { name ->
                    renamePlugin = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            ScriptPluginManager.renamePlugin(context, plugin.id, name)
                        }
                        result.fold(
                            onSuccess = {
                                pluginListVersion++
                                Toast.makeText(context, "已重命名", Toast.LENGTH_SHORT).show()
                            },
                            onFailure = { showScriptPluginManagerError(context, it) }
                        )
                    }
                }
            )
        }
        deletePlugin?.let { plugin ->
            ScriptPluginDeleteDialog(
                pluginNames = listOf(plugin.displayName ?: plugin.name.ifBlank { plugin.id }),
                onDismiss = { deletePlugin = null },
                onConfirm = {
                    deletePlugin = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            ScriptPluginManager.deletePlugin(context, plugin.id)
                        }
                        result.fold(
                            onSuccess = {
                                pluginListVersion++
                                Toast.makeText(context, "插件已删除", Toast.LENGTH_SHORT).show()
                            },
                            onFailure = { showScriptPluginManagerError(context, it) }
                        )
                    }
                }
            )
        }
    }
}

@Composable
fun ScriptPluginManagerPage(
    context: Context,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()
    var query by rememberSaveable { mutableStateOf("") }
    var refreshVersion by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var managedPlugins by remember {
        mutableStateOf<List<ScriptPluginManager.ManagedPlugin>>(emptyList())
    }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var draggedPluginId by remember { mutableStateOf<String?>(null) }
    var dragOrderChanged by remember { mutableStateOf(false) }
    var actionPlugin by remember { mutableStateOf<ScriptPluginManager.ManagedPlugin?>(null) }
    var renamePlugin by remember { mutableStateOf<ScriptPluginRuntime.ScriptPlugin?>(null) }
    var deletePlugins by remember { mutableStateOf<List<ScriptPluginRuntime.ScriptPlugin>>(emptyList()) }
    var importInspection by remember { mutableStateOf<ScriptPluginManager.ImportInspection?>(null) }
    var importOverwriteIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var busyText by remember { mutableStateOf("") }
    val pageActive = remember { AtomicBoolean(true) }
    val activeImportSessionId = remember { AtomicReference<String?>(null) }

    LaunchedEffect(refreshVersion) {
        loading = true
        val result = withContext(Dispatchers.IO) {
            runCatching {
                ScriptPluginManager.cleanupStaleDisplayIds(context).getOrThrow()
                ScriptPluginManager.listForDisplay(context)
            }
        }
        result.fold(
            onSuccess = { plugins ->
                managedPlugins = plugins
                selectedIds = selectedIds.intersect(plugins.mapTo(LinkedHashSet()) { it.plugin.id })
            },
            onFailure = { showScriptPluginManagerError(context, it) }
        )
        loading = false
    }
    DisposableEffect(context) {
        val subscription = ScriptPluginRuntime.subscribePluginCatalog(context) {
            Handler(Looper.getMainLooper()).post { refreshVersion++ }
        }
        onDispose { subscription.unsubscribe() }
    }
    DisposableEffect(Unit) {
        pageActive.set(true)
        onDispose {
            pageActive.set(false)
            activeImportSessionId.getAndSet(null)?.let { sessionId ->
                Thread({ ScriptPluginManager.discardImport(context, sessionId) }, "WCX-Plugin-Import-Cleanup").start()
            }
        }
    }

    val normalizedQuery = query.trim().lowercase(Locale.ROOT)
    val visiblePlugins = managedPlugins.filter { item ->
        val plugin = item.plugin
        normalizedQuery.isEmpty() ||
            plugin.id.lowercase(Locale.ROOT).contains(normalizedQuery) ||
            plugin.name.lowercase(Locale.ROOT).contains(normalizedQuery) ||
            plugin.author.lowercase(Locale.ROOT).contains(normalizedQuery) ||
            plugin.version.lowercase(Locale.ROOT).contains(normalizedQuery)
    }
    val pinnedPlugins = visiblePlugins.filter { it.pinned }
    val normalPlugins = visiblePlugins.filterNot { it.pinned }
    val visibleIds = visiblePlugins.mapTo(LinkedHashSet()) { it.plugin.id }
    val allVisibleSelected = visibleIds.isNotEmpty() && visibleIds.all { it in selectedIds }

    fun refresh() {
        refreshVersion++
    }

    fun setPinned(ids: Set<String>, pinned: Boolean) {
        if (ids.isEmpty() || busyText.isNotEmpty()) return
        busyText = if (pinned) "正在置顶" else "正在取消置顶"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                ScriptPluginManager.setPinned(context, ids, pinned)
            }
            busyText = ""
            result.fold(
                onSuccess = {
                    refresh()
                    Toast.makeText(
                        context,
                        if (pinned) "已置顶 ${ids.size} 个插件" else "已取消置顶 ${ids.size} 个插件",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onFailure = { showScriptPluginManagerError(context, it) }
            )
        }
    }

    fun commitOrder() {
        val ids = managedPlugins.map { it.plugin.id }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                ScriptPluginManager.saveDisplayOrder(context, ids)
            }
            result.onFailure { showScriptPluginManagerError(context, it) }
        }
    }

    fun movePlugin(item: ScriptPluginManager.ManagedPlugin, delta: Int): Boolean {
        if (query.isNotBlank()) return false
        val index = managedPlugins.indexOfFirst { it.plugin.id == item.plugin.id }
        val targetIndex = index + delta
        if (index < 0 || targetIndex !in managedPlugins.indices) return false
        if (managedPlugins[targetIndex].pinned != item.pinned) return false
        val next = managedPlugins.toMutableList()
        val moved = next.removeAt(index)
        next.add(targetIndex, moved)
        managedPlugins = next
        return true
    }

    fun launchImportPicker() {
        val activity = context.findActivity()
        if (activity == null) {
            Toast.makeText(context, "无法打开文件选择器", Toast.LENGTH_SHORT).show()
            return
        }
        ScriptPluginDocumentBridge.launchImport(activity) { uri ->
            if (!pageActive.get()) return@launchImport
            busyText = "正在检查导入包"
            Thread({
                val result = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        ScriptPluginManager.inspectImport(context, input).getOrThrow()
                    } ?: error("无法读取所选文件")
                }
                if (!pageActive.get()) {
                    result.getOrNull()?.let { inspection ->
                        ScriptPluginManager.discardImport(context, inspection.sessionId)
                    }
                    return@Thread
                }
                Handler(Looper.getMainLooper()).post {
                    if (!pageActive.get()) {
                        result.getOrNull()?.let { inspection ->
                            Thread(
                                { ScriptPluginManager.discardImport(context, inspection.sessionId) },
                                "WCX-Plugin-Import-Late-Cleanup"
                            ).start()
                        }
                        return@post
                    }
                    busyText = ""
                    result.fold(
                        onSuccess = { inspection ->
                            activeImportSessionId.set(inspection.sessionId)
                            importInspection = inspection
                            importOverwriteIds = emptySet()
                        },
                        onFailure = { showScriptPluginManagerError(context, it) }
                    )
                }
            }, "WCX-Plugin-Import-Inspect").start()
        }
    }

    PageScaffold(
        title = "本地插件管理",
        largeTitle = "本地插件管理",
        scrollBehavior = scrollBehavior,
        onBack = onBack,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item {
                SearchBarSurface(
                    query = query,
                    placeholder = "搜索本地插件",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    onQueryChange = { query = it }
                )
            }
            item { SmallTitle(modifier = Modifier.padding(top = 6.dp), text = "管理") }
            item {
                SettingsCard {
                    ScriptPluginManagerCommandRow(
                        icon = NavIcons.Import,
                        title = "导入插件",
                        summary = "从 ZIP 文件导入，导入后默认关闭",
                        enabled = busyText.isEmpty(),
                        onClick = ::launchImportPicker
                    )
                    InsetDivider()
                    ScriptPluginSelectionToolbar(
                        selectedCount = selectedIds.size,
                        allVisibleSelected = allVisibleSelected,
                        enabled = visibleIds.isNotEmpty() && busyText.isEmpty(),
                        onSelectAll = {
                            selectedIds = if (allVisibleSelected) {
                                selectedIds - visibleIds
                            } else {
                                selectedIds + visibleIds
                            }
                        },
                        onInvert = {
                            selectedIds = (selectedIds - visibleIds) + (visibleIds - selectedIds)
                        }
                    )
                    if (selectedIds.isNotEmpty()) {
                        InsetDivider()
                        ScriptPluginBatchActions(
                            enabled = busyText.isEmpty(),
                            onPin = { setPinned(selectedIds, true) },
                            onUnpin = { setPinned(selectedIds, false) },
                            onExport = {
                                val selected = managedPlugins.filter { it.plugin.id in selectedIds }.map { it.plugin }
                                launchScriptPluginExport(context, selected)
                            },
                            onDelete = {
                                deletePlugins = managedPlugins
                                    .filter { it.plugin.id in selectedIds }
                                    .map { it.plugin }
                            }
                        )
                    }
                }
            }
            if (busyText.isNotEmpty()) {
                item {
                    Text(
                        text = busyText,
                        color = MiuixTheme.colorScheme.primary,
                        fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)
                    )
                }
            }
            when {
                loading -> item { EmptyText("正在加载插件") }
                managedPlugins.isEmpty() -> item { EmptyText("暂无本地插件") }
                visiblePlugins.isEmpty() -> item { EmptyText("没有匹配的插件") }
                else -> {
                    if (pinnedPlugins.isNotEmpty()) {
                        item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "已置顶(${pinnedPlugins.size})") }
                        item {
                            SettingsCard {
                                pinnedPlugins.forEachIndexed { index, item ->
                                    key(item.plugin.id) {
                                        ScriptPluginManagerRow(
                                            modifier = Modifier,
                                            item = item,
                                            selected = item.plugin.id in selectedIds,
                                            dragEnabled = query.isBlank() && busyText.isEmpty(),
                                            onSelectedChange = { selected ->
                                                selectedIds = if (selected) {
                                                    selectedIds + item.plugin.id
                                                } else {
                                                    selectedIds - item.plugin.id
                                                }
                                            },
                                            onOpenActions = { actionPlugin = item },
                                            onDragMove = { delta -> movePlugin(item, delta) },
                                            onDragStateChange = { dragging ->
                                                if (dragging) {
                                                    draggedPluginId = item.plugin.id
                                                    dragOrderChanged = false
                                                } else if (draggedPluginId == item.plugin.id) {
                                                    draggedPluginId = null
                                                    if (dragOrderChanged) commitOrder()
                                                }
                                            },
                                            onOrderChanged = { dragOrderChanged = true }
                                        )
                                        if (index != pinnedPlugins.lastIndex) InsetDivider()
                                    }
                                }
                            }
                        }
                    }
                    if (normalPlugins.isNotEmpty()) {
                        item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "其他插件(${normalPlugins.size})") }
                        item {
                            SettingsCard {
                                normalPlugins.forEachIndexed { index, item ->
                                    key(item.plugin.id) {
                                        ScriptPluginManagerRow(
                                            modifier = Modifier,
                                            item = item,
                                            selected = item.plugin.id in selectedIds,
                                            dragEnabled = query.isBlank() && busyText.isEmpty(),
                                            onSelectedChange = { selected ->
                                                selectedIds = if (selected) {
                                                    selectedIds + item.plugin.id
                                                } else {
                                                    selectedIds - item.plugin.id
                                                }
                                            },
                                            onOpenActions = { actionPlugin = item },
                                            onDragMove = { delta -> movePlugin(item, delta) },
                                            onDragStateChange = { dragging ->
                                                if (dragging) {
                                                    draggedPluginId = item.plugin.id
                                                    dragOrderChanged = false
                                                } else if (draggedPluginId == item.plugin.id) {
                                                    draggedPluginId = null
                                                    if (dragOrderChanged) commitOrder()
                                                }
                                            },
                                            onOrderChanged = { dragOrderChanged = true }
                                        )
                                        if (index != normalPlugins.lastIndex) InsetDivider()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    actionPlugin?.let { item ->
        ScriptPluginActionDialog(
            plugin = item.plugin,
            pinned = item.pinned,
            onDismiss = { actionPlugin = null },
            onPinChanged = { pinned ->
                actionPlugin = null
                setPinned(setOf(item.plugin.id), pinned)
            },
            onRename = {
                actionPlugin = null
                showAfterDialogDismiss(context) { renamePlugin = item.plugin }
            },
            onExport = {
                actionPlugin = null
                showAfterDialogDismiss(context) {
                    launchScriptPluginExport(context, listOf(item.plugin))
                }
            },
            onDelete = {
                actionPlugin = null
                showAfterDialogDismiss(context) { deletePlugins = listOf(item.plugin) }
            }
        )
    }
    renamePlugin?.let { plugin ->
        ScriptPluginRenameDialog(
            plugin = plugin,
            onDismiss = { renamePlugin = null },
            onConfirm = { name ->
                renamePlugin = null
                busyText = "正在重命名"
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        ScriptPluginManager.renamePlugin(context, plugin.id, name)
                    }
                    busyText = ""
                    result.fold(
                        onSuccess = {
                            refresh()
                            Toast.makeText(context, "已重命名", Toast.LENGTH_SHORT).show()
                        },
                        onFailure = { showScriptPluginManagerError(context, it) }
                    )
                }
            }
        )
    }
    if (deletePlugins.isNotEmpty()) {
        val pendingDelete = deletePlugins
        ScriptPluginDeleteDialog(
            pluginNames = pendingDelete.map { it.displayName ?: it.name.ifBlank { it.id } },
            onDismiss = { deletePlugins = emptyList() },
            onConfirm = {
                deletePlugins = emptyList()
                busyText = "正在删除插件"
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        ScriptPluginManager.deletePlugins(context, pendingDelete.map { it.id })
                    }
                    busyText = ""
                    result.fold(
                        onSuccess = {
                            selectedIds = selectedIds - pendingDelete.mapTo(LinkedHashSet()) { it.id }
                            refresh()
                            Toast.makeText(context, "已删除 ${pendingDelete.size} 个插件", Toast.LENGTH_SHORT).show()
                        },
                        onFailure = { showScriptPluginManagerError(context, it) }
                    )
                }
            }
        )
    }
    importInspection?.let { inspection ->
        ScriptPluginImportDialog(
            inspection = inspection,
            overwriteIds = importOverwriteIds,
            applying = busyText == "正在导入插件",
            onOverwriteChanged = { pluginId, overwrite ->
                importOverwriteIds = if (overwrite) {
                    importOverwriteIds + pluginId
                } else {
                    importOverwriteIds - pluginId
                }
            },
            onDismiss = {
                activeImportSessionId.set(null)
                importInspection = null
                Thread(
                    { ScriptPluginManager.discardImport(context, inspection.sessionId) },
                    "WCX-Plugin-Import-Discard"
                ).start()
            },
            onConfirm = {
                busyText = "正在导入插件"
                scope.launch {
                    val actions = inspection.conflicts.associateWith { pluginId ->
                        if (pluginId in importOverwriteIds) {
                            ScriptPluginManager.ImportConflictAction.OVERWRITE
                        } else {
                            ScriptPluginManager.ImportConflictAction.SKIP
                        }
                    }
                    val result = withContext(Dispatchers.IO) {
                        ScriptPluginManager.applyImport(context, inspection.sessionId, actions)
                    }
                    busyText = ""
                    result.fold(
                        onSuccess = { imported ->
                            activeImportSessionId.set(null)
                            importInspection = null
                            importOverwriteIds = emptySet()
                            refresh()
                            Toast.makeText(
                                context,
                                "已导入 ${imported.importedPluginIds.size} 个插件，保持关闭状态",
                                Toast.LENGTH_LONG
                            ).show()
                        },
                        onFailure = { showScriptPluginManagerError(context, it) }
                    )
                }
            }
        )
    }
}

@Composable
private fun ScriptPluginSelectionToolbar(
    selectedCount: Int,
    allVisibleSelected: Boolean,
    enabled: Boolean,
    onSelectAll: () -> Unit,
    onInvert: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = if (selectedCount == 0) "未选择插件" else "已选择 $selectedCount 个",
            color = MiuixTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        TextButton(
            text = if (allVisibleSelected) "取消全选" else "全选",
            enabled = enabled,
            onClick = onSelectAll,
            colors = ButtonDefaults.textButtonColorsPrimary()
        )
        TextButton(
            text = "反选",
            enabled = enabled,
            onClick = onInvert,
            colors = ButtonDefaults.textButtonColorsPrimary()
        )
    }
}

@Composable
private fun ScriptPluginBatchActions(
    enabled: Boolean,
    onPin: () -> Unit,
    onUnpin: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ScriptPluginCompactAction("置顶", enabled, Modifier.weight(1f), onPin)
            ScriptPluginCompactAction("取消置顶", enabled, Modifier.weight(1f), onUnpin)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ScriptPluginCompactAction("导出", enabled, Modifier.weight(1f), onExport)
            ScriptPluginCompactAction("删除", enabled, Modifier.weight(1f), onDelete, destructive = true)
        }
    }
}

@Composable
private fun ScriptPluginCompactAction(
    text: String,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    destructive: Boolean = false
) {
    val color = when {
        !enabled -> MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.45f)
        destructive -> Color(0xFFD93025)
        else -> MiuixTheme.colorScheme.primary
    }
    Box(
        modifier = modifier.height(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.10f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ScriptPluginManagerCommandRow(
    icon: ImageVector,
    title: String,
    summary: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val color = if (enabled) MiuixTheme.colorScheme.onSurface else {
        MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.45f)
    }
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            imageVector = icon,
            contentDescription = null,
            colorFilter = ColorFilter.tint(color),
            modifier = Modifier.size(22.dp)
        )
        Column(modifier = Modifier.weight(1f).padding(start = 14.dp)) {
            Text(text = title, color = color, fontWeight = FontWeight.Medium)
            Text(text = summary, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ScriptPluginManagerRow(
    modifier: Modifier,
    item: ScriptPluginManager.ManagedPlugin,
    selected: Boolean,
    dragEnabled: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    onOpenActions: () -> Unit,
    onDragMove: (Int) -> Boolean,
    onDragStateChange: (Boolean) -> Unit,
    onOrderChanged: () -> Unit
) {
    val plugin = item.plugin
    val density = LocalDensity.current
    var itemHeightPx by remember(plugin.id) { mutableStateOf(0f) }
    var dragDistanceY by remember(plugin.id) { mutableStateOf(0f) }
    var dragging by remember(plugin.id) { mutableStateOf(false) }
    val currentOnDragMove by rememberUpdatedState(onDragMove)
    val currentOnDragStateChange by rememberUpdatedState(onDragStateChange)
    val currentOnOrderChanged by rememberUpdatedState(onOrderChanged)
    val visualDragY by animateFloatAsState(
        targetValue = dragDistanceY,
        animationSpec = tween(durationMillis = if (dragging) 0 else 160),
        label = "ScriptPluginDragOffset"
    )
    Row(
        modifier = modifier.zIndex(if (dragging) 1f else 0f)
            .graphicsLayer {
                translationY = visualDragY
                shadowElevation = if (dragging) with(density) { 7.dp.toPx() } else 0f
                shape = RoundedCornerShape(8.dp)
                clip = true
            }
            // 拖动中的行必须有不透明底色：否则它会透出下层行的文字，看起来像重影。
            .then(
                if (dragging) {
                    Modifier.background(MiuixTheme.colorScheme.surfaceContainer, RoundedCornerShape(8.dp))
                } else {
                    Modifier
                }
            )
            .fillMaxWidth()
            .onSizeChanged { itemHeightPx = it.height.toFloat() }
            .clickable { onSelectedChange(!selected) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SelectionMark(selected = selected, multiSelect = true)
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = plugin.displayName ?: plugin.name.ifBlank { plugin.id },
                    color = MiuixTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (item.pinned) {
                    Image(
                        imageVector = NavIcons.Pin,
                        contentDescription = "已置顶",
                        colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.primary),
                        modifier = Modifier.padding(start = 6.dp).size(16.dp)
                    )
                }
            }
            Text(
                text = buildString {
                    append(plugin.id)
                    append(" · ").append(plugin.author.ifBlank { "未知作者" })
                    append(" · ").append(plugin.version.ifBlank { "未知版本" })
                },
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Image(
            imageVector = NavIcons.More,
            contentDescription = "管理插件",
            colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceVariantSummary),
            modifier = Modifier.padding(start = 8.dp).size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable {
                    onOpenActions()
                }
                .padding(5.dp)
        )
        Image(
            imageVector = NavIcons.Drag,
            contentDescription = if (dragEnabled) "长按拖动排序" else "搜索时不可排序",
            colorFilter = ColorFilter.tint(
                MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = if (dragEnabled) 1f else 0.35f)
            ),
            modifier = Modifier.padding(start = 4.dp).size(32.dp)
                .pointerInput(plugin.id, dragEnabled, itemHeightPx) {
                    if (!dragEnabled) return@pointerInput
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            dragDistanceY = 0f
                            dragging = true
                            currentOnDragStateChange(true)
                        },
                        onDragEnd = {
                            dragging = false
                            dragDistanceY = 0f
                            currentOnDragStateChange(false)
                        },
                        onDragCancel = {
                            dragging = false
                            dragDistanceY = 0f
                            currentOnDragStateChange(false)
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragDistanceY += dragAmount.y
                            val step = itemHeightPx.takeIf { it > 0f } ?: with(density) { 62.dp.toPx() }
                            while (dragDistanceY <= -step * 0.5f) {
                                if (currentOnDragMove(-1)) {
                                    dragDistanceY += step
                                    currentOnOrderChanged()
                                } else {
                                    dragDistanceY = -step * 0.45f
                                    break
                                }
                            }
                            while (dragDistanceY >= step * 0.5f) {
                                if (currentOnDragMove(1)) {
                                    dragDistanceY -= step
                                    currentOnOrderChanged()
                                } else {
                                    dragDistanceY = step * 0.45f
                                    break
                                }
                            }
                        }
                    )
                }
                .padding(5.dp)
        )
    }
}

@Composable
private fun ScriptPluginActionDialog(
    plugin: ScriptPluginRuntime.ScriptPlugin,
    pinned: Boolean,
    onDismiss: () -> Unit,
    onPinChanged: (Boolean) -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    WindowDialog(
        show = true,
        title = plugin.displayName ?: plugin.name.ifBlank { plugin.id },
        onDismissRequest = onDismiss,
        content = {
            Column {
                ScriptPluginDialogActionRow(
                    icon = NavIcons.Pin,
                    text = if (pinned) "取消置顶" else "置顶",
                    onClick = { onPinChanged(!pinned) }
                )
                ScriptPluginDialogActionRow(NavIcons.Edit, "重命名", onClick = onRename)
                ScriptPluginDialogActionRow(NavIcons.Export, "导出", onClick = onExport)
                ScriptPluginDialogActionRow(NavIcons.Delete, "删除", destructive = true, onClick = onDelete)
            }
        }
    )
}

@Composable
private fun ScriptPluginDialogActionRow(
    icon: ImageVector,
    text: String,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    val color = if (destructive) Color(0xFFD93025) else MiuixTheme.colorScheme.onSurface
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (destructive) Color(0xFFD93025).copy(alpha = 0.08f)
                else MiuixTheme.colorScheme.secondaryVariant
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            imageVector = icon,
            contentDescription = null,
            colorFilter = ColorFilter.tint(color),
            modifier = Modifier.size(22.dp)
        )
        Text(text = text, color = color, fontSize = 15.sp, modifier = Modifier.padding(start = 18.dp))
    }
}

@Composable
private fun ScriptPluginRenameDialog(
    plugin: ScriptPluginRuntime.ScriptPlugin,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember(plugin.id) {
        mutableStateOf(plugin.displayName ?: plugin.name.ifBlank { plugin.id })
    }
    WindowDialog(
        show = true,
        title = "重命名插件",
        onDismissRequest = onDismiss,
        content = {
            Column {
                Text(
                    text = "仅修改展示名称，插件目录和 ID 保持不变",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 12.sp
                )
                BasicTextField(
                    value = name,
                    onValueChange = { name = it.take(100) },
                    singleLine = true,
                    textStyle = TextStyle(color = MiuixTheme.colorScheme.onSurface, fontSize = 15.sp),
                    cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MiuixTheme.colorScheme.secondaryVariant)
                        .padding(horizontal = 12.dp, vertical = 11.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TextButton(
                        text = "取消",
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                    TextButton(
                        text = "确定",
                        onClick = { onConfirm(name.trim()) },
                        enabled = name.trim().isNotEmpty(),
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        }
    )
}

@Composable
private fun ScriptPluginDeleteDialog(
    pluginNames: List<String>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    WindowDialog(
        show = true,
        title = if (pluginNames.size == 1) "删除插件" else "批量删除插件",
        onDismissRequest = onDismiss,
        content = {
            Column {
                Text(
                    text = if (pluginNames.size == 1) {
                        "确定删除“${pluginNames.first()}”吗？插件会先停止运行，删除后无法恢复。"
                    } else {
                        "确定删除已选的 ${pluginNames.size} 个插件吗？插件会先停止运行，删除后无法恢复。"
                    },
                    color = MiuixTheme.colorScheme.onSurface,
                    fontSize = 14.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TextButton(
                        text = "取消",
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                    TextButton(
                        text = "删除",
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        }
    )
}

@Composable
private fun ScriptPluginImportDialog(
    inspection: ScriptPluginManager.ImportInspection,
    overwriteIds: Set<String>,
    applying: Boolean,
    onOverwriteChanged: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    WindowDialog(
        show = true,
        title = "导入插件",
        onDismissRequest = { if (!applying) onDismiss() },
        content = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
                Text(
                    text = "共 ${inspection.plugins.size} 个插件。已有同 ID 插件默认跳过，可单独选择覆盖。",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 12.sp
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false).padding(top = 10.dp)
                ) {
                    items(inspection.plugins, key = { it.pluginId }) { plugin ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = !applying && plugin.conflict) {
                                    onOverwriteChanged(plugin.pluginId, plugin.pluginId !in overwriteIds)
                                }
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = plugin.name.ifBlank { plugin.pluginId },
                                    color = MiuixTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (plugin.conflict) {
                                        if (plugin.pluginId in overwriteIds) "覆盖已有插件" else "跳过已有插件"
                                    } else {
                                        "新增插件"
                                    },
                                    color = if (plugin.conflict && plugin.pluginId in overwriteIds) {
                                        Color(0xFFD93025)
                                    } else {
                                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    },
                                    fontSize = 12.sp
                                )
                            }
                            if (plugin.conflict) {
                                SelectionMark(
                                    selected = plugin.pluginId in overwriteIds,
                                    multiSelect = true
                                )
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TextButton(
                        text = "取消",
                        onClick = onDismiss,
                        enabled = !applying,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                    TextButton(
                        text = if (applying) "正在导入" else "导入",
                        onClick = onConfirm,
                        enabled = !applying,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        }
    )
}

private fun launchScriptPluginExport(
    context: Context,
    plugins: List<ScriptPluginRuntime.ScriptPlugin>
) {
    if (plugins.isEmpty()) {
        Toast.makeText(context, "未选择插件", Toast.LENGTH_SHORT).show()
        return
    }
    val activity = context.findActivity()
    if (activity == null) {
        Toast.makeText(context, "无法打开文件选择器", Toast.LENGTH_SHORT).show()
        return
    }
    val fileName = scriptPluginExportFileName(plugins)
    ScriptPluginDocumentBridge.launchExport(activity, fileName) { uri ->
        Thread({
            val result = runCatching {
                context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                    ScriptPluginManager.exportPlugins(context, plugins.map { it.id }, output).getOrThrow()
                } ?: error("无法写入所选文件")
            }
            if (result.isFailure) runCatching { context.contentResolver.delete(uri, null, null) }
            Handler(Looper.getMainLooper()).post {
                result.fold(
                    onSuccess = { exported ->
                        Toast.makeText(
                            context,
                            "已导出 ${exported.exportedPluginIds.size} 个插件",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onFailure = { showScriptPluginManagerError(context, it) }
                )
            }
        }, "WCX-Plugin-Export").start()
    }
}

private fun scriptPluginExportFileName(plugins: List<ScriptPluginRuntime.ScriptPlugin>): String {
    val base = if (plugins.size == 1) {
        plugins.first().displayName ?: plugins.first().name.ifBlank { plugins.first().id }
    } else {
        "WCX_脚本插件_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}"
    }
    val safe = base.removeSuffix(".zip")
        .replace(Regex("""[\\/:*?\"<>|]"""), "_")
        .trim()
        .ifBlank { "WCX_脚本插件" }
    return "$safe.zip"
}

private fun showAfterDialogDismiss(context: Context, action: () -> Unit) {
    val activity = context.findActivity()
    if (activity != null) {
        activity.window.decorView.postOnAnimation(action)
    } else {
        Handler(Looper.getMainLooper()).post(action)
    }
}

private fun showScriptPluginManagerError(context: Context, error: Throwable) {
    h.Hchat.utils.HLog.e("[WCX:ScriptManager] ${error.message ?: "操作失败"}", error)
    Toast.makeText(context, error.message ?: "操作失败", Toast.LENGTH_LONG).show()
}

@Composable
private fun PathSwitchRow(
    checked: Boolean,
    title: String,
    summary: String,
    onInfoClick: () -> Unit,
    onCheckedChange: (Boolean) -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    val pressFeedbackColor = rememberPressFeedbackColor(pressed)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(pressFeedbackColor)
                .responsiveTap(
                    onClick = onInfoClick,
                    onPressedChange = { pressed = it }
                )
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
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
            Text(text = summary, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}

@Composable
private fun ScriptPluginRow(
    checked: Boolean,
    title: String,
    summary: String,
    showSettings: Boolean,
    onOpenReadme: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenManager: () -> Unit,
    onCheckedChange: (Boolean) -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    val pressFeedbackColor = rememberPressFeedbackColor(pressed)
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(pressFeedbackColor)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f)
                .responsiveTap(
                    onClick = onOpenReadme,
                    onPressedChange = { pressed = it }
                )
        ) {
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
            Text(text = summary, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 12.sp)
        }
        if (showSettings) {
            Image(
                imageVector = NavIcons.Settings,
                contentDescription = "插件设置",
                colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceVariantSummary),
                modifier = Modifier
                    .padding(start = 10.dp, end = 8.dp)
                    .size(22.dp)
                    .responsiveTap(onClick = onOpenSettings)
            )
        }
        Image(
            imageVector = NavIcons.More,
            contentDescription = "管理插件",
            colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceVariantSummary),
            modifier = Modifier
                .padding(start = if (showSettings) 0.dp else 10.dp, end = 8.dp)
                .size(22.dp)
                .responsiveTap(onClick = onOpenManager)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun ScriptPluginPathDialog(
    context: Context,
    title: String,
    path: String,
    onClose: () -> Unit
) {
    WindowDialog(
        show = true,
        title = title,
        onDismissRequest = onClose,
        content = {
            Column {
                Text(
                    text = path,
                    color = MiuixTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MiuixTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                )
                TextButton(
                    text = "复制路径",
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText("WcxScriptDir", path))
                        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                        onClose()
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
                TextButton(
                    text = "关闭",
                    onClick = onClose,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    )
}

@Composable
fun FirstUseAgreementDialog(
    context: Context,
    onCancel: () -> Unit,
    onAccepted: () -> Unit
) {
    var input by remember { mutableStateOf("") }
    var remainingSeconds by remember { mutableStateOf(30) }
    val acceptedText = TermsGate.AGREEMENT_TEXT
    LaunchedEffect(Unit) {
        while (remainingSeconds > 0) {
            delay(1000L)
            remainingSeconds -= 1
        }
    }
    val agreement = remember {
        """
        Hchat 使用协议与免责声明

        1. 本模块为免费模块，仅供个人学习、研究、测试、逆向分析和备份用途使用，不提供任何商业授权、售后承诺或稳定性保证。
        2. 本模块与微信、腾讯及其关联主体无关，不代表官方立场，也不是官方客户端、官方插件或官方服务的一部分。
        3. 禁止倒卖、付费分发、捆绑销售、二次打包收费、引流售卖、以捐赠名义收费，禁止冒充作者、官方渠道或授权代理发布。
        4. 请勿在国内公开平台、群组、论坛、短视频平台、网盘分享页、应用市场或其它公开渠道传播、推广、引流、售卖或组织分发本模块。
        5. 本模块可能会修改微信运行时行为，使用后可能出现功能异常、消息异常、账号风控、限制登录、数据异常、闪退、掉线、模块冲突或其它不可预期问题。
        6. 使用者应自行确认所在地法律法规、平台协议、设备环境和账号风险；因安装、使用、传播、修改、二次分发或与其它模块共存产生的任何后果均由使用者自行承担。
        7. 禁止将本模块用于骚扰、欺诈、刷量、营销轰炸、盗取信息、破坏服务稳定性、绕过平台风控、侵犯他人权益或其它违法违规用途。
        8. 本模块不保证适配所有微信版本、系统版本、设备环境、热更新状态和其它模块共存环境，也不承诺持续维护、及时修复或提供任何形式的服务保障。
        9. 如果你不同意以上任一条款，请点击取消并停止使用本模块。

        如果你理解并接受以上内容，请在下方输入“$acceptedText”后继续使用。
        """.trimIndent()
    }
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val dialogMaxHeight = (screenHeight * 0.78f).coerceAtMost(640.dp)
    val agreementMaxHeight = (screenHeight * 0.42f).coerceIn(220.dp, 430.dp)
    WindowDialog(
        show = true,
        title = "使用协议",
        onDismissRequest = {},
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = dialogMaxHeight)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = agreementMaxHeight)
                        .verticalScroll(rememberScrollState())
                        .clip(RoundedCornerShape(10.dp))
                        .background(MiuixTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = agreement,
                        color = MiuixTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                        lineHeight = 20.sp
                    )
                }
                Text(
                    text = "请输入“$acceptedText”确认",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 14.dp)
                )
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = MiuixTheme.colorScheme.onSurface,
                        fontSize = 15.sp
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MiuixTheme.colorScheme.secondaryVariant)
                        .padding(horizontal = 12.dp, vertical = 11.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TextButton(
                        text = "取消",
                        onClick = onCancel,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                    TextButton(
                        text = if (remainingSeconds > 0) "继续（${remainingSeconds}s）" else "同意并继续",
                        onClick = {
                            if (remainingSeconds > 0) {
                                Toast.makeText(context, "请等待 ${remainingSeconds} 秒后继续", Toast.LENGTH_SHORT).show()
                            } else if (input.trim() == acceptedText) {
                                if (TermsGate.accept(context)) {
                                    onAccepted()
                                } else {
                                    Toast.makeText(context, "协议状态保存失败，请重试", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                Toast.makeText(context, "请输入“$acceptedText”后继续", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        }
    )
}

@Composable
fun ScriptPluginReadmeDialog(
    context: Context,
    plugin: ScriptPluginRuntime.ScriptPlugin,
    onClose: () -> Unit
) {
    val readme = remember(plugin.id) {
        runCatching {
            val file = File(plugin.dir, "README.md")
            if (file.isFile) file.readText(Charsets.UTF_8) else ""
        }.getOrDefault("")
    }
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val dialogMaxHeight = (screenHeight * 0.78f).coerceAtMost(640.dp)
    val readmeMaxHeight = (screenHeight * 0.58f).coerceIn(240.dp, 520.dp)

    WindowDialog(
        show = true,
        title = plugin.displayName ?: "未知",
        onDismissRequest = onClose,
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = dialogMaxHeight)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = readmeMaxHeight)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (readme.isBlank()) {
                        Text(
                            text = "暂无说明",
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 10.dp)
                        )
                    } else {
                        MarkdownUi.Content(context, readme)
                    }
                }
                TextButton(
                    text = "关闭",
                    onClick = onClose,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    )
}

}

private object MarkdownUi {

@Composable
fun Content(
    context: Context,
    markdown: String,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    bodyFontSize: androidx.compose.ui.unit.TextUnit = 13.sp,
    onCopyCode: ((String) -> Unit)? = null
) {
    val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').lines()
    var inCodeBlock = false
    var inlineState = MarkdownInlineState()
    val codeLines = ArrayList<String>()
    Column(modifier = Modifier.fillMaxWidth().padding(contentPadding)) {
        lines.forEach { rawLine ->
            val line = rawLine.trimEnd()
            if (line.trimStart().startsWith("```")) {
                if (inCodeBlock) {
                    CodeBlock(codeLines.joinToString("\n"), onCopyCode)
                    codeLines.clear()
                    inCodeBlock = false
                } else {
                    inCodeBlock = true
                    codeLines.clear()
                }
                return@forEach
            }
            if (inCodeBlock) {
                codeLines += rawLine
                return@forEach
            }
            val nextState = MarkdownLine(context, line, inlineState, bodyFontSize)
            inlineState = nextState
        }
        if (inCodeBlock && codeLines.isNotEmpty()) {
            CodeBlock(codeLines.joinToString("\n"), onCopyCode)
        }
    }
}

@Composable
private fun MarkdownLine(
    context: Context,
    line: String,
    inlineState: MarkdownInlineState,
    bodyFontSize: androidx.compose.ui.unit.TextUnit = 13.sp
): MarkdownInlineState {
    val trimmed = line.trim()
    return when {
        trimmed.isBlank() -> {
            Box(modifier = Modifier.height(8.dp))
            inlineState
        }
        trimmed.matches(Regex("""-{3,}|_{3,}|\*{3,}""")) -> {
            Box(
                modifier = Modifier.fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .height(1.dp)
                    .background(MiuixTheme.colorScheme.outline)
            )
            inlineState
        }
        trimmed.startsWith("#") -> {
            val level = trimmed.takeWhile { it == '#' }.length.coerceIn(1, 6)
            val text = trimmed.drop(level).trim()
            MarkdownTextResult(
                context = context,
                text = text,
                inlineState = inlineState,
                modifier = Modifier.padding(top = if (level <= 2) 10.dp else 8.dp, bottom = 4.dp),
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = when (level) {
                    1 -> 22.sp
                    2 -> 19.sp
                    3 -> 17.sp
                    else -> 15.sp
                },
                fontWeight = FontWeight.SemiBold
            )
        }
        trimmed.startsWith(">") -> {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Box(
                    modifier = Modifier.padding(end = 8.dp)
                        .size(width = 3.dp, height = 20.dp)
                        .background(MiuixTheme.colorScheme.primary)
                )
                MarkdownTextResult(
                    context = context,
                    text = trimmed.removePrefix(">").trim(),
                    inlineState = inlineState,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = bodyFontSize,
                    modifier = Modifier.weight(1f)
                )
            }
            inlineState
        }
        trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ") -> {
            MarkdownBullet(context, trimmed.drop(2).trim(), inlineState, fontSize = bodyFontSize)
        }
        Regex("""^\d+[.)]\s+.*""").matches(trimmed) -> {
            val marker = trimmed.substringBefore(' ').trim()
            MarkdownBullet(context, trimmed.removePrefix(marker).trim(), inlineState, marker, bodyFontSize)
        }
        else -> {
            MarkdownTextResult(
                context = context,
                text = line,
                inlineState = inlineState,
                fontSize = bodyFontSize,
                modifier = Modifier.padding(vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun MarkdownBullet(
    context: Context,
    text: String,
    inlineState: MarkdownInlineState,
    marker: String = "•",
    fontSize: androidx.compose.ui.unit.TextUnit = 13.sp
): MarkdownInlineState {
    var nextState = inlineState
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = marker,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = fontSize,
            modifier = Modifier.padding(end = 8.dp)
        )
        nextState = MarkdownTextResult(
            context = context,
            text = text,
            inlineState = inlineState,
            fontSize = fontSize,
            modifier = Modifier.weight(1f)
        )
    }
    return nextState
}

@Composable
fun CodeBlock(code: String, onCopy: ((String) -> Unit)? = null) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MiuixTheme.colorScheme.surfaceVariant)
    ) {
        if (onCopy != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 10.dp, top = 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "代码",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f)
                )
                Image(
                    imageVector = NavIcons.Copy,
                    contentDescription = "复制代码",
                    colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceVariantSummary),
                    modifier = Modifier.size(26.dp).clip(RoundedCornerShape(4.dp))
                        .clickable { onCopy(code) }
                        .padding(6.dp)
                )
            }
        }
        Text(
            text = code,
            color = MiuixTheme.colorScheme.onSurface,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
        )
    }
}

@Composable
fun Text(
    context: Context,
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MiuixTheme.colorScheme.onSurface,
    fontSize: androidx.compose.ui.unit.TextUnit = 13.sp,
    fontWeight: FontWeight? = null
) {
    MarkdownTextResult(context, text, MarkdownInlineState(), modifier, color, fontSize, fontWeight)
}

@Composable
private fun MarkdownTextResult(
    context: Context,
    text: String,
    inlineState: MarkdownInlineState,
    modifier: Modifier = Modifier,
    color: Color = MiuixTheme.colorScheme.onSurface,
    fontSize: androidx.compose.ui.unit.TextUnit = 13.sp,
    fontWeight: FontWeight? = null
): MarkdownInlineState {
    val primary = MiuixTheme.colorScheme.primary
    val result = remember(text, primary, inlineState.bold) {
        buildMarkdownAnnotatedString(text, primary, inlineState)
    }
    ClickableText(
        text = result.text,
        modifier = modifier,
        style = TextStyle(color = color, fontSize = fontSize, fontWeight = fontWeight),
        onClick = { offset ->
            result.text.getStringAnnotations(MARKDOWN_LINK_TAG, offset, offset)
                .firstOrNull()
                ?.let { annotation -> openMarkdownLink(context, annotation.item) }
        }
    )
    return result.state
}

private data class MarkdownInlineState(val bold: Boolean = false)

private data class MarkdownInlineResult(
    val text: AnnotatedString,
    val state: MarkdownInlineState
)

private fun buildMarkdownAnnotatedString(
    text: String,
    accent: Color,
    initialState: MarkdownInlineState
): MarkdownInlineResult {
    var state = initialState
    val annotated = buildAnnotatedString {
        state = appendInlineMarkdown(text, accent, state)
    }
    return MarkdownInlineResult(annotated, state)
}

private fun AnnotatedString.Builder.appendInlineMarkdown(
    text: String,
    accent: Color,
    initialState: MarkdownInlineState
): MarkdownInlineState {
    var state = initialState
    var segmentStart = 0
    MARKDOWN_LINK_REGEX.findAll(text).forEach { match ->
        if (match.range.first > segmentStart) {
            state = appendInlineMarkdownSegment(
                text.substring(segmentStart, match.range.first),
                accent,
                state
            )
        }
        val label = match.groupValues[1]
        val url = match.groupValues[2].trim()
        if (label.isNotBlank() && url.isNotBlank()) {
            pushStringAnnotation(MARKDOWN_LINK_TAG, url)
            appendStyledInline(label, accent, state, link = true)
            pop()
        } else {
            append(match.value)
        }
        segmentStart = match.range.last + 1
    }
    if (segmentStart < text.length) {
        state = appendInlineMarkdownSegment(text.substring(segmentStart), accent, state)
    }
    return state
}

private fun AnnotatedString.Builder.appendInlineMarkdownSegment(
    text: String,
    accent: Color,
    initialState: MarkdownInlineState
): MarkdownInlineState {
    var state = initialState
    var index = 0
    while (index < text.length) {
        when {
            text.startsWith("**", index) -> {
                state = state.copy(bold = !state.bold)
                index += 2
            }
            text[index] == '`' -> {
                val end = text.indexOf('`', index + 1)
                if (end > index) {
                    withStyle(SpanStyle(color = accent, fontFamily = FontFamily.Monospace)) {
                        append(text.substring(index + 1, end))
                    }
                    index = end + 1
                } else {
                    append(text[index])
                    index++
                }
            }
            else -> {
                appendStyledInline(text[index].toString(), accent, state, link = false)
                index++
            }
        }
    }
    return state
}

private fun AnnotatedString.Builder.appendStyledInline(
    value: String,
    accent: Color,
    state: MarkdownInlineState,
    link: Boolean
) {
    val style = SpanStyle(
        color = if (link) accent else Color.Unspecified,
        fontWeight = when {
            link -> FontWeight.Medium
            state.bold -> FontWeight.SemiBold
            else -> null
        }
    )
    withStyle(style) {
        append(value)
    }
}

private fun openMarkdownLink(context: Context, url: String) {
    val value = url.trim()
    if (value.isBlank()) return
    runCatching {
        val normalized = if (value.contains("://")) value else "https://$value"
        val uri = Uri.parse(normalized)
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

}

// ---------------------------------------------------------------------------
// WCX 侧本地组件实现（替代 Hchat 的通用组件，避免连带迁移无关模块）
// 视觉风格沿用 WCX 设置页的 Miuix 组件。
// ---------------------------------------------------------------------------


/** 市场服务器地址打码（保留协议头，隐藏主机与路径）。 */
private fun maskServiceUrl(url: String): String {
    val idx = url.indexOf("://")
    return if (idx > 0) url.substring(0, idx + 3) + "••••••••••••" else "••••••••••••"
}

/**
 * 市场服务器设置卡片。
 *
 * 地址默认打码隐藏，点击右侧「眼睛」显示原文；支持在「官方默认 / 备用 / 自定义」之间切换，
 * 便于第三方服务器不可用或迁移到自建服务器时随时切换，无需重新发版。
 */
@Composable
internal fun MarketServerSettingCard(context: Context) {
    var reveal by remember { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf(PluginMarketSettings.serviceUrl(context)) }

    SettingsCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "当前地址",
                    color = MiuixTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = if (reveal) current else maskServiceUrl(current),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 12.sp,
                )
            }
            Image(
                imageVector = if (reveal) NavIcons.VisibilityOff else NavIcons.Visibility,
                contentDescription = if (reveal) "隐藏地址" else "显示地址",
                colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceVariantSummary),
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { reveal = !reveal }
                    .padding(2.dp),
            )
        }
        InsetDivider()
        ActionRow("切换市场服务器", "官方默认 / 备用 / 自定义地址") { showDialog = true }
    }

    if (showDialog) {
        MarketServerDialog(
            context = context,
            currentUrl = current,
            onDismiss = { showDialog = false },
            onSaved = {
                current = PluginMarketSettings.serviceUrl(context)
                showDialog = false
            },
        )
    }
}

@Composable
private fun MarketServerDialog(
    context: Context,
    currentUrl: String,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val presets = PluginMarketSettings.presetServiceUrls()
    var selected by remember { mutableStateOf(if (presets.contains(currentUrl)) currentUrl else "") }
    var custom by remember { mutableStateOf(if (presets.contains(currentUrl)) "" else currentUrl) }

    WindowDialog(
        show = true,
        title = "切换市场服务器",
        onDismissRequest = onDismiss,
        content = {
            Column {
                presets.forEach { url ->
                    MarketServerOptionRow(
                        title = PluginMarketSettings.presetLabel(url),
                        summary = url,
                        selected = selected == url,
                        onClick = {
                            selected = url
                            custom = ""
                        },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "自定义地址",
                    color = MiuixTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                )
                BasicTextField(
                    value = custom,
                    onValueChange = {
                        custom = it.take(200)
                        selected = ""
                    },
                    singleLine = true,
                    textStyle = TextStyle(color = MiuixTheme.colorScheme.onSurface, fontSize = 14.sp),
                    cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MiuixTheme.colorScheme.secondaryVariant)
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TextButton(
                        text = "取消",
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                    TextButton(
                        text = "保存",
                        onClick = {
                            val target = if (selected.isNotBlank()) selected else custom.trim().trimEnd('/')
                            if (target.isBlank()) return@TextButton
                            PluginMarketSettings.saveServiceUrl(context, target)
                            onSaved()
                        },
                        enabled = selected.isNotBlank() || custom.trim().isNotBlank(),
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        },
    )
}

@Composable
private fun MarketServerOptionRow(
    title: String,
    summary: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = MiuixTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = summary,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 11.sp,
            )
        }
        if (selected) {
            Text(
                text = "✓",
                color = MiuixTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/** 卡片容器（圆角卡片，视觉对齐 Hchat）。 */
@Composable
internal fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
        cornerRadius = 18.dp,
    ) {
        Column { content() }
    }
}

/** 可点击的条目行（标题 + 摘要 + 右侧 ›；圆角按压反馈，对齐 Hchat）。 */
@Composable
internal fun ActionRow(title: String, summary: String, onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val pressFeedbackColor = rememberPressFeedbackColor(pressed)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(pressFeedbackColor)
            .responsiveTap(onClick = onClick, onPressedChange = { pressed = it })
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = MiuixTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
            if (summary.isNotBlank()) {
                Text(
                    text = summary,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 12.sp,
                )
            }
        }
        Text(
            text = "›",
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 22.sp,
        )
    }
}

/** 选择标记。 */
@Composable
internal fun SelectionMark(selected: Boolean, multiSelect: Boolean) {
    Text(
        text = if (selected) "✓" else "",
        fontSize = MiuixTheme.textStyles.body2.fontSize,
        fontWeight = FontWeight.Medium,
        color = if (selected) MiuixTheme.colorScheme.primary
        else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(end = 8.dp),
    )
}

/** 空态文案。 */
@Composable
internal fun EmptyText(text: String) {
    BasicComponent(onClick = null) {
        Text(
            text = text,
            fontSize = MiuixTheme.textStyles.body2.fontSize,
            color = BasicComponentDefaults.summaryColor().color,
            modifier = Modifier.padding(vertical = 10.dp),
        )
    }
}

/** 缩进分隔线。 */
@Composable
internal fun InsetDivider(start: Dp = 16.dp) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = start)
            .height(1.dp)
            .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.2f))
    )
}

/** 底部操作条。 */
@Composable
internal fun BottomActionBar(text: String, onClick: () -> Unit) {
    BasicComponent(onClick = onClick) {
        Text(
            text = text,
            fontSize = MiuixTheme.textStyles.headline1.fontSize,
            color = MiuixTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 底部双按钮操作栏（移植自 Hchat）。
 * 与上面的单按钮版本并存：两者参数名不同，重载可区分。
 */
@Composable
internal fun BottomActionBar(
    primaryText: String,
    onPrimaryClick: () -> Unit,
    secondaryText: String? = null,
    onSecondaryClick: (() -> Unit)? = null,
    middleText: String? = null,
    onMiddleClick: (() -> Unit)? = null
) {
    val backAction = when {
        secondaryText == "取消" && onSecondaryClick != null -> onSecondaryClick
        middleText == "取消" && onMiddleClick != null -> onMiddleClick
        primaryText == "取消" -> onPrimaryClick
        secondaryText == "返回" && onSecondaryClick != null -> onSecondaryClick
        middleText == "返回" && onMiddleClick != null -> onMiddleClick
        primaryText == "返回" -> onPrimaryClick
        secondaryText == "关闭" && onSecondaryClick != null -> onSecondaryClick
        middleText == "关闭" && onMiddleClick != null -> onMiddleClick
        primaryText == "关闭" -> onPrimaryClick
        else -> null
    }
    RegisterSettingsBackHandler(backAction)
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(MiuixTheme.colorScheme.background.copy(alpha = 0.92f))
            .padding(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (secondaryText != null && onSecondaryClick != null) {
            BottomBarButton(
                text = secondaryText,
                modifier = Modifier.weight(1f),
                filled = false,
                onClick = onSecondaryClick
            )
        }
        if (middleText != null && onMiddleClick != null) {
            BottomBarButton(
                text = middleText,
                modifier = Modifier.weight(1f),
                filled = false,
                onClick = onMiddleClick
            )
        }
        BottomBarButton(
            text = primaryText,
            modifier = Modifier.weight(1f),
            filled = true,
            onClick = onPrimaryClick
        )
    }
}

/** 底部操作栏按钮（移植自 Hchat）。 */
@Composable
private fun BottomBarButton(text: String, modifier: Modifier, filled: Boolean, onClick: () -> Unit) {
    val bg = if (filled) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.secondaryVariant
    val fg = if (filled) Color.White else MiuixTheme.colorScheme.onSecondaryVariant
    Text(
        modifier = modifier.clip(RoundedCornerShape(14.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        text = text,
        color = fg,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center
    )
}

/** 搜索框。 */
@Composable
internal fun SearchBarSurface(
    query: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    onQueryChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { M3Text(placeholder) },
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
    )
}

/** 页面脚手架（顶栏 + 可选右上操作 + 可选底部操作栏 + 内容；不依赖 miuix 高级 API，避免版本差异）。 */
@Composable
internal fun PageScaffold(
    title: String,
    largeTitle: String,
    scrollBehavior: Any? = null,
    onBack: (() -> Unit)? = null,
    topBarActions: @Composable RowScope.() -> Unit = {},
    bottomBar: (@Composable () -> Unit)? = null,
    @Suppress("UNUSED_PARAMETER") largeTitleUnused: String = largeTitle,
    content: @Composable (PaddingValues) -> Unit,
) {
    // 脚本管理/导入/在线插件页均为全屏 PageScaffold，顶栏需避开状态栏，否则「返回」会贴到状态栏。
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    BasicComponent(onClick = onBack) {
                        Text(
                            text = if (onBack != null) "← $title" else title,
                            fontSize = MiuixTheme.textStyles.headline1.fontSize,
                            fontWeight = FontWeight.Medium,
                            color = if (onBack != null) MiuixTheme.colorScheme.primary
                            else BasicComponentDefaults.titleColor().color,
                        )
                    }
                }
                topBarActions()
            }
            // 内容区用 weight(1f) 固定为「剩余高度」，保证内部 LazyColumn 拿到有界约束，
            // 否则可能被按无界高度测量（滚动区间为 0，列表滑不动）。
            Box(modifier = Modifier.weight(1f)) {
                content(PaddingValues(0.dp))
            }
        }
        if (bottomBar != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter),
            ) {
                bottomBar()
            }
        }
    }
}

/** 读取文件选择器返回 Uri 的显示名（移植自 Hchat MiuixSettingsPage）。 */
private fun queryDisplayName(context: Context, uri: Uri?): String? {
    if (uri == null) return null
    if ("content".equals(uri.scheme, ignoreCase = true)) {
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) {
                            return cursor.getString(index)?.takeIf { it.isNotBlank() }
                        }
                    }
                }
        } catch (_: Throwable) {
        }
    }
    return uri.lastPathSegment
        ?.let { Uri.decode(it) }
        ?.substringAfterLast('/')
        ?.substringAfterLast(':')
        ?.takeIf { it.isNotBlank() }
}

// ---------------------------------------------------------------------------
// 以下为【在线插件市场】UI，按 Hchat 原版整段移植（列表页 + 插件详情页 + 历史版本），
// 仅把「取宿主 Activity」从 Agent 工具改为本文件内已有的 findActivity()。
// ---------------------------------------------------------------------------

private object PluginMarketExtraFilePickerBridge {
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

internal data class PopupChoice<T>(
    val label: String,
    val value: T
)

@Composable
internal fun PopupChoiceRow(
    title: String,
    summary: String,
    options: List<PopupChoice<String>>,
    currentValue: String,
    onValueChanged: (String) -> Unit,
    enabled: Boolean = true
) {
    val selectedIndex = options.indexOfFirst { it.value == currentValue }.takeIf { it >= 0 } ?: 0
    WindowDropdownMenu(
        entry = DropdownEntry(
            items = options.mapIndexed { index, option ->
                DropdownItem(
                    text = option.label,
                    selected = index == selectedIndex,
                    onClick = { onValueChanged(option.value) }
                )
            }
        ),
        title = title,
        summary = summary,
        enabled = enabled,
        collapseOnSelection = true
    )
}

internal object PluginMarketUi {

private enum class PluginMarketUploadPhase {
    QUEUED,
    UPLOADING,
    SUCCESS,
    PENDING_REVIEW,
    FAILED
}

private data class PluginMarketUploadStatus(
    val phase: PluginMarketUploadPhase,
    val message: String
)

@Composable
fun ScriptPluginMarketPage(
    context: Context,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf("latest") }
    var refreshVersion by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf("") }
    var plugins by remember { mutableStateOf<List<PluginMarketPlugin>>(emptyList()) }
    var pluginCount by remember { mutableStateOf(0) }
    var selectedPlugin by remember { mutableStateOf<PluginMarketPlugin?>(null) }
    var detailPlugin by remember { mutableStateOf<PluginMarketPlugin?>(null) }
    var detailLoading by remember { mutableStateOf(false) }
    var detailError by remember { mutableStateOf("") }
    var detailRetryVersion by remember { mutableStateOf(0) }
    var historyVersions by remember { mutableStateOf<List<PluginMarketHistoryVersion>>(emptyList()) }
    var historyLoading by remember { mutableStateOf(false) }
    var historyError by remember { mutableStateOf("") }
    var historyInstallVersionId by remember { mutableStateOf<String?>(null) }
    var comments by remember { mutableStateOf<List<PluginMarketComment>>(emptyList()) }
    var commentsLoading by remember { mutableStateOf(false) }
    var commentsError by remember { mutableStateOf("") }
    var commentsRetryVersion by remember { mutableStateOf(0) }
    var currentUserIdentity by remember { mutableStateOf<PluginMarketUserIdentity?>(null) }
    var interactionError by remember { mutableStateOf("") }
    var likedByCurrentUser by remember { mutableStateOf(false) }
    var followingByCurrentUser by remember { mutableStateOf(false) }
    var likeCountLoaded by remember { mutableStateOf(false) }
    var commentCountLoaded by remember { mutableStateOf(false) }
    var updatingLike by remember { mutableStateOf(false) }
    var updatingFollow by remember { mutableStateOf(false) }
    var commentDraft by remember { mutableStateOf("") }
    var submittingComment by remember { mutableStateOf(false) }
    var deletingCommentId by remember { mutableStateOf<String?>(null) }
    var detailTargetExists by remember { mutableStateOf(false) }
    var installingRemoteId by remember { mutableStateOf<String?>(null) }
    var deletingRemoteId by remember { mutableStateOf<String?>(null) }
    var installMessage by remember { mutableStateOf("") }
    var pendingOverwrite by remember { mutableStateOf<PluginMarketPlugin?>(null) }
    var showUploadDialog by remember { mutableStateOf(false) }
    var notifications by remember { mutableStateOf<List<PluginMarketNotification>>(emptyList()) }
    var notificationUnreadCount by remember { mutableStateOf(0L) }
    var notificationsLoading by remember { mutableStateOf(true) }
    var notificationsError by remember { mutableStateOf("") }
    var notificationRefreshVersion by remember { mutableStateOf(0) }
    var showNotifications by remember { mutableStateOf(false) }

    fun updateSocialCounts(
        remotePluginId: String,
        likeCount: Long? = null,
        commentCount: Long? = null
    ) {
        fun updated(plugin: PluginMarketPlugin): PluginMarketPlugin = plugin.copy(
            likeCount = likeCount ?: plugin.likeCount,
            commentCount = commentCount ?: plugin.commentCount
        )
        detailPlugin = detailPlugin?.let { if (it.remotePluginId == remotePluginId) updated(it) else it }
        selectedPlugin = selectedPlugin?.let { if (it.remotePluginId == remotePluginId) updated(it) else it }
        plugins = plugins.map { if (it.remotePluginId == remotePluginId) updated(it) else it }
    }

    LaunchedEffect(query, sort, refreshVersion) {
        delay(250L)
        loading = true
        loadError = ""
        val result = withContext(Dispatchers.IO) {
            PluginMarketRepository.list(
                context = context,
                query = query.trim(),
                sort = sort,
                limit = Int.MAX_VALUE
            )
        }
        result.fold(
            onSuccess = { page ->
                plugins = page.items
                pluginCount = page.count
            },
            onFailure = { error ->
                loadError = pluginMarketErrorMessage(error)
                if (plugins.isEmpty()) pluginCount = 0
            }
        )
        loading = false
    }

    LaunchedEffect(notificationRefreshVersion) {
        notificationsLoading = true
        notificationsError = ""
        val result = withContext(Dispatchers.IO) {
            PluginMarketRepository.notifications(context, limit = Int.MAX_VALUE)
        }
        result.fold(
            onSuccess = { page ->
                notifications = page.items
                notificationUnreadCount = page.unreadCount
            },
            onFailure = { error ->
                notificationsError = pluginMarketErrorMessage(error)
                if (notifications.isEmpty()) notificationUnreadCount = 0L
            }
        )
        notificationsLoading = false
    }

    LaunchedEffect(showNotifications, notificationsLoading) {
        if (!showNotifications || notificationsLoading) return@LaunchedEffect
        val unreadIds = notifications.filterNot { it.read }.map { it.notificationId }
        if (unreadIds.isEmpty()) return@LaunchedEffect
        val result = withContext(Dispatchers.IO) {
            PluginMarketRepository.markNotificationsRead(context, unreadIds)
        }
        result.onSuccess { readResult ->
            notificationUnreadCount = readResult.unreadCount
            val markedIds = unreadIds.toHashSet()
            notifications = notifications.map { item ->
                if (item.notificationId in markedIds) item.copy(read = true) else item
            }
        }
    }

    LaunchedEffect(selectedPlugin?.remotePluginId, detailRetryVersion) {
        val selected = selectedPlugin ?: return@LaunchedEffect
        detailLoading = true
        detailError = ""
        detailPlugin = null
        historyLoading = true
        historyError = ""
        historyVersions = emptyList()
        detailTargetExists = false
        installMessage = ""
        val (detailResult, historyResult) = withContext(Dispatchers.IO) {
            val loadedDetail = PluginMarketRepository.detail(context, selected.remotePluginId).map { detail ->
                val exists = PluginMarketInstaller.existingLocalPluginId(context, detail) != null
                detail to exists
            }
            loadedDetail to PluginMarketRepository.history(context, selected.remotePluginId)
        }
        detailResult.fold(
            onSuccess = { (detail, exists) ->
                val currentSummary = selectedPlugin?.takeIf {
                    it.remotePluginId == selected.remotePluginId
                }
                detailPlugin = detail.copy(
                    likeCount = if (likeCountLoaded) {
                        currentSummary?.likeCount ?: detail.likeCount
                    } else {
                        detail.likeCount
                    },
                    commentCount = if (commentCountLoaded) {
                        currentSummary?.commentCount ?: detail.commentCount
                    } else {
                        detail.commentCount
                    }
                )
                detailTargetExists = exists
            },
            onFailure = { error ->
                detailError = pluginMarketErrorMessage(error)
                detailTargetExists = false
            }
        )
        historyResult.fold(
            onSuccess = { historyVersions = it },
            onFailure = { historyError = pluginMarketErrorMessage(it) }
        )
        detailLoading = false
        historyLoading = false
    }

    LaunchedEffect(selectedPlugin?.remotePluginId, commentsRetryVersion) {
        val selected = selectedPlugin ?: return@LaunchedEffect
        commentsLoading = true
        commentsError = ""
        interactionError = ""
        comments = emptyList()
        currentUserIdentity = null
        likedByCurrentUser = false
        likeCountLoaded = false
        commentCountLoaded = false
        val identityResult = withContext(Dispatchers.IO) { PluginMarketRepository.currentUserIdentity(context) }
        val commentsResult = withContext(Dispatchers.IO) { PluginMarketRepository.comments(context, selected.remotePluginId) }
        val likedResult = withContext(Dispatchers.IO) { PluginMarketRepository.likeStatus(context, selected.remotePluginId) }
        val followedResult = withContext(Dispatchers.IO) { PluginMarketRepository.followStatus(context, selected.remotePluginId) }
        identityResult.fold(
            onSuccess = { currentUserIdentity = it },
            onFailure = { interactionError = pluginMarketErrorMessage(it) }
        )
        commentsResult.fold(
            onSuccess = { page ->
                comments = page.items
                commentCountLoaded = true
                updateSocialCounts(selected.remotePluginId, commentCount = page.total)
            },
            onFailure = { commentsError = pluginMarketErrorMessage(it) }
        )
        likedResult.fold(
            onSuccess = { likeResult ->
                likedByCurrentUser = likeResult.liked
                likeCountLoaded = true
                updateSocialCounts(selected.remotePluginId, likeCount = likeResult.likeCount)
            },
            onFailure = {
                if (interactionError.isBlank()) interactionError = pluginMarketErrorMessage(it)
            }
        )
        followedResult.fold(
            onSuccess = { followingByCurrentUser = it },
            onFailure = { if (interactionError.isBlank()) interactionError = pluginMarketErrorMessage(it) }
        )
        commentsLoading = false
    }

    fun performInstall(plugin: PluginMarketPlugin, overwrite: Boolean) {
        if (installingRemoteId != null) return
        installingRemoteId = plugin.remotePluginId
        installMessage = ""
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                PluginMarketRepository.install(context, plugin, overwrite)
            }
            result.fold(
                onSuccess = { installed ->
                    detailTargetExists = true
                    installed.downloadCount?.let { count ->
                        detailPlugin = detailPlugin?.copy(downloadCount = count)
                        selectedPlugin = selectedPlugin?.copy(downloadCount = count)
                        plugins = plugins.map { item ->
                            if (item.remotePluginId == plugin.remotePluginId) {
                                item.copy(downloadCount = count)
                            } else {
                                item
                            }
                        }
                    }
                    installMessage = if (installed.replacedExisting) {
                        "更新成功，插件已保持禁用"
                    } else {
                        "安装成功，插件默认禁用"
                    }
                    Toast.makeText(context, installMessage, Toast.LENGTH_SHORT).show()
                },
                onFailure = { error ->
                    installMessage = "安装失败: ${pluginMarketErrorMessage(error)}"
                }
            )
            installingRemoteId = null
        }
    }

    fun requestInstall(plugin: PluginMarketPlugin) {
        if (installingRemoteId != null) return
        installingRemoteId = plugin.remotePluginId
        installMessage = ""
        scope.launch {
            val exists = withContext(Dispatchers.IO) {
                PluginMarketInstaller.existingLocalPluginId(context, plugin) != null
            }
            installingRemoteId = null
            if (exists) {
                pendingOverwrite = plugin
            } else {
                performInstall(plugin, overwrite = false)
            }
        }
    }

    fun requestHistoryInstall(version: PluginMarketHistoryVersion) {
        val selected = selectedPlugin ?: return
        if (historyInstallVersionId != null || installingRemoteId != null) return
        historyInstallVersionId = version.versionId
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                PluginMarketRepository.historyDetail(
                    context,
                    selected.remotePluginId,
                    version.versionId
                )
            }
            historyInstallVersionId = null
            result.fold(
                onSuccess = ::requestInstall,
                onFailure = {
                    Toast.makeText(
                        context,
                        "读取历史版本失败: ${pluginMarketErrorMessage(it)}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
    }

    fun deleteOwnedPlugin(plugin: PluginMarketPlugin) {
        if (deletingRemoteId != null || installingRemoteId != null) return
        deletingRemoteId = plugin.remotePluginId
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                PluginMarketRepository.deleteOwnedRemote(context, plugin.remotePluginId)
            }
            deletingRemoteId = null
            result.fold(
                onSuccess = {
                    selectedPlugin = null
                    detailPlugin = null
                    historyVersions = emptyList()
                    plugins = plugins.filterNot { it.remotePluginId == plugin.remotePluginId }
                    pluginCount = (pluginCount - 1).coerceAtLeast(0)
                    refreshVersion++
                    Toast.makeText(context, "线上插件已删除，本地插件不受影响", Toast.LENGTH_SHORT).show()
                },
                onFailure = { error ->
                    Toast.makeText(
                        context,
                        "删除失败: ${pluginMarketErrorMessage(error)}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
    }

    fun togglePluginLike(plugin: PluginMarketPlugin) {
        if (updatingLike) return
        if (currentUserIdentity == null) {
            Toast.makeText(
                context,
                interactionError.ifBlank { "当前微信账号资料尚未就绪" },
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val removeLike = likedByCurrentUser
        updatingLike = true
        interactionError = ""
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                if (removeLike) {
                    PluginMarketRepository.unlike(context, plugin.remotePluginId)
                } else {
                    PluginMarketRepository.like(context, plugin.remotePluginId)
                }
            }
            result.fold(
                onSuccess = { likeResult ->
                    likedByCurrentUser = likeResult.liked
                    likeCountLoaded = true
                    updateSocialCounts(plugin.remotePluginId, likeCount = likeResult.likeCount)
                },
                onFailure = { error ->
                    interactionError = pluginMarketErrorMessage(error)
                    Toast.makeText(context, "操作失败: $interactionError", Toast.LENGTH_LONG).show()
                }
            )
            updatingLike = false
        }
    }

    fun togglePluginFollow(plugin: PluginMarketPlugin) {
        if (updatingFollow || currentUserIdentity == null) return
        updatingFollow = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                PluginMarketRepository.toggleFollow(context, plugin.remotePluginId, followingByCurrentUser)
            }
            result.fold(
                onSuccess = { followingByCurrentUser = it },
                onFailure = { interactionError = pluginMarketErrorMessage(it) }
            )
            updatingFollow = false
        }
    }
    fun submitPluginComment(plugin: PluginMarketPlugin) {
        if (submittingComment) return
        val content = commentDraft.trim()
        if (content.isBlank()) {
            Toast.makeText(context, "评论内容不能为空", Toast.LENGTH_SHORT).show()
            return
        }
        if (currentUserIdentity == null) {
            Toast.makeText(
                context,
                interactionError.ifBlank { "当前微信账号资料尚未就绪" },
                Toast.LENGTH_LONG
            ).show()
            return
        }
        submittingComment = true
        interactionError = ""
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                PluginMarketRepository.addComment(context, plugin.remotePluginId, content)
            }
            result.fold(
                onSuccess = { mutation ->
                    mutation.comment?.let { added ->
                        comments = (listOf(added) + comments.filterNot { it.commentId == added.commentId })
                    }
                    commentDraft = ""
                    commentCountLoaded = true
                    updateSocialCounts(plugin.remotePluginId, commentCount = mutation.commentCount)
                },
                onFailure = { error ->
                    interactionError = pluginMarketErrorMessage(error)
                    Toast.makeText(context, "评论失败: $interactionError", Toast.LENGTH_LONG).show()
                }
            )
            submittingComment = false
        }
    }

    fun submitPluginReply(
        plugin: PluginMarketPlugin,
        parentComment: PluginMarketComment,
        content: String,
        onCompleted: (Boolean) -> Unit
    ) {
        if (submittingComment) {
            onCompleted(false)
            return
        }
        if (currentUserIdentity == null) {
            Toast.makeText(
                context,
                interactionError.ifBlank { "当前微信账号资料尚未就绪" },
                Toast.LENGTH_LONG
            ).show()
            onCompleted(false)
            return
        }
        submittingComment = true
        interactionError = ""
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                PluginMarketRepository.replyComment(
                    context,
                    plugin.remotePluginId,
                    parentComment,
                    content
                )
            }
            var succeeded = false
            result.fold(
                onSuccess = { mutation ->
                    mutation.comment?.let { added ->
                        comments = (listOf(added) + comments.filterNot {
                            it.commentId == added.commentId
                        })
                    }
                    commentCountLoaded = true
                    updateSocialCounts(plugin.remotePluginId, commentCount = mutation.commentCount)
                    succeeded = true
                },
                onFailure = { error ->
                    interactionError = pluginMarketErrorMessage(error)
                    Toast.makeText(context, "回复失败: $interactionError", Toast.LENGTH_LONG).show()
                }
            )
            submittingComment = false
            onCompleted(succeeded)
        }
    }

    fun openNotifications() {
        showNotifications = true
        notificationRefreshVersion++
    }

    fun deletePluginComment(plugin: PluginMarketPlugin, comment: PluginMarketComment) {
        if (deletingCommentId != null) return
        if (!comment.canDelete) {
            Toast.makeText(context, "只能删除自己的评论", Toast.LENGTH_SHORT).show()
            return
        }
        deletingCommentId = comment.commentId
        interactionError = ""
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                PluginMarketRepository.deleteOwnComment(context, plugin.remotePluginId, comment)
            }
            result.fold(
                onSuccess = { mutation ->
                    comments = comments.filterNot { it.commentId == comment.commentId }
                    commentCountLoaded = true
                    updateSocialCounts(plugin.remotePluginId, commentCount = mutation.commentCount)
                    if (mutation.commentCount != comments.size.toLong()) commentsRetryVersion++
                },
                onFailure = { error ->
                    interactionError = pluginMarketErrorMessage(error)
                    Toast.makeText(context, "删除评论失败: $interactionError", Toast.LENGTH_LONG).show()
                }
            )
            deletingCommentId = null
        }
    }

    PageScaffold(
        title = "在线插件",
        largeTitle = "在线插件",
        scrollBehavior = scrollBehavior,
        topBarActions = {
            TextButton(
                text = "上传本地插件",
                onClick = { showUploadDialog = true },
                colors = ButtonDefaults.textButtonColorsPrimary()
            )
            PluginMarketNotificationBell(
                unreadCount = notificationUnreadCount,
                onClick = ::openNotifications
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item {
                SearchBarSurface(
                    query = query,
                    placeholder = "搜索插件、作者或目录名",
                    modifier = Modifier.padding(horizontal = 12.dp),
                    onQueryChange = { query = it }
                )
            }
            item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "浏览") }
            item {
                SettingsCard {
                    PopupChoiceRow(
                        title = "排序方式",
                        summary = if (sort == "downloads") "热门（按下载量）" else "最新发布",
                        options = listOf(
                            PopupChoice("最新", "latest"),
                            PopupChoice("热门", "downloads")
                        ),
                        currentValue = sort,
                        onValueChanged = { sort = it }
                    )
                    InsetDivider()
                    ActionRow(
                        title = "刷新",
                        summary = if (loading) "正在加载在线插件" else "重新获取当前列表"
                    ) {
                        refreshVersion++
                    }
                }
            }
            item {
                SettingsCard(modifier = Modifier.padding(top = 10.dp)) {
                    Text(
                        text = "社区插件由用户上传，安装前请核对作者、说明和文件内容。下载后的插件默认禁用。",
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)
                    )
                }
            }
            item {
                SmallTitle(
                    modifier = Modifier.padding(top = 10.dp),
                    text = "在线插件(${if (loading && plugins.isEmpty()) "-" else pluginCount})"
                )
            }
            if (loadError.isNotBlank()) {
                item {
                    SettingsCard {
                        Column {
                            EmptyText(loadError)
                            TextButton(
                                text = "重试",
                                onClick = { refreshVersion++ },
                                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                                colors = ButtonDefaults.textButtonColorsPrimary()
                            )
                        }
                    }
                }
            } else if (loading && plugins.isEmpty()) {
                item { SettingsCard { EmptyText("正在加载在线插件...") } }
            } else if (plugins.isEmpty()) {
                item {
                    SettingsCard {
                        EmptyText(if (query.isBlank()) "暂无在线插件" else "没有匹配的在线插件")
                    }
                }
            } else {
                item {
                    SettingsCard {
                        plugins.forEachIndexed { index, plugin ->
                            PluginMarketListRow(plugin) {
                                selectedPlugin = plugin
                            }
                            if (index != plugins.lastIndex) InsetDivider()
                        }
                    }
                }
            }
        }
        
    }

    selectedPlugin?.let { summary ->
        PluginMarketDetailDialog(
            summary = summary,
            detail = detailPlugin,
            loading = detailLoading,
            error = detailError,
            historyVersions = historyVersions,
            historyLoading = historyLoading,
            historyError = historyError,
            historyInstallVersionId = historyInstallVersionId,
            installed = detailTargetExists,
            installing = installingRemoteId == summary.remotePluginId,
            deleting = deletingRemoteId == summary.remotePluginId,
            owned = PluginMarketRepository.ownsRemotePlugin(context, summary.remotePluginId),
            installMessage = installMessage,
            likedByCurrentUser = likedByCurrentUser,
            followingByCurrentUser = followingByCurrentUser,
            updatingLike = updatingLike,
            updatingFollow = updatingFollow,
            comments = comments,
            commentsLoading = commentsLoading,
            commentsError = commentsError,
            currentUserWxId = currentUserIdentity?.wxId.orEmpty(),
            interactionError = interactionError,
            commentDraft = commentDraft,
            submittingComment = submittingComment,
            deletingCommentId = deletingCommentId,
            onRetry = { detailRetryVersion++ },
            onRetryComments = { commentsRetryVersion++ },
            onInstall = ::requestInstall,
            onInstallHistory = ::requestHistoryInstall,
            onToggleLike = { togglePluginLike(summary) },
            onToggleFollow = { togglePluginFollow(summary) },
            onCommentDraftChanged = { value -> commentDraft = value.take(1000) },
            onSubmitComment = { submitPluginComment(summary) },
            onDeleteComment = { comment -> deletePluginComment(summary, comment) },
            onSubmitReply = { comment, content, onCompleted ->
                submitPluginReply(summary, comment, content, onCompleted)
            },
            onDelete = { deleteOwnedPlugin(summary) },
            onDismiss = {
                if (
                    installingRemoteId == null &&
                    deletingRemoteId == null &&
                    !updatingLike &&
                    !submittingComment &&
                    deletingCommentId == null
                ) {
                    selectedPlugin = null
                    detailPlugin = null
                    detailError = ""
                    historyVersions = emptyList()
                    historyError = ""
                    comments = emptyList()
                    commentsError = ""
                    currentUserIdentity = null
                    interactionError = ""
                    commentDraft = ""
                    installMessage = ""
                }
            }
        )
    }

    pendingOverwrite?.let { plugin ->
        WindowDialog(
            show = true,
            title = "覆盖本地插件",
            onDismissRequest = { pendingOverwrite = null },
            content = {
                Column {
                    Text(
                        text = "本地已存在目录“${PluginMarketInstaller.defaultLocalPluginId(plugin)}”。更新会覆盖 main.java、main.java.bshs、info.prop 和 README.md，并保留其它本地配置文件。更新后插件保持禁用。",
                        color = MiuixTheme.colorScheme.onSurface,
                        fontSize = 14.sp
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        TextButton(
                            text = "取消",
                            onClick = { pendingOverwrite = null },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                        TextButton(
                            text = "确认覆盖",
                            onClick = {
                                pendingOverwrite = null
                                performInstall(plugin, overwrite = true)
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                    }
                }
            }
        )
    }

    if (showUploadDialog) {
        PluginMarketUploadDialog(
            context = context,
            onDismiss = { uploaded ->
                showUploadDialog = false
                if (uploaded) refreshVersion++
            }
        )
    }

    if (showNotifications) {
        PluginMarketNotificationDialog(
            notifications = notifications,
            loading = notificationsLoading,
            error = notificationsError,
            onRetry = { notificationRefreshVersion++ },
            onNotificationClick = { notification ->
                showNotifications = false
                val target = plugins.firstOrNull {
                    it.remotePluginId == notification.remotePluginId
                } ?: PluginMarketPlugin(
                    remotePluginId = notification.remotePluginId,
                    sourcePluginId = "",
                    name = notification.pluginName,
                    author = "",
                    version = "",
                    versionId = "",
                    updateTime = "",
                    downloadCount = 0L,
                    likeCount = 0L,
                    commentCount = 0L,
                    description = "",
                    files = emptyList()
                )
                val activity = context as? Activity
                    ?: WeChatApis.currentActivity()?.currentActivity() as? Activity
                if (activity == null) {
                    selectedPlugin = target
                } else {
                    activity.window?.decorView?.postOnAnimation {
                        if (!activity.isFinishing && !activity.isDestroyed) selectedPlugin = target
                    }
                }
            },
            onDismiss = { showNotifications = false }
        )
    }
}

@Composable
private fun PluginMarketNotificationBell(
    unreadCount: Long,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier.size(40.dp).responsiveTap(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Image(
            imageVector = NavIcons.Bell,
            contentDescription = "回复通知",
            colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurface),
            modifier = Modifier.size(24.dp)
        )
        if (unreadCount > 0L) {
            Box(
                modifier = Modifier.align(Alignment.TopEnd)
                    .height(16.dp)
                    .sizeIn(minWidth = 16.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFD93025))
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (unreadCount > 99L) "99+" else unreadCount.toString(),
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun PluginMarketNotificationDialog(
    notifications: List<PluginMarketNotification>,
    loading: Boolean,
    error: String,
    onRetry: () -> Unit,
    onNotificationClick: (PluginMarketNotification) -> Unit,
    onDismiss: () -> Unit
) {
    WindowDialog(
        show = true,
        title = "回复通知",
        onDismissRequest = onDismiss,
        content = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "共 ${notifications.size} 条",
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 12.sp
                    )
                    TextButton(
                        text = if (loading) "正在刷新" else "刷新",
                        onClick = onRetry,
                        enabled = !loading,
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                    when {
                        loading && notifications.isEmpty() -> EmptyText("正在加载回复通知...")
                        error.isNotBlank() && notifications.isEmpty() -> {
                            EmptyText(error)
                            TextButton(
                                text = "重试",
                                onClick = onRetry,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.textButtonColorsPrimary()
                            )
                        }
                        notifications.isEmpty() -> EmptyText("暂无回复通知")
                        else -> notifications.forEachIndexed { index, notification ->
                            PluginMarketNotificationRow(
                                notification = notification,
                                onClick = { onNotificationClick(notification) }
                            )
                            if (index != notifications.lastIndex) Spacer(Modifier.height(8.dp))
                        }
                    }
                }
                TextButton(
                    text = "关闭",
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    )
}

@Composable
private fun PluginMarketNotificationRow(
    notification: PluginMarketNotification,
    onClick: () -> Unit
) {
    var pressed by remember(notification.notificationId) { mutableStateOf(false) }
    val feedbackColor = rememberPressFeedbackColor(pressed)
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (pressed) feedbackColor else MiuixTheme.colorScheme.secondaryVariant
            )
            .responsiveTap(onClick = onClick, onPressedChange = { pressed = it })
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text = when (notification.type) {
                "plugin_comment" -> "${notification.actorNickname.ifBlank { "微信用户" }} 评论了你的插件"
                "plugin_update" -> "你关注的插件发布了新版本"
                else -> "${notification.actorNickname.ifBlank { "微信用户" }} 回复了你的评论"
            },
            color = MiuixTheme.colorScheme.onSurface,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = notification.pluginName.ifBlank { notification.remotePluginId },
            color = MiuixTheme.colorScheme.primary,
            fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
        )
        Text(
            text = notification.content,
            color = MiuixTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth().padding(top = 7.dp)
        )
        Text(
            text = "原评论：${notification.originalContent}",
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(top = 5.dp)
        )
        Text(
            text = formatPluginMarketTime(notification.createdAt),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun PluginMarketListRow(
    plugin: PluginMarketPlugin,
    onClick: () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    val pressFeedbackColor = rememberPressFeedbackColor(pressed)
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(pressFeedbackColor)
            .responsiveTap(onClick = onClick, onPressedChange = { pressed = it })
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = plugin.name.ifBlank { plugin.remotePluginId },
                color = MiuixTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildString {
                    append("作者: ").append(plugin.author.ifBlank { "未知" })
                    append(" | 版本: ").append(plugin.version.ifBlank { "未知" })
                    append(" | 下载: ").append(plugin.downloadCount)
                    append("\n点赞: ").append(plugin.likeCount)
                    append(" | 评论: ").append(plugin.commentCount)
                    append("\n更新: ").append(formatPluginMarketTime(plugin.updateTime))
                },
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 12.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = "›",
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 22.sp,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@Composable
private fun PluginMarketDetailDialog(
    summary: PluginMarketPlugin,
    detail: PluginMarketPlugin?,
    loading: Boolean,
    error: String,
    historyVersions: List<PluginMarketHistoryVersion>,
    historyLoading: Boolean,
    historyError: String,
    historyInstallVersionId: String?,
    installed: Boolean,
    installing: Boolean,
    deleting: Boolean,
    owned: Boolean,
    installMessage: String,
    likedByCurrentUser: Boolean,
    followingByCurrentUser: Boolean,
    updatingLike: Boolean,
    updatingFollow: Boolean,
    comments: List<PluginMarketComment>,
    commentsLoading: Boolean,
    commentsError: String,
    currentUserWxId: String,
    interactionError: String,
    commentDraft: String,
    submittingComment: Boolean,
    deletingCommentId: String?,
    onRetry: () -> Unit,
    onRetryComments: () -> Unit,
    onInstall: (PluginMarketPlugin) -> Unit,
    onInstallHistory: (PluginMarketHistoryVersion) -> Unit,
    onToggleLike: () -> Unit,
    onToggleFollow: () -> Unit,
    onCommentDraftChanged: (String) -> Unit,
    onSubmitComment: () -> Unit,
    onDeleteComment: (PluginMarketComment) -> Unit,
    onSubmitReply: (PluginMarketComment, String, (Boolean) -> Unit) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var confirmDelete by remember(summary.remotePluginId) { mutableStateOf(false) }
    var pendingDeleteComment by remember(summary.remotePluginId) {
        mutableStateOf<PluginMarketComment?>(null)
    }
    var replyTarget by remember(summary.remotePluginId) {
        mutableStateOf<PluginMarketComment?>(null)
    }
    var replyDraft by remember(summary.remotePluginId) { mutableStateOf("") }
    WindowDialog(
        show = true,
        title = summary.name.ifBlank { "插件详情" },
        onDismissRequest = onDismiss,
        content = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).imePadding()) {
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                    val shownPlugin = detail ?: summary
                    PluginMarketDialogInfoRow("作者", shownPlugin.author.ifBlank { "未知" })
                    PluginMarketDialogInfoRow("版本", shownPlugin.version.ifBlank { "未知" })
                    PluginMarketDialogInfoRow("更新时间", formatPluginMarketTime(shownPlugin.updateTime))
                    PluginMarketDialogInfoRow("下载量", shownPlugin.downloadCount.toString())
                    PluginMarketDialogInfoRow("点赞数", shownPlugin.likeCount.toString())
                    PluginMarketDialogInfoRow("评论数", shownPlugin.commentCount.toString())
                    PluginMarketDialogInfoRow("插件 ID", summary.remotePluginId)
                    if (detail?.description?.isNotBlank() == true) {
                        Text(
                            text = detail.description,
                            color = MiuixTheme.colorScheme.onSurface,
                            fontSize = 14.sp,
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                        )
                    }
                    if (detail != null) {
                        Text(
                            text = "包含文件",
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                        Text(
                            text = detail.files.joinToString("、") { it.name }.ifBlank { "未知" },
                            color = MiuixTheme.colorScheme.onSurface,
                            fontSize = 13.sp,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        )
                        detail.files.firstOrNull { it.name.equals("README.md", ignoreCase = true) }
                            ?.content?.takeIf { it.isNotBlank() }
                            ?.let { readme ->
                                Text(
                                    text = "插件说明",
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(top = 12.dp)
                                )
                                Box(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                                    MarkdownUi.Content(
                                        context = context,
                                        markdown = readme,
                                        contentPadding = PaddingValues(0.dp)
                                    )
                                }
                            }
                    }
                    PluginMarketSocialSection(
                        likeCount = shownPlugin.likeCount,
                        commentCount = shownPlugin.commentCount,
                        likedByCurrentUser = likedByCurrentUser,
                        followingByCurrentUser = followingByCurrentUser,
                        updatingLike = updatingLike,
                        updatingFollow = updatingFollow,
                        comments = comments,
                        commentsLoading = commentsLoading,
                        commentsError = commentsError,
                        currentUserWxId = currentUserWxId,
                        interactionError = interactionError,
                        commentDraft = commentDraft,
                        submittingComment = submittingComment,
                        deletingCommentId = deletingCommentId,
                        enabled = !installing && !deleting,
                        onToggleLike = onToggleLike,
                        onToggleFollow = onToggleFollow,
                        onCommentDraftChanged = onCommentDraftChanged,
                        onSubmitComment = onSubmitComment,
                        onRetryComments = onRetryComments,
                        onDeleteComment = { pendingDeleteComment = it },
                        onReplyComment = { comment ->
                            replyTarget = comment
                            replyDraft = ""
                        }
                    )
                    PluginMarketHistoryUi.HistoryList(
                        versions = historyVersions,
                        loading = historyLoading,
                        error = historyError,
                        installingVersionId = historyInstallVersionId,
                        enabled = !installing,
                        onRetry = onRetry,
                        onInstall = onInstallHistory
                    )
                    Text(
                        text = "社区插件可能执行敏感操作，请确认来源可信。安装或更新完成后插件保持禁用。",
                        color = Color(0xFFD93025),
                        fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp)
                    )
                    when {
                        loading -> EmptyText("正在加载插件详情...")
                        error.isNotBlank() -> {
                            EmptyText(error)
                            TextButton(
                                text = "重新加载",
                                onClick = onRetry,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.textButtonColorsPrimary()
                            )
                        }
                    }
                    if (installMessage.isNotBlank()) {
                        Text(
                            text = installMessage,
                            color = if (installMessage.startsWith("安装失败")) {
                                Color(0xFFD93025)
                            } else {
                                MiuixTheme.colorScheme.primary
                            },
                            fontSize = 13.sp,
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }
                val activeReply = replyTarget
                if (activeReply != null) {
                    val focusRequester = remember(activeReply.commentId) { FocusRequester() }
                    val keyboardController = LocalSoftwareKeyboardController.current
                    LaunchedEffect(activeReply.commentId) {
                        focusRequester.requestFocus()
                        keyboardController?.show()
                    }
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MiuixTheme.colorScheme.secondaryVariant)
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = "回复 ${activeReply.userNickname.ifBlank { "微信用户" }}",
                            color = MiuixTheme.colorScheme.onSurface,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = activeReply.content.replace('\n', ' ').trim(),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
                        )
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(MiuixTheme.colorScheme.background)
                                .padding(horizontal = 10.dp, vertical = 9.dp)
                        ) {
                            if (replyDraft.isBlank()) {
                                Text(
                                    text = "回复 ${activeReply.userNickname.ifBlank { "微信用户" }}",
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    fontSize = 14.sp
                                )
                            }
                            BasicTextField(
                                value = replyDraft,
                                onValueChange = { replyDraft = it.take(1000) },
                                enabled = !submittingComment,
                                minLines = 1,
                                maxLines = 4,
                                textStyle = TextStyle(
                                    color = MiuixTheme.colorScheme.onSurface,
                                    fontSize = 14.sp
                                ),
                                cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${replyDraft.length}/1000",
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                fontSize = 11.sp
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(
                                    text = "取消",
                                    onClick = {
                                        replyTarget = null
                                        replyDraft = ""
                                    },
                                    enabled = !submittingComment,
                                    colors = ButtonDefaults.textButtonColorsPrimary()
                                )
                                TextButton(
                                    text = if (submittingComment) "正在回复" else "发送",
                                    onClick = {
                                        val content = replyDraft.trim()
                                        if (content.isNotBlank()) {
                                            onSubmitReply(activeReply, content) { succeeded ->
                                                if (succeeded) {
                                                    replyTarget = null
                                                    replyDraft = ""
                                                }
                                            }
                                        }
                                    },
                                    enabled = replyDraft.isNotBlank() && !submittingComment,
                                    colors = ButtonDefaults.textButtonColorsPrimary()
                                )
                            }
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        TextButton(
                            text = "关闭",
                            onClick = onDismiss,
                            enabled = !installing && !deleting && !updatingLike &&
                                !submittingComment && deletingCommentId == null,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                        if (owned) {
                            TextButton(
                                text = if (deleting) "正在删除" else "删除",
                                onClick = { confirmDelete = true },
                                enabled = !installing && !deleting && !updatingLike &&
                                    !submittingComment && deletingCommentId == null,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.textButtonColorsPrimary()
                            )
                        }
                        if (installing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                size = 18.dp
                            )
                        }
                        TextButton(
                            text = when {
                                installing -> "正在处理"
                                installed -> "更新"
                                else -> "安装"
                            },
                            onClick = { detail?.let(onInstall) },
                            enabled = detail != null && !loading && error.isBlank() && !installing &&
                                !deleting && !updatingLike && !submittingComment && deletingCommentId == null,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                    }
                }
            }
        }
    )
    if (confirmDelete) {
        WindowDialog(
            show = true,
            title = "删除线上插件",
            onDismissRequest = { confirmDelete = false },
            content = {
                Column {
                    Text(
                        text = "确定删除“${summary.name.ifBlank { summary.remotePluginId }}”吗？线上插件及全部历史版本都会被删除，本地插件不会删除。此操作无法撤销。",
                        color = MiuixTheme.colorScheme.onSurface,
                        fontSize = 14.sp
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        TextButton(
                            text = "取消",
                            onClick = { confirmDelete = false },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                        TextButton(
                            text = "确认删除",
                            onClick = {
                                confirmDelete = false
                                onDelete()
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                    }
                }
            }
        )
    }
    pendingDeleteComment?.let { comment ->
        WindowDialog(
            show = true,
            title = "删除评论",
            onDismissRequest = { pendingDeleteComment = null },
            content = {
                Column {
                    Text(
                        text = "确定删除这条评论吗？此操作无法撤销。",
                        color = MiuixTheme.colorScheme.onSurface,
                        fontSize = 14.sp
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        TextButton(
                            text = "取消",
                            onClick = { pendingDeleteComment = null },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                        TextButton(
                            text = "确认删除",
                            onClick = {
                                pendingDeleteComment = null
                                onDeleteComment(comment)
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
private fun PluginMarketSocialSection(
    likeCount: Long,
    commentCount: Long,
    likedByCurrentUser: Boolean,
    followingByCurrentUser: Boolean,
    updatingLike: Boolean,
    updatingFollow: Boolean,
    comments: List<PluginMarketComment>,
    commentsLoading: Boolean,
    commentsError: String,
    currentUserWxId: String,
    interactionError: String,
    commentDraft: String,
    submittingComment: Boolean,
    deletingCommentId: String?,
    enabled: Boolean,
    onToggleLike: () -> Unit,
    onToggleFollow: () -> Unit,
    onCommentDraftChanged: (String) -> Unit,
    onSubmitComment: () -> Unit,
    onRetryComments: () -> Unit,
    onDeleteComment: (PluginMarketComment) -> Unit,
    onReplyComment: (PluginMarketComment) -> Unit
) {
    Text(
        text = "互动",
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        fontSize = 12.sp,
        modifier = Modifier.padding(top = 16.dp)
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(
            text = when {
                updatingLike -> "正在处理"
                likedByCurrentUser -> "取消点赞"
                else -> "点赞"
            },
            onClick = onToggleLike,
            enabled = enabled && currentUserWxId.isNotBlank() && !updatingLike,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.textButtonColorsPrimary()
        )
        TextButton(
            text = when {
                updatingFollow -> "正在处理"
                followingByCurrentUser -> "取消关注"
                else -> "关注插件"
            },
            onClick = onToggleFollow,
            enabled = enabled && currentUserWxId.isNotBlank() && !updatingFollow,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.textButtonColorsPrimary()
        )
        Text(
            text = "$likeCount 个赞 · $commentCount 条评论",
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 13.sp,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
    Text(
        text = "发表评论",
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        fontSize = 12.sp,
        modifier = Modifier.padding(top = 10.dp)
    )
    BasicTextField(
        value = commentDraft,
        onValueChange = onCommentDraftChanged,
        enabled = enabled && currentUserWxId.isNotBlank() && !submittingComment,
        minLines = 2,
        maxLines = 4,
        textStyle = TextStyle(color = MiuixTheme.colorScheme.onSurface, fontSize = 14.sp),
        cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
        decorationBox = { innerTextField ->
            Box(contentAlignment = Alignment.TopStart) {
                if (commentDraft.isBlank()) {
                    Text(
                        text = if (currentUserWxId.isBlank()) "当前微信账号资料尚未就绪" else "填写评论内容",
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 14.sp
                    )
                }
                innerTextField()
            }
        },
        modifier = Modifier.fillMaxWidth().padding(top = 5.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MiuixTheme.colorScheme.secondaryVariant)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "${commentDraft.length}/1000",
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 12.sp
        )
        TextButton(
            text = if (submittingComment) "正在发表" else "发表",
            onClick = onSubmitComment,
            enabled = enabled && currentUserWxId.isNotBlank() &&
                commentDraft.isNotBlank() && !submittingComment,
            colors = ButtonDefaults.textButtonColorsPrimary()
        )
    }
    if (interactionError.isNotBlank()) {
        Text(
            text = interactionError,
            color = Color(0xFFD93025),
            fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        )
    }
    Text(
        text = "评论 ($commentCount)",
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        fontSize = 12.sp,
        modifier = Modifier.padding(top = 10.dp)
    )
    val commentThreads = remember(comments) { buildPluginMarketCommentThreads(comments) }
    when {
        commentsLoading -> EmptyText("正在加载评论...")
        commentsError.isNotBlank() -> {
            EmptyText(commentsError)
            TextButton(
                text = "重试",
                onClick = onRetryComments,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary()
            )
        }
        comments.isEmpty() -> EmptyText("暂无评论")
        else -> commentThreads.forEachIndexed { index, thread ->
            PluginMarketCommentRow(
                comment = thread.root,
                deleting = deletingCommentId == thread.root.commentId,
                enabled = enabled && deletingCommentId == null,
                onDelete = { onDeleteComment(thread.root) },
                onReply = { onReplyComment(thread.root) }
            )
            if (thread.replies.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(start = 14.dp, bottom = 8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MiuixTheme.colorScheme.secondaryVariant)
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                ) {
                    thread.replies.forEachIndexed { replyIndex, reply ->
                        PluginMarketReplyRow(
                            comment = reply,
                            deleting = deletingCommentId == reply.commentId,
                            enabled = enabled && deletingCommentId == null,
                            onDelete = { onDeleteComment(reply) },
                            onReply = { onReplyComment(reply) }
                        )
                        if (replyIndex != thread.replies.lastIndex) InsetDivider()
                    }
                }
            }
            if (index != commentThreads.lastIndex) InsetDivider()
        }
    }
}

@Composable
private fun PluginMarketCommentRow(
    comment: PluginMarketComment,
    deleting: Boolean,
    enabled: Boolean,
    onDelete: () -> Unit,
    onReply: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(
            modifier = Modifier.weight(1f)
                .clip(RoundedCornerShape(6.dp))
                .clickable(enabled = enabled, onClick = onReply)
                .padding(vertical = 7.dp)
        ) {
            Text(
                text = comment.userNickname.ifBlank { "微信用户" },
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = formatPluginMarketTime(comment.createdAt),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 11.sp
            )
            Text(
                text = comment.content,
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )
        }
        if (comment.canDelete) {
            TextButton(
                text = if (deleting) "正在删除" else "删除",
                onClick = onDelete,
                enabled = enabled && !deleting,
                colors = ButtonDefaults.textButtonColorsPrimary()
            )
        }
    }
}

@Composable
private fun PluginMarketReplyRow(
    comment: PluginMarketComment,
    deleting: Boolean,
    enabled: Boolean,
    onDelete: () -> Unit,
    onReply: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(
            modifier = Modifier.weight(1f)
                .clip(RoundedCornerShape(4.dp))
                .clickable(enabled = enabled, onClick = onReply)
                .padding(vertical = 6.dp)
        ) {
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Medium)) {
                        append(comment.userNickname.ifBlank { "微信用户" })
                    }
                    append(" 回复 ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Medium)) {
                        append(comment.replyToNickname.ifBlank { "微信用户" })
                    }
                    append("：")
                    append(comment.content)
                },
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = formatPluginMarketTime(comment.createdAt),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        if (comment.canDelete) {
            TextButton(
                text = if (deleting) "正在删除" else "删除",
                onClick = onDelete,
                enabled = enabled && !deleting,
                colors = ButtonDefaults.textButtonColorsPrimary()
            )
        }
    }
}

private data class PluginMarketCommentThread(
    val root: PluginMarketComment,
    val replies: List<PluginMarketComment>
)

private fun buildPluginMarketCommentThreads(
    comments: List<PluginMarketComment>
): List<PluginMarketCommentThread> {
    val commentsById = comments.associateBy { it.commentId }
    val roots = comments.filter { comment ->
        comment.parentCommentId.isBlank() || comment.parentCommentId !in commentsById
    }.sortedWith(
        compareByDescending<PluginMarketComment> { it.createdAt }
            .thenByDescending { it.commentId }
    )
    val repliesByRoot = linkedMapOf<String, MutableList<PluginMarketComment>>()
    comments.filter { it.parentCommentId.isNotBlank() }.forEach { comment ->
        var cursor = comment
        val visited = mutableSetOf(comment.commentId)
        while (cursor.parentCommentId.isNotBlank()) {
            val parent = commentsById[cursor.parentCommentId] ?: break
            if (!visited.add(parent.commentId)) break
            cursor = parent
        }
        if (cursor.commentId != comment.commentId) {
            repliesByRoot.getOrPut(cursor.commentId) { mutableListOf() }.add(comment)
        }
    }
    return roots.map { root ->
        PluginMarketCommentThread(
            root = root,
            replies = repliesByRoot[root.commentId].orEmpty().sortedWith(
                compareBy<PluginMarketComment> { it.createdAt }.thenBy { it.commentId }
            )
        )
    }
}

@Composable
private fun PluginMarketDialogInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 13.sp
        )
        Text(
            text = value,
            color = MiuixTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f).padding(start = 16.dp)
        )
    }
}

@Composable
private fun PluginMarketUploadDialog(
    context: Context,
    onDismiss: (Boolean) -> Unit
) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf("") }
    var localPlugins by remember { mutableStateOf<List<ScriptPluginRuntime.ScriptPlugin>>(emptyList()) }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var remoteNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var releaseNotes by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var extraFiles by remember { mutableStateOf<Map<String, List<PluginMarketFile>>>(emptyMap()) }
    var statuses by remember { mutableStateOf<Map<String, PluginMarketUploadStatus>>(emptyMap()) }
    var uploading by remember { mutableStateOf(false) }
    var uploadedAny by remember { mutableStateOf(false) }
    var reloadVersion by remember { mutableStateOf(0) }

    LaunchedEffect(reloadVersion) {
        loading = true
        loadError = ""
        val result = runCatching {
            withContext(Dispatchers.IO) { ScriptPluginRuntime.listPlugins(context) }
        }
        result.fold(
            onSuccess = { plugins ->
                localPlugins = plugins
                remoteNames = plugins.associate { plugin ->
                    plugin.id to (plugin.displayName ?: plugin.name).ifBlank { plugin.id }
                }
                releaseNotes = plugins.associate { plugin ->
                    plugin.id to releaseNotes[plugin.id].orEmpty()
                }
                extraFiles = extraFiles.filterKeys { id -> plugins.any { it.id == id } }
                selectedIds = selectedIds.intersect(plugins.mapTo(LinkedHashSet()) { it.id })
            },
            onFailure = { error -> loadError = pluginMarketErrorMessage(error) }
        )
        loading = false
    }

    val normalizedQuery = query.trim().lowercase(Locale.US)
    val filteredPlugins = localPlugins.filter { plugin ->
        normalizedQuery.isEmpty() ||
            plugin.id.lowercase(Locale.US).contains(normalizedQuery) ||
            plugin.name.lowercase(Locale.US).contains(normalizedQuery) ||
            plugin.author.lowercase(Locale.US).contains(normalizedQuery)
    }
    val allFilteredSelected = filteredPlugins.isNotEmpty() && filteredPlugins.all { it.id in selectedIds }

    fun startUpload() {
        if (uploading || selectedIds.isEmpty()) return
        val ordered = localPlugins.filter { it.id in selectedIds }
        if (ordered.any { remoteNames[it.id].orEmpty().trim().isBlank() }) {
            Toast.makeText(context, "在线插件名不能为空", Toast.LENGTH_SHORT).show()
            return
        }
        statuses = ordered.associate { plugin ->
            plugin.id to PluginMarketUploadStatus(PluginMarketUploadPhase.QUEUED, "等待上传")
        }
        uploading = true
        scope.launch {
            var publishedCount = 0
            var pendingCount = 0
            ordered.forEach { plugin ->
                statuses = statuses + (
                    plugin.id to PluginMarketUploadStatus(PluginMarketUploadPhase.UPLOADING, "正在上传")
                )
                val result = withContext(Dispatchers.IO) {
                    PluginMarketRepository.upload(
                        context = context,
                        localPluginId = plugin.id,
                        remoteName = remoteNames[plugin.id].orEmpty().trim(),
                        releaseNotes = releaseNotes[plugin.id].orEmpty().trim(),
                        extraFiles = extraFiles[plugin.id].orEmpty()
                    )
                }
                result.fold(
                    onSuccess = { ownership ->
                        uploadedAny = true
                        when (ownership.reviewStatus) {
                            PluginMarketReviewStatus.PENDING -> {
                                pendingCount++
                                statuses = statuses + (
                                    plugin.id to PluginMarketUploadStatus(
                                        PluginMarketUploadPhase.PENDING_REVIEW,
                                        "上传成功，待审核 · ${ownership.remotePluginId}"
                                    )
                                )
                            }
                            PluginMarketReviewStatus.APPROVED -> {
                                publishedCount++
                                statuses = statuses + (
                                    plugin.id to PluginMarketUploadStatus(
                                        PluginMarketUploadPhase.SUCCESS,
                                        "上传成功 · ${ownership.remotePluginId}"
                                    )
                                )
                            }
                        }
                    },
                    onFailure = { error ->
                        statuses = statuses + (
                            plugin.id to PluginMarketUploadStatus(
                                PluginMarketUploadPhase.FAILED,
                                "上传失败: ${pluginMarketErrorMessage(error)}"
                            )
                        )
                    }
                )
            }
            uploading = false
            Toast.makeText(
                context,
                "上传完成：已发布 $publishedCount，待审核 $pendingCount，失败 ${ordered.size - publishedCount - pendingCount}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    WindowDialog(
        show = true,
        title = "上传本地插件",
        onDismissRequest = { if (!uploading) onDismiss(uploadedAny) },
        content = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 620.dp)) {
                SearchBarSurface(
                    query = query,
                    placeholder = "搜索本地插件",
                    onQueryChange = { if (!uploading) query = it }
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "已选 ${selectedIds.size} / ${localPlugins.size}",
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = if (allFilteredSelected) "取消当前筛选" else "全选当前筛选",
                        color = if (filteredPlugins.isEmpty() || uploading) {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary
                        } else {
                            MiuixTheme.colorScheme.primary
                        },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickable(enabled = filteredPlugins.isNotEmpty() && !uploading) {
                            val filteredIds = filteredPlugins.mapTo(LinkedHashSet()) { it.id }
                            selectedIds = if (allFilteredSelected) {
                                selectedIds - filteredIds
                            } else {
                                selectedIds + filteredIds
                            }
                        }.padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    when {
                        loading -> item { EmptyText("正在读取本地插件...") }
                        loadError.isNotBlank() -> item {
                            Column {
                                EmptyText(loadError)
                                TextButton(
                                    text = "重试",
                                    onClick = { reloadVersion++ },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.textButtonColorsPrimary()
                                )
                            }
                        }
                        localPlugins.isEmpty() -> item { EmptyText("没有可运行的本地插件") }
                        filteredPlugins.isEmpty() -> item { EmptyText("没有匹配的本地插件") }
                        else -> items(filteredPlugins.size, key = { filteredPlugins[it].id }) { index ->
                            val plugin = filteredPlugins[index]
                            if (index > 0) InsetDivider(start = 0.dp)
                            PluginMarketUploadRow(
                                plugin = plugin,
                                selected = plugin.id in selectedIds,
                                remoteName = remoteNames[plugin.id].orEmpty(),
                                releaseNotes = releaseNotes[plugin.id].orEmpty(),
                                extraFiles = extraFiles[plugin.id].orEmpty(),
                                status = statuses[plugin.id],
                                enabled = !uploading,
                                onSelectedChange = { selected ->
                                    selectedIds = if (selected) selectedIds + plugin.id else selectedIds - plugin.id
                                },
                                onRemoteNameChange = { name ->
                                    remoteNames = remoteNames + (plugin.id to name.take(100))
                                },
                                onReleaseNotesChange = { notes ->
                                    releaseNotes = releaseNotes + (plugin.id to notes.take(500))
                                },
                                onPickExtraFiles = {
                                    context.findActivity()?.let { activity ->
                                        PluginMarketExtraFilePickerBridge.launch(activity) { files ->
                                            val existing = extraFiles[plugin.id].orEmpty()
                                            val merged = (files + existing).distinctBy {
                                                it.name.lowercase(Locale.ROOT)
                                            }
                                            extraFiles = extraFiles + (plugin.id to merged)
                                        }
                                    } ?: Toast.makeText(
                                        context,
                                        "无法打开文件选择器",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                                onClearExtraFiles = {
                                    extraFiles = extraFiles - plugin.id
                                }
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TextButton(
                        text = "关闭",
                        onClick = { onDismiss(uploadedAny) },
                        enabled = !uploading,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                    TextButton(
                        text = if (uploading) "正在上传" else "上传已选",
                        onClick = ::startUpload,
                        enabled = !uploading && selectedIds.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        }
    )
}

@Composable
private fun PluginMarketUploadRow(
    plugin: ScriptPluginRuntime.ScriptPlugin,
    selected: Boolean,
    remoteName: String,
    releaseNotes: String,
    extraFiles: List<PluginMarketFile>,
    status: PluginMarketUploadStatus?,
    enabled: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    onRemoteNameChange: (String) -> Unit,
    onReleaseNotesChange: (String) -> Unit,
    onPickExtraFiles: () -> Unit,
    onClearExtraFiles: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { onSelectedChange(!selected) }
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = plugin.name.ifBlank { plugin.id },
                    color = MiuixTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        append(plugin.id)
                        append(" · ").append(plugin.author.ifBlank { "未知作者" })
                        append(" · ").append(plugin.version.ifBlank { "未知版本" })
                    },
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            SelectionMark(selected = selected, multiSelect = true)
        }
        if (selected) {
            Text(
                text = "在线名称",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
            )
            BasicTextField(
                value = remoteName,
                onValueChange = onRemoteNameChange,
                enabled = enabled,
                singleLine = true,
                textStyle = TextStyle(color = MiuixTheme.colorScheme.onSurface, fontSize = 14.sp),
                cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth().padding(top = 5.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MiuixTheme.colorScheme.secondaryVariant)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
            Text(
                text = "更新说明",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp)
            )
            BasicTextField(
                value = releaseNotes,
                onValueChange = onReleaseNotesChange,
                enabled = enabled,
                minLines = 2,
                maxLines = 3,
                textStyle = TextStyle(color = MiuixTheme.colorScheme.onSurface, fontSize = 14.sp),
                cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.TopStart) {
                        if (releaseNotes.isBlank()) {
                            Text(
                                text = "填写本次版本的更新内容",
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                fontSize = 14.sp
                            )
                        }
                        innerTextField()
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 5.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MiuixTheme.colorScheme.secondaryVariant)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "附加文件${if (extraFiles.isEmpty()) "" else " (${extraFiles.size})"}",
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 12.sp
                    )
                    if (extraFiles.isNotEmpty()) {
                        Text(
                            text = extraFiles.joinToString("、") { it.name },
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
                TextButton(
                    text = "选择",
                    onClick = onPickExtraFiles,
                    enabled = enabled,
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
                if (extraFiles.isNotEmpty()) {
                    TextButton(
                        text = "清空",
                        onClick = onClearExtraFiles,
                        enabled = enabled,
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        }
        if (status != null) {
            val color = when (status.phase) {
                PluginMarketUploadPhase.SUCCESS -> MiuixTheme.colorScheme.primary
                PluginMarketUploadPhase.PENDING_REVIEW -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                PluginMarketUploadPhase.FAILED -> Color(0xFFD93025)
                else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
            }
            Text(
                text = status.message,
                color = color,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 6.dp)
            )
        }
    }
}

private fun pluginMarketErrorMessage(error: Throwable): String {
    if (error is PluginMarketException && error.errorCode == "UPLOADER_BLACKLISTED") {
        return "当前微信账号已被禁止上传在线插件"
    }
    return error.message?.trim().takeUnless { it.isNullOrBlank() } ?: error.javaClass.simpleName
}

}

private object PluginMarketHistoryUi {
    @Composable
    fun HistoryList(
        versions: List<PluginMarketHistoryVersion>,
        loading: Boolean,
        error: String,
        installingVersionId: String?,
        enabled: Boolean,
        onRetry: () -> Unit,
        onInstall: (PluginMarketHistoryVersion) -> Unit
    ) {
        Text(
            text = "历史版本",
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 14.dp)
        )
        when {
            loading -> EmptyText("正在加载历史版本...")
            error.isNotBlank() -> {
                EmptyText(error)
                TextButton(
                    text = "重新加载",
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
            versions.isEmpty() -> EmptyText("暂无历史版本")
            else -> versions.forEachIndexed { index, version ->
                if (index > 0) InsetDivider(start = 0.dp)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = buildString {
                                append(version.versionName.ifBlank { "未命名版本" })
                                if (index == 0) append("（最新）")
                            },
                            color = MiuixTheme.colorScheme.onSurface,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "${formatPluginMarketTime(version.createdAt)} · ${formatBytes(version.totalSize)}",
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "更新说明：${version.releaseNotes.ifBlank { "暂无更新说明" }}",
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 3.dp)
                        )
                    }
                    TextButton(
                        text = if (installingVersionId == version.versionId) "读取中" else "安装",
                        onClick = { onInstall(version) },
                        enabled = enabled && installingVersionId == null,
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        }
    }

    private fun formatBytes(size: Long): String = when {
        size >= 1024L * 1024L -> String.format(Locale.US, "%.1f MiB", size / (1024.0 * 1024.0))
        size >= 1024L -> String.format(Locale.US, "%.1f KiB", size / 1024.0)
        else -> "$size B"
    }
}

private val pluginMarketBeijingTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
        .withZone(ZoneId.of("Asia/Shanghai"))

private fun formatPluginMarketTime(value: String): String {
    val source = value.trim()
    if (source.isEmpty()) return "时间未知"
    val instant = runCatching { Instant.parse(source) }
        .recoverCatching { OffsetDateTime.parse(source, DateTimeFormatter.ISO_DATE_TIME).toInstant() }
        .recoverCatching {
            LocalDateTime.parse(source, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                .toInstant(ZoneOffset.UTC)
        }
        .getOrNull()
        ?: return source
    return pluginMarketBeijingTimeFormatter.format(instant)
}

/** WCX 入口：脚本 Tab 内容（Hchat 脚本页主体 + WCX 本地组件）。 */
@Composable
fun ScriptPluginMiuixTabContent(
    context: Context,
    onOpenManager: () -> Unit = {},
    onOpenMarket: () -> Unit = {},
    onOpenReadme: (ScriptPluginRuntime.ScriptPlugin) -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
    onOpenFaq: () -> Unit = {},
) {
    ScriptPluginSettingsMiuixContent.ScriptPluginSettingsContent(
        context = context,
        onOpenReadme = onOpenReadme,
        onOpenMarket = onOpenMarket,
        onOpenManager = onOpenManager,
        onOpenDiagnostics = onOpenDiagnostics,
        onOpenFaq = onOpenFaq,
    )
}

/** WCX 入口：脚本管理页（Tab 内导航）。 */
@Composable
fun ScriptPluginManagerTabPage(context: Context, onBack: () -> Unit) {
    ScriptPluginSettingsMiuixContent.ScriptPluginManagerPage(context = context, onBack = onBack)
}

/** WCX 入口：脚本 README 弹窗（自包含实现，不依赖页面内部的私有 composable）。 */
@Composable
fun ScriptPluginReadmeTabDialog(
    context: Context,
    plugin: ScriptPluginRuntime.ScriptPlugin,
    onClose: () -> Unit,
) {
    val readme = remember(plugin.id) {
        runCatching {
            val file = File(plugin.dir, "README.md")
            if (file.isFile) file.readText(Charsets.UTF_8) else ""
        }.getOrDefault("")
    }
    Dialog(onDismissRequest = onClose) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MiuixTheme.colorScheme.surfaceContainer)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                text = plugin.displayName ?: plugin.name,
                fontSize = MiuixTheme.textStyles.headline1.fontSize,
                fontWeight = FontWeight.Medium,
                color = BasicComponentDefaults.titleColor().color,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = readme.ifBlank { "暂无说明" },
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = BasicComponentDefaults.summaryColor().color,
            )
            Spacer(Modifier.height(12.dp))
            TextButton(
                text = "关闭",
                onClick = onClose,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}