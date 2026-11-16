package dev.seferaki.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.UUID

/** BLE peripheral side. All application payloads are encrypted before they enter this service. */
class BluetoothBridgeService : Service() {
    private lateinit var preferences: BluetoothModePreferences
    @Volatile private var stopping = false
    private var server: BluetoothGattServer? = null
    private var adapter: BluetoothAdapter? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var connectedDevice: BluetoothDevice? = null
    private var crypto: BluetoothSessionCrypto? = null
    private val assembler = BluetoothGattProtocol.Assembler()
    private val notificationQueue = ArrayDeque<ByteArray>()
    private var notifying = false
    private var subscribed = false
    private var mtu = 23
    private var messageId = 0

    private val advertiserCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) = setStatus("Bluetooth включён · ожидаю Mac")
        override fun onStartFailure(errorCode: Int) = fail("Не удалось сделать телефон видимым для поиска по Bluetooth ($errorCode)")
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothGatt.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connectedDevice = device; subscribed = false; crypto?.clear(); crypto = null; notificationQueue.clear()
                BluetoothBridgeRuntime.setConnected(false)
                setStatus("Mac найден · устанавливаю защищённую связь")
            } else if (device == connectedDevice) {
                connectedDevice = null; subscribed = false; crypto?.clear(); crypto = null; notificationQueue.clear(); notifying = false
                BluetoothBridgeRuntime.setConnected(false)
                if (preferences.enabled && !stopping && server != null) {
                    setStatus("Связь Bluetooth прервалась · ищу Mac")
                    runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiserCallback) }
                    startAdvertising()
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtuValue: Int) {
            if (device == connectedDevice && mtuValue >= 23) mtu = mtuValue
        }

        override fun onServiceAdded(status: Int, addedService: BluetoothGattService) {
            if (status != BluetoothGatt.GATT_SUCCESS || addedService.uuid != SERVICE_UUID) fail("Не удалось подготовить Bluetooth-службу GATT")
            else startAdvertising()
        }

        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            if (responseNeeded) runCatching { server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, byteArrayOf()) }
            if (descriptor.uuid == CCCD_UUID && value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) && device == connectedDevice) {
                subscribed = true
                beginHandshake()
            } else if (descriptor.uuid == CCCD_UUID && value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)) {
                subscribed = false; crypto?.clear(); crypto = null
            }
        }

        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            if (characteristic.uuid != RX_UUID || preparedWrite || offset != 0 || value.size > MAX_ATT_VALUE) {
                if (responseNeeded) runCatching { server?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH, 0, null) }
                return
            }
            if (responseNeeded) runCatching { server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null) }
            if (device != connectedDevice) return
            try {
                val complete = assembler.append(value) ?: return
                handleMessage(complete)
            } catch (error: Exception) {
                crypto?.clear(); crypto = null
                setStatus(error.localizedMessage ?: "Ошибка Bluetooth-пакета")
                runCatching { server?.cancelConnection(device) }
            }
        }

        @Suppress("DEPRECATION")
        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            synchronized(this@BluetoothBridgeService) {
                notifying = false
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    crypto?.clear(); crypto = null; setStatus("Не удалось отправить Bluetooth-кадр")
                    runCatching { server?.cancelConnection(device) }
                    return
                }
                sendNextNotification()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        preferences = BluetoothModePreferences(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            preferences.enabled = false; stopBluetooth(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY
        }
        if (!preferences.enabled) { setStatus("Bluetooth выключен"); stopSelf(); return START_NOT_STICKY }
        try {
            val notification = buildNotification("Запуск Bluetooth-связи…")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(NOTIFICATION_ID, notification)
        } catch (error: Exception) { fail("Не удалось запустить Bluetooth-службу: ${error.localizedMessage ?: "ошибка"}"); stopSelf(); return START_NOT_STICKY }
        startBluetooth()
        return START_STICKY
    }

    private fun startBluetooth() {
        if (server != null) return
        stopping = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            (checkSelfPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED || checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)) {
            fail("Разрешите Bluetooth для поиска Mac"); stopSelf(); return
        }
        val pair = PairStorage(this).load()
        if (pair == null) { fail("Сначала сопрягите приложения по QR-коду"); stopSelf(); return }
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        adapter = manager.adapter
        val btAdapter = adapter
        if (btAdapter == null || !btAdapter.isEnabled) { fail("Включите Bluetooth в настройках телефона"); stopSelf(); return }
        val advertiser = btAdapter.bluetoothLeAdvertiser
        if (advertiser == null) { fail("Телефон не поддерживает Bluetooth LE-рекламу"); stopSelf(); return }
        server = manager.openGattServer(this, gattCallback)
        if (server == null) { fail("Не удалось открыть Bluetooth GATT-сервер"); stopSelf(); return }
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        rx = BluetoothGattCharacteristic(RX_UUID, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE)
        tx = BluetoothGattCharacteristic(TX_UUID, BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ)
        tx?.addDescriptor(BluetoothGattDescriptor(CCCD_UUID, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        service.addCharacteristic(rx); service.addCharacteristic(tx)
        server?.addService(service)
        BluetoothBridgeRuntime.attach(this)
        setStatus("Подготовка Bluetooth-связи…")
    }

    private fun startAdvertising() {
        val advertiser = adapter?.bluetoothLeAdvertiser ?: return fail("Bluetooth LE-реклама недоступна")
        val settings = AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).setConnectable(true).setTimeout(0).setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).build()
        val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(SERVICE_UUID)).setIncludeDeviceName(false).build()
        runCatching { advertiser.startAdvertising(settings, data, advertiserCallback) }
            .onFailure { fail("Не удалось запустить Bluetooth LE: ${it.localizedMessage ?: "ошибка"}") }
        setStatus("Поиск Mac по Bluetooth…")
    }

    private fun beginHandshake() {
        val pair = PairStorage(this).load() ?: return fail("Пара с Mac не найдена")
        crypto?.clear()
        crypto = BluetoothSessionCrypto(pair.token, pair.deviceId)
        sendRaw(crypto!!.hello())
    }

    private fun handleMessage(data: ByteArray) {
        val session = crypto ?: error("Нет Bluetooth-рукопожатия")
        if (!session.isAuthenticated) {
            val proof = session.acceptChallenge(data)
            session.markAuthenticated()
            BluetoothBridgeRuntime.setConnected(true)
            sendRaw(proof)
            setStatus("Подключено по Bluetooth")
        } else {
            val plaintext = session.open(data)
            BluetoothBridgeRuntime.receive(plaintext)
        }
    }

    @Synchronized internal fun sendSecure(data: ByteArray) {
        val session = crypto ?: error("Нет связи по Bluetooth")
        require(session.isAuthenticated) { "Bluetooth-пара ещё не подтверждена" }
        sendRaw(session.seal(data))
    }

    @Synchronized private fun sendRaw(data: ByteArray) {
        val limit = (mtu - 3).coerceAtLeast(20).coerceAtMost(512)
        val chunks = BluetoothGattProtocol.fragment(++messageId, data, limit)
        synchronized(notificationQueue) { chunks.forEach(notificationQueue::addLast) }
        sendNextNotification()
    }

    @Suppress("DEPRECATION")
    @Synchronized private fun sendNextNotification() {
        if (notifying || !subscribed) return
        val device = connectedDevice ?: return
        val characteristic = tx ?: return
        val bytes = synchronized(notificationQueue) { if (notificationQueue.isEmpty()) null else notificationQueue.removeFirst() } ?: return
        notifying = true
        val sent = try {
            if (Build.VERSION.SDK_INT >= 33) server?.notifyCharacteristicChanged(device, characteristic, false, bytes) == BluetoothGatt.GATT_SUCCESS
            else { characteristic.value = bytes; server?.notifyCharacteristicChanged(device, characteristic, false) == true }
        } catch (_: SecurityException) { false } catch (_: Exception) { false }
        if (!sent) { notifying = false; fail("Не удалось отправить Bluetooth-уведомление") }
    }

    private fun stopBluetooth() {
        stopping = true
        val activeServer = server
        server = null
        BluetoothBridgeRuntime.detach(this)
        crypto?.clear(); crypto = null; notificationQueue.clear(); connectedDevice?.let { runCatching { activeServer?.cancelConnection(it) } }
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiserCallback) }
        runCatching { activeServer?.clearServices(); activeServer?.close() }
        connectedDevice = null; subscribed = false; notifying = false
    }

    private fun setStatus(value: String) { preferences.status = value; BluetoothBridgeRuntime.status = value; updateNotification(value) }
    private fun fail(value: String) { setStatus(value) }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Bluetooth ${getString(R.string.app_name)}", NotificationManager.IMPORTANCE_LOW).apply { description = "Состояние Bluetooth-связи с Mac" })
    }
    private fun buildNotification(text: String): Notification {
        val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground).setContentTitle(getString(R.string.app_name)).setContentText(text).setContentIntent(pending).setOngoing(true).build()
    }
    private fun updateNotification(text: String) { if (BluetoothModePreferences(this).enabled) runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text)) } }

    override fun onDestroy() { stopBluetooth(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val ACTION_START = "dev.seferaki.bridge.bluetooth.START"
        private const val ACTION_STOP = "dev.seferaki.bridge.bluetooth.STOP"
        private const val CHANNEL_ID = "bluetooth_bridge"
        private const val NOTIFICATION_ID = 2004
        private const val MAX_ATT_VALUE = 512
        private val SERVICE_UUID = UUID.fromString(BluetoothGattProtocol.SERVICE_UUID)
        private val RX_UUID = UUID.fromString(BluetoothGattProtocol.RX_UUID)
        private val TX_UUID = UUID.fromString(BluetoothGattProtocol.TX_UUID)
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        fun start(context: Context) {
            BluetoothModePreferences(context).enabled = true
            val intent = Intent(context, BluetoothBridgeService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }
        fun stop(context: Context) {
            BluetoothModePreferences(context).enabled = false
            context.startService(Intent(context, BluetoothBridgeService::class.java).setAction(ACTION_STOP))
        }
    }
}

/** Small in-process boundary used by clipboard and transfer coordinators. */
internal object BluetoothBridgeRuntime {
    @Volatile var connected = false
        private set
    @Volatile var status = "Bluetooth выключен"
    @Volatile private var service: BluetoothBridgeService? = null
    private val messageHandlers = java.util.concurrent.CopyOnWriteArrayList<(ByteArray) -> Unit>()
    private val pendingAcknowledgements = ConcurrentHashMap<String, CompletableFuture<String>>()
    private val pendingFileReplies = ConcurrentHashMap<String, CompletableFuture<String>>()
    fun attach(value: BluetoothBridgeService) { service = value }
    fun detach(value: BluetoothBridgeService) {
        if (service === value) {
            service = null
            setConnected(false)
            pendingAcknowledgements.values.forEach { it.completeExceptionally(IllegalStateException("Bluetooth-соединение прервано")) }
            pendingAcknowledgements.clear()
            pendingFileReplies.values.forEach { it.completeExceptionally(IllegalStateException("Bluetooth-соединение прервано")) }
            pendingFileReplies.clear()
        }
    }
    fun addMessageHandler(handler: (ByteArray) -> Unit): AutoCloseable {
        messageHandlers += handler
        return AutoCloseable { messageHandlers -= handler }
    }
    fun receive(data: ByteArray) {
        val copy = data.copyOf()
        val fileReply = runCatching { BluetoothFileMessage.decode(copy) }.getOrNull()?.takeIf { it.optString("kind") == "file.reply" }
        if (fileReply != null) {
            val key = "${fileReply.optString("id")}|${fileReply.optString("phase")}" 
            pendingFileReplies.remove(key)?.complete(fileReply.toString())
        }
        val acknowledgement = runCatching { BluetoothClipboardMessage.decode(copy) as? BluetoothClipboardMessage.Acknowledgement }.getOrNull()
        if (acknowledgement != null) {
            pendingAcknowledgements.remove(acknowledgement.eventId)?.complete(acknowledgement.state)
        }
        messageHandlers.forEach { handler -> runCatching { handler(copy) } }
    }
    fun send(data: ByteArray): Boolean = runCatching { service?.sendSecure(data) ?: return false; true }.getOrDefault(false)
    fun sendAndAwait(data: ByteArray, eventId: String, timeoutMillis: Long = 120_000): String {
        check(connected) { "Нет защищённого Bluetooth-соединения с Mac" }
        val future = CompletableFuture<String>()
        check(pendingAcknowledgements.putIfAbsent(eventId, future) == null) { "Ожидается подтверждение этого события" }
        try {
            check(send(data)) { "Не удалось отправить данные по Bluetooth" }
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (error: Exception) {
            throw (error.cause ?: error)
        } finally {
            pendingAcknowledgements.remove(eventId, future)
        }
    }
    fun sendFileAndAwait(data: ByteArray, id: String, phase: String, timeoutMillis: Long = 900_000): String {
        check(connected) { "Нет защищённого Bluetooth-соединения с Mac" }
        val key = "$id|$phase"
        val future = CompletableFuture<String>()
        check(pendingFileReplies.putIfAbsent(key, future) == null) { "Ожидается ответ Bluetooth-передачи" }
        try {
            check(send(data)) { "Не удалось отправить файл по Bluetooth" }
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (error: Exception) {
            throw (error.cause ?: error)
        } finally {
            pendingFileReplies.remove(key, future)
        }
    }
    fun setConnected(value: Boolean) {
        connected = value
        if (!value) {
            pendingAcknowledgements.values.forEach { it.completeExceptionally(IllegalStateException("Bluetooth-соединение прервано")) }
            pendingAcknowledgements.clear()
            pendingFileReplies.values.forEach { it.completeExceptionally(IllegalStateException("Bluetooth-соединение прервано")) }
            pendingFileReplies.clear()
        }
    }
}
