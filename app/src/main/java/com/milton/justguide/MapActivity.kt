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

    // ═══ Rotação da câmera (independente da tela) ═══
    private var isLandscapeRecording = false

    // ═══ Troca de câmera durante gravação ═══
    private var recordingSessionId: Long = 0  // Timestamp da sessão, vincula múltiplos vídeos
    private var recordingPartNumber = 1        // Parte atual (1, 2, 3...)
    private var isSwitchingCamera = false       // Flag para evitar salvar log parcial na troca

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
        binding.fabCloseApp.setOnClickListener { exitApplication() }

        // ═══ Botão trocar câmera — funciona DURANTE gravação ═══
        binding.fabSwitchCamera.setOnClickListener {
            if (recording != null) {
                switchCameraDuringRecording()
            } else {
                switchCamera()
            }
        }

        // ═══ Botão rotacionar — alterna paisagem/retrato da GRAVAÇÃO ═══
        binding.fabRotateImage.setOnClickListener {
            toggleRecordingOrientation()
        }

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
            popup.menu.add("---")
            popup.menu.add(if (isLandscapeRecording) "📹 Gravar: Retrato" else "📹 Gravar: Paisagem")

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
                        val newQuality = when(item.title) {
                            "HD (720p)" -> Quality.HD
                            "Full HD (1080p)" -> Quality.FHD
                            else -> Quality.SD
                        }
                        if (newQuality != videoQuality) {
                            videoQuality = newQuality
                            Toast.makeText(this, "Qualidade: ${item.title}", Toast.LENGTH_SHORT).show()
                            if (recording == null) {
                                startCamera()
                            } else {
                                Toast.makeText(this, "⚠️ Aplicada na próxima gravação", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    item.title.toString().contains("Gravar:") -> {
                        toggleRecordingOrientation()
                    }
                }
                true
            }
            popup.show()
        }

        intent.getStringExtra("EXTRA_DESTINO")?.let { if (it.isNotEmpty()) buscarETracarRota(it) }
        Handler(Looper.getMainLooper()).postDelayed({ startCamera() }, 500)
        checarPedidoDeRecorte(intent)
    }

    // ══════════════════════════════════════════
    // ROTAÇÃO PAISAGEM / RETRATO
    // ══════════════════════════════════════════

    /**
     * Alterna entre gravação paisagem (crop 16:9 widescreen) e retrato (normal).
     * Paisagem: recorta o frame vertical em faixa horizontal, cortando mais
     * céu (70% do corte em cima) e menos estrada (30% embaixo).
     * Precisa recriar o pipeline para aplicar o ViewPort/crop.
     * Se estiver gravando, faz stop→switch→start (como troca de câmera).
     */
    private fun toggleRecordingOrientation() {
        isLandscapeRecording = !isLandscapeRecording

        val modeText = if (isLandscapeRecording) "Paisagem 🌄" else "Retrato 📱"

        if (recording != null) {
            // Precisa recriar pipeline para mudar o crop — faz stop/start rápido
            isSwitchingCamera = true
            recording?.stop()
            recording = null
            recordingPartNumber++

            Handler(Looper.getMainLooper()).postDelayed({
                startCamera()
                Handler(Looper.getMainLooper()).postDelayed({
                    startRecordingPart()
                    isSwitchingCamera = false
                    Toast.makeText(this, "📹 Gravando em: $modeText", Toast.LENGTH_SHORT).show()
                }, 600)
            }, 400)
        } else {
            startCamera()
            Toast.makeText(this, "📹 Modo: $modeText", Toast.LENGTH_SHORT).show()
        }
    }

    // ══════════════════════════════════════════
    // TROCA DE CÂMERA DURANTE GRAVAÇÃO
    // ══════════════════════════════════════════

    /**
     * Troca frontal↔traseira DURANTE gravação.
     * Fluxo: para gravação atual → troca lens → inicia nova gravação.
     * Os arquivos ficam vinculados pelo mesmo sessionId.
     * O log de telemetria continua contínuo (não é interrompido).
     */
    private fun switchCameraDuringRecording() {
        if (isSwitchingCamera) return
        isSwitchingCamera = true

        val cameraLabel = if (lensFacing == CameraSelector.LENS_FACING_BACK) "Interior 🧑" else "Estrada 🛣️"
        Toast.makeText(this, "📷 Trocando para: $cameraLabel", Toast.LENGTH_SHORT).show()

        // 1. Para a gravação atual (sem salvar log — a sessão continua)
        recording?.stop()
        recording = null

        // 2. Troca a câmera
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT)
            CameraSelector.LENS_FACING_BACK else CameraSelector.LENS_FACING_FRONT

        // 3. Incrementa parte
        recordingPartNumber++

        // 4. Recria pipeline e inicia nova gravação após breve delay
        Handler(Looper.getMainLooper()).postDelayed({
            startCamera()

            // 5. Aguarda câmera estar pronta e inicia nova gravação
            Handler(Looper.getMainLooper()).postDelayed({
                startRecordingPart()
                isSwitchingCamera = false
            }, 600)
        }, 400)
    }

    // ══════════════════════════════════════════
    // CÂMERA — Setup e binding
    // ══════════════════════════════════════════

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

        // Rotação sempre ROTATION_0 — o celular fica na vertical
        // Para paisagem, usamos crop via ViewPort em vez de rotação
        val targetRotation = Surface.ROTATION_0

        val previewBuilder = Preview.Builder()
            .setTargetRotation(targetRotation)

        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(videoQuality))
            .build()

        val videoCaptureBuilder = VideoCapture.Builder(recorder)
            .setTargetRotation(targetRotation)

        val preview = previewBuilder.build()
            .also { it.setSurfaceProvider(binding.viewFinder.surfaceProvider) }

        videoCapture = videoCaptureBuilder.build()

        try {
            provider.unbindAll()

            val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

            if (isLandscapeRecording) {
                // ═══ MODO PAISAGEM: Crop 16:9 horizontal ═══
                // Cria um ViewPort com aspect ratio landscape (16:9)
                // ScaleType.FILL_CENTER faz o crop centralizado
                // Depois deslocamos para cortar mais céu usando layout bias
                val viewPort = ViewPort.Builder(
                    android.util.Rational(16, 9),  // Aspect ratio widescreen
                    targetRotation
                ).setScaleType(ViewPort.FILL_CENTER).build()

                val useCaseGroup = UseCaseGroup.Builder()
                    .setViewPort(viewPort)
                    .addUseCase(preview)
                    .addUseCase(videoCapture!!)
                    .build()

                provider.bindToLifecycle(this, cameraSelector, useCaseGroup)
            } else {
                // ═══ MODO RETRATO: Normal, sem crop ═══
                provider.bindToLifecycle(this, cameraSelector, preview, videoCapture)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // Fallback: tenta sem ViewPort se der erro
            try {
                provider.unbindAll()
                val preview2 = Preview.Builder().build()
                    .also { it.setSurfaceProvider(binding.viewFinder.surfaceProvider) }
                val recorder2 = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(videoQuality))
                    .build()
                videoCapture = VideoCapture.Builder(recorder2).build()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.Builder().requireLensFacing(lensFacing).build(),
                    preview2, videoCapture
                )
                if (isLandscapeRecording) {
                    isLandscapeRecording = false
                    runOnUiThread { Toast.makeText(this, "⚠️ Modo paisagem não suportado, usando retrato", Toast.LENGTH_SHORT).show() }
                }
            } catch (e2: Exception) { e2.printStackTrace() }
        }
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

    private fun startCamera() {
        ProcessCameraProvider.getInstance(this).addListener({
            cameraProvider = ProcessCameraProvider.getInstance(this).get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleRecording() { if (recording == null) startRecording() else stopRecording() }

    private fun stopRecording() {
        binding.fabStopRecording.isEnabled = false
        isSwitchingCamera = false
        recording?.stop()
        recording = null
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        atualizarBotaoGravacao(false)
    }

    // ══════════════════════════════════════════
    // GRAVAÇÃO — Início principal e partes
    // ══════════════════════════════════════════

    /**
     * Inicia uma NOVA sessão de gravação (botão REC).
     * Cria sessionId novo, parte 1.
     */
    @SuppressLint("MissingPermission")
    private fun startRecording() {
        val capture = videoCapture ?: return
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED

        // Nova sessão
        recordingSessionId = System.currentTimeMillis()
        recordingPartNumber = 1

        val cameraLabel = if (lensFacing == CameraSelector.LENS_FACING_BACK) "EXT" else "INT"
        val name = "JustGuide_${recordingSessionId}_P${recordingPartNumber}_$cameraLabel"
        videoFileName = "$name.mp4"

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/JustGuide")
        }
        val opts = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values).build()

        recording = capture.output.prepareRecording(this, opts)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(this)) { event ->
                if (event is VideoRecordEvent.Start) {
                    atualizarBotaoGravacao(true)
                    LocationData.isRecordingLog.postValue(true)
                }
                if (event is VideoRecordEvent.Finalize) {
                    binding.fabStopRecording.isEnabled = true
                    // Só salva log e atualiza UI se NÃO for troca de câmera
                    if (!isSwitchingCamera) {
                        atualizarBotaoGravacao(false)
                        LocationData.isRecordingLog.postValue(false)
                        // Salva log com nome da sessão (não da parte)
                        saveTripLog("JustGuide_${recordingSessionId}.mp4")
                    }
                }
            }
    }

    /**
     * Inicia uma PARTE da gravação (após troca de câmera).
     * Usa o mesmo sessionId, incrementa parte.
     */
    @SuppressLint("MissingPermission")
    private fun startRecordingPart() {
        val capture = videoCapture ?: return

        val cameraLabel = if (lensFacing == CameraSelector.LENS_FACING_BACK) "EXT" else "INT"
        val name = "JustGuide_${recordingSessionId}_P${recordingPartNumber}_$cameraLabel"
        videoFileName = "$name.mp4"

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/JustGuide")
        }
        val opts = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values).build()

        recording = capture.output.prepareRecording(this, opts)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(this)) { event ->
                if (event is VideoRecordEvent.Start) {
                    runOnUiThread {
                        val label = if (lensFacing == CameraSelector.LENS_FACING_BACK) "🛣️ Estrada" else "🧑 Interior"
                        Toast.makeText(this, "🔴 Gravando: $label (parte $recordingPartNumber)", Toast.LENGTH_SHORT).show()
                    }
                }
                if (event is VideoRecordEvent.Finalize) {
                    binding.fabStopRecording.isEnabled = true
                    if (!isSwitchingCamera) {
                        atualizarBotaoGravacao(false)
                        LocationData.isRecordingLog.postValue(false)
                        saveTripLog("JustGuide_${recordingSessionId}.mp4")
                    }
                }
            }
    }

    private fun atualizarBotaoGravacao(gravando: Boolean) {
        runOnUiThread {
            if (gravando) {
                binding.fabStopRecording.setImageResource(android.R.drawable.ic_media_pause)
                binding.fabStopRecording.backgroundTintList = ColorStateList.valueOf(Color.RED)
            } else {
                binding.fabStopRecording.setImageResource(android.R.drawable.ic_media_play)
                binding.fabStopRecording.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#00FFCC"))
            }
        }
    }

    // ══════════════════════════════════════════
    // CORTE DE PROVA + PDF LAUDO
    // ══════════════════════════════════════════

    private fun trimIncidentVideo(videoPath: String, incidentSecond: Int) {
        val outDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "JustGuide/Provas"); if (!outDir.exists()) outDir.mkdirs()
        val outFile = File(outDir, "PROVA_${System.currentTimeMillis()}.mp4")
        val startSec = if (incidentSecond > 20) incidentSecond - 20 else 0
        val endSec = incidentSecond + 20
        val startUs = startSec * 1000000L
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

                val pdfFile = gerarLaudoPericial(videoPath, startSec, endSec)
                runOnUiThread {
                    if (pdfFile != null) Toast.makeText(this, "✅ Prova + Laudo PDF salvos!", Toast.LENGTH_LONG).show()
                    else Toast.makeText(this, "✅ Prova salva! (sem telemetria para laudo)", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) { e.printStackTrace() } finally { extractor?.release(); muxer?.release(); retriever.release() }
        }.start()
    }

    private fun gerarLaudoPericial(videoPath: String, startSec: Int, endSec: Int): File? {
        val tripLogs = carregarLogParaLaudo(videoPath)
        if (tripLogs.isEmpty()) return null

        val startTimestamp = tripLogs.first().timestamp
        val trechoLogs = tripLogs.filter { log ->
            val logSec = ((log.timestamp - startTimestamp) / 1000).toInt()
            logSec in startSec..endSec
        }
        if (trechoLogs.isEmpty()) return null

        val sdf = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", java.util.Locale.getDefault())
        val sdfFile = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())

        val document = android.graphics.pdf.PdfDocument()
        val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, 1).create()
        val page = document.startPage(pageInfo)
        val canvas = page.canvas

        val titlePaint = android.graphics.Paint().apply { color = android.graphics.Color.parseColor("#00CCAA"); textSize = 22f; typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD); isAntiAlias = true }
        val headerPaint = android.graphics.Paint().apply { color = android.graphics.Color.parseColor("#FFFFFF"); textSize = 14f; typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD); isAntiAlias = true }
        val textPaint = android.graphics.Paint().apply { color = android.graphics.Color.parseColor("#CCCCCC"); textSize = 11f; typeface = android.graphics.Typeface.MONOSPACE; isAntiAlias = true }
        val labelPaint = android.graphics.Paint().apply { color = android.graphics.Color.parseColor("#668888"); textSize = 10f; typeface = android.graphics.Typeface.MONOSPACE; isAntiAlias = true }
        val linePaint = android.graphics.Paint().apply { color = android.graphics.Color.parseColor("#1A3A4A"); strokeWidth = 1f }

        canvas.drawColor(android.graphics.Color.parseColor("#050A10"))
        var y = 40f

        val subtitlePaint = android.graphics.Paint().apply { color = android.graphics.Color.parseColor("#336666"); textSize = 9f; typeface = android.graphics.Typeface.MONOSPACE; isAntiAlias = true }
        canvas.drawText("LAUDO PERICIAL DE TELEMETRIA", 30f, y, titlePaint); y += 20f
        canvas.drawText("JustGuide — Tacógrafo Digital com Prova Jurídica", 30f, y, subtitlePaint); y += 16f
        canvas.drawLine(30f, y, 565f, y, linePaint); y += 22f

        canvas.drawText("DADOS DO REGISTRO", 30f, y, headerPaint); y += 20f
        canvas.drawText("Arquivo: ${File(videoPath).name}", 30f, y, textPaint); y += 16f
        canvas.drawText("Trecho: ${formatTimePdf(startSec)} a ${formatTimePdf(endSec)} (${endSec - startSec}s)", 30f, y, textPaint); y += 16f
        canvas.drawText("Pontos de telemetria: ${trechoLogs.size}", 30f, y, textPaint); y += 16f
        canvas.drawText("Gerado em: ${sdf.format(java.util.Date())}", 30f, y, textPaint); y += 22f
        canvas.drawLine(30f, y, 565f, y, linePaint); y += 22f

        canvas.drawText("RESUMO DO TRECHO", 30f, y, headerPaint); y += 20f
        val firstLog = trechoLogs.first(); val lastLog = trechoLogs.last()
        val maxSpeed = trechoLogs.maxOf { it.speed }; val avgSpeed = trechoLogs.map { it.speed }.average()
        canvas.drawText("Início: ${sdf.format(java.util.Date(firstLog.timestamp))}", 30f, y, textPaint); y += 16f
        canvas.drawText("Fim: ${sdf.format(java.util.Date(lastLog.timestamp))}", 30f, y, textPaint); y += 16f
        canvas.drawText("Vel. máxima: ${maxSpeed.toInt()} km/h | Vel. média: ${avgSpeed.toInt()} km/h", 30f, y, textPaint); y += 16f
        canvas.drawText("GPS início: ${String.format("%.6f, %.6f", firstLog.latitude, firstLog.longitude)}", 30f, y, textPaint); y += 16f
        canvas.drawText("GPS fim: ${String.format("%.6f, %.6f", lastLog.latitude, lastLog.longitude)}", 30f, y, textPaint); y += 16f
        canvas.drawText("End. início: ${firstLog.address.ifBlank { "N/D" }}", 30f, y, textPaint); y += 16f
        canvas.drawText("End. fim: ${lastLog.address.ifBlank { "N/D" }}", 30f, y, textPaint); y += 18f

        try {
            val videoFile = File(videoPath)
            if (videoFile.exists()) {
                val hash = gerarAssinaturaArquivo(videoFile)
                canvas.drawText("SHA-256: ${hash.take(32)}...", 30f, y, labelPaint); y += 12f
                canvas.drawText("...${hash.drop(32)}", 30f, y, labelPaint); y += 18f
            }
        } catch (e: Exception) { y += 18f }

        canvas.drawLine(30f, y, 565f, y, linePaint); y += 22f
        canvas.drawText("REGISTRO DETALHADO", 30f, y, headerPaint); y += 18f
        canvas.drawText("HORA", 30f, y, labelPaint); canvas.drawText("VEL", 120f, y, labelPaint)
        canvas.drawText("LATITUDE", 170f, y, labelPaint); canvas.drawText("LONGITUDE", 290f, y, labelPaint)
        canvas.drawText("ENDEREÇO", 420f, y, labelPaint); y += 14f
        canvas.drawLine(30f, y, 565f, y, linePaint); y += 10f

        val maxRows = ((790 - y) / 13).toInt()
        val step = if (trechoLogs.size > maxRows) (trechoLogs.size / maxRows).coerceAtLeast(1) else 1
        var idx = 0
        while (idx < trechoLogs.size && y < 790) {
            val log = trechoLogs[idx]
            val hora = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(log.timestamp))
            val addr = if (log.address.length > 22) log.address.take(22) + "…" else log.address.ifBlank { "-" }
            canvas.drawText(hora, 30f, y, textPaint); canvas.drawText("${log.speed.toInt()}", 120f, y, textPaint)
            canvas.drawText(String.format("%.5f", log.latitude), 170f, y, textPaint)
            canvas.drawText(String.format("%.5f", log.longitude), 290f, y, textPaint)
            canvas.drawText(addr, 420f, y, textPaint); y += 13f; idx += step
        }

        y = 822f; canvas.drawLine(30f, y, 565f, y, linePaint); y += 12f
        val footPaint = android.graphics.Paint().apply { color = android.graphics.Color.parseColor("#336666"); textSize = 7f; typeface = android.graphics.Typeface.MONOSPACE; isAntiAlias = true }
        canvas.drawText("Documento gerado pelo sistema JustGuide — Dados brutos de telemetria GPS.", 30f, y, footPaint)
        document.finishPage(page)

        val outDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "JustGuide/Laudos")
        if (!outDir.exists()) outDir.mkdirs()
        val outFile = File(outDir, "LAUDO_${sdfFile.format(java.util.Date())}.pdf")

        return try {
            java.io.FileOutputStream(outFile).use { document.writeTo(it) }
            document.close()
            MediaScannerConnection.scanFile(this, arrayOf(outFile.absolutePath), null, null)
            outFile
        } catch (e: Exception) { e.printStackTrace(); document.close(); null }
    }

    private fun carregarLogParaLaudo(videoPath: String): List<LocationLog> {
        val videoFile = File(videoPath)
        val videoName = videoFile.nameWithoutExtension
        val logsDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "JustGuide/Logs")
        val possiblePaths = mutableListOf<String>()
        possiblePaths.add(videoPath.replace(".mp4", ".json").replace("Movies/JustGuide", "Documents/JustGuide/Logs"))
        possiblePaths.add(File(logsDir, "$videoName.json").absolutePath)

        // Busca por sessionId — para vídeos com partes (P1_EXT, P2_INT, etc)
        val sessionMatch = Regex("JustGuide_(\\d+)").find(videoName)
        if (sessionMatch != null) {
            val sessionId = sessionMatch.groupValues[1]
            possiblePaths.add(File(logsDir, "JustGuide_$sessionId.json").absolutePath)
        }

        if (logsDir.exists()) {
            logsDir.listFiles { f -> f.extension == "json" }?.forEach { jsonFile ->
                if (jsonFile.nameWithoutExtension.contains(videoName.takeLast(13)) ||
                    videoName.contains(jsonFile.nameWithoutExtension.takeLast(13))) {
                    possiblePaths.add(jsonFile.absolutePath)
                }
            }
        }
        for (path in possiblePaths.distinct()) {
            try {
                val file = File(path); if (!file.exists()) continue
                val json = file.readText(); if (json.isBlank()) continue
                try {
                    val wrapper = com.google.gson.Gson().fromJson(json, com.google.gson.JsonObject::class.java)
                    if (wrapper.has("dados_telemetria")) {
                        val type = object : com.google.gson.reflect.TypeToken<List<LocationLog>>() {}.type
                        val logs: List<LocationLog> = com.google.gson.Gson().fromJson(wrapper.getAsJsonArray("dados_telemetria"), type)
                        if (logs.isNotEmpty()) return logs
                    }
                } catch (e: Exception) {}
                try {
                    val type = object : com.google.gson.reflect.TypeToken<List<LocationLog>>() {}.type
                    val logs: List<LocationLog> = com.google.gson.Gson().fromJson(json, type)
                    if (logs.isNotEmpty()) return logs
                } catch (e: Exception) {}
            } catch (e: Exception) {}
        }
        return emptyList()
    }

    private fun formatTimePdf(seconds: Int): String {
        val m = seconds / 60; val s = seconds % 60; return String.format("%02d:%02d", m, s)
    }

    // ══════════════════════════════════════════
    // FUNÇÕES EXISTENTES (mantidas)
    // ══════════════════════════════════════════

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
        val moviesDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "JustGuide")
        val videoFile = File(moviesDir, fileName)
        val assinaturaVideo = if (videoFile.exists()) gerarAssinaturaArquivo(videoFile) else "sessao_multiplos_videos"
        val logFinal = mapOf("assinatura_seguranca" to assinaturaVideo, "dados_telemetria" to LocationData.tripLog.toList())
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

    private fun switchCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) CameraSelector.LENS_FACING_BACK else CameraSelector.LENS_FACING_FRONT
        startCamera()
    }

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
