package kr.regionradio

import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.util.concurrent.Executors

/** 재생 서비스. 휴대전화 알림, 블루투스, 안드로이드 오토가 모두 여기에 붙는다. */
class RadioService : MediaLibraryService(), RadioState.Listener {

    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var retried = false

    override fun onCreate() {
        super.onCreate()
        RadioState.init(this)

        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(attrs, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) retried = false
            }

            // 주소 유효 시간이 지나 끊기면 주소를 새로 받아 한 번 다시 시도한다
            override fun onPlayerError(error: PlaybackException) {
                val id = player.currentMediaItem?.mediaId ?: return
                if (retried) return
                retried = true
                io.execute {
                    val item = resolveItem(id) ?: return@execute
                    main.post {
                        player.setMediaItem(item)
                        player.prepare()
                        player.play()
                    }
                }
            }
        })

        session = MediaLibrarySession.Builder(this, player, LibraryCallback()).build()
        RadioState.addListener(this)
        RegionTracker.start(this)
        main.postDelayed(ticker, 300_000L)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        main.removeCallbacks(ticker)
        RadioState.removeListener(this)
        RegionTracker.stop()
        session.release()
        player.release()
        io.shutdown()
        super.onDestroy()
    }

    /** 지역이 바뀌면 목록을 새로 알리고, 듣고 있는 방송의 표시 주파수를 새 지역 것으로 바꾼다. */
    override fun onRegionChanged() {
        main.post {
            session.notifyChildrenChanged(ROOT_ID, playableStations().size, null)
            refreshCurrent()
        }
    }

    /** 듣고 있는 방송의 표시 정보(주파수, 프로그램 이름)를 지금 기준으로 고친다. 재생은 끊기지 않는다. */
    private fun refreshCurrent() {
        val cur = player.currentMediaItem ?: return
        val ch = Catalog.channels[cur.mediaId] ?: return
        val m = metadata(ch)
        if (m.title?.toString() == cur.mediaMetadata.title?.toString() &&
            m.artist?.toString() == cur.mediaMetadata.artist?.toString()
        ) return
        player.replaceMediaItem(player.currentMediaItemIndex, cur.buildUpon().setMediaMetadata(m).build())
    }

    /** 5분마다 프로그램 이름을 새로 확인한다 */
    private val ticker = object : Runnable {
        override fun run() {
            val id = player.currentMediaItem?.mediaId
            if (id != null) {
                io.execute {
                    Schedule.load(id)
                    main.post { refreshCurrent() }
                }
            }
            main.postDelayed(this, 300_000L)
        }
    }

    private fun playableStations(): List<Station> =
        RadioState.region.stations
            .filter { Catalog.channels[it.ch]?.hasStream == true }
            .sortedBy { it.freq }

    private fun metadata(ch: Channel): MediaMetadata {
        val r = RadioState.region
        val st = r.stations.firstOrNull { it.ch == ch.key }
        val title = if (st != null) Catalog.stationName(st) else ch.name
        val sub = if (st != null) {
            r.name + " · FM " + Catalog.freqText(st.freq) + "MHz"
        } else {
            r.name + "에서는 FM 주파수 없음"
        }
        val prog = Schedule.current(ch.key)
        val line = if (prog != null) prog.title + " · " + sub else sub
        return MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(line)
            .setSubtitle(line)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
            .build()
    }

    private fun browseItem(ch: Channel): MediaItem =
        MediaItem.Builder().setMediaId(ch.key).setMediaMetadata(metadata(ch)).build()

    private fun rootItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId(ROOT_ID)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("지역 라디오")
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS)
                    .build()
            )
            .build()

    /** 방송 주소를 받아 재생할 수 있는 항목으로 만든다. 네트워크 호출이 있어 io 스레드에서만 부른다. */
    private fun resolveItem(mediaId: String): MediaItem? {
        val ch = Catalog.channels[mediaId] ?: return null
        val url = StreamResolver.resolve(ch) ?: return null
        Schedule.load(ch.key) // 지금 방송 중인 프로그램 이름을 함께 보여 주기 위해
        val b = MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(url)
            .setMediaMetadata(metadata(ch))
        if (url.contains("m3u8")) b.setMimeType(MimeTypes.APPLICATION_M3U8)
        return b.build()
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(rootItem(), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val items = playableStations().mapNotNull { st -> Catalog.channels[st.ch]?.let { browseItem(it) } }
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val ch = Catalog.channels[mediaId]
                ?: return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
            return Futures.immediateFuture(LibraryResult.ofItem(browseItem(ch), null))
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val future = SettableFuture.create<MutableList<MediaItem>>()
            val ids = mediaItems.map { it.mediaId }
            io.execute {
                val out: MutableList<MediaItem> = ArrayList()
                for (id in ids) resolveItem(id)?.let { out.add(it) }
                future.set(out)
            }
            return future
        }
    }

    companion object {
        const val ROOT_ID = "root"
    }
}
