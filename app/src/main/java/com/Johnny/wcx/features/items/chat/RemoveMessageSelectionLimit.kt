package com.Johnny.wcx.features.items.chat

import dev.ujhhgtg.reflekt.utils.makeAccessible
import com.Johnny.wcx.constants.PackageNames
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexClass
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.hookDirectly
import de.robv.android.xposed.XC_MethodHook
import com.Johnny.wcx.utils.reflection.bool
import com.Johnny.wcx.utils.reflection.int
import com.Johnny.wcx.utils.reflection.void
import java.lang.reflect.Field
import java.util.concurrent.CopyOnWriteArraySet

@Feature(
    name = "解除消息多选数量限制",
    categories = ["聊天"],
    description = "解除聊天界面消息多选至多只能选择 100 条的限制"
)
object RemoveMessageSelectionLimit : SwitchFeature(), IResolveDex {

    private const val SELECTION_LIMIT = 100

    private val methodToggleMessageSelection by dexMethod(allowFailure = true) {
        matcher {
            declaredClass(WeMessageApi.classChattingDataAdapter.clazz)
            usingNumbers(SELECTION_LIMIT)
            paramTypes("${PackageNames.WECHAT}.plugin.msg.MsgIdTalker")
            returnType(bool)
        }
    }

    private val methodGetSelectedMessageCount by dexMethod(allowFailure = true) {
        matcher {
            declaredClass(WeMessageApi.classChattingDataAdapter.clazz)
            addUsingField {
                type(CopyOnWriteArraySet::class.java)
            }
            paramCount(0)
            returnType(int)
        }
    }

    private val classChatItemQuickSelect by dexClass {
        searchPackages("${PackageNames.WECHAT}.ui.chatting.component")
        matcher {
            usingEqStrings(
                "MicroMsg.ChatItemQuickSelectComponent",
                "initViews: chattingQuickSelectRootUp="
            )
        }
    }

    private val methodSetQuickSelectViewEnabled1 by dexMethod(
        allowMultiple = true,
        allowFailure = true,
        resultIndex = 0
    ) {
        matcher {
            declaredClass(classChatItemQuickSelect.clazz)
            usingNumbers(SELECTION_LIMIT)
            paramTypes(bool)
            returnType(void)
        }
    }

    private val methodSetQuickSelectViewEnabled2 by dexMethod(
        allowMultiple = true,
        allowFailure = true,
        resultIndex = 1
    ) {
        matcher {
            declaredClass(classChatItemQuickSelect.clazz)
            usingNumbers(SELECTION_LIMIT)
            paramTypes(bool)
            returnType(void)
        }
    }

    private val selectedMessagesField: Field by lazy {
        methodToggleMessageSelection.method.declaringClass.declaredFields.single {
            it.type == CopyOnWriteArraySet::class.java
        }.makeAccessible()
    }

    private data class TemporarilyRemovedSelections(
        val selectedMessages: CopyOnWriteArraySet<Any>,
        val removed: List<Any>
    )

    private val selectedMessageCountOverride = ThreadLocal<Int>()

    // 本地 Xposed 桥的 MethodHookParam.extra 为 val+Bundle（非 fork 的 Any），
    // 临时状态改用 ThreadLocal 传递（与 selectedMessageCountOverride 同风格）
    private val tempRemovedSelections = ThreadLocal<TemporarilyRemovedSelections?>()

    override fun onEnable() {
        // 8.0.77: ChattingDataAdapterV3 已移除, 相关 matcher 降级 placeholder, 本功能禁用
        if (WeMessageApi.classChattingDataAdapter.isPlaceholder) {
            WeLogger.w("RemoveMessageSelectionLimit", "ChattingDataAdapterV3 not found, feature disabled")
            return
        }
        listOf(
            methodSetQuickSelectViewEnabled1,
            methodSetQuickSelectViewEnabled2
        ).forEach {
            it.hookBefore {
                args[0] = true
            }
        }

        methodGetSelectedMessageCount.hookBefore {
            selectedMessageCountOverride.get()?.let {
                result = it
            }
        }

        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                val adapter = param.thisObject ?: return
                val message = param.args[0] ?: return
                @Suppress("UNCHECKED_CAST")
                val selectedMessages = selectedMessagesField.get(adapter) as CopyOnWriteArraySet<Any>
                if (message in selectedMessages || selectedMessages.size < SELECTION_LIMIT) return

                // Let WeChat run its original add and UI refresh path with 99 existing selections.
                val removed = selectedMessages.take(selectedMessages.size - SELECTION_LIMIT + 1)
                selectedMessages.removeAll(removed.toSet())
                tempRemovedSelections.set(TemporarilyRemovedSelections(selectedMessages, removed))
                selectedMessageCountOverride.set(selectedMessages.size + removed.size + 1)
            }

            override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                val state = tempRemovedSelections.get() ?: return
                val remainingAndNew = state.selectedMessages.toList()
                state.selectedMessages.clear()
                state.selectedMessages.addAll(state.removed)
                state.selectedMessages.addAll(remainingAndNew)
                selectedMessageCountOverride.remove()
            }
        }

        registerUnhook(methodToggleMessageSelection.method.hookDirectly(hook))
    }
}
