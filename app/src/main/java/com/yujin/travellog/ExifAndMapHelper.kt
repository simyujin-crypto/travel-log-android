package com.yujin.travellog

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

object ExifAndMapHelper {

    suspend fun parseMediaList(context: Context, uris: List<Uri>, apiKey: String): List<MediaItem> =
        withContext(Dispatchers.IO) {
            val sdf = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.getDefault())
            val results = mutableListOf<MediaItem>()

            for (uri in uris) {
                val mime = context.contentResolver.getType(uri) ?: ""
                val isVideo = mime.startsWith("video")
                var lat: Double? = null
                var lng: Double? = null
                var timeMs: Long = System.currentTimeMillis()

                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        if (!isVideo) {
                            val exif = ExifInterface(input)
                            val latLong = exif.latLong
                            if (latLong != null) {
                                lat = latLong[0]
                                lng = latLong[1]
                            }
                            val dt = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                                ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                            if (!dt.isNullOrBlank()) {
                                sdf.parse(dt)?.let { timeMs = it.time }
                            }
                        }
                    }
                } catch (_: Exception) { }

                val place = if (lat != null && lng != null) {
                    reverseGeocode(lat!!, lng!!, apiKey)
                } else {
                    "여행 기록"
                }

                results.add(
                    MediaItem(
                        uriString = uri.toString(),
                        isVideo = isVideo,
                        timestamp = timeMs,
                        lat = lat,
                        lng = lng,
                        placeName = place
                    )
                )
            }
            results.sortedBy { it.timestamp }
        }

    private fun reverseGeocode(lat: Double, lng: Double, apiKey: String): String {
        return try {
            val urlStr =
                "https://maps.googleapis.com/maps/api/geocode/json?latlng=$lat,$lng&language=ko&key=$apiKey"
            val jsonText = URL(urlStr).readText()
            val root = JSONObject(jsonText)
            val arr = root.optJSONArray("results")
            if (arr != null && arr.length() > 0) {
                val formatted = arr.getJSONObject(0).optString("formatted_address", "")
                val parts = formatted.split(" ").filter { it.isNotBlank() }
                parts.takeLast(2).joinToString(" ").ifBlank { "여행지" }
            } else {
                "여행지"
            }
        } catch (_: Exception) {
            "여행지"
        }
    }

    /**
     * 사진 GPS를 기반으로 한국 전체 여행경로를 한눈에 보여주는 Static Map 이미지를 다운로드해 로컬 파일로 저장
     */
    suspend fun downloadKoreaRouteMap(
        context: Context,
        items: List<MediaItem>,
        apiKey: String
    ): File? = withContext(Dispatchers.IO) {
        val coords = items.filter { it.lat != null && it.lng != null }
            .map { "${it.lat},${it.lng}" }
        if (coords.isEmpty()) return@withContext null

        val pathParam = URLEncoder.encode("color:0xd4af37ff|weight:5|" + coords.joinToString("|"), "UTF-8")
        val markersParam = coords.mapIndexed { idx, c ->
            val label = ('A' + (idx % 26))
            URLEncoder.encode("color:red|label:$label|$c", "UTF-8")
        }.joinToString("&markers=")

        // 한국 전체 맥락이 보이도록 넓게 잡되 경로를 포함
        val urlStr =
            "https://maps.googleapis.com/maps/api/staticmap?size=1080x720&scale=2&maptype=roadmap" +
                "&path=$pathParam&markers=$markersParam&key=$apiKey"

        return@withContext try {
            val bytes = URL(urlStr).readBytes()
            val bmp: Bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            val outFile = File(context.cacheDir, "korea_route_overview.png")
            FileOutputStream(outFile).use { fos ->
                bmp.compress(Bitmap.CompressFormat.PNG, 95, fos)
            }
            outFile
        } catch (_: Exception) {
            null
        }
    }
}