package com.chenyc.hyperpods.noroot

import android.content.Context
import android.util.Log

/**
 * 免 root 独立链路的入口（占位）。
 *
 * 链路实现正从 3.0.0 移植（分支 noroot-port）。这里先提供开关落点，
 * 保证设置页可编译、开关可持久化；链路接上后在此启动/停止即可。
 */
object NorootBridge {

    private const val TAG = "HyperPods-Noroot"

    fun setEnabled(context: Context, enabled: Boolean) {
        Log.i(TAG, "noroot link enabled=$enabled")
        if (!enabled) NorootLink.disconnect()
    }

    fun init(context: Context) {
        if (!com.chenyc.hyperpods.config.ConfigManager.norootEnabled()) return
        Log.i(TAG, "noroot link requested at startup")
    }
}
