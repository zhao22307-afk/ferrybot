package com.qqbot.app

import android.content.Context
import org.tukaani.xz.XZInputStream
import java.io.File

/**
 * 把 APK 里的 assets 铺到应用私有目录，并提供路径工具。
 *
 * filesDir/
 *   runtime/  node 可执行文件 + 依赖的 .so
 *   bot/      机器人代码（bot.mjs / lib / node_modules / prompt.txt）
 *   logs/     运行日志
 *   bot/emoji/ 表情包（和机器人代码里的 EMOJI_DIR 一致）
 */
class EnvManager(context: Context) {

    private val app: Context = context.applicationContext

    fun dir(name: String): File = File(app.filesDir, name).apply { if (!exists()) mkdirs() }

    val runtimeDir: File get() = dir(DIR_RUNTIME)
    val botDir: File get() = dir(DIR_BOT)
    val logDir: File get() = dir(DIR_LOGS)
    val emojiDir: File get() = dir("$DIR_BOT/emoji")
    val imageDir: File get() = dir("$DIR_BOT/image")
    val skillsDir: File get() = dir("$DIR_BOT/skills")

    val nodeBin: File get() = File(runtimeDir, "node")
    val logFile: File get() = File(logDir, "bot.log")
    /** 机器人自己写的日志（node 侧 log.mjs 直接落盘，内容比 stdout 全） */
    val botLogFile: File get() = File(botDir, "logs/qqbot.log")
    val envFile: File get() = File(botDir, ".env")

    /** 最近一次铺运行时的失败原因（给界面显示，避免只说「失败」不知道为啥） */
    @Volatile
    var lastError: String = ""
        private set

    /** filesDir 所在分区剩余空间（MB，-1 表示取不到） */
    fun freeSpaceMb(): Long = runCatching {
        val stat = android.os.StatFs(app.filesDir.absolutePath)
        stat.availableBytes / (1024L * 1024L)
    }.getOrDefault(-1L)

    /**
     * 运行时是否已铺好。
     *
     * 除了 node 在不在，还要比对运行时版本戳：早期版本漏了 libicudata.so.78
     * （node 的 libicuuc.so.78 依赖它，缺了直接 CANNOT LINK EXECUTABLE）。
     * 升级 App 后必须重铺一次，所以这里用版本戳而不是只看文件存在。
     */
    fun runtimeReady(): Boolean {
        val stamp = File(runtimeDir, RUNTIME_STAMP)
        if (!stamp.exists() || stamp.readText().trim() != RUNTIME_VERSION) return false
        return nodeBin.exists() && nodeBin.canExecute()
    }

    /** 铺运行时（node + .so），返回是否成功；失败原因写在 [lastError] */
    fun installRuntime(): Boolean {
        lastError = ""
        val free = freeSpaceMb()
        if (free in 0 until RUNTIME_NEED_MB) {
            lastError = "存储空间不足：铺开运行时要约 ${RUNTIME_NEED_MB}MB，当前只剩 ${free}MB"
            writeInstallLog(lastError)
            return false
        }
        // 三条路依次试：①assets 直读解压 ②先拷到 filesDir 再解压（老 Android 上 assets 流有读不全的坑）
        // ③清空 runtime 重来一次（上次留下的半截文件 / 坏时间戳）
        for (attempt in 1..2) {
            try {
                copyAssetDir("runtime", runtimeDir, overwrite = true, skipPacked = true)
                unpackAssetXz("runtime/$NODE_PACKED", NODE_NAME)
                unpackAssetXz("runtime/$ICU_DATA_PACKED", ICU_DATA_NAME)
                break
            } catch (e: Throwable) {
                writeInstallLog("第 $attempt 次解压失败（直读）：${describe(e)}")
                if (attempt == 2) {
                    // 直读两次都不行，退回「先拷到本地再解压」
                    try {
                        copyAssetDir("runtime", runtimeDir, overwrite = true, skipPacked = false)
                        unpackLocalXz(NODE_PACKED, NODE_NAME)
                        unpackLocalXz(ICU_DATA_PACKED, ICU_DATA_NAME)
                        break
                    } catch (e2: Throwable) {
                        // 最后清空重来
                        runCatching { wipeRuntime() }
                        try {
                            copyAssetDir("runtime", runtimeDir, overwrite = true, skipPacked = false)
                            unpackLocalXz(NODE_PACKED, NODE_NAME)
                            unpackLocalXz(ICU_DATA_PACKED, ICU_DATA_NAME)
                            break
                        } catch (e3: Throwable) {
                            lastError = "解压失败：${describe(e3)}（直读：${describe(e)}；拷贝：${describe(e2)}）"
                            writeInstallLog(lastError)
                            return false
                        }
                    }
                }
            }
        }
        val n = nodeBin
        val icu = File(runtimeDir, ICU_DATA_NAME)
        if (!n.exists() || n.length() < NODE_EXPECT_MIN) {
            lastError = "node 解压不完整：${n.length()} 字节（应为 $NODE_EXPECT）"
            writeInstallLog(lastError)
            return false
        }
        if (!icu.exists() || icu.length() < ICU_EXPECT_MIN) {
            lastError = "ICU 数据文件不完整：${icu.length()} 字节（应为 $ICU_EXPECT）"
            writeInstallLog(lastError)
            return false
        }
        n.setExecutable(true, false)
        runtimeDir.listFiles()?.forEach { f ->
            if (f.name.contains(".so")) f.setReadable(true, false)
        }
        File(runtimeDir, RUNTIME_STAMP).writeText(RUNTIME_VERSION)
        if (!nodeBin.canExecute()) {
            lastError = "node 解压出来了但不能执行（${nodeBin.length()} 字节）；多半是文件权限或挂载限制"
            writeInstallLog(lastError)
            return false
        }
        return true
    }

    private fun describe(e: Throwable): String =
        "${e.javaClass.simpleName}: ${e.message ?: ""}".ifBlank { e.javaClass.simpleName }

    /** 从 assets 里直读 xz 解压到 runtime 目录；目标已存在先删掉，避免半截文件 */
    private fun unpackAssetXz(assetPath: String, targetName: String) {
        val target = File(runtimeDir, targetName)
        runCatching { if (target.exists()) target.delete() }
        app.assets.open(assetPath, android.content.res.AssetManager.ACCESS_STREAMING).use { input ->
            XZInputStream(input, XZ_MEM_LIMIT_KB).use { xz ->
                target.outputStream().use { out -> xz.copyTo(out, 1 shl 16) }
            }
        }
    }

    /** 保守路径：assets 里的 xz 先拷到 runtime 目录再解压（解完删掉副本） */
    private fun unpackLocalXz(packedName: String, targetName: String) {
        val packed = File(runtimeDir, packedName)
        if (!packed.exists()) error("assets 里没有 $packedName")
        val target = File(runtimeDir, targetName)
        runCatching { if (target.exists()) target.delete() }
        packed.inputStream().use { input ->
            XZInputStream(input, XZ_MEM_LIMIT_KB).use { xz ->
                target.outputStream().use { out -> xz.copyTo(out, 1 shl 16) }
            }
        }
        packed.delete()
    }

    /** 环境诊断（给「复制诊断信息」按钮用）：不做修改，只报事实 */
    fun diagnostics(): String = buildString {
        appendLine("App version: ${appVersionName()}")
        appendLine("filesDir: ${app.filesDir.absolutePath}")
        appendLine("可用空间: ${freeSpaceMb()}MB")
        appendLine("runtimeReady: ${runtimeReady()}  版本戳: ${runCatching { File(runtimeDir, RUNTIME_STAMP).readText().trim() }.getOrDefault("-")}")
        appendLine("runtime 目录：")
        runtimeDir.listFiles()?.sortedBy { it.name }?.forEach { f ->
            appendLine("  ${f.name}  ${f.length()}")
        } ?: appendLine("  (不存在)")
        appendLine("assets/runtime：")
        runCatching {
            app.assets.list("runtime")?.sorted()?.forEach { name ->
                val sz = runCatching { app.assets.openFd("runtime/$name").use { it.length } }.getOrNull()
                appendLine("  $name  ${sz ?: "?"}")
            }
        }
        appendLine("node.xz 头 6 字节: ${runCatching { magicOf("runtime/$NODE_PACKED") }.getOrDefault("-")}")
        appendLine("ICU.xz 头 6 字节: ${runCatching { magicOf("runtime/$ICU_DATA_PACKED") }.getOrDefault("-")}")
        appendLine("上次失败: ${lastError.ifBlank { "-" }}")
    }

    private fun magicOf(assetPath: String): String =
        app.assets.open(assetPath).use { input ->
            val b = ByteArray(6)
            val n = input.read(b)
            (0 until n).joinToString(" ") { "%02X".format(b[it]) }
        }

    private fun appVersionName(): String = runCatching {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    /** 把铺设失败的原因记到 filesDir/runtime-install.log，用户可以直接把这几行发过来 */
    private fun writeInstallLog(msg: String) {
        runCatching {
            val f = File(app.filesDir, "runtime-install.log")
            val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            f.appendText("[$ts] $msg（剩余 ${freeSpaceMb()}MB）\n")
        }
    }

    /**
     * 铺机器人代码。
     *
     * 装过旧版本时 filesDir/bot 已经有文件了，只 copy 不覆盖的话 JS 改动永远不生效
     * （表现就是「改了代码/app 升级了，启动还是老行为」）。所以这里用版本戳：
     * 版本不一致 → 删掉旧代码重新铺；但用户的 data/、emoji/、logs/、.env、prompt.txt 都保留。
     */
    fun installBot(): Boolean = try {
        if (!botDir.exists()) botDir.mkdirs()
        val stamp = File(botDir, CODE_STAMP)
        val needUpgrade = !stamp.exists() || stamp.readText().trim() != BOT_CODE_VERSION
        if (needUpgrade) {
            botDir.listFiles()?.forEach { f ->
                if (f.name !in KEEP_ON_UPGRADE) deleteRecursive(f)
            }
            // overwrite=false：上面已删掉代码文件，剩下的 prompt.txt/.env/data 不会被 assets 覆盖
            copyAssetDir("bot", botDir, overwrite = false)
            stamp.writeText(BOT_CODE_VERSION)
        } else {
            copyAssetDir("bot", botDir, overwrite = false)
        }
        File(botDir, "bot.mjs").exists()
    } catch (e: Exception) {
        false
    }

    /** 强制重铺代码（用户点「重置机器人代码」时用），人设与记忆保留 */
    fun reinstallBot(): Boolean {
        runCatching { File(botDir, CODE_STAMP).delete() }
        return installBot()
    }

    private fun copyAssetDir(assetPath: String, target: File, overwrite: Boolean, skipPacked: Boolean = false) {
        val children = app.assets.list(assetPath)
        if (children.isNullOrEmpty()) {
            copyAssetFile(assetPath, target)
            return
        }
        if (!target.exists()) target.mkdirs()
        for (child in children) {
            // 压缩包（node.xz / ICU 数据）由 unpackAssetXz 直接从 assets 解压，不先拷一份
            if (skipPacked && child.endsWith(PACK_SUFFIX)) continue
            val childAsset = "$assetPath/$child"
            // .so.zz → .so：见 installRuntime 的说明
            val realName = if (child.endsWith(COMPRESS_SUFFIX)) child.dropLast(COMPRESS_SUFFIX.length) else child
            val childTarget = File(target, realName)
            val sub = app.assets.list(childAsset)
            if (!sub.isNullOrEmpty()) {
                copyAssetDir(childAsset, childTarget, overwrite, skipPacked)
            } else {
                if (!overwrite && childTarget.exists()) continue
                copyAssetFile(childAsset, childTarget)
            }
        }
    }

    private fun copyAssetFile(assetPath: String, target: File) {
        target.parentFile?.let { if (!it.exists()) it.mkdirs() }
        app.assets.open(assetPath).use { input ->
            target.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
        }
    }

    fun wipeRuntime() = deleteRecursive(runtimeDir)

    companion object {
        const val DIR_RUNTIME = "runtime"
        const val DIR_BOT = "bot"
        const val DIR_LOGS = "logs"

        /** 代码版本戳：改了 assets/bot 里的 JS 就把它 +1，装过的手机才会重新铺代码 */
        const val BOT_CODE_VERSION = "7"
        private const val CODE_STAMP = ".code-version"

        /** 运行时版本戳：assets/runtime 里的二进制换了就 +1，老机器才会重铺 */
        private const val RUNTIME_VERSION = "3"
        private const val RUNTIME_STAMP = ".runtime-version"

        /** 铺垫运行时大约要的剩余空间（MB）：node 45 + ICU 数据 33 + 其它 .so 15，留余量 */
        const val RUNTIME_NEED_MB = 110L

        /** xz 解压允许占用的内存（KiB）：assets 里是这个字典大小 8MiB，留 4 倍余量，别贴着 64MiB 上限 */
        private const val XZ_MEM_LIMIT_KB = 32 * 1024

        /** 只为让 APK 不重复压缩而用的打包后缀（从 assets 直读解压） */
        private const val PACK_SUFFIX = ".xz"

        /** assets 里 node 的压缩文件名（xz，省安装包体积） */
        private const val NODE_PACKED = "node.xz"
        private const val NODE_NAME = "node"
        /** node 解压后应为 45,318,136 字节；低于这个数的 90% 视为没解完 */
        private const val NODE_EXPECT = 45_318_136L
        private const val NODE_EXPECT_MIN = NODE_EXPECT * 9 / 10

        /** ICU 数据文件（33MB，xz 后 7.6MB）；node 依赖它 */
        private const val ICU_DATA_PACKED = "libicudata.so.78.xz"
        private const val ICU_DATA_NAME = "libicudata.so.78"
        /** 解压后应为 33,108,832 字节 */
        private const val ICU_EXPECT = 33_108_832L
        private const val ICU_EXPECT_MIN = ICU_EXPECT * 9 / 10
        /** 只为让 AGP 压缩而加的后缀（.so.zz → 解包时还原成 .so） */
        private const val COMPRESS_SUFFIX = ".zz"

        /** 升级代码时要保住的东西（用户数据 + 人设 + 凭证 + 自己装的插件/图片） */
        private val KEEP_ON_UPGRADE =
            setOf("data", "emoji", "image", "skills", "logs", ".env", "prompt.txt", CODE_STAMP)

        fun deleteRecursive(f: File?) {
            if (f == null || !f.exists()) return
            f.listFiles()?.forEach { deleteRecursive(it) }
            f.delete()
        }
    }
}