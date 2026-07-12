package com.example.clock

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * 服务器选择持久化：默认服务器列表 + 用户自定义服务器（SharedPreferences）。
 */
object ServerPrefs {

    // 默认服务器：覆盖国内低延迟与常见公共节点（第一项「阿里云」为默认选中）
    val defaultServers: List<Pair<String, String>> = listOf(
        "阿里云" to "ntp.aliyun.com",
        "阿里云备用" to "ntp1.aliyun.com",
        "腾讯" to "ntp.tencent.com",
        "苹果" to "time.apple.com",
        "中科院国家授时中心" to "ntp.ntsc.ac.cn",
        "中国 NTP 池" to "cn.pool.ntp.org",
        "微软" to "time.windows.com",
        "谷歌" to "time.google.com",
        "Cloudflare" to "time.cloudflare.com",
        "NTP 池" to "pool.ntp.org"
    )

    // 仅当选中以下服务器时，主界面才显示「下次进入时间」
    val NEXT_ENTRY_HOSTS: Set<String> = setOf("time.windows.com", "ntp1.aliyun.com")

    fun isNextEntryHost(host: String): Boolean = NEXT_ENTRY_HOSTS.contains(host)

    fun getSelected(ctx: Context): String {
        val p = ctx.getSharedPreferences("clock", Context.MODE_PRIVATE)
        return p.getString("selected_server", defaultServers[0].second)
            ?: defaultServers[0].second
    }

    fun getCustomHosts(p: SharedPreferences): List<String> {
        val s = p.getString("custom_servers", null) ?: return emptyList()
        return try {
            val arr = JSONArray(s)
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) out.add(arr.getString(i))
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun setCustomHosts(p: SharedPreferences, list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        p.edit().putString("custom_servers", arr.toString()).apply()
    }

    // ===== 实时在线人数功能 =====
    // 触发序列：阿里云备用 -> 微软 -> 阿里云 -> (阿里云备用 | 微软)
    private const val SEQ_A = "ntp1.aliyun.com"   // 阿里云备用
    private const val SEQ_B = "time.windows.com"   // 微软
    private const val SEQ_C = "ntp.aliyun.com"     // 阿里云（默认）

    // 记录一次服务器选择，返回是否恰好匹配「在线人数触发序列」
    fun recordSelection(ctx: Context, host: String): Boolean {
        val p = ctx.getSharedPreferences("clock", Context.MODE_PRIVATE)
        val arr = try { JSONArray(p.getString("selection_history", "[]")) } catch (e: Exception) { JSONArray() }
        arr.put(host)
        while (arr.length() > 8) arr.remove(0)
        p.edit().putString("selection_history", arr.toString()).apply()
        if (arr.length() >= 4) {
            val n = arr.length()
            val a = arr.optString(n - 4)
            val b = arr.optString(n - 3)
            val c = arr.optString(n - 2)
            val d = arr.optString(n - 1)
            if (a == SEQ_A && b == SEQ_B && c == SEQ_C &&
                (d == SEQ_A || d == SEQ_B)) {
                return true
            }
        }
        return false
    }

    fun isOnlineOn(ctx: Context): Boolean =
        ctx.getSharedPreferences("clock", Context.MODE_PRIVATE).getBoolean("online_on", false)

    fun setOnlineOn(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences("clock", Context.MODE_PRIVATE).edit().putBoolean("online_on", on).apply()
    }

}
