package moe.huime.moonpods.noroot

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import moe.huime.moonpods.BuildConfig
import moe.huime.moonpods.utils.miuiStrongToast.data.BatteryParams
import moe.huime.moonpods.utils.miuiStrongToast.data.MoonPodsAction
import moe.huime.moonpods.utils.miuiStrongToast.data.PodParams

/**
 * 免 root 直连桥（应用进程内）。
 *
 * Root 模式由被注入的蓝牙进程里的 `pods/moondrop/MoondropController` 接管耳机；
 * 免 root 模式由**应用进程自己**建 GAIA 链路，不需要 LSPosed。
 *
 * 关键点：对 UI **保持与 Root 模式完全相同的广播契约** —— 控件复用同一套，
 * 只是这里的控制器在应用进程、事件也用广播回灌给自己（setPackage(APPLICATION_ID)）。
 */
object NorootBridge : PodListener {

    private const val TAG = "MoonPods-Noroot"
    private var appContext: Context? = null
    private var receiverRegistered = false

    private val _snapshot = MutableStateFlow<PodSnapshot?>(null)
    val snapshot: StateFlow<PodSnapshot?> = _snapshot

    private val _lastEvent = MutableStateFlow<PodEvent?>(null)
    val lastEvent: StateFlow<PodEvent?> = _lastEvent

    fun init(context: Context) {
        appContext = context.applicationContext
        if (!MoondropLink.isInitialized()) {
            MoondropLink.init(context.applicationContext, this)
        }
        registerControlReceiver(context.applicationContext)
        // 通道先建出来（Android 13+ 没通道连设置页都看不到开关），已连接则立刻补一条状态
        NorootNotification.channel(context.applicationContext)
        val snap = runCatching {
            if (MoondropLink.isInitialized()) MoondropLink.snapshot() else null
        }.getOrNull()
        if (snap?.connected == true) {
            _snapshot.value = snap
            broadcastState(snap)
        }
    }

    fun connect(context: Context, device: BluetoothDevice) {
        init(context)
        lastDevice = device
        MoondropLink.connect(device)
    }

    fun disconnect() {
        if (MoondropLink.isInitialized()) MoondropLink.disconnect()
    }

    fun isInitialized(): Boolean = MoondropLink.isInitialized()

    fun matchModel(name: String?) = MoondropLink.sniffModel(name)

    /**
     * 收 UI/通知发来的控制命令，翻译成 GAIA 调用。
     *
     * 只在本进程注册：Root 模式里这些 action 是发给 com.android.bluetooth 的，
     * 免 root 模式里同一个 action 直接落到这里，UI 不需要区分模式。
     */
    private fun registerControlReceiver(ctx: Context) {
        if (receiverRegistered) return
        receiverRegistered = true
        val filter = IntentFilter().apply {
            listOf(
                MoonPodsAction.ANC_SELECT,
                MoonPodsAction.GAIN_SELECT,
                MoonPodsAction.LED_SELECT,
                MoonPodsAction.PROMPT_TONE_SELECT,
                MoonPodsAction.PROMPT_VOLUME_SELECT,
                MoonPodsAction.LHDC_SELECT,
                MoonPodsAction.DUAL_CONNECTION_SELECT,
                MoonPodsAction.GESTURE_SELECT,
                MoonPodsAction.ACTION_REFRESH_STATUS,
                MoonPodsAction.ACTION_CONNECT_POD_REQUEST,
                MoonPodsAction.ACTION_DISCONNECT_POD_REQUEST,
                MoonPodsAction.NOROOT_DISCONNECT,
                MoonPodsAction.UI_INIT,
                MoonPodsAction.REQUEST_BATTERY,
                MoonPodsAction.REQUEST_CAPABILITIES,
                MoonPodsAction.SPATIAL_AUDIO_SELECT,
                MoonPodsAction.HEAD_TRACKING_SELECT,
                MoonPodsAction.REQUEST_GESTURE,
            ).forEach { addAction(it) }
        }
        ctx.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                runCatching {
                    when (action) {
                        MoonPodsAction.ANC_SELECT ->
                            MoondropLink.setAnc(intent.getIntExtra(MoonPodsAction.EXTRA_STATUS, 0))
                        MoonPodsAction.GAIN_SELECT ->
                            MoondropLink.setGain(intent.getIntExtra(MoonPodsAction.EXTRA_STATUS, 0))
                        MoonPodsAction.LED_SELECT ->
                            MoondropLink.setLed(intent.getBooleanExtra(MoonPodsAction.EXTRA_ENABLED, false))
                        MoonPodsAction.PROMPT_TONE_SELECT ->
                            MoondropLink.setPromptTone(intent.getBooleanExtra(MoonPodsAction.EXTRA_ENABLED, false))
                        MoonPodsAction.PROMPT_VOLUME_SELECT ->
                            MoondropLink.setPromptVolumeRaw(
                                intent.getIntExtra(MoonPodsAction.EXTRA_PROMPT_VOLUME_RAW, -1)
                            )
                        MoonPodsAction.LHDC_SELECT ->
                            MoondropLink.setLhdc(intent.getBooleanExtra(MoonPodsAction.EXTRA_ENABLED, false))
                        MoonPodsAction.DUAL_CONNECTION_SELECT ->
                            MoondropLink.setDualConnection(intent.getBooleanExtra(MoonPodsAction.EXTRA_ENABLED, false))
                        MoonPodsAction.SPATIAL_AUDIO_SELECT ->
                            MoondropLink.setSpatial(intent.getBooleanExtra(MoonPodsAction.EXTRA_ENABLED, false))
                        MoonPodsAction.HEAD_TRACKING_SELECT ->
                            MoondropLink.setHeadTracking(intent.getBooleanExtra(MoonPodsAction.EXTRA_ENABLED, false))
                        MoonPodsAction.UI_INIT,
                        MoonPodsAction.REQUEST_CAPABILITIES -> MoondropLink.refreshAll()
                        MoonPodsAction.REQUEST_BATTERY -> MoondropLink.requestBatteryRefresh()
                        MoonPodsAction.REQUEST_GESTURE -> Unit
                        MoonPodsAction.ACTION_REFRESH_STATUS -> MoondropLink.refreshAll()
                        MoonPodsAction.ACTION_CONNECT_POD_REQUEST -> {
                            val device = intent.getParcelableExtra("device", BluetoothDevice::class.java)
                            if (device != null) MoondropLink.connect(device)
                        }
                        MoonPodsAction.ACTION_DISCONNECT_POD_REQUEST,
                        MoonPodsAction.NOROOT_DISCONNECT -> MoondropLink.disconnect()
                    }
                }.onFailure { Log.w(TAG, "control $action failed", it) }
            }
        }, filter, Context.RECEIVER_EXPORTED)
    }

    /** 上一次已广播的快照：内容不变就不再重复发广播（Frame 事件量大，必须去重）。 */
    @Volatile private var lastBroadcast: PodSnapshot? = null

    override fun onEvent(event: PodEvent) {
        Log.i(TAG, "event=${event.javaClass.simpleName} connected=${runCatching { MoondropLink.snapshot().connected }.getOrElse { false }}")
        _lastEvent.value = event
        // 逐帧的调试事件不参与状态广播：否则每来一包就发三条广播，界面会卡死
        if (event is PodEvent.Frame) return
        val snap = when (event) {
            is PodEvent.Connected -> event.snapshot
            PodEvent.Disconnected -> null
            else -> runCatching { MoondropLink.snapshot() }.getOrNull()
        }
        _snapshot.value = snap
        if (snap == null) {
            if (lastBroadcast != null) {
                lastBroadcast = null
                broadcastDisconnected()
            }
            return
        }
        if (snap == lastBroadcast) {
            Log.i(TAG, "broadcast skipped (unchanged)")
            return
        }
        lastBroadcast = snap
        Log.i(TAG, "broadcast state: anc=${snap.ancIndex}/${snap.ancModes.size} gain=${snap.gainIndex} led=${snap.ledOn} lhdc=${snap.lhdcOn} dual=${snap.dualConnectionOn}")
        broadcastState(snap)
        if (event is PodEvent.Connected) {
            // 连上后把全部功能读一遍：快照里 gain/led/prompt/lhdc/dual 读到之前是 null，
            // 不读的话界面就只有降噪一个控件。
            runCatching { MoondropLink.refreshAll() }
            // refreshAll 是异步读：等它把增益/指示灯/提示音/LHDC/双设备读回来之后，
            // 再补发一次完整状态，否则界面只有降噪一项、通知也只有电量。
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                val latest = runCatching { MoondropLink.snapshot() }.getOrNull()
                if (latest?.connected == true) {
                    lastBroadcast = null
                    broadcastState(latest)
                }
            }, 2000L)
            maybeShowPopup()
        }
    }

    /**
     * 把快照翻译成与 Root 模式**完全相同**的广播契约。
     *
     * 逐项对齐 MoondropController 的 publish*：
     *   action 名：PODS_CONNECTED / PODS_DISCONNECTED / BATTERY_CHANGED / ANC_CHANGED /
     *              GAIN_CHANGED / LED_CHANGED / PROMPT_TONE_CHANGED /
     *              PROMPT_VOLUME_CHANGED / LHDC_CHANGED / DUAL_CONNECTION_CHANGED
     *   extras  ：EXTRA_MAC / EXTRA_DEVICE_NAME / EXTRA_DEVICE + 各功能字段
     * 只发这一套：Root 与免 root 互斥，UI 不需要同时兼容两套事件源。
     */
    private fun broadcastState(snap: PodSnapshot) {
        val ctx = appContext ?: return
        val device = lastDevice
        fun send(action: String, fill: (Intent) -> Unit) {
            run {
                ctx.sendBroadcast(Intent(action).apply {
                    putExtra(MoonPodsAction.EXTRA_MAC, snap.deviceAddress)
                    putExtra(MoonPodsAction.EXTRA_DEVICE_NAME, snap.deviceName)
                    putExtra(MoonPodsAction.EXTRA_DEVICE, device)
                    putExtra("address", snap.deviceAddress)
                    putExtra("device_name", snap.deviceName)
                    fill(this)
                    setPackage(BuildConfig.APPLICATION_ID)
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                })
            }
        }

        send(MoonPodsAction.PODS_CONNECTED) {}

        send(MoonPodsAction.BATTERY_CHANGED) { i ->
            i.putExtra("status", BatteryParams(
                left = PodParams(snap.battery.left, snap.battery.leftCharging, snap.battery.leftKnown),
                right = PodParams(snap.battery.right, snap.battery.rightCharging, snap.battery.rightKnown),
                case = PodParams(snap.battery.case, snap.battery.caseCharging, snap.battery.caseKnown),
            ))
        }

        // 能力位：UI 靠它决定显示哪些控件（增益/指示灯/提示音/LHDC/双设备/手势）。
        // 没有这条广播时界面就只剩降噪一项。
        val caps = snap.capabilities
        if (caps.probed) {
            val bundle = android.os.Bundle().apply {
                putBoolean("probed", caps.probed)
                putBoolean("hasGain", caps.hasGain)
                putBoolean("hasLed", caps.hasLed)
                putBoolean("hasPromptTone", caps.hasPromptTone)
                putBoolean("hasPromptVolume", caps.hasPromptVolume)
                putBoolean("hasLhdc", caps.hasLhdc)
                putBoolean("hasDualConnection", caps.hasDualConnection)
                putBoolean("hasGestures", caps.hasGestures)
                putBoolean("hasAdaptive", snap.ancModes.any { it.id == "adaptive" })
                putBoolean("hasSpatial", caps.hasSpatial)
                putBoolean("hasHeadTracking", caps.hasHeadTracking)
                putStringArrayList("gainLabels", ArrayList(snap.gainLabels))
                putInt("promptVolumeMax", snap.promptVolumeMax)
            }
            send(MoonPodsAction.CAPABILITIES_CHANGED) { i ->
                i.putExtra(MoonPodsAction.EXTRA_CAPS_BUNDLE, bundle)
                i.putExtra(MoonPodsAction.EXTRA_MODEL_NAME, snap.modelName.ifBlank { snap.deviceName })
            }
        }

        if (snap.ancIndex >= 0 && snap.ancModes.isNotEmpty()) {
            send(MoonPodsAction.ANC_CHANGED) { i ->
                i.putExtra(MoonPodsAction.EXTRA_STATUS, snap.ancIndex)
                i.putStringArrayListExtra(
                    MoonPodsAction.EXTRA_ANC_IDS,
                    ArrayList(snap.ancModes.map { it.id }),
                )
            }
        }

        if (snap.gainIndex >= 0) {
            send(MoonPodsAction.GAIN_CHANGED) { i ->
                i.putExtra(MoonPodsAction.EXTRA_STATUS, snap.gainIndex)
            }
        }
        snap.ledOn?.let { on ->
            send(MoonPodsAction.LED_CHANGED) { i -> i.putExtra(MoonPodsAction.EXTRA_ENABLED, on) }
        }
        snap.promptToneOn?.let { on ->
            send(MoonPodsAction.PROMPT_TONE_CHANGED) { i -> i.putExtra(MoonPodsAction.EXTRA_ENABLED, on) }
        }
        if (snap.promptVolumeRaw >= 0) {
            send(MoonPodsAction.PROMPT_VOLUME_CHANGED) { i ->
                i.putExtra(MoonPodsAction.EXTRA_PROMPT_VOLUME_RAW, snap.promptVolumeRaw)
            }
        }
        snap.lhdcOn?.let { on ->
            send(MoonPodsAction.LHDC_CHANGED) { i -> i.putExtra(MoonPodsAction.EXTRA_ENABLED, on) }
        }
        snap.dualConnectionOn?.let { on ->
            send(MoonPodsAction.DUAL_CONNECTION_CHANGED) { i ->
                i.putExtra(MoonPodsAction.EXTRA_ENABLED, on)
            }
        }
        NorootNotification.post(ctx, snap)
    }

    /** 连接弹窗：仅在授予「显示在其他应用上层」时尝试，失败只记日志。 */
    private fun maybeShowPopup() {
        val ctx = appContext ?: return
        val allowed = runCatching { android.provider.Settings.canDrawOverlays(ctx) }.getOrDefault(false)
        if (!allowed) return
        runCatching {
            ctx.startActivity(Intent(ctx, moe.huime.moonpods.PopupActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("from_noroot_connect", true)
            })
        }.onFailure { Log.w(TAG, "popup launch failed", it) }
    }

    /** 最近一次连接的设备（广播里要带上 EXTRA_DEVICE，通知与原生页都依赖它）。 */
    @Volatile private var lastDevice: BluetoothDevice? = null

    private fun broadcastDisconnected() {
        val ctx = appContext ?: return
        ctx.sendBroadcast(Intent(MoonPodsAction.PODS_DISCONNECTED).apply {
            setPackage(BuildConfig.APPLICATION_ID)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        })
        NorootNotification.cancel(ctx)
    }
}
