package ir.pedicode.pol

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ir.pedicode.pol.data.Prefs

/** Restarts the bridge after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Prefs(context).serviceEnabled) {
            runCatching { BridgeService.start(context) }
        }
    }
}
