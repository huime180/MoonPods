package moe.huime.moonpods.ui

import moe.huime.moonpods.ui.pages.GesturePage

import moe.huime.moonpods.ui.MoondropControls

import android.os.Bundle

import android.annotation.SuppressLint
import android.app.Activity
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.ui.NavDisplay
import moe.huime.moonpods.MoonPodsApp
import moe.huime.moonpods.R
import moe.huime.moonpods.config.ConfigManager
import moe.huime.moonpods.config.PodImagePrefs
import moe.huime.moonpods.config.PodImageResource
import moe.huime.moonpods.pods.NoiseControlMode
import moe.huime.moonpods.pods.EqDevicePreset
import moe.huime.moonpods.pods.WearState
import moe.huime.moonpods.pods.WearStatus
import moe.huime.moonpods.pods.detectDeviceCapabilities
import moe.huime.moonpods.ui.pages.AboutPage
import moe.huime.moonpods.ui.pages.EqualizerPage
import moe.huime.moonpods.ui.pages.RfcommDebugPage
import moe.huime.moonpods.ui.pages.ThemeSettingsPage
import moe.huime.moonpods.utils.MoondropImageAutoImport
import moe.huime.moonpods.utils.RootManager
import moe.huime.moonpods.utils.miuiStrongToast.data.BatteryParams
import moe.huime.moonpods.utils.miuiStrongToast.data.batteryStatusCompat
import moe.huime.moonpods.utils.miuiStrongToast.data.MoonPodsAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** 与「设置 → 蓝牙 → 点设备」同一张设备详情页（Settings 内部 action）。 */
private const val ACTION_BLUETOOTH_DEVICE_DETAIL_SETTINGS = "android.settings.BLUETOOTH_DEVICE_DETAIL_SETTINGS"
private const val EXTRA_BLUETOOTH_DEVICE = "android.bluetooth.device.extra.DEVICE"
private const val EXTRA_BLUETOOTH_ADDRESS = "bluetoothaddress"

sealed interface Screen : NavKey {
    data object Main : Screen
    data object About : Screen
    data object Theme : Screen
    data object Equalizer : Screen
    data object RfcommDebug : Screen
    data object Gesture : Screen
}

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun MainUI(
    backStack: SnapshotStateList<Screen>,
    themeMode: MutableState<Int> = mutableStateOf(0),
    onThemeModeChange: (Int) -> Unit = {},
    accentMode: MutableState<Int> = mutableStateOf(0),
    onAccentModeChange: (Int) -> Unit = {},
    floatingBottomBar: MutableState<Boolean> = mutableStateOf(false),
    onFloatingBottomBarChange: (Boolean) -> Unit = {},
    blurBottomBar: MutableState<Boolean> = mutableStateOf(false),
    onBlurBottomBarChange: (Boolean) -> Unit = {},
    appLanguage: MutableState<Int> = mutableStateOf(AppLocale.SYSTEM),
    onAppLanguageChange: (Int) -> Unit = {},
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val mainTitle = remember { mutableStateOf("") }
    val batteryParams = remember { mutableStateOf(BatteryParams()) }
    val wearStatus = remember { mutableStateOf(WearStatus()) }
    val ancMode = remember { mutableStateOf(NoiseControlMode.OFF) }
    /** Smart-mode current auto-applied NC level (LIGHT/MEDIUM/DEEP), or null. */
    val smartAncLevel = remember { mutableStateOf<NoiseControlMode?>(null) }
    val hookConnected = remember { mutableStateOf(false) }
    // 水月雨线：协议栈跑在被 hook 的蓝牙进程里，应用侧只收广播做镜像
    // （与 hookConnected 那一路同一形态，只是 action 与状态项不同）。
    val moondropConnected = remember { mutableStateOf(false) }
    val moondropBattery = remember { mutableStateOf(BatteryParams()) }
    val moondropAncIndex = remember { mutableStateOf(-1) }
    val moondropAncIds = remember { mutableStateOf<List<String>>(emptyList()) }
    val moondropModelName = remember { mutableStateOf("") }
    // 能力包：决定耳机页上显示哪些水月雨功能项（显示项随耳机切换）
    val moondropCaps = remember { mutableStateOf<Bundle?>(null) }
    val moondropGainIndex = remember { mutableStateOf(0) }
    val moondropGainLabels = remember { mutableStateOf<List<String>>(emptyList()) }
    val moondropLedOn = remember { mutableStateOf(false) }
    val moondropPromptToneOn = remember { mutableStateOf(false) }
    val moondropPromptVolumeRaw = remember { mutableStateOf(0) }
    val moondropLhdcOn = remember { mutableStateOf(false) }
    val moondropDualConnectionOn = remember { mutableStateOf(false) }
    // 空间音频 / 头部追踪（GAIA feature 18）：未读到时按「关」保守呈现，不猜成已开启
    val moondropSpatialOn = remember { mutableStateOf(false) }
    val moondropHeadTrackingOn = remember { mutableStateOf(false) }
    // 手势配置：5 字节，每字节高 4 位左耳、低 4 位右耳；null = 还没读到
    val moondropGestureConf = remember { mutableStateOf<IntArray?>(null) }
    // 系统实际协商到的 A2DP 编码（由蓝牙进程读出来广播过来）；空 = 未知，界面不显示这一行
    val moondropActiveCodec = remember { mutableStateOf("") }
    val gameMode = remember { mutableStateOf(false) }
    val transparencyVocalEnhancement = remember { mutableStateOf(false) }
    val dualDeviceConnection = remember { mutableStateOf(false) }
    val tabs = remember { MainTab.entries.toList() }
    var selectedTab by remember { mutableStateOf(MainTab.Module) }
    var hasAppliedDefaultTab by remember { mutableStateOf(false) }
    var hasAutoOpenedConnectedDevice by remember { mutableStateOf(false) }
    var bluetoothState by remember { mutableStateOf(readBluetoothState(context)) }
    var xposedService by remember { mutableStateOf(MoonPodsApp.xposedService) }
    var showDevicePicker by remember { mutableStateOf(false) }
    var showRestartScopeDialog by remember { mutableStateOf(false) }
    var restartingScopes by remember { mutableStateOf(false) }
    var connectingDeviceAddress by remember { mutableStateOf<String?>(null) }
    var connectedDeviceAddress by remember { mutableStateOf("") }
    var showConnectErrorDialog by remember { mutableStateOf(false) }
    var hookConnectionState by remember { mutableStateOf("disconnected") }
    var pendingOpenEarphonesAfterPickerLoaded by remember { mutableStateOf(false) }
    var lastBluetoothServiceAliveMs by remember { mutableStateOf(0L) }
    var bluetoothServiceResponsive by remember { mutableStateOf(false) }
    val backgroundColor = appBackground()
    val overlayBottomBar = floatingBottomBar.value || blurBottomBar.value
    val pageBottomContentPadding = if (overlayBottomBar) 104.dp else 28.dp
    val backdrop = if (blurBottomBar.value) {
        rememberLayerBackdrop {
            drawRect(backgroundColor)
            drawContent()
        }
    } else {
        null
    }

    // Auto game mode preference (persisted)
    val prefs = remember { context.getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE) }
    val appConfig = remember { ConfigManager.refreshFromPrefs(prefs) }
    val autoGameMode = remember { mutableStateOf(appConfig.autoGameMode) }
    val focusNotification = remember { mutableStateOf(appConfig.focusNotification) }
    val workMode = remember { mutableStateOf(appConfig.workMode) }
    val milinkCardFeatures = remember { mutableStateOf(appConfig.milinkCardFeatures) }
    val notificationClickAction = remember { mutableStateOf(appConfig.notificationClickAction) }
    val moreClickAction = remember { mutableStateOf(appConfig.moreClickAction) }
    val desktopIconHidden = remember { mutableStateOf(isLauncherIconHidden(context)) }
    val logLevel = remember { mutableStateOf(appConfig.logLevel) }
    val islandMode = remember { mutableStateOf(appConfig.islandMode) }
    val islandShowTimings = remember { mutableStateOf(appConfig.islandShowTimings) }
    val spatialAudioMode = remember { mutableStateOf(prefs.getInt("spatial_audio_mode", ConfigManager.SPATIAL_AUDIO_OFF)) }
    val eqPreset = remember { mutableStateOf(-1) }
    val eqDevicePresets = remember { mutableStateOf<List<EqDevicePreset>>(emptyList()) }
    val earphonePrefs = remember { mutableStateOf(PodImagePrefs.load(prefs)) }
    val productId = remember { mutableStateOf<String?>(null) }

    /** 读水月雨能力包里的一个开关（没探测到就不显示对应控件）。 */
    fun moondropCap(key: String): Boolean = moondropCaps.value?.getBoolean(key) ?: false

    /** 水月雨档位标识 → 界面降噪模式（抗风噪/人声增强按通透处理，语义最接近）。 */
    fun moondropUiModeOf(ancId: String?): NoiseControlMode = when (ancId) {
        "anc" -> NoiseControlMode.NOISE_CANCELLATION
        "transparent", "live", "anti_wind" -> NoiseControlMode.TRANSPARENCY
        "adaptive" -> NoiseControlMode.ADAPTIVE
        else -> NoiseControlMode.OFF
    }

    /** 水月雨的命令统一以广播发回协议栈所在的进程（com.android.bluetooth）。 */
    fun moondropSend(action: String, configure: (Intent) -> Unit = {}) {
        context.sendBroadcast(Intent(action).apply {
            setPackage(if (moe.huime.moonpods.config.ConfigManager.workMode() == moe.huime.moonpods.config.ConfigManager.WORK_MODE_NOROOT) moe.huime.moonpods.BuildConfig.APPLICATION_ID else "com.android.bluetooth")
            configure(this)
        })
    }

    /** 耳机页的水月雨功能项：能力位、当前值与命令回调打包成一个载体透传给详情页。 */
    val moondropControls = MoondropControls(
        connected = moondropConnected.value,
        caps = moondropCaps.value,
        activeCodec = moondropActiveCodec.value,
        ancIds = moondropAncIds.value,
        ancIndex = moondropAncIndex.value,
        onAncChange = { index ->
            // 线上传的是档位下标（不是 OPPO 的 1/2/3/4 语义），直接回传下标
            moondropSend(MoonPodsAction.ANC_SELECT) {
                it.putExtra(MoonPodsAction.EXTRA_STATUS, index)
            }
            moondropAncIndex.value = index
        },
        gainLabels = moondropGainLabels.value,
        gainIndex = moondropGainIndex.value,
        onGainChange = { index ->
            moondropSend(MoonPodsAction.GAIN_SELECT) {
                it.putExtra(MoonPodsAction.EXTRA_STATUS, index)
            }
        },
        ledOn = moondropLedOn.value,
        onLedChange = { on ->
            moondropSend(MoonPodsAction.LED_SELECT) { it.putExtra(MoonPodsAction.EXTRA_ENABLED, on) }
        },
        promptToneOn = moondropPromptToneOn.value,
        onPromptToneChange = { on ->
            moondropSend(MoonPodsAction.PROMPT_TONE_SELECT) {
                it.putExtra(MoonPodsAction.EXTRA_ENABLED, on)
            }
        },
        promptVolumePercent = moondropPromptVolumeRaw.value.coerceIn(0, 100),
        onPromptVolumeChange = { percent ->
            // 设备侧直接收 0..100 的原始百分比，界面不再折算成「档」
            moondropSend(MoonPodsAction.PROMPT_VOLUME_SELECT) {
                it.putExtra(MoonPodsAction.EXTRA_PROMPT_VOLUME_RAW, percent.coerceIn(0, 100))
            }
        },
        lhdcOn = moondropLhdcOn.value,
        onLhdcChange = { on ->
            moondropSend(MoonPodsAction.LHDC_SELECT) { it.putExtra(MoonPodsAction.EXTRA_ENABLED, on) }
        },
        dualConnectionOn = moondropDualConnectionOn.value,
        onDualConnectionChange = { on ->
            moondropSend(MoonPodsAction.DUAL_CONNECTION_SELECT) {
                it.putExtra(MoonPodsAction.EXTRA_ENABLED, on)
            }
        },
        // 空间音频与头部追踪都**不乐观更新**：只把选择发给蓝牙进程，等它回读后广播 CHANGED 回灌
        spatialOn = moondropSpatialOn.value,
        onSpatialChange = { on ->
            moondropSend(MoonPodsAction.SPATIAL_AUDIO_SELECT) {
                it.putExtra(MoonPodsAction.EXTRA_ENABLED, on)
            }
        },
        headTrackingOn = moondropHeadTrackingOn.value,
        onHeadTrackingChange = { on ->
            moondropSend(MoonPodsAction.HEAD_TRACKING_SELECT) {
                it.putExtra(MoonPodsAction.EXTRA_ENABLED, on)
            }
        },
        onOpenGesture = { backStack.add(Screen.Gesture) }
    )

    /** 反向：界面模式 → 档位下标（找不到返回 -1，调用方不发命令）。 */
    fun moondropAncIndexOf(mode: NoiseControlMode): Int =
        moondropAncIds.value.indexOfFirst { moondropUiModeOf(it) == mode }

    val canShowDetailPage = hookConnected.value || moondropConnected.value
    val showEarphoneDetail = canShowDetailPage && !showDevicePicker
    val displayBattery = if (moondropConnected.value) moondropBattery.value else batteryParams.value
    val displayWearStatus = wearStatus.value
    val displayAnc = if (moondropConnected.value) {
        moondropUiModeOf(moondropAncIds.value.getOrNull(moondropAncIndex.value))
    } else {
        ancMode.value
    }
    val displayGameMode = gameMode.value
    val displayTransparencyVocalEnhancement = transparencyVocalEnhancement.value
    val displayDualDeviceConnection = dualDeviceConnection.value
    val displayTitle = mainTitle.value.takeIf { it.isNotBlank() && hookConnected.value } ?: mainTitle.value
    val displayCapabilities = detectDeviceCapabilities(
        context = context,
        deviceName = displayTitle,
        productId = productId.value,
    )

    LaunchedEffect(displayTitle, displayCapabilities) {
        Log.i(
            "HyperPods",
            "capability check: deviceName='$displayTitle', adaptive=${displayCapabilities.adaptiveSupported}, spatial=${displayCapabilities.spatialAudioSupported}, spatialSoundSwitch=${displayCapabilities.spatialSoundSwitchSupported}"
        )
    }

    LaunchedEffect(displayTitle) {
        if (displayTitle.isNotEmpty()) {
            mainTitle.value = displayTitle
        }
    }

    LaunchedEffect(canShowDetailPage) {
        if (!hasAppliedDefaultTab) {
            selectedTab = if (canShowDetailPage) MainTab.Earphones else MainTab.Module
            hasAppliedDefaultTab = true
        }
    }

    LaunchedEffect(hookConnectionState) {
        if (hookConnectionState == "error") {
            connectingDeviceAddress = null
            pendingOpenEarphonesAfterPickerLoaded = false
            showConnectErrorDialog = true
            showDevicePicker = true
        }
    }

    // 兜底：模块报告已连接时，清掉「正在连接」标记（广播丢失/进程重启也不会一直转圈）
    LaunchedEffect(hookConnected.value) {
        if (hookConnected.value) connectingDeviceAddress = null
    }

    LaunchedEffect(pendingOpenEarphonesAfterPickerLoaded, connectingDeviceAddress, hookConnected.value) {
        if (pendingOpenEarphonesAfterPickerLoaded && connectingDeviceAddress == null && hookConnected.value) {
            withFrameNanos { }
            pendingOpenEarphonesAfterPickerLoaded = false
            showDevicePicker = false
        }
    }

    val broadcastReceiver = remember {
        object : BroadcastReceiver() {
            override fun onReceive(p0: Context?, p1: Intent?) {
                when (p1?.action) {
                    // ---- 水月雨线（协议栈在被 hook 的蓝牙进程里，应用侧只做镜像）----

                    MoonPodsAction.PODS_CONNECTED -> {
                        moondropConnected.value = true
                        val name = p1.getStringExtra(MoonPodsAction.EXTRA_DEVICE_NAME)
                        moondropModelName.value = name.orEmpty()
                        mainTitle.value = name ?: ""
                        connectedDeviceAddress = p1.getStringExtra(MoonPodsAction.EXTRA_MAC)
                            ?: connectedDeviceAddress
                        Log.i("HyperPods", "moondrop pod connected: $name")
                    }

                    MoonPodsAction.PODS_DISCONNECTED -> {
                        moondropConnected.value = false
                        moondropBattery.value = BatteryParams()
                        moondropAncIndex.value = -1
                        moondropAncIds.value = emptyList()
                        moondropCaps.value = null
                        moondropActiveCodec.value = ""
                        mainTitle.value = ""
                    }

                    MoonPodsAction.BATTERY_CHANGED -> {
                        p1.batteryStatusCompat()?.let { moondropBattery.value = it }
                    }

                    MoonPodsAction.ANC_CHANGED -> {
                        moondropAncIndex.value = p1.getIntExtra(MoonPodsAction.EXTRA_STATUS, -1)
                        p1.getStringArrayListExtra(MoonPodsAction.EXTRA_ANC_IDS)?.let {
                            moondropAncIds.value = it
                        }
                    }

                    MoonPodsAction.GAIN_CHANGED ->
                        moondropGainIndex.value =
                            p1.getIntExtra(MoonPodsAction.EXTRA_STATUS, 0).coerceAtLeast(0)

                    MoonPodsAction.LED_CHANGED ->
                        moondropLedOn.value = p1.getBooleanExtra(MoonPodsAction.EXTRA_ENABLED, false)

                    MoonPodsAction.PROMPT_TONE_CHANGED ->
                        moondropPromptToneOn.value =
                            p1.getBooleanExtra(MoonPodsAction.EXTRA_ENABLED, false)

                    MoonPodsAction.PROMPT_VOLUME_CHANGED ->
                        moondropPromptVolumeRaw.value =
                            p1.getIntExtra(MoonPodsAction.EXTRA_PROMPT_VOLUME_RAW, 0).coerceAtLeast(0)

                    MoonPodsAction.LHDC_CHANGED ->
                        moondropLhdcOn.value = p1.getBooleanExtra(MoonPodsAction.EXTRA_ENABLED, false)

                    MoonPodsAction.DUAL_CONNECTION_CHANGED ->
                        moondropDualConnectionOn.value =
                            p1.getBooleanExtra(MoonPodsAction.EXTRA_ENABLED, false)

                    MoonPodsAction.SPATIAL_AUDIO_CHANGED ->
                        moondropSpatialOn.value =
                            p1.getBooleanExtra(MoonPodsAction.EXTRA_ENABLED, false)

                    MoonPodsAction.HEAD_TRACKING_CHANGED ->
                        moondropHeadTrackingOn.value =
                            p1.getBooleanExtra(MoonPodsAction.EXTRA_ENABLED, false)

                    MoonPodsAction.GESTURE_CHANGED -> {
                        // 内部按字节保存；界面按半字节取某一耳
                        p1.getByteArrayExtra(MoonPodsAction.EXTRA_GESTURE_PAYLOAD)?.let { bytes ->
                            moondropGestureConf.value = IntArray(bytes.size) { i ->
                                bytes[i].toInt() and 0xFF
                            }
                        }
                    }

                    MoonPodsAction.CODEC_CHANGED ->
                        moondropActiveCodec.value =
                            p1.getStringExtra(MoonPodsAction.EXTRA_CODEC).orEmpty()

                    MoonPodsAction.CAPABILITIES_CHANGED -> {
                        moondropModelName.value =
                            p1.getStringExtra(MoonPodsAction.EXTRA_MODEL_NAME).orEmpty()
                        val caps = p1.getBundleExtra(MoonPodsAction.EXTRA_CAPS_BUNDLE)
                        moondropCaps.value = caps
                        moondropGainLabels.value = caps?.getStringArrayList("gainLabels") ?: emptyList()
                    }

                    MoonPodsAction.ACTION_PODS_ANC_CHANGED -> {
                        connectedDeviceAddress = p1.getStringExtra("address") ?: connectedDeviceAddress
                        val status = p1.getIntExtra("status", 1)
                        ancMode.value = when (status) {
                            1 -> NoiseControlMode.OFF
                            2 -> NoiseControlMode.NOISE_CANCELLATION
                            3 -> NoiseControlMode.TRANSPARENCY
                            4 -> NoiseControlMode.ADAPTIVE
                            5 -> NoiseControlMode.NOISE_CANCELLATION_SMART
                            6 -> NoiseControlMode.NOISE_CANCELLATION_LIGHT
                            7 -> NoiseControlMode.NOISE_CANCELLATION_MEDIUM
                            8 -> NoiseControlMode.NOISE_CANCELLATION_DEEP
                            else -> NoiseControlMode.OFF
                        }
                    }

                    MoonPodsAction.ACTION_PODS_SMART_ANC_LEVEL_CHANGED -> {
                        val ord = p1.getIntExtra("ordinal", -1)
                        smartAncLevel.value = NoiseControlMode.entries.getOrNull(ord)
                    }

                    MoonPodsAction.ACTION_PODS_BATTERY_CHANGED -> {
                        connectedDeviceAddress = p1.getStringExtra("address") ?: connectedDeviceAddress
                        batteryParams.value =
                            p1.getParcelableExtra("status", BatteryParams::class.java)!!
                    }

                    MoonPodsAction.ACTION_PODS_WEAR_STATUS_CHANGED -> {
                        connectedDeviceAddress = p1.getStringExtra("address") ?: connectedDeviceAddress
                        wearStatus.value = WearStatus(
                            left = wearStateFromExtra(p1.getIntExtra("left_wear_status", -1)),
                            right = wearStateFromExtra(p1.getIntExtra("right_wear_status", -1)),
                            case = wearStateFromExtra(p1.getIntExtra("case_wear_status", -1))
                        )
                    }

                    MoonPodsAction.ACTION_PODS_GAME_MODE_CHANGED -> {
                        gameMode.value = p1.getBooleanExtra("enabled", false)
                    }

                    MoonPodsAction.ACTION_PODS_TRANSPARENCY_VOCAL_ENHANCEMENT_CHANGED -> {
                        transparencyVocalEnhancement.value = p1.getBooleanExtra("enabled", false)
                    }

                    MoonPodsAction.ACTION_PODS_SPATIAL_AUDIO_CHANGED -> {
                        spatialAudioMode.value = p1.getIntExtra("mode", ConfigManager.SPATIAL_AUDIO_OFF)
                    }

                    MoonPodsAction.ACTION_PODS_EQ_PRESET_CHANGED -> {
                        eqPreset.value = p1.getIntExtra("preset", -1)
                        eqDevicePresets.value = runCatching {
                            Json.decodeFromString(
                                ListSerializer(EqDevicePreset.serializer()),
                                p1.getStringExtra(MoonPodsAction.EXTRA_EQ_ENTRIES_JSON).orEmpty(),
                            )
                        }.getOrDefault(emptyList())
                    }

                    MoonPodsAction.ACTION_PODS_DUAL_DEVICE_CONNECTION_CHANGED -> {
                        dualDeviceConnection.value = p1.getBooleanExtra("enabled", false)
                    }

                    MoonPodsAction.ACTION_PODS_CONNECTED -> {
                        val deviceName = p1.getStringExtra("device_name")
                        val shouldOpenEarphones = connectingDeviceAddress != null || !hasAutoOpenedConnectedDevice
                        connectedDeviceAddress = p1.getStringExtra("address") ?: connectedDeviceAddress
                        productId.value = p1.getStringExtra("product_id") ?: productId.value
                        connectingDeviceAddress = null
                        mainTitle.value = deviceName ?: ""
                        earphonePrefs.value = PodImagePrefs.upsertConnected(
                            prefs = prefs,
                            service = xposedService,
                            address = connectedDeviceAddress,
                            name = deviceName.orEmpty(),
                        )
                        hookConnected.value = true
                        hookConnectionState = "connected"
                        if (shouldOpenEarphones) {
                            selectedTab = MainTab.Earphones
                            hasAppliedDefaultTab = true
                            hasAutoOpenedConnectedDevice = true
                            showDevicePicker = false
                            pendingOpenEarphonesAfterPickerLoaded = false
                        }
                        Log.i("HyperPods", "pod connected via hook: $deviceName")
                    }

                    MoonPodsAction.ACTION_PODS_CONNECTION_STATE_CHANGED -> {
                        hookConnectionState = p1.getStringExtra("state") ?: hookConnectionState
                        if (hookConnectionState == "disconnected") {
                            connectedDeviceAddress = ""
                            productId.value = null
                            mainTitle.value = ""
                            hookConnected.value = false
                            hasAutoOpenedConnectedDevice = false
                        } else if (hookConnected.value) {
                            connectedDeviceAddress = p1.getStringExtra("address") ?: connectedDeviceAddress
                            p1.getStringExtra("device_name")?.let {
                                mainTitle.value = it
                                earphonePrefs.value = PodImagePrefs.upsertConnected(prefs, xposedService, connectedDeviceAddress, it)
                            }
                        }
                    }

                    MoonPodsAction.ACTION_PODS_DISCONNECTED -> {
                        mainTitle.value = ""
                        connectedDeviceAddress = ""
                        productId.value = null
                        hookConnectionState = "disconnected"
                        hookConnected.value = false
                        hasAutoOpenedConnectedDevice = false
                    }

                    MoonPodsAction.ACTION_MODULE_BLUETOOTH_SERVICE_ALIVE -> {
                        lastBluetoothServiceAliveMs = SystemClock.elapsedRealtime()
                        bluetoothServiceResponsive = true
                    }

                    BluetoothAdapter.ACTION_STATE_CHANGED,
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        bluetoothState = readBluetoothState(context)
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        val serviceListener: (io.github.libxposed.service.XposedService?) -> Unit = { service ->
            xposedService = service
        }
        MoonPodsApp.addServiceListener(serviceListener)

        context.registerReceiver(broadcastReceiver, IntentFilter().apply {
            addAction(MoonPodsAction.ACTION_PODS_ANC_CHANGED)
            addAction(MoonPodsAction.ACTION_PODS_SMART_ANC_LEVEL_CHANGED)
            addAction(MoonPodsAction.ACTION_PODS_BATTERY_CHANGED)
            addAction(MoonPodsAction.ACTION_PODS_WEAR_STATUS_CHANGED)
            addAction(MoonPodsAction.ACTION_PODS_GAME_MODE_CHANGED)
            addAction(MoonPodsAction.ACTION_PODS_TRANSPARENCY_VOCAL_ENHANCEMENT_CHANGED)
            addAction(MoonPodsAction.ACTION_PODS_SPATIAL_AUDIO_CHANGED)
            addAction(MoonPodsAction.ACTION_PODS_EQ_PRESET_CHANGED)
            addAction(MoonPodsAction.ACTION_PODS_DUAL_DEVICE_CONNECTION_CHANGED)
            addAction(MoonPodsAction.ACTION_PODS_CONNECTED)
            addAction(MoonPodsAction.ACTION_PODS_CONNECTION_STATE_CHANGED)
            addAction(MoonPodsAction.ACTION_PODS_DISCONNECTED)
            addAction(MoonPodsAction.ACTION_MODULE_BLUETOOTH_SERVICE_ALIVE)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        
            addAction(MoonPodsAction.PODS_CONNECTED)
            addAction(MoonPodsAction.PODS_DISCONNECTED)
            addAction(MoonPodsAction.BATTERY_CHANGED)
            addAction(MoonPodsAction.ANC_CHANGED)
            addAction(MoonPodsAction.GAIN_CHANGED)
            addAction(MoonPodsAction.LED_CHANGED)
            addAction(MoonPodsAction.PROMPT_TONE_CHANGED)
            addAction(MoonPodsAction.PROMPT_VOLUME_CHANGED)
            addAction(MoonPodsAction.LHDC_CHANGED)
            addAction(MoonPodsAction.DUAL_CONNECTION_CHANGED)
            addAction(MoonPodsAction.SPATIAL_AUDIO_CHANGED)
            addAction(MoonPodsAction.HEAD_TRACKING_CHANGED)
            addAction(MoonPodsAction.GESTURE_CHANGED)
            addAction(MoonPodsAction.CODEC_CHANGED)
            addAction(MoonPodsAction.CAPABILITIES_CHANGED)
        }, Context.RECEIVER_EXPORTED)

        sendBluetoothModuleBroadcast(context, MoonPodsAction.ACTION_PODS_UI_INIT)

        // 水月雨线也要问一次（见 MoondropController.handleUIEvent 的 UI_INIT 分支）：
        // 只发 OPPO 那条 ACTION_PODS_UI_INIT 时，水月雨这边的 refreshAll() 从来没被触发过。
        sendBluetoothModuleBroadcast(context, MoonPodsAction.UI_INIT)

        onDispose {
            sendBluetoothModuleBroadcast(context, MoonPodsAction.ACTION_PODS_UI_CLOSED)
            try {
                context.unregisterReceiver(broadcastReceiver)
            } catch (_: Exception) {}
            MoonPodsApp.removeServiceListener(serviceListener)
        }
    }

    LaunchedEffect(selectedTab, hookConnected.value) {
        sendBluetoothModuleBroadcast(context, MoonPodsAction.ACTION_PODS_UI_INIT)

        // 水月雨线也要问一次（见 MoondropController.handleUIEvent 的 UI_INIT 分支）：
        // 只发 OPPO 那条 ACTION_PODS_UI_INIT 时，水月雨这边的 refreshAll() 从来没被触发过。
        sendBluetoothModuleBroadcast(context, MoonPodsAction.UI_INIT)
        if (selectedTab == MainTab.Module || hookConnected.value) {
            sendBluetoothModuleBroadcast(context, MoonPodsAction.ACTION_REFRESH_STATUS)
        }
    }

    // 蓝牙服务存活状态：只在收到心跳广播时评估，不做前台轮询
    LaunchedEffect(lastBluetoothServiceAliveMs) {
        bluetoothServiceResponsive = lastBluetoothServiceAliveMs > 0L &&
                SystemClock.elapsedRealtime() - lastBluetoothServiceAliveMs <= 75_000L
    }

    fun setAncMode(mode: NoiseControlMode) {
        ancMode.value = mode
        val status = when (mode) {
            NoiseControlMode.OFF -> 1
            NoiseControlMode.NOISE_CANCELLATION -> 2
            NoiseControlMode.TRANSPARENCY -> 3
            NoiseControlMode.ADAPTIVE -> 4
            NoiseControlMode.NOISE_CANCELLATION_SMART -> 5
            NoiseControlMode.NOISE_CANCELLATION_LIGHT -> 6
            NoiseControlMode.NOISE_CANCELLATION_MEDIUM -> 7
            NoiseControlMode.NOISE_CANCELLATION_DEEP -> 8
        }
        Intent(MoonPodsAction.ACTION_ANC_SELECT).apply {
            this.putExtra("status", status)
            setPackage(if (moe.huime.moonpods.config.ConfigManager.workMode() == moe.huime.moonpods.config.ConfigManager.WORK_MODE_NOROOT) moe.huime.moonpods.BuildConfig.APPLICATION_ID else "com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendBroadcast(this)
        }
    }

    fun setGameMode(enabled: Boolean) {
        gameMode.value = enabled
        Intent(MoonPodsAction.ACTION_GAME_MODE_SET).apply {
            this.putExtra("enabled", enabled)
            setPackage(if (moe.huime.moonpods.config.ConfigManager.workMode() == moe.huime.moonpods.config.ConfigManager.WORK_MODE_NOROOT) moe.huime.moonpods.BuildConfig.APPLICATION_ID else "com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendBroadcast(this)
        }
    }

    fun setTransparencyVocalEnhancement(enabled: Boolean) {
        transparencyVocalEnhancement.value = enabled
        Intent(MoonPodsAction.ACTION_TRANSPARENCY_VOCAL_ENHANCEMENT_SET).apply {
            this.putExtra("enabled", enabled)
            setPackage(if (moe.huime.moonpods.config.ConfigManager.workMode() == moe.huime.moonpods.config.ConfigManager.WORK_MODE_NOROOT) moe.huime.moonpods.BuildConfig.APPLICATION_ID else "com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendBroadcast(this)
        }
    }

    fun clearPodConnectionState() {
        connectingDeviceAddress = null
        pendingOpenEarphonesAfterPickerLoaded = false
        connectedDeviceAddress = ""
        mainTitle.value = ""
        batteryParams.value = BatteryParams()
        wearStatus.value = WearStatus()
        ancMode.value = NoiseControlMode.OFF
        hookConnected.value = false
        hookConnectionState = "disconnected"
        showConnectErrorDialog = false
        showDevicePicker = true
        selectedTab = MainTab.Earphones
    }

    fun onDeviceSelected(device: BluetoothDevice) {
        connectingDeviceAddress = device.address
        pendingOpenEarphonesAfterPickerLoaded = false
        showConnectErrorDialog = false
        showDevicePicker = true
        selectedTab = MainTab.Earphones
        hookConnectionState = "connecting"
        if (ConfigManager.workMode() == ConfigManager.WORK_MODE_NOROOT) {
            // 免 root：应用进程自己建链，不走蓝牙进程
            moe.huime.moonpods.noroot.NorootBridge.connect(context, device)
        } else {
            Intent(MoonPodsAction.ACTION_CONNECT_POD_REQUEST).apply {
                putExtra("device", device)
                setPackage(if (moe.huime.moonpods.config.ConfigManager.workMode() == moe.huime.moonpods.config.ConfigManager.WORK_MODE_NOROOT) moe.huime.moonpods.BuildConfig.APPLICATION_ID else "com.android.bluetooth")
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                context.sendBroadcast(this)
            }
        }
    }

    fun setSpatialAudioMode(mode: Int) {
        val normalizedMode = mode.coerceIn(ConfigManager.SPATIAL_AUDIO_OFF, ConfigManager.SPATIAL_AUDIO_HEAD_TRACKING)
        spatialAudioMode.value = normalizedMode
        prefs.edit().putInt("spatial_audio_mode", normalizedMode).apply()
        Intent(MoonPodsAction.ACTION_SPATIAL_AUDIO_SET).apply {
            this.putExtra("mode", normalizedMode)
            setPackage(if (moe.huime.moonpods.config.ConfigManager.workMode() == moe.huime.moonpods.config.ConfigManager.WORK_MODE_NOROOT) moe.huime.moonpods.BuildConfig.APPLICATION_ID else "com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendBroadcast(this)
        }
    }

    fun setEqPreset(preset: Int) {
        eqPreset.value = preset
        Intent(MoonPodsAction.ACTION_EQ_PRESET_SET).apply {
            this.putExtra("preset", preset)
            setPackage(if (moe.huime.moonpods.config.ConfigManager.workMode() == moe.huime.moonpods.config.ConfigManager.WORK_MODE_NOROOT) moe.huime.moonpods.BuildConfig.APPLICATION_ID else "com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendBroadcast(this)
        }
    }

    fun saveEqPreset(
        id: Int,
        name: String,
        frequencies: List<Int>,
        gains: List<Int>,
        minValue: Int,
        maxValue: Int,
    ) {
        Intent(MoonPodsAction.ACTION_EQ_PRESET_SAVE).apply {
            putExtra("id", id)
            putExtra("name", name)
            putIntegerArrayListExtra("frequencies", ArrayList(frequencies))
            putIntegerArrayListExtra("gains", ArrayList(gains))
            putExtra("min_value", minValue)
            putExtra("max_value", maxValue)
            setPackage(if (moe.huime.moonpods.config.ConfigManager.workMode() == moe.huime.moonpods.config.ConfigManager.WORK_MODE_NOROOT) moe.huime.moonpods.BuildConfig.APPLICATION_ID else "com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendBroadcast(this)
        }
    }

    fun deleteEqPreset(entry: EqDevicePreset) {
        Intent(MoonPodsAction.ACTION_EQ_PRESET_DELETE).apply {
            putExtra(
                MoonPodsAction.EXTRA_EQ_ENTRIES_JSON,
                Json.encodeToString(ListSerializer(EqDevicePreset.serializer()), listOf(entry)),
            )
            setPackage(if (moe.huime.moonpods.config.ConfigManager.workMode() == moe.huime.moonpods.config.ConfigManager.WORK_MODE_NOROOT) moe.huime.moonpods.BuildConfig.APPLICATION_ID else "com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendBroadcast(this)
        }
    }

    fun setDualDeviceConnection(enabled: Boolean) {
        dualDeviceConnection.value = enabled
        Intent(MoonPodsAction.ACTION_DUAL_DEVICE_CONNECTION_SET).apply {
            this.putExtra("enabled", enabled)
            setPackage(if (moe.huime.moonpods.config.ConfigManager.workMode() == moe.huime.moonpods.config.ConfigManager.WORK_MODE_NOROOT) moe.huime.moonpods.BuildConfig.APPLICATION_ID else "com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendBroadcast(this)
        }
    }

    fun onDeviceDisconnect(device: BluetoothDevice) {
        connectingDeviceAddress = null
        pendingOpenEarphonesAfterPickerLoaded = false
        if (device.address == connectedDeviceAddress) {
            hookConnected.value = false
            hookConnectionState = "disconnected"
            connectedDeviceAddress = ""
            mainTitle.value = ""
        }
        Intent(MoonPodsAction.ACTION_DISCONNECT_POD_REQUEST).apply {
            putExtra("device", device)
            setPackage(if (moe.huime.moonpods.config.ConfigManager.workMode() == moe.huime.moonpods.config.ConfigManager.WORK_MODE_NOROOT) moe.huime.moonpods.BuildConfig.APPLICATION_ID else "com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendBroadcast(this)
        }
    }

    fun onConnectedDeviceClick() {
        if (connectedDeviceAddress.isBlank() && mainTitle.value.isBlank()) return
        pendingOpenEarphonesAfterPickerLoaded = false
        hookConnected.value = true
        hookConnectionState = "connected"
        showDevicePicker = false
        selectedTab = MainTab.Earphones
    }

    fun backToDevicePicker() {
        showDevicePicker = true
    }

    fun openBluetoothSettings() {
        val action = if (bluetoothState.enabled) Settings.ACTION_BLUETOOTH_SETTINGS else BluetoothAdapter.ACTION_REQUEST_ENABLE
        Intent(action).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(this) }
                .onFailure { Toast.makeText(context, R.string.connect_failed, Toast.LENGTH_SHORT).show() }
        }
    }

    fun openDevicePicker() {
        showDevicePicker = true
        selectedTab = MainTab.Earphones
    }

    @SuppressLint("MissingPermission")
    fun openSystemHeadsetSettings() {
        val address = connectedDeviceAddress
        if (address.isBlank()) {
            Toast.makeText(context, R.string.connect_failed, Toast.LENGTH_SHORT).show()
            return
        }
        // 与「设置 → 蓝牙 → 点设备」落到同一页：直接用 Settings 内部那张设备详情页的 action。
        // 旧实现是硬编码 HyperOS 的高级耳机 Activity 并塞入模块伪造的设备 id，
        // 和系统蓝牙列表点进去的页面不是同一张 —— 已按 HyperPods for Moondrop 里验证过的写法改回。
        val device = runCatching {
            if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(address)
            } else {
                null
            }
        }.getOrNull()
        val opened = runCatching {
            context.startActivity(Intent(ACTION_BLUETOOTH_DEVICE_DETAIL_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (device != null) putExtra(EXTRA_BLUETOOTH_DEVICE, device)
                putExtra(EXTRA_BLUETOOTH_ADDRESS, address)
            })
        }.isSuccess
        if (opened) return
        // 兜底：普通蓝牙设置列表页
        val fallback = runCatching {
            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }.isSuccess
        if (!fallback) {
            Toast.makeText(context, R.string.connect_failed, Toast.LENGTH_SHORT).show()
        }
    }

    fun refreshStatus() {
        if (hookConnected.value) {
            context.sendBroadcast(Intent(MoonPodsAction.ACTION_REFRESH_STATUS).apply {
                setPackage(if (moe.huime.moonpods.config.ConfigManager.workMode() == moe.huime.moonpods.config.ConfigManager.WORK_MODE_NOROOT) moe.huime.moonpods.BuildConfig.APPLICATION_ID else "com.android.bluetooth")
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            })
        }
    }

    fun savePodImages(
        address: String,
        name: String,
        images: Map<PodImageResource, Uri?>,
        clearedImages: Set<PodImageResource>,
    ) {
        earphonePrefs.value = PodImagePrefs.saveImages(context, prefs, xposedService, address, name, images, clearedImages)
    }

    fun savePodImageBytes(address: String, name: String, images: Map<PodImageResource, ByteArray>) {
        earphonePrefs.value = PodImagePrefs.saveImageBytes(context, prefs, xposedService, address, name, images)
    }

    // 水月雨设备的官方图自动导入：连上、且这个地址还没有机型图时，按型号从官方目录导一次，
    // 用户不必自己点右上角。为什么挂在这一层（而不是设备页组合 / entry<Screen.Main> 里）：
    //   · context / prefs / xposedService 就在手边，与 savePodImageBytes 用的是同一套
    //     （不必在设备页里再取一次 SharedPreferences 或另开一条参数链）；
    //   · MainUI 这一层不随底栏切页、二级页进出而销毁 —— 挂在 entry 里的话，用户一进
    //     均衡器/主题页协程就被取消，几张图下载到一半就没了（协程取消会回滚「试过」标记，
    //     但没必要把一次导入拖成两次）。
    // 同一地址一次运行只试一次、只对水月雨设备生效、已有图不覆盖，全部由被调用的对象保证。
    LaunchedEffect(connectedDeviceAddress, mainTitle.value) {
        val address = connectedDeviceAddress
        if (address.isBlank()) return@LaunchedEffect
        MoondropImageAutoImport.importIfNeeded(
            context = context,
            prefs = prefs,
            service = xposedService,
            address = address,
            deviceName = mainTitle.value,
        )?.let { earphonePrefs.value = it }
    }

    fun restartScopes(packages: List<String>) {
        if (packages.isEmpty() || restartingScopes) return
        restartingScopes = true
        coroutineScope.launch {
            val success = withContext(Dispatchers.IO) {
                RootManager.restartPackages(packages)
            }
            restartingScopes = false
            showRestartScopeDialog = false
            if (success && "com.android.bluetooth" in packages) {
                clearPodConnectionState()
            }
            Toast.makeText(
                context,
                if (success) R.string.restart_scope_success else R.string.restart_scope_failed,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    // OPPO 专属设置二级页的写入回调：写盘 + 跨进程通知。
    // 提成局部 val 而不是内联进 entryProvider，是为了让「设置项怎么落盘」只有一处实现。
    val handleWorkModeChange: (Int) -> Unit = { mode ->
        workMode.value = mode
        ConfigManager.updateWorkMode(prefs, xposedService, mode)
        listOf("com.android.bluetooth", "com.xiaomi.bluetooth", "com.milink.service").forEach { pkg ->
            broadcastConfigChanged(context, pkg)
        }
    }

    val handleFocusNotificationChange: (Boolean) -> Unit = { enabled ->
        focusNotification.value = enabled
        // 同一开关同时控制 Root 与免 root：
        //  Root 的 hook 读设置后自行停发/撤掉 MIUI 通知；
        //  免 root 的通知在应用进程，这里立刻生效，不必等下一次状态事件。
        runCatching {
            if (!enabled) {
                moe.huime.moonpods.noroot.NorootNotification.cancel(context)
            } else if (ConfigManager.workMode() == ConfigManager.WORK_MODE_NOROOT) {
                val snap = moe.huime.moonpods.noroot.NorootBridge.snapshot.value
                if (snap?.connected == true) {
                    moe.huime.moonpods.noroot.NorootNotification.postSimple(context, snap)
                }
            }
        }
        ConfigManager.updateFocusNotification(prefs, xposedService, enabled)
        listOf("com.android.bluetooth", "com.xiaomi.bluetooth", "com.milink.service").forEach { pkg ->
            broadcastConfigChanged(context, pkg)
        }
    }

    val entryProvider = entryProvider<Screen> {
        entry<Screen.Main> {
            MainTabsScaffold(
                tabs = tabs,
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it },
                floatingBottomBar = floatingBottomBar.value,
                blurBottomBar = blurBottomBar.value,
                backdrop = backdrop,
                backgroundColor = backgroundColor,
                overlayBottomBar = overlayBottomBar,
                pageBottomContentPadding = pageBottomContentPadding,
                xposedService = xposedService,
                bluetoothServiceResponsive = bluetoothServiceResponsive,
                bluetoothEnabled = bluetoothState.enabled,
                bondedDeviceCount = bluetoothState.bondedCount,
                onBluetoothStatusClick = { openBluetoothSettings() },
                onPairedBluetoothClick = { openDevicePicker() },
                showEarphoneDetail = showEarphoneDetail,
                mainTitle = mainTitle.value,
                displayTitle = displayTitle,
                displayBattery = displayBattery,
                displayWearStatus = displayWearStatus,
                displayAnc = displayAnc,
                onAncModeChange = { setAncMode(it) },
                smartAncLevel = smartAncLevel.value,
                displayTransparencyVocalEnhancement = displayTransparencyVocalEnhancement,
                onTransparencyVocalEnhancementChange = { setTransparencyVocalEnhancement(it) },
                displayGameMode = displayGameMode,
                gameModeSupported = displayCapabilities.gameModeSupported,
                onGameModeChange = { setGameMode(it) },
                spatialAudioMode = spatialAudioMode.value,
                onSpatialAudioModeChange = { setSpatialAudioMode(it) },
                moondrop = moondropControls,
                equalizerVisible = displayCapabilities.eqPresets.isNotEmpty() ||
                    displayCapabilities.customEqSupported,
                dualDeviceSupported = displayCapabilities.dualDeviceSupported,
                onOpenEqualizer = { backStack.add(Screen.Equalizer) },
                displayDualDeviceConnection = displayDualDeviceConnection,
                onDualDeviceConnectionChange = { setDualDeviceConnection(it) },
                spatialAudioSupported = displayCapabilities.spatialAudioSupported,
                spatialSoundSupported = displayCapabilities.spatialSoundSwitchSupported,
                adaptiveModeEnabled = displayCapabilities.adaptiveSupported,
                earphonePrefs = earphonePrefs.value,
                connectedDeviceAddress = connectedDeviceAddress,
                connectingDeviceAddress = connectingDeviceAddress,
                showConnectErrorDialog = showConnectErrorDialog,
                onDeviceSelected = { onDeviceSelected(it) },
                onConnectedDeviceClick = { onConnectedDeviceClick() },
                onDeviceDisconnect = { onDeviceDisconnect(it) },
                onDismissConnectError = { showConnectErrorDialog = false },
                desktopIconHidden = desktopIconHidden,
                workMode = workMode,
                onWorkModeChange = handleWorkModeChange,
                focusNotification = focusNotification,
                onFocusNotificationChange = handleFocusNotificationChange,
                onDesktopIconHiddenChange = {
                    desktopIconHidden.value = it
                    setLauncherIconHidden(context, it)
                },
                logLevel = logLevel,
                onLogLevelChange = {
                    logLevel.value = it
                    ConfigManager.updateLogLevel(prefs, xposedService, it)
                    broadcastConfigChanged(context, "com.android.bluetooth")
                    broadcastConfigChanged(context, "com.milink.service")
                    broadcastConfigChanged(context, "com.xiaomi.bluetooth")
                },
                islandMode = islandMode,
                onIslandModeChange = {
                    islandMode.value = it
                    ConfigManager.updateIslandMode(prefs, xposedService, it)
                    broadcastConfigChanged(context, "com.android.bluetooth")
                    broadcastConfigChanged(context, "com.xiaomi.bluetooth")
                },
                islandShowTimings = islandShowTimings,
                onIslandShowTimingsChange = {
                    islandShowTimings.value = it
                    ConfigManager.updateIslandShowTimings(prefs, xposedService, it)
                    broadcastConfigChanged(context, "com.android.bluetooth")
                },
                appLanguage = appLanguage,
                onAppLanguageChange = {
                    appLanguage.value = it
                    onAppLanguageChange(it)
                },
                notificationClickAction = notificationClickAction,
                onNotificationClickActionChange = {
                    notificationClickAction.value = it
                    ConfigManager.updateNotificationClickAction(prefs, xposedService, it)
                    broadcastConfigChanged(context, "com.xiaomi.bluetooth")
                },
                moreClickAction = moreClickAction,
                onMoreClickActionChange = {
                    moreClickAction.value = it
                    ConfigManager.updateMoreClickAction(prefs, xposedService, it)
                },
                onOpenRfcommDebug = { backStack.add(Screen.RfcommDebug) },
                onOpenTheme = { backStack.add(Screen.Theme) },
                onOpenAbout = { backStack.add(Screen.About) },
                showRestartScopeDialog = showRestartScopeDialog,
                restartingScopes = restartingScopes,
                onShowRestartScopeDialog = { showRestartScopeDialog = true },
                onDismissRestartScopeDialog = { showRestartScopeDialog = false },
                onRestartScopes = { restartScopes(it) },
                onBackToDevicePicker = { backToDevicePicker() },
                onOpenSystemHeadsetSettings = { openSystemHeadsetSettings() },
                onSavePodImages = { address, name, images, clearedImages ->
                    savePodImages(address, name, images, clearedImages)
                },
                onSavePodImageBytes = { address, name, images -> savePodImageBytes(address, name, images) },
            )
        }
        entry<Screen.Gesture> {
            val gestureScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = stringResource(R.string.moondrop_gesture),
                        largeTitle = stringResource(R.string.moondrop_gesture),
                        scrollBehavior = gestureScrollBehavior,
                        navigationIcon = {
                            IconButton(onClick = { backStack.removeLast() }) {
                                Icon(imageVector = MiuixIcons.Back, contentDescription = "Back")
                            }
                        }
                    )
                }
            ) { padding ->
                GesturePage(
                    modifier = Modifier.overScrollVertical(),
                    contentPadding = padding,
                    conf = moondropGestureConf.value,
                    onSelect = { slot, ear, actionId ->
                        moondropSend(MoonPodsAction.GESTURE_SELECT) {
                            it.putExtra(MoonPodsAction.EXTRA_GESTURE_SLOT, slot)
                            it.putExtra(MoonPodsAction.EXTRA_GESTURE_EAR, ear)
                            it.putExtra(MoonPodsAction.EXTRA_STATUS, actionId)
                        }
                    }
                )
            }
        }

        entry<Screen.About> {
            val aboutScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = stringResource(R.string.about),
                        largeTitle = stringResource(R.string.about),
                        scrollBehavior = aboutScrollBehavior,
                        navigationIcon = {
                            IconButton(onClick = { backStack.removeLast() }) {
                                Icon(imageVector = MiuixIcons.Back, contentDescription = "Back")
                            }
                        }
                    )
                }
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(backgroundColor)
                        .padding(padding),
                ) {
                    AboutPage(
                        modifier = Modifier
                            .overScrollVertical()
                            .nestedScroll(aboutScrollBehavior.nestedScrollConnection),
                        contentPadding = PaddingValues(bottom = pageBottomContentPadding),
                    )
                }
            }
        }
        entry<Screen.Theme> {
            val themeScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = stringResource(R.string.theme_title),
                        largeTitle = stringResource(R.string.theme_title),
                        scrollBehavior = themeScrollBehavior,
                        navigationIcon = {
                            IconButton(onClick = { backStack.removeLast() }) {
                                Icon(imageVector = MiuixIcons.Back, contentDescription = "Back")
                            }
                        }
                    )
                }
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(backgroundColor)
                        .padding(padding),
                ) {
                    ThemeSettingsPage(
                        modifier = Modifier
                            .overScrollVertical()
                            .nestedScroll(themeScrollBehavior.nestedScrollConnection),
                        contentPadding = PaddingValues(bottom = pageBottomContentPadding),
                        themeMode = themeMode,
                        onThemeModeChange = onThemeModeChange,
                        accentMode = accentMode,
                        onAccentModeChange = onAccentModeChange,
                        floatingBottomBar = floatingBottomBar,
                        onFloatingBottomBarChange = onFloatingBottomBarChange,
                        blurBottomBar = blurBottomBar,
                        onBlurBottomBarChange = onBlurBottomBarChange,
                    )
                }
            }
        }
        entry<Screen.Equalizer> {
            val equalizerScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = stringResource(R.string.eq_preset_title),
                        largeTitle = stringResource(R.string.eq_preset_title),
                        scrollBehavior = equalizerScrollBehavior,
                        navigationIcon = {
                            IconButton(onClick = { backStack.removeLast() }) {
                                Icon(imageVector = MiuixIcons.Back, contentDescription = "Back")
                            }
                        },
                    )
                },
            ) { padding ->
                EqualizerPage(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(backgroundColor)
                        .overScrollVertical()
                        .nestedScroll(equalizerScrollBehavior.nestedScrollConnection),
                    contentPadding = PaddingValues(
                        top = padding.calculateTopPadding(),
                        bottom = pageBottomContentPadding,
                    ),
                    builtInPresets = displayCapabilities.eqPresets,
                    devicePresets = eqDevicePresets.value,
                    selectedId = eqPreset.value,
                    customEqSupported = displayCapabilities.customEqSupported,
                    customEqFrequencies = displayCapabilities.customEqFrequencies,
                    customEqMaxPresets = displayCapabilities.customEqMaxPresets,
                    onSelectPreset = ::setEqPreset,
                    onSavePreset = ::saveEqPreset,
                    onDeletePreset = ::deleteEqPreset,
                )
            }
        }
        entry<Screen.RfcommDebug> {
            val rfcommScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
            var clearRfcommLogsRequest by remember { mutableIntStateOf(0) }

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = "RFCOMM 调试",
                        largeTitle = "RFCOMM 调试",
                        scrollBehavior = rfcommScrollBehavior,
                        navigationIcon = {
                            IconButton(onClick = { backStack.removeLast() }) {
                                Icon(imageVector = MiuixIcons.Back, contentDescription = "Back")
                            }
                        },
                        actions = {
                            IconButton(onClick = { clearRfcommLogsRequest++ }) {
                                Icon(imageVector = MiuixIcons.Delete, contentDescription = "Clear logs")
                            }
                        }
                    )
                }
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(backgroundColor)
                        .padding(padding),
                ) {
                    RfcommDebugPage(
                        modifier = Modifier.nestedScroll(rfcommScrollBehavior.nestedScrollConnection),
                        contentPadding = PaddingValues(0.dp),
                        clearRequest = clearRfcommLogsRequest,
                    )
                }
            }
        }
    }

    val entries = rememberDecoratedNavEntries(
        backStack = backStack,
        entryProvider = entryProvider
    )

    NavDisplay(
        entries = entries,
        onBack = {
            if (backStack.size > 1) {
                backStack.removeLast()
            } else {
                (context as? Activity)?.finish()
            }
        }
    )
}

@Composable
fun appBackground(): Color = MiuixTheme.colorScheme.surface

private data class BluetoothSummary(
    val enabled: Boolean,
    val bondedCount: Int,
)

private fun readBluetoothState(context: Context): BluetoothSummary {
    val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    return runCatching {
        BluetoothSummary(
            enabled = adapter?.isEnabled == true,
            bondedCount = adapter?.bondedDevices?.size ?: 0,
        )
    }.getOrDefault(BluetoothSummary(enabled = false, bondedCount = 0))
}

private fun wearStateFromExtra(value: Int): WearState? {
    return WearState.fromValue(value)
}

private fun sendBluetoothModuleBroadcast(context: Context, action: String) {
    // 免 root 模式：控制命令回到本应用进程的 NorootBridge；Root 模式：发给被注入的蓝牙进程
    val targets = if (moe.huime.moonpods.config.ConfigManager.workMode() ==
        moe.huime.moonpods.config.ConfigManager.WORK_MODE_NOROOT
    ) {
        listOf(moe.huime.moonpods.BuildConfig.APPLICATION_ID)
    } else {
        listOf("com.android.bluetooth", "com.xiaomi.bluetooth")
    }
    targets.forEach { packageName ->
        Intent(action).apply {
            setPackage(packageName)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendBroadcast(this)
        }
    }
}

private fun isLauncherIconHidden(context: Context): Boolean {
    val component = ComponentName(context, "moe.huime.moonpods.LauncherActivity")
    val state = context.packageManager.getComponentEnabledSetting(component)
    return state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
}

private fun setLauncherIconHidden(context: Context, hidden: Boolean) {
    val component = ComponentName(context, "moe.huime.moonpods.LauncherActivity")
    val state = if (hidden) {
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    } else {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    }
    context.packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
}

private fun broadcastConfigChanged(context: Context, packageName: String) {
    Intent(MoonPodsAction.ACTION_CONFIG_CHANGED).apply {
        setPackage(packageName)
        addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        context.sendBroadcast(this)
    }
}
