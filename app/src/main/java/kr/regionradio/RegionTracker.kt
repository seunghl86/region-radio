package kr.regionradio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper

/** 위치를 따라가며 가장 가까운 송신 지역으로 바꾼다. */
object RegionTracker : LocationListener {
    private const val MARGIN_KM = 5.0 // 경계에서 왔다 갔다 하지 않도록 두는 여유
    private var lm: LocationManager? = null

    fun start(ctx: Context) {
        val app = ctx.applicationContext
        val fine = app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return
        val m = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm = m
        try {
            m.removeUpdates(this)
            val providers = m.allProviders
            for (p in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)) {
                if (!providers.contains(p)) continue
                if (p == LocationManager.GPS_PROVIDER && !fine) continue
                m.requestLocationUpdates(p, 60_000L, 1_000f, this, Looper.getMainLooper())
                m.getLastKnownLocation(p)?.let { onLocationChanged(it) }
            }
        } catch (e: SecurityException) {
            // 권한이 없으면 수동 선택으로 남는다
        } catch (e: IllegalArgumentException) {
        }
    }

    fun stop() {
        try {
            lm?.removeUpdates(this)
        } catch (e: Exception) {
        }
    }

    override fun onLocationChanged(loc: Location) {
        if (!RadioState.auto) return
        val cur = RadioState.region
        val near = Catalog.nearest(loc.latitude, loc.longitude)
        if (near.id == cur.id) return
        val dCur = Catalog.distanceKm(loc.latitude, loc.longitude, cur.lat, cur.lon)
        val dNew = Catalog.distanceKm(loc.latitude, loc.longitude, near.lat, near.lon)
        if (dNew + MARGIN_KM < dCur) RadioState.changeRegion(near)
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    @Deprecated("구형 기기용")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
    }
}
