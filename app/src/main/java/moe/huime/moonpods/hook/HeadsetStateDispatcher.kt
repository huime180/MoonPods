package moe.huime.moonpods.hook

import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import moe.huime.moonpods.BuildConfig
import moe.huime.moonpods.pods.PodBrand
import moe.huime.moonpods.pods.PodCatalog
import moe.huime.moonpods.pods.moondrop.MoondropController
import moe.huime.moonpods.utils.SystemApisUtils.setIconVisibility
import moe.huime.moonpods.utils.miuiStrongToast.data.MoonPodsAction

object HeadsetStateDispatcher : HookContext() {

    /** 日志 TAG（object 内是普通成员，不能放 companion：standalone object 不允许 companion）。 */
    private const val TAG = "HyperPods-Bluetooth"
    private var appRequestReceiverRegistered = false

    /**
     * 控制命令（应用 UI / 被伪装的原生耳机页 → 本进程的控制器）。
     *
     * 为什么要在蓝牙进程收：协议栈（[MoondropController]）就跑在本进程，
     * 命令必须回到协议栈所在进程执行，跨进程只有广播这一条路。
     */
    private val MOONDROP_CONTROL_ACTIONS = arrayOf(
        MoonPodsAction.UI_INIT,
        MoonPodsAction.REQUEST_BATTERY,
        MoonPodsAction.REQUEST_CAPABILITIES,
        MoonPodsAction.ANC_SELECT,
        MoonPodsAction.GAIN_SELECT,
        MoonPodsAction.LED_SELECT,
        MoonPodsAction.PROMPT_TONE_SELECT,
        MoonPodsAction.PROMPT_VOLUME_SELECT,
        MoonPodsAction.LHDC_SELECT,
        MoonPodsAction.DUAL_CONNECTION_SELECT,
        MoonPodsAction.SPATIAL_AUDIO_SELECT,
        MoonPodsAction.HEAD_TRACKING_SELECT,
        MoonPodsAction.GESTURE_SELECT,
        MoonPodsAction.CODEC_CHANGED
    )

    override fun onHook() {
        // 注意：这里**不能**按模式早退。被注入进程的 remote prefs 可能滞后（实测读到
        // 的 workMode 比应用侧旧），一旦早退就永远收不到应用随后发来的 SET_WORK_MODE，
        // 模式切换后只能重启进程才好 —— 那正是"切到 Root 也连不上"的原因。
        // 真正的模式判断放在每个回调里（WorkModeBridge），免 root 时不会做任何接管。
        runCatching {
            Log.i(TAG, "registering AdapterService.onCreate hook")
            hookAfter(findMethod("com.android.bluetooth.btservice.AdapterService", "onCreate")) {
                registerAppRequestReceiver(instance as? Context)
            }
        }.onFailure {
            Log.w("HyperPods", "AdapterService.onCreate hook skipped", it)
        }

        // 显式记录每一步 hook 的注册结果：Root 连接问题的第一现场
        runCatching {
            val m = findMethodByParamCount("com.android.bluetooth.a2dp.A2dpService", "handleConnectionStateChanged", 3)
            Log.i(TAG, "a2dp hook target: $m")
        }.onFailure { Log.e(TAG, "a2dp hook target not found", it) }

        hookAfter(findMethodByParamCount("com.android.bluetooth.a2dp.A2dpService", "handleConnectionStateChanged", 3)) {
            val currState = args[2] as Int
            val fromState = args[1] as Int
            val device = args[0] as BluetoothDevice?
            val handler = getObjectField(instance, "mHandler") as Handler
            if (device == null || currState == fromState) {
                return@hookAfter
            }
            handler.post {
                val context = instance as ContextWrapper
                registerAppRequestReceiver(context)
                // 免 root 模式：链路由应用进程自己建，这里绝不接管（否则两边抢同一条 SPP/GATT）
                if (moe.huime.moonpods.pods.WorkModeBridge.isNoRoot()) return@post
                // 认不出来的设备什么都不做（不是本模块支持的耳机时，绝不能去动系统的蓝牙状态）
                if (!moe.huime.moonpods.pods.moondrop.MoondropCatalog.loaded) {
                    // 被注入进程无法联网：请应用进程代为拉取并写回共享缓存
                    runCatching {
                        context.sendBroadcast(Intent(
                            moe.huime.moonpods.utils.miuiStrongToast.data.MoonPodsAction.REFRESH_CATALOG
                        ).apply {
                            setPackage(moe.huime.moonpods.BuildConfig.APPLICATION_ID)
                            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                        })
                    }
                }
                if (resolveBrand(device) == null) {
                    Log.d(TAG, "A2DP state $fromState -> $currState device=${device.address} 非水月雨，忽略")
                    return@post
                }
                Log.d(TAG, "A2DP state $fromState -> $currState device=${device.address}")

                val statusBarManager = context.getSystemService("statusbar") as StatusBarManager
                if (currState == BluetoothHeadset.STATE_CONNECTED) {
                    statusBarManager.setIconVisibility("wireless_headset", true)
                    MoondropController.init(context)
                    MoondropController.connect(device)
                } else if (currState == BluetoothHeadset.STATE_DISCONNECTING || currState == BluetoothHeadset.STATE_DISCONNECTED) {
                    statusBarManager.setIconVisibility("wireless_headset", false)
                    MoondropController.disconnect()
                }
            }
        }
    }

    private fun registerAppRequestReceiver(context: Context?) {
        if (context == null || appRequestReceiverRegistered) return
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (context == null) return
                when (intent?.action) {
                    MoonPodsAction.SET_WORK_MODE -> {
                        val mode = intent.getIntExtra("mode", -1)
                        if (mode >= 0) moe.huime.moonpods.pods.WorkModeBridge.override = mode
                        Log.i(TAG, "work mode set by app: $mode")
                        // 切到 Root 时立即接管已连接的耳机：这条广播已被日志证明可达，
                        // 不依赖 AdapterService.onCreate 是否 hook 成功。
                        if (mode == moe.huime.moonpods.config.ConfigManager.WORK_MODE_ROOT) {
                            connectAlreadyConnected(context)
                        }
                    }
                    MoonPodsAction.ACTION_PODS_UI_INIT,
                    MoonPodsAction.ACTION_REFRESH_STATUS -> {
                        context.sendBroadcast(Intent(MoonPodsAction.ACTION_MODULE_BLUETOOTH_SERVICE_ALIVE).apply {
                            setPackage(BuildConfig.APPLICATION_ID)
                            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                        })
                    }
                    MoonPodsAction.ACTION_CONNECT_POD_REQUEST -> {
                        val device = intent.getParcelableExtra("device", BluetoothDevice::class.java) ?: return
                        if (resolveBrand(device) == null) return
                        Log.d(TAG, "connect request from app device=${device.address}")
                        MoondropController.init(context)
                        MoondropController.connect(device)
                    }
                    MoonPodsAction.ACTION_DISCONNECT_POD_REQUEST -> {
                        val device = intent.getParcelableExtra("device", BluetoothDevice::class.java) ?: return
                        if (resolveBrand(device) == null) return
                        Log.d(TAG, "disconnect request from app device=${device.address}")
                        MoondropController.disconnect()
                    }

                    else -> {
                        // 命令统一交给本进程的控制器解释（认不出的 action 会被它忽略）
                        val action = intent?.action ?: return
                        if (MOONDROP_CONTROL_ACTIONS.contains(action)) {
                            MoondropController.init(context)
                            MoondropController.handleUIEvent(intent, context)
                        }
                    }
                }
            }
        }, IntentFilter().apply {
            addAction(MoonPodsAction.ACTION_PODS_UI_INIT)
            addAction(MoonPodsAction.ACTION_REFRESH_STATUS)
            addAction(MoonPodsAction.ACTION_CONNECT_POD_REQUEST)
            addAction(MoonPodsAction.ACTION_DISCONNECT_POD_REQUEST)
            addAction(MoonPodsAction.SET_WORK_MODE)
            MOONDROP_CONTROL_ACTIONS.forEach { addAction(it) }
        }, Context.RECEIVER_EXPORTED)

        // 兜底：A2dpService 的 hook 在部分 ROM 上不触发（混淆/签名差异），
        // 这里再监听系统 A2DP 广播，保证 Root 模式也能自动连接。
        runCatching {
            context.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    if (intent?.action != android.bluetooth.BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED) return
                    val device = runCatching {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    }.getOrNull() ?: return
                    val state = intent.getIntExtra(android.bluetooth.BluetoothProfile.EXTRA_STATE, -1)
                    if (resolveBrand(device) == null) return
                    if (state == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                        MoondropController.init(context)
                        MoondropController.connect(device)
                    } else if (state == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                        MoondropController.disconnect()
                    }
                }
            }, IntentFilter(android.bluetooth.BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED), Context.RECEIVER_EXPORTED)
        }.onFailure { Log.w(TAG, "a2dp receiver register failed", it) }

        connectAlreadyConnected(context)
        appRequestReceiverRegistered = true
    }

    /**
     * 模块加载时耳机可能已经连上了（蓝牙进程重启、模块后注入、LHDC 重协商之前等）：
     * 只监听连接事件会错过这种情况。这里主动查一次当前已连接的 A2DP 设备并接管。
     */
    @SuppressLint("MissingPermission")
    private fun connectAlreadyConnected(context: Context) {
        runCatching {
            val manager = context.getSystemService(android.bluetooth.BluetoothManager::class.java)
            val adapter = manager?.adapter ?: return
            if (!adapter.isEnabled) return
            val connected = manager.getConnectedDevices(android.bluetooth.BluetoothProfile.A2DP)
            connected?.forEach { device ->
                if (resolveBrand(device) == null) return@forEach
                Log.i(TAG, "already connected at load: ${device.address}, connecting")
                MoondropController.init(context)
                MoondropController.connect(device)
            }
        }.onFailure { Log.w(TAG, "connectAlreadyConnected failed", it) }
    }

    /** 品牌判定：设备名 + MAC 交给 [PodCatalog]，判定规则集中在 pods/。 */
    @SuppressLint("MissingPermission")
    private fun resolveBrand(device: BluetoothDevice): PodBrand? =
        runCatching { PodCatalog.brandOf(device.name ?: device.alias, device.address) }
            .onFailure { Log.w(TAG, "brand resolve failed for ${device.address}", it) }
            .getOrNull()
}
