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
 * 免 root 模式的耳机状态通知。
 *
 * 设计对齐 miuixmoondrop 的 PodNotification：一条常驻、静默的状态通知，承载
 * 机型名、三路电量、降噪三态快捷切换与断开操作；不做前台服务，生命周期跟随应用进程。
 *
 * 通道：id = moonpods_device_status，用户可见名称「耳机状态」/「Earbuds status」。
 */
object NorootNotification {

    private const val TAG = "MoonPods-Notification"
    private const val CHANNEL_ID = "moonpods_device_status"
    private const val NOTIFICATION_ID = 10005


    fun channel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (runCatching { nm.getNotificationChannel(CHANNEL_ID) }.getOrNull() != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                ctx.getString(R.string.noroot_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = ctx.getString(R.string.noroot_channel_desc)
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
        // 与 Root 模式（MiBluetoothToastHook）保持同一套文案：盒/左/右 + 充电符号，
        // 盒子单独一行、左右在同一行。两个模式只差通知通道，内容一致。
        val caseText = if (battery.caseKnown) {
            "${ctx.getString(R.string.noroot_label_case)}${battery.case}%" +
                (if (battery.caseCharging) "⚡ " else " ") + "\n"
        } else ""
        val leftText = if (battery.leftKnown) {
            "${ctx.getString(R.string.noroot_label_left)}${battery.left}%" +
                (if (battery.leftCharging) "⚡" else "")
        } else ""
        val bothKnown = battery.leftKnown && battery.rightKnown
        val rightText = if (battery.rightKnown) {
            "${if (bothKnown) " " else ""}${ctx.getString(R.string.noroot_label_right)}${battery.right}%" +
                (if (battery.rightCharging) "⚡ " else " ")
        } else ""
        val text = (caseText + leftText + rightText).trimEnd()
            .ifEmpty { ctx.getString(R.string.noroot_connected) }

        val openIntent = PendingIntent.getActivity(
            ctx,
            0,
            Intent(ctx, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_anc)
            .setContentTitle(snap.deviceName.ifBlank { snap.modelName.ifBlank { "MOONDROP" } })
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        // 与 Root 模式一致：只保留「断开连接」一个动作（降噪在耳机页/控制中心操作）
        builder.addAction(
            Notification.Action.Builder(
                null,
                ctx.getString(R.string.noroot_action_disconnect),
                PendingIntent.getBroadcast(
                    ctx,
                    20,
                    Intent(MoonPodsAction.NOROOT_DISCONNECT).setPackage(BuildConfig.APPLICATION_ID),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            ).build()
        )

        runCatching { nm.notify(NOTIFICATION_ID, builder.build()) }
            .onFailure { Log.w(TAG, "notify failed", it) }
    }

    fun cancel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        runCatching { nm.cancel(NOTIFICATION_ID) }
    }
}
