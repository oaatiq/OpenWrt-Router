package io.wrtpilot.core.domain

/** Rough device category, used to pick an icon. */
enum class DeviceKind { PHONE, TABLET, LAPTOP, COMPUTER, TV, CONSOLE, SPEAKER, CAMERA, PRINTER, WATCH, ROUTER, IOT, UNKNOWN }

object DeviceClassifier {

    private val rules: List<Pair<DeviceKind, List<String>>> = listOf(
        DeviceKind.TABLET to listOf("ipad", "tablet", "-tab", "galaxy-tab", "galaxytab", "kindle", "fire-hd", "mediapad", "lenovo-tab", "matepad"),
        DeviceKind.WATCH to listOf("watch", "galaxy-watch", "fitbit", "garmin"),
        DeviceKind.PHONE to listOf(
            "iphone", "android", "galaxy", "pixel", "phone", "redmi", "xiaomi", "poco", "oneplus", "oppo",
            "vivo", "realme", "huawei", "honor", "motorola", "moto-", "nokia", "infinix", "tecno", "itel", "sm-", "mi-"
        ),
        DeviceKind.LAPTOP to listOf("macbook", "laptop", "thinkpad", "notebook", "chromebook", "zenbook", "vivobook", "ideapad", "surface", "xps"),
        DeviceKind.COMPUTER to listOf("desktop", "imac", "mac-mini", "macmini", "-pc", "pc-", "workstation", "nas", "server", "raspberrypi", "synology", "qnap"),
        DeviceKind.TV to listOf("tv", "roku", "chromecast", "firetv", "fire-tv", "appletv", "apple-tv", "bravia", "webos", "tizen", "shield", "mibox", "android-box", "kodi"),
        DeviceKind.CONSOLE to listOf("playstation", "ps4", "ps5", "xbox", "nintendo", "switch", "steamdeck", "steam-deck"),
        DeviceKind.SPEAKER to listOf("echo", "alexa", "sonos", "homepod", "nest-mini", "google-home", "googlehome", "nest-audio", "speaker"),
        DeviceKind.CAMERA to listOf("cam", "ring-", "arlo", "wyze", "eufy", "doorbell", "hikvision", "dahua", "imou", "ezviz"),
        DeviceKind.PRINTER to listOf("printer", "epson", "brother", "canon", "laserjet", "officejet", "deskjet", "hp-"),
        DeviceKind.ROUTER to listOf("router", "openwrt", "access-point", "repeater", "extender", "mesh", "unifi", "tp-link", "fritz"),
        DeviceKind.IOT to listOf("esp-", "esp32", "esp8266", "tasmota", "shelly", "tuya", "sonoff", "smart-plug", "hue", "bulb", "vacuum", "roborock", "thermostat", "nest"),
    )

    private val vendorRules: List<Pair<DeviceKind, List<String>>> = listOf(
        DeviceKind.CONSOLE to listOf("nintendo", "sony interactive", "microsoft xbox"),
        DeviceKind.TV to listOf("roku", "vizio", "tcl", "hisense"),
        DeviceKind.SPEAKER to listOf("sonos", "amazon technologies"),
        DeviceKind.PRINTER to listOf("seiko epson", "brother industries", "canon"),
        DeviceKind.IOT to listOf("espressif", "tuya", "shelly", "itead", "signify", "philips lighting"),
        DeviceKind.COMPUTER to listOf("raspberry pi", "synology", "qnap", "intel corporate", "dell", "lenovo"),
        DeviceKind.ROUTER to listOf("tp-link", "netgear", "ubiquiti", "mikrotik", "avm", "gl technologies", "zyxel", "d-link"),
        DeviceKind.PHONE to listOf("apple", "samsung", "xiaomi", "huawei", "oneplus", "oppo", "vivo", "google", "motorola", "honor", "realme", "transsion"),
    )

    fun classify(name: String?, hostname: String?, vendor: String?): DeviceKind {
        val text = listOfNotNull(name, hostname).joinToString(" ").lowercase().replace('_', '-').replace(' ', '-')
        if (text.isNotBlank()) {
            for ((kind, keys) in rules) if (keys.any { text.contains(it) }) return kind
        }
        val v = vendor?.lowercase() ?: return DeviceKind.UNKNOWN
        for ((kind, keys) in vendorRules) if (keys.any { v.contains(it) }) return kind
        return DeviceKind.UNKNOWN
    }
}
