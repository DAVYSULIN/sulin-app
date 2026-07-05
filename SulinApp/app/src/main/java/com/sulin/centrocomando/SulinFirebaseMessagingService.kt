package com.sulin.centrocomando

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Recibe los mensajes push de Firebase Cloud Messaging y los muestra como
 * notificación nativa. También guarda el token cada vez que Firebase lo
 * genera o renueva.
 *
 * No requiere ningún cambio para funcionar: en cuanto agreguen
 * app/google-services.json (ver PUSH_NOTIFICATIONS.md), este servicio queda
 * activo automáticamente.
 *
 * Formato esperado del mensaje enviado desde el backend (Supabase Edge
 * Function -> FCM). Puede venir como "notification" (title/body) y/o como
 * "data" con claves libres, por ejemplo:
 *   { "data": { "title": "Falla reportada", "body": "Bomba #4 - Sector B",
 *               "path": "/mantenimiento/123" } }
 * "path" es opcional: si viene, al tocar la notificación la app abre esa
 * ruta específica dentro de la web en vez de la home.
 */
class SulinFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        PushTokenStore.save(this, token)
        MainActivity.notifyTokenRefreshed(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val title = message.notification?.title
            ?: message.data["title"]
            ?: getString(R.string.app_name)
        val body = message.notification?.body
            ?: message.data["body"]
            ?: ""
        val deepLinkPath = message.data["path"]

        showNotification(title, body, deepLinkPath)
    }

    private fun showNotification(title: String, body: String, deepLinkPath: String?) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (!deepLinkPath.isNullOrBlank()) {
                putExtra(MainActivity.EXTRA_DEEP_LINK_PATH, deepLinkPath)
            }
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, getString(R.string.notification_channel_id))
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(this, R.color.sulin_accent))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val hasPermission = ActivityCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            NotificationManagerCompat.from(this).notify(System.currentTimeMillis().toInt(), notification)
        }
    }
}
