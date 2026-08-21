package com.Johnny.wcx.utils.android

import android.content.Context
import android.util.AttributeSet
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import dev.ujhhgtg.reflekt.reflected.ReflectedConstructor
import dev.ujhhgtg.reflekt.reflekt
import com.Johnny.wcx.utils.reflection.int
import kotlin.reflect.KClass

val KClass<ChatFooter>.constructor: ReflectedConstructor<ChatFooter>
    get() {
        return reflekt().firstConstructor {
            parameters(Context::class, AttributeSet::class, int)
        }
    }
