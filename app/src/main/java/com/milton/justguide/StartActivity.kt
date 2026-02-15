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

    // ══════════════════════════════════════════
    // HISTÓRICO DE VIAGENS
    // ══════════════════════════════════════════

    private fun showTripHistoryDialog() {
        val moviesDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "JustGuide")
        val videoFiles = moviesDir.listFiles { f ->
            f.extension == "mp4" && f.name.startsWith("JustGuide_")
        }?.sortedByDescending { it.lastModified() }

        if (videoFiles.isNullOrEmpty()) {
            Toast.makeText(this, "Nenhuma viagem encontrada.", Toast.LENGTH_SHORT).show()
            return
        }

        val sdfDate = SimpleDateFormat("dd/MM/yy", Locale.getDefault())
        val sdfTime = SimpleDateFormat("HH:mm", Locale.getDefault())
        val recentFiles = videoFiles.take(30)

        val tripNames = recentFiles.map { file ->
            val date = sdfDate.format(Date(file.lastModified()))
            val time = sdfTime.format(Date(file.lastModified()))
            val duration = getVideoDuration(file)
            val durationMin = duration / 60000
            val sizeMb = String.format("%.1f", file.length() / (1024.0 * 1024.0))
            "$date  $time  (${durationMin}min | ${sizeMb}MB)"
        }.toTypedArray()

        val builder = AlertDialog.Builder(this)
            .setTitle("Viagens (${recentFiles.size}/${videoFiles.size})")
            .setItems(tripNames) { _, which -> showActionDialog(recentFiles[which], tripNames[which]) }
            .setNegativeButton("Fechar", null)

        if (videoFiles.size > 10) {
            builder.setNeutralButton("Limpar antigas") { _, _ -> mostrarDialogoLimpeza(videoFiles.toList()) }
        }

        builder.show()
    }

    private fun showActionDialog(videoFile: File, tripLabel: String) {
        val options = arrayOf(
            "Abrir na Sala de Perícia",
            "Cortar Prova (40s)",
            "Excluir esta viagem"
        )

        AlertDialog.Builder(this)
            .setTitle(tripLabel)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        startActivity(Intent(this, EvidenceActivity::class.java).apply {
                            putExtra("VIDEO_PATH", videoFile.absolutePath)
                        })
                    }
                    1 -> {
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
                    2 -> confirmarExclusao(videoFile)
                }
            }.show()
    }

    // ══════════════════════════════════════════
    // EXCLUSÃO E LIMPEZA
    // ══════════════════════════════════════════

    private fun confirmarExclusao(videoFile: File) {
        AlertDialog.Builder(this)
            .setTitle("Excluir viagem?")
            .setMessage("O vídeo e o log serão removidos.\n\n${videoFile.name}")
            .setPositiveButton("EXCLUIR") { _, _ ->
                excluirViagem(videoFile)
                Toast.makeText(this, "Viagem excluída", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun excluirViagem(videoFile: File) {
        if (videoFile.exists()) videoFile.delete()
        val logsDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "JustGuide/Logs")
        val jsonFile = File(logsDir, videoFile.nameWithoutExtension + ".json")
        if (jsonFile.exists()) jsonFile.delete()
        val sessionMatch = Regex("JustGuide_(\\d+)").find(videoFile.nameWithoutExtension)
        if (sessionMatch != null) {
            val sessionJson = File(logsDir, "JustGuide_${sessionMatch.groupValues[1]}.json")
            if (sessionJson.exists()) sessionJson.delete()
        }
        MediaScannerConnection.scanFile(this, arrayOf(videoFile.absolutePath), null, null)
    }

    private fun mostrarDialogoLimpeza(allFiles: List<File>) {
        val totalSizeMb = allFiles.sumOf { it.length() } / (1024.0 * 1024.0)
        val options = arrayOf(
            "Manter últimas 10 viagens",
            "Manter últimas 20 viagens",
            "Manter último mês",
            "Excluir TODAS"
        )

AlertDialog.Builder(this)
            .setTitle("Limpar — ${allFiles.size} viagens (${String.format("%.0f", totalSizeMb)} MB)")
            .setItems(options) { _, which ->
                val sorted = allFiles.sortedByDescending { it.lastModified() }
                val filesToDelete = when (which) {
                    0 -> sorted.drop(10)
                    1 -> sorted.drop(20)
                    2 -> {
                        val limite = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
                        allFiles.filter { it.lastModified() < limite }
                    }
                    3 -> allFiles.toList()
                    else -> emptyList()
                }
                if (filesToDelete.isEmpty()) {
                    Toast.makeText(this, "Nada para limpar", Toast.LENGTH_SHORT).show()
                    return@setItems
                }
                val delSizeMb = filesToDelete.sumOf { it.length() } / (1024.0 * 1024.0)
                AlertDialog.Builder(this)
                    .setTitle("Confirmar")
                    .setMessage("Excluir ${filesToDelete.size} viagens (${String.format("%.0f", delSizeMb)} MB)?")
                    .setPositiveButton("EXCLUIR") { _, _ ->
                        filesToDelete.forEach { excluirViagem(it) }
                        Toast.makeText(this, "${filesToDelete.size} viagens excluídas (${String.format("%.0f", delSizeMb)} MB)", Toast.LENGTH_LONG).show()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }.show()
    }

    private fun getVideoDuration(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
        } catch (e: Exception) { 0L } finally { retriever.release() }
    }
}
