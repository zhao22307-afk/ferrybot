package com.qqbot.app

/**
 * 日志清洗工具。
 *
 * 两种情况会让日志看起来像"乱码"：
 * 1. 终端颜色控制符（例如 `\u001B[31merror\u001B[39m`）原样写进文件，界面上会看到 `[31m`、`[39m` 这种字符；
 * 2. 装在 Linux 环境里的程序（apt/dpkg/LinuxQQ 等）偶尔往标准输出打调试用的十六进制串（一行 32~64 个 0-9a-f），
 *    这些是它们的内部数据，对用户没有意义，但会一行一行刷屏。
 *
 * 这里统一过滤：去掉 ANSI 颜色与不可见控制字符，丢掉纯十六进制噪声行和空行。
 */
object LogText {

    private val ANSI = Regex("\u001B\\[[0-9;]*[A-Za-z]")
    private val HASH = Regex("^[0-9a-fA-F]{32,}$")
    private val CTRL = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]")

    /** 返回清洗后的文本；返回 null 表示这一行不值得记录（空行或纯噪声行）。 */
    fun clean(raw: String): String? {
        var s = ANSI.replace(raw, "")
        s = CTRL.replace(s, "")
        s = s.trimEnd()
        val t = s.trim()
        if (t.isEmpty()) return null
        if (HASH.matches(t)) return null
        return s
    }
}