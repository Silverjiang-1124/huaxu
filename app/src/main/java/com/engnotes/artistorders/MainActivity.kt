package com.engnotes.artistorders

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.webkit.WebViewAssetLoader
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.Executors

private class OrderDatabase(context: Context) : SQLiteOpenHelper(context, "orders.db", null, 3) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE orders (id INTEGER PRIMARY KEY AUTOINCREMENT, customer TEXT NOT NULL, wechat TEXT NOT NULL, detail TEXT NOT NULL, quantity INTEGER NOT NULL, due TEXT NOT NULL, price TEXT NOT NULL, done INTEGER NOT NULL DEFAULT 0, type TEXT NOT NULL DEFAULT '只画头发', minutes_per_image INTEGER NOT NULL DEFAULT 30, completed_at TEXT NOT NULL DEFAULT '')")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE orders ADD COLUMN type TEXT NOT NULL DEFAULT '未选择'")
            db.execSQL("ALTER TABLE orders ADD COLUMN minutes_per_image INTEGER NOT NULL DEFAULT 30")
        }
        if (oldVersion < 3) db.execSQL("ALTER TABLE orders ADD COLUMN completed_at TEXT NOT NULL DEFAULT ''")
    }
    fun all(): JSONArray = readableDatabase.rawQuery("SELECT id, customer, wechat, detail, quantity, due, price, done, type, minutes_per_image, completed_at FROM orders ORDER BY done, CASE WHEN due = '' THEN 1 ELSE 0 END, due, id DESC", null).use { c ->
        JSONArray().apply { while (c.moveToNext()) put(JSONObject().apply {
            put("id", c.getLong(0)); put("customer", c.getString(1)); put("wechat", c.getString(2)); put("detail", c.getString(3)); put("quantity", c.getInt(4)); put("due", c.getString(5)); put("price", c.getString(6)); put("done", c.getInt(7) != 0); put("type", c.getString(8)); put("minutesPerImage", c.getInt(9)); put("completedAt", c.getString(10))
        }) }
    }
    fun save(o: JSONObject) {
        val quantity = o.getInt("quantity"); val minutes = o.getInt("minutesPerImage")
        require(quantity in 1..9999 && minutes in 1..1440) { "数量或工时不在允许范围内" }
        val due = o.optString("due", ""); require(due.isEmpty() || runCatching { LocalDate.parse(due) }.isSuccess) { "日期格式有误" }
        val price = o.optString("price", ""); require(price.isEmpty() || price.matches(Regex("\\d{1,9}(\\.\\d{1,2})?"))) { "金额格式有误" }
        val type = o.optString("type", "只画头发"); require(type in listOf("未选择", "只画头发", "头发＋修图", "修图（身材 / 滤镜）", "其他")) { "单据类型无效" }
        val done = o.optBoolean("done")
        val completedAt = if (done) o.optString("completedAt").ifBlank { LocalDateTime.now().toString() } else ""
        val v = ContentValues().apply {
            put("customer", o.optString("customer", "").trim()); put("wechat", o.optString("wechat", "").trim()); put("detail", o.optString("detail", "").trim())
            put("quantity", quantity); put("due", due); put("price", price); put("done", if (done) 1 else 0); put("type", type); put("minutes_per_image", minutes); put("completed_at", completedAt)
        }
        val id = o.optLong("id", 0)
        if (id == 0L) writableDatabase.insertOrThrow("orders", null, v)
        else check(writableDatabase.update("orders", v, "id = ?", arrayOf(id.toString())) == 1) { "订单不存在" }
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private lateinit var database: OrderDatabase
    private val recognizer by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
    private val ocrExecutor = Executors.newSingleThreadExecutor { task -> Thread(task, "Huaxu-OCR").apply { isDaemon = true } }
    private var ready = false
    private var pendingImage: Uri? = null
    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) recognize(uri) }
    private val preferences by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.statusBarColor = android.graphics.Color.rgb(248, 248, 244)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        database = OrderDatabase(this)
        DueReminderNotifications.createChannel(this)
        ReminderSchedule.schedule(this)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1201)
        }
        val assets = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.domStorageEnabled = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            addJavascriptInterface(NativeBridge(), "HuaxuNative")
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = request.url.scheme != "https" || request.url.host != "appassets.androidplatform.net" || !request.url.path.orEmpty().startsWith("/assets/web/")
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    val url = request.url
                    if (url.scheme == "https" && url.host == "appassets.androidplatform.net" && url.path.orEmpty().startsWith("/assets/web/")) {
                        assets.shouldInterceptRequest(url)?.let { return it }
                    }
                    return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), null)
                }
                override fun onPageFinished(view: WebView, url: String) { ready = true; publishState(); pendingImage?.let { pendingImage = null; recognize(it) } }
            }
        }
        val container = android.widget.FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.WHITE)
            setOnApplyWindowInsetsListener { view, insets ->
                @Suppress("DEPRECATION")
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    val safe = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                    view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
                } else {
                    view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
                }
                insets
            }
            addView(web, android.widget.FrameLayout.LayoutParams(-1, -1))
        }
        setContentView(container)
        web.loadUrl("https://appassets.androidplatform.net/assets/web/index.html")
        acceptShare(intent)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); acceptShare(intent) }
    @Suppress("DEPRECATION")
    private fun acceptShare(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("image/") == true) {
            val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: return
            if (ready) recognize(uri) else pendingImage = uri
        }
    }
    private fun emit(name: String, data: JSONObject) { runOnUiThread { web.evaluateJavascript("window.dispatchEvent(new CustomEvent(${JSONObject.quote(name)},{detail:${data}}))", null) } }
    private fun publishState() {
        try {
            val remindersDefault = preferences.getBoolean("reminders_enabled", true)
            val reminders = JSONObject()
                .put("noon", JSONObject().put("enabled", preferences.getBoolean("reminder_noon_enabled", remindersDefault)).put("time", preferences.getString("reminder_noon_time", "12:00")))
                .put("evening", JSONObject().put("enabled", preferences.getBoolean("reminder_evening_enabled", remindersDefault)).put("time", preferences.getString("reminder_evening_time", "18:00")))
            emit("huaxu-state", JSONObject().put("orders", database.all()).put("defaultMinutes", preferences.getInt("minutes_per_image", 30).coerceIn(1, 1440)).put("reminders", reminders))
        }
        catch (e: Exception) { emit("huaxu-error", JSONObject().put("message", e.message ?: "读取失败")) }
    }
    private inner class NativeBridge {
        @JavascriptInterface fun refresh() = publishState()
        @JavascriptInterface fun closeApp() { runOnUiThread { finish() } }
        @JavascriptInterface fun chooseImage() { runOnUiThread { picker.launch("image/*") } }
        @JavascriptInterface fun saveOrder(json: String) {
            try { database.save(JSONObject(json)); publishState(); emit("huaxu-saved", JSONObject()) }
            catch (e: Exception) { emit("huaxu-error", JSONObject().put("message", e.message ?: "保存失败")) }
        }
        @JavascriptInterface fun setReminderSettings(json: String) {
            try {
                val reminderSettings = JSONObject(json)
                val noon = reminderSettings.getJSONObject("noon")
                val evening = reminderSettings.getJSONObject("evening")
                val noonTime = noon.getString("time")
                val eveningTime = evening.getString("time")
                require(noonTime.matches(Regex("(?:[01]\\d|2[0-3]):[0-5]\\d")) && eveningTime.matches(Regex("(?:[01]\\d|2[0-3]):[0-5]\\d"))) { "提醒时间请使用 24 小时制" }
                preferences.edit()
                    .putBoolean("reminder_noon_enabled", noon.optBoolean("enabled", true)).putString("reminder_noon_time", noonTime)
                    .putBoolean("reminder_evening_enabled", evening.optBoolean("enabled", true)).putString("reminder_evening_time", eveningTime)
                    .apply()
                ReminderSchedule.schedule(this@MainActivity)
                publishState()
                emit("huaxu-reminder-saved", JSONObject())
            } catch (e: Exception) { emit("huaxu-error", JSONObject().put("message", e.message ?: "提醒设置保存失败")) }
        }
        @JavascriptInterface fun setDefaultMinutes(value: Int) {
            if (value !in 1..1440) { emit("huaxu-error", JSONObject().put("message", "每张工时请输入 1 至 1440 分钟")); return }
            preferences.edit().putInt("minutes_per_image", value).apply(); publishState(); emit("huaxu-setting-saved", JSONObject())
        }
    }
    private fun recognize(uri: Uri) {
        emit("huaxu-ocr-start", JSONObject())
        try {
            ocrExecutor.execute {
                try {
                    // Decode picker content on a worker thread so image IO cannot block Android navigation.
                    val image = InputImage.fromFilePath(applicationContext, uri)
                    recognizer.process(image).addOnSuccessListener { result ->
                        val raw = result.text
                        val wx = Regex("(?m)^[ \\t|丨]*(?:微信号|微信號)[ \\t]*[:：][ \\t]*([a-zA-Z][a-zA-Z0-9_-]{2,})[ \\t]*$").find(raw)?.groupValues?.get(1).orEmpty()
                        val nm = Regex("(?m)^[ \\t]*昵称[ \\t]*[:：][ \\t]*([^\\r\\n]+)").find(raw)
                        val isProfile = nm != null && (raw.contains("微信号") || raw.contains("微信號"))
                        val top = if (isProfile) raw.substring(0, nm!!.range.first) else ""
                        // The date line may be followed by a WeChat icon that OCR reads as arbitrary text.
                        val d = Regex("(?m)^[ \\t]*(\\d{1,2})[ \\t]*[.．/月-][ \\t]*(\\d{1,2})(?:日)?").find(top)
                        val date = d?.let { runCatching { LocalDate.of(LocalDate.now().year, it.groupValues[1].toInt(), it.groupValues[2].toInt()).toString() }.getOrNull() }.orEmpty()
                        val count = if (isProfile) 1 else Regex("(\\d{1,3})\\s*张").find(raw)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 } ?: 1
                        val price = if (isProfile) "" else Regex("(?:¥|￥)\\s*(\\d+(?:\\.\\d{1,2})?)|(\\d+(?:\\.\\d{1,2})?)\\s*元").find(raw)?.let { it.groupValues[1].ifBlank { it.groupValues[2] } }.orEmpty()
                        emit("huaxu-ocr", JSONObject().put("raw", raw).put("customer", nm?.groupValues?.get(1)?.trim().orEmpty()).put("wechat", wx).put("due", date).put("quantity", count).put("price", price))
                    }.addOnFailureListener {
                        emit("huaxu-ocr-error", JSONObject().put("message", it.localizedMessage ?: "识别失败"))
                    }
                } catch (e: Exception) {
                    emit("huaxu-ocr-error", JSONObject().put("message", e.localizedMessage ?: "无法读取图片"))
                }
            }
        } catch (e: Exception) {
            emit("huaxu-ocr-error", JSONObject().put("message", e.localizedMessage ?: "无法读取图片"))
        }
    }
    override fun onDestroy() { ocrExecutor.shutdownNow(); web.removeJavascriptInterface("HuaxuNative"); web.destroy(); database.close(); recognizer.close(); super.onDestroy() }
    @Deprecated("Deprecated in Android")
    override fun onBackPressed() { web.evaluateJavascript("window.dispatchEvent(new Event('huaxu-back'))", null) }
}
