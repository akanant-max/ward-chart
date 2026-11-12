package com.wardchart.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val ASSET_HOST = "appassets.androidplatform.net"
private const val START_URL = "https://$ASSET_HOST/assets/index.html"

private const val PREFS = "wardchart"
private const val KEY_SYNC_URI = "sync_uri"
private const val KEY_SYNC_NAME = "sync_name"
private const val KEY_SYNC_LAST = "sync_last"

/**
 * Ward Chart runs entirely on the phone.
 *
 * The page lives in app assets and is served over a local https origin by
 * WebViewAssetLoader, so localStorage is backed by a stable origin rather than
 * a file:// one, where it is unreliable across Android versions.
 *
 * Sync is deliberately plain: the user picks one JSON file (in Google Drive,
 * or anywhere else the system file picker reaches) and the app holds a
 * persisted permission to it. assets/shim.js does the merging; this class only
 * moves bytes in and out of that file.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    /** Create a new sync file. */
    private val createSyncFile = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> adoptSyncFile(uri) }

    /** Adopt a sync file that already exists (the second phone's path). */
    private val openSyncFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> adoptSyncFile(uri) }

    /**
     * Backs <input type="file"> in the page, which Setup -> Backup -> Import
     * uses. A WebView ignores a file input entirely unless the app answers
     * onShowFileChooser, so without this the Import button does nothing at all.
     */
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    /** Android 13+ asks before an app may post notifications. */
    private val askNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> refreshRemindersPanel() }

    private fun refreshRemindersPanel() {
        if (::web.isInitialized) {
            web.evaluateJavascript("window.WardReminders && window.WardReminders.refresh();", null)
        }
    }

    private val pickImportFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        // Answering with null on cancel is required. Leaving the callback
        // unanswered wedges the file input for the rest of the process.
        fileChooserCallback?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
        fileChooserCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this)
        setContentView(web)

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false
        // Needed so the WebView can read the content:// URI a picked file
        // arrives as. The only page ever loaded here is our own asset.
        web.settings.allowContentAccess = true

        web.addJavascriptInterface(Bridge(), "WardBridge")

        web.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                if (request.url.host == ASSET_HOST) return false
                // Anything else is an outside link: hand it to the browser.
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url)); true
                } catch (e: Exception) {
                    true
                }
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)   // cancel one in flight
                fileChooserCallback = callback
                return try {
                    pickImportFile.launch(
                        arrayOf("application/json", "text/plain", "text/*", "*/*")
                    )
                    true
                } catch (e: Exception) {
                    fileChooserCallback = null
                    callback.onReceiveValue(null)
                    Toast.makeText(
                        this@MainActivity, "No app available to pick a file.", Toast.LENGTH_LONG
                    ).show()
                    false
                }
            }
        }

        web.loadUrl(START_URL)
    }

    override fun onResume() {
        super.onResume()
        // The page also listens for visibilitychange; this covers the case
        // where the WebView was already visible when we returned.
        if (::web.isInitialized) {
            web.evaluateJavascript("window.WardSync && window.WardSync.syncNow();", null)
            // Back from Settings, possibly with a grant changed.
            refreshRemindersPanel()
        }
    }

    // ---------------------------------------------------------------- sync file

    private fun adoptSyncFile(uri: Uri?) {
        if (uri == null) return
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            // Some providers refuse a persistable grant; the URI usually still
            // works for this session, so carry on rather than failing outright.
        }
        prefs.edit()
            .putString(KEY_SYNC_URI, uri.toString())
            .putString(KEY_SYNC_NAME, displayNameOf(uri))
            .apply()
        web.evaluateJavascript("window.WardSync && window.WardSync.onFileChosen();", null)
    }

    private fun displayNameOf(uri: Uri): String = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else uri.lastPathSegment ?: "sync file" }
            ?: (uri.lastPathSegment ?: "sync file")
    } catch (e: Exception) {
        uri.lastPathSegment ?: "sync file"
    }

    private fun syncUri(): Uri? =
        prefs.getString(KEY_SYNC_URI, null)?.let {
            try { Uri.parse(it) } catch (e: Exception) { null }
        }

    // ---------------------------------------------------------------- bridge

    /**
     * Called from JavaScript. Every method runs on a WebView worker thread,
     * so anything touching the UI hops back with runOnUiThread.
     */
    inner class Bridge {

        /** Export → a file in the phone's Downloads folder. */
        @JavascriptInterface
        fun saveFile(name: String, text: String): Boolean {
            val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "ward-chart.json" }
            val where: String = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, safe)
                        put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    }
                    val uri = contentResolver.insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                    ) ?: return false
                    contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(text.toByteArray(Charsets.UTF_8))
                    } ?: return false
                    "Downloads"
                } else {
                    val dir = getExternalFilesDir(null) ?: filesDir
                    File(dir, safe).writeText(text, Charsets.UTF_8)
                    dir.absolutePath
                }
            } catch (e: Exception) {
                return false
            }
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Saved to $where: $safe", Toast.LENGTH_LONG).show()
            }
            return true
        }

        /** Shown at the foot of Setup, so "which build is this?" has an answer. */
        @JavascriptInterface
        fun appVersion(): String = try {
            val info = packageManager.getPackageInfo(packageName, 0)
            (info.versionName ?: "?")
        } catch (e: Exception) {
            "?"
        }

        // ------------------------------------------------------------ reminders

        @JavascriptInterface
        fun remindersStatus(): String = JSONObject().apply {
            put("notifAllowed", Reminders.notificationsAllowed(this@MainActivity))
            put("exactAllowed", Reminders.exactAllowed(this@MainActivity))
        }.toString()

        /** The page hands over concrete fire times; we arm them. Returns the count armed. */
        @JavascriptInterface
        fun scheduleReminders(json: String): Int = Reminders.apply(this@MainActivity, json)

        @JavascriptInterface
        fun requestNotificationPermission() {
            runOnUiThread {
                if (Build.VERSION.SDK_INT >= 33) {
                    val have = ContextCompat.checkSelfPermission(
                        this@MainActivity, Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!have) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else if (!Reminders.notificationsAllowed(this@MainActivity)) {
                    // Older Android: no runtime prompt exists; open the app's notification settings.
                    try {
                        startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                        )
                    } catch (_: Exception) {}
                }
            }
        }

        /** Android 12 only: the exact-alarm grant lives in a system screen. */
        @JavascriptInterface
        fun openExactAlarmSettings() {
            runOnUiThread {
                if (Build.VERSION.SDK_INT >= 31) {
                    try {
                        startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                Uri.parse("package:$packageName")
                            )
                        )
                    } catch (_: Exception) {}
                }
            }
        }

        @JavascriptInterface
        fun testNotification(): Boolean {
            if (!Reminders.notificationsAllowed(this@MainActivity)) return false
            Reminders.show(this@MainActivity, 7, "Ward Chart", "Reminders are working.")
            return true
        }

        /** Called when a block is ticked or a chore done, so its notification clears. */
        @JavascriptInterface
        fun dismissReminder(kind: String, id: String) = Reminders.dismiss(this@MainActivity, kind, id)

        // ------------------------------------------------------------ sync

        /** What the Sync panel shows. */
        @JavascriptInterface
        fun syncStatus(): String = JSONObject().apply {
            put("wired", syncUri() != null)
            put("file", prefs.getString(KEY_SYNC_NAME, "") ?: "")
            put("last", prefs.getString(KEY_SYNC_LAST, "") ?: "")
        }.toString()

        /** Whole contents of the sync file, or "" when there is nothing to read. */
        @JavascriptInterface
        fun syncRead(): String {
            val uri = syncUri() ?: return ""
            return try {
                contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                    ?: ""
            } catch (e: Exception) {
                ""
            }
        }

        /** Replace the sync file's contents. */
        @JavascriptInterface
        fun syncWrite(text: String): Boolean {
            val uri = syncUri() ?: return false
            return try {
                // "rwt" truncates first, so a shorter document does not leave
                // the tail of a longer one behind.
                contentResolver.openOutputStream(uri, "rwt")?.use { out ->
                    out.write(text.toByteArray(Charsets.UTF_8))
                } ?: return false
                prefs.edit().putString(
                    KEY_SYNC_LAST,
                    SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date())
                ).apply()
                true
            } catch (e: Exception) {
                false
            }
        }

        /** Start a new sync file. */
        @JavascriptInterface
        fun syncChoose() {
            runOnUiThread {
                try {
                    createSyncFile.launch("ward-chart.json")
                } catch (e: Exception) {
                    Toast.makeText(
                        this@MainActivity, "No app available to pick a file.", Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        /** Join a sync file this or another phone already created. */
        @JavascriptInterface
        fun syncJoin() {
            runOnUiThread {
                try {
                    openSyncFile.launch(arrayOf("application/json", "text/plain", "*/*"))
                } catch (e: Exception) {
                    Toast.makeText(
                        this@MainActivity, "No app available to pick a file.", Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        /** Stop syncing; the file itself is left alone. */
        @JavascriptInterface
        fun syncForget() {
            syncUri()?.let { uri ->
                try {
                    contentResolver.releasePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (e: Exception) {
                }
            }
            prefs.edit().remove(KEY_SYNC_URI).remove(KEY_SYNC_NAME).remove(KEY_SYNC_LAST).apply()
            runOnUiThread {
                web.evaluateJavascript("window.WardSync && window.WardSync.onFileChosen();", null)
            }
        }
    }
}
