package moe.fuqiuluo.portalex.ui.mock

import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONArray
import com.alibaba.fastjson2.JSONObject

data class HistoricalRoute(
    val name: String,
    val route: List<Pair<Double, Double>>
) {
    companion object {
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
            return HistoricalRoute(name, points)
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
            return obj
        }
    }
}
