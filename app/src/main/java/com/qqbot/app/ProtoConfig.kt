package com.qqbot.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

/**
 * 协议端的配置文件 + 鉴权 token。
 *
 * 手机本地跑协议端时，rootfs 就在 App 私有目录里，所以 App 可以直接往里写配置
 * （比在 proot 里 echo 一堆 JSON 靠谱得多）。token 由 App 自己生成，
 * 这样既不用去日志里 grep NapCat 的随机 token，也不会每次重装就变。
 */
object ProtoConfig {

    data class Tokens(val webui: String, val ws: String)

    fun randomHex(bytes: Int = 16): String {
        val rnd = SecureRandom()
        val b = ByteArray(bytes)
        rnd.nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    /** 读/生成 token（存在 linux/run/proto-tokens.json，重启不变） */
    fun tokens(runDir: File): Tokens {
        val f = File(runDir, "proto-tokens.json")
        val o = runCatching { JSONObject(f.readText()) }.getOrNull()
        var webui = o?.optString("webui").orEmpty()
        var ws = o?.optString("ws").orEmpty()
        var changed = false
        if (webui.isBlank()) {
            webui = randomHex()
            changed = true
        }
        if (ws.isBlank()) {
            ws = randomHex()
            changed = true
        }
        if (changed) {
            runCatching {
                f.parentFile?.mkdirs()
                f.writeText(JSONObject().put("webui", webui).put("ws", ws).toString(), Charsets.UTF_8)
            }
        }
        return Tokens(webui, ws)
    }

    fun napCatAppDir(rootfs: File): File =
        File(rootfs, "root/Napcat/opt/QQ/resources/app")

    fun napCatConfigDir(rootfs: File): File =
        File(napCatAppDir(rootfs), "app_launcher/napcat/config")

    /**
     * 写 NapCat 的配置：WebUI token、OneBot v11 的 WebSocket 服务器、钉 QQ 版本。
     * @return 写成功时返回 token（App 要用 ws token 去连 OneBot、用 webui token 取二维码）
     */
    fun writeNapCat(linux: LinuxEnv, wsPort: Int, webuiPort: Int): Tokens? {
        if (!napCatAppDir(linux.rootfsDir).exists()) return null
        val t = tokens(linux.runDir)
        return runCatching {
            // 1) WebUI（我们自己定 token，App 才能直接调 API 取二维码）
            val webui = JSONObject()
                .put("host", "127.0.0.1")
                .put("port", webuiPort)
                .put("token", t.webui)
                .put("loginRate", 3)
            val cfgDir = napCatConfigDir(linux.rootfsDir).apply { mkdirs() }
            File(cfgDir, "webui.json").writeText(webui.toString(2), Charsets.UTF_8)

            // 2) OneBot v11 WebSocket 服务端（只监听本机，不给局域网开）
            val wsServer = JSONObject()
                .put("name", "ferry")
                .put("enable", true)
                .put("host", "127.0.0.1")
                .put("port", wsPort)
                .put("messagePostFormat", "array")
                .put("reportSelfMessage", false)
                .put("token", t.ws)
                .put("enableForcePushEvent", true)
                .put("debug", false)
                .put("heartInterval", 30000)
            val onebot = JSONObject()
                .put(
                    "network",
                    JSONObject()
                        .put("httpServers", JSONArray())
                        .put("httpClients", JSONArray())
                        .put("websocketClients", JSONArray())
                        .put("websocketServers", JSONArray().put(wsServer))
                )
                .put("musicSignUrl", "")
                .put("enableLocalFile2Url", false)
                .put("parseMultMsg", false)
            File(cfgDir, "onebot11.json").writeText(onebot.toString(2), Charsets.UTF_8)

            // 3) 钉住 QQ 版本，否则 QQ 会自己弹更新然后 NapCat 注入失效
            val verDir = File(linux.rootfsDir, "root/.config/QQ/versions").apply { mkdirs() }
            val ver = JSONObject()
                .put("baseVersion", "3.2.34-53644")
                .put("curVersion", "3.2.34-53644")
                .put("buildId", "53644")
            File(verDir, "config.json").writeText(ver.toString(), Charsets.UTF_8)
            linux.log("已写入协议端配置：ws=$wsPort webui=$webuiPort")
            t
        }.getOrNull()
    }
}