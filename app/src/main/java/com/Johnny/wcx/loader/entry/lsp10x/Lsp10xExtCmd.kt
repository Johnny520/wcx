package com.Johnny.wcx.loader.entry.lsp10x

import io.github.libxposed.api.XposedInterface

object Lsp10xExtCmd {

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