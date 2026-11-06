package moe.huime.moonpods.noroot

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 免 root 模式的进程入口：耳机（A2DP）连上时把应用进程拉起来并建链。
 *
 * 为什么用系统广播而不是前台服务：A2DP 连接状态广播在 Android 8+ 的隐式广播豁免清单里，
 * 静态注册也能收到，不需要常驻通知、不需要前台服务 —— 也符合「不要前台监听」的要求。
 */
class NorootConnectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED) return
        val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
        val device = runCatching {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        }.getOrNull() ?: return
        // 断开/关闭：撤掉应用自己那条通知（进程可能已被回收，靠静态广播兜底）
        if (state == BluetoothProfile.STATE_DISCONNECTED || state == BluetoothProfile.STATE_DISCONNECTING) {
            // 无条件撤销：此处按型号匹配失败会把「已断开」的通知留在栏里
            moe.huime.moonpods.noroot.NorootNotification.cancelImmediate(context.applicationContext)
            return
        }
        if (state != BluetoothProfile.STATE_CONNECTED) return
        // 链路形态只判定一次（LinkMode 内部缓存），不再每次事件都读配置
        if (!moe.huime.moonpods.config.LinkMode.isNoRoot(context)) return
        // 只接管识别得出的水月雨机型；认不出来就不碰
        val name = runCatching { device.name ?: device.alias }.getOrNull()
        if (NorootBridge.matchModel(name) == null) return
        // 弹窗跟的是「蓝牙连上耳机」，先弹；模块接管是后续动作
        NorootBridge.showConnectPopup()
        NorootBridge.connect(context, device)
    }
}
