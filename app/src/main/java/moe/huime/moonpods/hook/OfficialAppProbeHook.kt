package moe.huime.moonpods.hook

/**
 * 官方 App（com.moondroplab.moondrop.moondrop_app）探测钩子。
 *
 * 目的：官方 App 是 Flutter，但设备通信经过 Java 层（com.qualcomm.qti.gaiaclient）。
 * 先注入并**只做观测**：记录它读取产品 ID / 设备标识的调用与返回值，
 * 据此确定「模拟设备」应替换哪个方法的返回值；确认后再改为可控返回。
 *
 * 第一阶段只打日志，不改变官方 App 的行为（避免它拉到错误配置或崩溃）。
 */
class OfficialAppProbeHook : HookContext() {

    private val TAG = "MoonPods-Official"

    /** 目标机型名（配置项 spoof_model）；为空时只观测不改写。 */
    private fun spoofModel(): String =
        runCatching { prefs.getString("spoof_model", null) }.getOrNull().orEmpty()

    override fun onHook() {
        // GAIA 客户端里与设备身份相关的方法名（不同版本可能不同，逐个 runCatching）
        val classNames = listOf(
            "com.qualcomm.qti.gaiaclient.core.gaia.qtil.data.basic.ProductId",
            "com.qualcomm.qti.gaiaclient.core.gaia.qtil.data.basic.DeviceInformation",
            "com.qualcomm.qti.gaiaclient.core.gaia.qtil.plugins.BasicPlugin",
            "com.qualcomm.qti.gaiaclient.repository.deviceinformation.DeviceInformationRepository",
        )
        // —— 机型伪装：把「当前设备是谁」替换成目标机型 ——
        // 官方 App 通过 GAIA 的 SystemInformation 得知设备型号（deviceModel / productId），
        // 再用它去产品目录里查到 UUID，进而缓存该机型的能力清单。
        // 篡改这两个值，App 会把我们的耳机当成目标机型来拉取与缓存。
        runCatching {
            val clazz = findClass("com.qualcomm.qti.gaiaclient.core.gaia.qtil.data.basic.SystemInformation")
            val model = spoofModel()
            clazz.declaredMethods
                .filter { it.parameterCount == 0 }
                .forEach { method ->
                    val type = method.returnType
                    val isModelLike = method.name.contains("model", true) ||
                        method.name.contains("product", true)
                    if (!isModelLike) return@forEach
                    runCatching {
                        if (type == String::class.java) {
                            hookAfter(method) {
                                val original = result as? String
                                Log.i(TAG, "SystemInformation.${method.name} = $original")
                                if (model.isNotBlank()) {
                                    Log.i(TAG, "spoof model -> $model")
                                    result = model
                                }
                            }
                        } else if (type == Int::class.javaPrimitiveType) {
                            hookAfter(method) {
                                Log.i(TAG, "SystemInformation.${method.name} = $result")
                            }
                        }
                    }.onFailure { Log.w(TAG, "identity hook failed: ${method.name}", it) }
                }
            Log.i(TAG, "identity spoof installed (model=$model)")
        }.onFailure { Log.w(TAG, "SystemInformation not found", it) }

        classNames.forEach { className ->
            runCatching {
                val clazz = findClass(className)
                clazz.declaredMethods
                    .filter { it.parameterCount == 0 && it.returnType == Int::class.javaPrimitiveType }
                    .forEach { method ->
                        runCatching {
                            hookAfter(method) {
                                Log.i(TAG, "${clazz.simpleName}.${method.name} -> ${result}")
                            }
                        }
                    }
                Log.i(TAG, "probe installed: $className")
            }.onFailure { Log.w(TAG, "probe skipped: $className", it) }
        }
    }
}
