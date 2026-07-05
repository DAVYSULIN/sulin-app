package com.sulin.centrocomando

import android.content.Context

/**
 * Guarda el último token de Firebase Cloud Messaging conocido, para poder
 * devolverlo desde el puente JS (WebAppBridge) en cualquier momento, incluso
 * si la web todavía no estaba lista cuando llegó/se renovó el token.
 */
object PushTokenStore {
    private const val PREFS_NAME = "sulin_push_prefs"
    private const val KEY_TOKEN = "fcm_token"

    fun save(context: Context, token: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TOKEN, token)
            .apply()
    }

    fun get(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TOKEN, null)
    }
}
