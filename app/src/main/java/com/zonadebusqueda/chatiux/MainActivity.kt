package com.zonadebusqueda.chatiux

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    companion object {
        const val CHAT_URL = "https://zonadebusqueda.com/chat.php"
        const val CHANNEL_ID = "chatiux_mensajes_channel"
    }

    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val dataUri = result.data?.data
            val results = if (dataUri != null) arrayOf(dataUri) else null
            filePathCallback?.onReceiveValue(results)
        } else {
            filePathCallback?.onReceiveValue(null)
        }
        filePathCallback = null
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        iniciarServicioNotificaciones()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        crearCanalNotificaciones()
        solicitarPermisoNotificaciones()

        webView = findViewById(R.id.chatiuxWebView)

        // 1. PERSISTENCIA DE SESIÓN Y COOKIES (PHPSESSID + recordarme de login.php)
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        // 2. CONFIGURACIÓN DEL WEBVIEW NATIVO PARA CHATIUX
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = "$userAgentString ChatiuxAndroidApp/1.0"
        }

        // 3. PUENTE JAVASCRIPT NATIVO PARA NOTIFICACIONES EN TIEMPO REAL SIN TOCAR CHAT.PHP
        webView.addJavascriptInterface(ChatiuxJsBridge(this), "AndroidChatiux")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                
                // En modo APK independiente, evitar salir hacia index.php y mantener siempre en chat.php
                if (url.endsWith("/index.php") || url.contains("/index.php?")) {
                    view?.loadUrl(CHAT_URL)
                    return true
                }
                
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Guardar cookies de sesión inmediatamente en disco
                CookieManager.getInstance().flush()

                // Inyectar adaptador transparente para conectar las notificaciones de chat.php con Android Nativo
                val jsHook = """
                    (function() {
                        if (window.__chatiuxAndroidHooked) return;
                        window.__chatiuxAndroidHooked = true;

                        // Ocultar enlaces de salida al portal si está en modo APK exclusiva
                        var style = document.createElement('style');
                        style.innerHTML = 'a[href="index.php"], a[href^="index.php"] { display: none !important; }';
                        document.head.appendChild(style);

                        // Conectar window.Notification de chat.php con el sistema nativo de Android
                        window.Notification = function(title, options) {
                            options = options || {};
                            if (window.AndroidChatiux && window.AndroidChatiux.mostrarNotificacionNativa) {
                                window.AndroidChatiux.mostrarNotificacionNativa(
                                    String(title || 'Chatiux'),
                                    String(options.body || 'Nuevo mensaje recibido'),
                                    0
                                );
                            }
                        };
                        window.Notification.permission = 'granted';
                        window.Notification.requestPermission = function(cb) {
                            if (typeof cb === 'function') cb('granted');
                            return Promise.resolve('granted');
                        };

                        // También interceptar mostrarToastNotificacion de chat.php para abrir el contacto exacto al tocar
                        if (typeof window.mostrarToastNotificacion === 'function') {
                            var origToast = window.mostrarToastNotificacion;
                            window.mostrarToastNotificacion = function(nombre, avatar, texto, sId) {
                                origToast(nombre, avatar, texto, sId);
                                var cleanText = String(texto || '');
                                if (cleanText.indexOf('[sticker:') === 0) cleanText = '🎨 Envió un Sticker';
                                else if (cleanText.indexOf('[img]') !== -1) cleanText = '📷 Foto adjunta';
                                if (window.AndroidChatiux && window.AndroidChatiux.mostrarNotificacionNativa) {
                                    window.AndroidChatiux.mostrarNotificacionNativa(
                                        String(nombre || 'Chatiux'),
                                        cleanText,
                                        parseInt(sId || 0, 10)
                                    );
                                }
                            };
                        }
                    })();
                """.trimIndent()

                view?.evaluateJavascript(jsHook, null)

                // Si se abrió desde una notificación de un remitente específico, abrir su conversación
                val targetSenderId = intent?.getIntExtra("open_sender_id", 0) ?: 0
                if (targetSenderId > 0 && url?.contains("chat.php") == true) {
                    intent?.removeExtra("open_sender_id")
                    view?.evaluateJavascript(
                        "if (typeof selectContact === 'function') { selectContact($targetSenderId); }",
                        null
                    )
                }
            }
        }

        // 4. SOPORTE PARA SUBIR FOTOS / CÁMARA EN EL CHAT (<input type="file">)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                }
                fileChooserLauncher.launch(Intent.createChooser(intent, "Seleccionar imagen para Chatiux"))
                return true
            }
        }

        // 5. MANEJO INTELIGENTE DEL BOTÓN ATRÁS DE ANDROID
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript(
                    """
                    (function() {
                        var lightbox = document.getElementById('php-image-lightbox');
                        if (lightbox && !lightbox.classList.contains('hidden')) {
                            closePhpImageLightbox();
                            return 'handled';
                        }
                        var modal = document.getElementById('modal-nuevo-chat');
                        if (modal && !modal.classList.contains('hidden')) {
                            cerrarModalNuevoChat();
                            return 'handled';
                        }
                        var panelConv = document.getElementById('panel-conversacion');
                        if (window.innerWidth < 768 && panelConv && !panelConv.classList.contains('hidden')) {
                            if (typeof volverAListaMobile === 'function') {
                                volverAListaMobile();
                                return 'handled';
                            }
                        }
                        return 'unhandled';
                    })();
                    """.trimIndent()
                ) { res ->
                    if (res != null && res.contains("handled")) {
                        return@evaluateJavascript
                    }
                    if (webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        finish()
                    }
                }
            }
        })

        webView.loadUrl(CHAT_URL)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val senderId = intent.getIntExtra("open_sender_id", 0)
        if (senderId > 0 && ::webView.isInitialized) {
            webView.evaluateJavascript(
                "if (typeof selectContact === 'function') { selectContact($senderId); }",
                null
            )
        }
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    private fun solicitarPermisoNotificaciones() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        iniciarServicioNotificaciones()
    }

    private fun iniciarServicioNotificaciones() {
        try {
            val serviceIntent = Intent(this, ChatiuxNotificationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (_: Exception) {}
    }

    private fun crearCanalNotificaciones() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Mensajes de Chatiux",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificaciones en tiempo real de nuevos mensajes y stickers en Chatiux"
                enableVibration(true)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    class ChatiuxJsBridge(private val context: Context) {
        @JavascriptInterface
        fun mostrarNotificacionNativa(titulo: String, mensaje: String, senderId: Int) {
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("open_sender_id", senderId)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                senderId,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(titulo)
                .setContentText(mensaje)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)

            try {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                ) {
                    NotificationManagerCompat.from(context).notify(
                        if (senderId > 0) senderId else System.currentTimeMillis().toInt(),
                        builder.build()
                    )
                }
            } catch (_: Exception) {}
        }
    }
}
