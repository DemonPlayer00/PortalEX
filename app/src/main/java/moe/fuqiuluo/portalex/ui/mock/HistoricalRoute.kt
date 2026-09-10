package moe.fuqiuluo.portalex.ui.mock

import android.graphics.Color
import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONArray
import com.alibaba.fastjson2.JSONObject

data class HistoricalRoute(
    val name: String,
    val route: List<Pair<Double, Double>>,
    /**
     * 逐端点平滑标志：smooth[i] = 「端点 i 之后的线段（i → i+1）是否平滑过渡」。
     * 长度与 [route] 端点一一对应；**最后一个端点没有后续线段**（占位 false）。
     * 旧数据无该字段时补全为全 false（行为同不平滑）。
     */
    val smooth: List<Boolean> = emptyList()
) {
    /** 第 index 段（端点 index → index+1）是否需要平滑；越界/缺省一律 false */
    fun isSmooth(index: Int): Boolean = smooth.getOrElse(index) { false }

    companion object {
        /** 普通线段颜色（编辑/预览一致） */
        val COLOR_NORMAL = Color.argb(178, 0, 78, 255)

        /** 平滑线段颜色（编辑/预览一致） */
        val COLOR_SMOOTH = Color.argb(178, 0, 200, 83)
        /**
         * 显式 JSON 读写。
         *
         * 不使用 fastjson2 的 `parseArray(json, HistoricalRoute::class.java)`：Android 运行时
         * 无法反射取得 Kotlin data class 的构造参数名，反序列化会把 name 传成 null 并抛
         * NPE（`Parameter specified as non-null is null: HistoricalRoute.<init>, parameter name`）。
         * 这里统一走 JSONArray/JSONObject 手工转换，存储格式：
         * `[{"name":"...","route":[{"first":lat,"second":lon}, ...]}, ...]`
         */
        fun listToJson(routes: List<HistoricalRoute>): String {
            val array = JSONArray()
            routes.forEach { array.add(it.toJsonObject()) }
            return array.toJSONString()
        }

        fun toJson(route: HistoricalRoute): String = route.toJsonObject().toJSONString()

        /** 解析路线列表；脏数据跳过，绝不抛异常（保证页面可用）。 */
        fun parseList(json: String): MutableList<HistoricalRoute> {
            if (json.isBlank()) return mutableListOf()
            val array = runCatching { JSONArray.parse(json) }.getOrNull() ?: return mutableListOf()
            val routes = mutableListOf<HistoricalRoute>()
            for (i in 0 until array.size) {
                parseRoute(array[i])?.let { routes.add(it) }
            }
            return routes
        }

        /** 解析单条路线；解析失败返回 null。 */
        fun parse(json: String): HistoricalRoute? {
            if (json.isBlank()) return null
            val element = runCatching { JSON.parse(json) }.getOrNull() ?: return null
            return parseRoute(element)
        }

        private fun parseRoute(element: Any?): HistoricalRoute? {
            val obj = element as? JSONObject ?: return null
            val name = obj.getString("name") ?: return null
            val points = mutableListOf<Pair<Double, Double>>()
            obj.getJSONArray("route")?.forEach { point ->
                when (point) {
                    is JSONObject -> {
                        // 兼容 {"first":lat,"second":lon} 与 {"lat":..,"lon":..}
                        val lat = when {
                            point.containsKey("first") -> point.getDoubleValue("first")
                            point.containsKey("lat") -> point.getDoubleValue("lat")
                            else -> Double.NaN
                        }
                        val lon = when {
                            point.containsKey("second") -> point.getDoubleValue("second")
                            point.containsKey("lon") -> point.getDoubleValue("lon")
                            else -> Double.NaN
                        }
                        if (!lat.isNaN() && !lon.isNaN()) points.add(Pair(lat, lon))
                    }
                    is JSONArray -> {
                        // 兼容 [lat, lon]
                        if (point.size >= 2) {
                            points.add(Pair(point.getDoubleValue(0), point.getDoubleValue(1)))
                        }
                    }
                }
            }
            return HistoricalRoute(name, points, parseSmooth(obj, points.size))
        }

        /** 解析逐端点平滑标志；缺失/非法一律 false，长度对齐端点数 */
        private fun parseSmooth(obj: JSONObject, pointCount: Int): List<Boolean> {
            val array = obj.getJSONArray("smooth") ?: return List(pointCount) { false }
            return List(pointCount) { i ->
                when (val v = array.get(i)) {
                    is Boolean -> v
                    is Number -> v.toInt() != 0
                    else -> false
                }
            }
        }

        private fun HistoricalRoute.toJsonObject(): JSONObject {
            val obj = JSONObject()
            obj["name"] = name
            val points = JSONArray()
            route.forEach { (lat, lon) ->
                val point = JSONObject()
                point["first"] = lat
                point["second"] = lon
                points.add(point)
            }
            obj["route"] = points
            val smoothArray = JSONArray()
            smooth.forEach { smoothArray.add(it) }
            obj["smooth"] = smoothArray
            return obj
        }
    }
}
