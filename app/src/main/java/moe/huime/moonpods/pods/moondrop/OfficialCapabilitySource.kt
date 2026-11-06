package moe.huime.moonpods.pods.moondrop

import android.content.Context
import android.util.Log
import moe.huime.moonpods.config.ConfigManager

/**
 * 从水月雨官方 App 的本地缓存读取「机型 → 功能清单」。
 *
 * 为什么需要它：官方云端 products/all 只有型号/芯片族/EQ 参数范围，**没有**功能清单
 * （ANC/PEQ/LED/提示音/双设备…）。官方 App 自己会在连接后按产品 UUID 拉取并缓存
 * 一份 `cachedDeviceFunc_<uuid>_<固件版本>`，内容是 `[{uuid, uri}]` 列表，
 * uri 形如 `/jieli/peq`、`/jieli/ancV2`、`/jieli/onebringtwoV2`。
 *
 * 本类在应用进程（有网络与 root 权限）读取那份缓存，整理成
 * `uuid → [功能 uri...]` 的映射写入模块共享 prefs，供两种模式复用。
 */
object OfficialCapabilitySource {

    private const val TAG = "MoonPods-OfficialCaps"
    private const val PREFS_PATH =
        "/data/data/com.moondroplab.moondrop.moondrop_app/shared_prefs/FlutterSharedPreferences.xml"

    /** 缓存里按固件版本可能有多条，取版本号最大的那条。 */
    private val VERSION_RE = Regex("""^(\d+(?:\.\d+)*)$""")

    fun refresh(context: Context) {
        val xml = runCatching { readWithRoot(PREFS_PATH) }.getOrNull() ?: return
        val map = parse(xml)
        if (map.isEmpty()) {
            Log.w(TAG, "no cachedDeviceFunc entries found")
            return
        }
        ConfigManager.updateOfficialFunctions(map)
        Log.i(TAG, "official functions loaded: ${map.size} devices")
    }

    /** 该产品 UUID 支持的功能 uri 列表；没有缓存返回空。 */
    fun functionsFor(uuid: String?): List<String> =
        if (uuid.isNullOrBlank()) emptyList() else ConfigManager.officialFunctions()[uuid].orEmpty()

    private fun readWithRoot(path: String): String? = runCatching {
        val process = ProcessBuilder("su", "-c", "cat '$path'")
            .redirectErrorStream(true)
            .start()
        val out = process.inputStream.bufferedReader().use { it.readText() }
        if (process.waitFor() == 0 && out.isNotBlank()) out else null
    }.getOrNull()

    /** 解析 Flutter 的 SharedPreferences XML：name="flutter.cachedDeviceFunc_<uuid>_<ver>"。 */
    private fun parse(xml: String): Map<String, List<String>> {
        val best = LinkedHashMap<String, Pair<List<Int>, List<String>>>()
        val entryRe = Regex(
            """cachedDeviceFunc_([0-9a-fA-F-]{36})_([0-9.]+)">(.*?)</string>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        for (m in entryRe.findAll(xml)) {
            val uuid = m.groupValues[1]
            val version = m.groupValues[2].split('.').mapNotNull { it.toIntOrNull() }
            val uris = Regex("""&quot;uri&quot;:&quot;([^&]+)&quot;""")
                .findAll(m.groupValues[3])
                .map { it.groupValues[1] }
                .toList()
            if (uris.isEmpty()) continue
            val prev = best[uuid]
            if (prev == null || compare(version, prev.first) > 0) best[uuid] = version to uris
        }
        return best.mapValues { it.value.second }
    }

    private fun compare(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }
}
