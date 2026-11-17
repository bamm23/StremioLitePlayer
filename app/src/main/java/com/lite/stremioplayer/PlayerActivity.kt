package com.lite.stremioplayer

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import java.util.Locale

class PlayerActivity : Activity() {

    private var player: ExoPlayer? = null
    private lateinit var surfaceView: SurfaceView
    private lateinit var subtitleView: SubtitleView
    private lateinit var tvStatus: TextView
    private lateinit var osdContainer: LinearLayout
    private lateinit var tvTime: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnPlayPause: Button
    private lateinit var btnAudio: Button
    private lateinit var btnSubtitles: Button
    private lateinit var btnAspect: Button

    private val handler = Handler(Looper.getMainLooper())
    private var lastKnownPositionMs: Long = 0L
    private var currentStreamKey: String = ""
    private var backPressedTime: Long = 0L
    private var isVerticalStretch = true
    private var currentVideoWidth = 0
    private var currentVideoHeight = 0

    private val progressRunnable = object : Runnable {
        override fun run() {
            val exo = player
            if (exo != null && exo.isPlaying) {
                val pos = exo.currentPosition
                val dur = exo.duration
                if (pos > 0) {
                    lastKnownPositionMs = pos
                    savePosition(pos)
                }
                if (osdContainer.visibility == View.VISIBLE && dur > 0) {
                    tvTime.text = "${formatTime(pos)} / ${formatTime(dur)}"
                    progressBar.progress = ((pos * 1000) / dur).toInt()
                }
            }
            handler.postDelayed(this, 2000)
        }
    }

    private val hideOsdRunnable = Runnable {
        osdContainer.visibility = View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)

        surfaceView = findViewById(R.id.surfaceView)
        subtitleView = findViewById(R.id.subtitleView)
        tvStatus = findViewById(R.id.tvStatus)
        osdContainer = findViewById(R.id.osdContainer)
        tvTime = findViewById(R.id.tvTime)
        progressBar = findViewById(R.id.progressBar)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnAudio = findViewById(R.id.btnAudio)
        btnSubtitles = findViewById(R.id.btnSubtitles)
        btnAspect = findViewById(R.id.btnAspect)

        // Estilo limpio para subtítulos con borde negro y fondo transparente
        subtitleView.setStyle(
            CaptionStyleCompat(
                Color.WHITE,
                Color.TRANSPARENT,
                Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                Color.BLACK,
                null
            )
        )

        btnPlayPause.setOnClickListener {
            player?.let { exo ->
                if (exo.isPlaying) exo.pause() else exo.play()
                resetOsdTimer()
            }
        }
        btnAudio.setOnClickListener {
            showTrackDialog(C.TRACK_TYPE_AUDIO, "Seleccionar Audio")
        }
        btnSubtitles.setOnClickListener {
            showTrackDialog(C.TRACK_TYPE_TEXT, "Seleccionar Subtítulos")
        }
        btnAspect.setOnClickListener {
            isVerticalStretch = !isVerticalStretch
            btnAspect.text = if (isVerticalStretch) "Modo: Estirado 16:9" else "Modo: Original"
            applyAspectRatio()
            resetOsdTimer()
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            setIntent(intent)
            handleIntent(intent)
        }
    }

    private fun handleIntent(intent: Intent) {
        val videoUri: Uri? = intent.data
        if (videoUri == null) {
            tvStatus.visibility = View.VISIBLE
            tvStatus.text = "Esperando enlace desde Stremio...\nSelecciona 'Reproducir en reproductor externo' en Stremio."
            return
        }

        // Clave única del video ignorando puertos efímeros
        currentStreamKey = videoUri.path ?: videoUri.toString()

        // Recuperar posición previa si se cerró o reconectó
        val intentPos = intent.getIntExtra("position", -1).toLong()
        val savedPos = getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
            .getLong(currentStreamKey, 0L)
        lastKnownPositionMs = if (intentPos > 0) intentPos else savedPos

        val subtitleConfigs = extractExternalSubtitles(intent)
        initPlayer(videoUri, subtitleConfigs)
    }

    @Suppress("DEPRECATION")
    private fun extractExternalSubtitles(intent: Intent): List<MediaItem.SubtitleConfiguration> {
        val configs = mutableListOf<MediaItem.SubtitleConfiguration>()
        val subsArray = intent.getParcelableArrayExtra("subs")
        val subsNames = intent.getStringArrayExtra("subs.name")

        subsArray?.forEachIndexed { index, parcelable ->
            if (parcelable is Uri) {
                val label = subsNames?.getOrNull(index) ?: "Subtítulo Externo ${index + 1}"
                val uriStr = parcelable.toString().lowercase(Locale.ROOT)
                val mime = when {
                    uriStr.endsWith(".vtt") -> MimeTypes.TEXT_VTT
                    uriStr.endsWith(".ass") || uriStr.endsWith(".ssa") -> MimeTypes.TEXT_SSA
                    else -> MimeTypes.APPLICATION_SUBRIP
                }
                configs.add(
                    MediaItem.SubtitleConfiguration.Builder(parcelable)
                        .setMimeType(mime)
                        .setLanguage(label)
                        .setLabel(label)
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                )
            }
        }

        // Soporte adicional por si Stremio o VLC style envía "subtitles_location"
        val singleSub = intent.getStringExtra("subtitles_location")
        if (!singleSub.isNullOrEmpty()) {
            configs.add(
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(singleSub))
                    .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                    .setLanguage("Subtítulo Stremio")
                    .setLabel("Subtítulo Stremio")
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()
            )
        }
        return configs
    }

    private fun initPlayer(videoUri: Uri, subs: List<MediaItem.SubtitleConfiguration>) {
        releasePlayer()

        // 1. Control estricto de RAM: máximo 14 MB de búfer y 0 back-buffer
        val loadControl = DefaultLoadControl.Builder()
            .setAllocator(DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE))
            .setBufferDurationsMs(
                4_000,  // minBufferMs
                12_000, // maxBufferMs
                1_500,  // bufferForPlaybackMs
                3_000   // bufferForPlaybackAfterRebufferMs
            )
            .setTargetBufferBytes(14 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(0, false)
            .build()

        // 2. Tolerancia alta a microcortes del motor de torrents local de Stremio
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(25_000)
            .setAllowCrossProtocolRedirects(true)

        val errorPolicy = object : DefaultLoadErrorHandlingPolicy() {
            override fun getMinimumLoadableRetryCount(dataType: Int): Int = 12
        }

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(httpDataSourceFactory)
            .setLoadErrorHandlingPolicy(errorPolicy)

        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)

        player = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build().apply {
                setVideoSurfaceView(surfaceView)
                addListener(playerListener)

                val mediaItem = MediaItem.Builder()
                    .setUri(videoUri)
                    .setSubtitleConfigurations(subs)
                    .build()

                setMediaItem(mediaItem)
                if (lastKnownPositionMs > 0) {
                    seekTo(lastKnownPositionMs)
                }
                prepare()
                playWhenReady = true
            }

        handler.post(progressRunnable)
    }

    private val playerListener = object : Player.Listener {
        override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
            subtitleView.setCues(cueGroup.cues)
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            currentVideoWidth = videoSize.width
            currentVideoHeight = videoSize.height
            applyAspectRatio()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    tvStatus.visibility = View.VISIBLE
                    tvStatus.text = "Cargando búfer..."
                }
                Player.STATE_READY -> {
                    tvStatus.visibility = View.GONE
                }
                Player.STATE_ENDED -> {
                    savePosition(0L)
                    finish()
                }
                else -> {}
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val exo = player ?: return
            if (exo.currentPosition > 0) {
                lastKnownPositionMs = exo.currentPosition
                savePosition(lastKnownPositionMs)
            }
            tvStatus.visibility = View.VISIBLE
            tvStatus.text = "Microcorte detectado. Reconectando en ${formatTime(lastKnownPositionMs)}..."

            handler.postDelayed({
                player?.let {
                    it.seekTo(lastKnownPositionMs)
                    it.prepare()
                    it.playWhenReady = true
                }
            }, 2500)
        }
    }

    private fun applyAspectRatio() {
        val params = surfaceView.layoutParams as FrameLayout.LayoutParams
        if (isVerticalStretch || currentVideoWidth == 0 || currentVideoHeight == 0) {
            // Estira verticalmente al 100% de la pantalla sin recortar los laterales
            params.width = FrameLayout.LayoutParams.MATCH_PARENT
            params.height = FrameLayout.LayoutParams.MATCH_PARENT
            params.gravity = Gravity.CENTER
        } else {
            // Modo proporción original si el usuario desea alternar
            val screenWidth = resources.displayMetrics.widthPixels
            val screenHeight = resources.displayMetrics.heightPixels
            val videoRatio = currentVideoWidth.toFloat() / currentVideoHeight.toFloat()
            val screenRatio = screenWidth.toFloat() / screenHeight.toFloat()
            if (videoRatio > screenRatio) {
                params.width = screenWidth
                params.height = (screenWidth / videoRatio).toInt()
            } else {
                params.height = screenHeight
                params.width = (screenHeight * videoRatio).toInt()
            }
            params.gravity = Gravity.CENTER
        }
        surfaceView.layoutParams = params
    }

    private fun showTrackDialog(trackType: @C.TrackType Int, title: String) {
        val exo = player ?: return
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        if (trackType == C.TRACK_TYPE_TEXT) {
            labels.add("Desactivar subtítulos")
            actions.add {
                exo.trackSelectionParameters = exo.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
            }
        }

        val groups = exo.currentTracks.groups
        for (group in groups) {
            if (group.type == trackType) {
                for (i in 0 until group.length) {
                    if (group.isTrackSupported(i)) {
                        val format = group.getTrackFormat(i)
                        val lang = format.language?.let { Locale(it).displayLanguage } ?: ""
                        val label = format.label ?: ""
                        val codecs = format.sampleMimeType?.substringAfter("/") ?: ""
                        val selected = if (group.isTrackSelected(i)) "✓ " else ""
                        val displayName = "$selected${listOf(label, lang, codecs).filter { it.isNotEmpty() }.joinToString(" - ")}"
                            .ifBlank { "${selected}Pista ${labels.size + 1}" }

                        labels.add(displayName)
                        actions.add {
                            exo.trackSelectionParameters = exo.trackSelectionParameters
                                .buildUpon()
                                .setTrackTypeDisabled(trackType, false)
                                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                                .build()
                        }
                    }
                }
            }
        }

        if (labels.isEmpty()) {
            Toast.makeText(this, "No hay pistas disponibles", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, which ->
                actions[which].invoke()
                resetOsdTimer()
            }
            .show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val exo = player ?: return super.onKeyDown(keyCode, event)
        val isOsdVisible = osdContainer.visibility == View.VISIBLE

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (!isOsdVisible) {
                    showOsd()
                    return true
                }
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (exo.isPlaying) exo.pause() else exo.play()
                showOsd()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (!isOsdVisible) {
                    val target = (exo.currentPosition - 15_000).coerceAtLeast(0L)
                    exo.seekTo(target)
                    lastKnownPositionMs = target
                    showOsd()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (!isOsdVisible) {
                    val dur = exo.duration
                    val target = if (dur > 0) (exo.currentPosition + 15_000).coerceAtMost(dur) else exo.currentPosition + 15_000
                    exo.seekTo(target)
                    lastKnownPositionMs = target
                    showOsd()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (!isOsdVisible) {
                    showOsd()
                    return true
                }
            }
            KeyEvent.KEYCODE_BACK -> {
                if (isOsdVisible) {
                    osdContainer.visibility = View.GONE
                    return true
                }
                val now = System.currentTimeMillis()
                if (now - backPressedTime < 2000) {
                    savePosition(exo.currentPosition)
                    finish()
                } else {
                    backPressedTime = now
                    Toast.makeText(this, "Presiona ATRÁS otra vez para salir a Stremio", Toast.LENGTH_SHORT).show()
                }
                return true
            }
        }

        if (isOsdVisible) resetOsdTimer()
        return super.onKeyDown(keyCode, event)
    }

    private fun showOsd() {
        val exo = player ?: return
        val pos = exo.currentPosition
        val dur = exo.duration
        if (dur > 0) {
            tvTime.text = "${formatTime(pos)} / ${formatTime(dur)}"
            progressBar.progress = ((pos * 1000) / dur).toInt()
        }
        osdContainer.visibility = View.VISIBLE
        btnPlayPause.requestFocus()
        resetOsdTimer()
    }

    private fun resetOsdTimer() {
        handler.removeCallbacks(hideOsdRunnable)
        handler.postDelayed(hideOsdRunnable, 5000)
    }

    private fun savePosition(pos: Long) {
        if (currentStreamKey.isNotEmpty() && pos > 0) {
            getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
                .edit()
                .putLong(currentStreamKey, pos)
                .apply()
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0L)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    private fun releasePlayer() {
        handler.removeCallbacks(progressRunnable)
        player?.let {
            if (it.currentPosition > 0) savePosition(it.currentPosition)
            it.release()
        }
        player = null
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
        player?.let {
            if (it.currentPosition > 0) savePosition(it.currentPosition)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
    }
}
