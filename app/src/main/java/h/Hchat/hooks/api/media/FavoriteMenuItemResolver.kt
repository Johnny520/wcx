package h.Hchat.hooks.api.media

import android.content.Context
import android.content.ContextWrapper
import android.view.ContextMenu
import android.view.View
import android.widget.HeaderViewListAdapter
import android.widget.ListView
import h.Hchat.utils.KavaReflector
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap

object FavoriteMenuItemResolver {
    private const val FAVORITE_UI_PREFIX = "com.tencent.mm.plugin.fav.ui."
    private const val FAVORITE_ADAPTER_PREFIX = "com.tencent.mm.plugin.fav.ui.adapter."
    private const val MAX_DEPTH = 4

    @JvmStatic
    fun resolve(source: Any?): Any? {
        return resolve(
            source,
            Collections.newSetFromMap(WeakHashMap<Any, Boolean>()),
            0
        )
    }

    @JvmStatic
    fun resolveMenuItem(source: Any?): Any? {
        return resolve(
            source,
            Collections.newSetFromMap(WeakHashMap<Any, Boolean>()),
            0,
            true
        )
    }

    @JvmStatic
    fun localId(favorite: Any?): Long {
        favorite ?: return 0L
        for (name in arrayOf("field_localId", "localId", "id")) {
            (KavaReflector.readField(favorite, name) as? Number)?.toLong()
                ?.takeIf { it > 0L }
                ?.let { return it }
        }
        return 0L
    }

    @JvmStatic
    fun resolveAdapterItem(adapter: Any?, position: Int): Any? {
        if (adapter == null || position < 0) return null
        return resolveFromAdapter(adapter, linkedSetOf(position))
    }

    private fun resolve(
        source: Any?,
        visited: MutableSet<Any>,
        depth: Int,
        menuContext: Boolean = false
    ): Any? {
        if (source == null || depth > MAX_DEPTH || !visited.add(source)) return null
        if (isFavoriteItem(source)) return source
        if (source is Array<*>) {
            source.forEach { resolve(it, visited, depth + 1, menuContext)?.let { item -> return item } }
            return null
        }
        if (source is Collection<*>) {
            source.forEach { resolve(it, visited, depth + 1, menuContext)?.let { item -> return item } }
            return null
        }
        if (menuContext && source is ContextMenu.ContextMenuInfo) {
            resolveFromContextMenuInfo(source, visited, depth)?.let { return it }
            return null
        }
        if (source is View) {
            resolve(source.tag, visited, depth + 1, menuContext)?.let { return it }
            return if (menuContext) resolveFromView(source, null) else null
        }
        val className = source.javaClass.name
        if (className.startsWith("java.") || className.startsWith("android.")) return null

        resolveFromFavoriteContextListener(source, visited, depth, menuContext)?.let { return it }
        readFromAdapterListener(source)?.let { return it }
        var current: Class<*>? = source.javaClass
        while (current != null && current != Any::class.java) {
            for (field in KavaReflector.declaredFields(current)) {
                if (field.type.isPrimitive || field.type == String::class.java) continue
                val value = KavaReflector.readField(field, source) ?: continue
                if (isFavoriteItem(value)) return value
                resolve(value, visited, depth + 1, menuContext)?.let { return it }
            }
            current = current.superclass
        }
        return readFromUiFields(source)
    }

    private fun resolveFromFavoriteContextListener(
        listener: Any,
        visited: MutableSet<Any>,
        depth: Int,
        menuContext: Boolean
    ): Any? {
        if (!menuContext) return null
        val className = listener.javaClass.name
        if (!className.startsWith(FAVORITE_UI_PREFIX)) return null

        val listenerValues = hierarchyFields(listener).mapNotNull { field ->
            KavaReflector.readField(field, listener)?.let { value -> field to value }
        }
        val position = listenerValues.firstOrNull { (field, _) ->
            field.type == Integer.TYPE || field.type == Integer::class.java
        }?.second?.let { it as? Number }?.toInt() ?: return null
        val ui = listenerValues.asSequence()
            .map { it.second }
            .firstOrNull(::isFavoriteListOwner)
            ?: return null
        val uiValues = hierarchyFields(ui).mapNotNull { field -> KavaReflector.readField(field, ui) }.toList()
        val adapter = uiValues.firstOrNull { it.javaClass.name.startsWith(FAVORITE_ADAPTER_PREFIX) }
            ?: return null
        val listView = favoriteListView(ui, uiValues, adapter)
        val headerCount = listView?.headerViewsCount ?: 0
        val uiName = ui.javaClass.name
        val index = if (uiName == "com.tencent.mm.plugin.fav.ui.FavSearchUI" ||
            uiName == "com.tencent.mm.plugin.fav.ui.FavFilterUI"
        ) {
            position - headerCount - 1
        } else {
            position - headerCount
        }
        if (index < 0) return null
        val methods = favoriteAdapterMethods(adapter).sortedWith(
            compareBy<Method> { if (it.name == "f") 0 else if (it.name == "getItem") 1 else 2 }
        )
        for (method in methods) {
            val item = KavaReflector.invoke(method, adapter, index) ?: continue
            if (isFavoriteItem(item)) return item
            resolve(item, visited, depth + 1, menuContext)?.let { return it }
        }
        return null
    }

    private fun isFavoriteListOwner(value: Any): Boolean {
        if (value.javaClass.name in FAVORITE_LIST_UI_NAMES) return true
        if (!value.javaClass.name.startsWith(FAVORITE_UI_PREFIX)) return false
        return hierarchyFields(value).any { field ->
            field.type.name.startsWith(FAVORITE_ADAPTER_PREFIX)
        }
    }

    private fun favoriteListView(ui: Any, uiValues: List<Any>, adapter: Any): ListView? {
        uiValues.filterIsInstance<ListView>().firstOrNull { listView ->
            listViewAdapterMatches(listView, adapter)
        }?.let { return it }
        KavaReflector.declaredMethods(ui.javaClass)
            .filter { method ->
                method.parameterTypes.isEmpty() &&
                    ListView::class.java.isAssignableFrom(method.returnType)
            }
            .forEach { method ->
                val listView = KavaReflector.invoke(method, ui) as? ListView
                if (listView != null && listViewAdapterMatches(listView, adapter)) return listView
            }
        return uiValues.filterIsInstance<ListView>().firstOrNull()
    }

    private fun listViewAdapterMatches(listView: ListView, adapter: Any): Boolean {
        val current = listView.adapter ?: return false
        return current === adapter ||
            (current is HeaderViewListAdapter && current.wrappedAdapter === adapter)
    }

    private fun resolveFromContextMenuInfo(
        info: ContextMenu.ContextMenuInfo,
        visited: MutableSet<Any>,
        depth: Int
    ): Any? {
        val targetView = KavaReflector.readField(info, "targetView") as? View ?: return null
        val position = (KavaReflector.readField(info, "position") as? Number)?.toInt()
        // Android's AdapterContextMenuInfo carries the exact row. Resolve that
        // row before walking arbitrary fields, otherwise a recycled View can
        // expose the previous favorite item from its tag.
        resolveFromView(targetView, position)?.let { return it }
        resolve(targetView.tag, visited, depth + 1, true)?.let { return it }
        return null
    }

    private fun resolveFromView(view: View, hintedPosition: Int?): Any? {
        var current: View? = view
        while (current != null) {
            if (current is ListView) {
                val position = hintedPosition
                    ?.takeIf { it >= 0 }
                    ?: current.getPositionForView(view).takeIf { it >= 0 }
                if (position != null) {
                    resolveFromListView(current, position, true)?.let { return it }
                }
            }
            current = current.parent as? View
        }
        return null
    }

    private fun resolveFromListView(listView: ListView, position: Int, exactFirst: Boolean = false): Any? {
        val adapter = listView.adapter ?: return null
        val headers = listView.headerViewsCount
        val positions = linkedSetOf<Int>()
        if (isSearchOrFilterList(listView)) {
            positions += position - headers - 1
            positions += position - headers
            positions += position - 1
            positions += position
        } else if (exactFirst) {
            positions += position - headers
            positions += position - headers - 1
            positions += position - headers + 1
        } else {
            positions += position - headers
            positions += position - headers - 1
            positions += position
            positions += position - 1
        }
        if (adapter is HeaderViewListAdapter) {
            resolveFromAdapter(adapter.wrappedAdapter, positions)?.let { return it }
        } else {
            resolveFromAdapter(adapter, positions)?.let { return it }
        }
        return null
    }

    private fun isSearchOrFilterList(listView: ListView): Boolean {
        var context: Context? = listView.context
        repeat(4) {
            when (context?.javaClass?.name) {
                "com.tencent.mm.plugin.fav.ui.FavSearchUI",
                "com.tencent.mm.plugin.fav.ui.FavFilterUI" -> return true
            }
            context = (context as? ContextWrapper)?.baseContext
        }
        return false
    }

    private fun resolveFromAdapter(adapter: Any, positions: Set<Int>): Any? {
        val methods = favoriteAdapterMethods(adapter)
        for (position in positions) {
            if (position < 0) continue
            for (method in methods) {
                val item = KavaReflector.invoke(method, adapter, position)
                if (isFavoriteItem(item)) return item
            }
        }
        return null
    }

    private fun readFromAdapterListener(listener: Any): Any? {
        val positions = mutableSetOf<Int>()
        val uiObjects = mutableListOf<Any>()
        var current: Class<*>? = listener.javaClass
        while (current != null && current != Any::class.java) {
            for (field in KavaReflector.declaredFields(current)) {
                val value = KavaReflector.readField(field, listener) ?: continue
                when {
                    value is Number -> positions += value.toInt()
                    value.javaClass.name.startsWith(FAVORITE_UI_PREFIX) -> uiObjects += value
                }
            }
            current = current.superclass
        }
        uiObjects.forEach { ui -> readFromUi(ui, positions)?.let { return it } }
        return null
    }

    private fun readFromUiFields(source: Any): Any? {
        val positions = mutableSetOf<Int>()
        var current: Class<*>? = source.javaClass
        while (current != null && current != Any::class.java) {
            for (field in KavaReflector.declaredFields(current)) {
                val value = KavaReflector.readField(field, source) ?: continue
                if (value is Number) positions += value.toInt()
            }
            current = current.superclass
        }
        return readFromUi(source, positions)
    }

    private fun readFromUi(ui: Any, positions: Set<Int>): Any? {
        if (positions.isEmpty()) return null
        val adapters = mutableListOf<Any>()
        val headerCounts = mutableSetOf(0)
        var current: Class<*>? = ui.javaClass
        while (current != null && current != Any::class.java) {
            for (field in KavaReflector.declaredFields(current)) {
                val value = KavaReflector.readField(field, ui) ?: continue
                when {
                    value is ListView -> headerCounts += value.headerViewsCount
                    value.javaClass.name.startsWith(FAVORITE_ADAPTER_PREFIX) -> adapters += value
                }
            }
            current = current.superclass
        }
        for (adapter in adapters) {
            val methods = favoriteAdapterMethods(adapter)
            for (position in positions) {
                for (header in headerCounts) {
                    val indexes = intArrayOf(position - header - 1, position - header, position - 1, position)
                    for (index in indexes.distinct()) {
                        if (index < 0) continue
                        for (method in methods) {
                            val item = KavaReflector.invoke(method, adapter, index)
                            if (isFavoriteItem(item)) return item
                        }
                    }
                }
            }
        }
        return null
    }

    private fun favoriteAdapterMethods(adapter: Any): List<Method> {
        return KavaReflector.declaredMethods(adapter.javaClass).filter { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.parameterTypes.size == 1 &&
                method.parameterTypes[0] == Integer.TYPE &&
                method.returnType != Void.TYPE &&
                !method.returnType.isPrimitive
        }.sortedWith(
            compareBy<Method> {
                when (it.name) {
                    "f" -> 0
                    "getItem" -> 1
                    else -> 2
                }
            }.thenBy(Method::toGenericString)
        )
    }

    private fun hierarchyFields(source: Any): Sequence<java.lang.reflect.Field> = sequence {
        var current: Class<*>? = source.javaClass
        while (current != null && current != Any::class.java) {
            yieldAll(KavaReflector.declaredFields(current))
            current = current.superclass
        }
    }

    private fun isFavoriteItem(value: Any?): Boolean {
        if (value == null || localId(value) <= 0L) return false
        val type = (KavaReflector.readField(value, "field_type") as? Number)?.toInt()
            ?: (KavaReflector.readField(value, "type") as? Number)?.toInt()
            ?: 0
        return type > 0 && KavaReflector.readField(value, "field_favProto") != null
    }

    private val FAVORITE_LIST_UI_NAMES = setOf(
        "com.tencent.mm.plugin.fav.ui.FavoriteIndexUI",
        "com.tencent.mm.plugin.fav.ui.FavSearchUI",
        "com.tencent.mm.plugin.fav.ui.FavFilterUI"
    )
}
