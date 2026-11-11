package h.Hchat.hooks.items.messageforward

import android.content.Intent
import h.Hchat.hooks.api.sns.PreparedSnsImage
import android.os.Parcelable
import h.Hchat.utils.KavaReflector
import java.lang.reflect.Field
import java.lang.reflect.Modifier

internal object SnsLivePhotoIntentBuilder {
    private const val IMAGE_ITEM_CLASS =
        "com.tencent.mm.plugin.gallery.model.GalleryItem\$ImageMediaItem"
    private const val LIVE_PHOTO_ITEM_CLASS =
        "com.tencent.mm.plugin.gallery.model.GalleryItem\$LivePhotoMediaItem"
    private const val EXTRA_MULTI_PIC_ITEMS = "KMulti_Pic_Item_List"
    private const val EXTRA_IMAGE_PATHS = "sns_kemdia_path_list"
    private const val MIME_IMAGE = "image/jpeg"

    fun putImageItems(
        intent: Intent,
        items: List<PreparedSnsImage>,
        classLoader: ClassLoader,
        logger: (String, Throwable?) -> Unit
    ): Boolean {
        if (items.isEmpty() || items.none { it.isLivePhoto }) return false
        return runCatching {
            val imageClass = KavaReflector.loadClass(IMAGE_ITEM_CLASS, classLoader)
                ?: return@runCatching false
            val liveClass = KavaReflector.loadClass(LIVE_PHOTO_ITEM_CLASS, classLoader)
                ?: return@runCatching false
            val imageConstructor = KavaReflector.findConstructor(
                imageClass,
                java.lang.Long.TYPE,
                String::class.java,
                String::class.java,
                String::class.java
            ) ?: return@runCatching false
            val liveConstructor = KavaReflector.findConstructor(
                liveClass,
                java.lang.Long.TYPE,
                String::class.java,
                String::class.java,
                String::class.java
            ) ?: return@runCatching false

            val parcelables = ArrayList<Parcelable>(items.size)
            items.forEach { item ->
                val nativeItem = if (item.isLivePhoto) {
                    KavaReflector.newInstance(
                        liveConstructor,
                        0L,
                        item.liveVideoPath,
                        item.imagePath,
                        MIME_IMAGE
                    )?.also { liveItem ->
                        check(writeLabeledInt(liveItem, "videoDuration=", item.liveVideoDurationMillis))
                        writeLabeledInt(liveItem, "videoWidth=", item.liveVideoWidth)
                        writeLabeledInt(liveItem, "videoHeight=", item.liveVideoHeight)
                        writeLabeledInt(
                            liveItem,
                            "videoSize=",
                            item.liveVideoSizeBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                        )
                        writeLabeledLong(liveItem, "coverTimeStampMs=", item.liveVideoCoverTimeMillis)
                        check(writeLabeledInt(liveItem, "isParsedVideo=", 1))
                    }
                } else {
                    KavaReflector.newInstance(
                        imageConstructor,
                        0L,
                        item.imagePath,
                        item.imagePath,
                        MIME_IMAGE
                    )
                }
                parcelables += nativeItem as? Parcelable ?: return@runCatching false
            }
            intent.putStringArrayListExtra(
                EXTRA_IMAGE_PATHS,
                items.mapTo(ArrayList<String>(items.size)) { it.imagePath }
            )
            intent.putParcelableArrayListExtra(EXTRA_MULTI_PIC_ITEMS, parcelables)
            intent.putExtra("KSnsPostManu", true)
            intent.putExtra("Ksnsupload_type", 0)
            true
        }.onFailure {
            logger("构造朋友圈实况编辑项失败", it)
        }.getOrDefault(false)
    }

    private fun writeLabeledInt(target: Any, label: String, value: Int): Boolean {
        val sentinel = 1_357_911
        for (field in instanceIntFields(target.javaClass)) {
            val oldValue = KavaReflector.readField(field, target) as? Int ?: continue
            if (!KavaReflector.writeField(field, target, sentinel)) continue
            val matched = runCatching { target.toString().contains("$label$sentinel") }
                .getOrDefault(false)
            KavaReflector.writeField(field, target, oldValue)
            if (matched) return KavaReflector.writeField(field, target, value)
        }
        return false
    }

    private fun writeLabeledLong(target: Any, label: String, value: Long): Boolean {
        val sentinel = 1_357_911_246_813L
        for (field in instanceLongFields(target.javaClass)) {
            val oldValue = KavaReflector.readField(field, target) as? Long ?: continue
            if (!KavaReflector.writeField(field, target, sentinel)) continue
            val matched = runCatching { target.toString().contains("$label$sentinel") }
                .getOrDefault(false)
            KavaReflector.writeField(field, target, oldValue)
            if (matched) return KavaReflector.writeField(field, target, value)
        }
        return false
    }

    private fun instanceIntFields(clazz: Class<*>): List<Field> {
        val fields = ArrayList<Field>()
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            KavaReflector.declaredFields(current)
                .filter { field ->
                    !Modifier.isStatic(field.modifiers) && field.type == Integer.TYPE
                }
                .forEach(fields::add)
            current = current.superclass
        }
        return fields
    }

    private fun instanceLongFields(clazz: Class<*>): List<Field> {
        val fields = ArrayList<Field>()
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            KavaReflector.declaredFields(current)
                .filter { field ->
                    !Modifier.isStatic(field.modifiers) && field.type == java.lang.Long.TYPE
                }
                .forEach(fields::add)
            current = current.superclass
        }
        return fields
    }
}
