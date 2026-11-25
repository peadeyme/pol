package ir.pedicode.pol

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import ir.pedicode.pol.data.PhoneData

/** Actions requested by the iPhone: send SMS, answer, end, dial. */
@SuppressLint("MissingPermission")
class CallControl(
    private val context: Context,
    private val data: PhoneData,
    /** Called with (ref, ok, error) once the radio reports the real result of an SMS. */
    private val onSmsResult: (String, Boolean, String?) -> Unit,
) {
    private val telecom = context.getSystemService(TelecomManager::class.java)
    private var requestCode = 0

    private val sentReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val ref = intent.getStringExtra(EXTRA_REF) ?: return
            val ok = resultCode == Activity.RESULT_OK
            val error = if (ok) null else when (resultCode) {
                SmsManager.RESULT_ERROR_NO_SERVICE -> "no_service"
                SmsManager.RESULT_ERROR_RADIO_OFF -> "radio_off"
                SmsManager.RESULT_ERROR_NULL_PDU -> "null_pdu"
                else -> "error_$resultCode"
            }
            onSmsResult(ref, ok, error)
        }
    }

    init {
        ContextCompat.registerReceiver(context, sentReceiver, IntentFilter(ACTION_SENT), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun close() {
        runCatching { context.unregisterReceiver(sentReceiver) }
    }

    /** Returns an error text if sending could not even start. */
    fun sendSms(ref: String, to: String, body: String, simSlot: Int): String? = try {
        val subId = data.subIdForSlot(simSlot)
        val sms = if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(SmsManager::class.java).createForSubscriptionId(subId)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getSmsManagerForSubscriptionId(subId)
        }
        val sent = PendingIntent.getBroadcast(
            context, requestCode++,
            Intent(ACTION_SENT).setPackage(context.packageName).putExtra(EXTRA_REF, ref),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val parts = sms.divideMessage(body)
        if (parts.size > 1) {
            // Only the last part reports back; the message counts as sent when it is.
            val intents = ArrayList<PendingIntent?>(parts.size)
            repeat(parts.size - 1) { intents.add(null) }
            intents.add(sent)
            sms.sendMultipartTextMessage(to, null, parts, intents, null)
        } else {
            sms.sendTextMessage(to, null, body, sent, null)
        }
        null
    } catch (e: Exception) {
        e.javaClass.simpleName + ": " + (e.message ?: "")
    }

    @Suppress("DEPRECATION")
    fun answer(): String? = runCatching { telecom.acceptRingingCall() }.exceptionOrNull()?.message

    @Suppress("DEPRECATION")
    fun end(): String? = runCatching { telecom.endCall() }.exceptionOrNull()?.message

    /** Returns an error text when the call could not be placed. */
    fun dial(to: String, simSlot: Int): String? = try {
        val extras = Bundle()
        val accounts = telecom.callCapablePhoneAccounts
        accounts.getOrNull(simSlot - 1)?.let {
            extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it)
        }
        telecom.placeCall(Uri.fromParts("tel", to, null), extras)
        null
    } catch (e: Exception) {
        e.javaClass.simpleName + ": " + (e.message ?: "")
    }

    companion object {
        private const val ACTION_SENT = "ir.pedicode.pol.SMS_SENT"
        private const val EXTRA_REF = "ref"
    }
}
