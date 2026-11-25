package ir.pedicode.pol.data

import android.annotation.SuppressLint
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.BatteryManager
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SubscriptionManager
import org.json.JSONArray
import org.json.JSONObject

/** Reads SMS, contacts, call log, SIMs and battery from the Android phone. */
@SuppressLint("MissingPermission")
class PhoneData(private val context: Context) {
    private val resolver = context.contentResolver

    fun battery(): Int =
        context.getSystemService(BatteryManager::class.java)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    fun sims(): JSONArray {
        val out = JSONArray()
        val sm = context.getSystemService(SubscriptionManager::class.java)
        val list = runCatching { sm.activeSubscriptionInfoList }.getOrNull().orEmpty()
        for (info in list) {
            out.put(
                JSONObject()
                    .put("id", info.subscriptionId)
                    .put("slot", info.simSlotIndex + 1)
                    .put("name", info.displayName?.toString() ?: info.carrierName?.toString() ?: "")
            )
        }
        return out
    }

    /** Maps a subscription id to the SIM slot number shown to users (1 or 2). */
    fun slotFor(subId: Int): Int {
        if (subId < 0) return 1
        val sm = context.getSystemService(SubscriptionManager::class.java)
        val info = runCatching { sm.getActiveSubscriptionInfo(subId) }.getOrNull()
        return (info?.simSlotIndex ?: 0) + 1
    }

    fun subIdForSlot(slot: Int): Int {
        val sm = context.getSystemService(SubscriptionManager::class.java)
        val list = runCatching { sm.activeSubscriptionInfoList }.getOrNull().orEmpty()
        return list.firstOrNull { it.simSlotIndex + 1 == slot }?.subscriptionId
            ?: SubscriptionManager.getDefaultSmsSubscriptionId()
    }

    fun nameFor(number: String?): String? {
        if (number.isNullOrBlank()) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return runCatching {
            resolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()
    }

    fun recentSms(limit: Int = 300): JSONArray {
        val out = JSONArray()
        val names = HashMap<String, String?>()
        val projection = arrayOf(
            Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
            Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.SUBSCRIPTION_ID,
        )
        runCatching {
            resolver.query(Telephony.Sms.CONTENT_URI, projection, null, null, "${Telephony.Sms.DATE} DESC")?.use { c ->
                while (c.moveToNext() && out.length() < limit) {
                    val addr = c.str(1) ?: continue
                    val name = names.getOrPut(addr) { nameFor(addr) }
                    out.put(
                        sms(
                            id = c.getLong(0).toString(),
                            addr = addr,
                            name = name,
                            body = c.str(2) ?: "",
                            ts = c.getLong(3),
                            incoming = c.getInt(4) == Telephony.Sms.MESSAGE_TYPE_INBOX,
                            sim = slotFor(c.getInt(5)),
                        )
                    )
                }
            }
        }
        return out
    }

    fun sms(id: String, addr: String, name: String?, body: String, ts: Long, incoming: Boolean, sim: Int): JSONObject =
        JSONObject()
            .put("id", id)
            .put("addr", addr)
            .put("name", name ?: JSONObject.NULL)
            .put("body", body)
            .put("ts", ts)
            .put("dir", if (incoming) "in" else "out")
            .put("sim", sim)

    fun contacts(): JSONArray {
        val byId = LinkedHashMap<Long, JSONObject>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.STARRED,
        )
        runCatching {
            resolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE LOCALIZED ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val number = c.str(2) ?: continue
                    val obj = byId.getOrPut(id) {
                        JSONObject()
                            .put("id", id.toString())
                            .put("name", c.str(1) ?: number)
                            .put("fav", c.getInt(3) == 1)
                            .put("phones", JSONArray())
                    }
                    val phones = obj.getJSONArray("phones")
                    val clean = number.replace(" ", "").replace("-", "")
                    var dup = false
                    for (i in 0 until phones.length()) if (phones.getString(i) == clean) dup = true
                    if (!dup) phones.put(clean)
                }
            }
        }
        return JSONArray(byId.values)
    }

    fun callLog(limit: Int = 150): JSONArray {
        val out = JSONArray()
        val projection = arrayOf(
            CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.DATE,
            CallLog.Calls.TYPE, CallLog.Calls.DURATION, CallLog.Calls.PHONE_ACCOUNT_ID,
        )
        runCatching {
            resolver.query(CallLog.Calls.CONTENT_URI, projection, null, null, "${CallLog.Calls.DATE} DESC")?.use { c ->
                while (c.moveToNext() && out.length() < limit) {
                    val type = when (c.getInt(3)) {
                        CallLog.Calls.INCOMING_TYPE -> "in"
                        CallLog.Calls.OUTGOING_TYPE -> "out"
                        CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE -> "missed"
                        else -> "in"
                    }
                    out.put(
                        JSONObject()
                            .put("addr", c.str(0) ?: "")
                            .put("name", c.str(1) ?: JSONObject.NULL)
                            .put("ts", c.getLong(2))
                            .put("type", type)
                            .put("dur", c.getLong(4))
                            .put("sim", c.str(5)?.toIntOrNull()?.let { slotFor(it) } ?: 1)
                    )
                }
            }
        }
        return out
    }

    private fun Cursor.str(i: Int): String? = if (isNull(i)) null else getString(i)
}
