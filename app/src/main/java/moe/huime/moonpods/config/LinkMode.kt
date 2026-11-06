package moe.huime.moonpods.config

import android.content.Context

/**
 * 链路形态：进程内只判定一次，之后所有路由都用这个结果。
 *
 * 之前每个事件回调、每个广播拼装处都调用 `ConfigManager.workMode()`，
 * 而该值在注入进程里可能滞后 —— 同一个动作在不同位置得到不同结论，
 * 于是出现「有时能连、有时不能」的随机现象。
 *
 * 现在改为：**进程启动后首次使用时读一次权威文件并缓存**，
 * 切换模式会让应用重启（ModeSwitcher），缓存自然失效。
 */
object LinkMode {

    @Volatile private var cachedNoRoot: Boolean? = null

    fun reset() {
        cachedNoRoot = null
    }

    /** 当前是否免 root 链路。 */
    fun isNoRoot(context: Context): Boolean {
        cachedNoRoot?.let { return it }
        val fromFile = runCatching { ModeFile.read(context) }.getOrNull()
        val mode = fromFile ?: ConfigManager.workMode()
        val noRoot = mode == ConfigManager.WORK_MODE_NOROOT
        cachedNoRoot = noRoot
        return noRoot
    }

    /**
     * 控制命令的投递目标：
     *   免 root → 本应用进程（NorootBridge 接收）
     *   Root   → com.android.bluetooth（注入的控制器接收）
     */
    fun controlTarget(context: Context): String =
        if (isNoRoot(context)) context.packageName else "com.android.bluetooth"
}
