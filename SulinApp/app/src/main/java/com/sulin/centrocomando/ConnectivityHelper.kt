package com.sulin.centrocomando

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Chequeo simple y confiable de si el dispositivo tiene conexión a internet
 * en este momento (WiFi, datos móviles o ethernet).
 */
object ConnectivityHelper {

    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
