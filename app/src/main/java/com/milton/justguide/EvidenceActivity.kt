package com.milton.justguide

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.maps.*
import com.google.android.gms.maps.model.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.milton.justguide.databinding.ActivityEvidenceBinding
import java.io.File

class EvidenceActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityEvidenceBinding
    private var googleMap: GoogleMap? = null
    private var tripLogs = mutableListOf<LocationLog>()
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEvidenceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val videoPath = intent.getStringExtra("VIDEO_PATH") ?: ""
        // Busca o JSON na pasta de Logs que criamos
        val jsonPath = videoPath.replace(".mp4", ".json")
            .replace("Movies/JustGuide", "Documents/JustGuide/Logs")

        // Inicializa o Mapa
        val mapFragment = supportFragmentManager.findFragmentById(R.id.mapEvidence) as SupportMapFragment
        mapFragment.getMapAsync(this)

        // Carrega os dados periciais
        carregarLogPericial(jsonPath)

        // Configura o Player
        binding.videoViewEvidence.setVideoPath(videoPath)
        binding.videoViewEvidence.setOnPreparedListener {
            it.start()
            sincronizarTelemetria()
        }
    }

    private fun carregarLogPericial(path: String) {
        try {
            val file = File(path)
            if (file.exists()) {
                val json = file.readText()
                val type = object : TypeToken<List<LocationLog>>() {}.type
                tripLogs = Gson().fromJson(json, type)
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun sincronizarTelemetria() {
        handler.post(object : Runnable {
            override fun run() {
                if (binding.videoViewEvidence.isPlaying) {
                    // Sincroniza o segundo do vídeo com a linha do log
                    val segundoAtual = binding.videoViewEvidence.currentPosition / 1000
                    if (segundoAtual < tripLogs.size) {
                        val log = tripLogs[segundoAtual]

                        // Atualiza o Carimbo Dinâmico
                        binding.tvEvidenceSpeed.text = "${log.speed.toInt()} KM/H"
                        binding.tvEvidenceAddress.text = log.address
                        binding.tvEvidenceDate.text = "DATA: ${java.text.SimpleDateFormat("dd/MM/yy HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(log.timestamp))}"

                        // Move o mapa para a posição exata
                        val pos = LatLng(log.latitude, log.longitude)
                        googleMap?.animateCamera(CameraUpdateFactory.newLatLng(pos))
                        googleMap?.clear()
                        googleMap?.addMarker(MarkerOptions().position(pos)
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_CYAN)))
                    }
                }
                handler.postDelayed(this, 1000)
            }
        })
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        googleMap?.setMapStyle(MapStyleOptions.loadRawResourceStyle(this, R.raw.map_dark))
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}