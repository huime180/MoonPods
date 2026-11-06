package moe.huime.moonpods.pods

import moe.huime.moonpods.pods.moondrop.MoondropModel
import moe.huime.moonpods.pods.moondrop.MoondropModelRegistry

/**
 * 耳机品牌。
 *
 * 历史说明：本模块早期由 OPPO（OppoPods 上游）+ 水月雨的代码合并而来，用 [PodBrand] 在
 * 两个协议栈之间分流。3.0 起已彻底移除 OPPO 支持（协议层、型号表、UI 入口全部删除），
 * 现在只剩水月雨一套协议栈；保留枚举是为了让 Hook 层的分流结构保持稳定、便于以后再扩厂牌。
 */
enum class PodBrand {
    /** 水月雨 MOONDROP（GAIA over GATT/SPP）。 */
    MOONDROP,
}

/**
 * 型号识别入口。
 *
 * 识别逻辑集中在 `pods/`，Hook 层与 UI 层**不得**自己写型号表；权威来源是
 * `pods/moondrop/MoondropModelRegistry`（代码内校准 + 官方云端目录）。
 */
object PodCatalog {

    /**
     * 水月雨型号档案。
     *
     * 传入多个候选键（设备名、MAC），按顺序取第一个能匹配上的——
     * 部分机型名称里带用户自定义后缀，只有 MAC 前缀能稳定认出来。
     */
    fun moondropModelOf(vararg keys: String?): MoondropModel? =
        keys.asSequence()
            .filterNotNull()
            .filter { it.isNotBlank() }
            .firstNotNullOfOrNull { key ->
                runCatching { MoondropModelRegistry.match(key) }.getOrNull()
            }

    /**
     * 品牌判定：认得出是水月雨就返回 MOONDROP，否则 null（不是本模块支持的耳机）。
     *
     * OPPO 支持已移除，这里不再有宽匹配分支，避免「任意蓝牙耳机被误接管」。
     */
    fun brandOf(deviceName: String?, mac: String? = null): PodBrand? {
        if (moondropModelOf(deviceName, mac) != null) return PodBrand.MOONDROP
        // 兜底：云端目录还没加载（或被注入进程拿不到网络）时，型号表是空的，
        // 仅靠 match() 会把水月雨耳机判成「不认识」而直接放弃连接 —— 这里按名称兜底。
        val name = deviceName?.trim().orEmpty()
        if (name.isEmpty()) return null
        // 已写死协议层的机型（布丁）不依赖云端目录
        if (name.uppercase().contains("PUDDING")) return PodBrand.MOONDROP
        // 只有「名称以 MOONDROP 开头、且当前目录里没有这台」才视为新设备并触发一次拉取
        if (name.uppercase().startsWith("MOONDROP")) {
            moe.huime.moonpods.pods.moondrop.MoondropCatalog.refreshForUnknownDevice()
            return null
        }
        return null
    }

    /** 水月雨名称兜底判定（走型号档案，不做 `contains` 宽匹配）。 */
    fun isMoondropName(deviceName: String?): Boolean = moondropModelOf(deviceName) != null
}
