package com.milton.justguide

import android.annotation.SuppressLint
import android.app.Activity
import android.content.*
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.location.*
import android.content.res.ColorStateList
import android.graphics.Color
import androidx.core.content.ContextCompat
import android.media.*
import android.os.*
import android.provider.MediaStore
import android.view.Surface
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import com.google.android.gms.maps.*
import com.google.android.gms.maps.model.*
import com.milton.justguide.databinding.ActivityMapBinding
import com.google.gson.Gson
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.widget.Autocomplete
import com.google.android.libraries.places.widget.model.AutocompleteActivityMode
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.security.MessageDigest

class MapActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityMapBinding
    private lateinit var googleMap: GoogleMap
    private var isMapReady = false
    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var videoFileName: String = ""
    private var videoQuality: Quality = Quality.SD
    private val pathPoints = mutableListOf<LatLng>()
    private var polyline: Polyline? = null
    private var routePolyline: Polyline? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private val toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
    private var isHudActive = false
    private var limiteVelocidade = 80
    private var pontosDaRota = mutableListOf<LatLng>()
    private var ultimoDestinoPesquisado: String = ""
    private var ultimoRecalculoMs: Long = 0
    private val apiKey = "AIzaSyCTmG8ZaVG0tPDCY4GieLWHKynlNOkWEcA"

    private val startAutocomplete = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val place = Autocomplete.getPlaceFromIntent(result.data!!)
            buscarETracarRota(place.address ?: place.name ?: "")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (!Places.isInitialized()) Places.initialize(applicationContext, apiKey)

        val mapFragment = supportFragmentManager.findFragmentById(R.id.map_fragment) as SupportMapFragment
        mapFragment.getMapAsync(this)

        startLocationService()
        setupObservers()

        binding.fabRecoverTrip.setOnClickListener { showTripHistoryDialog() }
        binding.fabRecoverTrip.setOnLongClickListener { abrirBuscaInteligente(); true }
        binding.fabStopRecording.setOnClickListener { toggleRecording() }
        binding.fabSwitchCamera.setOnClickListener { if (recording == null) switchCamera() }
        binding.fabCloseApp.setOnClickListener { exitApplication() }

        binding.fabChangeQuality.setOnClickListener {
            val popup = PopupMenu(this, it)
            popup.menu.add("Mapa: Modo Claro")
            popup.menu.add("Mapa: Modo Escuro")
            popup.menu.add("Mapa: Automático")
            popup.menu.add("---")
            popup.menu.add(if (isHudActive) "Desativar HUD" else "Ativar Modo HUD")
            popup.menu.add("---")
            popup.menu.add("SD (480p)")
            popup.menu.add("HD (720p)")
            popup.menu.add("Full HD (1080p)")
            popup.menu.add("---")
            popup.menu.add("Limite: 60 km/h")
            popup.menu.add("Limite: 80 km/h")
            popup.menu.add("Limite: 110 km/h")

            popup.setOnMenuItemClickListener { item ->
                when {
                    item.title == "Ativar Modo HUD" || item.title == "Desativar HUD" -> toggleHudMode()
                    item.title.toString().contains("Limite") -> {
                        limiteVelocidade = item.title.toString().replace(Regex("[^0-9]"), "").toInt()
                        Toast.makeText(this, "Alerta em $limiteVelocidade km/h", Toast.LENGTH_SHORT).show()
                    }
                    item.title == "Mapa: Modo Claro" -> googleMap.setMapStyle(null)
                    item.title == "Mapa: Modo Escuro" -> googleMap.setMapStyle(MapStyleOptions.loadRawResourceStyle(this, R.raw.map_dark))
                    item.title == "Mapa: Automático" -> aplicarEstiloAutomatico()
                    item.title.toString().contains("p)") -> {
                        videoQuality = when(item.title) {
                            "HD (720p)" -> Quality.HD
                            "Full HD (1080p)" -> Quality.FHD
                            else -> Quality.SD
                        }
                        Toast.makeText(this, "Qualidade alterada para: ${item.title}", Toast.LENGTH_SHORT).show()
                        if (recording == null) startCamera()
                    }
                }
                true
            }
            popup.show()
        }

        binding.fabRotateImage.setOnClickListener {
            if (recording != null) return@setOnClickListener
            requestedOrientation = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT)
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            Handler(Looper.getMainLooper()).postDelayed({ startCamera() }, 800)
        }

        intent.getStringExtra("EXTRA_DESTINO")?.let { if (it.isNotEmpty()) buscarETracarRota(it) }
        Handler(Looper.getMainLooper()).postDelayed({ startCamera() }, 500)
        checarPedidoDeRecorte(intent)
    }

    private fun checarPedidoDeRecorte(intent: Intent?) {
        val vPath = intent?.getStringExtra("EXTRA_VIDEO_PATH")
        val iSec = intent?.getIntExtra("EXTRA_INCIDENT_SEC", -1) ?: -1
        if (!vPath.isNullOrEmpty() && iSec != -1) {
            Handler(Looper.getMainLooper()).postDelayed({
                Toast.makeText(this, "⚙️ Preparando Prova...", Toast.LENGTH_SHORT).show()
                trimIncidentVideo(vPath, iSec)
            }, 3000)
        }
    }

    @SuppressLint("RestrictedApi")
    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        val rotationInt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display?.rotation ?: Surface.ROTATION_0
        else (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation

        val finalRotation = when (rotationInt) {
            Surface.ROTATION_90 -> Surface.ROTATION_90
            Surface.ROTATION_180 -> Surface.ROTATION_180
            Surface.ROTATION_270 -> Surface.ROTATION_270
            else -> Surface.ROTATION_0
        }

        val preview = Preview.Builder().setTargetRotation(finalRotation).build().also { it.setSurfaceProvider(binding.viewFinder.surfaceProvider) }
        val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(videoQuality)).build()
        videoCapture = VideoCapture.Builder(recorder).setTargetRotation(finalRotation).build()

        try {
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.Builder().requireLensFacing(lensFacing).build(), preview, videoCapture)
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun toggleHudMode() {
        isHudActive = !isHudActive
        val scale = if (isHudActive) -1f else 1f
        binding.tvSpeed.scaleY = scale
        binding.tvAddress.scaleY = scale
        binding.tvTripTime.scaleY = scale
        binding.tvDatetime.scaleY = scale
        if (isHudActive) googleMap.setMapStyle(MapStyleOptions.loadRawResourceStyle(this, R.raw.map_dark))
        else aplicarEstiloAutomatico()
    }

    private fun updateMap(loc: Location) {
        if (!isMapReady) return
        val lp = LatLng(loc.latitude, loc.longitude)
        pathPoints.add(lp)
        verificarDesvioDeRota(lp)

        val speedKmh = (loc.speed * 3.6).toInt()

        if (recording != null) {
            val addrText = if (binding.tvAddress.text.isNotEmpty()) binding.tvAddress.text.toString() else "Calculando..."
            val logEntry = LocationLog(
                latitude = loc.latitude,
                longitude = loc.longitude,
                speed = speedKmh.toDouble(),
                address = addrText,
                timestamp = System.currentTimeMillis()
            )
            LocationData.tripLog.add(logEntry)
        }

        runOnUiThread {
            binding.tvSpeed.text = speedKmh.toString()
            if (speedKmh > limiteVelocidade) {
                binding.tvSpeed.setTextColor(Color.RED)
                if (System.currentTimeMillis() % 2000 < 500) toneGenerator.startTone(ToneGenerator.TONE_CDMA_PIP, 300)
            } else binding.tvSpeed.setTextColor(Color.WHITE)
        }

        googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(lp, 17f))
        if (pathPoints.size >= 2) {
            polyline?.remove()
            polyline = googleMap.addPolyline(PolylineOptions().addAll(pathPoints).width(25f).color(0xFF00FF00.toInt()).geodesic(true))
        }
        binding.tvDatetime.text = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        cameraExecutor.execute { getStreetAddress(loc) }
    }

    private fun buscarETracarRota(endereco: String) {
        ultimoDestinoPesquisado = endereco
        cameraExecutor.execute {
            try {
                val addresses = Geocoder(this, Locale.getDefault()).getFromLocationName(endereco, 1)
                if (addresses.isNullOrEmpty()) return@execute
                val destLatLng = LatLng(addresses[0].latitude, addresses[0].longitude)
                val origin = LocationData.currentLocation.value ?: return@execute
                val url = "https://maps.googleapis.com/maps/api/directions/json?origin=${origin.latitude},${origin.longitude}&destination=${destLatLng.latitude},${destLatLng.longitude}&mode=driving&key=$apiKey"
                val response = java.net.URL(url).readText()
                val points = DirectionsParser().parse(response)
                pontosDaRota = points.toMutableList()
                runOnUiThread {
                    if (!isMapReady) return@runOnUiThread
                    routePolyline?.remove()
                    routePolyline = googleMap.addPolyline(PolylineOptions().addAll(points).width(18f).color(0xFF00BFFF.toInt()).geodesic(true))
                    googleMap.addMarker(MarkerOptions().position(destLatLng).title(endereco))
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun verificarDesvioDeRota(minhaPosicao: LatLng) {
        if (pontosDaRota.isEmpty() || ultimoDestinoPesquisado.isEmpty()) return
        val agora = System.currentTimeMillis()
        if (agora - ultimoRecalculoMs < 15000) return
        var menorDistancia = Float.MAX_VALUE
        for (ponto in pontosDaRota) {
            val res = FloatArray(1); Location.distanceBetween(minhaPosicao.latitude, minhaPosicao.longitude, ponto.latitude, ponto.longitude, res)
            if (res[0] < menorDistancia) menorDistancia = res[0]
        }
        if (menorDistancia > 100) { ultimoRecalculoMs = agora; toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 150); buscarETracarRota(ultimoDestinoPesquisado) }
    }

    private fun abrirBuscaInteligente() {
        val fields = listOf(Place.Field.ID, Place.Field.NAME, Place.Field.ADDRESS, Place.Field.LAT_LNG)
        startAutocomplete.launch(Autocomplete.IntentBuilder(AutocompleteActivityMode.OVERLAY, fields).setCountry("BR").build(this))
    }

    private fun startCamera() { ProcessCameraProvider.getInstance(this).addListener({ cameraProvider = ProcessCameraProvider.getInstance(this).get(); bindCameraUseCases() }, ContextCompat.getMainExecutor(this)) }
    private fun toggleRecording() { if (recording == null) startRecording() else stopRecording() }
    private fun stopRecording() { binding.fabStopRecording.isEnabled = false; recording?.stop(); recording = null; requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED; atualizarBotaoGravacao(false) }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        val capture = videoCapture ?: return
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        val name = "JustGuide_${System.currentTimeMillis()}"
        videoFileName = "$name.mp4"
        val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4"); put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/JustGuide") }
        val opts = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(values).build()
        recording = capture.output.prepareRecording(this, opts).withAudioEnabled().start(ContextCompat.getMainExecutor(this)) { event ->
            if (event is VideoRecordEvent.Start) { atualizarBotaoGravacao(true); LocationData.isRecordingLog.postValue(true) }
            if (event is VideoRecordEvent.Finalize) { atualizarBotaoGravacao(false); binding.fabStopRecording.isEnabled = true; LocationData.isRecordingLog.postValue(false); saveTripLog(videoFileName) }
        }
    }

    private fun atualizarBotaoGravacao(gravando: Boolean) {
        runOnUiThread {
            if (gravando) { binding.fabStopRecording.setImageResource(android.R.drawable.ic_media_pause); binding.fabStopRecording.backgroundTintList = ColorStateList.valueOf(Color.RED) }
            else { binding.fabStopRecording.setImageResource(android.R.drawable.ic_media_play); binding.fabStopRecording.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#00FFCC")) }
        }
    }

    private fun trimIncidentVideo(videoPath: String, incidentSecond: Int) {
        val outDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "JustGuide/Provas"); if (!outDir.exists()) outDir.mkdirs()
        val outFile = File(outDir, "PROVA_${System.currentTimeMillis()}.mp4")
        val startUs = if (incidentSecond > 20) (incidentSecond - 20) * 1000000L else 0L
        Thread {
            var extractor: MediaExtractor? = null; var muxer: MediaMuxer? = null; val retriever = MediaMetadataRetriever()
            try {
                Thread.sleep(2000)
                retriever.setDataSource(videoPath); val rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0
                extractor = MediaExtractor().apply { setDataSource(videoPath) }; muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4); muxer.setOrientationHint(rot)
                val trackMap = HashMap<Int, Int>()
                for (i in 0 until extractor.trackCount) {
                    val fmt = extractor.getTrackFormat(i); val mime = fmt.getString(MediaFormat.KEY_MIME)
                    if (mime?.startsWith("video/") == true || mime?.startsWith("audio/") == true) { trackMap[i] = muxer.addTrack(fmt); extractor.selectTrack(i) }
                }
                muxer.start(); val buf = ByteBuffer.allocate(1024 * 1024); val info = MediaCodec.BufferInfo(); extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                var offsetUs: Long = -1
                while (true) {
                    info.size = extractor.readSampleData(buf, 0); if (info.size < 0 || extractor.sampleTime > (startUs + 40000000L)) break
                    if (offsetUs == -1L) offsetUs = extractor.sampleTime; info.presentationTimeUs = extractor.sampleTime - offsetUs
                    val flags = if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    info.flags = flags; muxer.writeSampleData(trackMap[extractor.sampleTrackIndex]!!, buf, info); extractor.advance()
                }
                muxer.stop(); MediaScannerConnection.scanFile(this, arrayOf(outFile.absolutePath), null, null)
                runOnUiThread { Toast.makeText(this, "✅ Prova salva em Downloads!", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) { e.printStackTrace() } finally { extractor?.release(); muxer?.release(); retriever.release() }
        }.start()
    }

    private fun aplicarEstiloAutomatico() {
        if (!isMapReady) return
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (hour >= 18 || hour < 6) googleMap.setMapStyle(MapStyleOptions.loadRawResourceStyle(this, R.raw.map_dark))
        else googleMap.setMapStyle(null)
    }

    private fun showTripHistoryDialog() {
        val moviesDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "JustGuide")
        val files = moviesDir.listFiles { f -> f.extension == "mp4" }?.sortedByDescending { it.lastModified() }
        if (files.isNullOrEmpty()) return
        AlertDialog.Builder(this).setTitle("Cortar Prova").setItems(files.map { SimpleDateFormat("dd/MM HH:mm").format(Date(it.lastModified())) }.toTypedArray()) { _, i ->
            val input = EditText(this); input.inputType = android.text.InputType.TYPE_CLASS_NUMBER
            AlertDialog.Builder(this).setTitle("Minuto").setView(input).setPositiveButton("GERAR") { _, _ -> trimIncidentVideo(files[i].absolutePath, (input.text.toString().toIntOrNull() ?: 0) * 60) }.show()
        }.show()
    }

    private fun setupObservers() {
        LocationData.currentLocation.observe(this) { updateMap(it) }
        LocationData.totalTripTimeSeconds.observe(this) {
            val time = String.format("%02d:%02d:%02d", it/3600, (it%3600)/60, it%60)
            binding.tvTripTime.text = "$time | ${String.format("%.2f km", LocationData.totalDistanceKm.value ?: 0.0)}"
        }
    }

    private fun getStreetAddress(loc: Location) {
        try { val addr = Geocoder(this, Locale.getDefault()).getFromLocation(loc.latitude, loc.longitude, 1); if (!addr.isNullOrEmpty()) runOnUiThread { binding.tvAddress.text = addr[0].thoroughfare ?: "Via Pública" } } catch (e: Exception) {}
    }

    override fun onMapReady(map: GoogleMap) { googleMap = map; isMapReady = true; aplicarEstiloAutomatico(); if (checkLocationPermission()) googleMap.isMyLocationEnabled = true }

    private fun saveTripLog(fileName: String) {
        if (LocationData.tripLog.isEmpty()) return

        // 1. Localiza o vídeo que acabamos de gravar
        val moviesDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "JustGuide")
        val videoFile = File(moviesDir, fileName)

        // 2. Gera a assinatura do vídeo (se ele existir)
        val assinaturaVideo = if (videoFile.exists()) gerarAssinaturaArquivo(videoFile) else "vazio"

        // 3. Preparamos o objeto final para o JSON
        val logFinal = mapOf(
            "assinatura_seguranca" to assinaturaVideo,
            "dados_telemetria" to LocationData.tripLog
        )

        val logData = Gson().toJson(logFinal)
        val logFileName = fileName.replace(".mp4", ".json")
        val logDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "JustGuide/Logs")

        if (!logDir.exists()) logDir.mkdirs()
        val logFile = File(logDir, logFileName)

        try {
            logFile.writeText(logData)
            LocationData.tripLog.clear()
            runOnUiThread { Toast.makeText(this, "📊 Prova Blindada com Sucesso!", Toast.LENGTH_SHORT).show() }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun startLocationService() { if (checkLocationPermission()) ContextCompat.startForegroundService(this, Intent(this, LocationService::class.java)) }
    private fun switchCamera() { lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) CameraSelector.LENS_FACING_BACK else CameraSelector.LENS_FACING_FRONT; startCamera() }
    private fun checkLocationPermission() = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun exitApplication() { stopService(Intent(this, LocationService::class.java)); finishAffinity() }
    override fun onDestroy() { super.onDestroy(); cameraExecutor.shutdown(); toneGenerator.release() }



    private fun gerarAssinaturaArquivo(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = file.readBytes()
        val hashBytes = digest.digest(bytes)
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}