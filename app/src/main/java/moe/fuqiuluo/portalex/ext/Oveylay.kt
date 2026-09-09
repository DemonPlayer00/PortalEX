package moe.fuqiuluo.portalex.ext

import android.content.Context
import android.provider.Settings

fun Context.drawOverOtherAppsEnabled(): Boolean {
    return Settings.canDrawOverlays(this)
}
