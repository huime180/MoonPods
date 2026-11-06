package moe.huime.moonpods.utils

import android.content.Context
import android.content.Intent
import android.util.Log
import moe.huime.moonpods.config.ConfigManager
import moe.huime.moonpods.noroot.NorootBridge
import moe.huime.moonpods.noroot.NorootNotification
import moe.huime.moonpods.utils.miuiStrongToast.data.MoonPodsAction

/**
 * 工作模式切换的完整编排。
 *
 * 两种模式互斥且注入状态不同，只改配置会出现「配置已是 Root、系统应用却还挂着旧 hook」：
 * 因此切换必须按固定顺序执行：
 *   1. 断开模块当前的蓝牙/协议链路；
 *   2. 撤掉本应用的通知并让小米侧撤掉它那条；
 *   3. 写入新模式并广播（让被注入进程立即按新模式工作）；
 *   4. 结束被注入进程，使 hook 卸载（等价于「重启作用域」）；
 *   5. 重启本应用，避免界面继续持有旧模式的状态。
 */
object ModeSwitcher {

    private const val TAG = "MoonPods-ModeSwitch"

    private val HOOKED_PACKAGES = listOf(
        "com.android.bluetooth",
        "com.xiaomi.bluetooth",
        "com.milink.service",
        "com.android.settings",
    )

    fun switchTo(context: Context, mode: Int) {
        val app = context.applicationContext
        val previous = ConfigManager.workMode()
        if (previous == mode) return
        Log.i(TAG, "switch work mode: $previous -> $mode")

        // 1) 释放当前链路：两种模式共用同一条 RFCOMM/SPP 通道
        runCatching {
            NorootBridge.disconnect()
            NorootNotification.cancelImmediate(app)
        }
        // 2) 让小米蓝牙进程撤掉它那条通知
        runCatching {
            app.sendBroadcast(Intent(MoonPodsAction.CANCEL_PODS_NOTIFICATION).apply {
                setPackage("com.xiaomi.bluetooth")
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            })
        }
        // 先写权威文件：被注入进程直接读它，不依赖 prefs 同步
        runCatching { moe.huime.moonpods.config.ModeFile.write(app, mode) }
        // 3) 持久化 + 通知各注入进程
        runCatching {
            ConfigManager.updateWorkMode(
                app.getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE),
                null,
                mode,
            )
        }
        runCatching {
            HOOKED_PACKAGES.forEach { pkg ->
                app.sendBroadcast(Intent(MoonPodsAction.SET_WORK_MODE).apply {
                    setPackage(pkg)
                    putExtra("mode", mode)
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                })
            }
        }
        // 4)/5) 结束被注入进程并重启应用 —— 会阻塞（su 调用 + 等待进程退出），
        // 必须放到后台线程，否则设置页会卡住甚至在 su 授权弹窗时 ANR。
        Thread {
            runCatching { restartScope() }
            runCatching { restartApp(app) }
        }.start()
    }

    /** 通过 su 结束被注入进程；没有 root 时静默失败，进程会在下次启动时重新注入。 */
    private fun restartScope() {
        val cmd = HOOKED_PACKAGES.joinToString(";") { "am force-stop $it" }
        runCatching {
            val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            p.waitFor()
            Log.i(TAG, "scope restarted: $cmd")
        }.onFailure { Log.w(TAG, "restart scope failed", it) }
    }

    private fun restartApp(context: Context) {
        runCatching {
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: return@runCatching
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            context.startActivity(intent)
            Log.i(TAG, "app restart requested")
        }.onFailure { Log.w(TAG, "restart app failed", it) }
    }
}
