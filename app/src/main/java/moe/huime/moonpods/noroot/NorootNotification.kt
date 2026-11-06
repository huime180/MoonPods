package moe.huime.moonpods.noroot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import moe.huime.moonpods.BuildConfig
import moe.huime.moonpods.MainActivity
import moe.huime.moonpods.R
import moe.huime.moonpods.utils.miuiStrongToast.data.MoonPodsAction

/**
 * 免 root 模式**自己的**通知。
 *
 * Root 模式的耳机通知由 MIUI 蓝牙进程发（我们 hook 它，走小米原生体验）；
 * 免 root 模式没有那个进程，所以由应用自己发这一条：
 *   · 常驻、静默（无声音无震动）；
 *   · 显示机型 + 三路电量；
 *   · 点击打开应用，「断开连接」直接断本应用的链路。
 *
 * 刻意不做前台服务：通知只是状态展示，链路生命周期跟应用进程一致。
 */
object NorootNotification {

    private const val TAG = "MoonPods-NorootNotify"
    private const val CHANNEL_ID = "moonpods_noroot_status"
    private const val NOTIFICATION_ID = 10005

    fun channel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val existing = runCatching { nm.getNotificationChannel(CHANNEL_ID) }.getOrNull()
        if (existing != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "MoonPods 免 root 状态",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
        )
    }

    fun post(ctx: Context, snap: PodSnapshot) {
        if (!snap.connected) return
        channel(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val battery = snap.battery
        val parts = buildList {
            if (battery.leftKnown) add("L ${battery.left}%")
            if (battery.rightKnown) add("R ${battery.right}%")
            if (battery.caseKnown) add("盒 ${battery.case}%")
        }
        val text = if (parts.isEmpty()) "已连接" else parts.joinToString(" · ")
        val openIntent = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnectIntent = PendingIntent.getBroadcast(
            ctx, 1, Intent(MoonPodsAction.NOROOT_DISCONNECT).setPackage(BuildConfig.APPLICATION_ID),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_anc)
            .setContentTitle(snap.deviceName.ifBlank { snap.modelName.ifBlank { "MoonPods" } })
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "断开连接", disconnectIntent).build())
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, notification) }
            .onFailure { Log.w(TAG, "notify failed", it) }
    }

    fun cancel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        runCatching { nm.cancel(NOTIFICATION_ID) }
    }
}
