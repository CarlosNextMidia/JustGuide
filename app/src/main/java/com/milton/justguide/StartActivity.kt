package com.milton.justguide

import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.milton.justguide.databinding.ActivityStartBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class StartActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStartBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStartBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestAppPermissions()

        binding.cardStartTrip.setOnClickListener {
            val destinoDigitado = binding.etDestination.text.toString()
            val intent = Intent(this, MapActivity::class.java).apply {
                putExtra("EXTRA_DESTINO", destinoDigitado)
            }
            startActivity(intent)
        }

        binding.cardRecover.setOnClickListener {
            refreshAndShowHistory()
        }
    }

    private fun requestAppPermissions() {
        val permissions = mutableListOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(android.Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            permissions.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            permissions.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
    }

    private fun refreshAndShowHistory() {
        val moviesDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "JustGuide")
        if (!moviesDir.exists()) moviesDir.mkdirs()

        MediaScannerConnection.scanFile(this, arrayOf(moviesDir.absolutePath), null) { _, _ ->
            runOnUiThread { showTripHistoryDialog() }
        }
    }

    private fun showTripHistoryDialog() {
        val moviesDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "JustGuide")
        val videoFiles = moviesDir.listFiles { f -> f.extension == "mp4" && f.name.startsWith("JustGuide_") }?.sortedByDescending { it.lastModified() }

        if (videoFiles.isNullOrEmpty()) {
            Toast.makeText(this, "Nenhuma viagem encontrada.", Toast.LENGTH_SHORT).show()
            return
        }

        val tripNames = videoFiles.map { file ->
            val duration = getVideoDuration(file)
            val start = file.lastModified()
            val end = start + duration
            val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            val dateSdf = SimpleDateFormat("dd/MM/yy", Locale.getDefault())
            "${dateSdf.format(Date(start))} | ${sdf.format(Date(start))} às ${sdf.format(Date(end))} (${duration / 60000} min)"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Selecione a Viagem")
            .setItems(tripNames) { _, which -> showMinuteInputDialog(videoFiles[which]) }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun getVideoDuration(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
        } catch (e: Exception) { 0L } finally { retriever.release() }
    }

    private fun showMinuteInputDialog(videoFile: File) {
        val options = arrayOf("Assistir na Sala de Perícia", "Cortar Prova (40s)")

        AlertDialog.Builder(this)
            .setTitle("O que deseja fazer?")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> { // ABRE A SALA DE VÍDEO COM CARIMBO
                        val intent = Intent(this, EvidenceActivity::class.java).apply {
                            putExtra("VIDEO_PATH", videoFile.absolutePath)
                        }
                        startActivity(intent)
                    }
                    1 -> { // MANTÉM A FUNÇÃO DE CORTE QUE JÁ FUNCIONA
                        val input = EditText(this)
                        input.hint = "Minuto do incidente"
                        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER
                        AlertDialog.Builder(this)
                            .setTitle("Gerar Prova")
                            .setView(input)
                            .setPositiveButton("GERAR") { _, _ ->
                                val intent = Intent(this, MapActivity::class.java).apply {
                                    putExtra("EXTRA_VIDEO_PATH", videoFile.absolutePath)
                                    putExtra("EXTRA_INCIDENT_SEC", (input.text.toString().toIntOrNull() ?: 0) * 60)
                                }
                                startActivity(intent)
                            }.show()
                    }
                }
            }.show()
    }
    }
