package com.Johnny.wcx.features.api.ui

import android.app.Activity
import android.content.Context
import android.widget.BaseAdapter
import com.tencent.mm.chatroom.ui.ChatroomInfoUI
import com.tencent.mm.plugin.profile.ui.ContactInfoUI
import com.tencent.mm.ui.base.preference.MMPreference
import com.tencent.mm.ui.base.preference.Preference
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.Modifiers
import dev.ujhhgtg.reflekt.utils.isSubclassOf
import dev.ujhhgtg.reflekt.utils.makeAccessible
import com.Johnny.wcx.features.core.ApiFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.utils.WeLogger
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.CopyOnWriteArrayList

@Feature(name = "联系人页面扩展", categories = ["API"])
object WeContactPrefsScreenApi : ApiFeature() {

    interface IContactInfoProvider {
        fun getContactInfoItem(activity: Activity): List<PreferenceItem>
        fun onItemClick(activity: Activity, key: String): Boolean
    }

    data class PreferenceItem(
        val key: String,
        val title: String,
        val summary: String? = null,
        val position: Int = -1,
        /** 若给出，则插到「key 里包含该片段的 Preference」之后（优先于 [position]）。 */
        val afterKeyPart: String? = null,
    )

    private const val TAG = "WeContactPrefsScreenApi"

    private val providers = CopyOnWriteArrayList<IContactInfoProvider>()

    fun addProvider(provider: IContactInfoProvider) {
        providers.addIfAbsent(provider)
    }

    fun removeProvider(provider: IContactInfoProvider) {
        providers.remove(provider)
    }

    private lateinit var prefConstructor: Constructor<*>
    private lateinit var prefKeyField: Field
    private lateinit var adapterField: Field
    private lateinit var addPreferenceMethod: Method
    private lateinit var setKeyMethod: Method
    private lateinit var setSummaryMethod: Method
    private lateinit var setTitleMethod: Method

    override fun onEnable() {
        initReflection()

        listOf(
            ContactInfoUI::class,
            ChatroomInfoUI::class
        ).forEach {
            it.reflekt().apply {
                firstMethod { name = "initView" }
                    .hookAfter {
                        val adapterInstance = adapterField.get(thisObject as Activity)
                        for (provider in providers) {
                            try {
                                val items = provider.getContactInfoItem(thisObject as Activity)
                                for (item in items) {
                                    val pref = prefConstructor.newInstance(thisObject as Context)
                                    setKeyMethod.invoke(pref, item.key)
                                    setTitleMethod.invoke(pref, item.title)
                                    item.summary?.let { summary -> setSummaryMethod.invoke(pref, summary) }
                                    // position < 0 表示追加到末尾；大于现有条目数时夹到末尾，
                                    // 避免 IndexOutOfBounds 把整行吞掉。
                                    val count = runCatching { (adapterInstance as? BaseAdapter)?.count }
                                        .getOrDefault(0)
                                    var pos = if (item.position < 0) count
                                    else item.position.coerceIn(0, count)
                                    item.afterKeyPart?.let { part ->
                                        val idx = findPreferenceIndex(adapterInstance, count, part)
                                        if (idx >= 0) pos = (idx + 1).coerceIn(0, count)
                                    }
                                    addPreferenceMethod.invoke(adapterInstance, pref, pos)
                                }
                            } catch (ex: Exception) {
                                WeLogger.e(
                                    TAG,
                                    "provider ${provider.javaClass.name} threw while providing contact info item",
                                    ex
                                )
                            }
                        }
                    }

                firstMethod {
                    name = "onPreferenceTreeClick"
                }.hookBefore {
                    val preference = args[1] ?: return@hookBefore
                    val key = prefKeyField.get(preference) as? String ?: return@hookBefore
                    for (provider in providers) {
                        try {
                            if (provider.onItemClick(thisObject as Activity, key)) {
                                // 仅当原方法返回 boolean 时才设置 result = true
                                if (method is java.lang.reflect.Method) {
                                    val returnType = (method as java.lang.reflect.Method).returnType
                                    if (returnType == Boolean::class.javaPrimitiveType || returnType == java.lang.Boolean::class.java) {
                                        result = true
                                    }
                                }
                                return@hookBefore
                            }
                        } catch (ex: Exception) {
                            WeLogger.e(
                                TAG,
                                "provider ${provider.javaClass.name} threw while handling click event",
                                ex
                            )
                        }
                    }
                }
            }
        }
    }

    /** 在已存在的 Preference 里找「key 含指定片段」的那一条，用于把新条目插到它后面。 */
    private fun findPreferenceIndex(adapter: Any, count: Int, keyPart: String): Int {
        val getItem = adapter.javaClass.methods.firstOrNull {
            it.name == "getItem" && it.parameterTypes.size == 1
        } ?: return -1
        for (i in 0 until count) {
            val item = runCatching { getItem.invoke(adapter, i) }.getOrNull() ?: continue
            val key = runCatching { prefKeyField.get(item) as? String }.getOrNull() ?: continue
            if (key.contains(keyPart, ignoreCase = true)) return i
        }
        return -1
    }

    private fun initReflection() {
        prefConstructor = Preference::class.reflekt()
            .firstConstructor {
                parameters(Context::class)
            }.self

        prefKeyField = Preference::class.reflekt()
            .firstField {
                type = String::class
                modifiers { !it.contains(Modifiers.FINAL) }
            }.self.makeAccessible()

        adapterField = MMPreference::class.reflekt()
            .firstField {
                modifiers { !it.contains(Modifiers.STATIC) }
                type { it isSubclassOf BaseAdapter::class }
            }.self.makeAccessible()

        addPreferenceMethod = adapterField.type.reflekt()
            .firstMethod {
                modifiers { !it.contains(Modifiers.FINAL) }
                parameters(Preference::class, Int::class)
            }.self

        setKeyMethod = Preference::class.reflekt()
            .firstMethod {
                parameters(String::class)
                returnType = Void.TYPE
            }.self

        val charSeqMethods = Preference::class.reflekt()
            .methods {
                parameters(CharSequence::class)
            }.map { it.self }

        setSummaryMethod = charSeqMethods.getOrElse(0) { error("setSummary method not found") }
        setTitleMethod = charSeqMethods.getOrElse(1) { error("setTitle method not found") }
    }
}
