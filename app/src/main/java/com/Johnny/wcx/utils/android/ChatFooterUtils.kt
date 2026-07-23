package com.Johnny.wcx.utils.android

import android.content.Context
import android.util.AttributeSet
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import com.Johnny.reflekt.reflected.ReflectedConstructor
import com.Johnny.reflekt.reflekt
import com.Johnny.wcx.utils.reflection.BInt
import com.Johnny.wcx.utils.reflection.int
import kotlin.reflect.KClass

val KClass<ChatFooter>.constructor: ReflectedConstructor<ChatFooter>
    get() {
        return reflekt().run {
            firstConstructorOrNull {
                parameters(Context::class, AttributeSet::class, int)
            } ?: firstConstructor {
                parameters(Context::class, AttributeSet::class, BInt)
            }
        }
    }
