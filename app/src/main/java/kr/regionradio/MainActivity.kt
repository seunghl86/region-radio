package kr.regionradio

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

class MainActivity : Activity(), RadioState.Listener {

    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private lateinit var regionText: TextView
    private lateinit var nowText: TextView
    private lateinit var freqText: TextView
    private lateinit var listBox: LinearLayout
    private lateinit var spinner: Spinner
    private lateinit var stopButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RadioState.init(this)
        buildUi()
        askPermissions()
    }

    override fun onStart() {
        super.onStart()
        RadioState.addListener(this)
        RegionTracker.start(applicationContext)
        val token = SessionToken(this, ComponentName(this, RadioService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        future = f
        f.addListener({
            try {
                val c = f.get()
                controller = c
                c.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) {
                        render()
                    }
                })
                render()
            } catch (e: Exception) {
                Toast.makeText(this, "재생 서비스에 연결하지 못했습니다. 앱을 다시 열어 주세요.", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
        render()
    }

    override fun onStop() {
        RadioState.removeListener(this)
        future?.let { MediaController.releaseFuture(it) }
        future = null
        controller = null
        super.onStop()
    }

    override fun onRegionChanged() {
        runOnUiThread {
            val r = RadioState.region
            Toast.makeText(this, r.name + " 주파수로 맞췄습니다.", Toast.LENGTH_SHORT).show()
            render()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(20), dp(24), dp(20), dp(32))

        val title = TextView(this)
        title.text = "지역 라디오"
        title.textSize = 22f
        title.setTypeface(Typeface.DEFAULT_BOLD)
        root.addView(title)

        spinner = Spinner(this)
        val names = ArrayList<String>()
        names.add("위치에 따라 자동")
        for (r in Catalog.regions) names.add(r.name)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        spinner.setSelection(if (RadioState.auto) 0 else Catalog.regions.indexOfFirst { it.id == RadioState.region.id } + 1)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == 0) {
                    if (!RadioState.auto) {
                        RadioState.setAutoMode(true)
                        RegionTracker.start(applicationContext)
                    }
                } else {
                    RadioState.setAutoMode(false)
                    RadioState.changeRegion(Catalog.regions[position - 1])
                }
                render()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        root.addView(spinner, lp(top = 12))

        regionText = TextView(this)
        regionText.textSize = 14f
        root.addView(regionText, lp(top = 4))

        nowText = TextView(this)
        nowText.textSize = 18f
        nowText.setTypeface(Typeface.DEFAULT_BOLD)
        root.addView(nowText, lp(top = 20))

        freqText = TextView(this)
        freqText.textSize = 56f
        freqText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        freqText.setTextColor(0xFFD9480F.toInt())
        root.addView(freqText)

        stopButton = Button(this)
        stopButton.isAllCaps = false
        stopButton.text = "재생 멈춤"
        stopButton.setOnClickListener { controller?.pause() }
        root.addView(stopButton, lp(top = 4))

        listBox = LinearLayout(this)
        listBox.orientation = LinearLayout.VERTICAL
        root.addView(listBox, lp(top = 16))

        if (Build.VERSION.SDK_INT >= 29) {
            val bg = Button(this)
            bg.isAllCaps = false
            bg.text = "차에서 화면을 꺼도 지역 자동 전환: 위치 '항상 허용' 설정"
            bg.setOnClickListener { askBackgroundLocation() }
            root.addView(bg, lp(top = 20))
        }

        val foot = TextView(this)
        foot.textSize = 12f
        foot.text = "소리는 방송사 인터넷 실시간 방송으로 나옵니다. 지역 민영방송은 인터넷 재생 시 서울 본방송으로 나올 수 있습니다. " +
            "'주파수만' 표시된 방송은 차량 라디오에서 그 주파수로 맞춰 들으세요."
        root.addView(foot, lp(top = 16))

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun lp(top: Int): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        p.topMargin = dp(top)
        return p
    }

    private fun render() {
        val r = RadioState.region
        regionText.text = "현재 지역: " + r.name + if (RadioState.auto) " (위치 자동)" else " (직접 선택)"

        val c = controller
        val curId = c?.currentMediaItem?.mediaId
        val curSt = r.stations.firstOrNull { it.ch == curId }
        val curCh = if (curId != null) Catalog.channels[curId] else null
        if (curCh == null) {
            nowText.text = "아래에서 방송을 고르세요"
            freqText.text = "--.-"
        } else {
            val playing = c?.isPlaying == true
            nowText.text = (if (curSt != null) Catalog.stationName(curSt) else curCh.name) + if (playing) " · 재생 중" else " · 멈춤"
            freqText.text = if (curSt != null) Catalog.freqText(curSt.freq) else "--.-"
        }
        stopButton.visibility = if (curCh != null) View.VISIBLE else View.GONE

        listBox.removeAllViews()
        for (st in r.stations.sortedBy { it.freq }) {
            val ch = Catalog.channels[st.ch] ?: continue
            val b = Button(this)
            b.isAllCaps = false
            b.textSize = 16f
            b.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            var label = Catalog.freqText(st.freq) + "   " + Catalog.stationName(st)
            if (st.note.isNotEmpty()) label += " (" + st.note + ")"
            if (!ch.hasStream) label += " · 주파수만"
            b.text = label
            if (st.ch == curId) b.setTypeface(Typeface.DEFAULT_BOLD)
            b.setOnClickListener { play(ch) }
            listBox.addView(b, lp(top = 4))
        }
    }

    private fun play(ch: Channel) {
        if (!ch.hasStream) {
            Toast.makeText(this, "이 방송은 인터넷 재생 주소가 없어 주파수만 안내합니다.", Toast.LENGTH_SHORT).show()
            return
        }
        val c = controller
        if (c == null) {
            Toast.makeText(this, "재생 서비스에 연결하는 중입니다. 잠시 뒤 다시 눌러 주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        c.setMediaItem(MediaItem.Builder().setMediaId(ch.key).build())
        c.prepare()
        c.play()
    }

    private fun granted(p: String): Boolean = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun askPermissions() {
        val need = ArrayList<String>()
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION)
            need.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
    }

    private fun askBackgroundLocation() {
        if (Build.VERSION.SDK_INT < 29) return
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION) && !granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            askPermissions()
            return
        }
        if (granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            Toast.makeText(this, "이미 '항상 허용'으로 되어 있습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 2)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        RegionTracker.start(applicationContext)
        render()
    }
}
