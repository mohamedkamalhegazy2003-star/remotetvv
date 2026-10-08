package com.remotetv.app

import android.content.Context
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File

/** Optional: hard shutdown through ADB, only used when ADB happens to be enabled on the TV. */
object AdbShutdown {
    fun run(ctx: Context, host: String): String? {
        if (!NetScan.portOpen(host, Atv.ADB_PORT, 2_000)) {
            return "ADB is not enabled on the TV - use “Open power menu” instead"
        }
        val priv = File(ctx.filesDir, "adbkey")
        val pub = File(ctx.filesDir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        val d = Dadb.create(host, Atv.ADB_PORT, AdbKeyPair.read(priv, pub))
        try {
            d.shell(
                "reboot -p || svc power shutdown || " +
                    "am start -a com.android.internal.intent.action.REQUEST_SHUTDOWN " +
                    "--ez android.intent.extra.KEY_CONFIRM false --activity-clear-task"
            )
        } finally {
            runCatching { d.close() }
        }
        return null
    }
}
