package notch.cat.cast

import android.content.Intent

object Consts {
  object App {
    const val TAG = "mang"
    const val MIRROR_URI = "airplay://screen-mirror"
    const val OPEN_PLAYER_CHANNEL_ID = "notch_cat_cast_open_player"
    const val OPEN_PLAYER_NOTIFICATION_ID = 1002
    const val SERVICE_CHANNEL_ID = "notch_cat_cast_service"
    const val SERVICE_NOTIFICATION_ID = 1001
    const val PROGRESS_MAX = 10_000
    val START_ACTIONS = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_USER_UNLOCKED, Intent.ACTION_MY_PACKAGE_REPLACED)
  }

  object Dlna {
    const val HTTP_HEADER_LIMIT = 64 * 1024
    const val HTTP_HEADER_END = 0x0D0A0D0A
    const val SSDP_ADDRESS = "239.255.255.250"
    const val SSDP_PORT = 1900
    const val MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"
    const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
    const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"
    const val CONNECTION_MANAGER = "urn:schemas-upnp-org:service:ConnectionManager:1"
    const val SINK_PROTOCOL_INFO = "http-get:*:video/mp4:*,http-get:*:video/mpeg:*,http-get:*:application/vnd.apple.mpegurl:*,http-get:*:application/x-mpegURL:*,http-get:*:*:*"
    const val SERVER_HEADER = "Linux/5.15.170-android14-11-gf4a1f03072af HTTP/1.0 BDLE+DLNA/1.1 NotchCatCast/1.0"
    const val MAX_LOG_BODY = 2048
    const val SOCKET_TIMEOUT_MS = 10_000
    const val SSDP_ALIVE_INTERVAL_MS = 900_000L
  }

  object Airplay {
    const val TAG = "mang"
    const val PORT = 7000
    const val SOURCE_VERSION = "220.68"
    const val MODEL = "AppleTV3,2"
    const val FEATURES_HEX = "0x5A7FFEE6,0x400"
    const val FEATURES = 4399564848870L
    const val FLAGS_HEX = "0x4"
    const val BPLIST = "application/x-apple-binary-plist"
    const val XML_PLIST = "text/x-apple-plist+xml"
    const val OCTET = "application/octet-stream"
    const val TYPE_MIRROR = 110
    const val VIDEO_HEADER_BYTES = 128
    const val MAX_VIDEO_PAYLOAD_BYTES = 8 * 1024 * 1024
    val START_CODE = byteArrayOf(0, 0, 0, 1)
    val TYPE_AUDIO = setOf(96, 100, 101)
  }
}
