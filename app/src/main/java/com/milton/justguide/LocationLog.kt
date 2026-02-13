package com.milton.justguide

data class LocationLog(
    val latitude: Double,
    val longitude: Double,
    val speed: Double,
    val address: String,
    val timestamp: Long
)