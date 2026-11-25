package io.github.patschgoo.anibox

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import io.github.patschgoo.anibox.data.AppSettings
import io.github.patschgoo.anibox.debug.DebugLog
import io.github.patschgoo.anibox.data.DeviceIdentity
import io.github.patschgoo.anibox.data.SyncRepository
import io.github.patschgoo.anibox.net.Credentials
import io.github.patschgoo.anibox.net.HostResolver
import io.github.patschgoo.anibox.net.Link
import io.github.patschgoo.anibox.net.NetBios
import io.github.patschgoo.anibox.net.ShareAccess
import io.github.patschgoo.anibox.sync.ProgressStore
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The app's parts, made once per process. */
class AppGraph(context: Context) {
    private val app = context.applicationContext
    val settings = AppSettings(app)
    val credentials = Credentials(java.io.File(app.filesDir, "signin.json"))
    val store = ProgressStore(java.io.File(app.filesDir, "progress"))
    val resolver = HostResolver(
        saved = { settings.address(it) },
        dns = { dnsLookup(it) },
        netbios = { NetBios.lookup(it, broadcastAddresses(app)) },
        reachable = { ShareAccess.answers(it) },
    )
    val shares = ShareAccess(credentials, settings, resolver, link = { link(app) })
    val sync = SyncRepository(
        app.filesDir, settings, credentials, store, shares,
        DeviceIdentity.journalName(app.filesDir, Build.MODEL ?: "phone"),
    )

    companion object {
        private val dnsPool = Executors.newCachedThreadPool { r -> Thread(r, "anibox-dns").apply { isDaemon = true } }

        /** Android's own name lookup, given up after 3 s (a name it can't find can hang much longer). */
        fun dnsLookup(name: String): List<String> = try {
            dnsPool.submit(Callable { InetAddress.getAllByName(name).filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress } })
                .get(3, TimeUnit.SECONDS)
        } catch (_: Exception) {
            emptyList()
        }

        /** Wi-Fi, mobile data or none, and whether a VPN (Tailscale) is on: only for the wording of "doesn't answer". */
        fun link(context: Context): Link {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return Link.UNKNOWN
            val active = cm.activeNetwork ?: return Link(Link.Kind.NONE)
            val caps = cm.getNetworkCapabilities(active) ?: return Link.UNKNOWN
            val vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            var kind = kindOf(caps)
            if (vpn && kind == Link.Kind.OTHER) {
                // The VPN's own entry may not say what it runs over: look at the phone's other networks.
                @Suppress("DEPRECATION")
                val under = cm.allNetworks.mapNotNull { cm.getNetworkCapabilities(it) }
                    .filterNot { it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }.map { kindOf(it) }
                kind = when {
                    Link.Kind.WIFI in under -> Link.Kind.WIFI
                    Link.Kind.MOBILE in under -> Link.Kind.MOBILE
                    else -> Link.Kind.OTHER
                }
            }
            return Link(kind, vpn)
        }

        private fun kindOf(caps: NetworkCapabilities): Link.Kind = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Link.Kind.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Link.Kind.MOBILE
            else -> Link.Kind.OTHER
        }

        /** The broadcast address of each IPv4 network this phone is on (Wi-Fi), then 255.255.255.255. */
        fun broadcastAddresses(context: Context): List<InetAddress> {
            val out = ArrayList<InetAddress>()
            try {
                val cm = context.getSystemService(ConnectivityManager::class.java)
                val lp = cm?.getLinkProperties(cm.activeNetwork)
                for (la in lp?.linkAddresses.orEmpty()) {
                    val a = la.address as? Inet4Address ?: continue
                    val prefix = la.prefixLength
                    if (prefix !in 8..30) continue
                    val ip = a.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
                    val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
                    val bc = ip or mask.inv()
                    out += InetAddress.getByAddress(byteArrayOf((bc ushr 24).toByte(), (bc ushr 16).toByte(), (bc ushr 8).toByte(), bc.toByte()))
                }
            } catch (_: Exception) {
            }
            out += InetAddress.getByName("255.255.255.255")
            return out
        }
    }
}

class AniBoxApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        DebugLog.init(java.io.File(filesDir, "logs"))
        DebugLog.i(
            "app",
            "AniBox ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) started on ${Build.MANUFACTURER} ${Build.MODEL}, " +
                "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.SUPPORTED_ABIS.joinToString(",")}",
        )
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            DebugLog.e("app", "Crash in ${t.name}: ${e.stackTraceToString().take(4000)}")
            DebugLog.files() // waits until the line is on disk
            previous?.uncaughtException(t, e)
        }
        graph = AppGraph(this)
    }
}

val Context.graph: AppGraph get() = (applicationContext as AniBoxApp).graph
