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
        const val GET_FUSED_STATE    = "get_fused_state"
        const val GET_LISTENER_SIZE  = "get_listener_size"
        const val GET_LOCATION       = "get_location"
        const val GET_MOTION         = "get_motion"
        const val GET_SENSOR_STATUS  = "get_sensor_status"
        const val GET_STAMINA        = "get_stamina"
        const val GET_SPEED          = "get_speed"
        const val IS_GNSS_START      = "is_gnss_start"
        const val IS_SENSOR_MOCK     = "is_sensor_mock"
        const val IS_START           = "is_start"
        const val IS_WIFI_MOCK_START = "is_wifi_mock_start"
        const val MOVE               = "move"
        const val PUT_CONFIG         = "put_config"
        const val RANDOM             = "random"
        const val RESET_STAMINA      = "reset_stamina"
        const val ROUTE_CONTROL      = "route_control"
        const val SET_ALTITUDE       = "set_altitude"
        const val SET_BEARING        = "set_bearing"
        const val SET_PROXY          = "set_proxy"
        const val SET_ROUTE          = "set_route"
        const val SET_ROCKER         = "set_rocker"
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
        /** 步频侧外周传感器模拟：接管 TYPE_STEP_COUNTER / TYPE_STEP_DETECTOR */
        const val CADENCE_MOCK              = "cadence_mock"

        /** 角度与指南针侧外周传感器模拟：接管加速度/陀螺/磁场（朝向那一路） */
        const val ORIENTATION_MOCK          = "orientation_mock"
        const val CADENCE_SCALE             = "cadence_scale"
        /** 体力参数（`StaminaConfig.toWire()` 的定序数组） */
        const val STAMINA_CONFIG            = "stamina_config"
        /** 体力**状态回读**（模块 → App）：一步到位的一组，键名与 `StaminaModel.Snapshot` 一一对应 */
        const val STAMINA_ENABLED           = "stamina_enabled"
        const val STAMINA_WIRE              = "stamina_wire"
        const val STAMINA_PERCENT           = "stamina_percent"
        const val STAMINA_MULTIPLIER        = "stamina_multiplier"
        const val STAMINA_RESTING           = "stamina_resting"
        const val STAMINA_REMAINING_SEC     = "stamina_remaining_sec"
        const val STAMINA_BLEND             = "stamina_blend"
        const val STAMINA_REST_COUNT        = "stamina_rest_count"
        const val STAMINA_REST_TOTAL_SEC    = "stamina_rest_total_sec"
        const val STAMINA_APPARENT_SPEED    = "stamina_apparent_speed"
        const val STAMINA_IGNORED_TICKS     = "stamina_ignored_ticks"
        /** 距上一次"真的有位移"过去了多少秒（界面据此区分"跑动中"与"空闲"） */
        const val STAMINA_MOVED_AGO_SEC     = "stamina_moved_ago_sec"
        const val STAMINA_RECOVER_PER_MIN   = "stamina_recover_per_min"
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
        // 融合定位（fused provider）三态处置：0=拒绝 1=放行 2=伪装
        const val FUSED_MODE                = "fused_mode"
        // 本机是否存在融合定位（App 据此决定设置项可用性与默认值）
        const val FUSED_AVAILABLE           = "fused_available"
        // 融合 hook 状态单行诊断（调试模式才打日志，回包始终带）
        const val FUSED_STATUS              = "fused_status"
        const val LAST_LOCATION             = "last_location"
        const val LAT                       = "lat"
        const val LATITUDE                  = "latitude"
        const val LON                       = "lon"
        const val LONGITUDE                 = "longitude"
        const val LOOP_BROADCAST_LOCATION   = "loop_broadcast_location"
        const val MIN_SATELLITES            = "min_satellites"
        const val MODE                      = "mode"
        const val MOTION_COMPLETED          = "motion_completed"
        const val MOTION_MODE               = "motion_mode"
        const val MOTION_PLAYING            = "motion_playing"
        const val DISTANCE                  = "n"
        const val NEED_DOWNGRADE_TO_2G      = "need_downgrade_to_2g"
        const val NOISE_PROFILE             = "noise_profile"
        const val PROXY_BINDER              = "proxy"
        /** 定位上报间隔（毫秒）：App 的设置项，迁移后由模块时钟按它出帧 */
        const val REPORT_DURATION           = "report_duration"
        /** 路线播放路径点（展开后的采样点，见 `MotionEngine.setRoute`） */
        const val ROUTE_LAT                 = "route_lat"
        const val ROUTE_LON                 = "route_lon"
        const val ROUTE_DISTANCE            = "route_distance"
        const val ROUTE_POINTS              = "route_points"
        const val ROUTE_TRAVELLED           = "route_travelled"
        const val LISTENER_SIZE             = "size"
        const val SPEED                     = "speed"
        const val SPEED_AMPLITUDE           = "speed_amplitude"
    }

    /** 偏好键名（app 写、模块侧反射读同一份 prefs 文件） */
    object Pref {
        /** 步频侧外周传感器模拟（默认开，与原总开关一致 —— 拆分不得静默改变行为） */
        const val CADENCE_MOCK = "cadenceMock"

        /** 角度与指南针侧外周传感器模拟（默认开） */
        const val ORIENTATION_MOCK = "orientationMock"

    }
}
