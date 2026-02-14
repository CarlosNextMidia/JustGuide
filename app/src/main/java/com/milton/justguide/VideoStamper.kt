package com.milton.justguide

import android.content.Context
import android.graphics.*
import android.media.*
import android.os.Environment
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * VideoStamper — Carimba dados de telemetria no vídeo.
 *
 * Fluxo:
 * 1. Decodifica o MP4 original frame a frame (MediaCodec decoder)
 * 2. Renderiza cada frame em um Canvas
 * 3. Desenha overlay de telemetria (velocidade, rua, GPS, data, branding)
 * 4. Codifica o frame carimbado (MediaCodec encoder)
 * 5. Copia a trilha de áudio sem re-encoding
 * 6. Salva o resultado em Movies/JustGuide/ com sufixo _CARIMBADO
 *
 * Uso: VideoStamper(context).stamp(videoPath, jsonPath) { resultPath -> ... }
 */
class VideoStamper(private val context: Context) {

    companion object {
        private const val TAG = "VideoStamper"
        private const val TIMEOUT_US = 10000L
    }

    data class StampResult(val success: Boolean, val outputPath: String?, val error: String? = null)

    /**
     * Processa o vídeo em background thread.
     * @param videoPath caminho do MP4 original
     * @param onComplete callback com resultado (chamado na thread de background)
     */
    fun stampAsync(videoPath: String, onComplete: (StampResult) -> Unit) {
        Thread {
            val result = stamp(videoPath)
            onComplete(result)
        }.start()
    }

    fun stamp(videoPath: String): StampResult {
        try {
            // 1. Carrega telemetria
            val logs = carregarTelemetria(videoPath)
            if (logs.isEmpty()) {
                return StampResult(false, null, "Sem dados de telemetria")
            }

            val videoFile = File(videoPath)
            if (!videoFile.exists()) {
                return StampResult(false, null, "Vídeo não encontrado")
            }

            // 2. Configura output
            val outDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "JustGuide")
            if (!outDir.exists()) outDir.mkdirs()
            val outName = videoFile.nameWithoutExtension + "_CARIMBADO.mp4"
            val outFile = File(outDir, outName)

            // 3. Processa
            val success = processVideo(videoPath, outFile.absolutePath, logs)

            if (success) {
                // Registra no MediaScanner
                android.media.MediaScannerConnection.scanFile(
                    context, arrayOf(outFile.absolutePath), null, null
                )
                return StampResult(true, outFile.absolutePath)
            } else {
                outFile.delete()
                return StampResult(false, null, "Erro no processamento")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Erro no stamp", e)
            return StampResult(false, null, e.message)
        }
    }

    // ══════════════════════════════════════════
    // PROCESSAMENTO DE VÍDEO
    // ══════════════════════════════════════════

    private fun processVideo(inputPath: String, outputPath: String, logs: List<LocationLog>): Boolean {
        val extractor = MediaExtractor()
        extractor.setDataSource(inputPath)

        // Encontra tracks de vídeo e áudio
        var videoTrackIndex = -1
        var audioTrackIndex = -1
        var videoFormat: MediaFormat? = null
        var audioFormat: MediaFormat? = null

        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/") && videoTrackIndex == -1) {
                videoTrackIndex = i
                videoFormat = format
            } else if (mime.startsWith("audio/") && audioTrackIndex == -1) {
                audioTrackIndex = i
                audioFormat = format
            }
        }

        if (videoTrackIndex == -1 || videoFormat == null) {
            extractor.release()
            return false
        }

        val width = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
        val height = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
        val mime = videoFormat.getString(MediaFormat.KEY_MIME) ?: "video/avc"
        val rotation = try { videoFormat.getInteger(MediaFormat.KEY_ROTATION) } catch (e: Exception) { 0 }
        val frameRate = try { videoFormat.getInteger(MediaFormat.KEY_FRAME_RATE) } catch (e: Exception) { 30 }
        val bitRate = try { videoFormat.getInteger(MediaFormat.KEY_BIT_RATE) } catch (e: Exception) { width * height * 4 } // fallback

        Log.d(TAG, "Vídeo: ${width}x${height} rot=$rotation fps=$frameRate bitrate=$bitRate")

        // Configura decoder
        val decoder = MediaCodec.createDecoderByType(mime)
        val decoderFormat = MediaFormat.createVideoFormat(mime, width, height)

        // Configura encoder (H.264)
        val encoderMime = "video/avc"
        val encoderFormat = MediaFormat.createVideoFormat(encoderMime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val encoder = MediaCodec.createEncoderByType(encoderMime)
        encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = encoder.createInputSurface()
        encoder.start()

        // Configura decoder para renderizar no Surface do encoder
        decoder.configure(videoFormat, inputSurface, null, 0)
        decoder.start()

        // Muxer
        val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        if (rotation != 0) muxer.setOrientationHint(rotation)

        var muxerVideoTrack = -1
        var muxerAudioTrack = -1
        var muxerStarted = false

        // Prepara paints para overlay
        val overlayPaints = criarPaintsOverlay(width, height)
        val startTimestamp = logs.first().timestamp

        // ── PASSA 1: Decodifica e re-encoda vídeo com overlay ──
        // Nota: a abordagem Surface-to-Surface não permite Canvas overlay direto.
        // Precisamos usar uma abordagem alternativa: decode para Bitmap, overlay, encode.

        // Vamos usar a abordagem decode→Bitmap→Canvas→encode
        decoder.stop()
        decoder.release()

        // Reconfigura decoder para output em buffer (não surface)
        val decoder2 = MediaCodec.createDecoderByType(mime)
        val decoderFormat2 = MediaFormat(videoFormat)
        // Force color format YUV
        decoder2.configure(videoFormat, null, null, 0)
        decoder2.start()

        // Reconfigura encoder para aceitar input por buffer (não surface)
        encoder.stop()
        encoder.release()
        inputSurface.release()

        val encoder2 = MediaCodec.createEncoderByType(encoderMime)
        val encoderFormat2 = MediaFormat.createVideoFormat(encoderMime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        encoder2.configure(encoderFormat2, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder2.start()

        extractor.selectTrack(videoTrackIndex)
        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

        val bufferInfo = MediaCodec.BufferInfo()
        val encBufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var decoderDone = false
        var videoFrameCount = 0

        // Bitmap reutilizável para overlay
        var overlayBitmap: Bitmap? = null
        var overlayCanvas: Canvas? = null

        while (!decoderDone) {
            // Feed decoder
            if (!inputDone) {
                val inIdx = decoder2.dequeueInputBuffer(TIMEOUT_US)
                if (inIdx >= 0) {
                    val inputBuffer = decoder2.getInputBuffer(inIdx)!!
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        decoder2.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder2.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            // Drain decoder
            val outIdx = decoder2.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            if (outIdx >= 0) {
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    decoderDone = true
                    // Sinaliza fim para encoder
                    val encInIdx = encoder2.dequeueInputBuffer(TIMEOUT_US)
                    if (encInIdx >= 0) {
                        encoder2.queueInputBuffer(encInIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    }
                } else if (bufferInfo.size > 0) {
                    // Obtém o frame decodificado
                    val image = decoder2.getOutputImage(outIdx)

                    if (image != null) {
                        // Converte YUV para Bitmap
                        val frameBitmap = yuvImageToBitmap(image, width, height)
                        image.close()

                        if (frameBitmap != null) {
                            // Inicializa bitmap de overlay se necessário
                            if (overlayBitmap == null) {
                                overlayBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                overlayCanvas = Canvas(overlayBitmap!!)
                            }

                            // Desenha frame original
                            overlayCanvas!!.drawBitmap(frameBitmap, 0f, 0f, null)

                            // Calcula segundo do vídeo e encontra log correspondente
                            val videoTimeUs = bufferInfo.presentationTimeUs
                            val videoSec = (videoTimeUs / 1000000).toInt()
                            val log = findClosestLog(videoSec, startTimestamp, logs)

                            // Desenha overlay de telemetria
                            if (log != null) {
                                desenharOverlay(overlayCanvas!!, log, width, height, overlayPaints, videoTimeUs)
                            }

                            // Envia bitmap carimbado para encoder
                            val encInIdx = encoder2.dequeueInputBuffer(TIMEOUT_US)
                            if (encInIdx >= 0) {
                                val encImage = encoder2.getInputImage(encInIdx)
                                if (encImage != null) {
                                    bitmapToYuvImage(overlayBitmap!!, encImage)
                                    encoder2.queueInputBuffer(encInIdx, 0, 0, bufferInfo.presentationTimeUs, 0)
                                }
                            }

                            frameBitmap.recycle()
                            videoFrameCount++
                        }
                    }
                }
                decoder2.releaseOutputBuffer(outIdx, false)
            }

            // Drain encoder
            drainEncoder(encoder2, muxer, encBufferInfo, muxerVideoTrack, muxerStarted) { track, started ->
                muxerVideoTrack = track
                muxerStarted = started
            }
        }

        // Drain encoder final
        var encoderDone = false
        while (!encoderDone) {
            val encOutIdx = encoder2.dequeueOutputBuffer(encBufferInfo, TIMEOUT_US)
            when {
                encOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        muxerVideoTrack = muxer.addTrack(encoder2.outputFormat)
                        if (audioFormat != null) {
                            muxerAudioTrack = muxer.addTrack(audioFormat)
                        }
                        muxer.start()
                        muxerStarted = true
                    }
                }
                encOutIdx >= 0 -> {
                    if (encBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        encoderDone = true
                    }
                    if (encBufferInfo.size > 0 && muxerStarted) {
                        val encData = encoder2.getOutputBuffer(encOutIdx)!!
                        encData.position(encBufferInfo.offset)
                        encData.limit(encBufferInfo.offset + encBufferInfo.size)
                        muxer.writeSampleData(muxerVideoTrack, encData, encBufferInfo)
                    }
                    encoder2.releaseOutputBuffer(encOutIdx, false)
                }
            }
        }

        // ── PASSA 2: Copia áudio ──
        if (audioTrackIndex != -1 && audioFormat != null && muxerStarted && muxerAudioTrack != -1) {
            extractor.unselectTrack(videoTrackIndex)
            extractor.selectTrack(audioTrackIndex)
            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val audioBuf = ByteBuffer.allocate(1024 * 1024)
            val audioInfo = MediaCodec.BufferInfo()

            while (true) {
                audioInfo.size = extractor.readSampleData(audioBuf, 0)
                if (audioInfo.size < 0) break
                audioInfo.presentationTimeUs = extractor.sampleTime
                audioInfo.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                    MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                muxer.writeSampleData(muxerAudioTrack, audioBuf, audioInfo)
                extractor.advance()
            }
        }

        // Cleanup
        decoder2.stop(); decoder2.release()
        encoder2.stop(); encoder2.release()
        extractor.release()
        if (muxerStarted) { muxer.stop(); muxer.release() } else { muxer.release() }
        overlayBitmap?.recycle()

        Log.d(TAG, "✅ Carimbo concluído: $videoFrameCount frames processados → $outputPath")
        return videoFrameCount > 0
    }

    private fun drainEncoder(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        info: MediaCodec.BufferInfo,
        currentTrack: Int,
        muxerStarted: Boolean,
        onMuxerConfig: (Int, Boolean) -> Unit
    ) {
        var track = currentTrack
        var started = muxerStarted

        while (true) {
            val outIdx = encoder.dequeueOutputBuffer(info, 0) // non-blocking
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!started) {
                        track = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        started = true
                        onMuxerConfig(track, started)
                    }
                }
                outIdx >= 0 -> {
                    if (info.size > 0 && started) {
                        val data = encoder.getOutputBuffer(outIdx)!!
                        data.position(info.offset)
                        data.limit(info.offset + info.size)
                        muxer.writeSampleData(track, data, info)
                    }
                    encoder.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return // no more output available
            }
        }
    }

    // ══════════════════════════════════════════
    // OVERLAY — Desenho do carimbo
    // ══════════════════════════════════════════

    data class OverlayPaints(
        val speedPaint: Paint,
        val unitPaint: Paint,
        val addressPaint: Paint,
        val datePaint: Paint,
        val coordsPaint: Paint,
        val brandPaint: Paint,
        val bgPaint: Paint,
        val barHeight: Float,
        val margin: Float,
        val scale: Float
    )

    private fun criarPaintsOverlay(width: Int, height: Int): OverlayPaints {
        // Escala baseada na resolução (referência: 1080px width)
        val scale = width / 1080f

        val speedPaint = Paint().apply {
            color = Color.parseColor("#00FFCC")
            textSize = 48f * scale
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            isAntiAlias = true
            setShadowLayer(8f * scale, 0f, 0f, Color.parseColor("#0088AA"))
        }

        val unitPaint = Paint().apply {
            color = Color.parseColor("#4A8A8A")
            textSize = 14f * scale
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
        }

        val addressPaint = Paint().apply {
            color = Color.parseColor("#AACCCC")
            textSize = 18f * scale
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
        }

        val datePaint = Paint().apply {
            color = Color.parseColor("#CCCCCC")
            textSize = 16f * scale
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
        }

        val coordsPaint = Paint().apply {
            color = Color.parseColor("#3A6A6A")
            textSize = 12f * scale
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
        }

        val brandPaint = Paint().apply {
            color = Color.parseColor("#00FFCC")
            textSize = 14f * scale
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            isAntiAlias = true
            alpha = 180
        }

        val bgPaint = Paint().apply {
            color = Color.parseColor("#AA000000")
            style = Paint.Style.FILL
        }

        val barHeight = 100f * scale
        val margin = 16f * scale

        return OverlayPaints(speedPaint, unitPaint, addressPaint, datePaint, coordsPaint, brandPaint, bgPaint, barHeight, margin, scale)
    }

    private fun desenharOverlay(canvas: Canvas, log: LocationLog, w: Int, h: Int, p: OverlayPaints, timeUs: Long) {
        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
        val barTop = h - p.barHeight

        // Fundo semi-transparente na parte inferior
        canvas.drawRect(0f, barTop, w.toFloat(), h.toFloat(), p.bgPaint)

        // Velocidade (grande, à esquerda)
        val speedText = "${log.speed.toInt()}"
        canvas.drawText(speedText, p.margin, barTop + 55f * p.scale, p.speedPaint)

        // KM/H
        val speedWidth = p.speedPaint.measureText(speedText)
        canvas.drawText("KM/H", p.margin + speedWidth + 6f * p.scale, barTop + 55f * p.scale, p.unitPaint)

        // Data e hora (direita superior da barra)
        val dateText = sdf.format(Date(log.timestamp))
        val dateWidth = p.datePaint.measureText(dateText)
        canvas.drawText(dateText, w - p.margin - dateWidth, barTop + 30f * p.scale, p.datePaint)

        // Coordenadas GPS (direita, abaixo da data)
        val coordsText = String.format("%.4f, %.4f", log.latitude, log.longitude)
        val coordsWidth = p.coordsPaint.measureText(coordsText)
        canvas.drawText(coordsText, w - p.margin - coordsWidth, barTop + 48f * p.scale, p.coordsPaint)

        // Endereço (parte inferior da barra, toda a largura)
        val addr = if (log.address.isBlank()) "---" else log.address
        canvas.drawText(addr, p.margin, barTop + 82f * p.scale, p.addressPaint)

        // Branding: "JUSTGUIDE" no canto superior esquerdo
        val brandBgPaint = Paint().apply { color = Color.parseColor("#66000000"); style = Paint.Style.FILL }
        canvas.drawRect(0f, 0f, 220f * p.scale, 30f * p.scale, brandBgPaint)
        canvas.drawText("● JUSTGUIDE", p.margin, 20f * p.scale, p.brandPaint)

        // Indicador REC no canto superior direito (bolinha vermelha)
        val recPaint = Paint().apply { color = Color.RED; isAntiAlias = true }
        canvas.drawCircle(w - 30f * p.scale, 15f * p.scale, 6f * p.scale, recPaint)
        val recTextPaint = Paint().apply { color = Color.WHITE; textSize = 12f * p.scale; typeface = Typeface.MONOSPACE; isAntiAlias = true }
        canvas.drawText("REC", w - 70f * p.scale, 20f * p.scale, recTextPaint)
    }

    // ══════════════════════════════════════════
    // CONVERSÃO YUV ↔ BITMAP
    // ══════════════════════════════════════════

    private fun yuvImageToBitmap(image: android.media.Image, width: Int, height: Int): Bitmap? {
        return try {
            val yBuffer = image.planes[0].buffer
            val uBuffer = image.planes[1].buffer
            val vBuffer = image.planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)
            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            val yuvImage = YuvImage(nv21, android.graphics.ImageFormat.NV21, width, height, null)
            val out = java.io.ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 90, out)
            val bytes = out.toByteArray()
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            Log.e(TAG, "Erro YUV→Bitmap", e)
            null
        }
    }

    private fun bitmapToYuvImage(bitmap: Bitmap, image: android.media.Image) {
        try {
            val width = bitmap.width
            val height = bitmap.height
            val argb = IntArray(width * height)
            bitmap.getPixels(argb, 0, width, 0, 0, width, height)

            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]

            val yBuffer = yPlane.buffer
            val uBuffer = uPlane.buffer
            val vBuffer = vPlane.buffer

            val yRowStride = yPlane.rowStride
            val uvRowStride = uPlane.rowStride
            val uvPixelStride = uPlane.pixelStride

            for (y in 0 until height) {
                for (x in 0 until width) {
                    val pixel = argb[y * width + x]
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF

                    // RGB → YUV BT.601
                    val yVal = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                    yBuffer.put(y * yRowStride + x, yVal.coerceIn(0, 255).toByte())

                    if (y % 2 == 0 && x % 2 == 0) {
                        val uVal = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                        val vVal = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                        val uvIndex = (y / 2) * uvRowStride + (x / 2) * uvPixelStride
                        uBuffer.put(uvIndex, uVal.coerceIn(0, 255).toByte())
                        vBuffer.put(uvIndex, vVal.coerceIn(0, 255).toByte())
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro Bitmap→YUV", e)
        }
    }

    // ══════════════════════════════════════════
    // TELEMETRIA — Carregamento
    // ══════════════════════════════════════════

    private fun findClosestLog(videoSec: Int, startTimestamp: Long, logs: List<LocationLog>): LocationLog? {
        if (logs.isEmpty()) return null
        val targetTimestamp = startTimestamp + (videoSec * 1000L)
        var closest: LocationLog? = null
        var minDiff = Long.MAX_VALUE
        for (log in logs) {
            val diff = kotlin.math.abs(log.timestamp - targetTimestamp)
            if (diff < minDiff) { minDiff = diff; closest = log }
        }
        return closest
    }

    private fun carregarTelemetria(videoPath: String): List<LocationLog> {
        val videoFile = File(videoPath)
        val videoName = videoFile.nameWithoutExtension
        val logsDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "JustGuide/Logs")

        val possiblePaths = mutableListOf<String>()
        possiblePaths.add(videoPath.replace(".mp4", ".json").replace("Movies/JustGuide", "Documents/JustGuide/Logs"))
        possiblePaths.add(File(logsDir, "$videoName.json").absolutePath)

        val sessionMatch = Regex("JustGuide_(\\d+)").find(videoName)
        if (sessionMatch != null) {
            possiblePaths.add(File(logsDir, "JustGuide_${sessionMatch.groupValues[1]}.json").absolutePath)
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
                    val wrapper = Gson().fromJson(json, JsonObject::class.java)
                    if (wrapper.has("dados_telemetria")) {
                        val type = object : TypeToken<List<LocationLog>>() {}.type
                        val logs: List<LocationLog> = Gson().fromJson(wrapper.getAsJsonArray("dados_telemetria"), type)
                        if (logs.isNotEmpty()) return logs
                    }
                } catch (_: Exception) {}
                try {
                    val type = object : TypeToken<List<LocationLog>>() {}.type
                    val logs: List<LocationLog> = Gson().fromJson(json, type)
                    if (logs.isNotEmpty()) return logs
                } catch (_: Exception) {}
            } catch (_: Exception) {}
        }
        return emptyList()
    }
}
