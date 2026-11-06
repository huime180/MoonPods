package moe.huime.moonpods.hook

import android.content.SharedPreferences
import android.util.Log
import android.os.Build
import androidx.annotation.RequiresApi
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import moe.huime.moonpods.config.ConfigManager
import moe.huime.moonpods.hook.milink.MiLinkServiceHook

class HookEntry : XposedModule() {
    private val TAG = "HyperPods-HookEntry"
    private val configListeners = mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (!param.isFirstPackage) return
        // 工作模式只在这里判断一次：免 root 时完全不安装 Root 侧 hook，
        // 之后所有 hook 回调里都不需要再判断模式（避免 remote prefs 滞后导致的时好时坏）。
        if (moe.huime.moonpods.pods.WorkModeBridge.isNoRoot()) {
            Log.i(TAG, "no-root mode: skip all hooks for ${param.packageName}")
            return
        }

        when (param.packageName) {
            "com.android.bluetooth" -> {
                loadHook(HeadsetStateDispatcher, param.defaultClassLoader, param.packageName)
                loadHook(BluetoothUpstreamHeadsetHook(), param.defaultClassLoader, param.packageName)
            }
            // 设置进程（com.android.settings）不在作用域里：历史上那套设置页伪装已整体回退，
            // 分发与实现文件都已删除，不要再按「设置页被接管」理解本模块。
            "com.milink.service" -> loadHook(MiLinkServiceHook, param.defaultClassLoader, param.packageName)
            "com.xiaomi.bluetooth" -> {
                loadHook(MiBluetoothToastHook, param.defaultClassLoader, param.packageName)
                loadHook(BluetoothUpstreamHeadsetHook(), param.defaultClassLoader, param.packageName)
            }
            // 小米设置里的耳机详情页：视图级适配（参考 Konakonai/Edge2Pods）
            "com.android.settings" -> {
                loadHook(SettingsHeadsetHook(), param.defaultClassLoader, param.packageName)
            }
        }
    }

    private fun loadHook(hook: HookContext, classLoader: ClassLoader, packageName: String) {
        Log.module = this
        hook.module = this
        hook.appClassLoader = classLoader
        hook.packageName = packageName
        hook.prefs = getRemotePreferences("hyperpods_settings")
        Log.d(TAG, "loadHook package=$packageName hook=${hook.javaClass.simpleName}")
        ConfigManager.init(hook.prefs)
        val configListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
            if (key == ConfigManager.PREF_KEY_CONFIG_JSON) {
                ConfigManager.refreshFromPrefs(sharedPreferences)
            }
        }
        configListeners.add(configListener)
        hook.prefs.registerOnSharedPreferenceChangeListener(configListener)
        hook.onHook()
    }
}
