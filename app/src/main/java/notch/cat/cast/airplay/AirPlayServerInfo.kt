package notch.cat.cast.airplay

internal object AirPlayServerInfo {
  fun xml(deviceId: String) =
    """<?xml version="1.0" encoding="UTF-8"?>
    <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
    <plist version="1.0"><dict>
    <key>deviceid</key><string>$deviceId</string>
    <key>features</key><integer>$FEATURES</integer>
    <key>macAddress</key><string>$deviceId</string>
    <key>model</key><string>${AirPlayProfile.MODEL}</string>
    <key>osBuildVersion</key><string>12B435</string>
    <key>protovers</key><string>1.0</string>
    <key>srcvers</key><string>${AirPlayProfile.SOURCE_VERSION}</string>
    <key>vv</key><integer>2</integer>
    </dict></plist>""".trimIndent().toByteArray(Charsets.UTF_8)

  private const val FEATURES = 0x27f
}
