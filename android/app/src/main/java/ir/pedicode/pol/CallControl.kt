package ir.pedicode.pol

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import android.telephony.SmsManager
import ir.pedicode.pol.data.PhoneData

/** Actions requested by the iPhone: send SMS, answer, end, dial. */
@SuppressLint("MissingPermission")
class CallControl(private val context: Context, private val data: PhoneData) {
    private val telecom = context.getSystemService(TelecomManager::class.java)

    fun sendSms(to: String, body: String, simSlot: Int): Boolean = runCatching {
        val subId = data.subIdForSlot(simSlot)
        val sms = if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(SmsManager::class.java).createForSubscriptionId(subId)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getSmsManagerForSubscriptionId(subId)
        }
        val parts = sms.divideMessage(body)
        if (parts.size > 1) sms.sendMultipartTextMessage(to, null, parts, null, null)
        else sms.sendTextMessage(to, null, body, null, null)
        true
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    fun answer() {
        runCatching { telecom.acceptRingingCall() }
    }

    @Suppress("DEPRECATION")
    fun end() {
        runCatching { telecom.endCall() }
    }

    fun dial(to: String, simSlot: Int) {
        runCatching {
            val extras = Bundle()
            val accounts = telecom.callCapablePhoneAccounts
            accounts.getOrNull(simSlot - 1)?.let {
                extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it)
            }
            telecom.placeCall(Uri.fromParts("tel", to, null), extras)
        }
    }
}
