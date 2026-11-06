package moe.huime.moonpods.pods

/**
 * 从已删除的 `Packets.kt` 里抽出来的**通用数据/UI 类型**（OPPO 私有包结构、解析器、命令码都随
 * OPPO 支持一起删了，这里只保留 UI 与能力模型还需要的枚举/常量）。
 */

/**
 * ANC mode values for OPPO earphones (used in SET commands).
 * OPPO payload is 01 01 [value], except Adaptive which uses 01 01 00 08.
 */
object AncMode {
    const val OFF = 0x01
    const val NOISE_CANCELLATION = 0x02
    // ANC intensity payloads from OPPO captures: Smart/Light/Medium/Deep.
    const val NOISE_CANCELLATION_SMART = 0x80
    const val NOISE_CANCELLATION_LIGHT = 0x40
    const val NOISE_CANCELLATION_MEDIUM = 0x20
    const val NOISE_CANCELLATION_DEEP = 0x10
    const val TRANSPARENCY = 0x04
    const val ADAPTIVE_HIGH = 0x00
    const val ADAPTIVE_LOW = 0x08
}

/** ANC protocol implementation variant. */
enum class AncImplementation {
    /** Standard: 0x01=Off, 0x02=NC. */
    STANDARD,
    /** Compatible: 0x01=NC, 0x02=Off. Used by some older devices. */
    COMPATIBLE;
}

/** Battery component index in response payload. */
object BatteryComponent {
    const val LEFT = 1
    const val RIGHT = 2
    const val CASE = 3
}

/** Wearing-detection component/status values in active reports. */
object WearComponent {
    const val LEFT = 1
    const val RIGHT = 2
    const val CASE = 3
}

enum class WearState(val value: Int) {
    DISCONNECTED(0x00),
    IN_CASE(0x04),
    REMOVED(0x05),
    WEARING(0x07);

    companion object {
        fun fromValue(value: Int): WearState? = entries.firstOrNull { it.value == value }
    }
}

data class WearStatus(
    val left: WearState? = null,
    val right: WearState? = null,
    val case: WearState? = null
)

/** Feature IDs used by the switch-feature command/query. */
object GameModeFeature {
    const val LOW_LATENCY = 0x06
    const val DUAL_DEVICE_CONNECTION = 0x11
    const val FREE4_SPATIAL_SOUND = 0x1B
    const val MAIN = 0x28
}

/** Spatial audio mode values. */
object SpatialAudioMode {
    const val OFF = 0x00
    const val FIXED = 0x01
    const val HEAD_TRACKING = 0x02
}
