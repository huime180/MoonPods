package moe.huime.moonpods.noroot

import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import moe.huime.moonpods.pods.moondrop.MoondropGaia

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
            if (moe.huime.moonpods.config.ConfigManager.focusNotification()) {
                broadcastState(snap)
            }
        } else {
            // 进程重启后耳机已不在线：撤掉上一次残留的通知
            NorootNotification.cancel(context.applicationContext)
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
        // 运行期补充监听：静态接收器在部分 ROM 上收不到 A2DP 状态广播，
        // 这里再按 ACL 连接事件兜底（进程已在时也要能自动连上）。
        runCatching {
            ctx.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
                    if (moe.huime.moonpods.config.ConfigManager.workMode() !=
                        moe.huime.moonpods.config.ConfigManager.WORK_MODE_NOROOT
                    ) return
                    val device = runCatching {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    }.getOrNull() ?: return
                    val name = runCatching { device.name ?: device.alias }.getOrNull()
                    if (MoondropLink.sniffModel(name) == null) return
                    if (!MoondropLink.isInitialized() || !MoondropLink.snapshot().connected) {
                        connect(ctx, device)
                    }
                }
            }, IntentFilter().apply { addAction(BluetoothDevice.ACTION_ACL_CONNECTED) }, android.content.Context.RECEIVER_EXPORTED)
        }.onFailure { Log.w(TAG, "acl receiver register failed", it) }

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
                        MoonPodsAction.REQUEST_CAPABILITIES,
                        MoonPodsAction.ACTION_REFRESH_STATUS -> {
                            // 弹窗/页面打开时会先问一次：立刻回放当前快照（否则界面是空的），
                            // 再异步刷新一次真实状态。
                            val snap = runCatching { MoondropLink.snapshot() }.getOrNull()
                            if (snap?.connected == true) {
                                lastBroadcast = null
                                broadcastState(snap)
                                if (moe.huime.moonpods.config.ConfigManager.focusNotification()) {
                                    NorootNotification.postSimple(appContext ?: ctx, snap)
                                } else {
                                    NorootNotification.cancel(appContext ?: ctx)
                                }
                            }
                            MoondropLink.refreshAll()
                        }
                        MoonPodsAction.REQUEST_BATTERY -> MoondropLink.requestBatteryRefresh()
                        MoonPodsAction.REQUEST_GESTURE -> {
                            // 打开手势页时读一次：读回来会以 GestureChanged 事件回灌
                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                runCatching { MoondropLink.refreshGestures() }
                            }
                        }
                        MoonPodsAction.GESTURE_SELECT -> {
                            val slot = intent.getIntExtra(MoonPodsAction.EXTRA_GESTURE_SLOT, -1)
                            val ear = intent.getIntExtra(MoonPodsAction.EXTRA_GESTURE_EAR, -1)
                            val actionId = intent.getIntExtra(MoonPodsAction.EXTRA_STATUS, -1)
                            val slots = MoondropGaia.GestureSlot.entries
                            val ears = MoondropGaia.Ear.entries
                            if (slot in slots.indices && ear in ears.indices && actionId >= 0) {
                                MoondropLink.setGesture(slots[slot], ears[ear], actionId)
                            }
                        }
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
        if (event is PodEvent.GestureChanged) {
            val ctx = appContext
            if (ctx != null) {
                val payload = runCatching { event.conf.toPayload() }.getOrNull()
                if (payload != null) {
                    ctx.sendBroadcast(Intent(MoonPodsAction.GESTURE_CHANGED).apply {
                        putExtra(MoonPodsAction.EXTRA_MAC, lastDevice?.address)
                        putExtra(MoonPodsAction.EXTRA_GESTURE_PAYLOAD, payload)
                        setPackage(BuildConfig.APPLICATION_ID)
                        addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    })
                }
            }
        }
        val snap = when (event) {
            is PodEvent.Connected -> event.snapshot
            PodEvent.Disconnected -> null
            else -> runCatching { MoondropLink.snapshot() }.getOrNull()
        }
        _snapshot.value = snap
        if (snap == null) {
            lastBroadcast = null
            broadcastDisconnected()
            return
        }
        if (snap == lastBroadcast) {
            Log.i(TAG, "broadcast skipped (unchanged)")
            return
        }
        lastBroadcast = snap
        Log.i(TAG, "broadcast state: anc=${snap.ancIndex}/${snap.ancModes.size} gain=${snap.gainIndex} led=${snap.ledOn} lhdc=${snap.lhdcOn} dual=${snap.dualConnectionOn}")
        // 通知先发：广播链路任何一环抛异常都不该让通知丢失（之前就是这个顺序问题）
        runCatching {
            // 通知总开关与 Root 模式共用同一个设置项（焦点通知开关）
            val ctx = appContext ?: return
            if (!moe.huime.moonpods.config.ConfigManager.focusNotification()) {
                NorootNotification.cancel(ctx)
            } else {
                Log.i(TAG, "request notification: ${snap.deviceName} ${snap.battery.left}/${snap.battery.right}")
                NorootNotification.postSimple(ctx, snap)
            }
        }.onFailure { Log.w(TAG, "notification post failed", it) }
        runCatching { broadcastState(snap) }
            .onFailure { Log.w(TAG, "state broadcast failed", it) }
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
    }

    /** 连接弹窗：仅在授予「显示在其他应用上层」时尝试，失败只记日志。 */
    /**
     * 连接弹窗：样式跟随设置 connectPopup。
     *   0 模块弹窗 → PopupActivity
     *   1 官方     → MOONDROP App（未安装则退回系统蓝牙设置）
     *   2 不弹出
     * 只有授予「显示在其他应用上层」时才允许从后台拉起界面。
     */
    private fun maybeShowPopup() {
        val ctx = appContext ?: return
        when (moe.huime.moonpods.config.ConfigManager.connectPopup()) {
            moe.huime.moonpods.config.ConfigManager.CONNECT_POPUP_NONE -> return
            moe.huime.moonpods.config.ConfigManager.CONNECT_POPUP_OFFICIAL -> {
                val intent = moe.huime.moonpods.utils.OfficialApp.launchIntent(ctx)
                    ?: Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
                runCatching {
                    ctx.startActivity(intent.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                }.onFailure { Log.w(TAG, "official popup failed", it) }
                return
            }
            else -> Unit
        }
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
