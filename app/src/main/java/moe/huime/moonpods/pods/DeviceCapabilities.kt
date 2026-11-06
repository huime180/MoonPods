package moe.huime.moonpods.pods

import android.content.Context
import moe.huime.moonpods.pods.moondrop.AncMode
import moe.huime.moonpods.pods.moondrop.MoondropModelRegistry

data class DeviceCapabilities(
    val adaptiveSupported: Boolean,
    val spatialAudioSupported: Boolean,
    val spatialSoundSwitchSupported: Boolean,
    val ancImplementation: AncImplementation,
    val dualDeviceSupported: Boolean = false,
    val customEqSupported: Boolean = false,
    val eqPresets: List<EqPresetInfo> = emptyList(),
    val customEqFrequencies: List<Int> = emptyList(),
    val customEqMaxPresets: Int = 0,
    val gameModeFeatureId: Int = GameModeFeature.LOW_LATENCY,
    val gameModeSupported: Boolean = false,
)

@kotlinx.serialization.Serializable
data class EqPresetInfo(
    val id: Int,
    val name: String,
    val modeType: Int = -1,
)

@kotlinx.serialization.Serializable
data class EqDevicePreset(
    val id: Int,
    val name: String,
    val selected: Boolean = false,
    val minValue: Int = -6,
    val maxValue: Int = 6,
    val frequencies: List<Int> = emptyList(),
    val gains: List<Int> = emptyList(),
)

object EqDefaults {
    val FREQUENCIES = listOf(62, 250, 1000, 4000, 8000, 16000)
}

fun detectDeviceCapabilities(
    @Suppress("UNUSED_PARAMETER") context: Context? = null,
    deviceName: String,
    @Suppress("UNUSED_PARAMETER") productId: String? = null,
): DeviceCapabilities {
    // OPPO 的 device_models.json 白名单已随 OPPO 支持一起移除：现在能力只从水月雨型号档案推导。
    // 空间音频/游戏模式在水月雨协议里没有对应命令，恒为 false（UI 据此隐藏对应卡片）。
    val model = runCatching { MoondropModelRegistry.match(deviceName) }.getOrNull()
    val modes = model?.anc?.modes.orEmpty()
    return DeviceCapabilities(
        adaptiveSupported = modes.any { it.id == AncMode.ADAPTIVE.id },
        spatialAudioSupported = false,
        spatialSoundSwitchSupported = false,
        ancImplementation = AncImplementation.STANDARD,
        dualDeviceSupported = model?.features?.dualConnection == true,
        gameModeSupported = false,
    )
}
