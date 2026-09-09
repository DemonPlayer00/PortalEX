package moe.fuqiuluo.portalex.ext

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

fun Context.drawOverOtherAppsEnabled(): Boolean {
    return Settings.canDrawOverlays(this)
}
