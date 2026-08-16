package com.Johnny.wcx.features.items.moments

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.ujhhgtg.reflekt.utils.Modifiers
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.api.ui.WeMomentsApi
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.fs.asPath
import com.Johnny.wcx.utils.reflection.BBool
import com.Johnny.wcx.utils.reflection.BString
import kotlin.io.path.copyTo

@Feature(
    name = "上传原图",
    categories = ["朋友圈"],
    description = "上传时不压缩图片, 过大可能上传失败"
)
object NoCompressUploadedImages : ClickableFeature(), IResolveDex {

    private const val MODE_CONVERT = 0
    private const val MODE_COPY = 1

    private var selectedMode by WePrefs.prefOption("no_compress_mode", MODE_CONVERT)

    private val methodCreatePic by dexMethod {
        matcher {
            usingEqStrings(
                "MicroMsg.snsMediaStorage",
                "SnsCompressResolutionFor2G",
                "SnsCompressResolutionFor3G",
                "SnsCompressResolutionFor4G",
                "SnsCompressResolutionForWifi"
            )
        }
    }

    private val methodConvertImg2WxamWithoutZip by dexMethod {
        matcher {
            paramTypes("java.lang.String", "java.lang.String")
            usingEqStrings(
                "MicroMsg.snsMediaStorage",
                "convertImg2WxamWithoutZip origPath:%s OutOfMemoryError! rollback"
            )
        }
    }

    private val vfsGetCachePathMethod by lazy {
        WeMomentsApi.classVfs.reflekt().firstMethod {
            modifiers(Modifiers.STATIC)
            parameters(BString, BBool)
            returnType = BString
        }
    }

    override fun onEnable() {
        methodCreatePic.hookBefore {
            if (selectedMode == MODE_CONVERT) {
                val str6 = args[0] as? String ?: ""
                val str8 = args[1] as? String ?: ""
                val str = args[2] as? String ?: ""
                val strConcat = str6 + str

                val resultBool = methodConvertImg2WxamWithoutZip.method.invoke(null, str8, strConcat) as? Boolean ?: false
                result = resultBool
            }
        }

        methodCreatePic.hookAfter {
            if (selectedMode == MODE_COPY) {
                val str11 = args[0] as? String ?: ""
                val str13 = args[1] as? String ?: ""
                val str = args[2] as? String ?: ""
                val isUpload = args[3] as? Boolean ?: false

                if (isUpload) {
                    val src = str13.asPath
                    val strConcat2 = str11 + str
                    val cachePath = vfsGetCachePathMethod.invoke(null, strConcat2, true) as? String
                    if (cachePath != null) {
                        val dst = cachePath.asPath
                        src.copyTo(dst)
                    }
                }
            }
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var mode by remember { mutableIntStateOf(selectedMode) }

            AlertDialogContent(
                title = { Text("上传原图") },
                text = {
                    DefaultColumn {
                        ListItem(
                            modifier = Modifier.clickable {
                                mode = MODE_CONVERT
                            },
                            trailingContent = { RadioButton(mode == MODE_CONVERT, null) },
                            supportingContent = { Text("直接转换格式, 质量最高且速度快") },
                            headlineContent = { Text("不压缩转换 (推荐)") },
                        )

                        ListItem(
                            modifier = Modifier.clickable {
                                mode = MODE_COPY
                            },
                            trailingContent = { RadioButton(mode == MODE_COPY, null) },
                            supportingContent = { Text("用原图覆盖压缩后的缓存") },
                            headlineContent = { Text("原图覆盖") },
                        )
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text("取消") }
                },
                confirmButton = {
                    Button(onClick = {
                        selectedMode = mode
                        onDismiss()
                    }) { Text("确定") }
                }
            )
        }
    }
}
