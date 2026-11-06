package moe.huime.moonpods.config

/**
 * 焦点通知开关的跨进程同步点（与 WorkModeBridge 同构）。
 *
 * 被注入进程读 remote prefs 会滞后，曾出现「应用里已关闭、小米通知照样出现」。
 * 应用侧切换开关时发显式广播，这里保存最新值作为权威覆盖。
 */
object FocusNotificationBridge {
    const val UNSET = -1

    @Volatile
    var override: Int = UNSET

    fun enabled(): Boolean =
        if (override != UNSET) override == 1 else ConfigManager.focusNotificationFresh()

    fun set(enabled: Boolean) {
        override = if (enabled) 1 else 0
    }
}
