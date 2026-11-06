package moe.huime.moonpods.hook

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Looper
import com.xzakota.hyper.notification.focus.FocusNotification
import moe.huime.moonpods.utils.FocusIslandUtil
import moe.huime.moonpods.utils.PodImageLoader
import moe.huime.moonpods.utils.SystemApisUtils
import moe.huime.moonpods.utils.SystemApisUtils.cancelAsUser
import moe.huime.moonpods.utils.SystemApisUtils.notifyAsUser
import moe.huime.moonpods.config.ConfigManager
import moe.huime.moonpods.utils.miuiStrongToast.data.BatteryParams
import moe.huime.moonpods.utils.miuiStrongToast.data.batteryStatusCompat
import moe.huime.moonpods.utils.miuiStrongToast.data.MoonPodsAction
import moe.huime.moonpods.R
import moe.huime.moonpods.pods.detectDeviceCapabilities
import moe.huime.moonpods.utils.miuiStrongToast.MiuiStrongToastUtil

@SuppressLint("MissingPermission")
object MiBluetoothToastHook : HookContext() {

    // ANC 模式本地缓存，用于循环切换和状态同步（1=关 2=降噪 3=通透 4=自适应）
    // 通过接收 ACTION_PODS_ANC_CHANGED 广播与控制器保持同步
    private var localAncMode = 1
    private var notificationReceiverRegistered = false
    private var lastOfficialIslandShape: Triple<Boolean, Boolean, Boolean>? = null

    @SuppressLint("WrongConstant")
    @Volatile private var switchReceiverRegistered = false

    /**
     * 注册开关同步接收器（只注册一次）。
     *
     * 被注入进程读 remote prefs 会滞后，应用侧切换「焦点通知」时发的显式广播
     * 是唯一能立即生效的通道；此前只有 com.android.bluetooth 注册了接收器，
     * 本进程（com.xiaomi.bluetooth，真正发通知的那个）收不到。
     */
    /** 关闭开关时撤掉已发布的耳机通知（按 tag 扫描，只清本进程的 BTHeadset*）。 */
    private fun cancelStaleNotifications(context: Context) {
        runCatching {
            val nm = context.getSystemService("notification") as android.app.NotificationManager
            nm.activeNotifications
                .filter { it.tag?.startsWith("BTHeadset") == true }
                .forEach { n ->
                    runCatching {
                        nm.cancelAsUser(n.tag, n.id, moe.huime.moonpods.utils.SystemApisUtils.getUserAllUserHandle())
                    }
                }
            Log.i("HyperPods", "focus switch off: stale headset notifications cancelled")
        }.onFailure { Log.w("HyperPods", "cancel stale notifications failed", it) }
    }

    private fun ensureSwitchReceiver(context: Context) {
        if (switchReceiverRegistered) return
        switchReceiverRegistered = true
        runCatching {
            context.applicationContext.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    when (intent?.action) {
                        MoonPodsAction.SET_FOCUS_NOTIFICATION -> {
                            val enabled = intent.getBooleanExtra("enabled", true)
                            moe.huime.moonpods.config.FocusNotificationBridge.set(enabled)
                            // 关闭开关要立刻撤掉已经发出去的那条，而不是等下一次更新
                            if (!enabled && ctx != null) {
                                cancelStaleNotifications(ctx.applicationContext)
                            }
                        }
                        MoonPodsAction.SET_WORK_MODE -> {
                            val mode = intent.getIntExtra("mode", -1)
                            if (mode >= 0) moe.huime.moonpods.pods.WorkModeBridge.override = mode
                        }
                    }
                }
            }, IntentFilter().apply {
                addAction(MoonPodsAction.SET_FOCUS_NOTIFICATION)
                addAction(MoonPodsAction.SET_WORK_MODE)
            }, Context.RECEIVER_EXPORTED)
            Log.i("HyperPods", "switch receiver registered (xiaomi.bluetooth)")
        }.onFailure { Log.w("HyperPods", "switch receiver register failed", it) }
    }

    override fun onHook() {
        // 不能按模式早退：被注入进程的 remote prefs 会滞后，退出后就连
        // 「切回 Root 时重新发通知」都做不到（Root 模式通知完全消失）。
        // 模式判断放在每次通知更新时（ConfigManager.focusNotificationFresh / WorkModeBridge）。

        // 焦点通知总开关不再决定「是否挂钩」：关掉时要能撤掉已有通知、打开时要能立刻恢复，
        // 因此挂钩始终生效，开关在每次更新通知时判断。

        fun deleteIntent(context: Context, bluetoothDevice: BluetoothDevice): PendingIntent? {
            val intent = Intent("com.android.bluetooth.headset.notification.cancle")
            intent.putExtra("android.bluetooth.device.extra.DEVICE", bluetoothDevice)
            return PendingIntent.getBroadcast(context, 0, intent, 201326592)
        }

        fun createPodsNotification(bluetoothDevice: BluetoothDevice?, context: Context, batteryParams: BatteryParams) {
            // 开关同步：本进程需要自己接收广播（remote prefs 会滞后）
            ensureSwitchReceiver(context)
            // 焦点通知总开关：关闭时不再创建/刷新小米侧通知（并撤掉已有的一条）。
            // 这里读的是**刷新后的**配置 —— 被注入进程的 remote prefs 会滞后，
            // 用缓存值会出现「设置里关了、通知照样出现」。
            if (!ConfigManager.focusNotificationFresh()) {
                runCatching {
                    android.app.NotificationManager::class.java
                        .let { context.getSystemService(it) }
                        ?.cancel(10004)
                }
                return
            }
            // 连接弹窗：模块弹窗两种模式都支持（Root 侧由本进程拉起应用弹窗）
            runCatching {
                if (ConfigManager.connectPopup() == ConfigManager.CONNECT_POPUP_MODULE &&
                    android.provider.Settings.canDrawOverlays(context)
                ) {
                    context.startActivity(
                        Intent().setClassName("moe.huime.moonpods", "moe.huime.moonpods.PopupActivity")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    )
                }
            }

            val miheadset_notification_Box = context.resources.getIdentifier("miheadset_notification_Box", "string", "com.xiaomi.bluetooth")
            val miheadset_notification_LeftEar = context.resources.getIdentifier("miheadset_notification_LeftEar", "string", "com.xiaomi.bluetooth")
            val miheadset_notification_RightEar = context.resources.getIdentifier("miheadset_notification_RightEar", "string", "com.xiaomi.bluetooth")
            val miheadset_notification_Disconnect = context.resources.getIdentifier("miheadset_notification_Disconnect", "string", "com.xiaomi.bluetooth")
            val system_notification_accent_color = context.resources.getIdentifier("system_notification_accent_color", "color", "android")
            if (bluetoothDevice == null) {
                Log.e("HyperPods", "createPodsNotification: btDevice null")
                return
            }
            try {
                val address: String = bluetoothDevice.address
                var alias: String? = bluetoothDevice.alias
                if (alias?.isEmpty() == true) {
                    alias = bluetoothDevice.name
                }

                val caseBattStr = if (batteryParams.case != null && batteryParams.case!!.isConnected)
                    "${context.resources.getString(miheadset_notification_Box)}${batteryParams.case!!.battery}%" +
                            "${if (batteryParams.case!!.isCharging) "⚡ " else " "}\n"
                else ""
                val leftEar = if (batteryParams.left != null && batteryParams.left!!.isConnected)
                    "${context.resources.getString(miheadset_notification_LeftEar)}${batteryParams.left!!.battery}%" +
                        (if (batteryParams.left!!.isCharging) "⚡" else "")
                else ""
                val leftToRight = if (batteryParams.left?.isConnected == true && batteryParams.right?.isConnected == true) " " else ""
                val rightEar = if (batteryParams.right != null && batteryParams.right!!.isConnected)
                    "$leftToRight${context.resources.getString(miheadset_notification_RightEar)}${batteryParams.right!!.battery}%" +
                        (if (batteryParams.right!!.isCharging) "⚡ " else " ")
                else ""

                val contentText: String = caseBattStr + leftEar + rightEar
                val notificationManager = context.getSystemService("notification") as NotificationManager
                notificationManager.createNotificationChannel(
                    NotificationChannel(
                        "BTHeadset$address",
                        alias,
                        NotificationManager.IMPORTANCE_DEFAULT
                    ).apply {
                        setSound(null, null)
                        setAllowBubbles(true)
                    }
                )
                val bundle = Bundle()
                bundle.putParcelable("Device", bluetoothDevice)
                val intent = Intent("com.android.bluetooth.headset.notification")
                intent.putExtra("btData", bundle)
                intent.putExtra("disconnect", "1")
                intent.setIdentifier("BTHeadset$address")
                val disconnectAction = Notification.Action(
                    285737079,
                    context.resources.getString(miheadset_notification_Disconnect),
                    PendingIntent.getBroadcast(context, 0, intent, 201326592)
                )
                val moduleContext = context.createPackageContext(
                    "moe.huime.moonpods", Context.CONTEXT_IGNORE_SECURITY
                )
                val headsetBitmap = PodImageLoader.loadBoxBitmap(context, prefs, address)
                    ?: BitmapFactory.decodeResource(moduleContext.resources, R.drawable.img_box)
                if (headsetBitmap == null) {
                    Log.e("HyperPods", "createPodsNotification: headset bitmap null")
                    return
                }
                val headsetIcon = Icon.createWithBitmap(headsetBitmap)
                val pendingIntent = PendingIntent.getActivity(
                    context,
                    0,
                    Intent("chen.action.hyperpods.show_pods_ui").apply {
                        setClassName("moe.huime.moonpods", "moe.huime.moonpods.PopupActivity")
                        putExtra("android.bluetooth.device.extra.DEVICE", bluetoothDevice)
                        putExtra("bluetoothaddress", bluetoothDevice.address)
                        putExtra("device_name", alias)
                    },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val focusExtras = FocusNotification.buildV3 {
                    val logo = createPicture("key_headset", headsetIcon)
                    enableFloat = true
                    ticker = alias ?: ""
                    updatable = true
//                    tickerPic = logo

                    iconTextInfo {
                        animIconInfo{
                            type = 0
                            src = logo
                        }
                        title = alias ?: ""
                        content = contentText
                    }

                    island {
                        islandProperty = 1
                        bigIslandArea {
                            imageTextInfoLeft {
                                type = 1
                                picInfo {
                                    type = 1
                                    pic = logo
                                }
                            }
                            imageTextInfoRight {
                                type = 2
                                textInfo {
                                    title = alias ?: ""
                                    content = contentText
                                }
                            }
                        }
                    }


                    // 「断开连接」挂在**模板动作栏**（param_v2.actions）上，而不是 textButton：
                    // textButton 是内容下方**独立一整行**的胶囊按钮区，只剩一个按钮时会被系统拉满
                    // 整行（上游原本并排着「切换降噪」+「断开连接」，去掉前者后就撑开了 —— 用户反馈
                    // 太大）。actions 里的动作跟正文属同一块卡片布局，由系统按内容宽度排在行尾，
                    // 不再独占一行；type = 2 是「文字按钮」（ActionInfo 注释：0 圆形 / 1 进度 /
                    // 2 文字，库的 getType() 也是「无图标 + 有标题 = 2」），这样「断开连接」文案仍然
                    // 显示，不会退化成只剩一个图标。
                    actions {
                        addActionInfo {
                            type = 2
                            // 文案取**系统自带**的 miheadset_notification_Disconnect，而不是模块自己的
                            // R.string.*：注入进程里 createPackageContext 读的是磁盘上的模块 APK 资源，
                            // 若该进程还跑着旧 dex（更新模块后没重启作用域），数字资源 ID 就会与新资源错位，
                            // 按钮上会冒出别的字符串（实测出现过「右耳图片」「选择点击…」）。
                            // 系统字符串与注入进程同源，不存在这个错位。
                            val disconnectLabel = context.resources.getString(miheadset_notification_Disconnect)
                            val disconnectIntent = Intent("com.android.bluetooth.headset.notification").apply {
                                putExtra("btData", bundle)
                                putExtra("disconnect", "1")
                                setIdentifier("BTHeadset$address")
                            }
                            val disconnectAction = Notification.Action.Builder(
                                Icon.createWithResource(context, android.R.drawable.ic_delete),
                                disconnectLabel,
                                PendingIntent.getBroadcast(context, 2, disconnectIntent, 201326592)
                            ).build()
                            action = createAction("key_disconnect", disconnectAction)
                            actionTitle = disconnectLabel
                        }
                    }
                }
                // AOD 息屏显示：左右耳电量拼合后注入 aodTitle
                if (focusExtras != null) {
                    val aodParts = mutableListOf<String>()
                    if (batteryParams.left?.isConnected == true)
                        aodParts.add("L ${batteryParams.left!!.battery}%")
                    if (batteryParams.right?.isConnected == true)
                        aodParts.add("R ${batteryParams.right!!.battery}%")
                    val aodTitle = aodParts.joinToString(" | ")
                    try {
                        val json = org.json.JSONObject(focusExtras.getString("miui.focus.param") ?: "{}")
                        val pv2 = json.optJSONObject("param_v2") ?: org.json.JSONObject()
                        pv2.put("aodTitle", aodTitle)
                        pv2.put("aodPic", "key_headset")
                        json.put("param_v2", pv2)
                        focusExtras.putString("miui.focus.param", json.toString())
                    } catch (_: Exception) {}
                }
                notificationManager.notifyAsUser(
                    "BTHeadset$address",
                    10003,
                    Notification.Builder(context, "BTHeadset$address")
                        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                        .setWhen(0L)
                        .setTicker(alias)
                        .setDefaults(-1)
                        .setContentTitle(alias)
                        .setContentText(contentText)
                        .setContentIntent(pendingIntent)
                        .setDeleteIntent(deleteIntent(context, bluetoothDevice))
                        .setColor(context.getColor(system_notification_accent_color))
                        .addAction(disconnectAction)
                        .apply { focusExtras?.let { addExtras(it) } }
                        .setVisibility(Notification.VISIBILITY_PUBLIC)
                        .build(),
                    SystemApisUtils.getUserAllUserHandle()
                )
            } catch (e: Exception) {
                Log.e("HyperPods", "Failed to create Pod Notification", e)
            }
        }

        /**
         * 撤掉本模块发的耳机通知（tag = `BTHeadset<MAC>`，id = 10003）。
         *
         * [bluetoothDevice] 允许为 null（断连时控制器已把 `device` 清空）：
         * 只要 [mac] 还在，用 MAC 拼 tag 一样能撤。两个都没有就跳过并记一条日志 ——
         * 静默跳过正是「通知撤不掉」这个 bug 的原始形态，别再退回去。
         */
        fun cancelNotification(bluetoothDevice: BluetoothDevice?, context: Context, mac: String? = null) {
            try {
                val address = bluetoothDevice?.address?.takeIf { it.isNotEmpty() } ?: mac.orEmpty()
                val notificationManager = context.getSystemService("notification") as NotificationManager
                if (address.isNotEmpty()) {
                    notificationManager.cancelAsUser("BTHeadset$address", 10003, SystemApisUtils.getUserAllUserHandle())
                } else {
                    // 兜底：断连事件可能先于/晚于会话，控制器里已经没有 device，MAC 也拿不到。
                    // 这时扫一遍**本进程**发出去的通知（都在 com.xiaomi.bluetooth 名下，
                    // tag 统一是 BTHeadset<MAC> —— 与 MIUI 官方那条同款），把残留的全撤掉。
                    // 只扫本进程自己发的，不碰别的应用；失败也不影响主流程。
                    val stale = runCatching { notificationManager.activeNotifications }
                        .getOrNull().orEmpty()
                        .filter { it.tag?.startsWith("BTHeadset") == true }
                    stale.forEach { n ->
                        runCatching {
                            notificationManager.cancelAsUser(n.tag, n.id, SystemApisUtils.getUserAllUserHandle())
                        }
                    }
                    Log.w("HyperPods", "cancelNotification: no device/mac, swept ${stale.size} stale notification(s)")
                }
            } catch (e: Exception) {
                Log.e("HyperPods", "Failed to cancel Pod Notification!", e)
            }
        }


        fun registerNotificationReceiver(context: Context) {
            if (notificationReceiverRegistered) return
            val broadcastReceiver = object : BroadcastReceiver() {
                        override fun onReceive(p0: Context?, p1: Intent?) {
                            if (p1?.action == "chen.action.hyperpods.sendstrongtoast") {
                                val batteryParams = p1.getParcelableExtra("batteryParams", BatteryParams::class.java)!!
                                when (ConfigManager.islandMode()) {
                                    ConfigManager.ISLAND_MODE_MODULE -> {
                                        val address = p1.getStringExtra("address").orEmpty()
                                        FocusIslandUtil.showBatteryIsland(context, prefs, batteryParams, address)
                                    }
                                    ConfigManager.ISLAND_MODE_OFFICIAL -> {
                                        MiuiStrongToastUtil.showOfficialConnectToast(context, batteryParams)
                                        lastOfficialIslandShape = batteryShape(batteryParams)
                                    }
                                }
                            } else if (p1?.action == "chen.action.hyperpods.updatepodsnotification") {
                                val batteryParams = p1.getParcelableExtra<BatteryParams>("batteryParams", BatteryParams::class.java)
                                val device = p1.getParcelableExtra("device", BluetoothDevice::class.java)
                                if (batteryParams != null && ConfigManager.islandMode() == ConfigManager.ISLAND_MODE_OFFICIAL) {
                                    val shape = batteryShape(batteryParams)
                                    if (lastOfficialIslandShape == null || shape != lastOfficialIslandShape) {
                                        MiuiStrongToastUtil.showOfficialConnectToast(context, batteryParams)
                                    }
                                    lastOfficialIslandShape = shape
                                }
                                createPodsNotification(device, context, batteryParams!!)
                            } else if (p1?.action == "chen.action.hyperpods.cancelpodsnotification") {
                                val device = p1.getParcelableExtra("device", BluetoothDevice::class.java) as BluetoothDevice
                                cancelNotification(device, context)
                                MiuiStrongToastUtil.hideOfficialToast(context)
                                lastOfficialIslandShape = null
                            } else if (p1?.action == MoonPodsAction.UPDATE_PODS_NOTIFICATION) {
                                // 水月雨线：电量由蓝牙进程里的控制器发来，extra 走同一个
                                // BatteryStatusIntent helper，所以渲染路径与 OPPO 完全共用
                                val batteryParams = p1.batteryStatusCompat() ?: return
                                val device = p1.getParcelableExtra(
                                    MoonPodsAction.EXTRA_DEVICE, BluetoothDevice::class.java
                                )
                                createPodsNotification(device, context, batteryParams)
                            } else if (p1?.action == MoonPodsAction.SEND_STRONG_TOAST) {
                                val batteryParams = p1.batteryStatusCompat() ?: return
                                val address = p1.getStringExtra(MoonPodsAction.EXTRA_MAC).orEmpty()
                                when (ConfigManager.islandMode()) {
                                    ConfigManager.ISLAND_MODE_MODULE ->
                                        FocusIslandUtil.showBatteryIsland(context, prefs, batteryParams, address)

                                    ConfigManager.ISLAND_MODE_OFFICIAL -> {
                                        MiuiStrongToastUtil.showOfficialConnectToast(context, batteryParams)
                                        lastOfficialIslandShape = batteryShape(batteryParams)
                                    }
                                }
                            } else if (p1?.action == MoonPodsAction.CANCEL_PODS_NOTIFICATION) {
                                val device = p1.getParcelableExtra(
                                    MoonPodsAction.EXTRA_DEVICE, BluetoothDevice::class.java
                                )
                                // EXTRA_DEVICE 可能为空（断连时控制器已把 device 清空），
                                // 所以同时取 EXTRA_MAC —— 通知的 tag 就是 BTHeadset<MAC>。
                                cancelNotification(device, context, p1.getStringExtra(MoonPodsAction.EXTRA_MAC))
                                MiuiStrongToastUtil.hideOfficialToast(context)
                                lastOfficialIslandShape = null
                            } else if (p1?.action == MoonPodsAction.ACTION_PODS_ANC_CHANGED) {
                                // 同步耳机实际 ANC 状态到本地缓存，确保下次循环切换时状态准确
                                localAncMode = p1.getIntExtra("status", 1)
                            } else if (p1?.action == MoonPodsAction.ACTION_CYCLE_ANC) {
                                // OPPO 的 RFCOMM 快照已移除：设备名直接取广播里的 EXTRA
                                val capabilities = detectDeviceCapabilities(
                                    context = p0,
                                    deviceName = p1.getStringExtra("device_name").orEmpty(),
                                )
                                val cycle = if (capabilities.adaptiveSupported) {
                                    listOf(2, 4, 3, 1)
                                } else {
                                    listOf(2, 3, 1)
                                }
                                val currentIndex = cycle.indexOf(if (localAncMode in 5..8) 2 else localAncMode)
                                localAncMode = cycle[(currentIndex + 1).floorMod(cycle.size)]
                                Intent(MoonPodsAction.ACTION_ANC_SELECT).apply {
                                    putExtra("status", localAncMode)
                                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                                    p0?.sendBroadcast(this)
                                }
                            }
                        }
                    }

            val intentFilter = IntentFilter("chen.action.hyperpods.sendstrongtoast")
            intentFilter.addAction("chen.action.hyperpods.updatepodsnotification")
            intentFilter.addAction("chen.action.hyperpods.cancelpodsnotification")
            intentFilter.addAction(MoonPodsAction.ACTION_CYCLE_ANC)
            intentFilter.addAction(MoonPodsAction.ACTION_PODS_CONNECTED)
            intentFilter.addAction(MoonPodsAction.ACTION_PODS_DISCONNECTED)
            intentFilter.addAction(MoonPodsAction.ACTION_PODS_ANC_CHANGED)
            // 水月雨线（协议栈在蓝牙进程，通知侧在这里渲染）
            intentFilter.addAction(MoonPodsAction.UPDATE_PODS_NOTIFICATION)
            intentFilter.addAction(MoonPodsAction.SEND_STRONG_TOAST)
            intentFilter.addAction(MoonPodsAction.CANCEL_PODS_NOTIFICATION)
            context.registerReceiver(broadcastReceiver, intentFilter, Context.RECEIVER_EXPORTED)
            notificationReceiverRegistered = true
            context.sendBroadcast(Intent(MoonPodsAction.ACTION_REFRESH_STATUS).apply {
                setPackage("com.android.bluetooth")
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            })
        }

        fun hookNotificationConstructor(vararg parameterTypes: Class<*>) {
            runCatching {
                hookConstructorAfter(
                    findConstructor(
                        "com.android.bluetooth.ble.app.MiuiBluetoothNotification",
                        *parameterTypes,
                    )
                ) {
                    val context = args.filterIsInstance<Context>().firstOrNull()
                        ?: runCatching { getObjectField(instance, "mContext") as? Context }.getOrNull()
                        ?: return@hookConstructorAfter
                    registerNotificationReceiver(context)
                }
            }.onFailure {
                Log.d("HyperPods", "MiuiBluetoothNotification constructor unavailable: ${parameterTypes.joinToString { type -> type.name }}")
            }
        }

        hookNotificationConstructor(Context::class.java, Looper::class.java)
        runCatching {
            hookNotificationConstructor(
                Looper::class.java,
                findClass("com.android.bluetooth.ble.app.headset.BluetoothHeadsetService"),
            )
        }
    }

    private fun Int.floorMod(divisor: Int): Int = ((this % divisor) + divisor) % divisor

    private fun batteryShape(battery: BatteryParams): Triple<Boolean, Boolean, Boolean> = Triple(
        battery.left?.isConnected == true,
        battery.right?.isConnected == true,
        battery.case?.isConnected == true,
    )
}
