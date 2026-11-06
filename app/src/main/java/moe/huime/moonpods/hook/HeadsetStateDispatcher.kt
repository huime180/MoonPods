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
        runCatching {
            hookAfter(findMethod("com.android.bluetooth.btservice.AdapterService", "onCreate")) {
                registerAppRequestReceiver(instance as? Context)
            }
        }.onFailure {
            Log.w("HyperPods", "AdapterService.onCreate hook skipped", it)
        }

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
                if (moe.huime.moonpods.config.ConfigManager.workMode() ==
                    moe.huime.moonpods.config.ConfigManager.WORK_MODE_NOROOT
                ) return@post
                // 认不出来的设备什么都不做（不是本模块支持的耳机时，绝不能去动系统的蓝牙状态）
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
            MOONDROP_CONTROL_ACTIONS.forEach { addAction(it) }
        }, Context.RECEIVER_EXPORTED)
        appRequestReceiverRegistered = true
    }

    /** 品牌判定：设备名 + MAC 交给 [PodCatalog]，判定规则集中在 pods/。 */
    @SuppressLint("MissingPermission")
    private fun resolveBrand(device: BluetoothDevice): PodBrand? =
        runCatching { PodCatalog.brandOf(device.name ?: device.alias, device.address) }
            .onFailure { Log.w(TAG, "brand resolve failed for ${device.address}", it) }
            .getOrNull()
}
