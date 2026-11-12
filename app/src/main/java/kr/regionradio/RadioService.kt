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
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
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
            val cur = player.currentMediaItem ?: return@post
            val ch = Catalog.channels[cur.mediaId] ?: return@post
            val updated = cur.buildUpon().setMediaMetadata(metadata(ch)).build()
            player.replaceMediaItem(player.currentMediaItemIndex, updated)
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
        return MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(sub)
            .setSubtitle(sub)
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
