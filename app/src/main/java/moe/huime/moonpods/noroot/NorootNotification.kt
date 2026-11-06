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

    /**
     * 通知里的降噪三态：与 HyperOS 的 关 / 降噪 / 通透 一一对应。
     *
     * 族归属按设备上报的 id 判定：降噪族含 anc / anti_wind / adaptive，
     * 通透族含 transparent / live —— 与耳机页的家族口径一致。
     */
    private data class AncAction(val labelRes: Int, val ids: Set<String>)

    private val ANC_ACTIONS = listOf(
        AncAction(R.string.noroot_action_anc_off, setOf("off")),
        AncAction(R.string.noroot_action_anc_on, setOf("anc", "anti_wind", "adaptive")),
        AncAction(R.string.noroot_action_anc_transparency, setOf("transparent", "live")),
    )

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
        val parts = buildList {
            if (battery.leftKnown) add("L ${battery.left}%")
            if (battery.rightKnown) add("R ${battery.right}%")
            if (battery.caseKnown) add("C ${battery.case}%")
        }
        val text = parts.joinToString(" · ").ifEmpty { ctx.getString(R.string.noroot_connected) }

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

        // 降噪三态快捷切换：仅在设备上报的档位表里存在对应家族时出现
        ANC_ACTIONS.forEachIndexed { i, action ->
            val target = snap.ancModes.indexOfFirst { it.id in action.ids }
            if (target < 0) return@forEachIndexed
            builder.addAction(
                Notification.Action.Builder(
                    null,
                    ctx.getString(action.labelRes),
                    PendingIntent.getBroadcast(
                        ctx,
                        10 + i,
                        Intent(MoonPodsAction.ANC_SELECT)
                            .setPackage(BuildConfig.APPLICATION_ID)
                            .putExtra(MoonPodsAction.EXTRA_STATUS, target),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                ).build()
            )
        }
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
