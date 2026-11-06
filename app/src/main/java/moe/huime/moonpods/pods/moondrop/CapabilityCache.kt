package moe.huime.moonpods.pods.moondrop

import android.util.Log
import moe.huime.moonpods.config.ConfigManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * 机型能力缓存：每台机型只需探测一次，之后直接用缓存。
 *
 * 探测一次要发多轮 GAIA 请求（分页能力 + 电池类型 + ANC 路径），重连时会让用户
 * 等上几百毫秒到数秒。这里把探测结果按「型号键」持久化到模块共享 prefs，
 * 连接时先套用缓存，随后再在后台探测一次以校正（固件升级可能改变能力）。
 */
object CapabilityCache {

    private const val TAG = "MoonPods-CapCache"
    private const val KEY = "caps_cache_v1"

    fun load(modelKey: String?): MoondropCapabilities? {
        if (modelKey.isNullOrBlank()) return null
        val raw = ConfigManager.capabilityCacheJson() ?: return null
        return runCatching {
            val root = JSONObject(raw)
            val o = root.optJSONObject(modelKey) ?: return null
            val features = o.optJSONArray("features")?.let { arr ->
                buildSet { for (i in 0 until arr.length()) add(arr.getInt(i)) }
            }.orEmpty()
            MoondropCapabilities(
                features = features,
                ancPath = o.optInt("ancPath", MoondropGaia.ANC_PATH_UNKNOWN),
                batteryTypes = o.optJSONArray("batteryTypes")?.let { arr ->
                    buildList { for (i in 0 until arr.length()) add(arr.getInt(i)) }
                }.orEmpty(),
                hasGain = o.optBoolean("gain"),
                hasLed = o.optBoolean("led"),
                hasSpatial = o.optBoolean("spatial"),
                hasHeadTracking = o.optBoolean("headTracking"),
                hasPromptTone = o.optBoolean("promptTone"),
                hasPromptVolume = o.optBoolean("promptVolume"),
                hasLhdc = o.optBoolean("lhdc"),
                hasDualConnection = o.optBoolean("dual"),
                hasGestures = o.optBoolean("gestures"),
                probed = true,
            )
        }.onFailure { Log.w(TAG, "load failed for $modelKey", it) }.getOrNull()
    }

    /** 免 root 侧的等价类型（两处类型不同，缓存格式统一，做一次映射）。 */
    fun loadPod(modelKey: String?): moe.huime.moonpods.noroot.PodCapabilities? =
        load(modelKey)?.let { c ->
            moe.huime.moonpods.noroot.PodCapabilities(
                features = c.features,
                ancPath = c.ancPath,
                batteryTypes = c.batteryTypes,
                hasGain = c.hasGain,
                hasLed = c.hasLed,
                hasSpatial = c.hasSpatial,
                hasHeadTracking = c.hasHeadTracking,
                hasPromptTone = c.hasPromptTone,
                hasPromptVolume = c.hasPromptVolume,
                hasLhdc = c.hasLhdc,
                hasDualConnection = c.hasDualConnection,
                hasGestures = c.hasGestures,
            )
        }

    fun savePod(modelKey: String?, caps: moe.huime.moonpods.noroot.PodCapabilities) {
        save(
            modelKey,
            MoondropCapabilities(
                features = caps.features,
                ancPath = caps.ancPath,
                batteryTypes = caps.batteryTypes,
                hasGain = caps.hasGain,
                hasLed = caps.hasLed,
                hasSpatial = caps.hasSpatial,
                hasHeadTracking = caps.hasHeadTracking,
                hasPromptTone = caps.hasPromptTone,
                hasPromptVolume = caps.hasPromptVolume,
                hasLhdc = caps.hasLhdc,
                hasDualConnection = caps.hasDualConnection,
                hasGestures = caps.hasGestures,
                probed = true,
            ),
        )
    }

    fun save(modelKey: String?, caps: MoondropCapabilities) {
        if (modelKey.isNullOrBlank() || !caps.probed) return
        runCatching {
            val raw = ConfigManager.capabilityCacheJson() ?: "{}"
            val root = JSONObject(raw)
            root.put(modelKey, JSONObject().apply {
                put("features", JSONArray(caps.features.sorted()))
                put("ancPath", caps.ancPath)
                put("batteryTypes", JSONArray(caps.batteryTypes))
                put("gain", caps.hasGain)
                put("led", caps.hasLed)
                put("spatial", caps.hasSpatial)
                put("headTracking", caps.hasHeadTracking)
                put("promptTone", caps.hasPromptTone)
                put("promptVolume", caps.hasPromptVolume)
                put("lhdc", caps.hasLhdc)
                put("dual", caps.hasDualConnection)
                put("gestures", caps.hasGestures)
            })
            ConfigManager.updateCapabilityCacheJson(root.toString())
            Log.i(TAG, "saved capabilities for $modelKey")
        }.onFailure { Log.w(TAG, "save failed for $modelKey", it) }
    }
}
