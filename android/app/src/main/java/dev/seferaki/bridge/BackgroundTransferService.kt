package dev.seferaki.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.MediaStore
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** Keeps the paired Mac reachable while the app UI is closed and handles incoming file offers. */
class BackgroundTransferService : Service() {
    private lateinit var client: BridgeClient
    private val executor = Executors.newSingleThreadExecutor()
    private val transferExecutor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private val receiving = mutableSetOf<String>()
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        client = BridgeClient(this)
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ACCEPT -> intent.getStringExtra(EXTRA_TRANSFER_ID)?.let { transferExecutor.execute { receive(it) } }
            ACTION_REJECT -> intent.getStringExtra(EXTRA_TRANSFER_ID)?.let { id -> transferExecutor.execute { runCatching { client.requestCancellation(id) }; removeOfferNotification(id) } }
            ACTION_STOP -> { enabled = false; stopService() }
            else -> startIfAllowed()
        }
        return if (running.get()) START_STICKY else START_NOT_STICKY
    }

    private fun startIfAllowed() {
        if (running.get()) return
        if (client.pair == null) { enabled = false; stopSelf(); return }
        if (Build.VERSION.SDK_INT >= 37 && checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            stopSelf(); return
        }
        try {
            val notification = serviceNotification("Подключение к Mac…")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(SERVICE_NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(SERVICE_NOTIFICATION, notification)
            running.set(true); isRunning = true; enabled = true
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SamsungMacBridge:receive").apply { setReferenceCounted(false); acquire() }
            wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).createWifiLock(WifiManager.WIFI_MODE_FULL, "SamsungMacBridge:receive-wifi").apply { setReferenceCounted(false); acquire() }
            executor.execute { pollLoop() }
        } catch (error: Exception) {
            updateServiceNotification("Фоновый приём не запущен · откройте приложение")
            stopSelf()
        }
    }

    private fun pollLoop() {
        var delay = 2_000L
        while (running.get()) {
            try {
                if (client.pair == null) { enabled = false; break }
                val state = client.status()
                val transfers = state.optJSONArray("transfers")
                if (transfers != null) for (index in 0 until transfers.length()) {
                    val item = transfers.optJSONObject(index) ?: continue
                    if (item.optString("direction") != "toPhone" || item.optString("type") != "file") continue
                    val id = item.optString("id")
                    if (item.optString("state") == "offered") {
                        if (autoAcceptFiles) scheduleReceive(id)
                        else if (!MainActivity.isForeground) showOffer(item)
                    } else removeOfferNotification(id)
                }
                updateServiceNotification("Подключено · ожидание файлов с Mac")
                delay = 2_000L
            } catch (error: Exception) {
                if (!running.get()) break
                updateServiceNotification("Нет связи с Mac · повторное подключение")
                runCatching { rediscoverMac(this, client) { running.get() } }
                delay = (delay * 2).coerceAtMost(30_000L)
            }
            try { Thread.sleep(delay) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
        }
        stopService()
    }

    private fun scheduleReceive(id: String) {
        synchronized(receiving) { if (id in receiving) return }
        transferExecutor.execute { receive(id) }
    }

    private fun receive(id: String) {
        synchronized(receiving) { if (!receiving.add(id)) return }
        var temporary: java.io.File? = null
        var destination: android.net.Uri? = null
        var published = false
        try {
            val transfer = client.status().optJSONArray("transfers")?.let { list ->
                (0 until list.length()).mapNotNull { list.optJSONObject(it) }.firstOrNull { it.optString("id") == id }
            } ?: throw IOException("Передача больше не найдена")
            if (transfer.optString("state") != "offered") throw IOException("Файл уже не ожидает подтверждения")
            val wasAutomatic = autoAcceptFiles
            client.accept(id)
            val received = client.download(id, transfer.optLong("size"), transfer.optString("sha256"))
            temporary = received
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, transfer.optString("name", "Файл"))
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/SamsungMacBridge")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("Не удалось создать файл в Загрузках")
            destination = uri
            contentResolver.openOutputStream(uri, "w")?.use { output -> received.inputStream().use { it.copyTo(output) } }
                ?: throw IOException("Не удалось открыть файл для сохранения")
            val hash = transfer.optString("sha256")
            val deviceId = client.pair?.deviceId ?: throw IOException("Пара с Mac недоступна")
            ReceiptStore(this).record(id, hash, uri, deviceId)
            val visible = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            if (contentResolver.update(uri, visible, null, null) != 1) throw IOException("Не удалось опубликовать файл")
            published = true
            FileLocationStore(this).record(id, uri, transfer.optString("name", "Файл"), received = true)
            val acknowledged = runCatching { client.acknowledge(id, hash) }.isSuccess
            if (acknowledged) ReceiptStore(this).remove(id)
            removeOfferNotification(id)
            if (wasAutomatic) notifyReceived(id, transfer.optString("name", "Файл"))
        } catch (error: Exception) {
            destination?.let { if (!published) runCatching { contentResolver.delete(it, null, null) } }
            if (published) notifyReceived(id, "Файл сохранён; подтверждение Mac ожидает связи")
            else notifyTransferError(id, error.localizedMessage ?: "Не удалось принять файл")
        } finally {
            temporary?.delete()
            synchronized(receiving) { receiving.remove(id) }
        }
    }

    private fun showOffer(item: JSONObject) {
        val id = item.optString("id")
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean("offer:$id", false)) return
        prefs.edit().putBoolean("offer:$id", true).apply()
        val content = PendingIntent.getActivity(this, 20, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = notificationBuilder(OFFER_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle("Файл от Mac")
            .setContentText("${item.optString("name", "Файл")} · нажмите, чтобы принять")
            .setContentIntent(content).setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, "Принять", actionIntent(ACTION_ACCEPT, id, 31)).build())
            .addAction(Notification.Action.Builder(null, "Отклонить", actionIntent(ACTION_REJECT, id, 32)).build())
            .build()
        getSystemService(NotificationManager::class.java).notify(offerNotificationId(id), notification)
    }

    private fun notifyReceived(id: String, name: String) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(this, id.hashCode(), Intent(this, MainActivity::class.java).apply {
            action = "dev.seferaki.bridge.OPEN_RECEIVED_FILE"; putExtra("open_transfer_id", id); putExtra("open_transfer_name", name)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = notificationBuilder(OFFER_CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Файл получен").setContentText(name).setContentIntent(open).setAutoCancel(true).build()
        getSystemService(NotificationManager::class.java).notify(offerNotificationId(id), notification)
    }

    private fun notifyTransferError(id: String, message: String) {
        val notification = notificationBuilder(OFFER_CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Не удалось получить файл").setContentText(message).setAutoCancel(true).build()
        getSystemService(NotificationManager::class.java).notify(offerNotificationId(id), notification)
    }

    private fun actionIntent(action: String, id: String, code: Int) = PendingIntent.getService(this, code + id.hashCode(), Intent(this, BackgroundTransferService::class.java).setAction(action).putExtra(EXTRA_TRANSFER_ID, id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun removeOfferNotification(id: String) { getSystemService(NotificationManager::class.java).cancel(offerNotificationId(id)) }
    private fun offerNotificationId(id: String) = id.hashCode()
    private val autoAcceptFiles get() = getSharedPreferences("bridge_settings", MODE_PRIVATE).getBoolean("auto_accept_files", false)
    private var enabled
        get() = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("enabled", false)
        set(value) { getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("enabled", value).apply() }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(SERVICE_CHANNEL, "Фоновый приём файлов", NotificationManager.IMPORTANCE_LOW).apply { description = "Поддерживает связь с сопряжённым Mac для приёма файлов" })
        manager.createNotificationChannel(NotificationChannel(OFFER_CHANNEL, "Передачи файлов", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Подтверждение входящих файлов и уведомления о результате" })
    }

    private fun notificationBuilder(channel: String): Notification.Builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channel) else Notification.Builder(this)
    private fun serviceNotification(message: String): Notification = notificationBuilder(SERVICE_CHANNEL)
        .setSmallIcon(R.drawable.ic_notification).setContentTitle(getString(R.string.app_name)).setContentText(message)
        .setContentIntent(PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .setCategory(Notification.CATEGORY_SERVICE).setOngoing(true).setOnlyAlertOnce(true)
        .addAction(Notification.Action.Builder(null, "Приостановить", PendingIntent.getService(this, 2, Intent(this, BackgroundTransferService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build()).build()

    private fun updateServiceNotification(message: String) {
        if (running.get()) getSystemService(NotificationManager::class.java).notify(SERVICE_NOTIFICATION, serviceNotification(message))
    }

    private fun stopService() {
        running.set(false); isRunning = false
        client.cancel(); wakeLock?.let { if (it.isHeld) it.release() }; wifiLock?.let { if (it.isHeld) it.release() }
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    override fun onDestroy() { stopService(); executor.shutdownNow(); transferExecutor.shutdownNow(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_ACCEPT = "dev.seferaki.bridge.receive.ACCEPT"
        const val ACTION_REJECT = "dev.seferaki.bridge.receive.REJECT"
        const val ACTION_STOP = "dev.seferaki.bridge.receive.STOP"
        const val EXTRA_TRANSFER_ID = "transfer_id"
        const val PREFS = "bridge_background_receive"
        private const val SERVICE_CHANNEL = "background_receive"
        private const val OFFER_CHANNEL = "transfer_offers"
        private const val SERVICE_NOTIFICATION = 7031
        @Volatile var isRunning = false
            private set

        fun start(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", true).apply()
            val intent = Intent(context, BackgroundTransferService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }
        fun stop(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", false).apply()
            context.startService(Intent(context, BackgroundTransferService::class.java).setAction(ACTION_STOP))
        }
        fun shouldStart(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", true)
    }
}
