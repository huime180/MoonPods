package com.chenyc.hyperpods.noroot

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import com.chenyc.hyperpods.pods.moondrop.MoondropFramer
import com.chenyc.hyperpods.pods.moondrop.MoondropGaia
import java.util.UUID
import kotlin.concurrent.thread

/**
 * 免 root 链路（应用进程直连）—— 第一步：RFCOMM/SPP 连接与收发。
 *
 * 与 Root 控制器**代码复制、状态独立**：自己持有 socket，自己解帧，
 * 不复用注入进程里的任何对象。
 */
object NorootLink {

    private const val TAG = "HyperPods-NorootLink"

    @Volatile private var socket: android.bluetooth.BluetoothSocket? = null
    @Volatile var connected: Boolean = false
        private set

    private val framer = MoondropFramer()

    @SuppressLint("MissingPermission")
    fun connect(context: Context, device: BluetoothDevice) {
        disconnect()
        thread(name = "noroot-link", isDaemon = true) {
            runCatching {
                val s = device.createRfcommSocketToServiceRecord(
                    UUID.fromString(MoondropGaia.SPP_UUID)
                )
                s.connect()
                socket = s
                connected = true
                Log.i(TAG, "RFCOMM connected: ${device.address}")
                readLoop()
            }.onFailure { Log.w(TAG, "RFCOMM connect failed", it) }
        }
    }

    private fun readLoop() {
        val s = socket ?: return
        val buf = ByteArray(1024)
        runCatching {
            val input = s.inputStream
            while (connected) {
                val n = input.read(buf)
                if (n <= 0) break
                val frames = framer.feed(buf.copyOf(n), burstEnd = false)
                frames.forEach { frame ->
                    Log.i(TAG, "rx frame: " + frame.joinToString(" ") { "%02X".format(it) })
                }
            }
        }.onFailure { Log.w(TAG, "read loop ended", it) }
        connected = false
        socket = null
    }

    fun write(payload: ByteArray) {
        val s = socket ?: return
        runCatching { s.outputStream.write(payload) }
            .onFailure { Log.w(TAG, "write failed", it) }
    }

    /** 连接后先要一次电量，验证链路是通的。 */
    fun requestBattery() = write(MoondropGaia.batteryGetAllV4())

    fun disconnect() {
        connected = false
        runCatching { socket?.close() }
        socket = null
        Log.i(TAG, "disconnected")
    }

    fun isConnected(): Boolean = connected
}
