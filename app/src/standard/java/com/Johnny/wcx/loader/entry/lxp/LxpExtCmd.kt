package com.Johnny.wcx.loader.entry.lxp

import io.github.libxposed.api.XposedInterface

object LxpExtCmd {

    fun handleQueryExtension(cmd: String): Any? {
        return when (cmd) {
            "GetXposedInterfaceClass" -> XposedInterface::class.java
            "GetLoadPackageParam" -> null
            "GetInitZygoteStartupParam" -> null
            "GetInitErrors" -> emptyList<Throwable?>()
            else -> null
        }
    }
}
