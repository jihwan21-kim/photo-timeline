package com.photoroute.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** Bounded memory + disk tile cache, adapted from mahlernim/google-timeline-visualizer (MIT). */
class TileStore(context: Context) {
    private val directory = File(context.cacheDir, "carto-tiles").apply { mkdirs() }
    private val loading = ConcurrentHashMap<String, Any>()
    private val memory = object : LruCache<String, Bitmap>(48 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun cached(z: Int, x: Int, y: Int): Bitmap? = memory.get(key(z, x, y))

    fun load(z: Int, x: Int, y: Int): Bitmap? {
        val key = key(z, x, y)
        memory.get(key)?.let { return it }
        val lock = loading.computeIfAbsent(key) { Any() }
        return synchronized(lock) {
            try {
                memory.get(key)?.let { return@synchronized it }
                val target = File(directory, "$key.png")
                if (target.isFile) {
                    BitmapFactory.decodeFile(target.absolutePath)?.let {
                        memory.put(key, it)
                        return@synchronized it
                    }
                }
                val temp = File.createTempFile("$key-", ".tmp", directory)
                var connection: HttpURLConnection? = null
                try {
                    connection = URL("https://a.basemaps.cartocdn.com/light_all/$z/$x/$y.png")
                        .openConnection() as HttpURLConnection
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 15_000
                    connection.setRequestProperty("User-Agent", "PhotoTimeline-Android/1.0")
                    if (connection.responseCode !in 200..299) return@synchronized null
                    connection.inputStream.use { input -> temp.outputStream().use(input::copyTo) }
                    if (!temp.renameTo(target)) temp.copyTo(target, overwrite = true)
                    BitmapFactory.decodeFile(target.absolutePath)?.also { memory.put(key, it) }
                } finally {
                    temp.delete()
                    connection?.disconnect()
                }
            } catch (_: Exception) {
                null
            } finally {
                loading.remove(key)
            }
        }
    }

    private fun key(z: Int, x: Int, y: Int) = "${z}_${x}_${y}"
}
