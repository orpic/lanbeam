package dev.lanbeam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import kotlin.concurrent.thread

/** Keeps the server alive while the app is in the background, with a visible notification. */
class ServerService : Service() {
    private var server: LanServer? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        startForeground(1, notification("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (server == null) thread(name = "lanbeam-start") { startServer() }
        return START_NOT_STICKY
    }

    private var hotspot: Hotspot? = null
    private var scanner: PeerScanner? = null

    private fun startServer() {
        Dbg.init(this)
        val mode = Hub.state.value.mode
        try {
            val lan: LanAddress
            if (mode == "direct") {
                Hub.state.value = Hub.state.value.copy(error = null, link = "Starting hotspot…")
                val before = Net.allLanAddresses().map { it.address }.toSet()
                val hs = Hotspot(this).also { hotspot = it }
                val info = hs.start()
                lan = Hotspot.awaitApAddress(before) ?: throw IllegalStateException("Hotspot started but has no address.")
                Hub.state.value = Hub.state.value.copy(
                    wifiQr = Hotspot.wifiQr(info.ssid, info.passphrase),
                    hotspotName = info.ssid,
                    link = "Direct hotspot · ${LinkInfo.apBand(lan.iface)} · ${lan.iface}",
                    advice = LinkInfo.directAdvice(this),
                )
                Dbg.log("hotspot ${info.ssid} requested=${info.requestedBand} on ${lan.iface} ${lan.address.hostAddress}")
            } else {
                lan = Net.wifiClientAddress() ?: run {
                    Hub.state.value = Hub.state.value.copy(running = false, error = "Not connected to Wi-Fi. Connect to a network, or use Direct mode.")
                    stopSelf(); return
                }
                val li = LinkInfo.client(this)
                Hub.state.value = Hub.state.value.copy(wifiQr = null, hotspotName = null, link = li.text, advice = li.advice)
            }
            val ssl = TlsIdentity.sslContext(this, lan.address)
            Hub.state.value = Hub.state.value.copy(certFp = TlsIdentity.fingerprint)
            val s = LanServer(this, lan, ssl)
            s.start()
            server = s
            scanner = PeerScanner(lan, sweep = mode == "direct").also { it.start() }
            Hub.baseUrl = s.baseUrl
            Hub.state.value = Hub.state.value.copy(running = true, baseUrl = s.baseUrl, error = null)
            Hub.newPairUrl()
            Hub.publish()
            acquireLocks()
            getSystemService(NotificationManager::class.java).notify(1, notification("Sharing on ${lan.iface} · ${s.baseUrl}"))
            Dbg.log("serving ${s.baseUrl} on ${lan.iface}/${lan.prefix} mode=$mode")
        } catch (e: Exception) {
            Log.e("LanBeam", "start failed", e)
            hotspot?.stop(); hotspot = null
            Hub.state.value = Hub.state.value.copy(running = false, error = "Could not start: ${e.message}")
            stopSelf()
        }
    }

    private fun acquireLocks() {
        val wm = applicationContext.getSystemService(WifiManager::class.java)
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "lanbeam").apply { setReferenceCounted(false); acquire() }
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lanbeam:server").apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
    }

    override fun onDestroy() {
        scanner?.stop(); scanner = null
        server?.stop(); server = null
        hotspot?.stop(); hotspot = null
        runCatching { wifiLock?.release() }; runCatching { wakeLock?.release() }
        // Files shared in this session are not offered to whoever pairs next time; let go of their read access too.
        Hub.offers.forEach { o -> runCatching { contentResolver.releasePersistableUriPermission(o.uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        Hub.offers.clear()
        Hub.reset()
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Sharing", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ServerService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_lanbeam)
            .setContentTitle("LanBeam is sharing")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "sharing"
        const val ACTION_STOP = "dev.lanbeam.STOP"
        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, ServerService::class.java))
        fun stop(ctx: Context) = ctx.startService(Intent(ctx, ServerService::class.java).setAction(ACTION_STOP))
    }
}
