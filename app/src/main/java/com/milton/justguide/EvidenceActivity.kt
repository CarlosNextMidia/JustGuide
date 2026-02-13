package com.milton.justguide

import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.media.MediaScannerConnection
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEvidenceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        videoPath = intent.getStringExtra("VIDEO_PATH") ?: ""

        // Inicializa o Mapa
        val mapFragment = supportFragmentManager.findFragmentById(R.id.mapEvidence) as SupportMapFragment
        mapFragment.getMapAsync(this)

        // Carrega os dados periciais
        val jsonLoaded = carregarLogPericial(videoPath)
        if (!jsonLoaded) {
            Toast.makeText(this, "⚠️ Log de telemetria não encontrado.", Toast.LENGTH_LONG).show()
        }

        // Atualiza contagem
        binding.tvEvidenceLogCount.text = "${tripLogs.size} pts"

        // Configura controles de vídeo
        setupVideoControls()

        // Configura o Player
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
    // CONTROLES DE VÍDEO
    // ══════════════════════════════════════════

    private fun setupVideoControls() {
        // Play/Pause
        binding.btnPlayPause.setOnClickListener {
            if (isPlaying) {
                binding.videoViewEvidence.pause()
                binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
            } else {
                binding.videoViewEvidence.start()
                binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
            }
            isPlaying = !isPlaying
        }

        // Retroceder 10s
        binding.btnRewind.setOnClickListener {
            val newPos = (binding.videoViewEvidence.currentPosition - 10000).coerceAtLeast(0)
            binding.videoViewEvidence.seekTo(newPos)
        }

        // Avançar 10s
        binding.btnForward.setOnClickListener {
            val newPos = (binding.videoViewEvidence.currentPosition + 10000).coerceAtMost(binding.videoViewEvidence.duration)
            binding.videoViewEvidence.seekTo(newPos)
        }

        // SeekBar
        binding.seekBarVideo.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) binding.videoViewEvidence.seekTo(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun atualizarSeekBar() {
        handler.post(object : Runnable {
            override fun run() {
                try {
                    val current = binding.videoViewEvidence.currentPosition
                    val total = binding.videoViewEvidence.duration
                    binding.seekBarVideo.progress = current

                    val curMin = (current / 1000) / 60
                    val curSec = (current / 1000) % 60
                    val totMin = (total / 1000) / 60
                    val totSec = (total / 1000) % 60
                    binding.tvVideoTime.text = String.format("%02d:%02d/%02d:%02d", curMin, curSec, totMin, totSec)
                } catch (e: Exception) { /* vídeo pode não estar pronto */ }

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
                val file = File(path)
                if (!file.exists()) continue
                val json = file.readText()
                if (json.isBlank()) continue

                if (tryParseWrappedFormat(json)) {
                    android.util.Log.d("Evidence", "✓ JSON wrapped: $path — ${tripLogs.size} registros")
                    return true
                }
                if (tryParseDirectList(json)) {
                    android.util.Log.d("Evidence", "✓ JSON lista: $path — ${tripLogs.size} registros")
                    return true
                }
            } catch (e: Exception) {
                android.util.Log.e("Evidence", "Erro: $path", e)
            }
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
        val videoFile = File(videoPath)
        val videoName = videoFile.nameWithoutExtension

        paths.add(videoPath.replace(".mp4", ".json").replace("Movies/JustGuide", "Documents/JustGuide/Logs"))

        val logsDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "JustGuide/Logs")
        paths.add(File(logsDir, "$videoName.json").absolutePath)
        paths.add(File(videoFile.parentFile, "$videoName.json").absolutePath)

        if (logsDir.exists()) {
            logsDir.listFiles { f -> f.extension == "json" }?.forEach { jsonFile ->
                if (jsonFile.nameWithoutExtension.contains(videoName.takeLast(13)) ||
                    videoName.contains(jsonFile.nameWithoutExtension.takeLast(13))) {
                    paths.add(jsonFile.absolutePath)
                }
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
                    val millisAtual = binding.videoViewEvidence.currentPosition
                    val segundoAtual = millisAtual / 1000
                    val logEntry = findClosestLog(segundoAtual, startTimestamp)

                    if (logEntry != null) {
                        runOnUiThread {
                            binding.tvEvidenceSpeed.text = "${logEntry.speed.toInt()}"
                            val endereco = if (logEntry.address.isNotBlank()) logEntry.address else "Calculando..."
                            binding.tvEvidenceAddress.text = endereco
                            val sdf = SimpleDateFormat("dd/MM/yy HH:mm:ss", Locale.getDefault())
                            binding.tvEvidenceDate.text = sdf.format(Date(logEntry.timestamp))
                            binding.tvEvidenceCoords.text = String.format("%.4f, %.4f", logEntry.latitude, logEntry.longitude)

                            val pos = LatLng(logEntry.latitude, logEntry.longitude)
                            googleMap?.let { map ->
                                map.animateCamera(CameraUpdateFactory.newLatLngZoom(pos, 17f))
                                currentMarker?.remove()
                                currentMarker = map.addMarker(
                                    MarkerOptions().position(pos)
                                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_CYAN))
                                        .title("${logEntry.speed.toInt()} km/h")
                                )
                            }
                        }
                    }
                } catch (e: Exception) { /* vídeo pode não estar pronto */ }

                handler.postDelayed(this, 1000)
            }
        })
    }

    private fun findClosestLog(segundoVideo: Int, startTimestamp: Long): LocationLog? {
        if (tripLogs.isEmpty()) return null
        val targetTimestamp = startTimestamp + (segundoVideo * 1000L)
        var closest: LocationLog? = null
        var minDiff = Long.MAX_VALUE
        for (log in tripLogs) {
            val diff = kotlin.math.abs(log.timestamp - targetTimestamp)
            if (diff < minDiff) { minDiff = diff; closest = log }
        }
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
        tripPolyline = googleMap?.addPolyline(
            PolylineOptions().addAll(points).width(10f).color(0xFF00FF00.toInt()).geodesic(true)
        )
        if (points.size >= 2) {
            val bounds = LatLngBounds.Builder()
            points.forEach { bounds.include(it) }
            try { googleMap?.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 80)) } catch (e: Exception) {}
        }
    }

    // ══════════════════════════════════════════
    // GERAÇÃO DE PDF — LAUDO PERICIAL
    // ══════════════════════════════════════════

    /**
     * Gera um PDF de laudo pericial para um trecho de vídeo cortado.
     * Chamado externamente ou pode ser acionado por botão futuro.
     *
     * @param startSec segundo inicial do trecho
     * @param endSec segundo final do trecho
     * @param videoFile arquivo de vídeo original
     */
    fun gerarLaudoPericial(startSec: Int, endSec: Int, videoFile: File): File? {
        if (tripLogs.isEmpty()) return null

        val startTimestamp = tripLogs.first().timestamp

        // Filtra logs do trecho
        val trechoLogs = tripLogs.filter { log ->
            val logSec = ((log.timestamp - startTimestamp) / 1000).toInt()
            logSec in startSec..endSec
        }

        if (trechoLogs.isEmpty()) return null

        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
        val sdfFile = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

        // Criar PDF
        val document = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create() // A4
        val page = document.startPage(pageInfo)
        val canvas = page.canvas

        // Cores
        val titlePaint = Paint().apply { color = Color.parseColor("#00CCAA"); textSize = 22f; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); isAntiAlias = true }
        val headerPaint = Paint().apply { color = Color.parseColor("#FFFFFF"); textSize = 14f; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); isAntiAlias = true }
        val textPaint = Paint().apply { color = Color.parseColor("#CCCCCC"); textSize = 11f; typeface = Typeface.MONOSPACE; isAntiAlias = true }
        val labelPaint = Paint().apply { color = Color.parseColor("#668888"); textSize = 10f; typeface = Typeface.MONOSPACE; isAntiAlias = true }
        val linePaint = Paint().apply { color = Color.parseColor("#1A3A4A"); strokeWidth = 1f }

        // Fundo escuro
        canvas.drawColor(Color.parseColor("#050A10"))

        var y = 40f

        // Título
        canvas.drawText("LAUDO PERICIAL DE TELEMETRIA", 30f, y, titlePaint)
        y += 30f
        canvas.drawLine(30f, y, 565f, y, linePaint)
        y += 25f

        // Dados do vídeo
        canvas.drawText("DADOS DO REGISTRO", 30f, y, headerPaint); y += 20f
        canvas.drawText("Arquivo: ${videoFile.name}", 30f, y, textPaint); y += 16f
        canvas.drawText("Trecho: ${formatTime(startSec)} a ${formatTime(endSec)} (${endSec - startSec}s)", 30f, y, textPaint); y += 16f
        canvas.drawText("Registros no trecho: ${trechoLogs.size} pontos de telemetria", 30f, y, textPaint); y += 16f
        canvas.drawText("Gerado em: ${sdf.format(Date())}", 30f, y, textPaint); y += 25f

        canvas.drawLine(30f, y, 565f, y, linePaint); y += 20f

        // Resumo do trecho
        canvas.drawText("RESUMO DO TRECHO", 30f, y, headerPaint); y += 20f

        val firstLog = trechoLogs.first()
        val lastLog = trechoLogs.last()
        val maxSpeed = trechoLogs.maxOf { it.speed }
        val avgSpeed = trechoLogs.map { it.speed }.average()

        canvas.drawText("Início: ${sdf.format(Date(firstLog.timestamp))}", 30f, y, textPaint); y += 16f
        canvas.drawText("Fim: ${sdf.format(Date(lastLog.timestamp))}", 30f, y, textPaint); y += 16f
        canvas.drawText("Velocidade máxima: ${maxSpeed.toInt()} km/h", 30f, y, textPaint); y += 16f
        canvas.drawText("Velocidade média: ${avgSpeed.toInt()} km/h", 30f, y, textPaint); y += 16f
        canvas.drawText("Posição inicial: ${String.format("%.6f, %.6f", firstLog.latitude, firstLog.longitude)}", 30f, y, textPaint); y += 16f
        canvas.drawText("Posição final: ${String.format("%.6f, %.6f", lastLog.latitude, lastLog.longitude)}", 30f, y, textPaint); y += 16f

        val firstAddr = firstLog.address.ifBlank { "Não disponível" }
        val lastAddr = lastLog.address.ifBlank { "Não disponível" }
        canvas.drawText("Endereço inicial: $firstAddr", 30f, y, textPaint); y += 16f
        canvas.drawText("Endereço final: $lastAddr", 30f, y, textPaint); y += 25f

        canvas.drawLine(30f, y, 565f, y, linePaint); y += 20f

        // Tabela de telemetria
        canvas.drawText("REGISTRO DETALHADO DE TELEMETRIA", 30f, y, headerPaint); y += 20f

        // Cabeçalho da tabela
        canvas.drawText("HORA", 30f, y, labelPaint)
        canvas.drawText("VEL", 130f, y, labelPaint)
        canvas.drawText("LATITUDE", 190f, y, labelPaint)
        canvas.drawText("LONGITUDE", 310f, y, labelPaint)
        canvas.drawText("ENDEREÇO", 430f, y, labelPaint)
        y += 14f
        canvas.drawLine(30f, y, 565f, y, linePaint); y += 10f

        // Dados (limita para caber na página)
        val maxRows = ((780 - y) / 14).toInt()
        val step = if (trechoLogs.size > maxRows) trechoLogs.size / maxRows else 1

        var idx = 0
        while (idx < trechoLogs.size && y < 790) {
            val log = trechoLogs[idx]
            val hora = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(log.timestamp))
            val addr = if (log.address.length > 20) log.address.take(20) + "…" else log.address.ifBlank { "-" }

            canvas.drawText(hora, 30f, y, textPaint)
            canvas.drawText("${log.speed.toInt()}", 130f, y, textPaint)
            canvas.drawText(String.format("%.5f", log.latitude), 190f, y, textPaint)
            canvas.drawText(String.format("%.5f", log.longitude), 310f, y, textPaint)
            canvas.drawText(addr, 430f, y, textPaint)
            y += 14f
            idx += step
        }

        // Rodapé
        y = 820f
        canvas.drawLine(30f, y, 565f, y, linePaint); y += 14f
        val footPaint = Paint().apply { color = Color.parseColor("#336666"); textSize = 8f; typeface = Typeface.MONOSPACE; isAntiAlias = true }
        canvas.drawText("Documento gerado automaticamente pelo sistema JustGuide — Tacógrafo Digital com Prova Jurídica", 30f, y, footPaint)

        document.finishPage(page)

        // Salvar
        val outDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "JustGuide/Laudos")
        if (!outDir.exists()) outDir.mkdirs()
        val outFile = File(outDir, "LAUDO_${sdfFile.format(Date())}.pdf")

        return try {
            FileOutputStream(outFile).use { document.writeTo(it) }
            document.close()
            MediaScannerConnection.scanFile(this, arrayOf(outFile.absolutePath), null, null)
            outFile
        } catch (e: Exception) {
            e.printStackTrace()
            document.close()
            null
        }
    }

    private fun formatTime(seconds: Int): String {
        val m = seconds / 60
        val s = seconds % 60
        return String.format("%02d:%02d", m, s)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
