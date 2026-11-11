package h.Hchat.hooks.api.message

import h.Hchat.utils.KavaReflector
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

object MultiSelectMessageResolver {
    private const val CHATTING_COMPONENT_PREFIX = "com.tencent.mm.ui.chatting.component."
    private const val COMPONENT_SEARCH_DEPTH = 4

    private val selectedListFieldCache = ConcurrentHashMap<Class<*>, Field>()
    private val selectedMessagesMethodCache = ConcurrentHashMap<Class<*>, Method>()

    @JvmStatic
    fun resolve(root: Any?): List<Any> {
        if (root == null) return emptyList()
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        val queue = ArrayDeque<SearchNode>()
        visited.add(root)
        queue.add(SearchNode(root, 0))

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            selectedMessagesFromField(node.value).takeIf { it.isNotEmpty() }?.let { return it }
            selectedMessagesFromMethod(node.value).takeIf { it.isNotEmpty() }?.let { return it }
            if (node.depth >= COMPONENT_SEARCH_DEPTH) continue

            componentReferences(node.value).forEach { value ->
                if (visited.add(value)) queue.add(SearchNode(value, node.depth + 1))
            }
        }
        return emptyList()
    }

    private fun selectedMessagesFromField(owner: Any): List<Any> {
        selectedListFieldCache[owner.javaClass]?.let { field ->
            messagesFromList(KavaReflector.readField(field, owner)).takeIf { it.isNotEmpty() }?.let { return it }
        }
        var current: Class<*>? = owner.javaClass
        while (current != null && current != Any::class.java) {
            for (field in KavaReflector.declaredFields(current)) {
                if (Modifier.isStatic(field.modifiers) || !List::class.java.isAssignableFrom(field.type)) continue
                val messages = messagesFromList(KavaReflector.readField(field, owner))
                if (messages.isNotEmpty()) {
                    selectedListFieldCache[owner.javaClass] = field
                    return messages
                }
            }
            current = current.superclass
        }
        return emptyList()
    }

    private fun selectedMessagesFromMethod(owner: Any): List<Any> {
        selectedMessagesMethodCache[owner.javaClass]?.let { method ->
            messagesFromList(KavaReflector.invoke(method, owner)).takeIf { it.isNotEmpty() }?.let { return it }
        }
        var current: Class<*>? = owner.javaClass
        while (current != null && current != Any::class.java) {
            for (method in KavaReflector.declaredMethods(current)) {
                if (Modifier.isStatic(method.modifiers) || method.parameterTypes.isNotEmpty()) continue
                if (!List::class.java.isAssignableFrom(method.returnType)) continue
                val messages = messagesFromList(KavaReflector.invoke(method, owner))
                if (messages.isNotEmpty()) {
                    selectedMessagesMethodCache[owner.javaClass] = method
                    return messages
                }
            }
            current = current.superclass
        }
        return emptyList()
    }

    private fun messagesFromList(value: Any?): List<Any> {
        val list = value as? List<*> ?: return emptyList()
        if (list.isEmpty()) return emptyList()
        val messages = list.filterNotNull().filter { isNativeMessage(it) && messageId(it) > 0L }
        return if (messages.size == list.size) messages else emptyList()
    }

    private fun componentReferences(owner: Any): List<Any> {
        val result = ArrayList<Any>()
        var current: Class<*>? = owner.javaClass
        while (current != null && current != Any::class.java) {
            for (field in KavaReflector.declaredFields(current)) {
                if (Modifier.isStatic(field.modifiers) || field.type.isPrimitive || field.type.isArray) continue
                val value = KavaReflector.readField(field, owner) ?: continue
                if (value.javaClass.name.startsWith(CHATTING_COMPONENT_PREFIX)) result += value
            }
            current = current.superclass
        }
        return result
    }

    private fun isNativeMessage(value: Any): Boolean {
        if (value.javaClass.name.startsWith("com.tencent.mm.storage.")) return true
        return KavaReflector.declaredMethods(value.javaClass).any { method ->
            method.parameterTypes.isEmpty() &&
                (method.name == "getMsgId" || method.name == "getMsgID") &&
                (method.returnType == java.lang.Long.TYPE || method.returnType == java.lang.Long::class.java)
        }
    }

    private fun messageId(message: Any): Long {
        for (name in arrayOf("getMsgId", "getMsgID", "getId")) {
            (KavaReflector.invokeMethod(message, name) as? Number)?.toLong()?.takeIf { it > 0L }?.let { return it }
        }
        for (name in arrayOf("field_msgId", "msgId", "msgID", "id")) {
            (KavaReflector.readField(message, name) as? Number)?.toLong()?.takeIf { it > 0L }?.let { return it }
        }
        return 0L
    }

    private data class SearchNode(val value: Any, val depth: Int)
}
