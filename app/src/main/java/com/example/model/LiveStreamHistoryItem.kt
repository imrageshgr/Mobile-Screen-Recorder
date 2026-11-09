package com.example.model

import org.json.JSONObject
import java.util.UUID

/**
 * LiveStreamHistoryItem
 * Permanent, immutable audit log entry of a live stream broadcast session.
 * Stores platform, date, format (resolution/fps/bitrate/orientation), and title.
 * As per user specification, history cannot be deleted.
 */
data class LiveStreamHistoryItem(
    val id: String = UUID.randomUUID().toString(),
    val platform: String,
    val streamTitle: String,
    val timestamp: Long = System.currentTimeMillis(),
    val dateFormatted: String,
    val resolution: String,
    val fps: Int,
    val bitrateMbps: Int,
    val orientation: String,
    val streamServer: String
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("platform", platform)
            put("streamTitle", streamTitle)
            put("timestamp", timestamp)
            put("dateFormatted", dateFormatted)
            put("resolution", resolution)
            put("fps", fps)
            put("bitrateMbps", bitrateMbps)
            put("orientation", orientation)
            put("streamServer", streamServer)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): LiveStreamHistoryItem {
            return LiveStreamHistoryItem(
                id = json.optString("id", UUID.randomUUID().toString()),
                platform = json.optString("platform", "YouTube"),
                streamTitle = json.optString("streamTitle", "Mobile Game Live Stream"),
                timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                dateFormatted = json.optString("dateFormatted", ""),
                resolution = json.optString("resolution", "1080p"),
                fps = json.optInt("fps", 60),
                bitrateMbps = json.optInt("bitrateMbps", 12),
                orientation = json.optString("orientation", "16:9 Landscape"),
                streamServer = json.optString("streamServer", "rtmp://")
            )
        }
    }
}
