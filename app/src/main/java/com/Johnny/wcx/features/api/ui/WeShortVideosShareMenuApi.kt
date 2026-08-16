package com.Johnny.wcx.features.api.ui

import android.graphics.drawable.Drawable
import android.view.ContextMenu
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.reflekt.reflekt
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.ApiFeature
import com.Johnny.wcx.features.core.Feature
import org.json.JSONObject
import java.util.LinkedList

@Feature(name = "视频号分享菜单扩展", categories = ["API"], description = "为视频号分享菜单提供添加菜单项功能")
object WeShortVideosShareMenuApi : ApiFeature(), IResolveDex {

    fun interface IMenuItemsProvider {
        fun getMenuItems(): List<MenuItem>
    }

    data class MenuItem(
        val id: Int,
        val text: String, val drawable: Drawable,
        val onClick: (XC_MethodHook.MethodHookParam, Int, List<JSONObject>) -> Unit
    )

    private val menuItems = mutableMapOf<String, List<MenuItem>>()

    fun addProvider(provider: IMenuItemsProvider) {
        menuItems[provider.javaClass.name] = provider.getMenuItems()
    }

    fun removeProvider(provider: IMenuItemsProvider) {
        menuItems.remove(provider.javaClass.name)
    }

    private val methodCreateMenu1 by dexMethod {
        searchPackages("com.tencent.mm.plugin.finder.feed")
        matcher {
            name = "onCreateMMMenu"
            usingEqStrings("pos is error ")
        }
    }
    private val methodOnSelectMenuItem1 by dexMethod {
        searchPackages("com.tencent.mm.plugin.finder.feed")
        matcher {
            name = "onMMMenuItemSelected"
            usingEqStrings("[getMoreMenuItemSelectedListener] feed ")
        }
    }
    private val methodCreateMenu2 by dexMethod {
        searchPackages("com.tencent.mm.plugin.finder.feed")
        matcher {
            usingEqStrings("feed", "menu", "sheet", "holder", "KEY_FINDER_SELF_FLAG")
        }
    }
    private val methodOnSelectMenuItem2 by dexMethod {
        searchPackages("com.tencent.mm.plugin.finder.feed")
        matcher {
            declaredClass {
                usingEqStrings("Finder.FinderLoaderFeedUIContract.Presenter")
            }

            usingEqStrings("getMoreMenuItemSelectedListener feed ")
        }
    }
    private val methodCreateMenu3 by dexMethod {
        searchPackages("com.tencent.mm.plugin.finder.feed")
        matcher {
            name = "onCreateMMMenu"
            usingEqStrings("getCreateSecondMoreMenuListener: username=")
        }
    }
    private val methodOnSelectMenuItem3 by dexMethod {
        searchPackages("com.tencent.mm.plugin.finder.feed")
        matcher {
            name = "onMMMenuItemSelected"
            usingEqStrings("button_speedplay", "ref_eid")
        }
    }

    override fun onEnable() {
        methodCreateMenu1.hookBefore {
            val menu = args[0] as ContextMenu
            handleCreateMenu(menu)
        }

        methodOnSelectMenuItem1.hookBefore {
            val menuItem = args[0] as android.view.MenuItem
            val baseFinderFeed = thisObject.reflekt()
                .firstField {
                    type = "com.tencent.mm.plugin.finder.model.BaseFinderFeed"
                }
                .get()!!
            handleOnSelectMenuItem(this, menuItem, baseFinderFeed)
        }

        methodCreateMenu2.hookBefore {
            val menu = args[1] as ContextMenu
            handleCreateMenu(menu)
        }

        methodOnSelectMenuItem2.hookBefore {
            val menuItem = args[1] as android.view.MenuItem
            val baseFinderFeed = args[0]
            handleOnSelectMenuItem(this, menuItem, baseFinderFeed)
        }

        methodCreateMenu3.hookBefore {
            val menu = args[0] as ContextMenu
            handleCreateMenu(menu)
        }

        methodOnSelectMenuItem3.hookBefore {
            val menuItem = args[0] as android.view.MenuItem
            val baseFinderFeed = thisObject.reflekt()
                .firstField {
                    type = "com.tencent.mm.plugin.finder.model.BaseFinderFeed"
                }
                .get()!!
            handleOnSelectMenuItem(this, menuItem, baseFinderFeed)
        }
    }

    private fun handleCreateMenu(
        menu: ContextMenu
    ) {
        for (item in menuItems.values.flatten()) {
            menu.reflekt()
                .firstMethod {
                    parameters(Int::class, CharSequence::class, Drawable::class)
                }
                .invoke(item.id, item.text, item.drawable)
        }
    }

    private fun handleOnSelectMenuItem(
        param: XC_MethodHook.MethodHookParam,
        menuItem: android.view.MenuItem,
        baseFinderFeed: Any
    ) {
        val itemId = menuItem.itemId
        val finderItem = baseFinderFeed.reflekt()
            .firstField {
                name = "feedObject"
                superclass()
            }
            .get()!!
        val mediaType = finderItem.reflekt()
            .firstMethod {
                name = "getMediaType"
            }
            .invoke()!! as Int
        val mediaList = finderItem.reflekt()
            .firstMethod {
                name = "getMediaList"
            }
            .invoke() as LinkedList<*>
        val mediaJsonList = mediaList.map { media ->
            media.reflekt()
                .firstMethod {
                    name = "toJSON"
                    superclass()
                }.invoke()!! as JSONObject
        }

        for (item in menuItems.values.flatten()) {
            if (item.id == itemId) {
                item.onClick(param, mediaType, mediaJsonList)
                try {
                    // 仅当原方法返回 void 时才设置 result = null
                    if (param.method is java.lang.reflect.Method) {
                        val returnType = (param.method as java.lang.reflect.Method).returnType
                        if (returnType == Void.TYPE) {
                            param.result = null
                        }
                    }
                } catch (e: Throwable) {
                    // 兜底异常捕获，防止单条 Hook 异常导致微信主线程崩溃
                }
                return
            }
        }
    }
}
