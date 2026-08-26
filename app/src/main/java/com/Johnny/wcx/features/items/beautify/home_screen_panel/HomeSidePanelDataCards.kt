package com.Johnny.wcx.features.items.beautify.home_screen_panel

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Air
import com.composables.icons.materialsymbols.outlined.Cloud
import com.composables.icons.materialsymbols.outlined.Cloudy_snowing
import com.composables.icons.materialsymbols.outlined.Cyclone
import com.composables.icons.materialsymbols.outlined.Device_thermostat
import com.composables.icons.materialsymbols.outlined.Foggy
import com.composables.icons.materialsymbols.outlined.Format_quote
import com.composables.icons.materialsymbols.outlined.Grain
import com.composables.icons.materialsymbols.outlined.Humidity_percentage
import com.composables.icons.materialsymbols.outlined.Location_on
import com.composables.icons.materialsymbols.outlined.Partly_cloudy_day
import com.composables.icons.materialsymbols.outlined.Qr_code_scanner
import com.composables.icons.materialsymbols.outlined.Question_mark
import com.composables.icons.materialsymbols.outlined.Rainy
import com.composables.icons.materialsymbols.outlined.Rainy_heavy
import com.composables.icons.materialsymbols.outlined.Rainy_light
import com.composables.icons.materialsymbols.outlined.Rainy_snow
import com.composables.icons.materialsymbols.outlined.Snowing
import com.composables.icons.materialsymbols.outlined.Snowing_heavy
import com.composables.icons.materialsymbols.outlined.Storm
import com.composables.icons.materialsymbols.outlined.Sunny
import com.composables.icons.materialsymbols.outlined.Sunny_snowing
import com.composables.icons.materialsymbols.outlined.Thunderstorm
import com.composables.icons.materialsymbols.outlined.Tornado
import com.composables.icons.materialsymbols.outlined.Wallet
import com.composables.icons.materialsymbols.outlined.Weather_hail
import com.composables.icons.materialsymbols.outlined.Weather_snowy

import com.Johnny.wcx.features.items.beautify.resolveBeautifyText

import kotlinx.coroutines.delay
import java.time.Duration
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

internal fun weatherCardSnapshot(state: WeatherUiState): WeatherSnapshot? = when (state) {
    is WeatherUiState.Ready -> state.snapshot
    is WeatherUiState.Error -> state.cached
    WeatherUiState.Loading -> null
}

@Composable
internal fun HomeSidePanelDateTimeCard(
    card: DateTimeCardConfig,
    content: DateTimeCardContent,
    editMode: Boolean,
    modifier: Modifier = Modifier,
    cardDragModifier: Modifier = Modifier,
    onEditCard: ((String) -> Unit)? = null,
    onDeleteCard: ((String) -> Unit)? = null,
) {
    val now = when (content) {
        DateTimeCardContent.Runtime -> rememberHomeSidePanelNow()
        is DateTimeCardContent.Preview -> content.now
    }
    val localizedContext = androidx.compose.ui.platform.LocalContext.current
    val dateText = now.format(
        DateTimeFormatter.ofPattern(
            "M月d日 E",
            localizedContext.resources.configuration.locales[0],
        ),
    )
    val lunarDate = if (card.showLunarCalendar) {
        remember(now.toLocalDate()) { homeSidePanelLunarDate(now) }
    } else {
        null
    }
    val lunarText = lunarDate?.let {
        formatHomeSidePanelLunarDate(
            date = it,
            text = HomeSidePanelLunarDateText(
                prefix = "农历",
                leapPrefix = "闰",
                separator = "、",
                monthNames = stringArrayResource(com.Johnny.wcx.R.array.home_side_panel_lunar_month_names).asList(),
                dayNames = stringArrayResource(com.Johnny.wcx.R.array.home_side_panel_lunar_day_names).asList(),
            ),
        )
    }
    HomeSidePanelCardFrame(
        cardId = card.id,
        modifier = modifier.fillMaxWidth(),
        cardModifier = Modifier
            .fillMaxWidth()
            .then(if (editMode) cardDragModifier else Modifier),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        editMode = editMode,
        onEdit = onEditCard?.let { edit -> { edit(card.id) } },
        onDelete = onDeleteCard?.let { delete -> { delete(card.id) } },
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
                Text(
                    now.format(HOME_SIDE_PANEL_TIME_FORMATTER),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    buildString {
                        append(dateText)
                        lunarText?.let { append(" · ").append(it) }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 10.dp, bottom = 5.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(greetingResForHour(now.hour), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
internal fun HomeSidePanelWeatherCard(
    card: WeatherCardConfig,
    content: WeatherCardContent,
    editMode: Boolean,
    modifier: Modifier = Modifier,
    cardDragModifier: Modifier = Modifier,
    interactionEnabled: Boolean = true,
    onRefresh: (String) -> Unit = {},
    onEditCard: ((String) -> Unit)? = null,
    onDeleteCard: ((String) -> Unit)? = null,
) {
    val localizedContext = androidx.compose.ui.platform.LocalContext.current
    val runtime = content as? WeatherCardContent.Runtime
    val weather = runtime?.state
    val snapshot = when (content) {
        is WeatherCardContent.Runtime -> weatherCardSnapshot(content.state)
        is WeatherCardContent.Preview -> content.snapshot
    }
    val shape = RoundedCornerShape(24.dp)
    val clickModifier = if (interactionEnabled && !editMode && runtime != null) {
        Modifier.clickable { onRefresh(card.id) }
    } else {
        Modifier
    }
    HomeSidePanelCardFrame(
        cardId = card.id,
        modifier = modifier.fillMaxWidth(),
        cardModifier = Modifier
            .fillMaxWidth()
            .then(if (editMode) cardDragModifier else Modifier)
            .then(clickModifier),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        editMode = editMode,
        onEdit = onEditCard?.let { edit -> { edit(card.id) } },
        onDelete = onDeleteCard?.let { delete -> { delete(card.id) } },
    ) {
        val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        val location = snapshot?.city?.let { city ->
            listOfNotNull(city.city, city.district?.takeIf(String::isNotBlank))
                .distinct()
                .joinToString(" · ")
        } ?: "天气"
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, top = 17.dp, end = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    MaterialSymbols.Outlined.Location_on,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = contentColor,
                )
                Text(
                    location,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 5.dp),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                snapshot?.let {
                    Text(
                        "更新于 ".format(formatWeatherPublishedAt(it.publishedAt)),
                        modifier = Modifier
                            .padding(start = 10.dp)
                            .widthIn(max = 112.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.65f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (weather is WeatherUiState.Error) {
                Text(
                    text = localizedContext.resolveBeautifyText(weather.message),
                    modifier = Modifier.padding(start = 18.dp, top = 5.dp, end = 18.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp)
                            .padding(horizontal = 18.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (snapshot != null) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "${snapshot.temperature}°",
                                        style = MaterialTheme.typography.displayLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = contentColor,
                                        maxLines = 1,
                                    )
                                    Text(
                                        "体感 ".format(snapshot.feelsLike),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = contentColor.copy(alpha = 0.72f),
                                    )
                                }
                                Column(
                                    modifier = Modifier.widthIn(min = 96.dp, max = 120.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Icon(
                                        weatherIcon(snapshot.weatherCode),
                                        contentDescription = null,
                                        modifier = Modifier.size(52.dp),
                                        tint = contentColor,
                                    )
                                    Text(
                                        weatherDescriptionRes(snapshot.weatherCode),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Medium,
                                        color = contentColor,
                                        textAlign = TextAlign.Center,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        } else if (weather is WeatherUiState.Loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                color = contentColor,
                                strokeWidth = 3.dp,
                            )
                        } else {
                            Text(
                                "暂无天气数据，点击卡片重试",
                                style = MaterialTheme.typography.bodyMedium,
                                color = contentColor,
                            )
                        }
                    }
                    HorizontalDivider(color = contentColor.copy(alpha = 0.14f))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(68.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        HomeSidePanelWeatherMetric(
                            icon = MaterialSymbols.Outlined.Device_thermostat,
                            value = snapshot?.let { "${it.high}° / ${it.low}°" } ?: "-- / --",
                            label = "最高 / 最低",
                            modifier = Modifier.weight(1f),
                        )
                        VerticalDivider(
                            modifier = Modifier
                                .fillMaxHeight()
                                .padding(vertical = 10.dp),
                            color = contentColor.copy(alpha = 0.12f),
                        )
                        HomeSidePanelWeatherMetric(
                            icon = MaterialSymbols.Outlined.Humidity_percentage,
                            value = snapshot?.let { "${it.humidity}%" } ?: "--",
                            label = "湿度",
                            modifier = Modifier.weight(1f),
                        )
                        VerticalDivider(
                            modifier = Modifier
                                .fillMaxHeight()
                                .padding(vertical = 10.dp),
                            color = contentColor.copy(alpha = 0.12f),
                        )
                        HomeSidePanelWeatherMetric(
                            icon = MaterialSymbols.Outlined.Air,
                            value = snapshot?.let { "${it.windSpeed} km/h" } ?: "--",
                            label = "风速",
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (weather is WeatherUiState.Ready && weather.refreshing) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.82f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            color = contentColor,
                            strokeWidth = 3.dp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun HomeSidePanelWalletCard(
    card: WalletCardConfig,
    content: WalletCardContent,
    editMode: Boolean,
    modifier: Modifier = Modifier,
    cardDragModifier: Modifier = Modifier,
    interactionEnabled: Boolean = true,
    onToggleBalance: (String) -> Unit = {},
    onRunAction: (HomeSidePanelActionKind) -> Unit = {},
    onOpenPaymentCode: () -> Unit = {},
    onEditCard: ((String) -> Unit)? = null,
    onDeleteCard: ((String) -> Unit)? = null,
) {
    val runtime = content as? WalletCardContent.Runtime
    val displayBalance = when (content) {
        is WalletCardContent.Runtime -> content.state.displayBalance
        is WalletCardContent.Preview -> content.displayBalance
    }
    val isMasked = runtime?.state?.displayState?.isMasked == true
    val interactive = interactionEnabled && !editMode && runtime != null
    val clickModifier = if (interactive) {
        Modifier.clickable { onToggleBalance(card.id) }
    } else {
        Modifier
    }
    HomeSidePanelCardFrame(
        cardId = card.id,
        modifier = modifier.fillMaxWidth(),
        cardModifier = Modifier
            .fillMaxWidth()
            .then(if (editMode) cardDragModifier else Modifier)
            .then(clickModifier),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        editMode = editMode,
        onEdit = onEditCard?.let { edit -> { edit(card.id) } },
        onDelete = onDeleteCard?.let { delete -> { delete(card.id) } },
    ) {
        val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    MaterialSymbols.Outlined.Wallet,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = contentColor,
                )
                Text(
                    "当前余额",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor,
                )
            }
            Text(
                text = displayBalance,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = if (isMasked) 4.sp else 0.sp,
                color = contentColor,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        if (interactive) onRunAction(HomeSidePanelActionKind.SCAN)
                    },
                    modifier = Modifier.weight(1f),
                    enabled = interactive,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary,
                        disabledContentColor = MaterialTheme.colorScheme.primary,
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
                ) {
                    Icon(MaterialSymbols.Outlined.Qr_code_scanner, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("扫一扫", modifier = Modifier.padding(start = 7.dp), maxLines = 1)
                }
                Button(
                    onClick = {
                        if (interactive) onOpenPaymentCode()
                    },
                    modifier = Modifier.weight(1f),
                    enabled = interactive,
                    colors = ButtonDefaults.buttonColors(
                        disabledContainerColor = MaterialTheme.colorScheme.primary,
                        disabledContentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
                ) {
                    Icon(MaterialSymbols.Outlined.Wallet, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("付款码", modifier = Modifier.padding(start = 7.dp), maxLines = 1)
                }
            }
        }
    }
}

@Composable
internal fun HomeSidePanelHitokotoCard(
    card: HitokotoCardConfig,
    content: HitokotoCardContent,
    editMode: Boolean,
    modifier: Modifier = Modifier,
    cardDragModifier: Modifier = Modifier,
    interactionEnabled: Boolean = true,
    onRefresh: (String) -> Unit = {},
    onEditCard: ((String) -> Unit)? = null,
    onDeleteCard: ((String) -> Unit)? = null,
) {
    val localizedContext = androidx.compose.ui.platform.LocalContext.current
    val runtime = content as? HitokotoCardContent.Runtime
    val hitokoto = runtime?.state
    val snapshot = when (content) {
        is HitokotoCardContent.Runtime -> when (val state = content.state) {
            is HitokotoUiState.Ready -> state.snapshot
            is HitokotoUiState.Error -> state.cached
            HitokotoUiState.Loading -> null
        }

        is HitokotoCardContent.Preview -> content.snapshot
    }
    val clickModifier = if (interactionEnabled && !editMode && runtime != null) {
        Modifier.clickable { onRefresh(card.id) }
    } else {
        Modifier
    }
    HomeSidePanelCardFrame(
        cardId = card.id,
        modifier = modifier.fillMaxWidth(),
        cardModifier = Modifier
            .fillMaxWidth()
            .then(if (editMode) cardDragModifier else Modifier)
            .then(clickModifier),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        editMode = editMode,
        onEdit = onEditCard?.let { edit -> { edit(card.id) } },
        onDelete = onDeleteCard?.let { delete -> { delete(card.id) } },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(MaterialSymbols.Outlined.Format_quote, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    "一言",
                    modifier = Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Box(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(
                        text = snapshot?.text ?: if (hitokoto is HitokotoUiState.Loading) {
                            "一言加载中…"
                        } else {
                            "点击卡片获取一言"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (snapshot != null && (card.settings.showSource || card.settings.showAuthor)) {
                        val author = snapshot.author?.trim()?.takeIf { card.settings.showAuthor && it.isNotEmpty() }
                        val source = snapshot.source?.trim()?.takeIf { card.settings.showSource && it.isNotEmpty() }
                        val attribution = when {
                            author != null && source != null ->
                                "—— %1\$s「%2\$s」".format(author, source)

                            author != null -> "—— ".format(author)
                            source != null -> "——「".format(source)
                            else -> null
                        }
                        attribution?.let {
                            Text(
                                text = it,
                                modifier = Modifier.fillMaxWidth(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.End,
                            )
                        }
                    }
                }
                val refreshing = hitokoto is HitokotoUiState.Loading ||
                    hitokoto is HitokotoUiState.Ready && hitokoto.refreshing
                if (refreshing) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.82f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            color = MaterialTheme.colorScheme.onSurface,
                            strokeWidth = 3.dp,
                        )
                    }
                }
            }
            if (hitokoto is HitokotoUiState.Error) {
                Text(
                    text = localizedContext.resolveBeautifyText(hitokoto.message),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun HomeSidePanelWeatherMetric(
    icon: ImageVector,
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    Row(
        modifier = modifier.padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = contentColor)
        Column(modifier = Modifier.padding(start = 6.dp)) {
            Text(
                value,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.68f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun rememberHomeSidePanelNow(): LocalDateTime {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            val current = LocalDateTime.now()
            now = current
            val nextMinute = current.plusMinutes(1).withSecond(0).withNano(0)
            delay(Duration.between(current, nextMinute).toMillis().coerceAtLeast(1L))
        }
    }
    return now
}

private fun greetingResForHour(hour: Int): String = when (hour) {
    in 5..11 -> "早上好，今天也要保持好心情。"
    in 12..17 -> "下午好，愿今天一切顺利。"
    else -> "晚上好，愿你今晚安心入睡。"
}

private fun weatherIcon(code: String): ImageVector = when (weatherIconKind(code)) {
    WeatherIconKind.SUNNY -> MaterialSymbols.Outlined.Sunny
    WeatherIconKind.PARTLY_CLOUDY -> MaterialSymbols.Outlined.Partly_cloudy_day
    WeatherIconKind.OVERCAST -> MaterialSymbols.Outlined.Cloud
    WeatherIconKind.SHOWER -> MaterialSymbols.Outlined.Rainy
    WeatherIconKind.THUNDERSTORM -> MaterialSymbols.Outlined.Thunderstorm
    WeatherIconKind.HAIL -> MaterialSymbols.Outlined.Weather_hail
    WeatherIconKind.SLEET -> MaterialSymbols.Outlined.Rainy_snow
    WeatherIconKind.LIGHT_RAIN -> MaterialSymbols.Outlined.Rainy_light
    WeatherIconKind.RAIN -> MaterialSymbols.Outlined.Rainy
    WeatherIconKind.HEAVY_RAIN -> MaterialSymbols.Outlined.Rainy_heavy
    WeatherIconKind.RAINSTORM -> MaterialSymbols.Outlined.Storm
    WeatherIconKind.SNOW_SHOWER -> MaterialSymbols.Outlined.Sunny_snowing
    WeatherIconKind.LIGHT_SNOW -> MaterialSymbols.Outlined.Snowing
    WeatherIconKind.SNOW -> MaterialSymbols.Outlined.Weather_snowy
    WeatherIconKind.HEAVY_SNOW -> MaterialSymbols.Outlined.Snowing_heavy
    WeatherIconKind.BLIZZARD -> MaterialSymbols.Outlined.Cloudy_snowing
    WeatherIconKind.FOG -> MaterialSymbols.Outlined.Foggy
    WeatherIconKind.FREEZING_RAIN -> MaterialSymbols.Outlined.Rainy_snow
    WeatherIconKind.DUST_STORM -> MaterialSymbols.Outlined.Storm
    WeatherIconKind.DUST -> MaterialSymbols.Outlined.Grain
    WeatherIconKind.SAND -> MaterialSymbols.Outlined.Grain
    WeatherIconKind.SQUALL -> MaterialSymbols.Outlined.Cyclone
    WeatherIconKind.TORNADO -> MaterialSymbols.Outlined.Tornado
    WeatherIconKind.HAZE -> MaterialSymbols.Outlined.Air
    WeatherIconKind.UNKNOWN -> MaterialSymbols.Outlined.Question_mark
}

private fun weatherDescriptionRes(code: String): String = when (code.toIntOrNull()) {
    0 -> "晴"
    1 -> "多云"
    2 -> "阴"
    3 -> "阵雨"
    4 -> "雷阵雨"
    5 -> "雷阵雨并伴有冰雹"
    6 -> "雨夹雪"
    7 -> "小雨"
    8 -> "中雨"
    9 -> "大雨"
    10 -> "暴雨"
    11 -> "大暴雨"
    12 -> "特大暴雨"
    13 -> "阵雪"
    14 -> "小雪"
    15 -> "中雪"
    16 -> "大雪"
    17 -> "暴雪"
    18 -> "雾"
    19 -> "冻雨"
    20 -> "沙尘暴"
    21 -> "小雨-中雨"
    22 -> "中雨-大雨"
    23 -> "大雨-暴雨"
    24 -> "暴雨-大暴雨"
    25 -> "大暴雨-特大暴雨"
    26 -> "小雪-中雪"
    27 -> "中雪-大雪"
    28 -> "大雪-暴雪"
    29 -> "浮尘"
    30 -> "扬沙"
    31 -> "强沙尘暴"
    32 -> "飑"
    33 -> "龙卷风"
    34 -> "吹雪"
    35 -> "轻雾"
    53 -> "霾"
    else -> "未知"
}

private fun formatWeatherPublishedAt(publishedAt: String): String = runCatching {
    OffsetDateTime.parse(publishedAt).format(HOME_SIDE_PANEL_TIME_FORMATTER)
}.getOrDefault(publishedAt)

private val HOME_SIDE_PANEL_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm")
