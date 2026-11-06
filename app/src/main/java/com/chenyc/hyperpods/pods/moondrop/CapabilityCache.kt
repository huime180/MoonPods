package com.chenyc.hyperpods.pods.moondrop

import android.util.Log
import com.chenyc.hyperpods.config.ConfigManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * 机型能力缓存：每台机型只需完整探测一次。
 *
 * 探测要发多轮 GAIA 请求（分页能力 + 电池类型 + ANC 路径），是重连慢的主要来源。
 * 这里把结果按型号键存进模块共享 prefs；连接时先套用缓存，再后台探测校正。
 */
object CapabilityCache {

    private const val TAG = "HyperPods-CapCache"

    fun load(modelKey: String?): MoondropCapabilities? {
        if (modelKey.isNullOrBlank()) return null
        val raw = ConfigManager.capabilityCacheJson() ?: return null
        return runCatching {
            val o = JSONObject(raw).optJSONObject(modelKey) ?: return null
            MoondropCapabilities(
                features = o.optJSONArray("features")?.let { a ->
                    buildSet { for (i in 0 until a.length()) add(a.getInt(i)) }
                }.orEmpty(),
                ancPath = o.optInt("ancPath", MoondropGaia.ANC_PATH_UNKNOWN),
                batteryTypes = o.optJSONArray("batteryTypes")?.let { a ->
                    buildList { for (i in 0 until a.length()) add(a.getInt(i)) }
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

    fun save(modelKey: String?, caps: MoondropCapabilities) {
        if (modelKey.isNullOrBlank() || !caps.probed) return
        runCatching {
            val root = JSONObject(ConfigManager.capabilityCacheJson() ?: "{}")
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
