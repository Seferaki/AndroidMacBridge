package dev.seferaki.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class ClipboardSyncService : Service() {
    private lateinit var preferences: ClipboardSyncPreferences
    private lateinit var client: BridgeClient
    private lateinit var imageClient: BridgeClient
    private lateinit var screenshotClient: BridgeClient
    private val executor = Executors.newSingleThreadExecutor()
    private val imageExecutor = Executors.newSingleThreadExecutor()
    private val screenshotExecutor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private val screenshotJobs = ConcurrentHashMap.newKeySet<String>()
    private var screenshotObserver: ClipboardScreenshotObserver? = null
    private val networkVersion = AtomicLong()
    private val retryMonitor = Object()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var bluetoothMessageRegistration: AutoCloseable? = null
    private var networkCallbackRegistered = false
    private lateinit var clipboardManager: ClipboardManager
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (::clipboardManager.isInitialized) ClipboardImageProvider.cleanupAfterClipboardChange(this)
    }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = networkChanged()
        override fun onLost(network: Network) = networkChanged()
    }

    private fun networkChanged() {
        networkVersion.incrementAndGet()
        synchronized(retryMonitor) { retryMonitor.notifyAll() }
        if (running.get() && ::client.isInitialized) client.cancel()
        if (running.get() && ::imageClient.isInitialized) imageClient.cancel()
        if (running.get() && ::screenshotClient.isInitialized) screenshotClient.cancel()
    }

    override fun onCreate() {
        super.onCreate()
        preferences = ClipboardSyncPreferences(this)
        client = BridgeClient(this)
        imageClient = BridgeClient(this)
        screenshotClient = BridgeClient(this)
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.addPrimaryClipChangedListener(clipboardListener)
        bluetoothMessageRegistration = BluetoothBridgeRuntime.addMessageHandler(::handleBluetoothMessage)
        ClipboardImageProvider.cleanupAfterClipboardChange(this)
        networkCallbackRegistered = runCatching {
            getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
            true
        }.getOrDefault(false)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> stopForUser(clearCursor = false, status = "Приостановлено")
            ACTION_STOP -> stopForUser(clearCursor = true, status = "Остановлено")
            ACTION_UPDATE_SCREENSHOT_OBSERVER -> updateScreenshotObserver()
            ACTION_TRANSPORT_CHANGED -> {
                if (running.get()) {
                    if (::client.isInitialized) client.cancel()
                    if (::imageClient.isInitialized) imageClient.cancel()
                    synchronized(retryMonitor) { retryMonitor.notifyAll() }
                } else startSyncIfAllowed()
            }
            else -> startSyncIfAllowed()
        }
        return if (running.get()) START_STICKY else START_NOT_STICKY
    }

    private fun startSyncIfAllowed() {
        if (running.get()) return
        if (!preferences.enabled) return stopForUser(clearCursor = false, status = "Приостановлено")
        if (client.pair == null) return stopForUser(clearCursor = true, status = "Сначала подключите Mac")
        if (!notificationsAllowed()) return stopForUser(clearCursor = false, status = "Разрешите уведомления для фоновой синхронизации")
        if (!BluetoothModePreferences(this).enabled && !localNetworkAllowed()) return stopForUser(clearCursor = false, status = "Разрешите доступ к локальной сети")

        try {
            val notification = buildNotification("Подключение к Mac…")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (error: Exception) {
            preferences.enabled = false
            preferences.status = "Не удалось запустить фоновую службу: ${error.localizedMessage ?: "проверьте разрешения"}"
            stopSelf()
            return
        }

        running.set(true)
        isRunning = true
        preferences.status = "Подключение к Mac…"
        updateNotification(preferences.status)
        executor.execute { receiveLoop() }
        imageExecutor.execute { receiveImageLoop() }
        updateScreenshotObserver()
    }

    private fun receiveLoop() {
        var retryDelay = 1_000L
        while (running.get() && preferences.enabled) {
            if (BluetoothModePreferences(this).enabled) {
                setStatus(if (BluetoothBridgeRuntime.connected) "Подключено по Bluetooth · ожидание текста" else BluetoothModePreferences(this).status)
                waitForBluetoothTick()
                continue
            }
            client.refreshPair()
            if (client.pair == null) {
                stopForUser(clearCursor = true, status = "Пара с Mac удалена")
                break
            }
            try {
                val event = client.nextClipboardEvent(preferences.lastReceivedEventId)
                if (!running.get() || !preferences.enabled) break
                if (event != null) {
                    if (!preferences.shouldIgnore(event)) {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        preferences.markSeen(event)
                        ClipboardAccessibilityService.noteBridgeClipboardWrite()
                        clipboard.setPrimaryClip(ClipData.newPlainText(if (event.type == "link") "Ссылка" else "Текст", event.text))
                        ClipboardHistory(this).add(event.text)
                    }
                    preferences.markSeen(event)
                    setStatus("Подключено · последний текст принят")
                } else {
                    setStatus("Подключено · ожидание текста с Mac")
                }
                retryDelay = 1_000L
            } catch (error: SecurityException) {
                stopForUser(clearCursor = true, status = error.localizedMessage ?: "Пара с Mac недействительна")
                break
            } catch (error: Exception) {
                if (!running.get() || !preferences.enabled) break
                val versionBeforeDiscovery = networkVersion.get()
                setStatus("Ищу сопряжённый Mac в локальной сети…")
                val recovered = runCatching { rediscoverMac(this, client) { running.get() && preferences.enabled } }.getOrDefault(false)
                if (recovered) { retryDelay = 1_000L; continue }
                val detail = connectionFailureMessage(this, error)
                setStatus("Нет связи с Mac · повтор через ${retryDelay / 1000} с · $detail")
                try {
                    synchronized(retryMonitor) {
                        if (running.get() && preferences.enabled && networkVersion.get() == versionBeforeDiscovery) retryMonitor.wait(retryDelay)
                    }
                } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
                retryDelay = (retryDelay * 2).coerceAtMost(MAX_RETRY_DELAY)
            }
        }
    }

    private fun receiveImageLoop() {
        var retryDelay = 1_000L
        while (running.get() && preferences.enabled) {
            if (BluetoothModePreferences(this).enabled) {
                waitForBluetoothTick()
                continue
            }
            imageClient.refreshPair()
            if (imageClient.pair == null) return
            try {
                if (!imageClient.supportsClipboardImages()) return
                val event = imageClient.nextClipboardImage(preferences.lastReceivedImageEventId)
                if (!running.get() || !preferences.enabled) break
                if (event != null) {
                    if (!preferences.shouldIgnore(event)) {
                        val uri = ClipboardImageProvider.write(this, event.image)
                        try {
                            val clip = ClipData.newUri(contentResolver, "Изображение", uri)
                            ClipboardAccessibilityService.noteBridgeClipboardWrite()
                            clipboardManager.setPrimaryClip(clip)
                            ClipboardImageProvider.markCurrent(this, uri)
                            preferences.markImageSeen(event)
                        } catch (error: Exception) {
                            ClipboardImageProvider.delete(this, uri)
                            throw error
                        }
                    } else {
                        preferences.markImageSeen(event)
                    }
                }
                retryDelay = 1_000L
            } catch (error: SecurityException) {
                stopForUser(clearCursor = true, status = error.localizedMessage ?: "Пара с Mac недействительна")
                break
            } catch (error: Exception) {
                if (!running.get() || !preferences.enabled) break
                val versionBeforeDiscovery = networkVersion.get()
                val recovered = runCatching { rediscoverMac(this, imageClient) { running.get() && preferences.enabled } }.getOrDefault(false)
                if (recovered) { retryDelay = 1_000L; continue }
                val detail = connectionFailureMessage(this, error)
                setStatus("Изображения ждут связи · повтор через ${retryDelay / 1000} с · $detail")
                try {
                    synchronized(retryMonitor) {
                        if (running.get() && preferences.enabled && networkVersion.get() == versionBeforeDiscovery) retryMonitor.wait(retryDelay)
                    }
                } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
                retryDelay = (retryDelay * 2).coerceAtMost(MAX_RETRY_DELAY)
            }
        }
    }

    private fun waitForBluetoothTick() {
        try {
            synchronized(retryMonitor) { if (running.get() && preferences.enabled && BluetoothModePreferences(this).enabled) retryMonitor.wait(1_000L) }
        } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }

    private fun handleBluetoothMessage(data: ByteArray) {
        if (!BluetoothModePreferences(this).enabled) return
        val message = runCatching { BluetoothClipboardMessage.decode(data) }.getOrNull() ?: return
        if (message is BluetoothClipboardMessage.Acknowledgement) return
        mainHandler.post {
            val ack = when (message) {
                is BluetoothClipboardMessage.Text -> receiveBluetoothText(message.event)
                is BluetoothClipboardMessage.Image -> receiveBluetoothImage(message.event)
                is BluetoothClipboardMessage.Acknowledgement -> return@post
            }
            BluetoothBridgeRuntime.send(BluetoothClipboardMessage.acknowledgement(ack.first, ack.second))
        }
    }

    private fun receiveBluetoothText(event: ClipboardPayload): Pair<String, String> {
        if (!preferences.enabled) return event.eventId to "paused"
        if (preferences.shouldIgnore(event)) return event.eventId to "duplicate"
        return try {
            ClipboardAccessibilityService.noteBridgeClipboardWrite()
            clipboardManager.setPrimaryClip(ClipData.newPlainText(if (event.type == "link") "Ссылка" else "Текст", event.text))
            preferences.markSeen(event)
            ClipboardHistory(this).add(event.text)
            setStatus("Текст получен по Bluetooth")
            event.eventId to "accepted"
        } catch (_: Exception) { event.eventId to "rejected" }
    }

    private fun receiveBluetoothImage(event: ClipboardImageEvent): Pair<String, String> {
        if (!preferences.enabled) return event.eventId to "paused"
        if (preferences.shouldIgnore(event)) return event.eventId to "duplicate"
        return try {
            val uri = ClipboardImageProvider.write(this, event.image)
            try {
                ClipboardAccessibilityService.noteBridgeClipboardWrite()
                clipboardManager.setPrimaryClip(ClipData.newUri(contentResolver, "Изображение", uri))
                ClipboardImageProvider.markCurrent(this, uri)
                preferences.markImageSeen(event)
                setStatus("Изображение получено по Bluetooth")
                event.eventId to "accepted"
            } catch (error: Exception) {
                ClipboardImageProvider.delete(this, uri)
                throw error
            }
        } catch (_: Exception) { event.eventId to "rejected" }
    }

    private fun setStatus(value: String) {
        if (preferences.status == value) return
        preferences.status = value
        updateNotification(value)
    }

    private fun updateScreenshotObserver() {
        val shouldObserve = running.get() && preferences.enabled && preferences.autoShareScreenshots
        if (!shouldObserve) {
            screenshotObserver?.close()
            screenshotObserver = null
            return
        }
        if (screenshotObserver != null) return
        val observer = ClipboardScreenshotObserver(this, ::sendScreenshotToMac)
        if (observer.start()) {
            screenshotObserver = observer
        } else {
            observer.close()
            setStatus("Для автоотправки снимков разрешите полный доступ к изображениям")
        }
    }

    private fun sendScreenshotToMac(item: ScreenshotMediaItem) {
        if (!running.get() || !preferences.enabled || !preferences.autoShareScreenshots) return
        val uriKey = item.uri.toString()
        if (!screenshotJobs.add(uriKey)) return
        screenshotExecutor.execute {
            try {
                val image = readScreenshot(item.uri, item.mimeType)
                val snapshot = ClipboardSnapshot(image = image)
                val eventId = java.util.UUID.randomUUID().toString()
                var retryDelay = 1_000L
                var firstAttempt = true
                while (running.get() && preferences.enabled && preferences.autoShareScreenshots) {
                    if (!firstAttempt) {
                        synchronized(retryMonitor) {
                            if (running.get() && preferences.enabled && preferences.autoShareScreenshots) {
                                retryMonitor.wait(retryDelay)
                            }
                        }
                    }
                    firstAttempt = false
                    if (!running.get() || !preferences.enabled || !preferences.autoShareScreenshots) return@execute
                    val outcome = ClipboardSendCoordinator.send(
                        this,
                        screenshotClient,
                        snapshot,
                        preferences.sendMethod,
                        eventId
                    )
                    if (outcome.isSuccessful) {
                        setStatus("Снимок экрана передан на Mac")
                        return@execute
                    }
                    if (outcome.status !in setOf(ClipboardSendStatus.FAILED, ClipboardSendStatus.IMAGE_FAILED)) {
                        setStatus(outcome.message)
                        return@execute
                    }
                    setStatus("Снимок ждёт связи · повтор через ${retryDelay / 1000} с")
                    retryDelay = (retryDelay * 2).coerceAtMost(MAX_RETRY_DELAY)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: SecurityException) {
                setStatus("Нет доступа к новому снимку экрана")
            } catch (error: Exception) {
                setStatus(error.localizedMessage ?: "Не удалось прочитать снимок экрана")
            } finally {
                screenshotJobs.remove(uriKey)
            }
        }
    }

    private fun readScreenshot(uri: Uri, mimeType: String): ClipboardImage {
        if (mimeType != "image/png" && mimeType != "image/jpeg") throw IOException("Формат снимка не поддерживается")
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        (contentResolver.openInputStream(uri) ?: throw IOException("Снимок экрана недоступен")).use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                if (output.size() + count > ClipboardImage.MAX_BYTES) throw IOException("Снимок экрана больше 8 МиБ")
                output.write(buffer, 0, count)
            }
        }
        return ClipboardImage.validate(output.toByteArray(), mimeType)
    }

    private fun stopForUser(clearCursor: Boolean, status: String) {
        preferences.enabled = false
        preferences.status = status
        if (clearCursor) preferences.clearClipboardCursor()
        running.set(false)
        synchronized(retryMonitor) { retryMonitor.notifyAll() }
        isRunning = false
        screenshotObserver?.close()
        screenshotObserver = null
        if (::client.isInitialized) client.cancel()
        if (::imageClient.isInitialized) imageClient.cancel()
        if (::screenshotClient.isInitialized) screenshotClient.cancel()
        bluetoothMessageRegistration?.close()
        bluetoothMessageRegistration = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notificationsAllowed(): Boolean = Build.VERSION.SDK_INT < 33 ||
        checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun localNetworkAllowed(): Boolean = BluetoothModePreferences(this).enabled || Build.VERSION.SDK_INT < 37 ||
        checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") == PackageManager.PERMISSION_GRANTED

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "Общий буфер Samsung и Mac", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Показывает состояние фоновой синхронизации clipboard."
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(message: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pause = serviceAction(ACTION_PAUSE, 2)
        val stop = serviceAction(ACTION_STOP, 3)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(message)
            .setContentIntent(openApp)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Приостановить", pause).build())
            .addAction(Notification.Action.Builder(null, "Остановить", stop).build())
            .build()
    }

    private fun serviceAction(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this, requestCode, Intent(this, ClipboardSyncService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun updateNotification(message: String) {
        if (!running.get()) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(message))
    }

    override fun onDestroy() {
        running.set(false)
        synchronized(retryMonitor) { retryMonitor.notifyAll() }
        if (networkCallbackRegistered) runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
        if (::clipboardManager.isInitialized) clipboardManager.removePrimaryClipChangedListener(clipboardListener)
        if (::client.isInitialized) client.cancel()
        if (::imageClient.isInitialized) imageClient.cancel()
        if (::screenshotClient.isInitialized) screenshotClient.cancel()
        bluetoothMessageRegistration?.close()
        bluetoothMessageRegistration = null
        screenshotObserver?.close()
        screenshotObserver = null
        executor.shutdownNow()
        imageExecutor.shutdownNow()
        screenshotExecutor.shutdownNow()
        if (preferences.enabled) preferences.status = "Служба остановлена системой"
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "dev.seferaki.bridge.clipboard.START"
        const val ACTION_PAUSE = "dev.seferaki.bridge.clipboard.PAUSE"
        const val ACTION_STOP = "dev.seferaki.bridge.clipboard.STOP"
        const val ACTION_UPDATE_SCREENSHOT_OBSERVER = "dev.seferaki.bridge.clipboard.UPDATE_SCREENSHOTS"
        const val ACTION_TRANSPORT_CHANGED = "dev.seferaki.bridge.clipboard.TRANSPORT_CHANGED"
        private const val CHANNEL_ID = "clipboard_sync"
        private const val NOTIFICATION_ID = 7021
        private const val MAX_RETRY_DELAY = 30_000L

        @Volatile var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, ClipboardSyncService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }

        fun transportChanged(context: Context) {
            val intent = Intent(context, ClipboardSyncService::class.java).setAction(ACTION_TRANSPORT_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }

        fun stop(context: Context, clearCursor: Boolean = true) {
            val intent = Intent(context, ClipboardSyncService::class.java).setAction(if (clearCursor) ACTION_STOP else ACTION_PAUSE)
            context.startService(intent)
        }

        fun updateScreenshotSync(context: Context) {
            if (!isRunning) return
            context.startService(Intent(context, ClipboardSyncService::class.java).setAction(ACTION_UPDATE_SCREENSHOT_OBSERVER))
        }
    }
}
