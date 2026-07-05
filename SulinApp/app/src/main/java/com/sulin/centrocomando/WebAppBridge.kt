package com.sulin.centrocomando

import android.content.Context
import android.webkit.JavascriptInterface

/**
 * Puente entre el JavaScript de la web y el código nativo.
 *
 * Se registra en el WebView como `window.SulinNative` (ver MainActivity).
 * Solo queda expuesto en páginas de dominios de confianza (ver
 * MainActivity.ALLOWED_HOST / ALLOWED_EXTRA_HOSTS), así que una web externa
 * no puede llamarlo aunque el usuario navegue a un enlace externo.
 *
 * Del lado de la web se usa así (ver PUSH_NOTIFICATIONS.md para el detalle):
 *
 *   if (window.SulinNative) {
 *     const token = window.SulinNative.getPushToken(); // puede venir vacío
 *     window.onAndroidPushTokenReady = function(token) {
 *       // guardar el token en Supabase asociado al usuario logueado
 *     };
 *   }
 */
class WebAppBridge(private val context: Context) {

    @JavascriptInterface
    fun getPushToken(): String {
        return PushTokenStore.get(context) ?: ""
    }

    @JavascriptInterface
    fun isAndroidApp(): Boolean = true
}
