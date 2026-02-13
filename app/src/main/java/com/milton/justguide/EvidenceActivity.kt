package com.milton.justguide

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Environment
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.maps.*
import com.google.android.gms.maps.model.*
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.milton.justguide.databinding.ActivityEvidenceBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class EvidenceActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityEvidenceBinding
    private var googleMap: GoogleMap? = null
    private var tripLogs = mutableListOf<LocationLog>()
    private val handler = Handler(Looper.getMainLooper())
    private var currentMarker: Marker? = null
    private var tripPolyline: Polyline? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEvidenceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val videoPath = intent.getStringExtra("VIDEO_PATH") ?: ""

        // Inicializa o Mapa
        val mapFragment = supportFragmentManager.findFragmentById(R.id.mapEvidence) as SupportMapFragment
        mapFragment.getMapAsync(this)

        // Carrega os dados periciais - tenta múltiplos caminhos
        val jsonLoaded = carregarLogPericial(videoPath)
        if (!jsonLoaded) {
            Toast.makeText(this, "⚠️ Log de telemetria não encontrado para este vídeo.", Toast.LENGTH_LONG).show()
        }

        // Configura o Player
        if (videoPath.isNotEmpty()) {
            binding.videoViewEvidence.setVideoPath(videoPath)
            binding.videoViewEvidence.setOnPreparedListener { mp ->
                mp.start()
                sincronizarTelemetria()
            }
            binding.videoViewEvidence.setOnErrorListener { _, _, _ ->
                Toast.makeText(this, "Erro ao reproduzir vídeo.", Toast.LENGTH_SHORT).show()
                true
            }
        }
    }

    /**
     * Carrega o log pericial tentando múltiplos caminhos e formatos de JSON.
     * Retorna true se conseguiu carregar dados.
     */
    private fun carregarLogPericial(videoPath: String): Boolean {
        val possiblePaths = buildPossibleJsonPaths(videoPath)

        for (path in possiblePaths) {
            try {
                val file = File(path)
                if (!file.exists()) continue

                val json = file.readText()
                if (json.isBlank()) continue

                // Tenta o formato com wrapper (assinatura_seguranca + dados_telemetria)
                if (tryParseWrappedFormat(json)) {
                    android.util.Log.d("Evidence", "✓ JSON carregado (formato wrapped) de: $path — ${tripLogs.size} registros")
                    return true
                }

                // Tenta o formato de lista direta (fallback para JSONs antigos)
                if (tryParseDirectList(json)) {
                    android.util.Log.d("Evidence", "✓ JSON carregado (formato lista) de: $path — ${tripLogs.size} registros")
                    return true
                }

            } catch (e: Exception) {
                android.util.Log.e("Evidence", "Erro ao ler JSON de: $path", e)
            }
        }

        android.util.Log.w("Evidence", "✗ Nenhum JSON encontrado. Caminhos tentados: $possiblePaths")
        return false
    }

    /**
     * Parse do formato atual: { "assinatura_seguranca": "...", "dados_telemetria": [...] }
     */
    private fun tryParseWrappedFormat(json: String): Boolean {
        return try {
            val wrapper = Gson().fromJson(json, JsonObject::class.java)
            if (wrapper.has("dados_telemetria")) {
                val telemetriaArray = wrapper.getAsJsonArray("dados_telemetria")
                val type = object : TypeToken<List<LocationLog>>() {}.type
                val logs: List<LocationLog> = Gson().fromJson(telemetriaArray, type)
                if (logs.isNotEmpty()) {
                    tripLogs = logs.toMutableList()
                    true
                } else false
            } else false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Parse do formato antigo: lista direta de LocationLog
     */
    private fun tryParseDirectList(json: String): Boolean {
        return try {
            val type = object : TypeToken<List<LocationLog>>() {}.type
            val logs: List<LocationLog> = Gson().fromJson(json, type)
            if (logs.isNotEmpty()) {
                tripLogs = logs.toMutableList()
                true
            } else false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Gera múltiplos caminhos possíveis para o JSON baseado no path do vídeo.
     * Cobre diferentes cenários de onde o vídeo pode estar.
     */
    private fun buildPossibleJsonPaths(videoPath: String): List<String> {
        val paths = mutableListOf<String>()

        if (videoPath.isEmpty()) return paths

        val videoFile = File(videoPath)
        val videoName = videoFile.nameWithoutExtension

        // Caminho 1: Substituição direta (Movies/JustGuide → Documents/JustGuide/Logs)
        val directReplace = videoPath.replace(".mp4", ".json")
            .replace("Movies/JustGuide", "Documents/JustGuide/Logs")
        paths.add(directReplace)

        // Caminho 2: Construção explícita usando DIRECTORY_DOCUMENTS
        val logsDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "JustGuide/Logs"
        )
        paths.add(File(logsDir, "$videoName.json").absolutePath)

        // Caminho 3: Mesmo diretório do vídeo
        paths.add(File(videoFile.parentFile, "$videoName.json").absolutePath)

        // Caminho 4: Busca por nome parcial na pasta de logs (para nomes com timestamp)
        if (logsDir.exists()) {
            logsDir.listFiles { f -> f.extension == "json" }?.forEach { jsonFile ->
                // Se o nome do JSON contém parte do nome do vídeo ou vice-versa
                if (jsonFile.nameWithoutExtension.contains(videoName.takeLast(13)) ||
                    videoName.contains(jsonFile.nameWithoutExtension.takeLast(13))) {
                    paths.add(jsonFile.absolutePath)
                }
            }
        }

        return paths.distinct()
    }

    /**
     * Sincroniza a telemetria do log com o segundo atual do vídeo.
     * Usa timestamp relativo (diferença entre primeiro e atual) para encontrar
     * o registro correto, em vez de depender do índice sequencial.
     */
    private fun sincronizarTelemetria() {
        if (tripLogs.isEmpty()) return

        val startTimestamp = tripLogs.first().timestamp

        handler.post(object : Runnable {
            override fun run() {
                if (!binding.videoViewEvidence.isPlaying) {
                    handler.postDelayed(this, 500)
                    return
                }

                val millisAtual = binding.videoViewEvidence.currentPosition
                val segundoAtual = millisAtual / 1000

                // Encontra o registro mais próximo do segundo atual
                // Método 1: Por índice direto (se log tem ~1 registro por segundo)
                // Método 2: Por timestamp relativo (mais preciso)
                val logEntry = findClosestLog(segundoAtual, startTimestamp)

                if (logEntry != null) {
                    // Atualiza o Carimbo Dinâmico
                    runOnUiThread {
                        binding.tvEvidenceSpeed.text = "${logEntry.speed.toInt()} KM/H"

                        val endereco = if (logEntry.address.isNotBlank()) logEntry.address else "Calculando..."
                        binding.tvEvidenceAddress.text = endereco

                        val sdf = SimpleDateFormat("dd/MM/yy HH:mm:ss", Locale.getDefault())
                        binding.tvEvidenceDate.text = sdf.format(Date(logEntry.timestamp))

                        // Move o mapa para a posição exata
                        val pos = LatLng(logEntry.latitude, logEntry.longitude)
                        googleMap?.let { map ->
                            map.animateCamera(CameraUpdateFactory.newLatLngZoom(pos, 17f))

                            // Atualiza marcador
                            currentMarker?.remove()
                            currentMarker = map.addMarker(
                                MarkerOptions()
                                    .position(pos)
                                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_CYAN))
                                    .title("${logEntry.speed.toInt()} km/h")
                            )
                        }
                    }
                }

                handler.postDelayed(this, 1000)
            }
        })
    }

    /**
     * Encontra o registro de log mais próximo do segundo atual do vídeo.
     * Tenta primeiro por timestamp relativo, depois por índice direto.
     */
    private fun findClosestLog(segundoVideo: Int, startTimestamp: Long): LocationLog? {
        if (tripLogs.isEmpty()) return null

        // Método 1: Por timestamp relativo (mais preciso)
        val targetTimestamp = startTimestamp + (segundoVideo * 1000L)
        var closest: LocationLog? = null
        var minDiff = Long.MAX_VALUE

        for (log in tripLogs) {
            val diff = kotlin.math.abs(log.timestamp - targetTimestamp)
            if (diff < minDiff) {
                minDiff = diff
                closest = log
            }
        }

        // Se encontrou algo razoável (dentro de 10 segundos), usa
        if (closest != null && minDiff < 10000) return closest

        // Método 2: Fallback por índice direto
        return if (segundoVideo < tripLogs.size) tripLogs[segundoVideo]
        else tripLogs.lastOrNull()
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map

        // Estilo escuro
        try {
            googleMap?.setMapStyle(MapStyleOptions.loadRawResourceStyle(this, R.raw.map_dark))
        } catch (e: Exception) { /* ignora se não encontrar o style */ }

        // Desenha a rota completa da viagem no mapa
        desenharRotaCompleta()
    }

    /**
     * Desenha a polyline completa da viagem no mapa.
     * Assim o usuário vê todo o trajeto percorrido.
     */
    private fun desenharRotaCompleta() {
        if (tripLogs.isEmpty() || googleMap == null) return

        val points = tripLogs.map { LatLng(it.latitude, it.longitude) }

        tripPolyline?.remove()
        tripPolyline = googleMap?.addPolyline(
            PolylineOptions()
                .addAll(points)
                .width(12f)
                .color(0xFF00FF00.toInt()) // Verde igual ao da gravação
                .geodesic(true)
        )

        // Zoom para mostrar toda a rota
        if (points.size >= 2) {
            val bounds = LatLngBounds.Builder()
            points.forEach { bounds.include(it) }
            try {
                googleMap?.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 100))
            } catch (e: Exception) { /* ignora se mapa não tiver dimensões ainda */ }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
