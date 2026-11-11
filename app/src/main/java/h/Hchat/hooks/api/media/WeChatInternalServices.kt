package h.Hchat.hooks.api.media

import h.Hchat.dexkit.DexFinder
import h.Hchat.utils.KavaReflector
import java.lang.reflect.Method

/**
 * 微信内部服务容器辅助。
 *
 * 优先按目标类查找服务，找不到时沿接口和父类递归兜底。
 */
object WeChatInternalServices {
    @JvmStatic
    fun getService(dexFinder: DexFinder?, owner: Class<*>?): Any? {
        if (dexFinder == null || dexFinder.serviceGetterMethod == null || owner == null) {
            return null
        }
        val direct = getByClass(dexFinder.serviceGetterMethod, owner, owner)
        if (direct != null) return direct
        return getByInterfaces(dexFinder.serviceGetterMethod, owner, owner, HashSet())
    }

    private fun getByInterfaces(
        getter: Method,
        owner: Class<*>,
        clazz: Class<*>?,
        visited: MutableSet<Class<*>>
    ): Any? {
        if (clazz == null || clazz == Any::class.java || !visited.add(clazz)) return null
        for (itf in clazz.interfaces) {
            val direct = getByClass(getter, itf, owner)
            if (direct != null) return direct
            val nested = getByInterfaces(getter, owner, itf, visited)
            if (nested != null) return nested
        }
        return getByInterfaces(getter, owner, clazz.superclass, visited)
    }

    private fun getByClass(getter: Method, serviceClass: Class<*>, owner: Class<*>): Any? {
        return try {
            if (!KavaReflector.isStatic(getter)) return null
            val value = KavaReflector.invoke(getter, null, serviceClass)
            if (value != null && owner.isInstance(value)) value else null
        } catch (_: Throwable) {
            null
        }
    }
}
