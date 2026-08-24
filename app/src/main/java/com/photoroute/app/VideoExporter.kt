package com.photoroute.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object VideoExporter {
    private const val FPS = 30
    private const val BIT_RATE = 8_000_000

    suspend fun export(
        context: Context,
        base: Bitmap,
        plan: Plan,
        spec: CardSpec,
        durationSec: Float,
        cursorAt: (Float) -> Long,
        onProgress: (Float) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        val width = spec.ratio.w
        val height = spec.ratio.h
        val temp = File(context.cacheDir, "dongseon_${System.currentTimeMillis()}.mp4")
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var surface: android.view.Surface? = null
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec = encoder
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = encoder.createInputSurface()
            surface = inputSurface
            val writer = MediaMuxer(temp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = writer
            encoder.start()

            val info = MediaCodec.BufferInfo()
            var track = -1
            var muxerStarted = false
            val frameCount = (durationSec.coerceIn(4f, 60f) * FPS).toInt()
            val frameNanos = 1_000_000_000L / FPS
            var targetTime = System.nanoTime()

            fun drain(end: Boolean): Boolean {
                while (true) {
                    val index = encoder.dequeueOutputBuffer(info, if (end) 10_000L else 0L)
                    when {
                        index == MediaCodec.INFO_TRY_AGAIN_LATER -> return !end
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            track = writer.addTrack(encoder.outputFormat)
                            writer.start()
                            muxerStarted = true
                        }
                        index >= 0 -> {
                            val buffer = encoder.getOutputBuffer(index) ?: return false
                            if (info.size > 0 && muxerStarted) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                writer.writeSampleData(track, buffer, info)
                            }
                            val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            encoder.releaseOutputBuffer(index, false)
                            if (eos) return true
                        }
                    }
                }
            }

            for (frame in 0 until frameCount) {
                val progress = frame.toFloat() / (frameCount - 1).coerceAtLeast(1)
                val canvas = inputSurface.lockCanvas(null)
                try {
                    MapRenderer.drawScene(canvas, base, plan, spec, cursorAt(progress))
                } finally {
                    inputSurface.unlockCanvasAndPost(canvas)
                }
                drain(false)
                onProgress(progress)
                targetTime += frameNanos
                val wait = targetTime - System.nanoTime()
                if (wait > 0) Thread.sleep(wait / 1_000_000L, (wait % 1_000_000L).toInt())
            }
            encoder.signalEndOfInputStream()
            while (!drain(true)) Unit
            encoder.stop()
            writer.stop()

            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "dongseon_${System.currentTimeMillis()}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/동선지도")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@withContext false
            resolver.openOutputStream(uri)?.use { out -> temp.inputStream().use { it.copyTo(out) } }
                ?: return@withContext false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            }
            onProgress(1f)
            true
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { muxer?.release() }
            runCatching { surface?.release() }
            temp.delete()
        }
    }
}
