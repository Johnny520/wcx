package h.Hchat.hooks.items.payment.transfer

import android.content.Context
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.preferences.HchatStorage
import org.json.JSONArray
import org.json.JSONObject
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method
import java.lang.reflect.Modifier

data class TransferReceiveAccount(
    val key: String,
    val name: String,
    val accountType: Int,
    val subChannelId: Long,
    val bindSerial: String,
    val available: Boolean
)

object TransferReceiveAccountStore {
    const val DEFAULT_KEY = "default"
    const val LQT_KEY = "preset:lqt"
    const val BUSINESS_KEY = "preset:business"
    private const val KEY_ACCOUNTS = "transfer_receive_accounts"
    @Volatile private var installed = false

    @Synchronized
    fun install(context: FeatureContext, logger: (String, Throwable?) -> Unit): Boolean {
        if (installed) return true
        val prefs = DexMethodCache.prefs(
            context.hostContext(),
            "Hchat_transfer_receive_account_method_cache"
        )
        val cacheKey = DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
        val method = DexMethodCache.load(
            prefs,
            cacheKey,
            context.hostClassLoader(),
            "receive_account_parser"
        )
            ?.takeIf(::isAccountParser)
            ?: locateParser(context, logger)?.also {
                DexMethodCache.save(prefs, cacheKey, "receive_account_parser", it)
            }
            ?: return false
        HookRegistry.get().hook(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val json = param.args.firstOrNull() as? JSONObject ?: return
                captureItem(context.hostContext(), json)
            }
        })
        installed = true
        return true
    }

    fun list(context: Context?): List<TransferReceiveAccount> {
        if (context == null) return emptyList()
        val raw = HchatStorage.preferences(context, AutoTransferSettings.PREFS_NAME)
            .getString(accountsKey(), "")
            .orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val type = item.optInt("accountType", 0)
                    val subId = item.optLong("subChannelId", 0L)
                    val name = item.optString("name").trim()
                    if (name.isBlank()) continue
                    add(
                        TransferReceiveAccount(
                            key = accountKey(type, subId),
                            name = name,
                            accountType = type,
                            subChannelId = subId,
                            bindSerial = item.optString("bindSerial"),
                            available = item.optBoolean("available", true)
                        )
                    )
                }
            }.distinctBy { it.key }
        }.getOrDefault(emptyList())
    }

    fun find(context: Context?, key: String?): TransferReceiveAccount? {
        if (key.isNullOrBlank() || key == DEFAULT_KEY) return null
        return find(list(context), key)
    }

    fun find(accounts: List<TransferReceiveAccount>, key: String?): TransferReceiveAccount? {
        if (key.isNullOrBlank() || key == DEFAULT_KEY) return null
        val available = accounts.filter { it.available }
        return when (key) {
            LQT_KEY -> available.firstOrNull { normalizeName(it.name).contains("零钱通") }
            BUSINESS_KEY -> available.firstOrNull {
                val name = normalizeName(it.name)
                name.contains("经营") || name.contains("商户")
            }
            else -> available.firstOrNull { it.key == key }
        }
    }

    fun captureResponse(context: Context?, response: JSONObject?): List<TransferReceiveAccount> {
        if (context == null || response == null) return emptyList()
        val result = ArrayList<TransferReceiveAccount>()
        collectAccounts(response, result)
        val distinct = result.distinctBy { it.key }
        if (distinct.isNotEmpty()) save(context, distinct)
        return distinct
    }

    private fun collectAccounts(value: Any?, result: MutableList<TransferReceiveAccount>) {
        when (value) {
            is JSONObject -> {
                result += parseItem(value)
                val keys = value.keys()
                while (keys.hasNext()) {
                    collectAccounts(value.opt(keys.next()), result)
                }
            }
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    collectAccounts(value.opt(index), result)
                }
            }
        }
    }

    private fun captureItem(context: Context, json: JSONObject) {
        val parsed = parseItem(json)
        if (parsed.isEmpty()) return
        save(context, list(context) + parsed)
    }

    private fun parseItem(json: JSONObject): List<TransferReceiveAccount> {
        if (!json.has("recv_channel_type") || !json.has("recv_channel_name")) return emptyList()
        val type = json.optInt("recv_channel_type", 0)
        val name = json.optString("recv_channel_name").trim()
        if (name.isBlank()) return emptyList()
        val available = json.optInt("recv_channel_avail_state", 1) == 1
        val bindSerial = json.optString("bind_serial")
        val subInfo = json.optJSONObject("sub_recv_channel_info")
        val defaultSubId = subInfo?.optLong("default_sub_recv_channel_id", 0L) ?: 0L
        return buildList {
            add(TransferReceiveAccount(
                key = accountKey(type, defaultSubId),
                name = name,
                accountType = type,
                subChannelId = defaultSubId,
                bindSerial = bindSerial,
                available = available
            ))
            val subList = subInfo?.optJSONArray("sub_recv_channel_list")
            if (subList != null) {
                for (index in 0 until subList.length()) {
                    val sub = subList.optJSONObject(index) ?: continue
                    val subId = sub.optLong("id", 0L)
                    val subName = sub.optString("name").trim()
                    if (subName.isBlank()) continue
                    add(TransferReceiveAccount(
                        key = accountKey(type, subId),
                        name = "$name · $subName",
                        accountType = type,
                        subChannelId = subId,
                        bindSerial = bindSerial,
                        available = available
                    ))
                }
            }
        }
    }

    private fun save(context: Context, values: List<TransferReceiveAccount>) {
        val accounts = values.associateByTo(LinkedHashMap()) { it.key }
        val array = JSONArray()
        accounts.values.forEach { account ->
            array.put(JSONObject().apply {
                put("name", account.name)
                put("accountType", account.accountType)
                put("subChannelId", account.subChannelId)
                put("bindSerial", account.bindSerial)
                put("available", account.available)
            })
        }
        HchatStorage.preferences(context, AutoTransferSettings.PREFS_NAME)
            .edit()
            .putString(accountsKey(), array.toString())
            .apply()
    }

    private fun locateParser(
        context: FeatureContext,
        logger: (String, Throwable?) -> Unit
    ): Method? {
        return runCatching {
            context.dexKitBridge().findMethod(
                FindMethod().apply {
                    matcher(MethodMatcher().apply {
                        usingEqStrings(
                            "recv_channel_type",
                            "recv_channel_name",
                            "sub_recv_channel_info"
                        )
                    })
                }
            ).mapNotNull { data ->
                runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull()
            }.firstOrNull(::isAccountParser)
        }.getOrElse {
            logger("定位转账收款账户解析方法失败", it)
            null
        }
    }

    private fun isAccountParser(method: Method?): Boolean {
        if (method == null || !Modifier.isStatic(method.modifiers)) return false
        val params = method.parameterTypes
        return params.size == 1 && params[0] == JSONObject::class.java && method.returnType != Void.TYPE
    }

    private fun accountKey(type: Int, subId: Long): String = "$type:$subId"

    private fun normalizeName(value: String): String = value.replace(" ", "").trim()

    private fun accountsKey(): String {
        val wxId = WeChatApis.contact().account()?.selfWxId().orEmpty().trim()
        return if (wxId.isBlank()) KEY_ACCOUNTS else "$KEY_ACCOUNTS.$wxId"
    }
}
