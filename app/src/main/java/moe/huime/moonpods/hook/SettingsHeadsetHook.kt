package moe.huime.moonpods.hook

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * 小米设置里的耳机页（com.android.settings.bluetooth.MiuiHeadsetActivity）适配。
 *
 * 参考 Konakonai/Edge2Pods 的做法：页面本身是 MIUI 给自家耳机写的（三路电量、
 * 降噪三档、通透档位等），对我们的耳机并不完全成立，所以在页面创建/恢复时做视图级适配：
 *
 *   · 隐藏本机型没有的电量列（只有 L/R、没有充电盒时隐藏「耳机盒」）；
 *   · 把降噪档位文案换成我们档位表里的说法（基础/抗风噪/自适应），
 *     避免出现「深度/轻度」这种与设备不符的标签；
 *   · 只对本模块接管的设备生效（按 MIUI_HEADSET_SUPPORT 里的设备 ID 判定）。
 *
 * 真正的控制链路不在这里：ANC/电量走蓝牙进程里的 BluetoothUpstreamHeadsetHook。
 */
class SettingsHeadsetHook : HookContext() {

    private val TAG = "MoonPods-Settings"

    /** 本页面对应的设备是否为我们的（MIUI_HEADSET_SUPPORT 以我们的设备 ID 开头）。 */
    private val managedPages = java.util.Collections.newSetFromMap(
        java.util.WeakHashMap<Activity, Boolean>()
    )

    /** 与档位表对齐的降噪文案：view id 名 → 新文案。 */
    private val ancLabels = mapOf(
        "ancLowText" to "降噪",
        "ancMediumText" to "抗风噪",
        "ancHighText" to "自适应",
    )

    /** 本机型不成立的视图（没有充电盒时隐藏整列）。 */
    private val hiddenViewIds = listOf("batterybox", "diverbox")

    override fun onHook() {
        // 免 root 模式：系统应用保持原样，不做任何 hook（重启作用域后即恢复）
        if (moe.huime.moonpods.pods.WorkModeBridge.isNoRoot()) {
            Log.i(TAG, "no-root mode: skip hooks")
            return
        }
        runCatching {
            val activityClass = findClass("com.android.settings.bluetooth.MiuiHeadsetActivity")
            hookBefore(activityClass.getDeclaredMethod("onCreate", android.os.Bundle::class.java)) {
                val activity = instance as? Activity ?: return@hookBefore
                val support = activity.intent?.getStringExtra("MIUI_HEADSET_SUPPORT").orEmpty()
                if (support.startsWith(DEVICE_ID + ",")) managedPages.add(activity)
            }
        }.onFailure { Log.w(TAG, "MiuiHeadsetActivity hook skipped", it) }

        runCatching {
            val fragment = findClass("com.android.settings.bluetooth.MiuiHeadsetFragment")
            hookAfter(fragment.getDeclaredMethod("onResume")) {
                val activity = activityOf(instance as? Any)
                if (activity != null && managedPages.contains(activity)) {
                    applyAdaptations(activity.window?.decorView)
                }
            }
        }.onFailure { Log.w(TAG, "MiuiHeadsetFragment hook skipped", it) }
    }

    /** 隐藏本机型没有的视图；找不到就跳过（不同 ROM 版本 id 可能不同）。 */
    private fun applyAdaptations(root: View?) {
        if (root == null) return
        val res = root.resources
        hiddenViewIds.forEach { name ->
            val id = res.getIdentifier(name, "id", "com.android.settings")
            if (id != 0) root.findViewById<View>(id)?.visibility = View.GONE
        }
        // 单电量的机型：把「右」改成「电池」之类的重标在这里可以按需扩展；
        // 我们的机型有左右两路，因此不动文案。
        (root as? ViewGroup)?.let { relabelRecursive(it) }
    }

    private fun relabelRecursive(group: ViewGroup) {
        for (i in 0 until group.childCount) {
            when (val child = group.getChildAt(i)) {
                is ViewGroup -> relabelRecursive(child)
                is TextView -> {
                    val name = entryName(child)
                    val label = name?.let { ancLabels[it] }
                    if (label != null) child.text = label
                }
            }
        }
    }

    private fun entryName(view: View): String? {
        val id = view.id
        if (id == View.NO_ID) return null
        return runCatching {
            view.resources.getResourceEntryName(id)
        }.getOrNull()
    }

    private fun activityOf(any: Any?): Activity? {
        var ctx = when (any) {
            is Activity -> return any
            is View -> any.context
            is Context -> any
            else -> null
        }
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    companion object {
        /** 与 fakeDeviceId 保持一致的设备 ID 前缀（MIUI 用它在页面里认型号）。 */
        private const val DEVICE_ID = "01010607"
    }
}
