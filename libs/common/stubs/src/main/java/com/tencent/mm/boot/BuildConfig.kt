package com.tencent.mm.boot

object BuildConfig {

    // we use a kotlin `object` and no `const` here to prevent inlining

    @Suppress("MayBeConstant")
    @JvmField
    val BUILD_TAG: String = "Stub!"

    @Suppress("MayBeConstant")
    @JvmField
    val VERSION_NAME: String = "Stub!"

    @Suppress("MayBeConstant")
    @JvmField
    val VERSION_CODE: Int = 1337

    @Suppress("MayBeConstant")
    @JvmField
    val CLIENT_VERSION_ARM64: String = "Stub!"
}
