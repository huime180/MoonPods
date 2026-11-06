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
        // 进程可能刚被拉起：ConfigManager 缓存还没初始化时默认值是 Root，
        // 所以这里直接读一次 prefs，避免误判成 Root 而放弃连接。
        val prefs = runCatching {
            context.getSharedPreferences(moe.huime.moonpods.config.ConfigManager.PREFS_NAME, Context.MODE_PRIVATE)
        }.getOrNull()
        val mode = runCatching { prefs?.getInt("work_mode", -1) ?: -1 }.getOrDefault(-1)
        val workMode = if (mode >= 0) mode else runCatching {
            moe.huime.moonpods.config.ConfigManager.workMode()
        }.getOrDefault(moe.huime.moonpods.config.ConfigManager.WORK_MODE_ROOT)
        if (workMode != moe.huime.moonpods.config.ConfigManager.WORK_MODE_NOROOT) return
        // 只接管识别得出的水月雨机型；认不出来就不碰
        val name = runCatching { device.name ?: device.alias }.getOrNull()
        if (NorootBridge.matchModel(name) == null) return
        NorootBridge.connect(context, device)
    }
}
