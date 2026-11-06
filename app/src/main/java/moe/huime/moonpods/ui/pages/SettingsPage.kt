package moe.huime.moonpods.ui.pages

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.huime.moonpods.R
import moe.huime.moonpods.config.ConfigManager
import moe.huime.moonpods.ui.AppLocale
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight

@Composable
fun SettingsPage(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    desktopIconHidden: MutableState<Boolean> = mutableStateOf(false),
    onDesktopIconHiddenChange: (Boolean) -> Unit = {},
    workMode: MutableState<Int> = mutableStateOf(ConfigManager.WORK_MODE_ROOT),
    connectPopup: MutableState<Int> = mutableStateOf(ConfigManager.CONNECT_POPUP_MODULE),
    onConnectPopupChange: (Int) -> Unit = {},
    onWorkModeChange: (Int) -> Unit = {},
    focusNotification: MutableState<Boolean> = mutableStateOf(true),
    onFocusNotificationChange: (Boolean) -> Unit = {},
    logLevel: MutableState<Int> = mutableStateOf(ConfigManager.LOG_LEVEL_BASIC),
    onLogLevelChange: (Int) -> Unit = {},
    islandMode: MutableState<Int> = mutableStateOf(ConfigManager.ISLAND_MODE_OFFICIAL),
    onIslandModeChange: (Int) -> Unit = {},
    islandShowTimings: MutableState<Set<Int>> = mutableStateOf(emptySet()),
    onIslandShowTimingsChange: (Set<Int>) -> Unit = {},
    appLanguage: MutableState<Int> = mutableStateOf(AppLocale.SYSTEM),
    onAppLanguageChange: (Int) -> Unit = {},
    notificationClickAction: MutableState<Int> = mutableStateOf(ConfigManager.NOTIFICATION_CLICK_MODULE_POPUP),
    onNotificationClickActionChange: (Int) -> Unit = {},
    moreClickAction: MutableState<Int> = mutableStateOf(ConfigManager.MORE_CLICK_MODULE),
    onMoreClickActionChange: (Int) -> Unit = {},
    onOpenTheme: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
) {
    val connectPopupOptions = if (workMode.value == ConfigManager.WORK_MODE_ROOT) {
        listOf(
            stringResource(R.string.connect_popup_module),
            stringResource(R.string.connect_popup_official),
            stringResource(R.string.connect_popup_none),
        )
    } else {
        listOf(
            stringResource(R.string.connect_popup_module),
            stringResource(R.string.connect_popup_none),
        )
    }
    // 系统弹窗依赖 hook：免 root 模式下不提供该选项
    val connectPopupValues = if (workMode.value == ConfigManager.WORK_MODE_ROOT) {
        listOf(
            ConfigManager.CONNECT_POPUP_MODULE,
            ConfigManager.CONNECT_POPUP_OFFICIAL,
            ConfigManager.CONNECT_POPUP_NONE,
        )
    } else {
        listOf(ConfigManager.CONNECT_POPUP_MODULE, ConfigManager.CONNECT_POPUP_NONE)
    }
    val workModeOptions = listOf(
        stringResource(R.string.work_mode_root),
        stringResource(R.string.work_mode_noroot),
    )
    val workModeValues = listOf(ConfigManager.WORK_MODE_ROOT, ConfigManager.WORK_MODE_NOROOT)
    val languageOptions = listOf(
        stringResource(R.string.language_system),
        stringResource(R.string.language_chinese),
        stringResource(R.string.language_english),
    )
    val logLevelValues = listOf(ConfigManager.LOG_LEVEL_OFF, ConfigManager.LOG_LEVEL_BASIC, ConfigManager.LOG_LEVEL_DEBUG)
    val logLevelOptions = listOf(
        stringResource(R.string.log_level_off),
        stringResource(R.string.log_level_basic),
        stringResource(R.string.log_level_debug),
    )
    // 官方超级岛依赖 hook（通知由小米蓝牙进程发布）：免 root 模式下不提供
    val islandModeValues = if (workMode.value == ConfigManager.WORK_MODE_ROOT) {
        listOf(ConfigManager.ISLAND_MODE_NONE, ConfigManager.ISLAND_MODE_OFFICIAL, ConfigManager.ISLAND_MODE_MODULE)
    } else {
        listOf(ConfigManager.ISLAND_MODE_NONE, ConfigManager.ISLAND_MODE_MODULE)
    }
    val islandModeOptions = if (workMode.value == ConfigManager.WORK_MODE_ROOT) {
        listOf(
            stringResource(R.string.island_mode_none),
            stringResource(R.string.island_mode_official),
            stringResource(R.string.island_mode_module),
        )
    } else {
        listOf(
            stringResource(R.string.island_mode_none),
            stringResource(R.string.island_mode_module),
        )
    }
    val islandShowTimingOptions = listOf(
        ConfigManager.ISLAND_SHOW_TIMING_CONNECTED to stringResource(R.string.island_show_timing_connected),
        ConfigManager.ISLAND_SHOW_TIMING_WEARING to stringResource(R.string.island_show_timing_wearing),
        ConfigManager.ISLAND_SHOW_TIMING_REMOVED to stringResource(R.string.island_show_timing_removed),
        ConfigManager.ISLAND_SHOW_TIMING_IN_CASE to stringResource(R.string.island_show_timing_in_case),
    )
    val islandShowTimingEntries = remember(islandShowTimings.value, islandShowTimingOptions) {
        listOf(
            DropdownEntry(
                items = islandShowTimingOptions.map { (value, text) ->
                    DropdownItem(
                        text = text,
                        selected = value in islandShowTimings.value,
                        onClick = {
                            val selected = islandShowTimings.value
                            onIslandShowTimingsChange(
                                if (value in selected) selected - value else selected + value
                            )
                        },
                    )
                }
            )
        )
    }
    val notificationClickActionValues = listOf(
        ConfigManager.NOTIFICATION_CLICK_MODULE_POPUP,
        ConfigManager.NOTIFICATION_CLICK_SYSTEM_SETTINGS,
        ConfigManager.NOTIFICATION_CLICK_OFFICIAL,
    )
    val notificationClickActionOptions = listOf(
        stringResource(R.string.notification_click_module_popup),
        stringResource(R.string.click_action_system_settings),
        moe.huime.moonpods.utils.OfficialApp.label(LocalContext.current),
    )
    val moreClickActionValues = listOf(
        ConfigManager.MORE_CLICK_OFFICIAL,
        ConfigManager.MORE_CLICK_SYSTEM_SETTINGS,
        ConfigManager.MORE_CLICK_MODULE,
    )
    val moreClickActionOptions = listOf(
        moe.huime.moonpods.utils.OfficialApp.label(LocalContext.current),
        stringResource(R.string.click_action_system_settings),
        stringResource(R.string.click_action_module),
    )
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 12.dp,
            bottom = contentPadding.calculateBottomPadding() + 12.dp,
            start = 12.dp,
            end = 12.dp
        ),
    ) {
        item {
            Card {
                BasicComponent(
                    title = stringResource(R.string.theme_title),
                    summary = stringResource(R.string.theme_color_summary),
                    onClick = onOpenTheme,
                    endActions = {
                        Icon(
                            imageVector = MiuixIcons.Basic.ArrowRight,
                            contentDescription = null,
                        )
                    },
                )
            }
        }

        item {
            Card(modifier = Modifier.padding(top = 12.dp)) {
                OverlayDropdownPreference(
                    title = stringResource(R.string.language),
                    summary = stringResource(R.string.language_summary),
                    items = languageOptions,
                    selectedIndex = appLanguage.value.coerceIn(languageOptions.indices),
                    onSelectedIndexChange = { onAppLanguageChange(it) }
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.log_level),
                    summary = stringResource(R.string.log_level_summary),
                    items = logLevelOptions,
                    selectedIndex = logLevelValues.indexOf(logLevel.value).coerceAtLeast(0),
                    onSelectedIndexChange = { onLogLevelChange(logLevelValues[it]) }
                )
                SwitchPreference(
                    title = stringResource(R.string.hide_desktop_icon),
                    summary = stringResource(R.string.hide_desktop_icon_summary),
                    checked = desktopIconHidden.value,
                    onCheckedChange = { onDesktopIconHiddenChange(it) }
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.work_mode),
                    summary = stringResource(R.string.work_mode_summary),
                    items = workModeOptions,
                    selectedIndex = workModeValues.indexOf(workMode.value).coerceAtLeast(0),
                    onSelectedIndexChange = { onWorkModeChange(workModeValues[it]) }
                )
            }
        }

        item {
            // 通知与弹窗：与「超级岛」分开，单独成卡放在其上方
            Card(modifier = Modifier.padding(top = 12.dp)) {
                SwitchPreference(
                    title = stringResource(R.string.focus_notification),
                    summary = stringResource(R.string.focus_notification_summary),
                    checked = focusNotification.value,
                    onCheckedChange = { onFocusNotificationChange(it) }
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.connect_popup),
                    summary = stringResource(R.string.connect_popup_summary),
                    items = connectPopupOptions,
                    selectedIndex = connectPopupValues.indexOf(connectPopup.value).coerceAtLeast(0),
                    onSelectedIndexChange = { onConnectPopupChange(connectPopupValues[it]) }
                )
            }
        }

        item {
            Card(modifier = Modifier.padding(top = 12.dp)) {
                OverlayDropdownPreference(
                    title = stringResource(R.string.island_mode),
                    summary = stringResource(R.string.island_mode_summary),
                    items = islandModeOptions,
                    selectedIndex = islandModeValues.indexOf(islandMode.value).coerceAtLeast(0),
                    onSelectedIndexChange = { onIslandModeChange(islandModeValues[it]) }
                )
                if (islandMode.value == ConfigManager.ISLAND_MODE_MODULE) {
                    OverlayDropdownPreference(
                        title = stringResource(R.string.island_show_timing),
                        summary = stringResource(R.string.island_show_timing_summary),
                        entries = islandShowTimingEntries,
                        collapseOnSelection = false,
                    )
                }
                OverlayDropdownPreference(
                    title = stringResource(R.string.notification_click_action),
                    summary = stringResource(R.string.notification_click_action_summary),
                    items = notificationClickActionOptions,
                    selectedIndex = notificationClickActionValues.indexOf(notificationClickAction.value).coerceAtLeast(0),
                    onSelectedIndexChange = { onNotificationClickActionChange(notificationClickActionValues[it]) }
                )
                if (notificationClickAction.value == ConfigManager.NOTIFICATION_CLICK_MODULE_POPUP) {
                    OverlayDropdownPreference(
                        title = stringResource(R.string.more_click_action),
                        summary = stringResource(R.string.more_click_action_summary),
                        items = moreClickActionOptions,
                        selectedIndex = moreClickActionValues.indexOf(moreClickAction.value).coerceAtLeast(0),
                        onSelectedIndexChange = { onMoreClickActionChange(moreClickActionValues[it]) }
                    )
                }
            }
        }

        item {
            Card(modifier = Modifier.padding(top = 12.dp)) {
                BasicComponent(
                    title = stringResource(R.string.about),
                    summary = "HyperPods-Enhanced",
                    onClick = onOpenAbout,
                    endActions = {
                        Icon(
                            imageVector = MiuixIcons.Basic.ArrowRight,
                            contentDescription = null,
                        )
                    },
                )
            }
        }
    }
}
