package eus.argos.nativo

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import eus.argos.core.Ask
import eus.argos.core.BleAd
import eus.argos.core.CampoRadio
import eus.argos.core.VigiaGnss

/**
 * El módulo de radio en el teléfono: escucha Bluetooth LE y el estado de los
 * satélites. No emite, no se conecta a nada y no guarda nada en disco.
 *
 * Viene APAGADO y solo se enciende a mano. Android pide permiso de ubicación
 * para escanear Bluetooth y para ver los satélites; la posición que da el GPS
 * se descarta en cuanto llega: solo hace falta que el receptor esté en marcha
 * para que informe de la intensidad de cada satélite.
 */
class RadioVigia(private val ctx: Context) {
    private var campo = CampoRadio()
    private var gnss = VigiaGnss()
    @Volatile var activa = false; private set
    @Volatile var gnssEnMarcha = false; private set
    @Volatile var bleEnMarcha = false; private set
    private val t0 = SystemClock.elapsedRealtime()
    private fun ahora() = (SystemClock.elapsedRealtime() - t0) / 1000.0
    private val principal = Handler(Looper.getMainLooper())

    fun permisos(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.ACCESS_FINE_LOCATION,
                                                 Manifest.permission.ACCESS_COARSE_LOCATION)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun tiene(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    private val ble = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, r: ScanResult) {
            if (!activa) return
            val dir = r.device?.address ?: return
            val a = BleAd.parse(r.scanRecord?.bytes)
            synchronized(this@RadioVigia) { campo.oye(dir, r.rssi, a, ahora()) }
        }
        override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach { onScanResult(0, it) } }
        override fun onScanFailed(errorCode: Int) { Log.w(TAG, "ARGOS radio: escaneo BLE falló ($errorCode)"); bleEnMarcha = false }
    }

    private val sats = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(st: GnssStatus) {
            val l = (0 until st.satelliteCount).map { VigiaGnss.Sat(st.getConstellationType(it) * 1000 + st.getSvid(it), st.getCn0DbHz(it).toDouble()) }
            synchronized(this@RadioVigia) { gnss.epoca(l, ahora()) }
        }
    }

    // La posición llega porque el receptor está en marcha; no se usa ni se guarda.
    private val pos = object : LocationListener {
        override fun onLocationChanged(location: Location) {}
        @Deprecated("API antigua") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
    }

    /** Enciende lo que se pueda. Devuelve qué no se pudo, dicho para el panel; null si todo va. */
    @SuppressLint("MissingPermission")
    fun empieza(): String? {
        if (activa) return null
        campo = CampoRadio(); gnss = VigiaGnss()
        activa = true
        val problemas = ArrayList<String>()
        val puedeBle = if (Build.VERSION.SDK_INT >= 31) tiene(Manifest.permission.BLUETOOTH_SCAN) else tiene(Manifest.permission.ACCESS_FINE_LOCATION)
        if (!puedeBle) problemas.add("sin permiso de Bluetooth")
        else {
            val adaptador = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            val scanner = if (adaptador?.isEnabled == true) adaptador.bluetoothLeScanner else null
            if (scanner == null) problemas.add("Bluetooth apagado")
            else try {
                val ajustes = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                // Remote ID en Bluetooth 5 viaja en anuncios EXTENDIDOS; el modo
                // heredado no los entrega. Solo donde el chip los admite.
                if (Build.VERSION.SDK_INT >= 26 && adaptador?.isLeExtendedAdvertisingSupported == true)
                    ajustes.setLegacy(false).setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
                scanner.startScan(null, ajustes.build(), ble)
                bleEnMarcha = true
            } catch (e: Exception) { problemas.add("Bluetooth: ${e.message}") }
        }
        if (!tiene(Manifest.permission.ACCESS_FINE_LOCATION)) problemas.add("sin permiso para ver los satélites")
        else {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (lm == null || !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) problemas.add("GPS apagado")
            else try {
                lm.registerGnssStatusCallback(sats, principal)
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, pos, Looper.getMainLooper())
                gnssEnMarcha = true
            } catch (e: Exception) { problemas.add("GNSS: ${e.message}") }
        }
        Log.i(TAG, "ARGOS radio: encendida · BLE ${if (bleEnMarcha) "sí" else "no"} · GNSS ${if (gnssEnMarcha) "sí" else "no"}" +
                   (if (problemas.isEmpty()) "" else " · " + problemas.joinToString(", ")))
        return problemas.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    @SuppressLint("MissingPermission")
    fun para() {
        if (!activa) return
        activa = false
        runCatching {
            (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.bluetoothLeScanner?.stopScan(ble)
        }
        runCatching {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            lm?.unregisterGnssStatusCallback(sats); lm?.removeUpdates(pos)
        }
        bleEnMarcha = false; gnssEnMarcha = false
        // Apagar es olvidar: ni los resúmenes de dirección quedan.
        synchronized(this) { campo.olvida() }
    }

    private fun textoGnss(): String = when {
        !gnssEnMarcha -> "sin datos"
        else -> when (gnss.estado) {
            VigiaGnss.Estado.APRENDIENDO -> "aprendiendo el cielo"
            VigiaGnss.Estado.NORMAL -> "normal"
            VigiaGnss.Estado.INTERFERENCIA -> "POSIBLE INTERFERENCIA"
            VigiaGnss.Estado.SUPLANTACION -> "POSIBLE SUPLANTACIÓN"
        }
    }

    /** Para el chat. null si está apagada. */
    fun vivo(): Ask.RadioLive? = if (!activa) null else synchronized(this) {
        val e = campo.estado(ahora())
        Ask.RadioLive(
            e.aparatos,
            e.porClase.filterKeys { it.rastreador }.mapKeys { it.key.texto },
            e.drones.map { d ->
                listOfNotNull(d.tipo ?: "dron", d.serie,
                    d.alturaM.takeIf { it.isFinite() }?.let {
                        "a %.0f m %s".format(it, if (d.sobreSuelo) "sobre el suelo" else "sobre su despegue") },
                    d.velocidadMs.takeIf { it.isFinite() }?.let { "%.0f km/h".format(it * 3.6) }).joinToString(" ")
            },
            textoGnss(),
            // Lo más reciente primero: los de campo ya vienen así; del GNSS,
            // solo los de los últimos 10 minutos.
            e.avisos.map { it.texto } +
                (if (!gnss.ultimoAvisoT.isNaN() && ahora() - gnss.ultimoAvisoT <= 600) gnss.avisos.reversed() else emptyList()))
    }

    /** Una línea para el panel. */
    fun linea(): String {
        if (!activa) return ""
        val v = vivo() ?: return ""
        val rast = v.rastreadores.values.sum()
        return "radio · " + (if (bleEnMarcha) "${v.aparatos} aparatos · $rast rastreador${if (rast == 1) "" else "es"} · " +
            "${v.drones.size} dron${if (v.drones.size == 1) "" else "es"}" else "Bluetooth parado") +
            " · GPS ${v.gnss}" + (v.avisos.firstOrNull()?.let { "\n⚠ $it" } ?: "")
    }

    /** Rojo en la barra: un aviso de los últimos 10 min o el GNSS en alarma AHORA. */
    fun hayAviso(): Boolean = vivo()?.let { v -> v.avisos.isNotEmpty() || v.gnss.startsWith("POSIBLE") } ?: false
}
