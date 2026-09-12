package moe.fuqiuluo.xposed.utils

/**
 * **跨进程协议的唯一事实来源**（app ⇄ 模块 ⇄ system_server 那一层）。
 *
 * 为什么要有这个文件：这条通道以前是两处各自维护的字符串字面量（app 侧 `MockServiceHelper`
 * 写 `putString("command_id", "start")`，模块侧 `when (commandId) { "start" -> ... }` 读），
 * 于是**改名/打错字编译器一声不吭**，表现是"命令发出去了但系统侧什么都没发生" —— 这类故障
 * 排查过一次（配置不传播），代价很高。
 *
 * 规则：
 *  1. 新增/改名命令或键，**只改这里**；两侧引用常量，编译器会替你抓错。
 *  2. 命令名与键名允许同名（如 is_start），但必须用对命名空间：命令用 [Cmd]，键用 [Key]。
 *  3. 这里只放"跨进程可见"的名字；进程内部的私有字符串不要放进来。
 */
object PortalProtocol {

    /** 虚拟 provider 名（`LocationManager.sendExtraCommand` 的第一个参数） */
    const val PROVIDER = "portal"

    /** 模块自己的偏好文件名（app 与模块读的是同一份文件） */
    const val PREFS_NAME = "portal"

    /** 命令名：sendExtraCommand(provider, <command>, extras) 的第二个参数 */
    object Cmd {
        const val BROADCAST_LOCATION = "broadcast_location"
        const val GET_ALTITUDE       = "get_altitude"
        const val GET_BEARING        = "get_bearing"
        const val GET_LISTENER_SIZE  = "get_listener_size"
        const val GET_LOCATION       = "get_location"
        const val GET_SENSOR_STATUS  = "get_sensor_status"
        const val GET_SPEED          = "get_speed"
        const val IS_GNSS_START      = "is_gnss_start"
        const val IS_SENSOR_MOCK     = "is_sensor_mock"
        const val IS_START           = "is_start"
        const val IS_WIFI_MOCK_START = "is_wifi_mock_start"
        const val MOVE               = "move"
        const val PUT_CONFIG         = "put_config"
        const val RANDOM             = "random"
        const val SET_ALTITUDE       = "set_altitude"
        const val SET_BEARING        = "set_bearing"
        const val SET_PROXY          = "set_proxy"
        const val SET_SENSOR_MOCK    = "set_sensor_mock"
        const val SET_SPEED          = "set_speed"
        const val SET_SPEED_AMP      = "set_speed_amp"
        const val START              = "start"
        const val START_GNSS_MOCK    = "start_gnss_mock"
        const val START_WIFI_MOCK    = "start_wifi_mock"
        const val STOP               = "stop"
        const val STOP_GNSS_MOCK     = "stop_gnss_mock"
        const val STOP_WIFI_MOCK     = "stop_wifi_mock"
        const val SYNC_CONFIG        = "sync_config"
        const val UPDATE_LOCATION    = "update_location"
        const val EXCHANGE_KEY       = "exchange_key"
    }

    /** Bundle 键名：extras 与回包里的字段 */
    object Key {
        const val ACCURACY                  = "accuracy"
        const val ALTITUDE                  = "altitude"
        const val BEARING                   = "bearing"
        const val BINDER_SENSOR_MOCK        = "binder_sensor_mock"
        const val CADENCE_SCALE             = "cadence_scale"
        const val COMMAND_ID                = "command_id"
        const val DISABLE_FUSED_LOCATION    = "disable_fused_location"
        const val DISABLE_GET_FROM_LOCATION = "disable_get_from_location"
        const val DISABLE_REQUEST_GEOFENCE  = "disable_request_geofence"
        const val ENABLE                    = "enable"
        const val ENABLE_AGPS               = "enable_agps"
        const val ENABLE_DEBUG_LOG          = "enable_debug_log"
        const val ENABLE_LOG                = "enable_log"
        const val ENABLE_NMEA               = "enable_nmea"
        const val HAS_BEARINGS              = "has_bearings"
        const val HIDE_MOCK                 = "hide_mock"
        const val HOOK_WIFI                 = "hook_wifi"
        const val IS_GNSS_START             = "is_gnss_start"
        const val IS_START                  = "is_start"
        const val IS_WIFI_MOCK_START        = "is_wifi_mock_start"
        const val EXCHANGE_REPLY            = "key"
        const val LAST_LOCATION             = "last_location"
        const val LAT                       = "lat"
        const val LATITUDE                  = "latitude"
        const val LON                       = "lon"
        const val LONGITUDE                 = "longitude"
        const val LOOP_BROADCAST_LOCATION   = "loop_broadcast_location"
        const val MIN_SATELLITES            = "min_satellites"
        const val MODE                      = "mode"
        const val DISTANCE                  = "n"
        const val NEED_DOWNGRADE_TO_2G      = "need_downgrade_to_2g"
        const val NOISE_PROFILE             = "noise_profile"
        const val PROXY_BINDER              = "proxy"
        const val SENSOR_GRID_HZ            = "sensor_grid_hz"
        const val LISTENER_SIZE             = "size"
        const val SPEED                     = "speed"
        const val SPEED_AMPLITUDE           = "speed_amplitude"
    }

    /** 偏好键名（app 写、模块侧反射读同一份 prefs 文件） */
    object Pref {
        const val BINDER_SENSOR_MOCK = "binderSensorMock"
    }
}
