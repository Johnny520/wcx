package com.Johnny.wcx.features.api.ui

import android.graphics.drawable.Drawable
import android.util.SparseArray
import android.widget.BaseAdapter
import android.widget.ImageView
import androidx.collection.mutableIntObjectMapOf
import androidx.core.util.size
import de.robv.android.xposed.XC_MethodHook
import com.Johnny.reflekt.reflekt
import com.Johnny.reflekt.utils.createInstance
import com.Johnny.reflekt.utils.isSubclassOf
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexClass
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.ApiFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.runOnUiThread
import com.Johnny.wcx.utils.hookBeforeDirectly
import com.Johnny.wcx.utils.reflection.BBool
import com.Johnny.wcx.utils.reflection.BInt
import com.Johnny.wcx.utils.reflection.BString
import java.util.concurrent.CopyOnWriteArrayList

@Feature(name = "首页菜单服务", categories = ["API"], description = "提供向首页右上角菜单添加菜单项的能力")
object WeHomeScreenPopupMenuApi : ApiFeature(), IResolveDex {

    interface IMenuItemsProvider {
        fun getMenuItems(param: XC_MethodHook.MethodHookParam): List<MenuItem>
    }

    data class MenuItem(
        val id: Int,
        val text: String, val drawable: Drawable,
        val onClick: () -> Unit
    ) {
        val fakeResId get() = id + text.hashCode()
    }

    private val providers = CopyOnWriteArrayList<IMenuItemsProvider>()

    fun addProvider(provider: IMenuItemsProvider) {
        providers.addIfAbsent(provider)
    }

    fun removeProvider(provider: IMenuItemsProvider) {
        providers.remove(provider)
    }

    private const val TAG = "WeHomeScreenPopupMenuApi"

    private val fakeResIdToResMap = mutableIntObjectMapOf<Drawable>()

    private val methodAddItem by dexMethod {
        searchPackages("com.tencent.mm.ui")
        matcher {
            usingEqStrings(
                "MicroMsg.PlusSubMenuHelper",
                "dyna plus config is null, we use default one"
            )
        }
    }
    private val methodHandleItemClick by dexMethod {
        searchPackages("com.tencent.mm.ui")
        matcher {
            usingEqStrings("MicroMsg.PlusSubMenuHelper", "processOnItemClick")
        }
    }
    private val classMenuItemData by dexClass {
        searchPackages("com.tencent.mm.ui")
        matcher {
            addFieldForType(BString)
            addFieldForType(BInt)
            addFieldForType(BInt)
            addFieldForType(BInt)
            addFieldForType(BString)
            fieldCount(5)
            methods {
                add {
                    usingEqStrings("")
                }
            }
        }
    }
    private val classMenuItemWrapper by dexClass {
        searchPackages("com.tencent.mm.ui")
        matcher {
            addFieldForType(BBool)
            addFieldForType(classMenuItemData.clazz)
        }
    }

    override fun onEnable() {
        // WeChat 8.0.70 moved this to com.tencent.mm.ui.HomeUI
        methodAddItem.hookAfter {
            var thisObj = thisObject

            if (thisObj.javaClass.simpleName == "HomeUI") {
                thisObj = thisObj.reflekt()
                    .firstField { type = methodHandleItemClick.method.declaringClass }
                    .get()!!
            }

            @Suppress("UNCHECKED_CAST")
            val items = thisObj.reflekt()
                .firstField {
                    type = SparseArray::class
                }
                .get()!! as SparseArray<Any>
            val baseAdapter = thisObj.reflekt()
                .firstField {
                    type { it isSubclassOf BaseAdapter::class }
                }
                .get()!! as BaseAdapter

            baseAdapter.reflekt().firstMethod {
                name = "getView"
            }.apply {
                var unhook: XC_MethodHook.Unhook? = null

                hookBefore {
                    unhook = ImageView::class.reflekt().firstMethod {
                        name = "setImageResource"
                    }.hookBeforeDirectly {
                        val fakeResId = args[0] as Int
                        val imageView = thisObject as ImageView
                        imageView.setImageDrawable(fakeResIdToResMap[fakeResId] ?: return@hookBeforeDirectly)
                        result = null
                    }
                }

                hookAfter {
                    unhook!!.unhook()
                }
            }

            for (provider in providers) {
                try {
                    for (item in provider.getMenuItems(this)) {
                        fakeResIdToResMap[item.fakeResId] = item.drawable

                        val itemData = classMenuItemData.clazz.createInstance(
                            item.id,
                            item.text,
                            "",
                            item.fakeResId,
                            0
                        )
                        val itemWrapper =
                            classMenuItemWrapper.clazz.createInstance(itemData)
                        items.put(items.size, itemWrapper)

                        runOnUiThread {
                            baseAdapter.notifyDataSetChanged()
                        }
                    }
                } catch (ex: Exception) {
                    WeLogger.e(
                        TAG,
                        "provider ${provider.javaClass.name} threw while providing menu items",
                        ex
                    )
                }
            }

            runOnUiThread {
                baseAdapter.notifyDataSetChanged()
            }
        }

        methodHandleItemClick.hookBefore {
            val thisObj = thisObject

            @Suppress("UNCHECKED_CAST")
            val items = thisObj.reflekt()
                .firstField {
                    type = SparseArray::class
                }
                .get()!! as SparseArray<Any>
            val position = args[2] as Int
            val itemWrapper = items.get(position)
            val itemData = itemWrapper.reflekt()
                .firstField { type = classMenuItemData.clazz }.get()!!
            val id = itemData.reflekt()
                .fields { type = Int::class }[1].get()!! as Int

            for (provider in providers) {
                for (item in provider.getMenuItems(this)) {
                    if (item.id == id) {
                        try {
                            item.onClick()
                            return@hookBefore
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
}
