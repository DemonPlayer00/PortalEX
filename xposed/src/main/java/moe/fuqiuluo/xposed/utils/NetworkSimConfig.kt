package moe.fuqiuluo.xposed.utils

/** 三个网络模拟页面下发的运行时配置（system_server 内存态）。 */
object NetworkSimConfig {
    @Volatile var gpsEnabled = false
    @Volatile var cellEnabled = false
    @Volatile var wifiEnabled = false
    @Volatile var gpsCode = "3"
    @Volatile var gpsSatellites = "12"
    @Volatile var cellMcc = "460"
    @Volatile var cellMnc = "00"
    @Volatile var cellCid = "123456789"
    @Volatile var cellTac = "1234"
    @Volatile var wifiSsid = "CMCC-EDU"
    @Volatile var wifiBssid = "02:00:00:00:00:00"
    @Volatile var wifiRssi = -55
}
