package com.Johnny.wcx.features.items.beautify.wex.feature

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.Johnny.wcx.features.items.beautify.wex.WexBeautifyFeature
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.hookAfterDirectly
import dev.ujhhgtg.reflekt.reflekt
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.concurrent.thread

/**
 * 首页三卡 — 移植自 Wex（无 Activity.onResume Hook 版本）
 *
 * 策略：
 * - 不 Hook Activity.onResume
 * - Hook ViewGroup.addView 检测 ListView 被添加到 LauncherUI 视图树
 * - 当检测到 ListView 且父级在 LauncherUI 层级中时，插入三卡作为 Header
 * - 仅表面级修改：只修改视图外观，不拦截页面初始化
 */
object WexHomeCardsFeature {

    private const val TAG = "WexHomeCards"
    private const val TAG_INSERTED = "wex_home_cards"
    private const val IMAGE_CACHE_DIR = "wex_daily_image"
    private const val CACHE_FILE_NAME = "daily_image_cache.jpg"

    // 已加载的每日一图 Bitmap，用于预览和保存
    @Volatile
    private var currentDailyBitmap: Bitmap? = null

    fun install() {
        try {
            // Hook ViewGroup.addView — 检测 ListView 被添加到 LauncherUI 视图树
            ViewGroup::class.reflekt()
                .firstMethod { name = "addView"; parameters(View::class, Int::class, ViewGroup.LayoutParams::class) }
                .hookAfterDirectly {
                    try {
                        val child = args[0] as? View ?: return@hookAfterDirectly
                        if (!WexBeautifyFeature.masterEnabled) return@hookAfterDirectly

                        // 检测子 View 是否为 ListView/RecyclerView
                        if (!child.javaClass.name.contains("ListView") &&
                            !child.javaClass.name.contains("RecyclerView")) return@hookAfterDirectly

                        // 确认父级位于 LauncherUI 视图层级中
                        val parent = thisObject as? ViewGroup ?: return@hookAfterDirectly
                        if (!isInLauncherUIHierarchy(parent)) return@hookAfterDirectly

                        // 查找包含该 View 的 Activity
                        val act = findActivityFromView(parent) ?: return@hookAfterDirectly
                        if (act.javaClass.name != "com.tencent.mm.ui.LauncherUI") return@hookAfterDirectly

                        // 延迟插入卡片，等待布局完成
                        parent.post {
                            try {
                                insertCards(act, child)
                            } catch (e: Throwable) {
                                WeLogger.e(TAG, "首页卡片插入异常", e)
                            }
                        }
                    } catch (e: Throwable) {
                        WeLogger.e(TAG, "首页卡片 addView hook 异常", e)
                    }
                }
            WeLogger.d(TAG, "首页卡片 Hook 已注册（无 onResume 版本）")
        } catch (e: Throwable) {
            WeLogger.e(TAG, "首页卡片 Hook 注册失败", e)
        }
    }

    /**
     * 判断 ViewGroup 是否位于 LauncherUI 视图层级中。
     * 递归向上查找，检查是否有 View 的 context 为 LauncherUI Activity。
     */
    private fun isInLauncherUIHierarchy(view: View): Boolean {
        var current: View? = view
        while (current != null) {
            val ctx = current.context
            if (ctx is Activity && ctx.javaClass.name == "com.tencent.mm.ui.LauncherUI") {
                return true
            }
            current = if (current.parent is View) current.parent as View else null
        }
        return false
    }

    /**
     * 从 View 查找所属的 Activity。
     */
    private fun findActivityFromView(view: View): Activity? {
        var current: View? = view
        while (current != null) {
            val ctx = current.context
            if (ctx is Activity) return ctx
            current = if (current.parent is View) current.parent as View else null
        }
        return null
    }

    private fun insertCards(act: Activity, listView: View) {
        if ((listView.tag as? String) == TAG_INSERTED) return
        try {
            val d = act.resources.displayMetrics.density
            val root = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
            }

            var added = 0
            if (WexBeautifyFeature.homeCalendarCardEnabled) {
                val card = buildCalendarCard(act, d)
                root.addView(card, LinearLayout.LayoutParams(-1, -2))
                added++
            }
            if (WexBeautifyFeature.homeImageCardEnabled) {
                val card = buildImageCard(act, d)
                val lp = LinearLayout.LayoutParams(-1, -2)
                if (added > 0) lp.topMargin = (10 * d).toInt()
                root.addView(card, lp)
                added++
            }
            if (WexBeautifyFeature.homeMusicCardEnabled) {
                val card = buildMusicCard(act, d)
                val lp = LinearLayout.LayoutParams(-1, -2)
                if (added > 0) lp.topMargin = (10 * d).toInt()
                root.addView(card, lp)
                added++
            }

            if (root.childCount == 0) {
                listView.tag = null
                return
            }

            listView.javaClass.getMethod(
                "addHeaderView", View::class.java, Any::class.java, Boolean::class.javaPrimitiveType
            ).invoke(listView, root, null, false)
            listView.tag = TAG_INSERTED
            WeLogger.d(TAG, "首页卡片插入成功 (${root.childCount}张)")
        } catch (e: Throwable) {
            WeLogger.e(TAG, "首页卡片插入失败", e)
        }
    }

    // ==================== 深色模式检测 ====================

    private fun isDarkMode(act: Activity): Boolean {
        val nightMode = act.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightMode == Configuration.UI_MODE_NIGHT_YES
    }

    /** 获取卡片背景色（自动适配深浅色） */
    private fun cardBgColor(act: Activity): Int {
        return if (isDarkMode(act)) Color.parseColor("#2C2C2C") else Color.parseColor("#F5F5F5")
    }

    /** 获取标题文字颜色 */
    private fun titleTextColor(act: Activity): Int {
        return if (isDarkMode(act)) Color.parseColor("#E0E0E0") else Color.parseColor("#212121")
    }

    /** 获取副标题文字颜色 */
    private fun subtitleTextColor(act: Activity): Int {
        return if (isDarkMode(act)) Color.parseColor("#AAAAAA") else Color.parseColor("#999999")
    }

    /** 获取分隔线颜色 */
    private fun dividerColor(act: Activity): Int {
        return if (isDarkMode(act)) Color.parseColor("#3A3A3A") else Color.parseColor("#E0E0E0")
    }

    // ==================== 日历卡片 ====================

    private fun buildCalendarCard(act: Activity, d: Float): View {
        fun dp(v: Float) = (v * d).toInt()
        val cal = Calendar.getInstance()
        val dateStr = SimpleDateFormat("yyyy年MM月dd日", Locale.CHINESE).format(cal.time)
        val weekStr = SimpleDateFormat("EEEE", Locale.CHINESE).format(cal.time)

        return LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, dp(16f).toFloat())
                }
            }
            background = GradientDrawable().apply {
                setColor(cardBgColor(act))
                cornerRadius = dp(16f).toFloat()
            }
        }.also { card ->
            val textCol = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }
            textCol.addView(TextView(act).apply {
                text = dateStr
                textSize = 16f
                setTextColor(titleTextColor(act))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            textCol.addView(TextView(act).apply {
                text = weekStr
                textSize = 12f
                setTextColor(subtitleTextColor(act))
            })
            card.addView(textCol, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(TextView(act).apply {
                text = "📅"
                textSize = 28f
                gravity = Gravity.CENTER
            })
        }
    }

    // ==================== 图片卡片 ====================

    private fun buildImageCard(act: Activity, d: Float): View {
        fun dp(v: Float) = (v * d).toInt()
        val card = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, dp(16f).toFloat())
                }
            }
            background = GradientDrawable().apply {
                setColor(cardBgColor(act))
                cornerRadius = dp(16f).toFloat()
            }
            isClickable = true
            isLongClickable = true
        }

        // 顶部行：文字 + 缩略图
        val topRow = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val textCol = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        textCol.addView(TextView(act).apply {
            text = "每日一图"
            textSize = 16f
            setTextColor(titleTextColor(act))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        textCol.addView(TextView(act).apply {
            text = "发现美好瞬间"
            textSize = 12f
            setTextColor(subtitleTextColor(act))
        })
        topRow.addView(textCol, LinearLayout.LayoutParams(0, -2, 1f))

        // 缩略图预览 ImageView
        val thumbnailSize = dp(56f)
        val thumbnail = ImageView(act).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, dp(8f).toFloat())
                }
            }
            layoutParams = LinearLayout.LayoutParams(thumbnailSize, thumbnailSize)
            setImageResource(android.R.drawable.ic_menu_gallery) // 默认占位图
        }
        topRow.addView(thumbnail)

        // 图片预览区域
        val previewArea = ImageView(act).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(160f)
            ).apply { topMargin = dp(10f) }
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, dp(12f).toFloat())
                }
            }
            visibility = View.GONE
            setImageResource(android.R.drawable.ic_menu_gallery) // 默认占位图
        }

        card.addView(topRow)
        card.addView(previewArea)

        // 加载每日一图
        loadDailyImage(act, thumbnail, previewArea)

        // ── 单击：弹出大图预览弹窗 ──
        card.setOnClickListener {
            try {
                showImagePreviewDialog(act)
            } catch (e: Throwable) {
                WeLogger.e(TAG, "图片预览弹窗异常", e)
            }
        }

        // ── 长按：唤起API配置弹窗 ──
        card.setOnLongClickListener {
            try {
                showImageConfigDialog(act, thumbnail, previewArea)
            } catch (e: Throwable) {
                WeLogger.e(TAG, "图片配置弹窗异常", e)
            }
            true
        }

        return card
    }

    // ==================== 每日一图加载 ====================

    private fun loadDailyImage(act: Activity, thumbnail: ImageView, previewArea: ImageView) {
        thread(name = "wex-daily-image") {
            try {
                // 先尝试从缓存加载
                val cached = loadCachedImage(act)
                if (cached != null) {
                    currentDailyBitmap = cached
                    act.runOnUiThread {
                        thumbnail.setImageBitmap(cached)
                        previewArea.setImageBitmap(cached)
                        previewArea.visibility = View.VISIBLE
                    }
                    WeLogger.d(TAG, "每日一图从缓存加载成功")
                    return@thread
                }

                // 从网络加载
                val apiUrl = WexBeautifyFeature.imageApiUrl
                val apiKey = WexBeautifyFeature.imageApiKey
                WeLogger.d(TAG, "每日一图开始网络请求: $apiUrl")
                val bitmap = fetchImageFromUrl(apiUrl, apiKey)

                if (bitmap != null) {
                    currentDailyBitmap = bitmap
                    saveImageToCache(act, bitmap)
                    act.runOnUiThread {
                        thumbnail.setImageBitmap(bitmap)
                        previewArea.setImageBitmap(bitmap)
                        previewArea.visibility = View.VISIBLE
                    }
                    WeLogger.i(TAG, "每日一图加载成功: ${bitmap.width}x${bitmap.height}")
                } else {
                    WeLogger.w(TAG, "每日一图网络请求返回空，使用默认占位图")
                }
            } catch (e: Throwable) {
                WeLogger.e(TAG, "每日一图加载失败", e)
            }
        }
    }

    private fun fetchImageFromUrl(urlStr: String, apiKey: String = ""): Bitmap? {
        var connection: HttpURLConnection? = null
        var input: InputStream? = null
        try {
            val url = URL(urlStr)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (apiKey.isNotBlank()) {
                connection.setRequestProperty("X-API-Key", apiKey)
                connection.setRequestProperty("Authorization", "Bearer $apiKey")
            }
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                WeLogger.w(TAG, "每日一图 HTTP ${connection.responseCode}")
                return null
            }

            input = connection.inputStream
            return BitmapFactory.decodeStream(input)
        } catch (e: Throwable) {
            WeLogger.e(TAG, "每日一图网络请求异常", e)
            return null
        } finally {
            try { input?.close() } catch (_: Throwable) {}
            try { connection?.disconnect() } catch (_: Throwable) {}
        }
    }

    private fun loadCachedImage(act: Activity): Bitmap? {
        return try {
            val cacheDir = File(act.cacheDir, IMAGE_CACHE_DIR)
            val cacheFile = File(cacheDir, CACHE_FILE_NAME)
            if (cacheFile.exists()) {
                BitmapFactory.decodeFile(cacheFile.absolutePath)
            } else null
        } catch (e: Throwable) {
            WeLogger.e(TAG, "读取缓存图片失败", e)
            null
        }
    }

    private fun saveImageToCache(act: Activity, bitmap: Bitmap) {
        try {
            val cacheDir = File(act.cacheDir, IMAGE_CACHE_DIR)
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val cacheFile = File(cacheDir, CACHE_FILE_NAME)
            FileOutputStream(cacheFile).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos)
            }
            WeLogger.d(TAG, "每日一图已缓存: ${cacheFile.absolutePath}")
        } catch (e: Throwable) {
            WeLogger.e(TAG, "保存缓存图片失败", e)
        }
    }

    // ==================== 图片预览弹窗 ====================

    private fun showImagePreviewDialog(act: Activity) {
        val bitmap = currentDailyBitmap
        if (bitmap == null) {
            Toast.makeText(act, "图片尚未加载完成，请稍后再试", Toast.LENGTH_SHORT).show()
            return
        }

        val d = act.resources.displayMetrics.density
        fun dp(v: Float) = (v * d).toInt()

        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
        }

        val img = ImageView(act).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            maxHeight = (act.resources.displayMetrics.heightPixels * 0.6).toInt()
            setImageBitmap(bitmap)
        }
        root.addView(img, LinearLayout.LayoutParams(-1, -2))

        val dialog = AlertDialog.Builder(act)
            .setTitle("每日一图预览")
            .setView(root)
            .setNegativeButton("取消") { dlg, _ -> dlg.dismiss() }
            .setPositiveButton("保存图片") { dlg, _ ->
                try {
                    saveImageToGallery(act, bitmap)
                } catch (e: Throwable) {
                    WeLogger.e(TAG, "保存图片失败", e)
                    Toast.makeText(act, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
                dlg.dismiss()
            }
            .create()
        dialog.show()
    }

    private fun saveImageToGallery(act: Activity, bitmap: Bitmap) {
        // 权限检查
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ 不需要 WRITE_EXTERNAL_STORAGE
        } else {
            if (act.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Toast.makeText(act, "缺少存储权限，请在系统设置中授予存储权限", Toast.LENGTH_LONG).show()
                return
            }
        }

        val filename = "每日一图_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(java.util.Date())}.jpg"

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Wex每日一图")
                }
                val uri = act.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues
                )
                if (uri != null) {
                    act.contentResolver.openOutputStream(uri)?.use { os ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, os)
                    }
                    Toast.makeText(act, "图片已保存到相册", Toast.LENGTH_SHORT).show()
                    WeLogger.i(TAG, "图片已保存到相册: $filename")
                } else {
                    Toast.makeText(act, "保存失败：无法创建文件", Toast.LENGTH_SHORT).show()
                }
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "Wex每日一图"
                )
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, filename)
                FileOutputStream(file).use { fos ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos)
                }
                // 通知相册刷新
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DATA, file.absolutePath)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                }
                act.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                Toast.makeText(act, "图片已保存到相册", Toast.LENGTH_SHORT).show()
                WeLogger.i(TAG, "图片已保存: ${file.absolutePath}")
            }
        } catch (e: Throwable) {
            WeLogger.e(TAG, "保存图片到相册失败", e)
            Toast.makeText(act, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ==================== API配置弹窗 ====================

    private fun showImageConfigDialog(act: Activity, thumbnail: ImageView, previewArea: ImageView) {
        val d = act.resources.displayMetrics.density
        fun dp(v: Float) = (v * d).toInt()

        val scrollView = ScrollView(act)
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f), dp(16f), dp(24f), dp(16f))
        }

        // API地址输入框
        root.addView(TextView(act).apply {
            text = "图片API接口地址"
            textSize = 14f
            setTextColor(titleTextColor(act))
            setPadding(0, dp(8f), 0, dp(4f))
        })
        val apiInput = EditText(act).apply {
            setText(WexBeautifyFeature.imageApiUrl)
            setTextColor(titleTextColor(act))
            setHintTextColor(subtitleTextColor(act))
            hint = "输入API地址"
            textSize = 14f
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            background = GradientDrawable().apply {
                setColor(cardBgColor(act))
                cornerRadius = dp(8f).toFloat()
                setStroke(1, dividerColor(act))
            }
        }
        root.addView(apiInput, LinearLayout.LayoutParams(-1, -2))

        // API Key 输入框
        root.addView(TextView(act).apply {
            text = "API Key (可选)"
            textSize = 14f
            setTextColor(titleTextColor(act))
            setPadding(0, dp(12f), 0, dp(4f))
        })
        val apiKeyInput = EditText(act).apply {
            setText(WexBeautifyFeature.imageApiKey)
            setTextColor(titleTextColor(act))
            setHintTextColor(subtitleTextColor(act))
            hint = "输入API Key"
            textSize = 14f
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            background = GradientDrawable().apply {
                setColor(cardBgColor(act))
                cornerRadius = dp(8f).toFloat()
                setStroke(1, dividerColor(act))
            }
        }
        root.addView(apiKeyInput, LinearLayout.LayoutParams(-1, -2))

        // 刷新间隔
        root.addView(TextView(act).apply {
            text = "刷新间隔（秒）"
            textSize = 14f
            setTextColor(titleTextColor(act))
            setPadding(0, dp(12f), 0, dp(4f))
        })
        val intervalInput = EditText(act).apply {
            setText(WexBeautifyFeature.imageRefreshInterval.toString())
            setTextColor(titleTextColor(act))
            setHintTextColor(subtitleTextColor(act))
            hint = "3600"
            textSize = 14f
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            background = GradientDrawable().apply {
                setColor(cardBgColor(act))
                cornerRadius = dp(8f).toFloat()
                setStroke(1, dividerColor(act))
            }
        }
        root.addView(intervalInput, LinearLayout.LayoutParams(-1, -2))

        // 恢复默认按钮
        val resetBtn = TextView(act).apply {
            text = "恢复默认API地址"
            textSize = 14f
            setTextColor(Color.parseColor("#FF5252"))
            gravity = Gravity.CENTER
            setPadding(0, dp(16f), 0, dp(8f))
            setOnClickListener {
                apiInput.setText(WexBeautifyFeature.DEFAULT_IMAGE_API)
                apiKeyInput.setText("")
                intervalInput.setText("3600")
            }
        }
        root.addView(resetBtn)

        scrollView.addView(root)

        AlertDialog.Builder(act)
            .setTitle("每日一图配置")
            .setView(scrollView)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { dlg, _ ->
                try {
                    val newApi = apiInput.text.toString().trim()
                    val newApiKey = apiKeyInput.text.toString().trim()
                    val newInterval = intervalInput.text.toString().trim().toIntOrNull() ?: 3600

                    WexBeautifyFeature.imageApiUrl = newApi
                    WexBeautifyFeature.imageApiKey = newApiKey
                    WexBeautifyFeature.imageRefreshInterval = newInterval

                    WeLogger.i(TAG, "每日一图配置已更新: api=$newApi, interval=$newInterval")
                    Toast.makeText(act, "配置已保存，下次刷新生效", Toast.LENGTH_SHORT).show()

                    // 清除缓存，重新加载
                    clearImageCache(act)
                    currentDailyBitmap = null
                    loadDailyImage(act, thumbnail, previewArea)
                } catch (e: Throwable) {
                    WeLogger.e(TAG, "保存图片配置失败", e)
                    Toast.makeText(act, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
                dlg.dismiss()
            }
            .create()
            .show()
    }

    private fun clearImageCache(act: Activity) {
        try {
            val cacheDir = File(act.cacheDir, IMAGE_CACHE_DIR)
            val cacheFile = File(cacheDir, CACHE_FILE_NAME)
            if (cacheFile.exists()) cacheFile.delete()
            WeLogger.d(TAG, "每日一图缓存已清除")
        } catch (e: Throwable) {
            WeLogger.e(TAG, "清除缓存失败", e)
        }
    }

    // ==================== 音乐卡片 ====================

    private fun buildMusicCard(act: Activity, d: Float): View {
        fun dp(v: Float) = (v * d).toInt()
        return LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, dp(16f).toFloat())
                }
            }
            background = GradientDrawable().apply {
                setColor(cardBgColor(act))
                cornerRadius = dp(16f).toFloat()
            }
        }.also { card ->
            val textCol = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }
            textCol.addView(TextView(act).apply {
                text = "音乐播放器"
                textSize = 16f
                setTextColor(titleTextColor(act))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            textCol.addView(TextView(act).apply {
                text = "当前未在播放"
                textSize = 12f
                setTextColor(subtitleTextColor(act))
            })
            card.addView(textCol, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(TextView(act).apply {
                text = "🎵"
                textSize = 28f
                gravity = Gravity.CENTER
            })
        }
    }
}