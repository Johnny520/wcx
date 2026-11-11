package h.Hchat.hooks.items.realtail

import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.media.WeChatInternalServices
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.utils.KavaReflector
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

class ContactProfilePrefetcher(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit,
    private val onProfileUpdated: (String) -> Unit
) {
    private val lastRequestAt = ConcurrentHashMap<String, Long>()
    @Volatile private var sceneClass: Class<*>? = null

    fun requestIfMissing(wxid: String?) {
        val id = wxid?.trim().orEmpty()
        if (!RealNameTailStore.isValidWxid(id)) return
        if (!profileMissing(id)) return
        val now = System.currentTimeMillis()
        val last = lastRequestAt[id] ?: 0L
        if (now - last < REQUEST_COOLDOWN_MS) return
        lastRequestAt[id] = now
        WeChatApis.tasks()?.runAsync { requestProfile(id) } ?: Thread { requestProfile(id) }.start()
    }

    private fun profileMissing(wxid: String): Boolean {
        val contact = WeChatApis.contact().contacts()?.getContact(wxid) ?: return true
        return contact.gender == 0 || contact.getRegion().isBlank()
    }

    private fun requestProfile(wxid: String) {
        try {
            val sent = requestViaGetContactService(wxid) || requestViaNativeScene(wxid)
            if (!sent) return
            WeChatApis.tasks()?.runOnMainDelayed("real_tail_profile_refresh_$wxid", REFRESH_DELAY_MS) {
                onProfileUpdated(wxid)
            }
        } catch (t: Throwable) {
            logger("联系人资料预取失败", t)
        }
    }

    private fun requestViaGetContactService(wxid: String): Boolean {
        val dexFinder = context.dexFinder()
        val methods = dexFinder.getContactAddMethods ?: return false
        for (method in methods) {
            val service = resolveService(method) ?: continue
            val args = serviceArgs(method, wxid) ?: continue
            val targetMethod = methodForService(method, service) ?: continue
            if (KavaReflector.invokeSuccessfully(targetMethod, service, *args)) {
                return true
            }
        }
        return false
    }

    private fun resolveService(method: Method): Any? {
        val owner = method.declaringClass ?: return null
        resolveServiceByGetter(owner)?.let { return it }
        WeChatInternalServices.getService(context.dexFinder(), owner)?.let { return it }
        for (itf in owner.interfaces) {
            resolveServiceByGetter(itf)?.let { return it }
            WeChatInternalServices.getService(context.dexFinder(), itf)?.let { return it }
        }
        return WeChatInternalServices.getService(context.dexFinder(), owner.superclass)
    }

    private fun resolveServiceByGetter(owner: Class<*>): Any? {
        val getters = context.dexFinder().getContactServiceGetters ?: return null
        for (getter in getters) {
            if (!owner.isAssignableFrom(getter.returnType)) continue
            val service = KavaReflector.invoke(getter, null) ?: continue
            if (owner.isInstance(service)) return service
        }
        return null
    }

    private fun methodForService(method: Method, service: Any): Method? {
        if (method.declaringClass.isInstance(service)) return method
        return KavaReflector.findDeclaredMethod(
            service.javaClass,
            method.name,
            *method.parameterTypes
        ) ?: KavaReflector.findMethod(
            service.javaClass,
            method.name,
            *method.parameterTypes
        )
    }

    private fun serviceArgs(method: Method, wxid: String): Array<Any?>? {
        val types = method.parameterTypes ?: return null
        return when {
            types.size == 2 && types[0] == String::class.java && types[1] == String::class.java ->
                arrayOf(wxid, "")
            types.size == 3 &&
                types[0] == String::class.java &&
                types[1] == String::class.java &&
                (types[2] == Integer.TYPE || types[2] == Integer::class.java) ->
                arrayOf(wxid, "", DEFAULT_CONTACT_SCENE)
            else -> null
        }
    }

    private fun requestViaNativeScene(wxid: String): Boolean {
        val scene = buildScene(wxid) ?: return false
        return WeChatApis.network()?.sendRequest(scene) == true
    }

    private fun buildScene(wxid: String): Any? {
        val clazz = sceneClass ?: locateSceneClass()?.also { sceneClass = it } ?: return null
        for (ctor in KavaReflector.declaredConstructors(clazz)) {
            val values = buildArgs(ctor, wxid) ?: continue
            val scene = KavaReflector.newInstance(ctor, values) ?: continue
            if (sceneType(scene) == GET_CONTACT_TYPE) return scene
        }
        return null
    }

    private fun locateSceneClass(): Class<*>? {
        return try {
            context.dexFinder().findNativeNetSceneClass(GET_CONTACT_URI, GET_CONTACT_TYPE)
        } catch (t: Throwable) {
            logger("定位联系人资料请求失败", t)
            null
        }
    }

    private fun buildArgs(ctor: Constructor<*>, wxid: String): Array<Any?>? {
        val types = ctor.parameterTypes ?: return null
        if (types.isEmpty()) return null
        val out = arrayOfNulls<Any>(types.size)
        var hasString = false
        for (i in types.indices) {
            val type = types[i]
            when {
                type == String::class.java -> {
                    out[i] = wxid
                    hasString = true
                }
                type == Integer.TYPE || type == Integer::class.java -> out[i] = 0
                type == java.lang.Long.TYPE || type == java.lang.Long::class.java -> out[i] = 0L
                type == java.lang.Boolean.TYPE || type == java.lang.Boolean::class.java -> out[i] = false
                else -> return null
            }
        }
        return if (hasString) out else null
    }

    private fun sceneType(scene: Any): Int {
        return try {
            val value = KavaReflector.invokeMethod(scene, "getType")
            if (value is Number) value.toInt() else -1
        } catch (_: Throwable) {
            -1
        }
    }

    companion object {
        private const val GET_CONTACT_URI = "/cgi-bin/micromsg-bin/getcontact"
        private const val GET_CONTACT_TYPE = 182
        private const val DEFAULT_CONTACT_SCENE = 0
        private const val REQUEST_COOLDOWN_MS = 10 * 60 * 1000L
        private const val REFRESH_DELAY_MS = 2500L
    }
}
