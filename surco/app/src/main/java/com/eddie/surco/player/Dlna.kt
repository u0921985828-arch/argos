package com.eddie.surco.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import com.eddie.surco.data.HttpCall
import com.eddie.surco.data.SoapRequest
import com.eddie.surco.data.SoapResult
import com.eddie.surco.data.Upnp
import com.eddie.surco.data.UpnpDevice
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * La red local por la que se habla con los aparatos: la dirección IPv4 del móvil en la wifi (o
 * ethernet, o su propio punto de acceso) y la red de Android, para atar a ella los sockets. Sin
 * atarlos, con datos móviles por defecto y una wifi «sin internet», lo local saldría por datos.
 */
class Lan(val address: Inet4Address, val network: Network?) {
    /** Socket sin conectar por esta red (para HttpCall y Upnp.send). */
    fun newSocket(): Socket = network?.let { n -> runCatching { n.socketFactory.createSocket() }.getOrNull() } ?: Socket()

    companion object {
        /** La red local de ahora, o null si no hay wifi. Hace falta ACCESS_NETWORK_STATE. */
        fun find(ctx: Context): Lan? {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val net = cm?.activeNetwork
            val caps = net?.let { cm.getNetworkCapabilities(it) }
            if (net != null && caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            ) {
                cm.getLinkProperties(net)?.linkAddresses?.map { it.address }?.filterIsInstance<Inet4Address>()
                    ?.firstOrNull { !it.isLoopbackAddress }?.let { return Lan(it, net) }
            }
            // Con VPN, con datos por defecto o siendo el móvil el punto de acceso: la interfaz a mano.
            val ifaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull().orEmpty()
            return ifaces.filter { i ->
                runCatching { i.isUp && !i.isLoopback }.getOrDefault(false) &&
                    listOf("wlan", "eth", "ap", "swlan", "softap").any { i.name.startsWith(it) }
            }.flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }?.let { Lan(it, null) }
        }
    }
}

/**
 * Aparatos DLNA de la wifi. La hoja «Enviar a» pide buscar mientras está abierta: una ronda de
 * SSDP de ~3 s, y otra cada 10 s (hay teles que tardan en despertar la red). Todo fuera del hilo
 * principal; la lista es un StateFlow. Un aparato que deja de contestar tres rondas se quita.
 */
object Dlna {
    private val _devices = MutableStateFlow<List<UpnpDevice>>(emptyList())
    val devices: StateFlow<List<UpnpDevice>> = _devices.asStateFlow()
    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var watchers = 0
    private var round = 0
    // Por LOCATION: el aparato y la última ronda en que contestó.
    private val known = HashMap<String, Pair<UpnpDevice, Int>>()

    /** Desde el hilo principal (la hoja al abrirse). */
    fun startSearching(ctx: Context) {
        watchers++
        if (job?.isActive == true) return
        val app = ctx.applicationContext
        job = scope.launch {
            // Dos minutos como mucho: con la hoja olvidada abierta no se gasta batería sin fin.
            repeat(12) {
                searchOnce(app)
                delay(7_000)
            }
        }
    }

    fun stopSearching() {
        watchers = (watchers - 1).coerceAtLeast(0)
        if (watchers == 0) { job?.cancel(); job = null; _searching.value = false }
    }

    private suspend fun searchOnce(ctx: Context) {
        val lan = Lan.find(ctx)
        if (lan == null) { synchronized(known) { known.clear() }; _devices.value = emptyList(); return }
        _searching.value = true
        val wifi = ctx.getSystemService(WifiManager::class.java)
        // Sin este cerrojo, muchos móviles tiran los paquetes multidifusión para ahorrar batería.
        val lock = runCatching { wifi?.createMulticastLock("surco-dlna")?.apply { setReferenceCounted(false); acquire() } }.getOrNull()
        val locations = LinkedHashSet<String>()
        try {
            MulticastSocket(InetSocketAddress(lan.address, 0)).use { s ->
                runCatching { s.networkInterface = NetworkInterface.getByInetAddress(lan.address) }
                runCatching { s.timeToLive = 4 }
                runCatching { lan.network?.bindSocket(s) }
                Upnp.search(s, InetSocketAddress(Upnp.SSDP_HOST, Upnp.SSDP_PORT), Upnp.mSearch(mx = 2), 3_000) { locations += it.location }
            }
        } catch (e: Exception) {
            // Sin red a mitad, o un móvil que no deja abrir el socket: esta ronda sin resultados.
        } finally {
            runCatching { lock?.release() }
        }
        val r = ++round
        // Las descripciones a la vez, cada una con su plazo corto: una tele dormida no frena a las demás.
        val fresh = coroutineScopeAsync(locations.toList()) { loc ->
            val cached = synchronized(known) { known[loc]?.first }
            cached ?: runCatching {
                val reply = HttpCall.request("GET", loc, timeoutMs = 2_500, newSocket = lan::newSocket)
                if (reply.code == 200) Upnp.parseDescription(reply.text(), loc) else null
            }.getOrNull()
        }
        synchronized(known) {
            fresh.forEach { (loc, d) -> if (d != null) known[loc] = d to r }
            known.entries.removeAll { r - it.value.second >= 3 }
            // Un aparato puede anunciarse por dos direcciones: uno por UDN.
            _devices.value = known.values.map { it.first }.distinctBy { it.udn }.sortedBy { it.name.lowercase() }
        }
        _searching.value = false
    }

    private suspend fun coroutineScopeAsync(locs: List<String>, f: (String) -> UpnpDevice?): List<Pair<String, UpnpDevice?>> =
        kotlinx.coroutines.coroutineScope { locs.map { l -> async(Dispatchers.IO) { l to f(l) } }.awaitAll() }

    /** SOAP a un aparato por la red local, con plazo corto. Para llamar fuera del hilo principal. */
    fun call(lan: Lan, req: SoapRequest, timeoutMs: Int = 3_000): SoapResult = Upnp.send(req, timeoutMs, lan::newSocket)
}
