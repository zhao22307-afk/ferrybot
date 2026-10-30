package com.qqbot.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机自启（AUTO_START=1 才启动） */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val env = EnvManager(context)
        val cfg = ConfigStore(env)
        if (cfg.get("AUTO_START", "0") != "1") return
        if (!env.runtimeReady()) return
        BotService.start(context)
    }
}