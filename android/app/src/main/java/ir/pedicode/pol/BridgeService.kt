package ir.pedicode.pol

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import ir.pedicode.pol.ble.BleServer
import ir.pedicode.pol.data.License
import ir.pedicode.pol.data.PhoneData
import ir.pedicode.pol.data.Prefs
import ir.pedicode.pol.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.util.UUID

/** What the UI shows about the bridge. */
data class BridgeStatus(
    val running: Boolean = false,
    val unsupported: Boolean = false,
    val bluetoothOff: Boolean = false,
    val connected: Boolean = false,
    val iphoneName: String? = null,
    /** Code from the last refused pairing attempt, shown in the app to help the user. */
    val lastRejected: String? = null,
    /** Pairing code currently on screen. */
    val shownCode: String? = null,
)

object BridgeState {
    private val _status = MutableStateFlow(BridgeStatus())
    val status: StateFlow<BridgeStatus> = _status
    fun update(f: (BridgeStatus) -> BridgeStatus) { _status.value = f(_status.value) }
}

/**
 * Foreground service that keeps the BLE link alive and forwards
 * SMS, calls, contacts and call log to the paired iPhone.
 */
@SuppressLint("MissingPermission")
class BridgeService : Service(), BleServer.Listener {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var data: PhoneData
    private lateinit var control: CallControl
    private lateinit var ble: BleServer

    /** Devices that sent a valid hello on this connection. */
    private val authorized = HashSet<String>()

    private var callId: String? = null
    private var lastCallState = TelephonyManager.EXTRA_STATE_IDLE

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        data = PhoneData(this)
        control = CallControl(this, data)
        ble = BleServer(this, this)
        startAsForeground()

        // System broadcasts only; EXPORTED so every Android version delivers them.
        val flag = ContextCompat.RECEIVER_EXPORTED
        ContextCompat.registerReceiver(this, smsReceiver, IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION).apply { priority = 999 }, flag)
        ContextCompat.registerReceiver(this, phoneReceiver, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED), flag)
        ContextCompat.registerReceiver(this, btReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), flag)

        val adapterOn = getSystemService(android.bluetooth.BluetoothManager::class.java).adapter?.isEnabled == true
        ble.start()
        BridgeState.update {
            it.copy(running = true, bluetoothOff = !adapterOn, unsupported = adapterOn && !ble.supportsPeripheral)
        }
        main.postDelayed(statusTick, STATUS_INTERVAL)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(smsReceiver) }
        runCatching { unregisterReceiver(phoneReceiver) }
        runCatching { unregisterReceiver(btReceiver) }
        ble.stop()
        BridgeState.update { BridgeStatus(shownCode = it.shownCode, lastRejected = it.lastRejected) }
        super.onDestroy()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "اتصال پل", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("پل فعال است")
            .setContentText("پیامک‌ها و تماس‌ها به آیفون فرستاده می‌شوند")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        // Typed foreground services exist since Android 10.
        startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    }

    // ---- BLE events ----

    override fun onConnected(device: BluetoothDevice) {
        authorized.remove(device.address)
    }

    override fun onDisconnected(device: BluetoothDevice) {
        authorized.remove(device.address)
        BridgeState.update { it.copy(connected = false, iphoneName = null) }
    }

    override fun onSubscribed(device: BluetoothDevice) {
        // Wait for the iPhone's hello before sending anything.
    }

    override fun onMessage(device: BluetoothDevice, json: String) {
        val msg = runCatching { JSONObject(json) }.getOrNull() ?: return
        val type = msg.optString("t")

        if (type == "hello") {
            // Fall back to the Bluetooth address if the iPhone sent no id.
            val id = msg.optString("id").ifEmpty { "addr:" + device.address }
            val code = digitsOnly(msg.optString("code"))
            // The code shown on screen is always accepted, even if it differs from the stored one.
            val accepted = setOfNotNull(digitsOnly(prefs.pairingCode), BridgeState.status.value.shownCode?.let(::digitsOnly))
            val reason = when {
                id in prefs.trustedPhones -> null
                code.isEmpty() -> "no_code"
                code !in accepted -> "bad_code"
                else -> null
            }
            if (reason != null) {
                // Shown only on the Android screen (the owner's phone), to diagnose mismatches.
                val detail = "دریافتی ${code.ifEmpty { "-" }} · انتظار ${accepted.joinToString("/")} · $reason"
                BridgeState.update { it.copy(lastRejected = detail) }
                ble.send(
                    JSONObject().put("t", "denied").put("reason", reason).put("got", code)
                        .put("keys", msg.names()?.join(",") ?: "").toString()
                )
                return
            }
            prefs.trust(id)
            authorized.add(device.address)
            BridgeState.update { it.copy(lastRejected = null, connected = true, iphoneName = msg.optString("name").ifEmpty { "iPhone" }) }
            sendWelcome()
            if (License.isActive(this, prefs)) sendFullSync()
            return
        }
        if (device.address !in authorized) return
        if (!License.isActive(this, prefs)) {
            ble.send(JSONObject().put("t", "license").put("license", licenseJson()).toString())
            return
        }

        when (type) {
            "send_sms" -> {
                val ok = control.sendSms(msg.optString("to"), msg.optString("body"), msg.optInt("sim", 1))
                ble.send(JSONObject().put("t", "sent").put("ref", msg.optString("ref")).put("ok", ok).toString())
            }
            "call_answer" -> control.answer()
            "call_end" -> control.end()
            "dial" -> control.dial(msg.optString("to"), msg.optInt("sim", 1))
            "sync" -> sendFullSync()
            "ping" -> sendStatus()
        }
    }

    /** Accepts Persian/Arabic digits and stray spaces. */
    private fun digitsOnly(s: String): String = buildString {
        for (c in s) {
            val d = Character.digit(c, 10)
            if (d >= 0) append(d)
        }
    }

    private fun sendWelcome() {
        ble.send(
            JSONObject()
                .put("t", "welcome")
                .put("device", Build.MODEL)
                .put("sims", data.sims())
                .put("battery", data.battery())
                .put("trialDays", License.trialDaysLeft(prefs.firstRun))
                .put("license", licenseJson())
                .toString()
        )
    }

    private fun licenseJson(): JSONObject {
        val info = License.current(this, prefs)
        return JSONObject()
            .put("active", License.isActive(this, prefs))
            .put("plan", info?.plan ?: "trial")
            .put("daysLeft", info?.daysLeft ?: License.trialDaysLeft(prefs.firstRun))
    }

    private fun sendFullSync() {
        // Each block is its own message so the iPhone can show data as it arrives.
        Thread {
            val contacts = data.contacts()
            ble.send(JSONObject().put("t", "contacts").put("items", contacts).toString())
            ble.send(JSONObject().put("t", "sms_sync").put("items", data.recentSms()).toString())
            ble.send(JSONObject().put("t", "calls").put("items", data.callLog()).toString())
        }.start()
    }

    private fun sendStatus() {
        ble.send(
            JSONObject().put("t", "status").put("battery", data.battery()).put("sims", data.sims())
                .put("license", licenseJson()).toString()
        )
    }

    private val statusTick = object : Runnable {
        override fun run() {
            if (ble.isConnected) sendStatus()
            main.postDelayed(this, STATUS_INTERVAL)
        }
    }

    // ---- phone events ----

    private val smsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
            if (parts.isEmpty()) return
            val addr = parts[0].displayOriginatingAddress ?: return
            val body = parts.joinToString("") { it.displayMessageBody ?: "" }
            val subId = if (Build.VERSION.SDK_INT >= 30) {
                intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, -1)
            } else {
                intent.getIntExtra("subscription", -1)
            }
            val sms = data.sms(
                id = "n" + System.currentTimeMillis(),
                addr = addr,
                name = data.nameFor(addr),
                body = body,
                ts = parts[0].timestampMillis,
                incoming = true,
                sim = data.slotFor(subId),
            )
            if (License.isActive(this@BridgeService, prefs)) {
                ble.send(JSONObject().put("t", "sms").put("item", sms).toString())
            }
        }
    }

    private val phoneReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
            // Without READ_CALL_LOG the number is missing; the broadcast also fires twice.
            @Suppress("DEPRECATION")
            val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            if (state == lastCallState && number == null) return

            val out = JSONObject().put("t", "call")
            when (state) {
                TelephonyManager.EXTRA_STATE_RINGING -> {
                    if (number == null) return
                    if (lastCallState == TelephonyManager.EXTRA_STATE_RINGING && callId != null) return
                    callId = UUID.randomUUID().toString()
                    out.put("state", "ringing").put("addr", number).put("name", data.nameFor(number) ?: JSONObject.NULL)
                }
                TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                    if (callId == null) callId = UUID.randomUUID().toString()
                    out.put("state", "active")
                    if (number != null) out.put("addr", number)
                }
                TelephonyManager.EXTRA_STATE_IDLE -> {
                    if (callId == null) { lastCallState = state; return }
                    out.put("state", "ended")
                }
            }
            lastCallState = state
            out.put("id", callId)
            if (License.isActive(this@BridgeService, prefs)) ble.send(out.toString())
            if (state == TelephonyManager.EXTRA_STATE_IDLE) {
                callId = null
                // Refresh the call log shortly after the call is written.
                main.postDelayed({
                    Thread { ble.send(JSONObject().put("t", "calls").put("items", data.callLog()).toString()) }.start()
                }, 1500)
            }
        }
    }

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> {
                    ble.start()
                    BridgeState.update { it.copy(bluetoothOff = false, unsupported = !ble.supportsPeripheral) }
                }
                BluetoothAdapter.STATE_TURNING_OFF -> {
                    ble.stop()
                    BridgeState.update { it.copy(connected = false, iphoneName = null, bluetoothOff = true) }
                }
            }
        }
    }

    companion object {
        private const val CHANNEL = "bridge"
        private const val NOTIFICATION_ID = 1
        private const val STATUS_INTERVAL = 60_000L

        fun start(context: Context) {
            Prefs(context).serviceEnabled = true
            context.startForegroundService(Intent(context, BridgeService::class.java))
        }

        fun stop(context: Context) {
            Prefs(context).serviceEnabled = false
            context.stopService(Intent(context, BridgeService::class.java))
        }
    }
}
