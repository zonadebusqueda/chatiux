package com.zonadebusqueda.chatiux

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.webkit.CookieManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Servicio en segundo plano que consulta el endpoint existente en chat.php:
 *   chat.php?ajax_chat_action=unread_count
 * reutilizando automáticamente las cookies de sesión (PHPSESSID / recordarme)
 * sin modificar ningún archivo PHP ni la base de datos MySQL.
 */
class ChatiuxNotificationService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var lastNotifiedMsgId = -1

    private val pollRunnable = object : Runnable {
        override fun run() {
            verificarMensajesSinLeer()
            handler.postDelayed(this, 12000L) // Cada 12 segundos en segundo plano
        }
    }

    override fun onCreate() {
        super.onCreate()
        iniciarModoForegroundSilencioso()
        handler.post(pollRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(pollRunnable)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun iniciarModoForegroundSilencioso() {
        val syncChannelId = "chatiux_sync_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                syncChannelId,
                "Chatiux Conectado",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantiene activa la recepción de mensajes de Chatiux"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, syncChannelId)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Chatiux activo")
            .setContentText("Recibiendo mensajes en tiempo real")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()

        startForeground(9001, notification)
    }

    private fun verificarMensajesSinLeer() {
        executor.execute {
            try {
                val baseUrl = MainActivity.CHAT_URL
                val cookies = CookieManager.getInstance().getCookie(baseUrl) ?: return@execute
                val sep = if (baseUrl.contains("?")) "&" else "?"
                val endpoint = baseUrl + sep + "ajax_chat_action=unread_count"

                val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("Cookie", cookies)
                    setRequestProperty("Accept", "application/json")
                }

                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val response = reader.readText()
                    reader.close()

                    val json = JSONObject(response)
                    if (json.optString("status") == "ok") {
                        val latest = json.optJSONObject("latest_unread")
                        if (latest != null) {
                            val msgId = latest.optInt("id_m", 0)
                            val senderId = latest.optInt("sender_id", 0)
                            val senderName = latest.optString("u_nombre", "Nuevo mensaje")
                            var rawText = latest.optString("texto", "")

                            if (rawText.startsWith("[sticker:")) {
                                rawText = "🎨 Envió un Sticker"
                            } else if (rawText.contains("[img]")) {
                                rawText = "📷 Foto adjunta"
                            }

                            if (lastNotifiedMsgId != -1 && msgId > lastNotifiedMsgId) {
                                lanzarNotificacionPushLocal(senderName, rawText, senderId, msgId)
                            }
                            if (msgId > lastNotifiedMsgId) {
                                lastNotifiedMsgId = msgId
                            }
                        } else if (lastNotifiedMsgId == -1) {
                            lastNotifiedMsgId = 0
                        }
                    }
                }
                conn.disconnect()
            } catch (_: Exception) {}
        }
    }

    private fun lanzarNotificacionPushLocal(senderName: String, body: String, senderId: Int, msgId: Int) {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_sender_id", senderId)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            senderId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, MainActivity.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(senderName)
            .setContentText(body.take(100))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(this).notify(msgId, builder.build())
        }
    }
}
