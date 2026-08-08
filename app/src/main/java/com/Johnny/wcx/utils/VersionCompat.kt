package com.Johnny.wcx.utils

import android.content.Context
import android.content.pm.PackageManager
import com.Johnny.wcx.constants.PackageNames

/**
 * 微信版本兼容性检测工具。
 *
 * 设计原则：
 * - 不写死微信版本号判断，不写 if-else 区分 8076/8077/8078 硬编码版本分支
 * - 基于 DexKit 动态查找结果判断兼容性，而非静态版本号比对
 * - 提供版本信息获取和兼容性提示
 */
object VersionCompat {

    private const val TAG = "VersionCompat"

    /** 缓存的微信版本号 */
    @Volatile
    private var cachedWeChatVersionCode: Long? = null
    @Volatile
    private var cachedWeChatVersionName: String? = null

    /** 获取微信版本号 */
    fun getWeChatVersionCode(): Long {
        if (cachedWeChatVersionCode != null) return cachedWeChatVersionCode!!
        return try {
            val ctx = HostInfo.application
            val pm = ctx.packageManager
            val pkgInfo = pm.getPackageInfo(PackageNames.WECHAT, 0)
            val code = pkgInfo.longVersionCode
            cachedWeChatVersionCode = code
            WeLogger.d(TAG, "微信版本号: $code")
            code
        } catch (e: PackageManager.NameNotFoundException) {
            WeLogger.w(TAG, "无法获取微信版本信息", e)
            -1L
        } catch (e: Throwable) {
            WeLogger.e(TAG, "获取微信版本异常", e)
            -1L
        }
    }

    /** 获取微信版本名 */
    fun getWeChatVersionName(): String {
        if (cachedWeChatVersionName != null) return cachedWeChatVersionName!!
        return try {
            val ctx = HostInfo.application
            val pm = ctx.packageManager
            val pkgInfo = pm.getPackageInfo(PackageNames.WECHAT, 0)
            val name = pkgInfo.versionName ?: "未知"
            cachedWeChatVersionName = name
            WeLogger.d(TAG, "微信版本名: $name")
            name
        } catch (e: Throwable) {
            WeLogger.w(TAG, "获取微信版本名异常", e)
            "未知"
        }
    }

    /**
     * 检查当前微信版本是否已知兼容。
     * 返回值：[Pair] 兼容状态 + 提示消息
     */
    fun checkCompatibility(failedFeatureCount: Int, totalFeatureCount: Int): Pair<Boolean, String> {
        val wechatVersion = getWeChatVersionName()
        val wechatCode = getWeChatVersionCode()

        if (failedFeatureCount == 0) {
            return true to "微信 $wechatVersion (code=$wechatCode) 兼容，所有功能正常"
        }

        val failureRate = failedFeatureCount.toFloat() / totalFeatureCount.coerceAtLeast(1)
        return when {
            failureRate >= 0.5f -> {
                false to "当前微信版本 $wechatVersion 兼容性较差，${failedFeatureCount}/${totalFeatureCount} 个功能暂未适配，请等待模块更新"
            }
            failureRate >= 0.2f -> {
                false to "当前微信版本 $wechatVersion 部分功能(${failedFeatureCount}/${totalFeatureCount})暂未适配，等待模块更新"
            }
            else -> {
                true to "微信 $wechatVersion 基本兼容，${failedFeatureCount} 个功能暂不可用"
            }
        }
    }
}