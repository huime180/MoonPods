package moe.huime.moonpods.noroot

import android.bluetooth.BluetoothDevice
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 免 root 直连桥。
 *
 * 与「Hook 模式」互补：Hook 模式由被注入的蓝牙进程里的 `pods/moondrop/MoondropController`
 * 接管耳机；免 root 模式由**应用进程自己**建 GAIA 链路（BLE / RFCOMM），不需要 LSPosed、
 * 不需要任何系统权限。两者共用同一套协议语义，差别只在“谁持有蓝牙连接”。
 *
 * 这里刻意**不起前台服务**：链路生命周期跟应用进程一致，进程被回收就断开，
 * 由系统 A2DP 广播（[NorootConnectReceiver]）在耳机重新连上时再把进程拉起来。
 */
object NorootBridge : PodListener {

    private val _snapshot = MutableStateFlow<PodSnapshot?>(null)
    /** 当前耳机快照（免 root 模式下由本进程维护） */
    val snapshot: StateFlow<PodSnapshot?> = _snapshot

    private val _lastEvent = MutableStateFlow<PodEvent?>(null)
    /** 最近一次状态事件（UI / 通知层按需消费） */
    val lastEvent: StateFlow<PodEvent?> = _lastEvent

    fun init(context: Context) {
        if (MoondropLink.isInitialized()) return
        MoondropLink.init(context.applicationContext, this)
    }

    /** 免 root 连接：先 init 再建链，调用方可以是广播接收器或 UI。 */
    fun connect(context: Context, device: BluetoothDevice) {
        init(context)
        MoondropLink.connect(device)
    }

    fun disconnect() {
        if (!MoondropLink.isInitialized()) return
        MoondropLink.disconnect()
    }

    fun isInitialized(): Boolean = MoondropLink.isInitialized()

    /** 这台设备是不是水月雨（走官方云端目录识别）。 */
    fun matchModel(name: String?) = MoondropLink.sniffModel(name)

    override fun onEvent(event: PodEvent) {
        _lastEvent.value = event
        _snapshot.value = when (event) {
            is PodEvent.Connected -> event.snapshot
            PodEvent.Disconnected -> null
            else -> runCatching { MoondropLink.snapshot() }.getOrNull()
        }
    }
}
