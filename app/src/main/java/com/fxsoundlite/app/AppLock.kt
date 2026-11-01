package com.fxsoundlite.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * App lock. While the setting is on, the app shows [AppLockActivity] over whatever page is opened until the
 * fingerprint, face or screen lock (PIN / pattern / password) is accepted.
 *
 * - It locks when the app is opened from scratch and after it was out of sight longer than the chosen delay.
 *   Turning the screen is not "out of sight" (a rotation never locks).
 * - It only guards the screens. Music keeps playing and the notification controls keep working while locked.
 * - If the phone no longer has any fingerprint / face / screen lock, the app is NOT locked: nobody gets shut
 *   out of their own app by a lock that can no longer be opened.
 */
object AppLock {

    /**
     * Weak biometrics on purpose: face unlock on most Android phones is class 2 ("weak"), so asking for
     * BIOMETRIC_STRONG would leave it out. The screen lock is always accepted as a fallback.
     */
    const val AUTHENTICATORS =
        BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    /** The "lock after" choices of the Settings page, in milliseconds. */
    val DELAYS = longArrayOf(0L, 30_000L, 60_000L, 300_000L)

    /** Pages that must stay reachable while locked or that only hand over to another page. */
    private val exempt = setOf<Class<*>>(
        AppLockActivity::class.java,
        WakeAlarmActivity::class.java,
        ChargingScreensaverActivity::class.java,
        ExternalOpenActivity::class.java,
        SpotifyCallbackActivity::class.java
    )

    private var started = 0
    private var leftAt = 0L
    private var needsLock = true // a fresh process always starts locked
    private var pageShown = false

    /** Is there a fingerprint, face or screen lock set up on this phone? */
    fun available(c: Context): Boolean =
        BiometricManager.from(c).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    fun active(c: Context): Boolean = MusicPrefs.appLock(c) && available(c)

    /** The person has just been recognised. Also forgets a pending "was away" mark, so nothing locks again right after. */
    fun markUnlocked() { needsLock = false; leftAt = 0L }
    fun lockPageShown(shown: Boolean) { pageShown = shown }

    /** The app was swiped away from the recent apps: whatever delay is set, opening it again must ask again. */
    fun onTaskRemoved() { needsLock = true }

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                started++
                if (started == 1 && leftAt != 0L) {
                    // The app comes back into view after really being away (not after a rotation).
                    if (SystemClock.elapsedRealtime() - leftAt >= MusicPrefs.appLockDelayMs(activity)) needsLock = true
                    leftAt = 0L
                }
            }

            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
                if (started == 0 && !activity.isChangingConfigurations) leftAt = SystemClock.elapsedRealtime()
            }

            override fun onActivityResumed(activity: Activity) {
                if (activity.javaClass in exempt || !needsLock || pageShown) return
                if (!active(activity)) return
                pageShown = true
                activity.startActivity(Intent(activity, AppLockActivity::class.java))
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /** Shows the system prompt. [onDone] gets true only when the person was really recognised. */
    fun authenticate(a: FragmentActivity, title: String, subtitle: String?, onDone: (Boolean) -> Unit) {
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onDone(true)
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onDone(false)
            // onAuthenticationFailed (one wrong finger) does not end the prompt: the person just tries again.
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .apply { if (subtitle != null) setSubtitle(subtitle) }
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
        try {
            BiometricPrompt(a, ContextCompat.getMainExecutor(a), callback).authenticate(info)
        } catch (e: Exception) {
            android.util.Log.w("AppLock", "prompt failed", e)
            onDone(false)
        }
    }
}
