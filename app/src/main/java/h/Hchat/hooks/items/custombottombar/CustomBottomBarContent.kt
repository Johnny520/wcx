package h.Hchat.hooks.items.custombottombar

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import h.Hchat.ui.miuix.FloatingBottomBar
import h.Hchat.ui.miuix.FloatingBottomBarItem
import h.Hchat.ui.miuix.rememberViewBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

internal data class CustomBottomBarConfig(
    val glass: Boolean,
    val blurRadius: Int,
    val hideLabels: Boolean,
    val showBadges: Boolean,
    val vibrationEnabled: Boolean,
    val vibrationStrength: Int
)

internal class FloatingBottomBarTouchState {
    @Volatile
    private var bounds: BottomBarBounds? = null

    fun update(left: Float, top: Float, right: Float, bottom: Float) {
        bounds = BottomBarBounds(left, top, right, bottom)
    }

    fun snapshot(): BottomBarBounds? = bounds
}

internal data class BottomBarBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

@Stable
internal class CustomBottomBarUiState(
    selectedIndex: Int,
    mainUnread: Int,
    contactUnread: Int,
    discoveryUnread: Int,
    showDiscoveryDot: Boolean,
    settingsUnread: Int,
    showSettingsDot: Boolean
) {
    var selectedIndex by mutableIntStateOf(selectedIndex)
    var mainUnread by mutableIntStateOf(mainUnread)
    var contactUnread by mutableIntStateOf(contactUnread)
    var discoveryUnread by mutableIntStateOf(discoveryUnread)
    var showDiscoveryDot by mutableStateOf(showDiscoveryDot)
    var settingsUnread by mutableIntStateOf(settingsUnread)
    var showSettingsDot by mutableStateOf(showSettingsDot)
}

@Composable
internal fun CustomBottomBarRoot(
    activity: Activity,
    sourceView: View,
    state: CustomBottomBarUiState,
    config: CustomBottomBarConfig,
    touchState: FloatingBottomBarTouchState,
    onTabClicked: (Int) -> Unit
) {
    val dark = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    MiuixTheme(
        colors = if (dark) darkColorScheme() else lightColorScheme(),
        content = {
            FloatingNavigationBar(
                sourceView = sourceView,
                state = state,
                config = config,
                touchState = touchState,
                onTabClicked = onTabClicked
            )
        }
    )
}

@Composable
private fun FloatingNavigationBar(
    sourceView: View,
    state: CustomBottomBarUiState,
    config: CustomBottomBarConfig,
    touchState: FloatingBottomBarTouchState,
    onTabClicked: (Int) -> Unit
) {
    val backdrop = rememberViewBackdrop(sourceView)
    val contentColor = if (isDarkMode()) Color(0xFFE8E8E8) else Color(0xFF202020)
    val selectedColor = MiuixTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = 12.dp,
                end = 12.dp,
                bottom = 12.dp + WindowInsets.navigationBars.asPaddingValues()
                    .calculateBottomPadding()
            ),
        contentAlignment = Alignment.BottomCenter
    ) {
        FloatingBottomBar(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .onGloballyPositioned { coordinates ->
                    val position = coordinates.positionInWindow()
                    touchState.update(
                        left = position.x,
                        top = position.y,
                        right = position.x + coordinates.size.width,
                        bottom = position.y + coordinates.size.height
                    )
                },
            selectedIndex = state.selectedIndex,
            onSelected = onTabClicked,
            backdrop = backdrop,
            tabsCount = NAV_ITEMS.size,
            isBlurEnabled = config.glass,
            blurRadius = config.blurRadius.dp,
            floating = true,
            overlayContent = {
                NAV_ITEMS.indices.forEach { index ->
                    BottomBarBadgeOverlay(
                        badge = badgeFor(index, state, config),
                        showLabelSlot = !config.hideLabels
                    )
                }
            }
        ) { selectTab ->
            NAV_ITEMS.forEachIndexed { index, item ->
                val selected = state.selectedIndex == index
                FloatingBottomBarItem(
                    onClick = { selectTab(index) },
                    modifier = Modifier.defaultMinSize(minWidth = 76.dp)
                ) {
                    BottomBarIcon(
                        item = item,
                        selected = selected,
                        selectedColor = selectedColor
                    )
                    if (!config.hideLabels) {
                        BasicText(
                            text = item.label,
                            style = TextStyle(
                                color = if (selected) selectedColor else contentColor,
                                fontSize = 11.sp,
                                lineHeight = 14.sp,
                                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                                textAlign = TextAlign.Center
                            ),
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomBarIcon(
    item: BottomBarItem,
    selected: Boolean,
    selectedColor: Color
) {
    Box(modifier = Modifier.size(width = 42.dp, height = 30.dp), contentAlignment = Alignment.Center) {
        Image(
            imageVector = if (selected) item.selectedIcon else item.normalIcon,
            contentDescription = item.label,
            colorFilter = ColorFilter.tint(if (selected) selectedColor else WX_ICON_GRAY),
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun RowScope.BottomBarBadgeOverlay(
    badge: BottomBarBadge,
    showLabelSlot: Boolean
) {
    Column(
        modifier = Modifier
            .defaultMinSize(minWidth = 76.dp)
            .fillMaxHeight()
            .weight(1f),
        verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(modifier = Modifier.size(width = 42.dp, height = 30.dp)) {
            when {
                badge.count > 0 -> CountBadge(badge.count, Modifier.align(Alignment.TopEnd))
                badge.dot -> Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 1.dp, end = 2.dp)
                        .size(8.dp)
                        .background(BADGE_RED, CircleShape)
                )
            }
        }
        if (showLabelSlot) Spacer(Modifier.height(14.dp))
    }
}

@Composable
private fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    val text = if (count > 99) "99+" else count.toString()
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 17.dp, minHeight = 17.dp)
            .background(BADGE_RED, CircleShape)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = text,
            style = TextStyle(
                color = Color.White,
                fontSize = 9.sp,
                lineHeight = 11.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            ),
            maxLines = 1
        )
    }
}

private fun badgeFor(
    index: Int,
    state: CustomBottomBarUiState,
    config: CustomBottomBarConfig
): BottomBarBadge {
    if (!config.showBadges) return BottomBarBadge()
    return when (index) {
    0 -> BottomBarBadge(count = state.mainUnread.coerceAtLeast(0))
    1 -> BottomBarBadge(count = state.contactUnread.coerceAtLeast(0))
    2 -> BottomBarBadge(
        count = state.discoveryUnread.coerceAtLeast(0),
        dot = state.discoveryUnread <= 0 && state.showDiscoveryDot
    )
    3 -> BottomBarBadge(
        count = state.settingsUnread.coerceAtLeast(0),
        dot = state.settingsUnread <= 0 && state.showSettingsDot
    )
    else -> BottomBarBadge()
    }
}

@Composable
private fun isDarkMode(): Boolean =
    (androidx.compose.ui.platform.LocalConfiguration.current.uiMode and
        Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

internal fun performBottomBarHaptic(view: View, enabled: Boolean, strength: Int) {
    if (!enabled) return
    val normalized = FloatingBottomBarSettings.normalizeVibrationStrength(strength)
    if (normalized == FloatingBottomBarSettings.MAX_VIBRATION_STRENGTH &&
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    ) {
        return
    }
    val vibrated = runCatching {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            (view.context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
        }
        if (vibrator?.hasVibrator() != true) return@runCatching false
        val amplitudeControlled = vibrator.hasAmplitudeControl()
        val amplitude = if (amplitudeControlled) {
            1 + (normalized - FloatingBottomBarSettings.MIN_VIBRATION_STRENGTH) * 254 /
                (FloatingBottomBarSettings.MAX_VIBRATION_STRENGTH -
                    FloatingBottomBarSettings.MIN_VIBRATION_STRENGTH)
        } else {
            VibrationEffect.DEFAULT_AMPLITUDE
        }
        val duration = if (amplitudeControlled) {
            HAPTIC_DURATION_MS
        } else {
            HAPTIC_FALLBACK_MIN_DURATION_MS +
                (normalized - FloatingBottomBarSettings.MIN_VIBRATION_STRENGTH) *
                (HAPTIC_FALLBACK_MAX_DURATION_MS - HAPTIC_FALLBACK_MIN_DURATION_MS) /
                (FloatingBottomBarSettings.MAX_VIBRATION_STRENGTH -
                    FloatingBottomBarSettings.MIN_VIBRATION_STRENGTH)
        }
        vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude))
        true
    }.getOrDefault(false)
    if (!vibrated) {
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }
}

private const val HAPTIC_DURATION_MS = 18L
private const val HAPTIC_FALLBACK_MIN_DURATION_MS = 8L
private const val HAPTIC_FALLBACK_MAX_DURATION_MS = 30L

private data class BottomBarBadge(val count: Int = 0, val dot: Boolean = false)

private data class BottomBarItem(
    val label: String,
    val normalIcon: ImageVector,
    val selectedIcon: ImageVector
)

private val NAV_ITEMS = listOf(
    BottomBarItem(
        "微信",
        bottomBarIcon(
            "WeChatNormal",
            "M26.002359,54.803513 L27.321718,55.185577 C30.085625,55.985954 33.006626,56.400002 36,56.400002 C50.698292,56.400002 62.400002,46.453548 62.400002,34.5 C62.400002,22.546453 50.698292,12.6 36,12.6 C21.30171,12.6 9.6,22.546453 9.6,34.5 C9.6,40.727772 12.770791,46.578289 18.301785,50.734089 L19.953779,51.975342 L19.248581,58.042023 Z M36,60 C32.613049,60 29.357191,59.522919 26.320362,58.643509 L17.371504,62.934578 C17.116058,63.057068 16.831148,63.104713 16.549749,63.072006 C15.726863,62.976353 15.137323,62.231728 15.232977,61.40884 L16.139269,53.612202 C9.920994,48.939999 6,42.109215 6,34.5 C6,20.416739 19.431458,9 36,9 C52.568542,9 66,20.416739 66,34.5 C66,48.58326 52.568542,60 36,60 Z",
            selected = false
        ),
        bottomBarIcon(
            "WeChatSelected",
            "M36,60 C32.613049,60 29.357191,59.522919 26.320362,58.643509 L17.371504,62.934578 C17.116058,63.057068 16.831148,63.104713 16.549749,63.072006 C15.726863,62.976353 15.137323,62.231728 15.232977,61.40884 L16.139269,53.612202 C9.920994,48.939999 6,42.109215 6,34.5 C6,20.416739 19.431458,9 36,9 C52.568542,9 66,20.416739 66,34.5 C66,48.58326 52.568542,60 36,60 Z",
            selected = true
        )
    ),
    BottomBarItem(
        "通讯录",
        bottomBarIcon(
            "ContactsNormal",
            "M56.400002,59.400002 L56.400002,57.946388 C56.400002,57.258179 55.694038,56.130058 55.07943,55.830261 L38.09837,47.547119 C33.518623,45.313179 32.386719,39.908741 35.668011,36.019104 L36.752609,34.733425 C38.406586,32.772804 39.900002,28.693172 39.900002,26.130558 L39.900002,21.000622 C39.900002,16.364742 36.136768,12.6 31.5,12.6 C26.868927,12.6 23.1,16.365608 23.1,20.998741 L23.1,26.127872 C23.1,28.696991 24.58724,32.761452 26.247236,34.728935 L27.331833,36.014439 C30.619909,39.911579 29.475643,45.310951 24.902159,47.542759 L7.921099,55.82933 C7.311051,56.127026 6.6,57.266029 6.6,57.946388 L6.6,59.400002 Z M3,60 L3,57.946388 C3,55.891125 4.494453,53.495724 6.342293,52.593998 L23.323353,44.30743 C25.786131,43.105621 26.358778,40.443787 24.580336,38.335918 L23.495739,37.050415 C21.288954,34.434856 19.5,29.548489 19.5,26.127872 L19.5,20.998741 C19.5,14.37202 24.886068,9 31.5,9 C38.127419,9 43.5,14.378941 43.5,21.000622 L43.5,26.130558 C43.5,29.547888 41.702797,34.448582 39.504261,37.054718 L38.419662,38.340397 C36.651096,40.436852 37.203564,43.105194 39.676647,44.311531 L56.657707,52.594673 C58.503605,53.495079 60,55.875511 60,57.946388 L60,60 C60,61.656853 58.656853,63 57,63 L6,63 C4.343146,63 3,61.656853 3,60 Z M60,43.5 L69,43.5 L69,47.099998 L60,47.099998 Z M54,34.5 L69,34.5 L69,38.099998 L54,38.099998 Z M48,25.5 L69,25.5 L69,29.1 L48,29.1 Z",
            selected = false
        ),
        bottomBarIcon(
            "ContactsSelected",
            "M3,60 L3,57.946388 C3,55.891125 4.494453,53.495724 6.342293,52.593998 L23.323353,44.30743 C25.786131,43.105621 26.358778,40.443787 24.580336,38.335918 L23.495739,37.050415 C21.288954,34.434856 19.5,29.548489 19.5,26.127872 L19.5,20.998741 C19.5,14.37202 24.886068,9 31.5,9 C38.127419,9 43.5,14.378941 43.5,21.000622 L43.5,26.130558 C43.5,29.547888 41.702797,34.448582 39.504261,37.054718 L38.419662,38.340397 C36.651096,40.436852 37.203564,43.105194 39.676647,44.311531 L56.657707,52.594673 C58.503605,53.495079 60,55.875511 60,57.946388 L60,60 C60,61.656853 58.656853,63 57,63 L6,63 C4.343146,63 3,61.656853 3,60 Z M60,43.5 L69,43.5 L69,47.099998 L60,47.099998 Z M54,34.5 L69,34.5 L69,38.099998 L54,38.099998 Z M48,25.5 L69,25.5 L69,29.1 L48,29.1 Z",
            selected = true
        )
    ),
    BottomBarItem(
        "发现",
        bottomBarIcon(
            "DiscoverNormal",
            "M36,62.400002 C50.580318,62.400002 62.400002,50.580318 62.400002,36 C62.400002,21.419683 50.580318,9.6 36,9.6 C21.419683,9.6 9.6,21.419683 9.6,36 C9.6,50.580318 21.419683,62.400002 36,62.400002 Z M36,66 C19.431458,66 6,52.568542 6,36 C6,19.431458 19.431458,6 36,6 C52.568542,6 66,19.431458 66,36 C66,52.568542 52.568542,66 36,66 Z M33.370068,33.370068 L27.802084,44.197914 L38.629932,38.629932 L44.197914,27.802084 Z M30.696699,30.696699 L48.373051,21.607025 C48.803566,21.385643 49.314457,21.385643 49.744972,21.607025 C50.481701,21.985872 50.77182,22.890223 50.392975,23.626949 L41.303303,41.303303 L23.626949,50.392975 C23.196436,50.614357 22.685541,50.614357 22.255028,50.392975 C21.518301,50.01413 21.22818,49.109779 21.607025,48.373051 Z",
            selected = false
        ),
        bottomBarIcon(
            "DiscoverSelected",
            "M36,66 C19.431458,66 6,52.568542 6,36 C6,19.431458 19.431458,6 36,6 C52.568542,6 66,19.431458 66,36 C66,52.568542 52.568542,66 36,66 Z M30.696699,30.696699 L21.607025,48.373051 C21.22818,49.109779 21.518301,50.01413 22.255028,50.392975 C22.685541,50.614357 23.196436,50.614357 23.626949,50.392975 L41.303303,41.303303 L50.392975,23.626949 C50.77182,22.890223 50.481701,21.985872 49.744972,21.607025 C49.314457,21.385643 48.803566,21.385643 48.373051,21.607025 Z",
            selected = true
        )
    ),
    BottomBarItem(
        "我",
        bottomBarIcon(
            "MeNormal",
            "M60.900002,59.400002 L60.900002,57.946388 C60.900002,57.258179 60.194038,56.130058 59.57943,55.830261 L42.59837,47.547119 C38.018623,45.313179 36.886719,39.908741 40.168011,36.019104 L41.252609,34.733425 C42.906586,32.772804 44.400002,28.693172 44.400002,26.130558 L44.400002,21.000622 C44.400002,16.364742 40.636768,12.6 36,12.6 C31.368927,12.6 27.6,16.365608 27.6,20.998741 L27.6,26.127872 C27.6,28.696991 29.08724,32.761452 30.747236,34.728935 L31.831833,36.014439 C35.119907,39.911579 33.975643,45.310951 29.402159,47.542759 L12.421099,55.82933 C11.811051,56.127026 11.1,57.266029 11.1,57.946388 L11.1,59.400002 Z M7.5,60 L7.5,57.946388 C7.5,55.891125 8.994453,53.495724 10.842293,52.593998 L27.823353,44.30743 C30.286131,43.105621 30.858778,40.443787 29.080336,38.335918 L27.995739,37.050415 C25.788954,34.434856 24,29.548489 24,26.127872 L24,20.998741 C24,14.37202 29.386068,9 36,9 C42.627419,9 48,14.378941 48,21.000622 L48,26.130558 C48,29.547888 46.202797,34.448582 44.004261,37.054718 L42.919662,38.340397 C41.151096,40.436852 41.703564,43.105194 44.176647,44.311531 L61.157707,52.594673 C63.003605,53.495079 64.5,55.875511 64.5,57.946388 L64.5,60 C64.5,61.656853 63.156853,63 61.5,63 L10.5,63 C8.843145,63 7.5,61.656853 7.5,60 Z",
            selected = false
        ),
        bottomBarIcon(
            "MeSelected",
            "M7.5,60 L7.5,57.946388 C7.5,55.891125 8.994453,53.495724 10.842293,52.593998 L27.823353,44.30743 C30.286131,43.105621 30.858778,40.443787 29.080336,38.335918 L27.995739,37.050415 C25.788954,34.434856 24,29.548489 24,26.127872 L24,20.998741 C24,14.37202 29.386068,9 36,9 C42.627419,9 48,14.378941 48,21.000622 L48,26.130558 C48,29.547888 46.202797,34.448582 44.004261,37.054718 L42.919662,38.340397 C41.151096,40.436852 41.703564,43.105194 44.176647,44.311531 L61.157707,52.594673 C63.003605,53.495079 64.5,55.875511 64.5,57.946388 L64.5,60 C64.5,61.656853 63.156853,63 61.5,63 L10.5,63 C8.843145,63 7.5,61.656853 7.5,60 Z",
            selected = true
        )
    )
)

private fun bottomBarIcon(
    name: String,
    path: String,
    selected: Boolean
): ImageVector = ImageVector.Builder(
    name = "Hchat.BottomBar.$name",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 72f,
    viewportHeight = 72f
).addPath(
    pathData = addPathNodes(path),
    fill = SolidColor(Color.Black),
    pathFillType = if (selected) PathFillType.NonZero else PathFillType.EvenOdd
).build()

private val BADGE_RED = Color(0xFFFF3B30)
private val WX_ICON_GRAY = Color(0xFF8A8A8A)
