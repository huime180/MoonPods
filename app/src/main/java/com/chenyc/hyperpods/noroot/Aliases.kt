package com.chenyc.hyperpods.noroot

/*
 * 免 root 链路复用 Root 侧的协议与型号数据，只保留自己的连接实现。
 * 这里用别名把两侧名字对齐，避免再复制一份实现（3.0.0 曾因此产生两份代码）。
 */

typealias BatteryCodec = com.chenyc.hyperpods.pods.moondrop.MoondropBatteryCodec
typealias BatterySlot = com.chenyc.hyperpods.pods.moondrop.BatterySlot
typealias BatteryState = com.chenyc.hyperpods.pods.moondrop.BatteryState
const val BATTERY_UNKNOWN = com.chenyc.hyperpods.pods.moondrop.BATTERY_UNKNOWN

typealias SrcProtocol = com.chenyc.hyperpods.pods.moondrop.MoondropSrcProtocol

typealias MoondropModel = com.chenyc.hyperpods.pods.moondrop.MoondropModel
typealias PodTransport = com.chenyc.hyperpods.pods.moondrop.PodTransport
typealias AncMode = com.chenyc.hyperpods.pods.moondrop.AncMode
typealias AncPathKind = com.chenyc.hyperpods.pods.moondrop.AncPathKind

/** 2.1.0 的型号档案在 MoondropModelRegistry 里，这里做同名转发。 */
object MoondropModels {
    val FALLBACK: MoondropModel get() = com.chenyc.hyperpods.pods.moondrop.MoondropModelRegistry.FALLBACK

    fun match(deviceName: String?): MoondropModel? =
        com.chenyc.hyperpods.pods.moondrop.MoondropModelRegistry.match(deviceName)
}
