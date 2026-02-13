package com.milton.justguide

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.lifecycle.MutableLiveData
import com.google.android.gms.location.*
import kotlin.math.roundToInt

// ESTRUTURA UNIFICADA PARA PERÍCIA (LocationLog)



// Objeto singleton para gerenciar o estado da localização
object LocationData {
    val currentLocation = MutableLiveData<Location>()
    val currentSpeedKmH = MutableLiveData<Int>()
    val isRecordingLog = MutableLiveData(false)
    val tripLog = ArrayList<LocationLog>() // UNIFICADO PARA LocationLog
    val totalDistanceKm = MutableLiveData(0f)
    val totalTripTimeSeconds = MutableLiveData(0L)
}

class LocationService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback

    private val CHANNEL_ID = "location_channel"
    private val NOTIFICATION_ID = 1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createLocationRequest()
        createLocationCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = createNotification()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        startLocationUpdates()
        return START_STICKY
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MapActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Just Guide Ativo")
            .setContentText("Monitorando sua viagem e GPS em tempo real")
            .setSmallIcon(R.drawable.logo_app)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Canal de Monitoramento Just Guide",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun createLocationRequest() {
        locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L)
            .setMinUpdateIntervalMillis(2000L)
            .build()
    }

    private fun createLocationCallback() {
        locationCallback = object : LocationCallback() {
            private var lastLocation: Location? = null
            private var totalDistanceMeters = 0f
            private var startTimeMillis = 0L

            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    LocationData.currentLocation.postValue(location)

                    val speedKmH = if (location.hasSpeed()) (location.speed * 3.6).roundToInt() else 0
                    LocationData.currentSpeedKmH.postValue(speedKmH)

                    if (lastLocation == null) {
                        lastLocation = location
                        startTimeMillis = System.currentTimeMillis()
                    } else {
                        totalDistanceMeters += lastLocation!!.distanceTo(location)
                        lastLocation = location
                    }

                    val elapsedTimeSeconds = (System.currentTimeMillis() - startTimeMillis) / 1000
                    LocationData.totalDistanceKm.postValue(totalDistanceMeters / 1000f)
                    LocationData.totalTripTimeSeconds.postValue(elapsedTimeSeconds)

                    // CORREÇÃO CIRÚRGICA: Adicionando LocationLog em vez de TripLogEntry
                    if (LocationData.isRecordingLog.value == true) {
                        LocationData.tripLog.add(LocationLog(
                            latitude = location.latitude,
                            longitude = location.longitude,
                            speed = speedKmH.toDouble(),
                            address = "", // Preenchido dinamicamente na MapActivity se necessário
                            timestamp = System.currentTimeMillis()
                        ))
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
    }

    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    override fun onDestroy() {
        stopLocationUpdates()
        super.onDestroy()
    }
}