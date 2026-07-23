package com.Johnny.wcx.features.items.chat.panel

import kotlinx.serialization.Serializable

@Serializable
internal data class PanelCustomOrders(
    val packs: List<String> = emptyList(),
    val items: Map<String, List<String>> = emptyMap(),
)

internal fun customOrderIndex(order: List<String>?, key: String): Int {
    val index = order?.indexOf(key) ?: -1
    return if (index >= 0) index else Int.MAX_VALUE
}

internal fun normalizedCustomOrder(requested: List<String>, available: Collection<String>): List<String> {
    val availableSet = available.toHashSet()
    val retained = requested.distinct().filter { it in availableSet }
    val retainedSet = retained.toHashSet()
    return retained + available.filterNot { it in retainedSet }
}
