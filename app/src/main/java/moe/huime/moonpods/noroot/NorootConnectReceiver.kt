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
        if (state != BluetoothProfile.STATE_CONNECTED) return
        // Root 模式下由被注入的蓝牙进程接管，这里不能抢
        if (moe.huime.moonpods.config.ConfigManager.workMode() != moe.huime.moonpods.config.ConfigManager.WORK_MODE_NOROOT) return
        // 只接管识别得出的水月雨机型；认不出来就不碰
        val name = runCatching { device.name ?: device.alias }.getOrNull()
        if (NorootBridge.matchModel(name) == null) return
        NorootBridge.connect(context, device)
    }
}
