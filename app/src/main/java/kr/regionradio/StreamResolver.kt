package kr.regionradio

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 방송사 인터넷 실시간 방송 주소를 얻는다.
 * 방송사가 공식 공개한 주소가 아니므로 언제든 바뀌거나 막힐 수 있다.
 * 주소가 바뀌면 assets/stations.json 의 streamType / streamArg 와 이 파일만 고치면 된다.
 */
object StreamResolver {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    /** 네트워크 호출이므로 반드시 백그라운드 스레드에서 부른다. 실패하면 null. */
    fun resolve(ch: Channel): String? {
        return try {
            when (ch.streamType) {
                "direct" -> ch.streamArg
                "kbs" -> kbs(ch.streamArg)
                "mbc" -> plain(
                    "https://sminiplay.imbc.com/aacplay.ashx?agent=webapp&channel=" + ch.streamArg,
                    "http://mini.imbc.com/"
                )
                "sbs" -> plain(
                    "https://apis.sbs.co.kr/play-api/1.0/livestream/" + ch.streamArg + "?protocol=hls&ssl=Y",
                    "https://www.sbs.co.kr/"
                )
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun kbs(code: String): String? {
        val body = get("https://cfpwwwapi.kbs.co.kr/api/v1/landing/live/channel_code/$code", "https://onair.kbs.co.kr/")
        val items = JSONObject(body).getJSONArray("channel_item")
        for (i in 0 until items.length()) {
            val url = items.getJSONObject(i).optString("service_url")
            if (url.startsWith("http")) return url
        }
        return null
    }

    private fun plain(url: String, referer: String): String? {
        val body = get(url, referer).trim().trim('"')
        return if (body.startsWith("http")) body else null
    }

    private fun get(url: String, referer: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Referer", referer)
        try {
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
