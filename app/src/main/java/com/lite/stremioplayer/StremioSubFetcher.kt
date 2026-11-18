package com.lite.stremioplayer

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.concurrent.thread

object StremioSubFetcher {

    // Lista de Addons de subtítulos compatibles con protocolo Stremio.
    // Puedes agregar aquí el URL base de tu addon de Subscene/SubDL si usas uno específico.
    private val SUBTITLE_ADDONS = listOf(
        "https://opensubtitles-v3.strem.io",
        "https://opensubtitles.strem.io"
    )

    data class VideoQueryInfo(
        val rawTitle: String,
        val cleanTitle: String,
        val year: String?,
        val season: Int?,
        val episode: Int?,
        val imdbId: String?,
        val videoHash: String?,
        val videoSize: Long?
    )

    fun fetchSubtitlesAsync(
        intent: Intent,
        videoUri: Uri,
        onResult: (List<MediaItem.SubtitleConfiguration>, String) -> Unit
    ) {
        val mainHandler = Handler(Looper.getMainLooper())

        thread(start = true, name = "StremioSubThread") {
            try {
                // 1. Extraer toda pista posible del Intent y del servidor local 127.0.0.1:11470
                val info = extractVideoInfo(intent, videoUri)

                // 2. Obtener IMDb ID (si no vino en el Intent, lo buscamos en Cinemeta de Stremio)
                val isSeries = info.season != null && info.episode != null
                val type = if (isSeries) "series" else "movie"
                val imdbId = info.imdbId ?: searchImdbIdInCinemeta(info.cleanTitle, info.year, type)

                if (imdbId == null) {
                    mainHandler.post {
                        onResult(emptyList(), "No se pudo identificar el título (${info.cleanTitle})")
                    }
                    return@thread
                }

                // 3. Construir el identificador para los addons de Stremio (ej. tt1234567 o tt1234567:1:2)
                val stremioId = if (isSeries) "$imdbId:${info.season}:${info.episode}" else imdbId

                // 4. Consultar los Addons de subtítulos de Stremio
                val foundSubs = mutableListOf<MediaItem.SubtitleConfiguration>()
                val seenUrls = HashSet<String>()

                for (addonBase in SUBTITLE_ADDONS) {
                    val addonUrl = "$addonBase/subtitles/$type/$stremioId.json"
                    val jsonStr = httpGet(addonUrl, 8000) ?: continue
                    val root = JSONObject(jsonStr)
                    val subsArray = root.optJSONArray("subtitles") ?: continue

                    var esCount = 0
                    var enCount = 0

                    for (i in 0 until subsArray.length()) {
                        val subObj = subsArray.getJSONObject(i)
                        val url = subObj.optString("url", "")
                        val lang = subObj.optString("lang", "").lowercase(Locale.ROOT)

                        if (url.isEmpty() || seenUrls.contains(url)) continue

                        // Filtramos Español e Inglés para mantener el consumo de RAM al mínimo
                        val isSpanish = lang == "spa" || lang == "es" || lang.contains("span") || lang == "spl" || lang == "lat"
                        val isEnglish = lang == "eng" || lang == "en" || lang.contains("engl")

                        if (!isSpanish && !isEnglish) continue

                        // Máximo 12 opciones en español y 6 en inglés para no inflar la memoria
                        if (isSpanish && esCount >= 12) continue
                        if (isEnglish && enCount >= 6) continue

                        if (isSpanish) esCount++ else enCount++
                        seenUrls.add(url)

                        val langLabel = if (isSpanish) {
                            "Español #${esCount}"
                        } else {
                            "Inglés #${enCount}"
                        }

                        val mime = if (url.lowercase(Locale.ROOT).endsWith(".vtt")) {
                            MimeTypes.TEXT_VTT
                        } else {
                            MimeTypes.APPLICATION_SUBRIP
                        }

                        val config = MediaItem.SubtitleConfiguration.Builder(Uri.parse(url))
                            .setMimeType(mime)
                            .setLanguage(if (isSpanish) "es" else "en")
                            .setLabel("[Addon] $langLabel")
                            .setSelectionFlags(if (isSpanish && esCount == 1) C.SELECTION_FLAG_DEFAULT else 0)
                            .build()

                        foundSubs.add(config)
                    }

                    if (foundSubs.isNotEmpty()) break
                }

                mainHandler.post {
                    if (foundSubs.isEmpty()) {
                        onResult(emptyList(), "No se hallaron subtítulos ES/EN para $stremioId")
                    } else {
                        onResult(foundSubs, "Cargados ${foundSubs.size} subtítulos externos ($stremioId)")
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    onResult(emptyList(), "Error buscando subtítulos: ${e.message}")
                }
            }
        }
    }

    private fun extractVideoInfo(intent: Intent, videoUri: Uri): VideoQueryInfo {
        var foundImdb: String? = null
        var rawCandidate = ""

        // 1. Escanear todos los extras que Stremio haya enviado
        val extras = intent.extras
        if (extras != null) {
            for (key in extras.keySet()) {
                val value = extras.get(key)?.toString() ?: continue
                // Detectar si algún extra contiene un IMDb ID (tt1234567)
                val imdbMatch = Regex("(tt\\d{7,9})").find(value)
                if (imdbMatch != null && foundImdb == null) {
                    foundImdb = imdbMatch.value
                }
                if (key.lowercase(Locale.ROOT) in listOf("title", "name", "filename", " android.intent.extra.title")) {
                    if (value.length > rawCandidate.length) rawCandidate = value
                }
            }
        }

        // 2. Si el servidor local de Stremio (127.0.0.1:11470) está sirviendo el torrent,
        // consultamos sus cabeceras o su endpoint /stats.json para obtener el nombre real del archivo MKV/MP4
        val uriStr = videoUri.toString()
        if (rawCandidate.isEmpty() && (uriStr.contains(":11470") || uriStr.startsWith("http"))) {
            rawCandidate = fetchFilenameFromStremioServer(uriStr) ?: ""
        }

        if (rawCandidate.isEmpty()) {
            rawCandidate = videoUri.lastPathSegment ?: "Video"
        }

        return parseFilename(rawCandidate, foundImdb)
    }

    private fun fetchFilenameFromStremioServer(streamUrl: String): String? {
        // Intento A: Consultar stats.json del motor de Stremio (ej. http://127.0.0.1:11470/<hash>/<idx>/stats.json)
        try {
            val baseStream = streamUrl.substringBefore("?")
            val statsUrl = if (baseStream.endsWith("/")) "${baseStream}stats.json" else "$baseStream/stats.json"
            val statsJson = httpGet(statsUrl, 2500)
            if (!statsJson.isNullOrEmpty()) {
                val obj = JSONObject(statsJson)
                val streamName = obj.optString("streamName", "")
                if (streamName.isNotEmpty()) return streamName
                val files = obj.optJSONArray("files")
                if (files != null && files.length() > 0) {
                    val firstFile = files.optJSONObject(0)?.optString("name", "") ?: ""
                    if (firstFile.isNotEmpty()) return firstFile
                }
            }
        } catch (_: Exception) {}

        // Intento B: Leer cabecera Content-Disposition del propio enlace de video
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(streamUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Range", "bytes=0-0")
                connectTimeout = 2500
                readTimeout = 2500
            }
            val disposition = conn.getHeaderField("Content-Disposition")
            if (!disposition.isNullOrEmpty()) {
                val match = Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?").find(disposition)
                if (match != null) {
                    return Uri.decode(match.groupValues[1])
                }
            }
        } catch (_: Exception) {
        } finally {
            conn?.disconnect()
        }
        return null
    }

    private fun parseFilename(rawName: String, imdbId: String?): VideoQueryInfo {
        val decoded = Uri.decode(rawName).substringAfterLast("/")
        var working = decoded
            .replace(Regex("\\.(mkv|mp4|avi|mov|webm)$", RegexOption.IGNORE_CASE), "")
            .replace('.', ' ')
            .replace('_', ' ')

        // Detectar patrón de serie: S01E02 o 1x02
        var season: Int? = null
        var episode: Int? = null
        val seMatch = Regex("(?i)\\bS(\\d{1,2})E(\\d{1,3})\\b").find(working)
            ?: Regex("(?i)\\b(\\d{1,2})x(\\d{1,3})\\b").find(working)

        if (seMatch != null) {
            season = seMatch.groupValues[1].toIntOrNull()
            episode = seMatch.groupValues[2].toIntOrNull()
            working = working.substring(0, seMatch.range.first)
        }

        // Detectar año (ej. 2024)
        var year: String? = null
        val yearMatch = Regex("\\b(19\\d{2}|20\\d{2})\\b").find(working)
        if (yearMatch != null) {
            year = yearMatch.value
            working = working.substring(0, yearMatch.range.first)
        }

        // Limpiar tags de calidad comunes si no había año ni S01E01 antes
        working = working.replace(
            Regex("(?i)\\b(2160p|1080p|720p|480p|4k|uhd|bluray|web-dl|webrip|hdrip|x264|x265|hevc|10bit|hdr|dv).*"),
            ""
        ).trim()

        return VideoQueryInfo(
            rawTitle = decoded,
            cleanTitle = working.ifEmpty { decoded },
            year = year,
            season = season,
            episode = episode,
            imdbId = imdbId,
            videoHash = null,
            videoSize = null
        )
    }

    private fun searchImdbIdInCinemeta(cleanTitle: String, year: String?, type: String): String? {
        val encoded = URLEncoder.encode(cleanTitle, "UTF-8")
        val url = "https://v3-cinemeta.strem.io/catalog/$type/top/search=$encoded.json"
        val jsonStr = httpGet(url, 6000) ?: return null

        val metas = JSONObject(jsonStr).optJSONArray("metas") ?: return null
        if (metas.length() == 0) return null

        // Si tenemos el año del archivo, priorizamos el resultado que coincida con ese año
        if (year != null) {
            for (i in 0 until metas.length()) {
                val meta = metas.getJSONObject(i)
                val releaseInfo = meta.optString("releaseInfo", "")
                if (releaseInfo.startsWith(year)) {
                    return meta.optString("imdb_id").ifEmpty { meta.optString("id") }
                }
            }
        }

        val first = metas.getJSONObject(0)
        return first.optString("imdb_id").ifEmpty { first.optString("id") }
    }

    private fun httpGet(urlStr: String, timeoutMs: Int): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("User-Agent", "StremioLitePlayer/1.0")
            }
            if (conn.responseCode in 200..299) {
                BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            } else null
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
