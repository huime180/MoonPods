package moe.huime.moonpods.pods

import moe.huime.moonpods.config.ConfigManager

/**
 * 工作模式的跨进程同步点。
 *
 * 被注入进程（com.android.bluetooth 等）通过 Xposed 的 remote preferences 读配置，
 * 该副本在模块服务未重新绑定时可能落后于应用侧 —— 实测出现过「应用里已切到 Root，
 * 蓝牙进程仍读到免 root」导致永不连接。应用侧切换模式时会给这些进程发一条显式广播，
 * 这里保存最新值作为权威覆盖。
 */
object WorkModeBridge {
    const val UNSET = -1

    @Volatile
    var override: Int = UNSET

    fun mode(): Int {
        if (override != UNSET) return override
        // 优先读文件：remote prefs 会滞后，文件是实时权威值
        moe.huime.moonpods.config.ModeFile.readByPath(
            moe.huime.moonpods.config.ModeFile.candidatePaths("moe.huime.moonpods")
        )?.let { return it }
        // 被注入进程的 remote prefs 可能滞后：每次判断前重读一次
        ConfigManager.refreshNow()
        return ConfigManager.workMode()
    }

    fun isRoot(): Boolean = mode() == ConfigManager.WORK_MODE_ROOT

    fun isNoRoot(): Boolean = mode() == ConfigManager.WORK_MODE_NOROOT
}
