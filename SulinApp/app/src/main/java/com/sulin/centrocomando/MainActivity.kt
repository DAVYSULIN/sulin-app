package com.sulin.centrocomando

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Actividad única de SULIN | Centro de Comando.
 *
 * Envuelve la web (https://comfy-beijinho-bcc604.netlify.app) en una WebView
 * nativa con: sesión persistente, escáner QR (cámara), subida/descarga de
 * archivos, soporte de popups para login OAuth, manejo de "sin conexión",
 * botón atrás nativo y splash screen.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var offlineContainer: LinearLayout

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermissionRequest: PermissionRequest? = null
    private var cameraImageUri: Uri? = null

    companion object {
        /** URL raíz de la web. Cambiar acá si el dominio cambia en el futuro. */
        const val HOME_URL = "https://comfy-beijinho-bcc604.netlify.app/"
        const val ALLOWED_HOST = "comfy-beijinho-bcc604.netlify.app"

        /** Dominios de backend que deben abrirse DENTRO de la app (no en el navegador). */
        val ALLOWED_EXTRA_HOSTS = listOf("supabase.co", "supabase.in")

        /** Clave del extra con la ruta a abrir al tocar una notificación push. */
        const val EXTRA_DEEP_LINK_PATH = "deep_link_path"

        // Referencia a la Activity en primer plano, para poder avisarle "en
        // vivo" si llega un token de push nuevo mientras la app está abierta
        // (ver onResume/onPause más abajo). Si no hay ninguna en primer
        // plano, el token igual queda guardado en PushTokenStore.
        private var activeInstance: MainActivity? = null

        fun notifyTokenRefreshed(token: String) {
            activeInstance?.sendTokenToWeb(token)
        }
    }

    // ---------------------------------------------------------------------
    // Permiso de cámara en tiempo de ejecución (usado por el Visor QR 360)
    // ---------------------------------------------------------------------
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val request = pendingPermissionRequest
        if (request != null) {
            if (granted) {
                request.grant(request.resources)
            } else {
                request.deny()
                Toast.makeText(this, R.string.camera_permission_denied, Toast.LENGTH_LONG).show()
            }
        }
        pendingPermissionRequest = null
    }

    // ---------------------------------------------------------------------
    // Selector de archivos (subir evidencia fotográfica, adjuntos, etc.)
    // ---------------------------------------------------------------------
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        var results: Array<Uri>? = null
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            results = when {
                data?.clipData != null -> {
                    val clip = data.clipData!!
                    Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
                }
                data?.dataString != null -> arrayOf(Uri.parse(data.dataString))
                cameraImageUri != null -> arrayOf(cameraImageUri!!)
                else -> null
            }
        }
        filePathCallback?.onReceiveValue(results)
        filePathCallback = null
        cameraImageUri = null
    }

    // ---------------------------------------------------------------------
    // Permiso de notificaciones (Android 13+). Si lo rechazan, la app sigue
    // funcionando normal; simplemente no van a ver notificaciones push.
    // ---------------------------------------------------------------------
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        // installSplashScreen() debe llamarse ANTES de super.onCreate()
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.web_view)
        swipeRefresh = findViewById(R.id.swipe_refresh)
        progressBar = findViewById(R.id.progress_bar)
        offlineContainer = findViewById(R.id.offline_container)

        setupWebView()
        setupSwipeToRefresh()
        setupOfflineRetry()
        setupBackNavigation()
        requestNotificationPermissionIfNeeded()
        initPushToken()

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            val deepLinkPath = intent?.getStringExtra(EXTRA_DEEP_LINK_PATH)
            if (deepLinkPath.isNullOrBlank()) {
                loadHome()
            } else {
                loadUrl(HOME_URL.trimEnd('/') + deepLinkPath)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val deepLinkPath = intent.getStringExtra(EXTRA_DEEP_LINK_PATH)
        if (!deepLinkPath.isNullOrBlank()) {
            loadUrl(HOME_URL.trimEnd('/') + deepLinkPath)
        }
    }

    // =========================================================================================
    //  Configuración de la WebView
    // =========================================================================================

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.setSupportMultipleWindows(true)
        settings.userAgentString = "${settings.userAgentString} SulinAndroidApp/1.0"

        // Cookies y almacenamiento local: imprescindibles para que la sesión
        // de Supabase (login) persista entre aperturas de la app.
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = SulinWebViewClient()
        webView.webChromeClient = SulinWebChromeClient()
        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            downloadFile(url, contentDisposition, mimeType)
        }

        // Puente para que la web pueda leer el token de notificaciones push.
        // Solo queda expuesto en los dominios de confianza (ver
        // shouldOverrideUrlLoading), no en enlaces externos.
        webView.addJavascriptInterface(WebAppBridge(this), "SulinNative")

        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
    }

    /** Controla qué navegaciones se quedan dentro de la app y cuáles se abren afuera. */
    private inner class SulinWebViewClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            val scheme = uri.scheme ?: ""
            val host = uri.host ?: ""

            return when {
                scheme == "tel" || scheme == "mailto" || scheme == "geo" || scheme == "whatsapp" -> {
                    openExternally(uri)
                    true
                }
                host == ALLOWED_HOST || ALLOWED_EXTRA_HOSTS.any { host == it || host.endsWith(".$it") } -> {
                    false // se queda en la WebView
                }
                else -> {
                    // Dominios externos (incluye login de Google/OAuth, soporte, etc.)
                    openExternally(uri)
                    true
                }
            }
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            super.onPageStarted(view, url, favicon)
            progressBar.visibility = View.VISIBLE
        }

        override fun onPageFinished(view: WebView, url: String?) {
            super.onPageFinished(view, url)
            progressBar.visibility = View.GONE
            swipeRefresh.isRefreshing = false
            showContent()
            // Si ya teníamos un token de push guardado, se lo pasamos a la
            // web recién ahora que la página (y su JS) están listos.
            PushTokenStore.get(this@MainActivity)?.let { sendTokenToWeb(it) }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            super.onReceivedError(view, request, error)
            // Solo mostramos la pantalla de "sin conexión" si falla la carga
            // de la página principal, no si falla un recurso secundario
            // (una imagen, un script de analytics, etc.)
            if (request.isForMainFrame) {
                showOffline()
            }
        }
    }

    /** Maneja cámara, popups, selector de archivos, diálogos JS y progreso. */
    private inner class SulinWebChromeClient : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            super.onProgressChanged(view, newProgress)
            progressBar.progress = newProgress
            progressBar.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
        }

        // --- Permiso de cámara para el escáner QR (getUserMedia) ---
        override fun onPermissionRequest(request: PermissionRequest) {
            runOnUiThread {
                val resources = request.resources
                val onlyAsksCamera = resources.size == 1 &&
                    resources[0] == PermissionRequest.RESOURCE_VIDEO_CAPTURE

                if (!onlyAsksCamera) {
                    request.deny()
                    return@runOnUiThread
                }

                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    request.grant(resources)
                } else {
                    pendingPermissionRequest = request
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            }
        }

        // --- Ventanas emergentes: login con Google u otro proveedor OAuth ---
        // Se abren en el navegador del sistema a propósito: Google bloquea el
        // login dentro de WebViews embebidas por seguridad, así que esta es
        // la única forma confiable de que el login funcione.
        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message
        ): Boolean {
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            val popupWebView = WebView(this@MainActivity)
            popupWebView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                    openExternally(request.url)
                    v.post { popupWebView.destroy() }
                    return true
                }
            }
            transport.webView = popupWebView
            resultMsg.sendToTarget()
            return true
        }

        // --- Selector de archivos (subir foto de evidencia, adjuntos, etc.) ---
        override fun onShowFileChooser(
            webView: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams
        ): Boolean {
            filePathCallback?.onReceiveValue(null)
            filePathCallback = callback

            val captureIntent = createImageCaptureIntent()
            val chooserIntent = Intent(Intent.ACTION_CHOOSER).apply {
                putExtra(Intent.EXTRA_INTENT, params.createIntent())
                putExtra(Intent.EXTRA_TITLE, getString(R.string.file_chooser_title))
                if (captureIntent != null) {
                    putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(captureIntent))
                }
            }

            return try {
                fileChooserLauncher.launch(chooserIntent)
                true
            } catch (e: ActivityNotFoundException) {
                filePathCallback = null
                Toast.makeText(this@MainActivity, R.string.file_chooser_error, Toast.LENGTH_SHORT).show()
                false
            }
        }

        override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
            AlertDialog.Builder(this@MainActivity)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                .setCancelable(false)
                .show()
            return true
        }

        override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
            AlertDialog.Builder(this@MainActivity)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                .setCancelable(false)
                .show()
            return true
        }

        override fun onJsPrompt(
            view: WebView,
            url: String,
            message: String,
            defaultValue: String?,
            result: JsPromptResult
        ): Boolean {
            val input = EditText(this@MainActivity).apply { setText(defaultValue) }
            AlertDialog.Builder(this@MainActivity)
                .setMessage(message)
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm(input.text.toString()) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                .setCancelable(false)
                .show()
            return true
        }
    }

    // =========================================================================================
    //  Cámara -> subida de archivos (input file con opción "Tomar foto")
    // =========================================================================================

    private fun createImageCaptureIntent(): Intent? {
        return try {
            val imagesDir = getExternalFilesDir(null) ?: return null
            imagesDir.mkdirs()
            val imageFile = File.createTempFile("SULIN_${System.currentTimeMillis()}_", ".jpg", imagesDir)
            cameraImageUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", imageFile)
            Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        } catch (e: IOException) {
            null
        }
    }

    // =========================================================================================
    //  Pull to refresh
    // =========================================================================================

    private fun setupSwipeToRefresh() {
        swipeRefresh.setColorSchemeResources(R.color.sulin_accent)
        swipeRefresh.setProgressBackgroundColorSchemeResource(R.color.sulin_surface)
        swipeRefresh.setOnRefreshListener {
            if (ConnectivityHelper.isOnline(this)) {
                webView.reload()
            } else {
                swipeRefresh.isRefreshing = false
                showOffline()
            }
        }
        // Solo permitir el gesto de refresh cuando la WebView está scrolleada
        // hasta arriba del todo (para no interferir con el scroll normal).
        webView.viewTreeObserver.addOnScrollChangedListener {
            swipeRefresh.isEnabled = webView.scrollY == 0
        }
    }

    // =========================================================================================
    //  Estado "sin conexión"
    // =========================================================================================

    private fun setupOfflineRetry() {
        findViewById<Button>(R.id.btn_retry).setOnClickListener {
            if (ConnectivityHelper.isOnline(this)) {
                loadHome()
            } else {
                Toast.makeText(this, R.string.still_offline, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadHome() {
        loadUrl(HOME_URL)
    }

    private fun loadUrl(url: String) {
        if (ConnectivityHelper.isOnline(this)) {
            showContent()
            webView.loadUrl(url)
        } else {
            showOffline()
        }
    }

    private fun showOffline() {
        offlineContainer.visibility = View.VISIBLE
        swipeRefresh.visibility = View.GONE
    }

    private fun showContent() {
        offlineContainer.visibility = View.GONE
        swipeRefresh.visibility = View.VISIBLE
    }

    // =========================================================================================
    //  Enlaces externos y descargas
    // =========================================================================================

    private fun openExternally(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app_found, Toast.LENGTH_SHORT).show()
        }
    }

    private fun downloadFile(url: String, contentDisposition: String?, mimeType: String?) {
        try {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                addRequestHeader("cookie", CookieManager.getInstance().getCookie(url))
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(this, getString(R.string.downloading, fileName), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.download_error, Toast.LENGTH_SHORT).show()
        }
    }

    // =========================================================================================
    //  Notificaciones push
    // =========================================================================================

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    /**
     * Pide el token de FCM actual (si Firebase ya está configurado; ver
     * PUSH_NOTIFICATIONS.md) y se lo pasa a la web. No hace nada si todavía
     * no agregaron app/google-services.json — no rompe nada, solo no hay
     * token disponible hasta ese momento.
     */
    private fun initPushToken() {
        if (FirebaseApp.getApps(this).isEmpty()) return

        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val token = task.result ?: return@addOnCompleteListener
                PushTokenStore.save(this, token)
                sendTokenToWeb(token)
            }
        }
    }

    /**
     * Le avisa a la web el token de push llamando a
     * `window.onAndroidPushTokenReady(token)` si la web definió esa función
     * (ver PUSH_NOTIFICATIONS.md para el snippet del lado web).
     */
    private fun sendTokenToWeb(token: String) {
        val js = "if (window.onAndroidPushTokenReady) { window.onAndroidPushTokenReady(${JSONObject.quote(token)}); }"
        webView.post { webView.evaluateJavascript(js, null) }
    }

    // =========================================================================================
    //  Botón atrás: navega el historial de la web antes de minimizar la app
    // =========================================================================================

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    moveTaskToBack(true)
                }
            }
        })
    }

    // =========================================================================================
    //  Ciclo de vida
    // =========================================================================================

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        activeInstance = null
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        activeInstance = this
        if (offlineContainer.visibility == View.VISIBLE && ConnectivityHelper.isOnline(this)) {
            loadHome()
        }
    }

    override fun onDestroy() {
        if (activeInstance === this) {
            activeInstance = null
        }
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }
}
