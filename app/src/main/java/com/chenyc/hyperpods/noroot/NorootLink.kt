package com.chenyc.hyperpods.noroot

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log

/**
 * 免 root 链路（应用进程直连）—— 骨架。
 *
 * 与 Root 链路（注入 com.android.bluetooth 的控制器）完全独立：
 * 自己持有 socket / GATT，自己读取状态并广播；不复用控制器的任何状态。
 *
 * 当前阶段只实现生命周期与状态广播，传输层（RFCOMM/SPP 与 BLE GATT）在下一步接入。
 */
object NorootLink {

    private const val TAG = "HyperPods-NorootLink"

    @Volatile private var device: BluetoothDevice? = null
    @Volatile var connected: Boolean = false
        private set

    fun connect(context: Context, target: BluetoothDevice) {
        device = target
        Log.i(TAG, "connect requested: ${target.address} (transport layer not wired yet)")
    }

    fun disconnect() {
        connected = false
        device = null
        Log.i(TAG, "disconnected")
    }

    fun isConnected(): Boolean = connected
}
