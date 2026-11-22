package com.Johnny.wcx.features.items.chat

import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.Modifiers
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexClass
import com.Johnny.wcx.dexkit.dsl.dexField
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.Feature

import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.WeLogger
import org.luckypray.dexkit.DexKitBridge

@Feature(
    name = "强制启用所有功能",
    categories = ["聊天"],
    description = "在聊天工具面板中强制启用所有功能"
)
object ForceEnableAllTools : SwitchFeature(), IResolveDex {

    private const val TAG = "ForceEnableAllTools"

    private val classAppPanelConfig by dexClass()
    private val fieldAppPanelConfig by dexField()
    private val fieldFirstFlag by dexField()
    private val fieldFlagEnabled by dexField()
    private val methodRefreshAppPanel by dexMethod()

    override fun onEnable() {
        methodRefreshAppPanel.hookBefore {
            val appPanel = thisObject ?: return@hookBefore
            val config = fieldAppPanelConfig.field.get(appPanel) ?: return@hookBefore
            config.reflekt().fields {
                type = fieldFirstFlag.field.type
            }.forEach {
                fieldFlagEnabled.field.setBoolean(it.get()!!, true)
            }
        }
    }

    override fun resolveDex(dexKit: DexKitBridge) {
        val refreshMethod = dexKit.findMethod {
            searchPackages(APP_PANEL_PACKAGE)
            matcher {
                declaredClass = APP_PANEL_CLASS
                usingEqStrings("MicroMsg.AppPanel", "roomEnable:%s, hideRoomLive:%s")
                paramCount = 0
                returnType = "void"
            }
        }.singleOrNull() ?: run {
            WeLogger.e(TAG, "定位 AppPanel 刷新方法失败, 功能不可用")
            return
        }
        methodRefreshAppPanel.setDescriptor(refreshMethod)

        val usedFields = refreshMethod.usingFields.map { it.field }.distinct()
        val firstFlag = usedFields.firstOrNull { field ->
            field.modifiers and Modifiers.FINAL != 0 &&
                field.type.fields.count { it.typeName == "boolean" } == 1
        } ?: run {
            WeLogger.e(TAG, "定位 AppPanel 配置标记字段失败, 功能不可用")
            return
        }
        val configClass = firstFlag.declaredClass
        val appPanelConfig = usedFields.firstOrNull { field ->
            field.className == APP_PANEL_CLASS && field.typeName == configClass.name
        } ?: run {
            WeLogger.e(TAG, "定位 AppPanel 配置字段失败, 功能不可用")
            return
        }
        val flagEnabled = firstFlag.type.fields.firstOrNull {
            it.typeName == "boolean"
        } ?: run {
            WeLogger.e(TAG, "定位 AppPanel 开关字段失败, 功能不可用")
            return
        }

        classAppPanelConfig.setDescriptor(configClass)
        fieldAppPanelConfig.setDescriptor(appPanelConfig)
        fieldFirstFlag.setDescriptor(firstFlag)
        fieldFlagEnabled.setDescriptor(flagEnabled)
    }

    private const val APP_PANEL_PACKAGE = "com.tencent.mm.pluginsdk.ui.chat"
    private const val APP_PANEL_CLASS = "$APP_PANEL_PACKAGE.AppPanel"
}
