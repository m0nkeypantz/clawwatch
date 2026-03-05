package com.clawwatch.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private const val TAG = "LocationProvider"
private const val CACHE_TTL_MS = 60_000L // 60 seconds

class LocationProvider(private val context: Context) {

    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    // Location cache
    private var cachedLocation: String? = null
    private var cacheTimestamp = 0L

    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Get the device's last known location as a formatted string.
     * Returns cached value if less than 60 seconds old.
     * Returns null if permission denied or location unavailable.
     */
    @SuppressLint("MissingPermission")
    suspend fun getLocationString(): String? {
        if (!hasPermission()) return null

        // Return cached if fresh
        val now = System.currentTimeMillis()
        val cached = cachedLocation
        if (cached != null && (now - cacheTimestamp) < CACHE_TTL_MS) {
            return cached
        }

        return try {
            val location = suspendCoroutine<Location?> { cont ->
                val cts = CancellationTokenSource()
                fusedClient.getCurrentLocation(
                    Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                    cts.token
                ).addOnSuccessListener { loc ->
                    cont.resume(loc)
                }.addOnFailureListener {
                    cont.resume(null)
                }
            }
            if (location != null) {
                val result = formatLocation(location)
                cachedLocation = result
                cacheTimestamp = now
                result
            } else {
                // Fallback to last known
                val last = suspendCoroutine<Location?> { cont ->
                    fusedClient.lastLocation
                        .addOnSuccessListener { loc -> cont.resume(loc) }
                        .addOnFailureListener { cont.resume(null) }
                }
                if (last != null) {
                    val result = formatLocation(last)
                    cachedLocation = result
                    cacheTimestamp = now
                    result
                } else {
                    cachedLocation // return stale cache if available
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Location error: ${e.message}")
            cachedLocation // return stale cache on error
        }
    }

    private fun formatLocation(location: Location): String {
        val lat = "%.4f".format(location.latitude)
        val lon = "%.4f".format(location.longitude)
        return "[Location: $lat, $lon]"
    }
}
