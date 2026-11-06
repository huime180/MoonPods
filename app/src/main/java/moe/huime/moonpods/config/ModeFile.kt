package moe.huime.moonpods.config

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 工作模式的跨进程权威来源：一个普通文件。
 *
 * 为什么不用 SharedPreferences：被注入进程读的是 Xposed 的 remote prefs 副本，
 * 在模块服务重新绑定前不会同步（实测读到旧模式），把模式判断放进连接路径时
 * 就会表现为「切了模式却连不上 / 重连不接管」。
 *
 * 文件放在应用的外部私有目录：应用进程可写，系统 uid（被注入进程）可读，
 * 且不经过任何同步机制。
 */
object ModeFile {

    private const val TAG = "MoonPods-ModeFile"
    private const val NAME = "work_mode.txt"

    private fun file(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, NAME)

    fun write(context: Context, mode: Int) {
        runCatching {
            file(context).writeText(mode.toString())
            Log.i(TAG, "work mode written: $mode")
        }.onFailure { Log.w(TAG, "write failed", it) }
    }

    /** 被注入进程使用：按路径直接读，例如 /sdcard/Android/data/<pkg>/files/work_mode.txt。 */
    fun readByPath(paths: List<File>): Int? {
        paths.forEach { f ->
            runCatching {
                if (f.exists()) f.readText().trim().toIntOrNull()?.let { return it }
            }
        }
        return null
    }

    /** 应用进程使用。 */
    fun read(context: Context): Int? = readByPath(listOf(file(context)))

    /** 候选路径（被注入进程里猜应用外部目录用）。 */
    fun candidatePaths(packageName: String): List<File> = listOf(
        File("/storage/emulated/0/Android/data/$packageName/files/$NAME"),
        File("/sdcard/Android/data/$packageName/files/$NAME"),
    )
}
