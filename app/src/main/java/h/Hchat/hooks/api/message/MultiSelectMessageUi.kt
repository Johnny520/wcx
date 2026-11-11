package h.Hchat.hooks.api.message

import android.os.Handler
import android.os.Looper
import h.Hchat.utils.KavaReflector
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap

object MultiSelectMessageUi {
    private const val CHATTING_COMPONENT_PREFIX = "com.tencent.mm.ui.chatting.component."
    private const val COMPONENT_SEARCH_DEPTH = 4

    @JvmStatic
    fun resolveExitTarget(
        root: Any?,
        exitMethod: Method,
        logger: (String, Throwable?) -> Unit
    ): ExitTarget? {
        val owner = findComponent(root, exitMethod.declaringClass)
        if (owner == null) {
            logger("未找到多选消息原生退出组件: ${exitMethod.declaringClass.name}", null)
            return null
        }
        return ExitTarget(owner, exitMethod)
    }

    class ExitTarget internal constructor(
        private val owner: Any,
        private val method: Method
    ) {
        fun exit(logger: (String, Throwable?) -> Unit) {
            Handler(Looper.getMainLooper()).post {
                runCatching { KavaReflector.invokeOrThrow(method, owner) }
                    .onFailure { logger("退出多选状态失败: ${method.toGenericString()}", it) }
            }
        }
    }

    private fun findComponent(root: Any?, targetClass: Class<*>): Any? {
        if (root == null) return null
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        val queue = ArrayDeque<SearchNode>()
        visited.add(root)
        queue.add(SearchNode(root, 0))
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (targetClass.isInstance(node.value)) return node.value
            if (node.depth >= COMPONENT_SEARCH_DEPTH) continue
            componentReferences(node.value).forEach { value ->
                if (visited.add(value)) queue.add(SearchNode(value, node.depth + 1))
            }
        }
        return null
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

    private data class SearchNode(val value: Any, val depth: Int)
}
