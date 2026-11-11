package h.Hchat.hooks.items.script

import de.robv.android.xposed.XposedBridge
import h.Hchat.dexkit.DexBridgeHolder
import h.Hchat.utils.KavaReflector
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.ClassMatcher
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Array
import java.lang.reflect.Member
import java.util.ArrayList

class ScriptDexKitBridge internal constructor(
    private val dexKitBridge: DexKitBridge?,
    private val dexBridgeHolder: DexBridgeHolder?,
    private val classLoader: ClassLoader
) {
    fun bridge(): DexKitBridge? = dexKitBridge

    fun holder(): DexBridgeHolder? = dexBridgeHolder

    fun findClassList(usingStrings: List<String>?): List<Class<*>> {
        val strings = usingStrings?.filter { it.isNotBlank() }.orEmpty()
        if (strings.isEmpty()) return emptyList()
        return runCatching {
            val bridge = dexKitBridge ?: return emptyList()
            val out = linkedSetOf<Class<*>>()
            collectClassMatches(bridge, strings, out)
            if (out.isEmpty() && strings.size > 1) {
                strings.forEach {
                    val single = listOf(it)
                    collectClassMatches(bridge, single, out)
                }
            }
            ArrayList(out)
        }.onFailure {
            h.Hchat.utils.HLog.e("[Hchat:Script] DexKit查找类失败: ${it.message}", it)
        }.getOrDefault(emptyList())
    }

    fun findClassList(vararg usingStrings: String): List<Class<*>> {
        return findClassList(usingStrings.toList())
    }

    fun findClassList(usingStrings: Any?): List<Class<*>> {
        return findClassList(normalizeStrings(usingStrings))
    }

    fun findMemberList(usingStrings: List<String>?): List<Member> {
        val strings = usingStrings?.filter { it.isNotBlank() }.orEmpty()
        if (strings.isEmpty()) return emptyList()
        return runCatching {
            val bridge = dexKitBridge ?: return emptyList()
            val out = linkedSetOf<Member>()
            collectMemberMatches(bridge, strings, out)
            if (out.isEmpty() && strings.size > 1) {
                strings.forEach {
                    val single = listOf(it)
                    collectMemberMatches(bridge, single, out)
                }
            }
            ArrayList(out)
        }.onFailure {
            h.Hchat.utils.HLog.e("[Hchat:Script] DexKit查找成员失败: ${it.message}", it)
        }.getOrDefault(emptyList())
    }

    fun findMemberList(vararg usingStrings: String): List<Member> {
        return findMemberList(usingStrings.toList())
    }

    fun findMemberList(usingStrings: Any?): List<Member> {
        return findMemberList(normalizeStrings(usingStrings))
    }

    private fun normalizeStrings(value: Any?): List<String> {
        if (value == null) return emptyList()
        if (value is String) return listOf(value)
        if (value is Iterable<*>) return flattenStrings(value)
        if (value.javaClass.isArray) {
            return flattenArray(value)
        }
        return listOf(value.toString())
    }

    private fun flattenStrings(values: Iterable<*>): List<String> {
        val out = ArrayList<String>()
        values.forEach { appendStringValue(it, out) }
        return out
    }

    private fun flattenArray(value: Any): List<String> {
        val out = ArrayList<String>()
        val size = Array.getLength(value)
        for (i in 0 until size) {
            appendStringValue(Array.get(value, i), out)
        }
        return out
    }

    private fun appendStringValue(value: Any?, out: MutableList<String>) {
        when {
            value == null -> Unit
            value is String -> out.add(value)
            value is Iterable<*> -> value.forEach { appendStringValue(it, out) }
            value.javaClass.isArray -> {
                val size = Array.getLength(value)
                for (i in 0 until size) appendStringValue(Array.get(value, i), out)
            }
            else -> out.add(value.toString())
        }
    }

    private fun collectClassMatches(
        bridge: DexKitBridge,
        strings: List<String>,
        out: MutableSet<Class<*>>
    ) {
        appendClassMatches(bridge, strings, out)
        appendMethodOwnerMatches(bridge, strings, out)
    }

    private fun collectMemberMatches(
        bridge: DexKitBridge,
        strings: List<String>,
        out: MutableSet<Member>
    ) {
        appendMethodMatches(bridge, strings, out)
        appendClassMemberMatches(bridge, strings, out)
    }

    private fun appendMethodMatches(
        bridge: DexKitBridge,
        strings: List<String>,
        out: MutableSet<Member>
    ) {
        if (strings.isEmpty()) return
        val matcher = MethodMatcher().apply { usingStrings(strings) }
        val query = FindMethod().apply { matcher(matcher) }
        bridge.findMethod(query)
            .mapNotNull { runCatching { it.getMethodInstance(classLoader) }.getOrNull() }
            .forEach { out.add(it) }
    }

    private fun appendClassMatches(
        bridge: DexKitBridge,
        strings: List<String>,
        out: MutableSet<Class<*>>
    ) {
        if (strings.isEmpty()) return
        val matcher = ClassMatcher().apply { usingStrings(strings) }
        val query = FindClass().apply { matcher(matcher) }
        bridge.findClass(query)
            .mapNotNull { KavaReflector.loadClass(it.name, classLoader) }
            .forEach { out.add(it) }
    }

    private fun appendMethodOwnerMatches(
        bridge: DexKitBridge,
        strings: List<String>,
        out: MutableSet<Class<*>>
    ) {
        if (strings.isEmpty()) return
        val matcher = MethodMatcher().apply { usingStrings(strings) }
        val query = FindMethod().apply { matcher(matcher) }
        bridge.findMethod(query)
            .mapNotNull { runCatching { it.getMethodInstance(classLoader).declaringClass }.getOrNull() }
            .forEach { out.add(it) }
    }

    private fun appendClassMemberMatches(
        bridge: DexKitBridge,
        strings: List<String>,
        out: MutableSet<Member>
    ) {
        if (strings.isEmpty()) return
        val matcher = ClassMatcher().apply { usingStrings(strings) }
        val query = FindClass().apply { matcher(matcher) }
        bridge.findClass(query)
            .mapNotNull { KavaReflector.loadClass(it.name, classLoader) }
            .forEach { clazz ->
                KavaReflector.declaredConstructors(clazz).forEach { out.add(it) }
                KavaReflector.declaredMethods(clazz).forEach { out.add(it) }
            }
    }
}
