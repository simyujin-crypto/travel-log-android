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

    suspend fun parseMediaList(
        context: Context,
        uris: List<Uri>,
        apiKey: String,
        fallbackTitle: String
    ): List<MediaItem> = withContext(Dispatchers.IO) {
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
            } catch (_: Exception) {
            }

            // GPS가 없는 사진은 직전 유효 좌표가 있으면 이어받고, 없으면 fallbackTitle 사용
            if (lat == null || lng == null) {
                val prevWithGps = results.lastOrNull { it.lat != null && it.lng != null }
                if (prevWithGps != null) {
                    lat = prevWithGps.lat
                    lng = prevWithGps.lng
                }
            }

            val place = if (lat != null && lng != null) {
                reverseGeocode(lat!!, lng!!, apiKey).ifBlank { fallbackTitle }
            } else {
                fallbackTitle
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
                // 대한민국 행정구역 앞부분 제거 후 주요 지명 추출
                val filtered = parts.filter { it != "대한민국" }
                filtered.takeLast(2).joinToString(" ").ifBlank { formatted }
            } else {
                ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * 사진 GPS를 기반으로 한국 전체 경로가 보이도록 Google Static Maps 이미지를 생성
     */
    suspend fun downloadKoreaRouteMap(
        context: Context,
        items: List<MediaItem>,
        apiKey: String
    ): File? = withContext(Dispatchers.IO) {
        val coords = items.filter { it.lat != null && it.lng != null }
            .map { "${it.lat},${it.lng}" }
        if (coords.isEmpty()) return@withContext null

        val sampled = if (coords.size > 20) {
            val step = coords.size / 20
            coords.filterIndexed { index, _ -> index % step == 0 || index == coords.lastIndex }
        } else {
            coords
        }

        val pathParam = URLEncoder.encode(
            "color:0xffd4af37|weight:5|" + sampled.joinToString("|"),
            "UTF-8"
        )
        val startCoord = sampled.first()
        val endCoord = sampled.last()
        val startMarker = URLEncoder.encode("color:green|label:S|$startCoord", "UTF-8")
        val endMarker = URLEncoder.encode("color:red|label:E|$endCoord", "UTF-8")

        // 한국 전체 지형 감각이 잡히도록 줌 레벨과 크기 지정
        val urlStr =
            "https://maps.googleapis.com/maps/api/staticmap?size=800x600&scale=2&maptype=roadmap" +
                "&path=$pathParam&markers=$startMarker&markers=$endMarker&region=KR&key=$apiKey"

        return@withContext try {
            val bytes = URL(urlStr).readBytes()
            val bmp: Bitmap? = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bmp == null) return@withContext null
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