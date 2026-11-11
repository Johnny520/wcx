package com.highcapable.kavaref.runtime

internal class DefaultLogger : KavaRefRuntime.Logger {
    companion object {
        fun init(value: KavaRefRuntime.LogLevel) {
        }
    }

    override val tag: String = "KavaRef"

    override fun debug(msg: Any?, throwable: Throwable?) {
    }

    override fun info(msg: Any?, throwable: Throwable?) {
    }

    override fun warn(msg: Any?, throwable: Throwable?) {
    }

    override fun error(msg: Any?, throwable: Throwable?) {
    }
}
