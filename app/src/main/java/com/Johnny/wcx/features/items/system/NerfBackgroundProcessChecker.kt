package com.Johnny.wcx.features.items.system

import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature

@Feature(
    name = "禁用微信进程状态检测器",
    categories = ["系统与隐私"],
    description = "微信会在后台每隔一段时间检测微信主进程状态, 并在特定条件下故意抛出异常结束主进程\n虽然我不知道这玩意有啥用和是否应该关掉, 但「崩溃拦截」会把这玩意算进去, 有点烦, 所以如果你想关的话这里可以关"
)
object NerfBackgroundProcessChecker : SwitchFeature(), IResolveDex {

    private val methodPerformProcessCheck by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.AbstractProcessChecker", "pass this check,because request is null! ????")
        }
    }

    override fun onEnable() {
        methodPerformProcessCheck.hookBefore {
            try {
                // 仅当原方法返回 void 时才设置 result = null，避免对非 void 方法（如 getInstance）造成 ClassCastException
                if (method is java.lang.reflect.Method) {
                val returnType = (method as java.lang.reflect.Method).returnType
                if (returnType == Void.TYPE) {
                    result = null
                }
            }
            } catch (e: Throwable) {
                // 兜底异常捕获
            }
        }
    }
}
