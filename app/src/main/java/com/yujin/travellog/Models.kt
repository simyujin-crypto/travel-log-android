package com.yujin.travellog

import android.net.Uri
import java.io.Serializable

data class MediaItem(
    val uriString: String,
    val isVideo: Boolean,
    val timestamp: Long,
    val lat: Double?,
    val lng: Double?,
    val placeName: String
) : Serializable

data class TripConfig(
    val title: String = "속리산등반",
    val dateText: String = "2026.11.16",
    val companions: String = "구주연",
    val scheduleType: String = "당일",
    val departure: String = "메가커피수원대점 오전10시",
    val lodging: String = "없음",
    val coverUriString: String? = null,
    val bgmUriString: String? = null,
    val apiKey: String = "AIzaSyCm6JNhKc5ghV8qSTIzrrf5FYjlHqoa6jo",
    val items: ArrayList<MediaItem> = arrayListOf()
) : Serializable {
    fun companionCount(): Int {
        val list = companions.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return list.size
    }

    fun getUri(raw: String?): Uri? = raw?.let { Uri.parse(it) }
}