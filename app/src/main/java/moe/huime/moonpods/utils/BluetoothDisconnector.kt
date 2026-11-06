package moe.huime.moonpods.utils

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log

/**
 * 断开**系统蓝牙连接**（A2DP），而不是只断开模块自己那条 GAIA 链路。
 *
 * 通知与模块页面统一走这里：用户点「断开连接」的语义是让耳机从手机断开，
 * 不是仅关闭模块的协议通道 —— 后者会留下「系统显示已连接、模块却显示未连接」的分裂状态。
 */
object BluetoothDisconnector {

    private const val TAG = "MoonPods-BtDisconnect"

    fun disconnect(context: Context, address: String?) {
        if (address.isNullOrBlank()) return
        runCatching {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
            val device = adapter.getRemoteDevice(address)
            adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    runCatching {
                        // disconnect(BluetoothDevice) 是 @SystemApi，SDK 里不可见：
                        // 走反射调用同一个 Binder 方法，避免依赖隐藏 API 编译期符号。
                        proxy.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
                            .invoke(proxy, device)
                        Log.i(TAG, "A2DP disconnect issued: $address")
                    }.onFailure { Log.w(TAG, "A2DP disconnect failed", it) }
                    runCatching { adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy) }
                }

                override fun onServiceDisconnected(profile: Int) = Unit
            }, BluetoothProfile.A2DP)
        }.onFailure { Log.w(TAG, "disconnect failed: $address", it) }
    }
}
