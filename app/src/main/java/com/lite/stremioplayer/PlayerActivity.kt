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
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PlayerActivity : Activity() {

    private companion object {
        const val ADJUST_NONE = 0
        const val ADJUST_SIZE = 1
        const val ADJUST_BG = 2
        const val SUB_SCALE_MIN = 50
        const val SUB_SCALE_MAX = 250
        const val SUB_STEP = 10
        // Tope para no inflar la RAM si algún addon envía decenas de subtítulos
        const val MAX_EXTERNAL_SUBS = 30
        val LANG_CODE = Regex("^[A-Za-z]{2,3}([-_][A-Za-z0-9]{2,4})?$")
    }

    private data class SubSource(
        val uri: Uri,
        val label: String,
        val language: String?,
        val mime: String?,      // null = aún sin detectar
        val enabled: Boolean
    )

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
    private lateinit var btnSubSize: Button
    private lateinit var btnSubBg: Button
    private lateinit var btnReloadSubs: Button

    private val handler = Handler(Looper.getMainLooper())
    private var lastKnownPositionMs: Long = 0L
    private var currentStreamKey: String = ""
    private var backPressedTime: Long = 0L
    private var isVerticalStretch = true
    private var currentVideoWidth = 0
    private var currentVideoHeight = 0

    // Estado de subtítulos
    private var currentVideoUri: Uri? = null
    private var currentSubs: List<SubSource> = emptyList()
    private var dataSourceFactory: DataSource.Factory? = null
    private var mediaSourceFactory: DefaultMediaSourceFactory? = null
    private var subGeneration = 0
    private var adjustMode = ADJUST_NONE
    private var subScalePct = 100
    private var subBgPct = 0

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

    private val hideOsdRunnable = Runnable { hideOsd() }

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
        btnSubSize = findViewById(R.id.btnSubSize)
        btnSubBg = findViewById(R.id.btnSubBg)
        btnReloadSubs = findViewById(R.id.btnReloadSubs)

        // Ajustes de subtítulos guardados (tamaño y opacidad del fondo)
        val sp = getSharedPreferences("sub_prefs", Context.MODE_PRIVATE)
        subScalePct = sp.getInt("scale", 100).coerceIn(SUB_SCALE_MIN, SUB_SCALE_MAX)
        subBgPct = sp.getInt("bg", 0).coerceIn(0, 100)
        // Permite que el tamaño elegido también aplique a subtítulos con tamaños embebidos (VTT/ASS)
        subtitleView.setApplyEmbeddedFontSizes(false)
        applySubtitleStyle()

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

        // Tamaño / fondo: OK entra o sale del modo ajuste; en modo ajuste IZQ/DER cambian el valor
        btnSubSize.setOnClickListener {
            setAdjustMode(if (adjustMode == ADJUST_SIZE) ADJUST_NONE else ADJUST_SIZE)
            resetOsdTimer()
        }
        btnSubBg.setOnClickListener {
            setAdjustMode(if (adjustMode == ADJUST_BG) ADJUST_NONE else ADJUST_BG)
            resetOsdTimer()
        }
        btnSubSize.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && adjustMode == ADJUST_SIZE) setAdjustMode(ADJUST_NONE)
        }
        btnSubBg.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && adjustMode == ADJUST_BG) setAdjustMode(ADJUST_NONE)
        }
        btnReloadSubs.setOnClickListener {
            setAdjustMode(ADJUST_NONE)
            refreshSubtitles(force = true)
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

        // Mismo video ya en reproducción: no reiniciar, solo actualizar subtítulos si llegaron nuevos
        if (player != null && videoUri == currentVideoUri) {
            refreshSubtitles(force = false)
            return
        }

        // Clave única del video ignorando puertos efímeros
        currentStreamKey = videoUri.path ?: videoUri.toString()

        // Recuperar posición previa si se cerró o reconectó
        val intentPos = intent.getIntExtra("position", -1).toLong()
        val savedPos = getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
            .getLong(currentStreamKey, 0L)
        lastKnownPositionMs = if (intentPos > 0) intentPos else savedPos

        tvStatus.visibility = View.VISIBLE
        tvStatus.text = "Cargando..."
        val gen = ++subGeneration
        resolveSubtitles(collectSubtitleSources(intent, videoUri)) { subs ->
            if (gen == subGeneration && !isFinishing && !isDestroyed) initPlayer(videoUri, subs)
        }
    }

    // ───────────────────────── Captura de subtítulos de Stremio ─────────────────────────

    /**
     * Lee TODAS las vías conocidas: "subs" (estilo MX Player: Uri[] + subs.name / subs.filename /
     * subs.enable), "subtitles_location" (estilo VLC), cualquier otra extra cuyo nombre empiece por
     * "sub" (Uri, String, arreglos, listas o JSON) y los URIs adjuntos en ClipData.
     */
    @Suppress("DEPRECATION")
    private fun collectSubtitleSources(intent: Intent, videoUri: Uri? = intent.data): List<SubSource> {
        val result = LinkedHashMap<String, SubSource>()
        val extras = intent.extras

        fun add(uri: Uri, name: String?, fileName: String?, enabledSet: Set<String>) {
            val key = uri.toString()
            if (key in result || uri == videoUri) return
            val label = name?.takeIf { it.isNotBlank() }
                ?: fileName?.takeIf { it.isNotBlank() }
                ?: "Subtítulo Externo ${result.size + 1}"
            result[key] = SubSource(
                uri = uri,
                label = label,
                language = name?.takeIf { LANG_CODE.matches(it) },
                mime = guessMime(uri, fileName),
                enabled = key in enabledSet
            )
        }

        if (extras != null) {
            val keys = try { extras.keySet().toList() } catch (e: Exception) { emptyList() }
            fun raw(k: String): Any? = try { extras.get(k) } catch (e: Exception) { null }

            // Subtítulos que Stremio pide activar por defecto
            val enabledSet = HashSet<String>()
            for (k in keys) {
                val lk = k.lowercase(Locale.ROOT)
                if (lk.startsWith("sub") && lk.endsWith(".enable")) {
                    toUris(raw(k)).forEach { enabledSet.add(it.toString()) }
                }
            }

            for (k in keys) {
                val lk = k.lowercase(Locale.ROOT)
                if (!lk.startsWith("sub")) continue
                if (lk.endsWith(".name") || lk.endsWith(".filename") || lk.endsWith(".enable")) continue
                val uris = toUris(raw(k))
                val names = toStrings(raw("$k.name"))
                val fileNames = toStrings(raw("$k.filename"))
                uris.forEachIndexed { i, u -> add(u, names.getOrNull(i), fileNames.getOrNull(i), enabledSet) }
            }
        }

        // ClipData: algunas apps adjuntan los subtítulos aquí (con permiso de lectura)
        intent.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i).uri?.let { add(it, null, null, emptySet()) }
            }
        }

        // Los marcados como "enable" primero; límite para cuidar la RAM
        return result.values.sortedByDescending { it.enabled }.take(MAX_EXTERNAL_SUBS)
    }

    private fun isSupportedScheme(s: String): Boolean {
        val t = s.lowercase(Locale.ROOT)
        return t.startsWith("http://") || t.startsWith("https://") ||
            t.startsWith("content://") || t.startsWith("file://")
    }

    private fun toUris(value: Any?): List<Uri> = when (value) {
        null -> emptyList()
        is Uri -> listOf(value)
        is String -> parseUriString(value)
        is Array<*> -> value.flatMap { toUris(it) }
        is Collection<*> -> value.flatMap { toUris(it) }
        else -> emptyList()
    }

    private fun parseUriString(s: String): List<Uri> {
        val t = s.trim()
        if (t.startsWith("[")) {
            try {
                val arr = JSONArray(t)
                return (0 until arr.length()).flatMap { i ->
                    when (val o = arr.get(i)) {
                        is JSONObject -> parseUriString(o.optString("url", o.optString("uri", "")))
                        else -> parseUriString(o.toString())
                    }
                }
            } catch (_: Exception) {
            }
        }
        return if (isSupportedScheme(t)) listOf(Uri.parse(t)) else emptyList()
    }

    private fun toStrings(value: Any?): List<String> = when (value) {
        null -> emptyList()
        is String -> listOf(value)
        is Array<*> -> value.map { it?.toString() ?: "" }
        is Collection<*> -> value.map { it?.toString() ?: "" }
        else -> emptyList()
    }

    /** Detecta el formato por nombre de archivo, ruta o parámetros; null si no se puede saber. */
    private fun guessMime(uri: Uri, fileName: String?): String? {
        fun fromExt(text: String?): String? {
            val t = text?.lowercase(Locale.ROOT) ?: return null
            return when {
                t.endsWith(".vtt") -> MimeTypes.TEXT_VTT
                t.endsWith(".ass") || t.endsWith(".ssa") -> MimeTypes.TEXT_SSA
                t.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
                t.endsWith(".ttml") || t.endsWith(".dfxp") || t.endsWith(".xml") -> MimeTypes.APPLICATION_TTML
                else -> null
            }
        }
        fromExt(fileName)?.let { return it }
        fromExt(uri.path)?.let { return it }
        val q = (uri.query ?: "").lowercase(Locale.ROOT)
        return when {
            "format=vtt" in q || "ext=vtt" in q || "type=vtt" in q -> MimeTypes.TEXT_VTT
            "format=ass" in q || "format=ssa" in q || "ext=ass" in q -> MimeTypes.TEXT_SSA
            "format=srt" in q || "ext=srt" in q || "type=srt" in q -> MimeTypes.APPLICATION_SUBRIP
            else -> null
        }
    }

    /** Lee los primeros bytes del subtítulo para identificar el formato (solo si la URL no lo dice). */
    private fun sniffMime(uri: Uri): String {
        return try {
            val buf = ByteArray(1024)
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            val n = if (scheme == "http" || scheme == "https") {
                val c = URL(uri.toString()).openConnection() as HttpURLConnection
                try {
                    c.connectTimeout = 3000
                    c.readTimeout = 3000
                    c.instanceFollowRedirects = true
                    c.inputStream.use { it.read(buf) }
                } finally {
                    c.disconnect()
                }
            } else {
                contentResolver.openInputStream(uri)?.use { it.read(buf) } ?: -1
            }
            val head = String(buf, 0, n.coerceAtLeast(0), Charsets.UTF_8)
                .trimStart('\uFEFF', ' ', '\n', '\r', '\t')
            when {
                head.startsWith("WEBVTT") -> MimeTypes.TEXT_VTT
                head.contains("[Script Info]", true) || head.contains("[V4+ Styles]", true) -> MimeTypes.TEXT_SSA
                head.startsWith("<?xml") || head.startsWith("<tt") -> MimeTypes.APPLICATION_TTML
                else -> MimeTypes.APPLICATION_SUBRIP
            }
        } catch (e: Exception) {
            MimeTypes.APPLICATION_SUBRIP
        }
    }

    /** Completa en segundo plano (en paralelo, máx. 4 s) el formato de los subtítulos sin extensión. */
    private fun resolveSubtitles(raw: List<SubSource>, onDone: (List<SubSource>) -> Unit) {
        val unknown = raw.filter { it.mime == null }
        if (unknown.isEmpty()) {
            onDone(raw)
            return
        }
        Thread {
            val resolved = HashMap<String, String>()
            val pool = Executors.newFixedThreadPool(4)
            try {
                val tasks = unknown.map { s -> Callable { s.uri.toString() to sniffMime(s.uri) } }
                pool.invokeAll(tasks, 4, TimeUnit.SECONDS).forEach { f ->
                    if (!f.isCancelled) {
                        try {
                            val (k, m) = f.get()
                            resolved[k] = m
                        } catch (_: Exception) {
                        }
                    }
                }
            } finally {
                pool.shutdownNow()
            }
            val result = raw.map {
                if (it.mime == null) it.copy(mime = resolved[it.uri.toString()] ?: MimeTypes.APPLICATION_SUBRIP) else it
            }
            handler.post { onDone(result) }
        }.start()
    }

    // ───────────────────────── Recarga de subtítulos en caliente ─────────────────────────

    /**
     * Vuelve a leer los subtítulos del Intent y a descargarlos, conservando el tiempo actual.
     * force=true (botón): siempre recarga. force=false (Stremio reenvía el mismo video): solo si cambió algo.
     */
    private fun refreshSubtitles(force: Boolean) {
        if (player == null || currentVideoUri == null) return
        val gen = ++subGeneration
        if (force) Toast.makeText(this, "Buscando subtítulos...", Toast.LENGTH_SHORT).show()

        resolveSubtitles(collectSubtitleSources(intent, currentVideoUri)) { subs ->
            if (gen != subGeneration || isFinishing || isDestroyed) return@resolveSubtitles
            val exo = player ?: return@resolveSubtitles
            val uri = currentVideoUri ?: return@resolveSubtitles

            if (subs.isEmpty()) {
                if (force) {
                    val keys = intent.extras?.keySet()?.joinToString(", ") ?: "ninguna"
                    Toast.makeText(
                        this,
                        "Stremio no envió subtítulos para este video.\nDatos recibidos: $keys",
                        Toast.LENGTH_LONG
                    ).show()
                }
                return@resolveSubtitles
            }
            val sameSet = subs.map { it.uri.toString() }.toSet() == currentSubs.map { it.uri.toString() }.toSet()
            if (!force && sameSet) return@resolveSubtitles

            val pos = if (exo.currentPosition > 0) exo.currentPosition else lastKnownPositionMs
            val keepPlaying = exo.playWhenReady
            currentSubs = subs

            // Reactivar texto por si estaba desactivado y limpiar selección de pistas viejas
            exo.trackSelectionParameters = exo.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .build()

            exo.setMediaSource(buildMediaSource(uri, subs), pos)
            exo.prepare()
            exo.playWhenReady = keepPlaying
            lastKnownPositionMs = pos
            Toast.makeText(
                this,
                "Subtítulos recargados (${subs.size}). Continuando en ${formatTime(pos)}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun SubSource.toConfiguration(isDefault: Boolean): MediaItem.SubtitleConfiguration =
        MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(mime ?: MimeTypes.APPLICATION_SUBRIP)
            .setLanguage(language)
            .setLabel(label)
            .setSelectionFlags(if (isDefault) C.SELECTION_FLAG_DEFAULT else 0)
            .build()

    /**
     * Video + subtítulos como fuentes separadas. Un subtítulo caído (404, formato raro, timeout)
     * termina como "sin texto" en vez de provocar un error de reproducción y reconexión.
     */
    private fun buildMediaSource(videoUri: Uri, subs: List<SubSource>): MediaSource {
        val video = mediaSourceFactory!!.createMediaSource(MediaItem.fromUri(videoUri))
        if (subs.isEmpty()) return video

        val subFactory = SingleSampleMediaSource.Factory(dataSourceFactory!!)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(1))
            .setTreatLoadErrorsAsEndOfStream(true)

        val anyEnabled = subs.any { it.enabled }
        val sources = ArrayList<MediaSource>(subs.size + 1)
        sources.add(video)
        subs.forEachIndexed { i, s ->
            val isDefault = s.enabled || (!anyEnabled && i == 0)
            sources.add(subFactory.createMediaSource(s.toConfiguration(isDefault), C.TIME_UNSET))
        }
        return MergingMediaSource(*sources.toTypedArray())
    }

    // ───────────────────────── Reproductor ─────────────────────────

    private fun initPlayer(videoUri: Uri, subs: List<SubSource>) {
        releasePlayer()
        currentVideoUri = videoUri
        currentSubs = subs

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

        // DefaultDataSource: además de http(s), permite subtítulos content:// y file://
        val dsFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)
        dataSourceFactory = dsFactory

        val errorPolicy = object : DefaultLoadErrorHandlingPolicy() {
            override fun getMinimumLoadableRetryCount(dataType: Int): Int = 12
        }

        val msFactory = DefaultMediaSourceFactory(dsFactory)
            .setLoadErrorHandlingPolicy(errorPolicy)
        mediaSourceFactory = msFactory

        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)

        player = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(msFactory)
            .setLoadControl(loadControl)
            .build().apply {
                setVideoSurfaceView(surfaceView)
                addListener(playerListener)

                setMediaSource(buildMediaSource(videoUri, subs))
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
            // Mientras se ajusta tamaño/fondo se muestra el texto de ejemplo en lugar de los cues reales
            if (adjustMode != ADJUST_NONE) return
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

    // ───────────────────────── Tamaño y fondo de subtítulos ─────────────────────────

    private fun applySubtitleStyle() {
        subtitleView.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * subScalePct / 100f)
        val bg = if (subBgPct <= 0) Color.TRANSPARENT else Color.argb(subBgPct * 255 / 100, 0, 0, 0)
        // Blanco con borde negro (como antes); el fondo negro se superpone según la opacidad elegida
        subtitleView.setStyle(
            CaptionStyleCompat(
                Color.WHITE,
                bg,
                Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                Color.BLACK,
                null
            )
        )
        updateSubButtons()
    }

    private fun updateSubButtons() {
        btnSubSize.text = if (adjustMode == ADJUST_SIZE) "<  Tamaño: $subScalePct%  >" else "Tamaño sub: $subScalePct%"
        btnSubBg.text = if (adjustMode == ADJUST_BG) "<  Fondo: $subBgPct%  >" else "Fondo sub: $subBgPct%"
    }

    private fun setAdjustMode(mode: Int) {
        if (adjustMode == mode) return
        adjustMode = mode
        updateSubButtons()
        if (mode != ADJUST_NONE) {
            showSubtitlePreview()
        } else {
            // Volver a los subtítulos reales
            subtitleView.setCues(player?.currentCues?.cues ?: emptyList())
        }
    }

    private fun adjustSubtitle(direction: Int) {
        when (adjustMode) {
            ADJUST_SIZE -> subScalePct = (subScalePct + direction * SUB_STEP).coerceIn(SUB_SCALE_MIN, SUB_SCALE_MAX)
            ADJUST_BG -> subBgPct = (subBgPct + direction * SUB_STEP).coerceIn(0, 100)
            else -> return
        }
        getSharedPreferences("sub_prefs", Context.MODE_PRIVATE)
            .edit()
            .putInt("scale", subScalePct)
            .putInt("bg", subBgPct)
            .apply()
        applySubtitleStyle()
        resetOsdTimer()
    }

    /** Texto de ejemplo a media altura para verlo por encima del panel OSD mientras se ajusta. */
    private fun showSubtitlePreview() {
        val cue = Cue.Builder()
            .setText("Así se verán los subtítulos\nSegunda línea de ejemplo")
            .setLine(0.35f, Cue.LINE_TYPE_FRACTION)
            .setLineAnchor(Cue.ANCHOR_TYPE_START)
            .setPosition(0.5f)
            .setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE)
            .build()
        subtitleView.setCues(listOf(cue))
    }

    // ───────────────────────── Diálogo de pistas ─────────────────────────

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
                        val displayName = "$selected${listOf(label, lang, codecs).filter { it.isNotEmpty() }.distinct().joinToString(" - ")}"
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

    // ───────────────────────── Control remoto ─────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val exo = player ?: return super.onKeyDown(keyCode, event)
        val isOsdVisible = osdContainer.visibility == View.VISIBLE

        // Modo ajuste de subtítulos: IZQ/DER cambian el valor; ARRIBA/ABAJO/ATRÁS lo cierran
        if (isOsdVisible && adjustMode != ADJUST_NONE) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    adjustSubtitle(-1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    adjustSubtitle(+1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> setAdjustMode(ADJUST_NONE)
                KeyEvent.KEYCODE_BACK -> {
                    setAdjustMode(ADJUST_NONE)
                    return true
                }
            }
        }

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
                    hideOsd()
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

    private fun hideOsd() {
        setAdjustMode(ADJUST_NONE)
        osdContainer.visibility = View.GONE
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
        subGeneration++ // invalida cualquier detección de subtítulos pendiente
        releasePlayer()
    }
}
