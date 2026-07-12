package com.example.clock

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.concurrent.thread
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import java.util.UUID
import android.util.Xml

class MainActivity : Activity() {

    private lateinit var timeText: TextView
    private lateinit var dateText: TextView
    private lateinit var syncIndicator: TextView
    private lateinit var settingsBtn: TextView
    private lateinit var nextEntryText: TextView
    private lateinit var onlineText: TextView

    // 是否显示「下次进入时间」：由暗号触发（连点时间 10 次 / 长按 30 秒），持久化保存
    private var nextEntryOn = false

    // 实时在线人数：由服务器切换序列暗号触发，持久化保存
    private var onlineOn = false
    private var onlineBase = ""

    private val handler = Handler(Looper.getMainLooper())

    // NTP 校准得到的「本地时间 → 真实时间」偏移（毫秒）。null 表示尚未校准。
    private var offset: Long? = null

    // 暗号计数
    private var tapCount = 0
    private var lastTapTime = 0L
    private var longPressStart = 0L
    private val TAP_WINDOW = 2000L

    private val shanghai = TimeZone.getTimeZone("Asia/Shanghai")
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.CHINA).apply { timeZone = shanghai }
    private val dateFmt = SimpleDateFormat("yyyy年M月d日 EEEE", Locale.CHINA).apply { timeZone = shanghai }

    private val tick = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 200)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()

        timeText = findViewById(R.id.timeText)
        dateText = findViewById(R.id.dateText)
        syncIndicator = findViewById(R.id.syncIndicator)
        settingsBtn = findViewById(R.id.settingsBtn)
        nextEntryText = findViewById(R.id.nextEntryText)
        onlineText = findViewById(R.id.onlineText)

        loadPrefs()
        startOnlinePolling()
        fitTextSize()
        settingsBtn.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        setupSecretGesture()
        handler.post(tick)
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
        loadPrefs()
        syncTime() // 从设置返回后按新选择重新校准
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
    }

    private fun loadPrefs() {
        val p = getSharedPreferences("clock", MODE_PRIVATE)
        nextEntryOn = p.getBoolean("next_entry_on", false)
        onlineOn = ServerPrefs.isOnlineOn(this)
        onlineBase = loadOnlineBase()
    }

    // 暗号：连续点击时间 10 次，或长按时间区域 ≥30 秒，切换「下次进入时间」显示
    private fun setupSecretGesture() {
        timeText.isClickable = true
        timeText.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    longPressStart = System.currentTimeMillis()
                }
                MotionEvent.ACTION_UP -> {
                    val dur = System.currentTimeMillis() - longPressStart
                    if (dur >= 30000) {
                        toggleNextEntry()
                    } else {
                        val now = System.currentTimeMillis()
                        if (now - lastTapTime > TAP_WINDOW) tapCount = 0
                        tapCount++
                        lastTapTime = now
                        if (tapCount >= 10) {
                            tapCount = 0
                            toggleNextEntry()
                        }
                    }
                }
            }
            true
        }
    }

    private fun toggleNextEntry() {
        nextEntryOn = !nextEntryOn
        getSharedPreferences("clock", MODE_PRIVATE)
            .edit().putBoolean("next_entry_on", nextEntryOn).apply()
        Toast.makeText(
            this,
            if (nextEntryOn) "下次进入时间：开" else "下次进入时间：关",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
    }

    // 根据屏宽自适应字号，保证 "HH:mm:ss" 一行铺满又不溢出
    private fun fitTextSize() {
        val dm = resources.displayMetrics
        val wDp = dm.widthPixels / dm.density
        val size = ((wDp * 0.85f) / (8f * 0.6f)).coerceIn(40f, 170f)
        timeText.textSize = size
        dateText.textSize = (size * 0.26f).coerceIn(18f, 40f)
    }

    private fun updateClock() {
        val now = System.currentTimeMillis() + (offset ?: 0)
        val cal = Calendar.getInstance(shanghai)
        cal.timeInMillis = now
        timeText.text = timeFmt.format(cal.time)
        dateText.text = dateFmt.format(cal.time)

        // 仅当暗号已开启，且当前选中服务器为「微软 / 阿里云备用」时才显示
        val selected = ServerPrefs.getSelected(this)
        val showNext = nextEntryOn && ServerPrefs.isNextEntryHost(selected)
        if (showNext) {
            nextEntryText.visibility = View.VISIBLE
            nextEntryText.text = "下次进入 ${nextEntryTime(now)}"
        } else {
            nextEntryText.visibility = View.GONE
        }

        // 实时在线人数：由序列暗号开启，且已配置 Worker 地址时才显示
        onlineText.visibility = if (onlineOn && onlineBase.isNotEmpty()) View.VISIBLE else View.GONE
    }

    // 加入时间规律：每分钟的第 1/5/9/13… 分（每 4 分钟一次，minute % 4 == 1）。
    // 返回从 now 起下一个符合条件的整分时刻（秒/毫秒归零）。
    private fun nextEntryTime(now: Long): String {
        val cal = Calendar.getInstance(shanghai)
        cal.timeInMillis = now
        val m = cal.get(Calendar.MINUTE)
        val s = cal.get(Calendar.SECOND)
        val ms = cal.get(Calendar.MILLISECOND)
        var deltaMin = (4 - ((m - 1) % 4)) % 4
        if (deltaMin == 0 && (s > 0 || ms > 0)) deltaMin = 4
        cal.add(Calendar.MINUTE, deltaMin)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return timeFmt.format(cal.time)
    }

    // 优先用用户在设置里选的服务器；失败再静默回退其他服务器，绝不显示具体地址
    private fun syncTime() {
        val prefs = getSharedPreferences("clock", MODE_PRIVATE)
        val selected = ServerPrefs.getSelected(this)
        val custom = ServerPrefs.getCustomHosts(prefs)
        val defaults = ServerPrefs.defaultServers.map { it.second }
        val order = mutableListOf(selected)
        (defaults + custom).filter { it != selected }.toCollection(order)

        syncIndicator.text = "同步中…"
        thread {
            for (host in order) {
                try {
                    val off = SntpClient.requestTime(host)
                    offset = off
                    handler.post { syncIndicator.text = "已校准" }
                    return@thread
                } catch (e: Exception) {
                    // 尝试下一个
                }
            }
            handler.post { syncIndicator.text = "未校准 · 本机时间" }
        }
    }

    // ===== 实时在线人数 =====
    private fun startOnlinePolling() {
        handler.postDelayed(onlineTimer, 1000)
    }

    private val onlineTimer = object : Runnable {
        override fun run() {
            if (onlineOn && onlineBase.isNotEmpty()) {
                fetchOnline()
            }
            handler.postDelayed(this, 30_000)
        }
    }

    private fun deviceUuid(): String {
        val p = getSharedPreferences("clock", MODE_PRIVATE)
        var u = p.getString("device_uuid", null)
        if (u == null) {
            u = UUID.randomUUID().toString()
            p.edit().putString("device_uuid", u).apply()
        }
        return u
    }

    // 从 APK 内置 assets/online_config.xml 读取 Worker 地址（发布前改此文件即可，设置界面不可改）
    private fun loadOnlineBase(): String {
        return try {
            val parser = Xml.newPullParser()
            parser.setInput(assets.open("online_config.xml"), "utf-8")
            var url = ""
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "online") {
                    url = parser.getAttributeValue(null, "url") ?: ""
                    break
                }
                event = parser.next()
            }
            url.trim().trimEnd('/')
        } catch (e: Exception) {
            ""
        }
    }

    private fun fetchOnline() {
        thread {
            try {
                val url = URL("${onlineBase.trimEnd('/')}/heartbeat?id=${deviceUuid()}")
                val con = url.openConnection() as HttpURLConnection
                con.connectTimeout = 10_000
                con.readTimeout = 10_000
                val txt = con.inputStream.bufferedReader().readText()
                val n = JSONObject(txt).optInt("online", -1)
                handler.post {
                    onlineText.text = if (n >= 0) "在线 $n 人" else "在线 --"
                }
            } catch (e: Exception) {
                handler.post { onlineText.text = "在线 获取失败" }
            }
        }
    }
}
