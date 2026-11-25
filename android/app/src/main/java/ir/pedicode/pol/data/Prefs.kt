package ir.pedicode.pol.data

import android.content.Context
import java.security.SecureRandom

/** Small persistent settings: pairing code and the iPhones allowed to connect. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("pol", Context.MODE_PRIVATE)

    val pairingCode: String
        get() = sp.getString(KEY_CODE, null) ?: newPairingCode()

    fun newPairingCode(): String {
        val code = (SecureRandom().nextInt(900_000) + 100_000).toString()
        sp.edit().putString(KEY_CODE, code).apply()
        return code
    }

    val trustedPhones: Set<String>
        get() = sp.getStringSet(KEY_TRUSTED, emptySet()) ?: emptySet()

    fun trust(id: String) {
        sp.edit().putStringSet(KEY_TRUSTED, trustedPhones + id).apply()
    }

    fun forgetAll() {
        sp.edit().remove(KEY_TRUSTED).apply()
        newPairingCode()
    }

    var serviceEnabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, false)
        set(v) = sp.edit().putBoolean(KEY_ENABLED, v).apply()

    /** First launch time, used for the trial period. */
    val firstRun: Long
        get() {
            val v = sp.getLong(KEY_FIRST_RUN, 0L)
            if (v != 0L) return v
            val now = System.currentTimeMillis()
            sp.edit().putLong(KEY_FIRST_RUN, now).apply()
            return now
        }

    var licenseCode: String?
        get() = sp.getString(KEY_LICENSE, null)
        set(v) = sp.edit().putString(KEY_LICENSE, v).apply()

    companion object {
        private const val KEY_LICENSE = "license"
        private const val KEY_CODE = "pairing_code"
        private const val KEY_TRUSTED = "trusted"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_FIRST_RUN = "first_run"
    }
}
