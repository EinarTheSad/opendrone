package pl.einarthesad.opendrone

import android.content.Context

enum class SnapshotFormat {
    JPEG,
    PNG
}

enum class VideoFormat {
    AVI,
    MP4
}

data class ConnectionConfig(
    val droneIp: String,
    val localVideoPort: Int,
    val droneVideoPort: Int
)

class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("opendrone_settings", Context.MODE_PRIVATE)

    var localVideoPort: Int
        get() = prefs.getInt("local_video_port", 40238)
        set(value) = prefs.edit().putInt("local_video_port", value).apply()

    var droneVideoPort: Int
        get() = prefs.getInt("drone_video_port", 8080)
        set(value) = prefs.edit().putInt("drone_video_port", value).apply()

    var snapshotFormat: SnapshotFormat
        get() = readEnum("snapshot_format", SnapshotFormat.JPEG)
        set(value) = prefs.edit().putString("snapshot_format", value.name).apply()

    var videoFormat: VideoFormat
        get() = readEnum("video_format", VideoFormat.AVI)
        set(value) = prefs.edit().putString("video_format", value.name).apply()

    private inline fun <reified T : Enum<T>> readEnum(key: String, fallback: T): T {
        val value = prefs.getString(key, fallback.name) ?: return fallback

        return try {
            enumValueOf(value)
        } catch (_: Exception) {
            fallback
        }
    }
}
