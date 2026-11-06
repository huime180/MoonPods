package moe.huime.moonpods.noroot

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import moe.huime.moonpods.config.ConfigManager

/**
 * 免 root 会话保活服务。
 *
 * 免 root 的链路跑在应用进程里：进程一被回收，耳机就不再受控。用户希望
 * 「关掉应用后仍能控制耳机」，唯一系统允许的方式是前台服务 —— 这里复用
 * 状态通知作为前台通知（与常驻通知同一条，不额外增加一条）。
 *
 * 生命周期：模式为免 root 且有会话时启动；用户主动断开、切到 Root 或关闭
 * 焦点通知时停止。
 */
class NorootKeepAliveService : Service() {

    companion object {
        private const val TAG = "MoonPods-KeepAlive"

        fun start(context: Context) {
            if (ConfigManager.workMode() != ConfigManager.WORK_MODE_NOROOT) return
            runCatching {
                context.startForegroundService(Intent(context, NorootKeepAliveService::class.java))
            }.onFailure { Log.w(TAG, "start failed", it) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, NorootKeepAliveService::class.java)) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NorootNotification.NOTIFICATION_ID, placeholder())
        NorootBridge.init(this)
        NorootBridge.autoConnect(this)
        return START_STICKY
    }

    /** 前台通知先给一条最小的；真正的状态通知由 NorootNotification 更新同一条。 */
    private fun placeholder(): Notification = Notification.Builder(this, NorootNotification.CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_headset)
        .setContentTitle("MoonPods")
        .setContentText("正在保持耳机连接")
        .setOngoing(true)
        .build()

    override fun onDestroy() {
        // 刻意什么都不做：服务被系统回收/应用被停止时，绝不能顺手断开蓝牙连接。
        // 需要断开只有用户点「断开连接」这一条路径。
        super.onDestroy()
    }
}
