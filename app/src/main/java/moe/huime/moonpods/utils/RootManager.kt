package moe.huime.moonpods.utils

import android.util.Log

/**
 * root 相关工具（只保留模块自己需要的两个能力）。
 *
 * 历史：这个文件原本还负责从「欢律」的数据目录扫描机型图（OPPO 专用）。
 * OPPO 支持与欢律图片导入一起移除后，只剩下：
 *   · [restartPackages]：让用户改完作用域后重启被注入进程（设置页的「重启作用域」）；
 *   · [hasRootAccess]：设置页用来显示 root 是否可用。
 */
object RootManager {

    private const val TAG = "HyperPods-Root"
    private val packageNameRegex = Regex("^[A-Za-z0-9_.]+$")

    fun restartPackages(packages: Collection<String>): Boolean {
        val targets = packages.distinct().filter { it.matches(packageNameRegex) }
        if (targets.isEmpty()) return false

        return runCatching {
            val command = targets.joinToString("; ") { "am force-stop $it" }
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            process.waitFor() == 0
        }.getOrDefault(false)
    }

    fun hasRootAccess(): Boolean {
        return runRootText("echo yes")?.trim() == "yes"
    }

    private fun runRootText(command: String): String? {
        return runCatching {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            if (exitCode == 0) output else null
        }.onFailure { Log.e(TAG, "root text failed command=$command", it) }.getOrNull()
    }
}
