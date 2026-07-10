package com.example.clock

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var timeText: TextView
    private lateinit var dateText: TextView
    private lateinit var statusText: TextView

    private val handler = Handler(Looper.getMainLooper())

    // NTP 校准得到的本地时间与真实时间的偏移（毫秒）。null 表示尚未校准。
    private var offset: Long? = null

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
        statusText = findViewById(R.id.statusText)

        fitTextSize()
        handler.post(tick)
        syncTime()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
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
        // 8 个字符（HH:mm:ss），数字字宽约为字号的 0.6 倍，目标占屏宽 85%
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
    }

    private fun syncTime() {
        statusText.text = "正在连接时间服务器…"
        thread {
            val servers = listOf(
                "ntp.aliyun.com",
                "cn.pool.ntp.org",
                "time.apple.com",
                "time.google.com",
                "pool.ntp.org"
            )
            for (host in servers) {
                try {
                    val off = SntpClient.requestTime(host)
                    offset = off
                    handler.post { statusText.text = "已校准 · $host" }
                    return@thread
                } catch (e: Exception) {
                    // 尝试下一个服务器
                }
            }
            handler.post { statusText.text = "未能连接，使用本机时间" }
        }
    }
}
