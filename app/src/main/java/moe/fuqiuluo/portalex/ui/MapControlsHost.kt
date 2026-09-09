package moe.fuqiuluo.portalex.ui

/**
 * 三点展开栏（地图控件）宿主页面：主界面与路线编辑页共用同一套地图类型切换。
 * 未选中=普通图，选中=卫星图。
 */
interface MapControlsHost {

    /** 当前是否卫星图 */
    fun isSatellite(): Boolean

    /** 切换卫星图/普通图 */
    fun toggleSatellite()
}
