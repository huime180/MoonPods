package moe.huime.moonpods.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * 水月雨官方 App（com.moondroplab.moondrop.moondrop_app）的展示名与启动入口。
 *
 * 不写死「水月雨官方 app」这种文案：直接读系统里该包名的应用名（中文/英文随系统语言），
 * 读不到时回退到 MOONDROP —— 官方 App 改名前后的差异都不会出现在我们的界面上。
 */
object OfficialApp {

    const val PACKAGE = "com.moondroplab.moondrop.moondrop_app"
    const val FALLBACK_LABEL = "MOONDROP"

    /** 官方 App 的应用名；没安装时回退到 MOONDROP。 */
    fun label(context: Context): String = runCatching {
        val pm = context.packageManager
        val info = pm.getApplicationInfo(PACKAGE, 0)
        pm.getApplicationLabel(info).toString().ifBlank { FALLBACK_LABEL }
    }.getOrDefault(FALLBACK_LABEL)

    /** 打开官方 App 的启动 Intent；没装返回 null。 */
    fun launchIntent(context: Context): Intent? =
        runCatching { context.packageManager.getLaunchIntentForPackage(PACKAGE) }.getOrNull()
}
