package com.fxsoundlite.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Spotify sign-in with OAuth 2.0 Authorization Code + PKCE (no client secret, so nothing secret ships in the app).
 *
 * Every user registers THEIR OWN Spotify app (developer.spotify.com/dashboard; Spotify now requires Premium for
 * development-mode apps and allows 5 users per Client ID), adds [REDIRECT_URI] as a redirect URI and pastes the
 * Client ID in Settings > Spotify. Tokens stay in this app's private preferences (not backed up to Drive).
 */
object SpotifyAuth {
    const val PKG = "com.spotify.music"
    const val REDIRECT_URI = "starrysound://spotify-callback"
    private const val PREFS = "spotify"
    private const val AUTH_URL = "https://accounts.spotify.com/authorize"
    private const val TOKEN_URL = "https://accounts.spotify.com/api/token"
    private const val SCOPES = "playlist-read-private playlist-read-collaborative user-library-read " +
        "user-read-playback-state user-modify-playback-state"

    class AuthException(message: String) : Exception(message)

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun clientId(c: Context): String = prefs(c).getString("client_id", "").orEmpty().trim()
    fun setClientId(c: Context, v: String) {
        val clean = v.filter { it.isLetterOrDigit() }
        if (clean != clientId(c)) { prefs(c).edit().putString("client_id", clean).apply(); disconnect(c) }
    }

    fun isConnected(c: Context): Boolean = prefs(c).getString("refresh", "").orEmpty().isNotEmpty()

    /** Apply the equalizer to the Spotify app's audio (default on). */
    fun eqEnabled(c: Context): Boolean = prefs(c).getBoolean("eq", true)
    fun setEqEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean("eq", on).apply()

    fun disconnect(c: Context) {
        prefs(c).edit().remove("access").remove("refresh").remove("expires").apply()
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    /** The browser intent that starts the sign-in. The result comes back to [SpotifyCallbackActivity]. */
    fun loginIntent(c: Context): Intent {
        val rnd = SecureRandom()
        val verifier = b64(ByteArray(48).also { rnd.nextBytes(it) })
        val state = b64(ByteArray(16).also { rnd.nextBytes(it) })
        val challenge = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        prefs(c).edit().putString("verifier", verifier).putString("state", state).apply()
        val url = AUTH_URL + "?response_type=code" +
            "&client_id=" + enc(clientId(c)) +
            "&scope=" + enc(SCOPES) +
            "&redirect_uri=" + enc(REDIRECT_URI) +
            "&state=" + enc(state) +
            "&code_challenge_method=S256&code_challenge=" + enc(challenge)
        return Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Blocking: call from a background thread. Trades the redirect's code for tokens. */
    fun finishLogin(c: Context, data: Uri) {
        val p = prefs(c)
        data.getQueryParameter("error")?.let { throw AuthException(it) }
        if (data.getQueryParameter("state").orEmpty() != p.getString("state", "").orEmpty()) throw AuthException("state mismatch")
        val code = data.getQueryParameter("code") ?: throw AuthException("no code")
        store(c, tokenRequest(form(
            "grant_type" to "authorization_code", "code" to code, "redirect_uri" to REDIRECT_URI,
            "client_id" to clientId(c), "code_verifier" to p.getString("verifier", "").orEmpty()
        )))
        p.edit().remove("verifier").remove("state").apply()
    }

    /** Blocking. A valid access token, refreshed first when it is about to expire. */
    @Synchronized
    fun accessToken(c: Context): String {
        val p = prefs(c)
        val tok = p.getString("access", "").orEmpty()
        if (tok.isNotEmpty() && System.currentTimeMillis() < p.getLong("expires", 0L) - 60_000L) return tok
        val refresh = p.getString("refresh", "").orEmpty()
        if (refresh.isEmpty()) throw AuthException("not connected")
        try {
            store(c, tokenRequest(form("grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to clientId(c))))
        } catch (e: AuthException) {
            if (e.message == "invalid_grant") disconnect(c) // revoked in the Spotify account
            throw e
        }
        return p.getString("access", "").orEmpty()
    }

    /** Forces the next [accessToken] to refresh (after a 401). */
    fun invalidate(c: Context) = prefs(c).edit().putLong("expires", 0L).apply()

    private fun store(c: Context, o: JSONObject) {
        val e = prefs(c).edit()
            .putString("access", o.getString("access_token"))
            .putLong("expires", System.currentTimeMillis() + o.optLong("expires_in", 3600L) * 1000L)
        o.optString("refresh_token").takeIf { it.isNotBlank() }?.let { e.putString("refresh", it) } // refresh may omit it: keep the old one
        e.apply()
    }

    private fun tokenRequest(body: String): JSONObject {
        val r = Http.request("POST", TOKEN_URL, mapOf("Accept" to "application/json"), body, "application/x-www-form-urlencoded")
        val o = try { JSONObject(r.body) } catch (_: Exception) { JSONObject() }
        if (r.code !in 200..299) throw AuthException(o.optString("error").ifBlank { "HTTP ${r.code}" })
        return o
    }

    private fun form(vararg kv: Pair<String, String>) = kv.joinToString("&") { enc(it.first) + "=" + enc(it.second) }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
