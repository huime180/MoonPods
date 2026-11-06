package moe.huime.moonpods.utils

import moe.huime.moonpods.config.EarphonePref
import moe.huime.moonpods.utils.miuiStrongToast.data.BatteryParams

/**
 * 设备卡片 / 弹窗 / 通知共用的机型图选择。
 *
 * 规则（按「当前实际在线的耳」决定展示哪张图）：
 *   · 只有左耳在线（左有电量、右无数据）  → 左耳图（缺失回落整机图）
 *   · 只有右耳在线                        → 右耳图（缺失回落整机图）
 *   · 双耳都在线 / 都无法判断              → 整机图
 *
 * 判断依据是**电量读数是否存在**：主耳单独使用时固件只回报在线的耳，
 * 另一侧读数为 -1（离线）。这样不需要额外探测，也不用猜穿戴语义。
 */
object PodImageSelector {

    fun choose(pref: EarphonePref?, battery: BatteryParams?): String? {
        if (pref == null) return null
        val box = pref.boxImagePath
        val leftOnline = (battery?.left?.battery ?: -1) >= 0
        val rightOnline = (battery?.right?.battery ?: -1) >= 0
        val left = pref.leftImagePath
        val right = pref.rightImagePath
        return when {
            leftOnline && !rightOnline -> left ?: box
            rightOnline && !leftOnline -> right ?: box
            else -> box ?: left ?: right
        }
    }
}
