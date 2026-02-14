package com.milton.justguide

import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.media.*
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.maps.*
import com.google.android.gms.maps.model.*
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.milton.justguide.databinding.ActivityEvidenceBinding
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*

class EvidenceActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityEvidenceBinding
    private var googleMap: GoogleMap? = null
    private var tripLogs = mutableListOf<LocationLog>()
    private val handler = Handler(Looper.getMainLooper())
    private var currentMarker: Marker? = null
    private var tripPolyline: Polyline? = null
    private var isPlaying = true
    private var videoPath = ""
    private var jsonPath = ""
    private var assinaturaOriginal = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEvidenceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        videoPath = intent.getStringExtra("VIDEO_PATH") ?: ""

        val mapFragment = supportFragmentManager.findFragmentById(R.id.mapEvidence) as SupportMapFragment
        mapFragment.getMapAsync(this)

        val jsonLoaded = carregarLogPericial(videoPath)
        if (!jsonLoaded) {
            Toast.makeText(this, "⚠️ Log de telemetria não encontrado.", Toast.LENGTH_LONG).show()
        }

        binding.tvEvidenceLogCount.text = "${tripLogs.size} pts"

        setupVideoControls()
        setupActionButtons()

        if (videoPath.isNotEmpty()) {
            binding.videoViewEvidence.setVideoPath(videoPath)
            binding.videoViewEvidence.setOnPreparedListener { mp ->
                mp.start()
                isPlaying = true
                binding.seekBarVideo.max = binding.videoViewEvidence.duration
                sincronizarTelemetria()
                atualizarSeekBar()
            }
            binding.videoViewEvidence.setOnCompletionListener {
                isPlaying = false
                binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
            }
            binding.videoViewEvidence.setOnErrorListener { _, _, _ ->
                Toast.makeText(this, "Erro ao reproduzir vídeo.", Toast.LENGTH_SHORT).show()
                true
            }
        }
    }

    // ══════════════════════════════════════════
    // BOTÕES DE AÇÃO
    // ══════════════════════════════════════════

    private fun setupActionButtons() {
        binding.btnVerifyIntegrity.setOnClickListener { verificarIntegridade() }
        binding.btnCutEvidence.setOnClickListener { mostrarDialogoCorte() }
        binding.btnGeneratePdf.setOnClickListener {
            if (tripLogs.isEmpty()) {
                Toast.makeText(this, "⚠️ Sem dados de telemetria", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val duration = try { binding.videoViewEvidence.duration / 1000 } catch (e: Exception) { 0 }
            val pdfFile = gerarLaudoPericial(0, duration, File(videoPath))
            if (pdfFile != null) {
                Toast.makeText(this, "✅ Laudo PDF salvo em Downloads!", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "❌ Erro ao gerar laudo", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ══════════════════════════════════════════
    // VERIFICAÇÃO DE INTEGRIDADE (SHA-256)
    // ══════════════════════════════════════════

    private fun verificarIntegridade() {
        if (videoPath.isEmpty()) {
            Toast.makeText(this, "⚠️ Nenhum vídeo carregado", Toast.LENGTH_SHORT).show()
            return
        }
        if (assinaturaOriginal.isEmpty() || assinaturaOriginal == "vazio" || assinaturaOriginal == "sessao_multiplos_videos") {
            Toast.makeText(this, "⚠️ Assinatura de segurança não disponível no log", Toast.LENGTH_LONG).show()
            return
        }

        Toast.makeText(this, "🔍 Verificando integridade...", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                val videoFile = File(videoPath)
                if (!videoFile.exists()) {
                    runOnUiThread { Toast.makeText(this, "❌ Arquivo de vídeo não encontrado", Toast.LENGTH_SHORT).show() }
                    return@Thread
                }
                val hashAtual = gerarAssinaturaArquivo(videoFile)
                val integro = hashAtual == assinaturaOriginal

                runOnUiThread {
                    if (integro) {
                        AlertDialog.Builder(this)
                            .setTitle("✅ VÍDEO ÍNTEGRO")
                            .setMessage("O vídeo NÃO foi adulterado.\n\nSHA-256 original:\n${assinaturaOriginal.take(32)}...\n\nSHA-256 atual:\n${hashAtual.take(32)}...\n\nAs assinaturas são idênticas.")
                            .setPositiveButton("OK", null).show()
                    } else {
                        AlertDialog.Builder(this)
                            .setTitle("⚠️ VÍDEO ADULTERADO")
                            .setMessage("ATENÇÃO: O vídeo foi modificado!\n\nSHA-256 original:\n${assinaturaOriginal.take(32)}...\n\nSHA-256 atual:\n${hashAtual.take(32)}...\n\nAs assinaturas NÃO coincidem.")
                            .setPositiveButton("OK", null).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "❌ Erro: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun gerarAssinaturaArquivo(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ══════════════════════════════════════════
    // CORTE DE PROVA (da Sala de Perícia)
    // ══════════════════════════════════════════

    private fun mostrarDialogoCorte() {
        if (videoPath.isEmpty()) {
            Toast.makeText(this, "⚠️ Nenhum vídeo carregado", Toast.LENGTH_SHORT).show()
            return
        }
        val currentSec = try { binding.videoViewEvidence.currentPosition / 1000 } catch (e: Exception) { 0 }

        AlertDialog.Builder(this)
            .setTitle("Cortar Prova Pericial")
            .setMessage("Cortar 40s (20s antes + 20s depois).\n\nPosição atual: ${formatTime(currentSec)}")
            .setPositiveButton("Usar posição atual") { _, _ -> executarCorte(currentSec) }
            .setNeutralButton("Digitar minuto") { _, _ ->
                val input = EditText(this)
                input.inputType = android.text.InputType.TYPE_CLASS_NUMBER
                input.hint = "Ex: 3 (para o minuto 3)"
                AlertDialog.Builder(this)
                    .setTitle("Minuto do incidente")
                    .setView(input)
                    .setPositiveButton("CORTAR") { _, _ ->
                        val minuto = input.text.toString().toIntOrNull() ?: 0
                        executarCorte(minuto * 60)
                    }.show()
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun executarCorte(incidentSecond: Int) {
        Toast.makeText(this, "⚙️ Cortando prova + gerando laudo...", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                val outDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "JustGuide/Provas")
                if (!outDir.exists()) outDir.mkdirs()
                val outFile = File(outDir, "PROVA_${System.currentTimeMillis()}.mp4")

                val startSec = if (incidentSecond > 20) incidentSecond - 20 else 0
                val endSec = incidentSecond + 20
                val startUs = startSec * 1000000L

                var extractor: MediaExtractor? = null; var muxer: MediaMuxer? = null
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(videoPath)
                    val rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0
                    extractor = MediaExtractor().apply { setDataSource(videoPath) }
                    muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4); muxer.setOrientationHint(rot)
                    val trackMap = HashMap<Int, Int>()
                    for (i in 0 until extractor.trackCount) {
                        val fmt = extractor.getTrackFormat(i); val mime = fmt.getString(MediaFormat.KEY_MIME)
                        if (mime?.startsWith("video/") == true || mime?.startsWith("audio/") == true) { trackMap[i] = muxer.addTrack(fmt); extractor.selectTrack(i) }
                    }
                    muxer.start(); val buf = ByteBuffer.allocate(1024 * 1024); val info = MediaCodec.BufferInfo()
                    extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC); var offsetUs: Long = -1
                    while (true) {
                        info.size = extractor.readSampleData(buf, 0); if (info.size < 0 || extractor.sampleTime > (startUs + 40000000L)) break
                        if (offsetUs == -1L) offsetUs = extractor.sampleTime; info.presentationTimeUs = extractor.sampleTime - offsetUs
                        info.flags = if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                        muxer.writeSampleData(trackMap[extractor.sampleTrackIndex]!!, buf, info); extractor.advance()
                    }
                    muxer.stop(); MediaScannerConnection.scanFile(this, arrayOf(outFile.absolutePath), null, null)
                } finally { extractor?.release(); muxer?.release(); retriever.release() }

                val pdfFile = gerarLaudoPericial(startSec, endSec, File(videoPath))
                runOnUiThread {
                    if (pdfFile != null) Toast.makeText(this, "✅ Prova + Laudo PDF salvos em Downloads!", Toast.LENGTH_LONG).show()
                    else Toast.makeText(this, "✅ Prova salva! (sem telemetria para laudo)", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread { Toast.makeText(this, "❌ Erro ao cortar: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    // ══════════════════════════════════════════
    // CONTROLES DE VÍDEO
    // ══════════════════════════════════════════

    private fun setupVideoControls() {
        binding.btnPlayPause.setOnClickListener {
            if (isPlaying) { binding.videoViewEvidence.pause(); binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_play) }
            else { binding.videoViewEvidence.start(); binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_pause) }
            isPlaying = !isPlaying
        }
        binding.btnRewind.setOnClickListener { binding.videoViewEvidence.seekTo((binding.videoViewEvidence.currentPosition - 10000).coerceAtLeast(0)) }
        binding.btnForward.setOnClickListener { binding.videoViewEvidence.seekTo((binding.videoViewEvidence.currentPosition + 10000).coerceAtMost(binding.videoViewEvidence.duration)) }
        binding.seekBarVideo.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser) binding.videoViewEvidence.seekTo(progress) }
            override fun onStartTrackingTouch(sb: SeekBar?) {}; override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun atualizarSeekBar() {
        handler.post(object : Runnable {
            override fun run() {
                try {
                    val current = binding.videoViewEvidence.currentPosition; val total = binding.videoViewEvidence.duration
                    binding.seekBarVideo.progress = current
                    binding.tvVideoTime.text = String.format("%02d:%02d/%02d:%02d", (current/1000)/60, (current/1000)%60, (total/1000)/60, (total/1000)%60)
                } catch (e: Exception) {}
                handler.postDelayed(this, 500)
            }
        })
    }

    // ══════════════════════════════════════════
    // CARREGAMENTO DO LOG PERICIAL
    // ══════════════════════════════════════════

    private fun carregarLogPericial(videoPath: String): Boolean {
        val possiblePaths = buildPossibleJsonPaths(videoPath)
        for (path in possiblePaths) {
            try {
                val file = File(path); if (!file.exists()) continue
                val json = file.readText(); if (json.isBlank()) continue
                if (tryParseWrappedFormat(json)) {
                    jsonPath = path
                    try {
                        val wrapper = Gson().fromJson(json, JsonObject::class.java)
                        if (wrapper.has("assinatura_seguranca")) assinaturaOriginal = wrapper.get("assinatura_seguranca").asString
                    } catch (_: Exception) {}
                    return true
                }
                if (tryParseDirectList(json)) { jsonPath = path; return true }
            } catch (e: Exception) { android.util.Log.e("Evidence", "Erro: $path", e) }
        }
        return false
    }

    private fun tryParseWrappedFormat(json: String): Boolean {
        return try {
            val wrapper = Gson().fromJson(json, JsonObject::class.java)
            if (wrapper.has("dados_telemetria")) {
                val arr = wrapper.getAsJsonArray("dados_telemetria")
                val type = object : TypeToken<List<LocationLog>>() {}.type
                val logs: List<LocationLog> = Gson().fromJson(arr, type)
                if (logs.isNotEmpty()) { tripLogs = logs.toMutableList(); true } else false
            } else false
        } catch (e: Exception) { false }
    }

    private fun tryParseDirectList(json: String): Boolean {
        return try {
            val type = object : TypeToken<List<LocationLog>>() {}.type
            val logs: List<LocationLog> = Gson().fromJson(json, type)
            if (logs.isNotEmpty()) { tripLogs = logs.toMutableList(); true } else false
        } catch (e: Exception) { false }
    }

    private fun buildPossibleJsonPaths(videoPath: String): List<String> {
        val paths = mutableListOf<String>()
        if (videoPath.isEmpty()) return paths
        val videoFile = File(videoPath); val videoName = videoFile.nameWithoutExtension
        paths.add(videoPath.replace(".mp4", ".json").replace("Movies/JustGuide", "Documents/JustGuide/Logs"))
        val logsDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "JustGuide/Logs")
        paths.add(File(logsDir, "$videoName.json").absolutePath)
        paths.add(File(videoFile.parentFile, "$videoName.json").absolutePath)
        val sessionMatch = Regex("JustGuide_(\\d+)").find(videoName)
        if (sessionMatch != null) paths.add(File(logsDir, "JustGuide_${sessionMatch.groupValues[1]}.json").absolutePath)
        if (logsDir.exists()) {
            logsDir.listFiles { f -> f.extension == "json" }?.forEach { jsonFile ->
                if (jsonFile.nameWithoutExtension.contains(videoName.takeLast(13)) || videoName.contains(jsonFile.nameWithoutExtension.takeLast(13)))
                    paths.add(jsonFile.absolutePath)
            }
        }
        return paths.distinct()
    }

    // ══════════════════════════════════════════
    // SINCRONIZAÇÃO DE TELEMETRIA
    // ══════════════════════════════════════════

    private fun sincronizarTelemetria() {
        if (tripLogs.isEmpty()) return
        val startTimestamp = tripLogs.first().timestamp
        handler.post(object : Runnable {
            override fun run() {
                if (!isPlaying) { handler.postDelayed(this, 500); return }
                try {
                    val segundoAtual = binding.videoViewEvidence.currentPosition / 1000
                    val logEntry = findClosestLog(segundoAtual, startTimestamp)
                    if (logEntry != null) {
                        runOnUiThread {
                            binding.tvEvidenceSpeed.text = "${logEntry.speed.toInt()}"
                            binding.tvEvidenceAddress.text = if (logEntry.address.isNotBlank()) logEntry.address else "Calculando..."
                            binding.tvEvidenceDate.text = SimpleDateFormat("dd/MM/yy HH:mm:ss", Locale.getDefault()).format(Date(logEntry.timestamp))
                            binding.tvEvidenceCoords.text = String.format("%.4f, %.4f", logEntry.latitude, logEntry.longitude)
                            val pos = LatLng(logEntry.latitude, logEntry.longitude)
                            googleMap?.let { map ->
                                map.animateCamera(CameraUpdateFactory.newLatLngZoom(pos, 17f))
                                currentMarker?.remove()
                                currentMarker = map.addMarker(MarkerOptions().position(pos).icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_CYAN)).title("${logEntry.speed.toInt()} km/h"))
                            }
                        }
                    }
                } catch (e: Exception) {}
                handler.postDelayed(this, 1000)
            }
        })
    }

    private fun findClosestLog(segundoVideo: Int, startTimestamp: Long): LocationLog? {
        if (tripLogs.isEmpty()) return null
        val targetTimestamp = startTimestamp + (segundoVideo * 1000L)
        var closest: LocationLog? = null; var minDiff = Long.MAX_VALUE
        for (log in tripLogs) { val diff = kotlin.math.abs(log.timestamp - targetTimestamp); if (diff < minDiff) { minDiff = diff; closest = log } }
        if (closest != null && minDiff < 10000) return closest
        return if (segundoVideo < tripLogs.size) tripLogs[segundoVideo] else tripLogs.lastOrNull()
    }

    // ══════════════════════════════════════════
    // MAPA
    // ══════════════════════════════════════════

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        try { googleMap?.setMapStyle(MapStyleOptions.loadRawResourceStyle(this, R.raw.map_dark)) } catch (e: Exception) {}
        desenharRotaCompleta()
    }

    private fun desenharRotaCompleta() {
        if (tripLogs.isEmpty() || googleMap == null) return
        val points = tripLogs.map { LatLng(it.latitude, it.longitude) }
        tripPolyline?.remove()
        tripPolyline = googleMap?.addPolyline(PolylineOptions().addAll(points).width(10f).color(0xFF00FF00.toInt()).geodesic(true))
        if (points.size >= 2) {
            val bounds = LatLngBounds.Builder(); points.forEach { bounds.include(it) }
            try { googleMap?.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 80)) } catch (e: Exception) {}
        }
    }

    // ══════════════════════════════════════════
    // GERAÇÃO DE PDF — LAUDO PERICIAL
    // ══════════════════════════════════════════

    fun gerarLaudoPericial(startSec: Int, endSec: Int, videoFile: File): File? {
        if (tripLogs.isEmpty()) return null
        val startTimestamp = tripLogs.first().timestamp
        val trechoLogs = tripLogs.filter { log -> val logSec = ((log.timestamp - startTimestamp) / 1000).toInt(); logSec in startSec..endSec }
        if (trechoLogs.isEmpty()) return null

        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
        val sdfFile = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

        val document = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
        val page = document.startPage(pageInfo); val canvas = page.canvas

        val titlePaint = Paint().apply { color = Color.parseColor("#00CCAA"); textSize = 22f; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); isAntiAlias = true }
        val headerPaint = Paint().apply { color = Color.parseColor("#FFFFFF"); textSize = 14f; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); isAntiAlias = true }
        val textPaint = Paint().apply { color = Color.parseColor("#CCCCCC"); textSize = 11f; typeface = Typeface.MONOSPACE; isAntiAlias = true }
        val labelPaint = Paint().apply { color = Color.parseColor("#668888"); textSize = 10f; typeface = Typeface.MONOSPACE; isAntiAlias = true }
        val linePaint = Paint().apply { color = Color.parseColor("#1A3A4A"); strokeWidth = 1f }

        canvas.drawColor(Color.parseColor("#050A10"))
        var y = 40f

        canvas.drawText("LAUDO PERICIAL DE TELEMETRIA", 30f, y, titlePaint); y += 20f
        val sub = Paint().apply { color = Color.parseColor("#336666"); textSize = 9f; typeface = Typeface.MONOSPACE; isAntiAlias = true }
        canvas.drawText("JustGuide — Tacógrafo Digital com Prova Jurídica", 30f, y, sub); y += 16f
        canvas.drawLine(30f, y, 565f, y, linePaint); y += 22f

        canvas.drawText("DADOS DO REGISTRO", 30f, y, headerPaint); y += 20f
        canvas.drawText("Arquivo: ${videoFile.name}", 30f, y, textPaint); y += 16f
        canvas.drawText("Trecho: ${formatTime(startSec)} a ${formatTime(endSec)} (${endSec - startSec}s)", 30f, y, textPaint); y += 16f
        canvas.drawText("Registros: ${trechoLogs.size} pontos", 30f, y, textPaint); y += 16f
        canvas.drawText("Gerado em: ${sdf.format(Date())}", 30f, y, textPaint); y += 16f

        if (assinaturaOriginal.isNotEmpty() && assinaturaOriginal != "vazio" && assinaturaOriginal != "sessao_multiplos_videos") {
            canvas.drawText("SHA-256: ${assinaturaOriginal.take(40)}...", 30f, y, labelPaint); y += 12f
            canvas.drawText("...${assinaturaOriginal.drop(40)}", 30f, y, labelPaint); y += 18f
        } else y += 18f

        canvas.drawLine(30f, y, 565f, y, linePaint); y += 20f
        canvas.drawText("RESUMO DO TRECHO", 30f, y, headerPaint); y += 20f
        val firstLog = trechoLogs.first(); val lastLog = trechoLogs.last()
        val maxSpeed = trechoLogs.maxOf { it.speed }; val avgSpeed = trechoLogs.map { it.speed }.average()
        canvas.drawText("Início: ${sdf.format(Date(firstLog.timestamp))}", 30f, y, textPaint); y += 16f
        canvas.drawText("Fim: ${sdf.format(Date(lastLog.timestamp))}", 30f, y, textPaint); y += 16f
        canvas.drawText("Vel. máx: ${maxSpeed.toInt()} km/h | Vel. méd: ${avgSpeed.toInt()} km/h", 30f, y, textPaint); y += 16f
        canvas.drawText("GPS início: ${String.format("%.6f, %.6f", firstLog.latitude, firstLog.longitude)}", 30f, y, textPaint); y += 16f
        canvas.drawText("GPS fim: ${String.format("%.6f, %.6f", lastLog.latitude, lastLog.longitude)}", 30f, y, textPaint); y += 16f
        canvas.drawText("End. início: ${firstLog.address.ifBlank { "N/D" }}", 30f, y, textPaint); y += 16f
        canvas.drawText("End. fim: ${lastLog.address.ifBlank { "N/D" }}", 30f, y, textPaint); y += 22f
        canvas.drawLine(30f, y, 565f, y, linePaint); y += 20f

        canvas.drawText("REGISTRO DETALHADO", 30f, y, headerPaint); y += 20f
        canvas.drawText("HORA", 30f, y, labelPaint); canvas.drawText("VEL", 130f, y, labelPaint)
        canvas.drawText("LATITUDE", 190f, y, labelPaint); canvas.drawText("LONGITUDE", 310f, y, labelPaint)
        canvas.drawText("ENDEREÇO", 430f, y, labelPaint); y += 14f
        canvas.drawLine(30f, y, 565f, y, linePaint); y += 10f

        val maxRows = ((780 - y) / 14).toInt()
        val step = if (trechoLogs.size > maxRows) trechoLogs.size / maxRows else 1
        var idx = 0
        while (idx < trechoLogs.size && y < 790) {
            val log = trechoLogs[idx]
            val hora = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(log.timestamp))
            val addr = if (log.address.length > 20) log.address.take(20) + "…" else log.address.ifBlank { "-" }
            canvas.drawText(hora, 30f, y, textPaint); canvas.drawText("${log.speed.toInt()}", 130f, y, textPaint)
            canvas.drawText(String.format("%.5f", log.latitude), 190f, y, textPaint)
            canvas.drawText(String.format("%.5f", log.longitude), 290f, y, textPaint) // Corrigido: 290 para alinhar
            canvas.drawText(addr, 430f, y, textPaint); y += 14f; idx += step
        }

        y = 820f; canvas.drawLine(30f, y, 565f, y, linePaint); y += 14f
        val foot = Paint().apply { color = Color.parseColor("#336666"); textSize = 8f; typeface = Typeface.MONOSPACE; isAntiAlias = true }
        canvas.drawText("JustGuide — Tacógrafo Digital com Prova Jurídica", 30f, y, foot)

        document.finishPage(page)

        val outDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "JustGuide/Laudos")
        if (!outDir.exists()) outDir.mkdirs()
        val outFile = File(outDir, "LAUDO_${sdfFile.format(Date())}.pdf")

        return try {
            FileOutputStream(outFile).use { document.writeTo(it) }; document.close()
            MediaScannerConnection.scanFile(this, arrayOf(outFile.absolutePath), null, null); outFile
        } catch (e: Exception) { e.printStackTrace(); document.close(); null }
    }

    private fun formatTime(seconds: Int): String = String.format("%02d:%02d", seconds / 60, seconds % 60)

    override fun onDestroy() { super.onDestroy(); handler.removeCallbacksAndMessages(null) }
}
