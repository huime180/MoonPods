package moe.huime.moonpods.pods

/**
 * UI 层的降噪模式枚举。
 *
 * 历史上它定义在 `pods/Packets.kt`（OPPO 协议文件）里，OPPO 支持移除后仍被 UI 使用，
 * 因此单独留在 `pods/` 下。水月雨侧真正的档位列表来自设备探测（见 `MoondropAncSwitch` 的
 * `ancIds`），这个枚举只用于原生卡片的“三态”映射与兜底控件。
 */
enum class NoiseControlMode {
    OFF,
    NOISE_CANCELLATION,
    NOISE_CANCELLATION_SMART,
    NOISE_CANCELLATION_LIGHT,
    NOISE_CANCELLATION_MEDIUM,
    NOISE_CANCELLATION_DEEP,
    ADAPTIVE,
    TRANSPARENCY
}

fun NoiseControlMode.isNoiseCancellation(): Boolean {
    return when (this) {
        NoiseControlMode.NOISE_CANCELLATION,
        NoiseControlMode.NOISE_CANCELLATION_SMART,
        NoiseControlMode.NOISE_CANCELLATION_LIGHT,
        NoiseControlMode.NOISE_CANCELLATION_MEDIUM,
        NoiseControlMode.NOISE_CANCELLATION_DEEP -> true
        else -> false
    }
}
