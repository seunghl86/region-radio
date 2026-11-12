package kr.regionradio

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class Channel(
    val key: String,
    val name: String,
    val kind: String,
    val streamType: String, // kbs, mbc, sbs, direct, none
    val streamArg: String
) {
    val hasStream: Boolean get() = streamType != "none"
}

data class Station(val ch: String, val freq: Double, val localName: String, val note: String)

data class Region(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val stations: List<Station>
)

/** assets/stations.json 에서 읽은 방송·지역 목록 */
object Catalog {
    var channels: Map<String, Channel> = emptyMap()
        private set
    var regions: List<Region> = emptyList()
        private set

    @Synchronized
    fun load(ctx: Context) {
        if (regions.isNotEmpty()) return
        val text = ctx.assets.open("stations.json").bufferedReader().use { it.readText() }
        val root = JSONObject(text)
        val chArr = root.getJSONArray("channels")
        val chMap = LinkedHashMap<String, Channel>()
        for (i in 0 until chArr.length()) {
            val o = chArr.getJSONObject(i)
            val c = Channel(
                o.getString("key"), o.getString("name"), o.getString("kind"),
                o.getString("streamType"), o.getString("streamArg")
            )
            chMap[c.key] = c
        }
        val rArr = root.getJSONArray("regions")
        val rList = ArrayList<Region>()
        for (i in 0 until rArr.length()) {
            val o = rArr.getJSONObject(i)
            val sArr = o.getJSONArray("stations")
            val sts = ArrayList<Station>()
            for (j in 0 until sArr.length()) {
                val s = sArr.getJSONObject(j)
                sts.add(Station(s.getString("ch"), s.getDouble("freq"), s.optString("localName"), s.optString("note")))
            }
            rList.add(Region(o.getString("id"), o.getString("name"), o.getDouble("lat"), o.getDouble("lon"), sts))
        }
        channels = chMap
        regions = rList
    }

    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 6371.0 * 2 * asin(sqrt(a))
    }

    fun nearest(lat: Double, lon: Double): Region =
        regions.minByOrNull { distanceKm(lat, lon, it.lat, it.lon) } ?: regions[0]

    fun stationName(st: Station): String =
        if (st.localName.isNotEmpty()) st.localName else (channels[st.ch]?.name ?: st.ch)

    fun freqText(f: Double): String = String.format(Locale.US, "%.1f", f)
}

/** 현재 지역과 자동/수동 상태. 앱 화면과 재생 서비스가 함께 쓴다. */
object RadioState {
    interface Listener {
        fun onRegionChanged()
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    private var prefs: SharedPreferences? = null

    lateinit var region: Region
        private set
    var auto: Boolean = true
        private set

    @Synchronized
    fun init(ctx: Context) {
        if (prefs != null) return
        Catalog.load(ctx)
        val p = ctx.applicationContext.getSharedPreferences("radio", Context.MODE_PRIVATE)
        auto = p.getBoolean("auto", true)
        val saved = p.getString("region", "cheonan")
        region = Catalog.regions.firstOrNull { it.id == saved } ?: Catalog.regions[0]
        prefs = p
    }

    fun addListener(l: Listener) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    fun setAutoMode(v: Boolean) {
        auto = v
        prefs?.edit()?.putBoolean("auto", v)?.apply()
    }

    fun changeRegion(r: Region) {
        if (r.id == region.id) return
        region = r
        prefs?.edit()?.putString("region", r.id)?.apply()
        for (l in listeners) l.onRegionChanged()
    }
}
