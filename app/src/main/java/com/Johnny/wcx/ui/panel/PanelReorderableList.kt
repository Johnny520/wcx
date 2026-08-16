package com.Johnny.wcx.ui.panel

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun <T> PanelReorderableList(
    items: List<T>,
    itemKey: (T) -> Any,
    onMove: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier,
    itemContent: @Composable (item: T, dragHandleModifier: Modifier) -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val hapticFeedback = LocalHapticFeedback.current
    var draggingKey by remember { mutableStateOf<Any?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }

    LazyColumn(
        state = listState,
        modifier = modifier,
        userScrollEnabled = draggingKey == null,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(items, key = { _, item -> itemKey(item) }) { _, item ->
            val key = itemKey(item)
            val dragging = draggingKey == key
            val dragHandle = Modifier.pointerInput(key) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        if (listState.layoutInfo.visibleItemsInfo.any { it.key == key }) {
                            draggingKey = key
                            dragOffset = 0f
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.ContextClick)
                        }
                    },
                    onDragCancel = {
                        draggingKey = null
                        dragOffset = 0f
                    },
                    onDragEnd = {
                        draggingKey = null
                        dragOffset = 0f
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        if (draggingKey != key) return@detectDragGesturesAfterLongPress
                        dragOffset += amount.y
                        val current = listState.layoutInfo.visibleItemsInfo
                            .firstOrNull { it.key == key }
                            ?: return@detectDragGesturesAfterLongPress
                        val start = current.offset + dragOffset
                        val end = start + current.size
                        val target = listState.layoutInfo.visibleItemsInfo.firstOrNull { candidate ->
                            when {
                                candidate.index == current.index -> false
                                dragOffset > 0f -> candidate.index > current.index &&
                                        end > candidate.offset + candidate.size / 2

                                else -> candidate.index < current.index &&
                                        start < candidate.offset + candidate.size / 2
                            }
                        }
                        if (target != null) {
                            onMove(current.index, target.index)
                            dragOffset -= target.offset - current.offset
                        }

                        val viewport = listState.layoutInfo
                        val center = current.offset + dragOffset + current.size / 2
                        when {
                            center < viewport.viewportStartOffset + 56 && listState.canScrollBackward ->
                                scope.launch { listState.scrollBy(-12f) }

                            center > viewport.viewportEndOffset - 56 && listState.canScrollForward ->
                                scope.launch { listState.scrollBy(12f) }
                        }
                    },
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer {
                        translationY = if (dragging) dragOffset else 0f
                        scaleX = if (dragging) 1.02f else 1f
                        scaleY = if (dragging) 1.02f else 1f
                        shadowElevation = if (dragging) 8.dp.toPx() else 0f
                    }
                    .then(if (dragging) Modifier else Modifier.animateItem()),
            ) {
                itemContent(item, dragHandle)
            }
        }
    }
}
