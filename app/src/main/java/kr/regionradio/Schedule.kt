package kr.regionradio

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/** 편성표의 프로그램 하나. 시각은 자정부터의 분. */
data class Prog(val start: Int, val end: Int, val title: String)

/**
 * 방송사 편성표를 받아 온다(오늘 하루치, 서울 본방송 기준).
 * 방송사가 공식 공개한 주소가 아니어서 바뀌면 이 파일을 고친다.
 */
object Schedule {
    private val SRC = mapOf(
        "k1" to "kbs:21", "k2" to "kbs:22", "kc" to "kbs:25",
        "ms" to "mbc:STFM", "m4" to "mbc:FM4U",
        "sp" to "sbs:Power", "sl" to "sbs:Love"
    )
    private const val RETRY_MS = 5 * 60 * 1000L
    private val cache = ConcurrentHashMap<String, Pair<String, List<Prog>>>()
    private val failedAt = ConcurrentHashMap<String, Long>()

    fun has(key: String): Boolean = SRC.containsKey(key)

    private fun kst(): Calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Seoul"))

    private fun today(): String {
        val c = kst()
        return String.format(Locale.US, "%04d%02d%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    fun nowMinutes(): Int {
        val c = kst()
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    fun hm(min: Int): String = String.format(Locale.US, "%02d:%02d", (min / 60) % 24, min % 60)

    fun inRange(p: Prog, now: Int): Boolean =
        if (p.end > p.start) now >= p.start && now < p.end else now >= p.start || now < p.end

    fun cached(key: String): List<Prog>? {
        val e = cache[key] ?: return null
        return if (e.first == today()) e.second else null
    }

    fun current(key: String): Prog? {
        val list = cached(key) ?: return null
        val now = nowMinutes()
        return list.firstOrNull { inRange(it, now) }
    }

    /** 아직 받지 않았고, 방금 실패한 것도 아닌 경우에만 true */
    fun needsLoad(key: String): Boolean {
        if (!has(key) || cached(key) != null) return false
        val f = failedAt[key] ?: return true
        return System.currentTimeMillis() - f > RETRY_MS
    }

    /** 네트워크 호출이므로 백그라운드 스레드에서 부른다. 실패하면 null. */
    fun load(key: String, force: Boolean = false): List<Prog>? {
        cached(key)?.let { return it }
        val src = SRC[key] ?: return null
        if (!force && !needsLoad(key)) return null
        val list = try {
            val type = src.substringBefore(':')
            val arg = src.substringAfter(':')
            when (type) {
                "kbs" -> kbs(arg)
                "mbc" -> mbc(arg)
                "sbs" -> sbs(arg)
                else -> emptyList()
            }.sortedBy { it.start }
        } catch (e: Exception) {
            emptyList()
        }
        if (list.isEmpty()) {
            failedAt[key] = System.currentTimeMillis()
            return null
        }
        failedAt.remove(key)
        cache[key] = Pair(today(), list)
        return list
    }

    private fun hhmm(s: String): Int {
        val d = s.filter { it.isDigit() }
        if (d.length < 4) return 0
        return (d.substring(0, 2).toInt() * 60 + d.substring(2, 4).toInt()) % 1440
    }

    private fun kbs(code: String): List<Prog> {
        val d = today()
        val body = StreamResolver.get(
            "https://static.api.kbs.co.kr/mediafactory/v1/schedule/weekly?rtype=json&local_station_code=00" +
                "&channel_code=$code&program_planned_date_from=$d&program_planned_date_to=$d",
            "https://schedule.kbs.co.kr/"
        )
        val days = JSONArray(body)
        val out = ArrayList<Prog>()
        for (i in 0 until days.length()) {
            val arr = days.getJSONObject(i).optJSONArray("schedules") ?: continue
            for (j in 0 until arr.length()) {
                val o = arr.getJSONObject(j)
                val title = o.optString("program_title").trim()
                if (title.isEmpty()) continue
                out.add(Prog(hhmm(o.optString("program_planned_start_time")), hhmm(o.optString("program_planned_end_time")), title))
            }
        }
        return out.distinctBy { it.start }
    }

    private fun mbc(channel: String): List<Prog> {
        val body = StreamResolver.get("https://miniunit.imbc.com/Schedule?rtype=json", "http://mini.imbc.com/")
        val arr = JSONObject(body).getJSONArray("Programs")
        val day = "일월화수목금토"[kst().get(Calendar.DAY_OF_WEEK) - 1].toString()
        val out = ArrayList<Prog>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("Channel") != channel) continue
            if (!o.optString("LiveDays").contains(day)) continue
            val title = o.optString("ProgramTitle").trim()
            if (title.isEmpty()) continue
            val start = hhmm(o.optString("StartTime"))
            val run = o.optString("RunningTime").toIntOrNull() ?: 0
            out.add(Prog(start, (start + run) % 1440, title))
        }
        return out.distinctBy { it.start }
    }

    private fun sbs(name: String): List<Prog> {
        val c = kst()
        val url = "https://static.cloud.sbs.co.kr/schedule/" + c.get(Calendar.YEAR) + "/" +
            (c.get(Calendar.MONTH) + 1) + "/" + c.get(Calendar.DAY_OF_MONTH) + "/" + name + ".json"
        val arr = JSONArray(StreamResolver.get(url, "https://www.sbs.co.kr/"))
        val out = ArrayList<Prog>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val title = o.optString("title").trim()
            if (title.isEmpty()) continue
            out.add(Prog(hhmm(o.optString("start_time")), hhmm(o.optString("end_time")), title))
        }
        return out.distinctBy { it.start }
    }
}
