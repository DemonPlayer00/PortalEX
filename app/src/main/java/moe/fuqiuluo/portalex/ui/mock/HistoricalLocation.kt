package moe.fuqiuluo.portalex.ui.mock

import java.math.BigDecimal

data class HistoricalLocation(
    val name: String,
    val address: String,
    val lat: Double,
    val lon: Double
) {
    companion object {
        // Format: "name","address","lat","lon"
        fun fromString(str: String): HistoricalLocation {
            // CSV parser supporting commas inside quoted fields
            val fields = mutableListOf<String>()
            var currentField = StringBuilder()
            var inQuotes = false
            
            var i = 0
            while (i < str.length) {
                val char = str[i]
                when {
                    char == '"' && (i + 1 >= str.length || str[i + 1] != '"') -> {
                        // Toggle quote state
                        inQuotes = !inQuotes
                    }
                    char == '"' && i + 1 < str.length && str[i + 1] == '"' -> {
                        // Handle escaped quotes ("") 
                        currentField.append('"')
                        // Skip next quote
                        i++
                    }
                    char == ',' && !inQuotes -> {
                        // Comma as separator
                        fields.add(currentField.toString().trim())
                        currentField = StringBuilder()
                    }
                    else -> {
                        // Regular character
                        currentField.append(char)
                    }
                }
                i++
            }
            
            // Add the last field
            fields.add(currentField.toString().trim())
            
            if (fields.size != 4) {
                throw IllegalArgumentException("Invalid format. Expected 4 fields but got ${fields.size}: $str")
            }
            
            /*
             * 名称/地址**不能再 trim('"')**：引号已经在解析阶段按 CSV 规则处理掉了
             * （inQuotes 状态机 + `""` 转义），字段内容里出现的引号是**数据本身**。
             * 旧实现对每个字段再 trim('"') ⇒ `他说"走这边"` 写出去读回来会少一个引号
             * （实测往返不一致，见 HistoricalLocationTest）。
             * 数值字段保留 trim('"')：数字不可能合法地以引号开头/结尾，旧的带引号数据还能读。
             */
            return HistoricalLocation(
                name = fields[0],
                address = fields[1],
                lat = fields[2].trim('"').toDouble(),
                lon = fields[3].trim('"').toDouble()
            )
        }
    }

    override fun toString(): String {
        // BigDecimal.valueOf（走字符串表示）而不是 BigDecimal(double)：后者展开**二进制精确值**，
        // 22.54321 会被写成 "22.5432100000000009738392540805…"（几十位），把偏好文件撑大且难读。
        val plainLat = BigDecimal.valueOf(lat).toPlainString()
        val plainLon = BigDecimal.valueOf(lon).toPlainString()
        return "${csvField(name)},${csvField(address)},$plainLat,$plainLon"
    }

    /**
     * 一个 CSV 字段：含逗号或双引号时整体加引号，内部双引号按 CSV 规则翻倍。
     *
     * 只加引号不转义是不够的 —— 解析器把单个 `"` 当状态开关，于是 `他说"走这边"`
     * 这类名字写出去再读回来就散了（实测往返不一致，见 HistoricalLocationTest）。
     */
    private fun csvField(value: String): String =
        if (value.contains(',') || value.contains('"')) "\"${value.replace("\"", "\"\"")}\""
        else value
}
