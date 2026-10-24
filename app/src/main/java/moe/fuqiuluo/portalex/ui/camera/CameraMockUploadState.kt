package moe.fuqiuluo.portalex.ui.camera

import android.content.Context
import moe.fuqiuluo.portalex.ext.sharedPrefs

/**
 * cammock 上传状态（按视频 id）。
 *
 * 上传脚本可能因为长视频拷贝超时/中断而失败；失败后保留 failed 集合，
 * 视频行据此显示“重试上传”。成功上传后清掉 failed 并记入 uploaded。
 */
object CameraMockUploadState {
    private const val KEY_UPLOADED = "cameraMockUploadedIds"
    private const val KEY_FAILED = "cameraMockUploadFailedIds"

    fun uploadedIds(context: Context): Set<String> =
        context.sharedPrefs.getStringSet(KEY_UPLOADED, emptySet()) ?: emptySet()

    fun failedIds(context: Context): Set<String> =
        context.sharedPrefs.getStringSet(KEY_FAILED, emptySet()) ?: emptySet()

    /**
     * 未上传的视频显示重试入口：包括失败记录，以及升级前没有上传状态的旧视频。
     * 上传成功后 id 会进入 uploaded，按钮自动消失。
     */
    fun needsRetry(context: Context, videoId: String): Boolean =
        videoId !in uploadedIds(context)

    fun markUploaded(context: Context, videoIds: Collection<String>) {
        val uploaded = uploadedIds(context).toMutableSet().apply { addAll(videoIds) }
        val failed = failedIds(context).toMutableSet().apply { removeAll(videoIds) }
        context.sharedPrefs.edit()
            .putStringSet(KEY_UPLOADED, uploaded)
            .putStringSet(KEY_FAILED, failed)
            .apply()
    }

    fun markFailed(context: Context, videoIds: Collection<String>) {
        val failed = failedIds(context).toMutableSet().apply {
            addAll(videoIds)
            removeAll(uploadedIds(context))
        }
        context.sharedPrefs.edit().putStringSet(KEY_FAILED, failed).apply()
    }
}
