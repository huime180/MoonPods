package moe.huime.moonpods.noroot

/*
 * 电量解析与 Root 侧共用同一份实现（原本是两份逐字复制，容易漂移）。
 * 这里只保留别名与常量转发，调用点无需改动。
 */

typealias BatteryCodec = moe.huime.moonpods.pods.moondrop.MoondropBatteryCodec
typealias BatterySlot = moe.huime.moonpods.pods.moondrop.BatterySlot
typealias BatteryState = moe.huime.moonpods.pods.moondrop.BatteryState

const val BATTERY_UNKNOWN = moe.huime.moonpods.pods.moondrop.BATTERY_UNKNOWN
