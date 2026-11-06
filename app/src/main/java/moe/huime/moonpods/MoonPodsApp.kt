package moe.huime.moonpods

import android.app.Application
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArraySet
import moe.huime.moonpods.config.ConfigManager

class MoonPodsApp : Application(), XposedServiceHelper.OnServiceListener {
    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(this)
        // 免 root 模式：应用进程自己直连耳机。这里只初始化链路与广播桥，不起任何服务。
        // 被注入进程没有网络权限：它们请求刷新时由应用进程代劳，结果写回共享 prefs
        runCatching {
            registerReceiver(object : android.content.BroadcastReceiver() {
                override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                    if (intent?.action != moe.huime.moonpods.utils.miuiStrongToast.data.MoonPodsAction.REFRESH_CATALOG) return
                    Thread {
                        runCatching { moe.huime.moonpods.pods.moondrop.MoondropCatalog.refresh(force = true) }
                    }.start()
                }
            }, android.content.IntentFilter(
                moe.huime.moonpods.utils.miuiStrongToast.data.MoonPodsAction.REFRESH_CATALOG
            ), android.content.Context.RECEIVER_EXPORTED)
        }

        runCatching {
            val prefs = getSharedPreferences(ConfigManager.PREFS_NAME, MODE_PRIVATE)
            ConfigManager.init(prefs)
            if (ConfigManager.workMode() == ConfigManager.WORK_MODE_NOROOT) {
                moe.huime.moonpods.noroot.NorootBridge.init(this)
                // 启动即尝试恢复上一次的耳机连接（Root 模式由 hook 侧自动接管）
                moe.huime.moonpods.noroot.NorootBridge.autoConnect(this)
            }
        }
    }

    override fun onServiceBind(service: XposedService) {
        Log.d(TAG, "LSPosed service bound api=${service.apiVersion} framework=${service.frameworkName}/${service.frameworkVersionCode}")
        xposedService = service
        notifyListeners(service)
    }

    override fun onServiceDied(service: XposedService) {
        if (xposedService == service) {
            Log.d(TAG, "LSPosed service died")
            xposedService = null
            notifyListeners(null)
        }
    }

    private fun notifyListeners(service: XposedService?) {
        listeners.forEach { it(service) }
    }

    companion object {
        private const val TAG = "HyperPods-App"

        @Volatile
        var xposedService: XposedService? = null
            private set

        private val listeners = CopyOnWriteArraySet<(XposedService?) -> Unit>()

        fun addServiceListener(listener: (XposedService?) -> Unit) {
            listeners.add(listener)
            listener(xposedService)
        }

        fun removeServiceListener(listener: (XposedService?) -> Unit) {
            listeners.remove(listener)
        }
    }
}
