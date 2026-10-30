package com.Johnny.wcx.features.items.contacts

import android.view.MenuItem
import androidx.activity.ComponentActivity
import dev.ujhhgtg.reflekt.reflekt
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.api.net.models.protobuf.NearbyFriendProto
import com.Johnny.wcx.features.api.net.models.protobuf.WeProto
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.reflection.int
import java.util.LinkedList

@Feature(name = "自动添加附近的人", categories = ["联系人与群组"], description = "在附近的人菜单中添加菜单项, 可全自动向附近的人按模板发送消息 (没写完)")
object AutoAddNearbyFriends : ClickableFeature(), IResolveDex {

    private const val TAG = "AutoAddNearbyFriends"

    // 锚点允许失败：微信 8.0.79 起「附近的人」页面改版，
    // NearbyPersonUIC / NearbySayHiListUI 两个特征串已不存在（实测 8.0.71~8.0.78 才有）。
    // 允许失败后走占位降级，避免整个功能加载抛异常。
    private val methodCreateMenu by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("NearbyPersonUIC", "showLiveBottomSheet create menu.")
        }
    }

    private val methodMenuOnClick by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("com.tencent.mm.plugin.nearby.ui.NearbySayHiListUI")
            name = "onMMMenuItemSelected"
        }
    }

    /**
     * 8.0.79 起「附近的人」页面改版，两个锚点串均不存在，整个功能无可用界面。
     * 返回非空后设置页会拒绝开启并直接提示，避免用户开了开关却毫无反应。
     */
    override val unavailableReason: String?
        get() = if (methodCreateMenu.isPlaceholder || methodMenuOnClick.isPlaceholder) {
            "当前微信版本（8.0.79+）「附近的人」页面已改版，该功能不可用"
        } else null

    override fun onEnable() {
        if (methodCreateMenu.isPlaceholder || methodMenuOnClick.isPlaceholder) {
            WeLogger.w(
                TAG,
                "当前微信版本未找到「附近的人」菜单锚点（8.0.79 起页面改版），功能自动跳过"
            )
            return
        }

        methodCreateMenu.hookBefore {
            args[0].reflekt().firstMethod {
                parameters(int, CharSequence::class)
            }.invoke(6, "自动加好友")
        }

        methodMenuOnClick.hookBefore {
            val menuItem = args[0] as MenuItem
            val itemId = menuItem.itemId
            if (itemId != 6) return@hookBefore

            val controller = thisObject.reflekt().firstField().get()!!
            val friends = controller.reflekt().firstField {
                type = List::class
            }.get()!! as LinkedList<*>

            val friendProtos = friends.map {
                WeProto.decode<NearbyFriendProto>(
                    it.reflekt().invokeMethod("toByteArray", superclass = true) as ByteArray
                )
            }

            try {
                // 仅当原方法返回 void 时才设置 result = null
                if (method is java.lang.reflect.Method) {
                    val returnType = (method as java.lang.reflect.Method).returnType
                    if (returnType == Void.TYPE) {
                        result = null
                    }
                }
            } catch (e: Throwable) {
                // 兜底异常捕获，防止单条 Hook 异常导致微信主线程崩溃
            }
        }
    }

    override fun onClick(context: ComponentActivity) {

    }
}
