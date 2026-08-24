package com.photoroute.app

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class TimelinePoint(
    val latitude: Double,
    val longitude: Double,
    val timeMillis: Long,
)

internal data class PreparedTimelineJson(
    val json: String?,
    val selected: Int,
    val exported: Int,
    val skippedNoLocation: Int,
    val skippedNoTime: Int,
    val skippedUnreadable: Int,
    val removedDuplicates: Int,
)

/**
 * Turns user-selected original photos into the smallest canonical Timeline.json document
 * understood by google-timeline-visualizer v2.4.1.
 */
internal class TimelineJsonExporter(private val context: Context) {

    private sealed interface ReadResult {
        data class Point(val value: TimelinePoint) : ReadResult
        data object NoLocation : ReadResult
        data object NoTime : ReadResult
        data object Unreadable : ReadResult
    }

    suspend fun prepare(photoUris: List<Uri>): PreparedTimelineJson = withContext(Dispatchers.IO) {
        val uniqueUris = photoUris.distinct()
        val points = ArrayList<TimelinePoint>(uniqueUris.size)
        var noLocation = 0
        var noTime = 0
        var unreadable = 0

        uniqueUris.forEach { uri ->
            when (val result = readPhoto(uri)) {
                is ReadResult.Point -> points += result.value
                ReadResult.NoLocation -> noLocation += 1
                ReadResult.NoTime -> noTime += 1
                ReadResult.Unreadable -> unreadable += 1
            }
        }

        val normalized = normalizeTimelinePoints(points)
        PreparedTimelineJson(
            json = normalized.takeIf { it.isNotEmpty() }?.let(::buildTimelineJson),
            selected = uniqueUris.size,
            exported = normalized.size,
            skippedNoLocation = noLocation,
            skippedNoTime = noTime,
            skippedUnreadable = unreadable,
            removedDuplicates = points.size - normalized.size,
        )
    }

    suspend fun write(destination: Uri, json: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(destination, "wt")?.bufferedWriter(Charsets.UTF_8)
                ?.use { it.write(json) }
                ?: return@runCatching false
            true
        }.getOrDefault(false)
    }

    private fun readPhoto(uri: Uri): ReadResult {
        val mediaCaptureTime = queryMediaCaptureTime(uri)
        var bestResult: ReadResult = ReadResult.Unreadable

        candidateUris(uri).forEach { candidate ->
            val result = runCatching {
                context.contentResolver.openInputStream(candidate)?.use { stream ->
                    val exif = ExifInterface(stream)
                    val latLong = exif.latLong
                        ?: return@use ReadResult.NoLocation
                    val latitude = latLong[0]
                    val longitude = latLong[1]
                    if (
                        !latitude.isFinite() || !longitude.isFinite() ||
                        latitude !in MIN_LATITUDE..MAX_LATITUDE ||
                        longitude !in -180.0..180.0 ||
                        (latitude == 0.0 && longitude == 0.0)
                    ) {
                        return@use ReadResult.NoLocation
                    }

                    val time = exifTime(exif, mediaCaptureTime)
                        ?: return@use ReadResult.NoTime
                    ReadResult.Point(TimelinePoint(latitude, longitude, time))
                } ?: ReadResult.Unreadable
            }.getOrDefault(ReadResult.Unreadable)

            if (result is ReadResult.Point) return result
            if (bestResult == ReadResult.Unreadable || result == ReadResult.NoTime) {
                bestResult = result
            }
        }
        return bestResult
    }

    private fun exifTime(exif: ExifInterface, mediaCaptureTime: Long?): Long? {
        val original = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
        val originalSubseconds = exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL)
        val originalOffset = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)

        // A capture timestamp with its own UTC offset is the most faithful clock. GPS time is
        // already UTC and is the next safest source when older cameras omit the offset tag.
        parseExifDateTime(original, originalSubseconds, originalOffset, assumeLocalZone = false)
            ?.let { return it }
        runCatching { exif.gpsDateTime }.getOrNull()?.takeIf { it > 0L }?.let { return it }
        mediaCaptureTime?.takeIf { it > 0L }?.let { return it }
        parseExifDateTime(original, originalSubseconds, originalOffset, assumeLocalZone = true)
            ?.let { return it }

        parseExifDateTime(
            exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED),
            exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_DIGITIZED),
            exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED),
            assumeLocalZone = true,
        )?.let { return it }
        return parseExifDateTime(
            exif.getAttribute(ExifInterface.TAG_DATETIME),
            exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME),
            exif.getAttribute(ExifInterface.TAG_OFFSET_TIME),
            assumeLocalZone = true,
        )
    }

    private fun parseExifDateTime(
        raw: String?,
        rawSubseconds: String?,
        rawOffset: String?,
        assumeLocalZone: Boolean,
    ): Long? {
        if (raw.isNullOrBlank()) return null
        val local = EXIF_DATE_FORMATS.firstNotNullOfOrNull { formatter ->
            runCatching { LocalDateTime.parse(raw.trim(), formatter) }.getOrNull()
        } ?: return null
        val millis = rawSubseconds.orEmpty().takeWhile(Char::isDigit).take(3)
            .padEnd(3, '0').toIntOrNull() ?: 0
        val withSubseconds = local.plusNanos(millis * 1_000_000L)
        val offset = rawOffset?.trim()?.takeIf { it.isNotEmpty() }?.let {
            runCatching { ZoneOffset.of(it) }.getOrNull()
        }
        if (offset != null) return withSubseconds.toInstant(offset).toEpochMilli()
        if (!assumeLocalZone) return null
        return withSubseconds.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    private fun candidateUris(uri: Uri): List<Uri> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return listOf(uri)
        val mediaUri = equivalentMediaUri(uri)
        val base = mediaUri ?: uri
        val original = runCatching { MediaStore.setRequireOriginal(base) }.getOrDefault(base)
        return listOf(original, base, uri).distinct()
    }

    private fun equivalentMediaUri(uri: Uri): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching { MediaStore.getMediaUri(context, uri) }.getOrNull()
    }

    private fun queryMediaCaptureTime(uri: Uri): Long? {
        val candidates = listOfNotNull(equivalentMediaUri(uri), uri).distinct()
        return candidates.firstNotNullOfOrNull { candidate ->
            runCatching {
                context.contentResolver.query(
                    candidate,
                    arrayOf(MediaStore.Images.Media.DATE_TAKEN),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val index = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                    if (index >= 0 && !cursor.isNull(index)) {
                        cursor.getLong(index).takeIf { it > 0L }
                    } else {
                        null
                    }
                }
            }.getOrNull()
        }
    }

    private companion object {
        const val MIN_LATITUDE = -85.05112878
        const val MAX_LATITUDE = 85.05112878
        val EXIF_DATE_FORMATS = listOf(
            DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.US),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US),
        )
    }
}

internal fun normalizeTimelinePoints(points: List<TimelinePoint>): List<TimelinePoint> {
    val unique = LinkedHashSet<TimelinePointKey>(points.size)
    return points.asSequence()
        .filter {
            it.latitude.isFinite() && it.longitude.isFinite() &&
                it.latitude in -85.05112878..85.05112878 &&
                it.longitude in -180.0..180.0 && it.timeMillis > 0L
        }
        .sortedBy(TimelinePoint::timeMillis)
        .filter {
            unique.add(
                TimelinePointKey(
                    it.timeMillis,
                    it.latitude.toBits(),
                    it.longitude.toBits(),
                )
            )
        }
        .toList()
}

private data class TimelinePointKey(
    val timeMillis: Long,
    val latitudeBits: Long,
    val longitudeBits: Long,
)

internal fun buildTimelineJson(points: List<TimelinePoint>): String {
    require(points.isNotEmpty()) { "Timeline.json needs at least one point" }
    val start = Instant.ofEpochMilli(points.first().timeMillis).toString()
    val end = Instant.ofEpochMilli(points.last().timeMillis).toString()
    return buildString(points.size * 92 + 180) {
        append("{\n")
        append("  \"semanticSegments\": [\n")
        append("    {\n")
        append("      \"startTime\": \"").append(start).append("\",\n")
        append("      \"endTime\": \"").append(end).append("\",\n")
        append("      \"timelinePath\": [\n")
        points.forEachIndexed { index, point ->
            val coordinate = String.format(
                Locale.US,
                "%.7f,%.7f",
                point.latitude,
                point.longitude,
            )
            val time = Instant.ofEpochMilli(point.timeMillis).toString()
            append("        {\"point\": \"").append(coordinate)
                .append("\", \"time\": \"").append(time).append("\"}")
            if (index != points.lastIndex) append(',')
            append('\n')
        }
        append("      ]\n")
        append("    }\n")
        append("  ]\n")
        append("}\n")
    }
}
