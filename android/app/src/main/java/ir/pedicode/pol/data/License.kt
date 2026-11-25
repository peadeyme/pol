package ir.pedicode.pol.data

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.util.Base64
import org.json.JSONObject
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.TimeUnit

/**
 * Plans, trial and offline license check.
 *
 * A license code is `base64url(payload).base64url(signature)`, where the payload is
 * {"d": device code, "p": "m"|"s"|"y", "e": expiry (unix seconds)} signed with ECDSA P-256.
 * Only the public key lives here, so the code stays safe in a public repository:
 * nobody can make a valid license without the private key (kept by the seller,
 * used by tools/license.py).
 */
object License {
    const val TRIAL_DAYS = 7
    const val BUY_URL = "https://pedicode.ir"

    private const val PUBLIC_KEY =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEXbBu62fjl6xP3Bfr7ZTawNh+2pTnH3aoPpBcbZ8dYG10w6Q+06T/hoA08E+hsVWF7PxqR6RpjKO7bK0KBWNcRQ=="

    data class Plan(val title: String, val price: String, val note: String)

    val plans = listOf(
        Plan("ماهانه", "۳۰۰٬۰۰۰ تومان", "۳۱ روز"),
        Plan("شش‌ماهه", "۱٬۵۰۰٬۰۰۰ تومان", "۱۸۳ روز — ۱۷٪ ارزان‌تر"),
        Plan("سالانه", "۲٬۵۰۰٬۰۰۰ تومان", "۳۶۶ روز — ۳۱٪ ارزان‌تر"),
    )

    data class Info(val plan: String, val expiresAt: Long) {
        val daysLeft: Int
            get() = TimeUnit.SECONDS.toDays(expiresAt - System.currentTimeMillis() / 1000).toInt().coerceAtLeast(0)
        val planName: String get() = when (plan) { "y" -> "سالانه"; "s" -> "شش‌ماهه"; "m" -> "ماهانه"; else -> "اشتراک" }
    }

    /** Short code the customer sends to the seller, e.g. "7F3A2-9C41B". Stable for this app on this phone. */
    @SuppressLint("HardwareIds")
    fun deviceCode(context: Context): String {
        val raw = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        val hash = MessageDigest.getInstance("SHA-256").digest(("pol:" + raw).toByteArray())
        val hex = hash.joinToString("") { "%02X".format(it) }.take(10)
        return hex.substring(0, 5) + "-" + hex.substring(5)
    }

    /** Returns license details when the code is genuine, for this phone, and not expired. */
    fun verify(code: String?, deviceCode: String): Info? {
        if (code.isNullOrBlank()) return null
        return runCatching {
            val parts = code.trim().split(".")
            if (parts.size != 2) return null
            val payload = b64(parts[0])
            val signature = b64(parts[1])
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.decode(PUBLIC_KEY, Base64.DEFAULT)))
            val ok = Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update(payload)
                verify(signature)
            }
            if (!ok) return null
            val json = JSONObject(String(payload, Charsets.UTF_8))
            if (json.optString("d") != deviceCode) return null
            val info = Info(json.optString("p", "m"), json.getLong("e"))
            if (info.expiresAt * 1000 < System.currentTimeMillis()) null else info
        }.getOrNull()
    }

    fun trialDaysLeft(firstRun: Long, now: Long = System.currentTimeMillis()): Int {
        val used = TimeUnit.MILLISECONDS.toDays(now - firstRun).toInt()
        return (TRIAL_DAYS - used).coerceAtLeast(0)
    }

    fun current(context: Context, prefs: Prefs): Info? = verify(prefs.licenseCode, deviceCode(context))

    fun isActive(context: Context, prefs: Prefs): Boolean =
        current(context, prefs) != null || trialDaysLeft(prefs.firstRun) > 0

    private fun b64(s: String): ByteArray =
        Base64.decode(s, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}
