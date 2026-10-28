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

    /**
     * 多条目：基站 "mcc/mnc|cid/tac"，Wi-Fi "ssid|bssid"。
     * 空列表 = 只用上面的单条字段；非空时**列表优先**，首条即"当前连接"的那一条。
     */
    @Volatile var cellList: List<String> = emptyList()
    @Volatile var wifiList: List<String> = emptyList()

    fun firstCell(): Triple<String, String, Pair<String, String>> {
        val e = cellList.firstOrNull() ?: return Triple(cellMcc, cellMnc, cellCid to cellTac)
        val (a, b) = e.split("|", limit = 2).let { it.getOrNull(0).orEmpty() to it.getOrNull(1).orEmpty() }
        val (mcc, mnc) = a.split("/", limit = 2).let { it.getOrNull(0).orEmpty() to it.getOrNull(1).orEmpty() }
        val (cid, tac) = b.split("/", limit = 2).let { it.getOrNull(0).orEmpty() to it.getOrNull(1).orEmpty() }
        return Triple(mcc.ifBlank { cellMcc }, mnc.ifBlank { cellMnc }, cid.ifBlank { cellCid } to tac.ifBlank { cellTac })
    }

    fun firstWifi(): Pair<String, String> {
        val e = wifiList.firstOrNull() ?: return wifiSsid to wifiBssid
        val (a, b) = e.split("|", limit = 2).let { it.getOrNull(0).orEmpty() to it.getOrNull(1).orEmpty() }
        return (a.ifBlank { wifiSsid }) to (b.ifBlank { wifiBssid })
    }
}
