package h.Hchat.hooks.api.contact

import h.Hchat.dexkit.DexFinder
import h.Hchat.hooks.api.net.WeChatNetworkDispatcher
import h.Hchat.utils.KavaReflector
import java.lang.reflect.Constructor
import java.util.Collections

/**
 * 通过好友申请 API。
 *
 * 底层复用微信 NetSceneVerifyUser(opcode=3)，对脚本层保持 WA 同款签名。
 */
class WeChatVerifyUserApi(
    private val dexFinder: DexFinder?,
    private val dispatcher: WeChatNetworkDispatcher?,
    private val logger: Logger?
) {
    fun interface Logger {
        fun log(message: String)
    }

    val isAvailable: Boolean
        get() = dexFinder?.hasVerifyUserApi() == true && dispatcher != null

    fun verifyUser(wxid: String?, ticket: String?, scene: Int): Boolean {
        return verifyUser(wxid, ticket, scene, 0)
    }

    fun verifyUser(wxid: String?, ticket: String?, scene: Int, privacy: Int): Boolean {
        val cleanWxid = wxid?.trim().orEmpty()
        if (cleanWxid.isBlank()) {
            log("通过好友申请失败: wxid为空")
            return false
        }
        val clazz = dexFinder?.verifyUserClass
        if (clazz == null) {
            log("通过好友申请失败: verifyUserClass为空")
            return false
        }
        val request = newRequest(clazz, cleanWxid, ticket.orEmpty(), scene, privacy) ?: return false
        val sent = dispatcher?.send(request) == true
        if (!sent) log("通过好友申请失败: 发包失败 request=${request.javaClass.name}")
        return sent
    }

    private fun newRequest(
        clazz: Class<*>,
        wxid: String,
        ticket: String,
        scene: Int,
        privacy: Int
    ): Any? {
        val ctors = KavaReflector.declaredConstructors(clazz)
            .filter { it.isVerifyUserCtor() }
            .sortedByDescending { it.parameterTypes.size }
        var lastError: String? = null
        for (ctor in ctors) {
            try {
                val args = when (ctor.parameterTypes.size) {
                    8 -> arrayOf<Any?>(VERIFY_OK_OPCODE, wxid, ticket, scene, "", privacy, Collections.emptyList<String>(), null)
                    6 -> arrayOf<Any?>(VERIFY_OK_OPCODE, wxid, ticket, scene, "", privacy)
                    4 -> arrayOf<Any?>(VERIFY_OK_OPCODE, wxid, ticket, scene)
                    else -> continue
                }
                return KavaReflector.newInstance(ctor, *args)
            } catch (e: Throwable) {
                lastError = "${ctor.parameterTypes.size}: ${e.message}"
            }
        }
        log("通过好友申请失败: 无合适构造${lastError?.let { ", last=$it" } ?: ""}")
        return null
    }

    private fun Constructor<*>.isVerifyUserCtor(): Boolean {
        val types = parameterTypes
        if (types.size == 4) {
            return isInt(types[0])
                && types[1] == String::class.java
                && types[2] == String::class.java
                && isInt(types[3])
        }
        if (types.size == 6) {
            return isInt(types[0])
                && types[1] == String::class.java
                && types[2] == String::class.java
                && isInt(types[3])
                && types[4] == String::class.java
                && isInt(types[5])
        }
        if (types.size == 8) {
            return isInt(types[0])
                && types[1] == String::class.java
                && types[2] == String::class.java
                && isInt(types[3])
                && types[4] == String::class.java
                && isInt(types[5])
                && java.util.List::class.java.isAssignableFrom(types[6])
        }
        return false
    }

    private fun isInt(type: Class<*>?): Boolean =
        type == Int::class.javaPrimitiveType || type == Int::class.javaObjectType

    private fun log(message: String) {
        logger?.log("[WeChatVerifyUserApi] $message")
    }

    private companion object {
        private const val VERIFY_OK_OPCODE = 3
    }
}
