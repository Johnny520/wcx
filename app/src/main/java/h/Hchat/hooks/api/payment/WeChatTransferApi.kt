package h.Hchat.hooks.api.payment

import android.os.Handler
import android.os.Looper
import h.Hchat.dexkit.DexFinder
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.net.WeChatNetworkDispatcher
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.utils.KavaReflector
import org.json.JSONObject
import java.lang.reflect.Constructor
import java.util.Collections
import java.util.IdentityHashMap
import de.robv.android.xposed.XC_MethodHook

/**
 * 微信普通转账领取/退回 API。
 */
class WeChatTransferApi(
    private val dexFinder: DexFinder?,
    private val dispatcher: WeChatNetworkDispatcher?,
    private val logger: Logger?
) {
    data class QueryResult(val errorCode: Int, val errorMessage: String, val response: JSONObject?)

    fun interface Logger {
        fun log(message: String)
    }

    val isAvailable: Boolean
        get() = dexFinder?.hasTransferOperationApi() == true && dispatcher != null

    fun canOperate(): Boolean = isAvailable

    fun canQuery(): Boolean = dexFinder?.hasTransferQueryApi() == true && dispatcher != null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingQueries = Collections.synchronizedMap(IdentityHashMap<Any, (QueryResult?) -> Unit>())
    @Volatile private var queryHookInstalled = false
    private val lastOperationFailure = ThreadLocal<String>()

    fun receive(params: TransferOperationParams?): Boolean = operate("confirm", params)

    fun refund(params: TransferOperationParams?): Boolean = operate("refuse", params)

    fun lastFailureReason(): String = lastOperationFailure.get().orEmpty()

    fun query(
        params: TransferQueryParams?,
        timeoutMs: Long = 7000L,
        callback: (QueryResult?) -> Unit
    ): Boolean {
        if (params == null || params.transactionId.isBlank() || params.transId.isBlank()) return false
        val clazz = dexFinder?.transferQueryClass ?: return false
        if (!installQueryHook()) return false
        val request = newQueryRequest(clazz, params) ?: return false
        pendingQueries[request] = callback
        mainHandler.postDelayed({
            val expired = pendingQueries.remove(request) ?: return@postDelayed
            expired(null)
        }, timeoutMs.coerceAtLeast(1000L))
        if (dispatcher?.send(request) == true) return true
        pendingQueries.remove(request)
        return false
    }

    fun operate(op: String?, params: TransferOperationParams?): Boolean {
        lastOperationFailure.set("")
        if (params == null) {
            recordOperationFailure("stage=validate params=null")
            return false
        }
        if (!isValidOp(op)) {
            recordOperationFailure("stage=validate op=${op.orEmpty()}")
            return false
        }
        if (params.transactionId.isBlank() || params.transId.isBlank() || params.username.isBlank()) {
            recordOperationFailure("stage=validate transactionId/transId/username缺失")
            return false
        }
        val clazz = dexFinder?.transferOperationClass
        if (clazz == null) {
            recordOperationFailure("stage=locate requestClass=null")
            return false
        }
        val request = newRequest(clazz, op ?: "", params) ?: return false
        setProcessName(request)
        val sent = dispatcher?.send(request) == true
        if (!sent) {
            recordOperationFailure(
                "stage=dispatch op=$op request=${request.javaClass.name} dispatcher=${dispatcher != null}"
            )
        }
        return sent
    }

    private fun newRequest(
        clazz: Class<*>,
        op: String,
        params: TransferOperationParams
    ): Any? {
        val ctors = KavaReflector.declaredConstructors(clazz)
            .filter { it.isTransferOperationCtor() }
            .sortedWith(compareByDescending<Constructor<*>> { it.parameterTypes.size }
                .thenByDescending { scoreCtor(it) })
        if (ctors.isEmpty()) {
            val available = KavaReflector.declaredConstructors(clazz)
                .joinToString(";") { constructorSignature(it) }
            recordOperationFailure(
                "stage=constructor_select request=${clazz.name} available=$available"
            )
            return null
        }
        var lastError = ""
        for (ctor in ctors) {
            val args = buildArgs(ctor.parameterTypes, op, params) ?: continue
            val request = KavaReflector.newInstance(ctor, *args)
            if (request != null) {
                log("构造转账操作请求: version=${wechatVersion()} target=${clazz.name}${constructorSignature(ctor)}")
                return request
            }
            lastError = "stage=constructor_invoke request=${clazz.name} " +
                "ctor=${constructorSignature(ctor)} args=${argumentShape(args)}"
        }
        recordOperationFailure(
            lastError.ifBlank { "stage=constructor_map request=${clazz.name}" }
        )
        return null
    }

    @Synchronized
    private fun installQueryHook(): Boolean {
        if (queryHookInstalled) return true
        val method = dexFinder?.transferQueryResponseMethod ?: return false
        return runCatching {
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val callback = pendingQueries.remove(param.thisObject) ?: return
                    callback(
                        QueryResult(
                            errorCode = (param.args.getOrNull(0) as? Number)?.toInt() ?: -1,
                            errorMessage = param.args.getOrNull(1) as? String ?: "",
                            response = param.args.getOrNull(2) as? JSONObject
                        )
                    )
                }
            })
            queryHookInstalled = true
            true
        }.getOrElse {
            log("转账查询响应 Hook 失败: ${it.message}")
            false
        }
    }

    private fun newQueryRequest(clazz: Class<*>, params: TransferQueryParams): Any? {
        val ctor = KavaReflector.declaredConstructors(clazz)
            .filter(::isTransferQueryCtor)
            .maxByOrNull { it.parameterTypes.size }
            ?: return null
        val args: Array<Any?> = if (ctor.parameterTypes.size == 6) {
            arrayOf(0, params.transactionId, params.transId, params.invalidTime,
                params.groupUsername, params.transferAttach)
        } else {
            arrayOf(0, params.transactionId, params.transId, params.invalidTime,
                params.groupUsername)
        }
        log(
            "构造转账查询请求: transactionId=${params.transactionId.isNotBlank()} " +
                "transId=${params.transId.isNotBlank()} args=${ctor.parameterTypes.size}"
        )
        return runCatching { KavaReflector.newInstance(ctor, *args) }.getOrElse {
            log("构造转账查询请求失败: ${it.message}")
            null
        }
    }

    private fun isTransferQueryCtor(ctor: Constructor<*>): Boolean {
        val p = ctor.parameterTypes
        return p.size in 5..6 && isInt(p[0]) && p[1] == String::class.java
            && p[2] == String::class.java && isInt(p[3]) && p[4] == String::class.java
            && (p.size == 5 || p[5] == String::class.java)
    }

    private fun buildArgs(
        types: Array<Class<*>>,
        op: String,
        params: TransferOperationParams
    ): Array<Any?>? {
        return when (transferOperationLayout(types)) {
            TransferOperationLayout.V68_PLUS -> arrayOf(
                params.transactionId,
                params.transId,
                params.totalFee,
                op,
                params.username,
                params.invalidTime,
                params.leftButtonContinue,
                params.groupUsername,
                params.recvAccountType,
                params.bindSerial,
                cleanMap(params.subTitleClicked),
                params.subRecvChannelId,
                params.displayName,
                params.transferAttach
            )
            TransferOperationLayout.V68_PLUS_SHORT -> arrayOf(
                params.transactionId,
                params.transId,
                params.totalFee,
                op,
                params.username,
                params.invalidTime,
                params.groupUsername,
                params.recvAccountType,
                params.bindSerial,
                cleanMap(params.subTitleClicked),
                params.subRecvChannelId,
                params.displayName,
                params.transferAttach
            )
            TransferOperationLayout.V66 -> arrayOf(
                params.transactionId,
                params.transId,
                params.totalFee,
                op,
                params.username,
                params.invalidTime,
                params.leftButtonContinue,
                params.groupUsername,
                params.recvAccountType,
                cleanMap(params.subTitleClicked),
                params.subRecvChannelId,
                params.displayName,
                params.transferAttach
            )
            TransferOperationLayout.V66_SHORT -> arrayOf(
                params.transactionId,
                params.transId,
                params.totalFee,
                op,
                params.username,
                params.invalidTime,
                params.groupUsername,
                params.recvAccountType,
                cleanMap(params.subTitleClicked),
                params.subRecvChannelId,
                params.displayName,
                params.transferAttach
            )
            TransferOperationLayout.V58 -> arrayOf(
                params.transactionId,
                params.transId,
                params.totalFee,
                op,
                params.username,
                params.invalidTime,
                params.leftButtonContinue,
                params.groupUsername,
                params.recvAccountType,
                cleanMap(params.subTitleClicked),
                params.subRecvChannelId,
                params.displayName
            )
            TransferOperationLayout.V58_SHORT -> arrayOf(
                params.transactionId,
                params.transId,
                params.totalFee,
                op,
                params.username,
                params.invalidTime,
                params.groupUsername,
                params.recvAccountType,
                cleanMap(params.subTitleClicked),
                params.subRecvChannelId,
                params.displayName
            )
            TransferOperationLayout.V49 -> arrayOf(
                params.transactionId,
                params.transId,
                params.totalFee,
                op,
                params.username,
                params.invalidTime,
                params.leftButtonContinue,
                params.groupUsername,
                params.recvAccountType,
                cleanMap(params.subTitleClicked)
            )
            TransferOperationLayout.V49_SHORT -> arrayOf(
                params.transactionId,
                params.transId,
                params.totalFee,
                op,
                params.username,
                params.invalidTime,
                params.groupUsername,
                params.recvAccountType,
                cleanMap(params.subTitleClicked)
            )
            null -> null
        }
    }

    private fun Constructor<*>.isTransferOperationCtor(): Boolean {
        return transferOperationLayout(parameterTypes) != null
    }

    private fun transferOperationLayout(types: Array<Class<*>>): TransferOperationLayout? {
        if (types.size < 6 ||
            types[0] != String::class.java ||
            types[1] != String::class.java ||
            !isInt(types[2]) ||
            types[3] != String::class.java ||
            types[4] != String::class.java ||
            !isInt(types[5])
        ) return null
        return when {
            types.size == 9 &&
                types[6] == String::class.java &&
                isInt(types[7]) && isMap(types[8]) -> TransferOperationLayout.V49_SHORT
            types.size == 10 &&
                types[6] == String::class.java && types[7] == String::class.java &&
                isInt(types[8]) && isMap(types[9]) -> TransferOperationLayout.V49
            types.size == 11 &&
                types[6] == String::class.java && isInt(types[7]) && isMap(types[8]) &&
                isLong(types[9]) && types[10] == String::class.java ->
                TransferOperationLayout.V58_SHORT
            types.size == 12 &&
                types[6] == String::class.java && types[7] == String::class.java &&
                isInt(types[8]) && isMap(types[9]) && isLong(types[10]) &&
                types[11] == String::class.java -> TransferOperationLayout.V58
            types.size == 12 &&
                types[6] == String::class.java && isInt(types[7]) && isMap(types[8]) &&
                isLong(types[9]) && types[10] == String::class.java &&
                types[11] == String::class.java -> TransferOperationLayout.V66_SHORT
            types.size == 13 &&
                types[6] == String::class.java && types[7] == String::class.java &&
                isInt(types[8]) && isMap(types[9]) && isLong(types[10]) &&
                types[11] == String::class.java && types[12] == String::class.java ->
                TransferOperationLayout.V66
            types.size == 13 &&
                types[6] == String::class.java && isInt(types[7]) &&
                types[8] == String::class.java && isMap(types[9]) && isLong(types[10]) &&
                types[11] == String::class.java && types[12] == String::class.java ->
                TransferOperationLayout.V68_PLUS_SHORT
            types.size == 14 &&
                types[6] == String::class.java && types[7] == String::class.java &&
                isInt(types[8]) && types[9] == String::class.java && isMap(types[10]) &&
                isLong(types[11]) && types[12] == String::class.java &&
                types[13] == String::class.java -> TransferOperationLayout.V68_PLUS
            else -> null
        }
    }

    private fun scoreCtor(ctor: Constructor<*>): Int {
        val types = ctor.parameterTypes
        var score = 0
        if (types.any { it == Long::class.javaPrimitiveType || it == Long::class.javaObjectType }) score += 2
        if (types.size >= 10 && types[9] == String::class.java) score += 2
        return score
    }

    private fun cleanMap(map: Map<String, String>?): Map<String, String>? {
        if (map.isNullOrEmpty()) return null
        return map.filterKeys { it.isNotBlank() }.filterValues { it.isNotBlank() }
    }

    private fun setProcessName(request: Any) {
        try {
            KavaReflector.invoke(
                KavaReflector.findMethod(request.javaClass, "setProcessName", String::class.java),
                request,
                "RemittanceProcess"
            )
        } catch (_: Throwable) {
        }
    }

    private fun isValidOp(op: String?): Boolean = op == "confirm" || op == "refuse"

    private fun isInt(type: Class<*>?): Boolean =
        type == Int::class.javaPrimitiveType || type == Int::class.javaObjectType

    private fun isLong(type: Class<*>?): Boolean =
        type == Long::class.javaPrimitiveType || type == Long::class.javaObjectType

    private fun isMap(type: Class<*>?): Boolean =
        type != null && Map::class.java.isAssignableFrom(type)

    private fun constructorSignature(ctor: Constructor<*>): String =
        ctor.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.name }

    private fun argumentShape(args: Array<Any?>): String =
        args.joinToString(prefix = "(", postfix = ")") { it?.javaClass?.name ?: "null" }

    private fun recordOperationFailure(detail: String) {
        val value = "version=${wechatVersion()} $detail"
        lastOperationFailure.set(value)
        log("转账操作失败: $value")
    }

    private fun wechatVersion(): String = runCatching {
        WeChatApis.version()?.current()?.displayVersion()
    }.getOrNull().orEmpty().ifBlank { "unknown" }

    private fun log(message: String) {
        logger?.log("[WeChatTransferApi] $message")
    }

    private enum class TransferOperationLayout {
        V49_SHORT,
        V49,
        V58_SHORT,
        V58,
        V66_SHORT,
        V66,
        V68_PLUS_SHORT,
        V68_PLUS
    }
}
