package com.photoroute.app

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

/** Where the photos are allowed to come from. */
enum class Source(val label: String) {
    CAMERA("내 카메라"),      // shot on some camera, sitting in DCIM
    THIS_DEVICE("이 기기"),   // EXIF model matches this phone
    ALL("전체"),              // anything with coordinates
}

data class Bucket(val id: Long, val name: String, val count: Int)

data class ScanResult(
    val photos: List<Photo>,
    val scanned: Int,
    val skippedNoLocation: Int,
    val skippedNotMine: Int,
)

/**
 * Reads GPS out of the device photo library.
 *
 * The part that trips people up: since Android 10 MediaStore strips location from EXIF.
 * You get it back only by holding ACCESS_MEDIA_LOCATION *and* wrapping the item uri in
 * MediaStore.setRequireOriginal(). Without both, latLong is null for every single photo.
 */
class PhotoScanner(private val context: Context) {

    private val exifDate = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)

    private val collection
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    /** Folders the gallery knows about, so the user can tick the ones that are theirs. */
    suspend fun buckets(): List<Bucket> = withContext(Dispatchers.IO) {
        val counts = LinkedHashMap<Long, Pair<String, Int>>()
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Images.Media.BUCKET_ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME),
            null, null, "${MediaStore.Images.Media.DATE_TAKEN} DESC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val name = c.getString(nameCol) ?: continue
                val prev = counts[id]
                counts[id] = name to ((prev?.second ?: 0) + 1)
            }
        }
        counts.map { (id, v) -> Bucket(id, v.first, v.second) }.sortedByDescending { it.count }
    }

    /**
     * [from]/[to] are local epoch millis and are pushed into the SQL query, so only photos
     * inside the window ever get opened — that read is what costs time, not the query.
     */
    suspend fun scan(
        from: Long,
        to: Long,
        source: Source,
        buckets: Set<Long>,
        onProgress: (done: Int, total: Int) -> Unit,
    ): ScanResult = withContext(Dispatchers.IO) {

        val where = StringBuilder()
        val args = ArrayList<String>()

        // DATE_TAKEN is millis but null for some files; fall back to DATE_ADDED in seconds.
        where.append(
            "((${MediaStore.Images.Media.DATE_TAKEN} IS NOT NULL " +
                "AND ${MediaStore.Images.Media.DATE_TAKEN} BETWEEN ? AND ?) " +
                "OR (${MediaStore.Images.Media.DATE_TAKEN} IS NULL " +
                "AND ${MediaStore.Images.Media.DATE_ADDED} BETWEEN ? AND ?))"
        )
        args += from.toString(); args += to.toString()
        args += (from / 1000).toString(); args += (to / 1000).toString()

        if (source != Source.ALL) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                where.append(" AND ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?")
                args += "DCIM/%"
            } else {
                @Suppress("DEPRECATION")
                where.append(" AND ${MediaStore.Images.Media.DATA} LIKE ?")
                args += "%/DCIM/%"
            }
        }
        if (buckets.isNotEmpty()) {
            where.append(" AND ${MediaStore.Images.Media.BUCKET_ID} IN (")
            where.append(buckets.joinToString(",") { "?" })
            where.append(")")
            buckets.forEach { args += it.toString() }
        }

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
        )

        val photos = ArrayList<Photo>()
        var scanned = 0
        var noLocation = 0
        var notMine = 0

        context.contentResolver.query(
            collection, projection, where.toString(), args.toTypedArray(),
            "${MediaStore.Images.Media.DATE_TAKEN} ASC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val takenCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val addedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val total = cursor.count
            onProgress(0, total)

            while (cursor.moveToNext()) {
                scanned++
                if (scanned % 20 == 0) onProgress(scanned, total)

                val id = cursor.getLong(idCol)
                var uri = ContentUris.withAppendedId(collection, id)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    uri = runCatching { MediaStore.setRequireOriginal(uri) }.getOrDefault(uri)
                }

                var rejectedAsNotMine = false
                val point = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val exif = ExifInterface(stream)

                        val make = exif.getAttribute(ExifInterface.TAG_MAKE)
                        val model = exif.getAttribute(ExifInterface.TAG_MODEL)
                        if (!passesSource(source, make, model)) {
                            rejectedAsNotMine = true
                            return@use null
                        }

                        val ll = exif.latLong ?: return@use null
                        if (ll[0] == 0.0 && ll[1] == 0.0) return@use null

                        val stamp = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                            ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                        val t = stamp?.let { runCatching { exifDate.parse(it)?.time }.getOrNull() }
                            ?: cursor.getLong(takenCol).takeIf { it > 0 }
                            ?: (cursor.getLong(addedCol) * 1000L)

                        Photo(ll[0], ll[1], t)
                    }
                }.getOrNull()

                when {
                    point != null -> photos.add(point)
                    rejectedAsNotMine -> notMine++
                    else -> noLocation++
                }
            }
            onProgress(total, total)
        }
        ScanResult(photos, scanned, noLocation, notMine)
    }

    /**
     * A screenshot or a picture someone sent you has no Make/Model. A photo you actually
     * shot always does — that single field separates "내가 찍은 사진" from the rest.
     */
    private fun passesSource(source: Source, make: String?, model: String?): Boolean = when (source) {
        Source.ALL -> true
        Source.CAMERA -> !make.isNullOrBlank() || !model.isNullOrBlank()
        Source.THIS_DEVICE ->
            model?.equals(Build.MODEL, ignoreCase = true) == true ||
                (model?.equals(Build.DEVICE, ignoreCase = true) == true) ||
                (make?.equals(Build.MANUFACTURER, ignoreCase = true) == true &&
                    model?.contains(Build.MODEL, ignoreCase = true) == true)
    }
}
