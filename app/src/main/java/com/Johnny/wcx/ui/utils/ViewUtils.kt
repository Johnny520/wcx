@file:Suppress("NOTHING_TO_INLINE")

package com.Johnny.wcx.ui.utils

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ListAdapter

/**
 * Lazily traverses the View hierarchy using a Pre-order Depth-First Search (DFS).
 * Uses an iterative stack to avoid the performance penalty of recursive yieldAll.
 */
val View.allViews: Sequence<View>
    get() = sequence {
        val stack = mutableListOf(this@allViews)
        while (stack.isNotEmpty()) {
            val current = stack.removeAt(stack.lastIndex)
            yield(current)
            if (current is ViewGroup) {
                // Push children in reverse order to maintain standard left-to-right DFS
                for (i in current.childCount - 1 downTo 0) {
                    stack.add(current.getChildAt(i))
                }
            }
        }
    }

fun <T : View> View.findViewsByClassName(className: String): Sequence<T> {
    return allViews
        .filter { it.javaClass.name == className || it.javaClass.simpleName == className }
        .map { @Suppress("UNCHECKED_CAST") (it as T) }
}

fun <T : View> View.findViewByClassName(className: String): T? {
    return findViewsByClassName<T>(className).firstOrNull()
}

fun <T : View> View?.findViewsWhich(predicate: (View) -> Boolean): Sequence<T> {
    if (this == null) return emptySequence()
    return this.allViews
        .filter(predicate)
        .map { @Suppress("UNCHECKED_CAST") (it as T) }
}

fun <T : View> View?.findViewWhich(predicate: (View) -> Boolean): T? {
    return findViewsWhich<T>(predicate).firstOrNull()
}

fun <T : View> View.findViewByChildIndexes(vararg indexes: Int): T? {
    var current: View = this
    for (index in indexes) {
        current = (current as? ViewGroup)?.getChildAt(index) ?: return null
    }
    @Suppress("UNCHECKED_CAST")
    return current as? T
}

fun ListAdapter.iterator(parent: ViewGroup): Iterator<View> =
    object : Iterator<View> {

        private var index = 0
        override fun hasNext() = index < count
        override fun next(): View {
            index++
            return getView(index, null, parent)
        }
    }

fun ListAdapter.iterable(parent: ViewGroup): Iterable<View> =
    Iterable { iterator(parent) }

inline val Activity.rootView: ViewGroup
    get() = findViewById(android.R.id.content)

inline fun Int.dpToPx(context: Context): Int =
    (this * context.resources.displayMetrics.density).toInt()

val View.idString
    get() = if (this.id != View.NO_ID) {
        runCatching { this.resources.getResourceEntryName(this.id) }.getOrDefault(null)
    } else null

fun View.removeSelf() {
    (parent as? ViewGroup)?.removeView(this)
}
