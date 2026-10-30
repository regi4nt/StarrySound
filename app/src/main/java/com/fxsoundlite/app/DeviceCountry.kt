package com.fxsoundlite.app

import android.content.Context
import android.content.res.Resources
import android.telephony.TelephonyManager
import java.util.Locale

/**
 * The country the device is in, as a lowercase 2-letter code. SIM / network first, then the SYSTEM locale.
 * Locale.getDefault() is deliberately not used: the in-app language picker (see [LocaleHelper]) sets it to a
 * bare "id" / "en" with no country, which made every "local" shelf fall back to the US / worldwide.
 */
object DeviceCountry {

    private fun ok(c: String?): String? =
        c?.trim()?.lowercase(Locale.US)?.takeIf { it.length == 2 && it.all { ch -> ch in 'a'..'z' } }

    /** null when nothing tells the country. */
    fun codeOrNull(ctx: Context?): String? {
        val tm = runCatching { ctx?.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager }.getOrNull()
        return ok(runCatching { tm?.simCountryIso }.getOrNull())
            ?: ok(runCatching { tm?.networkCountryIso }.getOrNull())
            ?: ok(runCatching { Resources.getSystem().configuration.locales[0].country }.getOrNull())
    }

    fun code(ctx: Context?): String = codeOrNull(ctx) ?: "us"
}
