package dev.seferaki.bridge

import androidx.activity.ComponentActivity
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.StatusBarManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.graphics.drawable.Icon
import android.provider.Settings
import android.provider.MediaStore
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.DateFormat
import java.util.Date
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    private data class LiveTransferProgress(val sent: Long, val total: Long, val startedAt: Long)
    private data class QueuedFile(val id: String, val uri: Uri, val name: String, val state: String = "waiting", val error: String? = null)
    private data class BluetoothReceiveSession(
        val transfer: JSONObject,
        val temporary: File,
        val output: FileOutputStream,
        val digest: MessageDigest,
        var received: Long = 0
    )

    private val client by lazy { BridgeClient(this) }
    private val clipboardPreferences by lazy { ClipboardSyncPreferences(this) }
    private val receiptStore by lazy { ReceiptStore(this) }
    private val pendingCancellations by lazy { PendingCancellationStore(this) }
    private val pendingUploads by lazy { PendingUploadStore(this) }
    private val worker = Executors.newSingleThreadExecutor()
    private val transferWorker = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pollInFlight = AtomicBoolean(false)
    private val uploading = ConcurrentHashMap.newKeySet<String>()
    private val attemptedUploads = ConcurrentHashMap.newKeySet<String>()
    private val cancelling = ConcurrentHashMap.newKeySet<String>()
    private val receiving = ConcurrentHashMap.newKeySet<String>()
    private val cancelledReceives = ConcurrentHashMap.newKeySet<String>()
    private val publicationLock = Any()
    private val seenText = ConcurrentHashMap.newKeySet<String>()
    private val preparedFiles = ConcurrentHashMap<String, BridgeClient.Prepared>()
    private val bluetoothReceiveSessions = ConcurrentHashMap<String, BluetoothReceiveSession>()
    private val bluetoothCancelledTransfers = ConcurrentHashMap.newKeySet<String>()
    private var bluetoothMessageRegistration: AutoCloseable? = null
    private val queuedFiles = ArrayDeque<QueuedFile>()
    private val cancelledQueueIds = ConcurrentHashMap.newKeySet<String>()
    private var lastHandledShareFingerprint: String? = null
    private var lastHandledShareAt = 0L
    private val autoAcceptedReceives = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var preparingFile = false
    private var permissionAction: (() -> Unit)? = null
    private var permissionPrompted = false
    private var statusView: TextView? = null
    private var deviceView: TextView? = null
    private var deviceTitle: TextView? = null
    private var lastConnectionTime: Long? = null
    private var cards: LinearLayout? = null
    private var historyExpanded = false
    private var historyAction: TextView? = null
    private var lastTransfers = JSONArray()
    @Volatile private var autoAcceptFiles = false
    private val liveTransferProgress = mutableMapOf<String, LiveTransferProgress>()
    private val lastProgressRenderAt = mutableMapOf<String, Long>()
    private var pairButton: Button? = null
    private var manualPairButton: Button? = null
    private var editAddressButton: Button? = null
    private var unpairButton: Button? = null
    private var connectionControls: LinearLayout? = null
    private var outgoingInput: EditText? = null
    private var sendButton: Button? = null
    private var pickFilesButton: Button? = null
    private var clipboardSyncButton: Button? = null
    private var clipboardSyncStatusView: TextView? = null
    private var clipboardHistoryList: LinearLayout? = null
    private var clipboardHistoryScroll: ScrollView? = null
    private var clipboardHistoryClear: Button? = null
    private var clipboardHistorySnapshot: List<String>? = null
    private var sizeClipboardHistory: (() -> Unit)? = null
    private var openFolderAction: TextView? = null
    private var clipboardKeyboardStatusView: TextView? = null
    private var compatibilityView: TextView? = null
    private var autoShareScreenshotsSwitch: android.widget.Switch? = null
    private var bluetoothModeSwitch: android.widget.Switch? = null
    private var pendingScreenshotPermissionRequest = false

    private val requestAutoAcceptNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) ensureAutoReceiveNotificationChannel()
        else runOnUiThread { statusView?.text = "Автоприём включён. Уведомления отключены — результат останется в истории передач." }
    }

    private val requestClipboardNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startClipboardSyncWithLocalNetwork()
        else {
            clipboardPreferences.enabled = false
            clipboardPreferences.status = "Разрешите уведомления, чтобы видеть состояние фоновой синхронизации."
            updateClipboardControls()
        }
    }

    private val requestScreenshotImagePermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        val wasRequested = pendingScreenshotPermissionRequest
        pendingScreenshotPermissionRequest = false
        if (wasRequested && hasFullImagePermission()) {
            clipboardPreferences.autoShareScreenshots = true
            autoShareScreenshotsSwitch?.isChecked = true
            ClipboardSyncService.updateScreenshotSync(this)
        } else {
            clipboardPreferences.autoShareScreenshots = false
            autoShareScreenshotsSwitch?.isChecked = false
            Toast.makeText(this, "Для автоотправки снимков разрешите полный доступ к изображениям.", Toast.LENGTH_LONG).show()
        }
    }

    private val requestBluetoothPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) startBluetoothMode()
        else {
            BluetoothModePreferences(this).enabled = false
            bluetoothModeSwitch?.isChecked = false
            Toast.makeText(this, "Для Bluetooth-связи разрешите подключение и рекламу Bluetooth.", Toast.LENGTH_LONG).show()
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    private val qrScanner = registerForActivityResult(ScanContract()) { result ->
        val value = result.contents
        if (value.isNullOrBlank()) return@registerForActivityResult
        withLocalNetworkPermission { pairFromQr(value, null) }
    }

    private val selectFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { files ->
        files.forEach { uri -> runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        if (files.isNotEmpty()) {
            if (BluetoothModePreferences(this).enabled) enqueueFiles(files)
            else withLocalNetworkPermission { enqueueFiles(files) }
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            if (isFinishing || client.pair == null) return
            if (BluetoothModePreferences(this@MainActivity).enabled) {
                val isConnected = BluetoothBridgeRuntime.connected
                deviceTitle?.text = client.pair?.name ?: "Mac"
                deviceView?.text = if (isConnected) "Подключено по Bluetooth" else BluetoothModePreferences(this@MainActivity).status
                deviceView?.setTextColor(if (isConnected) 0xFF26834D.toInt() else 0xFF667184.toInt())
                if (!isConnected) statusView?.text = "Ожидаю защищённое Bluetooth-соединение"
                if (isConnected) maybePrepareNextFile()
                updateClipboardControls(connectionAvailable = isConnected)
                mainHandler.postDelayed(this, 1200)
                return
            }
            if (!pollInFlight.compareAndSet(false, true)) {
                mainHandler.postDelayed(this, 1800)
                return
            }
            worker.execute {
                try {
                    val state = client.status()
                    receiptStore.retryConfirmed(client) { receiving.contains(it) }
                    pendingCancellations.reconcile(client)
                    val transfers = state.optJSONArray("transfers") ?: JSONArray()
                    processTransfers(transfers)
                    runOnUiThread {
                        lastConnectionTime = System.currentTimeMillis()
                        lastTransfers = transfers
                        deviceTitle?.text = state.optString("name", "Mac")
                        deviceView?.text = "Подключено"
                        deviceView?.setTextColor(0xFF26834D.toInt())
                        val active = (0 until transfers.length()).any { i -> transfers.optJSONObject(i)?.optString("state") == "transferring" }
                        if (!active) statusView?.text = ""
                        renderTransfers(transfers)
                        renderClipboardHistory()
                        updateClipboardControls()
                    }
                } catch (error: Exception) {
                    runOnUiThread {
                        val last = lastConnectionTime?.let { " Последняя связь: ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))}." }.orEmpty()
                        deviceView?.text = connectionFailureMessage(this@MainActivity, error) + last
                        deviceView?.setTextColor(0xFF667184.toInt())
                        updateClipboardControls(connectionAvailable = false)
                    }
                    if (wifiEnabled(this@MainActivity) && hasLocalNetworkPermission() && classifyConnectionError(error) == ConnectionProblem.UNAVAILABLE) {
                        runCatching { rediscoverMac(this@MainActivity, client) { !isFinishing && client.pair != null } }
                    }
                } finally {
                    pollInFlight.set(false)
                    maybePrepareNextFile()
                    mainHandler.postDelayed(this, 1800)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = getSharedPreferences("bridge_settings", MODE_PRIVATE)
        settings.edit().remove("accelerated_file_transfer").remove("accelerated_file_transfer_network").apply()
        autoAcceptFiles = settings.getBoolean("auto_accept_files", false)
        lastHandledShareFingerprint = savedInstanceState?.getString(STATE_SHARE_FINGERPRINT)
        @Suppress("DEPRECATION")
        window.apply {
            statusBarColor = 0xFFFFFFFF.toInt()
            navigationBarColor = 0xFFF5F6F9.toInt()
            decorView.systemUiVisibility = decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        ensureAutoReceiveNotificationChannel()
        bluetoothMessageRegistration = BluetoothBridgeRuntime.addMessageHandler(::handleBluetoothMessage)
        buildInterface()
        if (savedInstanceState == null) handleShareIntent(intent)
        handleNotificationOpen(intent)
        if (BluetoothModePreferences(this).enabled && client.pair != null) startBluetoothMode()
        if (client.pair != null) {
            if (BluetoothModePreferences(this).enabled) startPolling()
            else withLocalNetworkPermission { startPolling() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!handleNotificationOpen(intent)) handleShareIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_SHARE_FINGERPRINT, lastHandledShareFingerprint)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        isForeground = true
        updateClipboardControls()
        if (client.pair != null) {
            if (!BluetoothModePreferences(this).enabled && !hasLocalNetworkPermission() && clipboardPreferences.enabled) {
                clipboardPreferences.enabled = false
                clipboardPreferences.status = "Разрешите доступ к локальной сети; фоновая синхронизация остановлена."
            }
            if (!BluetoothModePreferences(this).enabled && !hasLocalNetworkPermission()) deviceView?.text = "Нет доступа к локальной сети. Разрешите его в настройках приложения."
            if (BluetoothModePreferences(this).enabled) {
                startPolling()
            } else if (hasLocalNetworkPermission()) {
                startPolling()
                if (BackgroundTransferService.shouldStart(this)) runCatching { BackgroundTransferService.start(this) }
            }
            if (clipboardPreferences.enabled) {
                if (!hasNotificationPermission()) {
                    clipboardPreferences.enabled = false
                    clipboardPreferences.status = "Разрешение на уведомления отключено; фоновая синхронизация остановлена."
                } else if (!ClipboardSyncService.isRunning) {
                    runCatching { ClipboardSyncService.start(this) }
                        .onFailure {
                            clipboardPreferences.enabled = false
                            clipboardPreferences.status = "Не удалось возобновить службу: ${it.localizedMessage ?: "проверьте настройки"}"
                        }
                }
            }
        }
        updateClipboardControls()
        updateClipboardKeyboardStatus()
        renderCompatibilityStatus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Android grants clipboard reads to the focused app; onResume can run before focus arrives.
        if (hasFocus &&
            clipboardPreferences.enabled &&
            clipboardPreferences.sendMethod == ClipboardSendMethod.APP_FOCUS &&
            hasLocalNetworkPermission()
        ) sendCurrentClipboardToMac()
    }

    override fun onPause() {
        super.onPause()
        isForeground = false
        mainHandler.removeCallbacks(poll)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        bluetoothMessageRegistration?.close()
        bluetoothMessageRegistration = null
        bluetoothReceiveSessions.values.forEach { runCatching { it.output.close() }; it.temporary.delete() }
        bluetoothReceiveSessions.clear()
        client.cancel()
        worker.shutdownNow()
        transferWorker.shutdownNow()
        super.onDestroy()
    }

    private fun hasNotificationPermission(): Boolean = Build.VERSION.SDK_INT < 33 ||
        checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun imageReadPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            android.Manifest.permission.READ_MEDIA_IMAGES,
            android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun hasFullImagePermission(): Boolean =
        checkSelfPermission(imageReadPermissions().first()) == PackageManager.PERMISSION_GRANTED

    private fun setAutoShareScreenshots(enabled: Boolean) {
        if (enabled && !hasFullImagePermission()) {
            pendingScreenshotPermissionRequest = true
            autoShareScreenshotsSwitch?.isChecked = false
            requestScreenshotImagePermission.launch(imageReadPermissions())
            return
        }
        clipboardPreferences.autoShareScreenshots = enabled
        if (clipboardPreferences.enabled && !ClipboardSyncService.isRunning) ClipboardSyncService.start(this)
        else ClipboardSyncService.updateScreenshotSync(this)
        if (enabled && !clipboardPreferences.enabled) {
            Toast.makeText(this, "Автоотправка начнётся, когда будет включён общий буфер.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleClipboardSync() {
        if (client.pair == null) {
            clipboardPreferences.status = "Сначала подключите Mac, чтобы включить общий буфер."
            updateClipboardControls()
            return
        }
        if (clipboardPreferences.enabled && ClipboardSyncService.isRunning) {
            clipboardPreferences.enabled = false
            clipboardPreferences.status = "Приостановлено"
            runCatching { ClipboardSyncService.stop(this, clearCursor = false) }
            updateClipboardControls()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission()) {
            requestClipboardNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startClipboardSyncWithLocalNetwork()
    }

    private fun startClipboardSyncWithLocalNetwork() {
        if (client.pair == null) {
            clipboardPreferences.status = "Сначала подключите Mac, чтобы включить общий буфер."
            updateClipboardControls()
            return
        }
        if (!hasNotificationPermission()) {
            clipboardPreferences.status = "Разрешите уведомления для фоновой синхронизации."
            updateClipboardControls()
            return
        }
        withLocalNetworkPermission {
            clipboardPreferences.enabled = true
            clipboardPreferences.status = "Запуск фоновой службы…"
            try {
                ClipboardSyncService.start(this)
                statusView?.text = "Фоновая синхронизация запускается."
            } catch (error: Exception) {
                clipboardPreferences.enabled = false
                clipboardPreferences.status = "Не удалось запустить службу: ${error.localizedMessage ?: "проверьте настройки"}"
            }
            updateClipboardControls()
        }
    }

    private fun updateClipboardControls(connectionAvailable: Boolean? = null) {
        val enabled = clipboardPreferences.enabled
        val running = ClipboardSyncService.isRunning
        clipboardSyncButton?.text = when {
            enabled && running -> "Приостановить общий буфер"
            enabled -> "Возобновить общий буфер"
            else -> "Включить общий буфер"
        }
        clipboardSyncButton?.isEnabled = client.pair != null
        clipboardSyncStatusView?.text = when {
            enabled && !wifiEnabled(this) -> "Общий буфер ожидает подключения Wi‑Fi."
            enabled && connectionAvailable == false -> "Общий буфер ожидает соединения с Mac."
            else -> clipboardPreferences.status
        }
        renderClipboardHistory()
    }

    private fun updateClipboardKeyboardStatus() {
        clipboardKeyboardStatusView?.text = when (ClipboardKeyboardStatus.read(this)) {
            ClipboardKeyboardAvailability.NOT_ENABLED -> "Состояние клавиатуры: выключена в Android. Нажмите «Настроить клавиатуру в Android»."
            ClipboardKeyboardAvailability.ENABLED_NOT_SELECTED -> "Состояние клавиатуры: включена, но сейчас выбрана другая клавиатура."
            ClipboardKeyboardAvailability.SELECTED -> if (clipboardPreferences.sendMethod == ClipboardSendMethod.IME_KEYBOARD) {
                "Состояние клавиатуры: выбрана. Копирование будет отправляться на Mac при включённом общем буфере."
            } else {
                "Состояние клавиатуры: выбрана. Чтобы включить автосинхронизацию, выберите клавиатурный способ выше."
            }
            ClipboardKeyboardAvailability.UNAVAILABLE -> "Состояние клавиатуры сейчас не удалось определить. Проверьте системные настройки ввода Android."
        }
    }

    private fun renderCompatibilityStatus() {
        compatibilityView?.text = AndroidCompatibilitySnapshot.capture(this).format(this)
    }

    private fun sendCurrentClipboardToMac() {
        if (!clipboardPreferences.enabled ||
            clipboardPreferences.sendMethod != ClipboardSendMethod.APP_FOCUS ||
            client.pair == null || !hasLocalNetworkPermission()
        ) return
        val snapshot = ClipboardSendCoordinator.readClipboard(this)
        if ((snapshot.text.isNullOrEmpty() && snapshot.image == null) || snapshot.sensitive || snapshot.status != null) return
        worker.execute {
            if (!clipboardPreferences.enabled || client.pair == null) return@execute
            val outcome = ClipboardSendCoordinator.send(
                this,
                client,
                snapshot,
                ClipboardSendMethod.APP_FOCUS
            )
            runOnUiThread {
                if (!isFinishing && outcome.status != ClipboardSendStatus.WRONG_METHOD) {
                    clipboardPreferences.status = outcome.message
                    updateClipboardControls()
                }
            }
        }
    }

    private fun requestClipboardTilePlacement() {
        if (Build.VERSION.SDK_INT < 33) {
            AlertDialog.Builder(this)
                .setTitle("Добавить плитку «На Mac»")
                .setMessage("Откройте шторку быстрых настроек, нажмите «Изменить», найдите «На Mac» и перетащите плитку в активную область.")
                .setPositiveButton("Понятно", null)
                .show()
            return
        }
        val statusBar = getSystemService(StatusBarManager::class.java)
        if (statusBar == null) {
            Toast.makeText(this, "Откройте редактор быстрых настроек и добавьте плитку «На Mac».", Toast.LENGTH_LONG).show()
            return
        }
        statusBar.requestAddTileService(
            ComponentName(this, ClipboardSyncTileService::class.java),
            getString(R.string.clipboard_tile_label),
            Icon.createWithResource(this, R.drawable.ic_tile_send_clipboard),
            mainExecutor
        ) { result ->
            val message = when (result) {
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "Плитка «На Mac» добавлена в шторку."
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "Плитка «На Mac» уже добавлена."
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> "Добавление плитки отменено."
                else -> "Система не добавила плитку. Попробуйте через редактор шторки."
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun buildInterface() {
        val dark = false // Утверждённый светлый вариант; системные размеры шрифта сохраняются.
        val backgroundColor = if (dark) 0xFF111318.toInt() else 0xFFF5F6F9.toInt()
        val cardColor = if (dark) 0xFF22252C.toInt() else 0xFFFFFFFF.toInt()
        fun panel(): android.graphics.drawable.GradientDrawable = android.graphics.drawable.GradientDrawable().apply {
            setColor(cardColor); cornerRadius = dp(14).toFloat()
        }
        fun blockPadding(view: View) { view.setPadding(dp(14), dp(12), dp(14), dp(12)) }
        fun addGap(parent: LinearLayout, size: Int = 10) { parent.addView(View(this), LinearLayout.LayoutParams(1, dp(size))) }
        fun compactButton(label: String, action: () -> Unit): Button = Button(this).apply {
            text = label; isAllCaps = false; minHeight = dp(48); textSize = 14f
            val shape = android.graphics.drawable.GradientDrawable().apply {
                setColor(if (dark) 0xFF303846.toInt() else 0xFFFFFFFF.toInt()); cornerRadius = dp(10).toFloat(); setStroke(dp(1), 0xFFE6EAF0.toInt())
            }
            background = shape
            elevation = 0f
            stateListAnimator = null
            setTextColor(android.content.res.ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(if (dark) 0xFF8A9099.toInt() else 0xFF8A94A2.toInt(), if (dark) 0xFFE5EDFF.toInt() else 0xFF1E344D.toInt())
            ))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener { action() }
        }

        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(backgroundColor)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                view.setPadding(0, bars.top, 0, bars.bottom)
                insets
            }
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; setBackgroundColor(backgroundColor) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(20)) }
        val screenHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        screenHost.addView(root)
        scroll.addView(screenHost)
        var showPage: (Int) -> Unit = {}
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(12), dp(12), dp(8)); setBackgroundColor(android.graphics.Color.WHITE) }
        val screenTitle = TextView(this).apply { text = getString(R.string.app_name); textSize = 20f; setTextColor(0xFF182536.toInt()); setTypeface(null, android.graphics.Typeface.BOLD) }
        header.addView(screenTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        shell.addView(header)
        shell.addView(View(this).apply { setBackgroundColor(0xFFE4E8EE.toInt()) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
        shell.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val deviceCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(144); background = panel(); setPadding(dp(16), dp(24), dp(16), dp(24)) }
        val deviceRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val laptopIcon = android.widget.ImageView(this).apply { setImageResource(dev.seferaki.bridge.R.drawable.ic_laptop); contentDescription = null }
        deviceRow.addView(laptopIcon, LinearLayout.LayoutParams(dp(76), dp(66)).apply { marginEnd = dp(16) })
        val deviceText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        deviceTitle = TextView(this).apply { text = client.pair?.name ?: "Mac"; textSize = 16f; setTextColor(0xFF182536.toInt()); setTypeface(null, android.graphics.Typeface.BOLD) }
        deviceText.addView(deviceTitle)
        deviceView = TextView(this).apply {
            textSize = 13f; setTextColor(0xFF667184.toInt()); setPadding(0, dp(4), 0, 0)
            compoundDrawablePadding = dp(6)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val connectedLabel = s.toString() == "Подключено" || s.toString() == "Connected"
                    setCompoundDrawablesWithIntrinsicBounds(if (connectedLabel) R.drawable.ic_connected else 0, 0, 0, 0)
                }
                override fun afterTextChanged(s: android.text.Editable?) = Unit
            })
            text = if (client.pair == null) "Mac не сопряжён" else "Пара сохранена · проверяем связь"
        }
        deviceText.addView(deviceView)
        deviceRow.addView(deviceText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        deviceCard.addView(deviceRow)
        root.addView(deviceCard)
        addGap(root, 12)

        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pickFilesButton = compactButton("Файл") { selectFiles.launch(arrayOf("*/*")) }.apply { isEnabled = client.pair != null }
        pickFilesButton?.apply { minHeight = dp(60); setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_action_file, 0, 0, 0); compoundDrawablePadding = dp(5) }
        actionRow.addView(pickFilesButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        val compose = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE }
        outgoingInput = EditText(this).apply {
            hint = "Текст или ссылка для Mac"; isSingleLine = true; isEnabled = client.pair != null
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        compose.addView(outgoingInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        sendButton = compactButton("Отправить") { sendTypedText() }.apply { isEnabled = client.pair != null }
        compose.addView(sendButton)
        val textAction = compactButton("Текст") { compose.visibility = if (compose.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        textAction.apply { minHeight = dp(60); setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_action_text, 0, 0, 0); compoundDrawablePadding = dp(5) }
        actionRow.addView(textAction, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        val clipboardPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(dp(18), dp(18), dp(18), dp(20)) }
        val clipboardAction = compactButton("Буфер") { showPage(1) }
        clipboardAction.apply { minHeight = dp(60); setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_action_clipboard, 0, 0, 0); compoundDrawablePadding = dp(5) }
        actionRow.addView(clipboardAction, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(actionRow)
        controls.addView(compose)
        clipboardPanel.addView(TextView(this).apply { text = "История буфера"; textSize = 15f; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(0xFF182536.toInt()); setPadding(dp(4), dp(12), dp(4), dp(8)) })
        clipboardHistoryList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = panel() }
        clipboardHistoryScroll = ScrollView(this).apply { isFillViewport = false; addView(clipboardHistoryList) }
        clipboardPanel.addView(clipboardHistoryScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)))
        clipboardHistoryClear = compactButton("Очистить историю") {
            AlertDialog.Builder(this).setMessage("Очистить историю буфера на телефоне?")
                .setNegativeButton("Отмена", null).setPositiveButton("Очистить") { _, _ -> ClipboardHistory(this).clear(); renderClipboardHistory() }.show()
        }
        clipboardPanel.addView(clipboardHistoryClear)
        clipboardSyncButton = compactButton("Общий буфер") { toggleClipboardSync() }
        clipboardPanel.addView(clipboardSyncButton)
        clipboardSyncStatusView = TextView(this).apply { setPadding(dp(4), dp(4), dp(4), dp(4)) }
        clipboardPanel.addView(clipboardSyncStatusView)
        sizeClipboardHistory = {
            scroll.post {
                val historyScroll = clipboardHistoryScroll
                if (clipboardPanel.visibility == View.VISIBLE && historyScroll != null && scroll.height > 0) {
                    var occupied = clipboardPanel.paddingTop + clipboardPanel.paddingBottom
                    for (index in 0 until clipboardPanel.childCount) {
                        val child = clipboardPanel.getChildAt(index)
                        if (child !== historyScroll && child.visibility != View.GONE) occupied += child.measuredHeight
                    }
                    val height = (scroll.height - occupied).coerceAtLeast(dp(120))
                    if (historyScroll.layoutParams.height != height) historyScroll.layoutParams = historyScroll.layoutParams.apply { this.height = height }
                }
            }
        }
        scroll.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) sizeClipboardHistory?.invoke()
        }
        for (index in 0 until clipboardPanel.childCount) {
            val child = clipboardPanel.getChildAt(index)
            if (child !== clipboardHistoryScroll) child.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (bottom - top != oldBottom - oldTop) sizeClipboardHistory?.invoke()
            }
        }
        root.addView(controls)
        addGap(root, 3)

        val settingsPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(20)); visibility = View.GONE }
        val languagePanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = panel(); blockPadding(this) }
        languagePanel.addView(TextView(this).apply {
            text = getString(R.string.app_language_title)
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFF182536.toInt())
            setPadding(0, 0, 0, dp(4))
        })
        languagePanel.addView(TextView(this).apply {
            text = getString(R.string.app_language_description)
            textSize = 12f
            setTextColor(0xFF657184.toInt())
            setPadding(0, 0, 0, dp(8))
        })
        languagePanel.addView(compactButton(AppLanguage.selectedName(this)) {
            val selected = AppLanguage.options.indexOfFirst { it.tag == AppLanguage.selectedTag(this) }.coerceAtLeast(0)
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.language_select_title))
                .setSingleChoiceItems(AppLanguage.options.map { it.nativeName }.toTypedArray(), selected) { dialog, which ->
                    AppLanguage.setSelected(this, AppLanguage.options[which].tag)
                    dialog.dismiss()
                    recreate()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        })
        settingsPanel.addView(languagePanel)
        val settingsGap = View(this).apply { minimumHeight = dp(12) }
        settingsPanel.addView(settingsGap)
        val connectionPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = panel(); blockPadding(this) }
        connectionPanel.addView(compactButton("Проверить соединение") {
            if (client.pair == null) deviceView?.text = "Mac не сопряжён. Подключите его по QR-коду."
            else if (BluetoothModePreferences(this).enabled) startPolling()
            else withLocalNetworkPermission { startPolling() }
        })
        connectionControls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pairButton = compactButton("Сопрячь с Mac по QR-коду") { startQrScan() }.apply { visibility = if (client.pair == null) View.VISIBLE else View.GONE }
        connectionControls?.addView(pairButton)
        manualPairButton = compactButton("Ввести код подключения") { enterInvitation() }.apply { visibility = if (client.pair == null) View.VISIBLE else View.GONE }
        connectionControls?.addView(manualPairButton)
        editAddressButton = compactButton("Изменить адрес Mac") { editMacAddress() }.apply { visibility = if (client.pair != null) View.VISIBLE else View.GONE }
        connectionControls?.addView(editAddressButton)
        unpairButton = compactButton("Удалить пару") { unpair() }.apply { visibility = if (client.pair != null) View.VISIBLE else View.GONE }
        connectionControls?.addView(unpairButton)
        connectionPanel.addView(connectionControls)
        settingsPanel.addView(connectionPanel)

        val clipboardMethodPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panel()
            blockPadding(this)
        }
        clipboardMethodPanel.addView(TextView(this).apply {
            text = "Способ отправки текста на Mac"
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFF182536.toInt())
            setPadding(0, 0, 0, dp(4))
        })
        val clipboardMethodGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val appFocusMethod = RadioButton(this).apply {
            id = View.generateViewId()
            text = "При возврате в приложение"
            textSize = 14f
            setTextColor(0xFF26364A.toInt())
            isChecked = clipboardPreferences.sendMethod == ClipboardSendMethod.APP_FOCUS
        }
        val quickTileMethod = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Плитка быстрых настроек «На Mac»"
            textSize = 14f
            setTextColor(0xFF26364A.toInt())
            isChecked = clipboardPreferences.sendMethod == ClipboardSendMethod.QUICK_SETTINGS_TILE
        }
        val keyboardMethod = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Клавиатура AndroidMacBridge"
            textSize = 14f
            setTextColor(0xFF26364A.toInt())
            isChecked = clipboardPreferences.sendMethod == ClipboardSendMethod.IME_KEYBOARD
        }
        val accessibilityMethod = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Автоматически с любой клавиатурой"
            textSize = 14f
            setTextColor(0xFF26364A.toInt())
            isChecked = clipboardPreferences.sendMethod == ClipboardSendMethod.ACCESSIBILITY
        }
        clipboardMethodGroup.addView(appFocusMethod)
        clipboardMethodGroup.addView(quickTileMethod)
        clipboardMethodGroup.addView(keyboardMethod)
        clipboardMethodGroup.addView(accessibilityMethod)
        clipboardMethodGroup.setOnCheckedChangeListener { _, checkedId ->
            clipboardPreferences.sendMethod = when (checkedId) {
                quickTileMethod.id -> ClipboardSendMethod.QUICK_SETTINGS_TILE
                keyboardMethod.id -> ClipboardSendMethod.IME_KEYBOARD
                accessibilityMethod.id -> ClipboardSendMethod.ACCESSIBILITY
                else -> ClipboardSendMethod.APP_FOCUS
            }
            updateClipboardKeyboardStatus()
        }
        clipboardMethodPanel.addView(clipboardMethodGroup)
        clipboardMethodPanel.addView(TextView(this).apply {
            text = "Для автоматического режима включите службу специальных возможностей. Она получает события интерфейса других приложений, чтобы распознать копирование; скопированный текст отправляется только сопряжённому Mac при включённом общем буфере. Работа зависит от приложения и версии Android."
            textSize = 12f
            setTextColor(0xFF657184.toInt())
        })
        clipboardMethodPanel.addView(compactButton("Включить службу в Android") {
            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                .onFailure { Toast.makeText(this, "Не удалось открыть специальные возможности Android.", Toast.LENGTH_LONG).show() }
        })
        clipboardMethodPanel.addView(compactButton("Добавить плитку в шторку") { requestClipboardTilePlacement() })
        clipboardMethodPanel.addView(compactButton("Настроить клавиатуру в Android") {
            runCatching { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
                .onFailure { Toast.makeText(this, "Не удалось открыть настройки клавиатуры Android.", Toast.LENGTH_LONG).show() }
        })
        clipboardMethodPanel.addView(compactButton("Выбрать клавиатуру AndroidMacBridge") {
            when (ClipboardKeyboardStatus.read(this)) {
                ClipboardKeyboardAvailability.SELECTED -> Toast.makeText(this, "Клавиатура AndroidMacBridge уже выбрана.", Toast.LENGTH_SHORT).show()
                ClipboardKeyboardAvailability.NOT_ENABLED -> Toast.makeText(this, "Сначала включите клавиатуру в настройках Android.", Toast.LENGTH_LONG).show()
                ClipboardKeyboardAvailability.ENABLED_NOT_SELECTED -> {
                    (getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                        ?.showInputMethodPicker()
                        ?: Toast.makeText(this, "Системный переключатель клавиатуры недоступен.", Toast.LENGTH_LONG).show()
                }
                ClipboardKeyboardAvailability.UNAVAILABLE -> Toast.makeText(this, "Состояние клавиатуры сейчас недоступно.", Toast.LENGTH_LONG).show()
            }
        })
        clipboardKeyboardStatusView = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF657184.toInt())
            setPadding(0, dp(4), 0, 0)
        }
        clipboardMethodPanel.addView(clipboardKeyboardStatusView)
        clipboardMethodPanel.addView(TextView(this).apply {
            text = "Плитка отправляет текст по нажатию. В режиме клавиатуры передаётся только скопированный обычный текст; вводимые символы остаются в активном поле."
            textSize = 12f
            setTextColor(0xFF657184.toInt())
            setPadding(0, dp(3), 0, 0)
        })
        settingsPanel.addView(
            clipboardMethodPanel,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        )

        val screenshotPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = panel(); blockPadding(this) }
        autoShareScreenshotsSwitch = android.widget.Switch(this).apply {
            text = "Автоотправка снимков экрана"
            isChecked = clipboardPreferences.autoShareScreenshots && hasFullImagePermission()
            setOnCheckedChangeListener { _, enabled -> setAutoShareScreenshots(enabled) }
        }
        screenshotPanel.addView(autoShareScreenshotsSwitch)
        screenshotPanel.addView(TextView(this).apply {
            text = "Новые снимки экрана телефона будут сразу отправляться в буфер Mac, даже если приложение закрыто. Работает при включённом общем буфере и требует полного доступа к изображениям. Существующие снимки не отправляются."
            textSize = 12f
            setTextColor(0xFF657184.toInt())
            setPadding(0, dp(4), 0, 0)
        })
        settingsPanel.addView(screenshotPanel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        val backgroundReceivePanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = panel(); blockPadding(this) }
        backgroundReceivePanel.addView(android.widget.Switch(this).apply {
            text = "Приём файлов в фоне"
            isChecked = BackgroundTransferService.shouldStart(this@MainActivity)
            setOnCheckedChangeListener { _, enabled ->
                if (enabled && client.pair == null) {
                    isChecked = false
                    Toast.makeText(this@MainActivity, "Сначала подключите Mac", Toast.LENGTH_SHORT).show()
                } else if (enabled) {
                    if (!hasLocalNetworkPermission()) withLocalNetworkPermission { BackgroundTransferService.start(this@MainActivity) }
                    else runCatching { BackgroundTransferService.start(this@MainActivity) }
                        .onFailure { Toast.makeText(this@MainActivity, "Не удалось включить фоновый приём: ${it.localizedMessage}", Toast.LENGTH_LONG).show() }
                } else BackgroundTransferService.stop(this@MainActivity)
            }
        })
        backgroundReceivePanel.addView(TextView(this).apply {
            text = "Поддерживает связь с Mac при закрытом приложении. Показывает уведомление; входящий файл можно принять прямо из него."
            textSize = 12f; setTextColor(0xFF657184.toInt()); setPadding(0, dp(4), 0, 0)
        })
        settingsPanel.addView(backgroundReceivePanel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        val bluetoothPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = panel(); blockPadding(this) }
        bluetoothModeSwitch = android.widget.Switch(this).apply {
            text = "Bluetooth-связь без общей сети Wi‑Fi"
            isChecked = BluetoothModePreferences(this@MainActivity).enabled
            setOnCheckedChangeListener { _, enabled -> setBluetoothMode(enabled) }
        }
        bluetoothPanel.addView(bluetoothModeSwitch)
        bluetoothPanel.addView(TextView(this).apply {
            text = "Позволяет обмениваться файлами и буфером, даже если Mac и телефон подключены к разным сетям. Bluetooth должен быть включён на обоих устройствах; скорость может быть ниже Wi‑Fi. Режим запускается отдельно на Mac и телефоне."
            textSize = 12f; setTextColor(0xFF657184.toInt()); setPadding(0, dp(4), 0, 0)
        })
        bluetoothPanel.addView(TextView(this).apply {
            text = "Состояние: ${BluetoothModePreferences(this@MainActivity).status}"
            textSize = 12f; setTextColor(0xFF657184.toInt()); setPadding(0, dp(4), 0, 0)
        })
        settingsPanel.addView(bluetoothPanel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        val incomingFilesPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = panel(); blockPadding(this) }
        incomingFilesPanel.addView(android.widget.Switch(this).apply {
            text = "Принимать файлы автоматически"
            isChecked = autoAcceptFiles
            setOnCheckedChangeListener { _, enabled ->
                autoAcceptFiles = enabled
                getSharedPreferences("bridge_settings", MODE_PRIVATE).edit().putBoolean("auto_accept_files", enabled).apply()
                if (enabled) {
                    if (Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission()) {
                        requestAutoAcceptNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        ensureAutoReceiveNotificationChannel()
                    }
                    acceptIncomingFilesAutomatically(lastTransfers)
                }
            }
        })
        incomingFilesPanel.addView(TextView(this).apply {
            text = "Файлы от сопряжённого Mac будут сохраняться в Загрузки/SamsungMacBridge без запроса подтверждения."
            textSize = 12f; setTextColor(0xFF657184.toInt()); setPadding(0, dp(4), 0, 0)
        })
        settingsPanel.addView(incomingFilesPanel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        val compatibilityPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = panel(); blockPadding(this) }
        compatibilityPanel.addView(TextView(this).apply {
            text = getString(R.string.compatibility_title)
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFF182536.toInt())
            setPadding(0, 0, 0, dp(6))
        })
        compatibilityView = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF657184.toInt())
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        compatibilityPanel.addView(compatibilityView)
        compatibilityPanel.addView(compactButton(getString(R.string.compatibility_refresh)) { renderCompatibilityStatus() })
        compatibilityPanel.addView(compactButton(getString(R.string.compatibility_open_settings)) {
            runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
                .onFailure { Toast.makeText(this, "Не удалось открыть настройки Android.", Toast.LENGTH_LONG).show() }
        })
        settingsPanel.addView(compatibilityPanel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        statusView = TextView(this).apply {
            text = if (client.pair == null) "Передавайте файлы, ссылки и текст по своей сети Wi‑Fi" else ""
            textSize = 12f; setPadding(dp(4), dp(8), dp(4), dp(3))
            visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE }
                override fun afterTextChanged(s: android.text.Editable?) = Unit
            })
        }
        shell.addView(statusView, 2)
        val historyHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(20), dp(4), dp(8)) }
        historyHeader.addView(TextView(this).apply { text = "Недавние передачи"; textSize = 16f; setTextColor(0xFF182536.toInt()); setTypeface(null, android.graphics.Typeface.BOLD) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        openFolderAction = TextView(this).apply {
            text = "Папка"; textSize = 12f; setTextColor(0xFF216CDD.toInt()); gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(6), dp(8)); contentDescription = "Открыть папку полученных файлов"
            setOnClickListener { openReceivedFolder() }
        }
        historyHeader.addView(openFolderAction)
        historyAction = TextView(this).apply {
            text = "›"; textSize = 24f; setTextColor(0xFF8190A8.toInt()); setPadding(dp(8), dp(4), dp(8), dp(4)); contentDescription = "Показать все передачи"
            setOnClickListener { historyExpanded = !historyExpanded; text = if (historyExpanded) "Свернуть" else "›"; textSize = if (historyExpanded) 13f else 24f; contentDescription = if (historyExpanded) "Свернуть историю" else "Показать все передачи"; renderTransfers(lastTransfers) }
        }
        historyHeader.addView(historyAction)
        root.addView(historyHeader)
        cards = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = panel() }
        root.addView(cards)
        screenHost.addView(clipboardPanel)
        screenHost.addView(settingsPanel)
        val navigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(5), dp(8), dp(5))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.WHITE); cornerRadius = dp(24).toFloat()
            }
            elevation = 0f
        }
        val navItems = mutableListOf<LinearLayout>()
        val navLabels = mutableListOf<TextView>()
        val navIcons = mutableListOf<android.widget.ImageView>()
        val names = listOf("Главная", "Буфер", "Настройки")
        val icons = listOf(R.drawable.ic_nav_home, R.drawable.ic_action_clipboard, R.drawable.ic_nav_settings)
        names.forEachIndexed { index, label ->
            val icon = android.widget.ImageView(this).apply { setImageResource(icons[index]); contentDescription = null }
            val caption = TextView(this).apply { text = label; textSize = 13f; gravity = Gravity.CENTER; maxLines = 1 }
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; minimumHeight = dp(62)
                contentDescription = label; isClickable = true; isFocusable = true
                addView(icon, LinearLayout.LayoutParams(dp(23), dp(23)))
                addView(caption, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })
                setOnClickListener { showPage(index) }
            }
            navItems.add(item); navIcons.add(icon); navLabels.add(caption)
            navigation.addView(item, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        showPage = { page ->
            screenTitle.text = if (page == 0) getString(R.string.app_name) else names[page]
            root.visibility = if (page == 0) View.VISIBLE else View.GONE
            clipboardPanel.visibility = if (page == 1) View.VISIBLE else View.GONE
            settingsPanel.visibility = if (page == 2) View.VISIBLE else View.GONE
            navItems.forEachIndexed { index, item ->
                val selected = index == page
                item.isSelected = selected
                val color = if (selected) 0xFF216CDD.toInt() else 0xFF7D8BA0.toInt()
                navIcons[index].setColorFilter(color); navLabels[index].setTextColor(color)
                item.background = if (selected) android.graphics.drawable.GradientDrawable().apply { setColor(0xFFE8F0FD.toInt()); cornerRadius = dp(14).toFloat() } else null
            }
            if (page == 1) { renderClipboardHistory(); sizeClipboardHistory?.invoke() }
            scroll.scrollTo(0, 0)
        }
        val navigationArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(10))
            setBackgroundColor(backgroundColor); clipChildren = false; clipToPadding = false
            addView(navigation, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        shell.addView(navigationArea)
        showPage(if (client.pair == null) 2 else 0)
        setContentView(shell)
        AppLanguage.applyToViewTree(this, shell, setOfNotNull(cards, clipboardHistoryList))
        updateClipboardControls()
        renderTransfers(lastTransfers)
    }

    private fun enterInvitation() {
        val field = EditText(this).apply {
            hint = "Вставьте код из приложения Mac"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 3
        }
        AlertDialog.Builder(this).setTitle("Код подключения").setView(field)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Подключить") { _, _ ->
                val value = field.text.toString().trim()
                if (value.isNotEmpty()) withLocalNetworkPermission { pairFromQr(value, null) }
            }.show()
    }

    private fun startQrScan() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Отсканируйте QR-код из приложения Mac")
            setBeepEnabled(false)
            setOrientationLocked(false)
        }
        qrScanner.launch(options)
    }

    private fun hasLocalNetworkPermission(): Boolean = Build.VERSION.SDK_INT < 37 ||
        checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") == PackageManager.PERMISSION_GRANTED

    private fun setBluetoothMode(enabled: Boolean) {
        val preferences = BluetoothModePreferences(this)
        if (!enabled) {
            BluetoothBridgeService.stop(this)
            if (clipboardPreferences.enabled) ClipboardSyncService.transportChanged(this)
            mainHandler.removeCallbacks(poll)
            if (client.pair != null && hasLocalNetworkPermission()) startPolling()
            return
        }
        if (client.pair == null) {
            bluetoothModeSwitch?.isChecked = false
            Toast.makeText(this, "Сначала сопрягите приложения по QR-коду.", Toast.LENGTH_LONG).show()
            return
        }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            preferences.enabled = false
            bluetoothModeSwitch?.isChecked = false
            preferences.status = "Bluetooth LE недоступен на этом устройстве. Используйте Wi‑Fi."
            Toast.makeText(this, preferences.status, Toast.LENGTH_LONG).show()
            renderCompatibilityStatus()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val missing = arrayOf(android.Manifest.permission.BLUETOOTH_ADVERTISE, android.Manifest.permission.BLUETOOTH_CONNECT)
                .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) {
                preferences.enabled = false
                requestBluetoothPermissions.launch(missing.toTypedArray())
                return
            }
        }
        startBluetoothMode()
        if (clipboardPreferences.enabled) ClipboardSyncService.transportChanged(this)
        if (clipboardPreferences.enabled && !ClipboardSyncService.isRunning) runCatching { ClipboardSyncService.start(this) }
        startPolling()
    }

    private fun startBluetoothMode() {
        if (client.pair == null) {
            BluetoothModePreferences(this).enabled = false
            bluetoothModeSwitch?.isChecked = false
            Toast.makeText(this, "Сначала сопрягите приложения по QR-коду.", Toast.LENGTH_LONG).show()
            return
        }
        val capability = bluetoothCapability(this)
        if (capability.state != CompatibilityState.AVAILABLE) {
            val preferences = BluetoothModePreferences(this)
            preferences.enabled = false
            preferences.status = capability.description
            bluetoothModeSwitch?.isChecked = false
            Toast.makeText(this, capability.description, Toast.LENGTH_LONG).show()
            renderCompatibilityStatus()
            return
        }
        runCatching { BluetoothBridgeService.start(this) }
            .onFailure {
                BluetoothModePreferences(this).enabled = false
                bluetoothModeSwitch?.isChecked = false
                Toast.makeText(this, "Не удалось запустить Bluetooth: ${it.localizedMessage ?: "ошибка"}", Toast.LENGTH_LONG).show()
            }
    }

    private fun withLocalNetworkPermission(action: () -> Unit) {
        if (hasLocalNetworkPermission()) {
            action()
            return
        }
        permissionAction = action
        if (permissionPrompted) {
            AlertDialog.Builder(this)
                .setTitle("Нужен доступ к локальной сети")
                .setMessage("Откройте настройки приложения и разрешите доступ к локальной сети Wi‑Fi.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Открыть настройки") { _, _ ->
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
                .show()
            return
        }
        permissionPrompted = true
        AlertDialog.Builder(this)
            .setTitle("Доступ к локальной сети")
            .setMessage("Приложению нужен доступ к локальной сети Wi‑Fi, чтобы найти и подключить ваш Mac. Данные остаются между вашими устройствами.")
            .setNegativeButton("Пока нет") { _, _ -> statusView?.text = "Разрешите доступ к локальной сети, чтобы подключиться к Mac." }
            .setPositiveButton("Продолжить") { _, _ ->
                requestPermissions(arrayOf("android.permission.ACCESS_LOCAL_NETWORK"), REQUEST_LOCAL_NETWORK)
            }
            .show()
    }

    @Deprecated("Совместимость с запросом разрешения ACCESS_LOCAL_NETWORK")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_LOCAL_NETWORK) return
        val action = permissionAction.also { permissionAction = null }
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) action?.invoke()
        else statusView?.text = "Доступ не выдан. Его можно разрешить в настройках приложения → Разрешения."
    }

    private fun pairFromQr(raw: String, manualHost: String?) {
        statusView?.text = "Проверяю приглашение…"
        worker.execute {
            try {
                val invitation = Invitation.parse(raw, manualHost)
                val info = client.pair(invitation, AndroidDeviceIdentity.displayName(Build.MANUFACTURER, Build.MODEL))
                runOnUiThread {
                    deviceTitle?.text = info.name
                    deviceView?.text = "Подключено"
                    deviceView?.setTextColor(0xFF26834D.toInt())
                    pairButton?.visibility = View.GONE
                    manualPairButton?.visibility = View.GONE
                    editAddressButton?.visibility = View.VISIBLE
                    unpairButton?.visibility = View.VISIBLE
                    outgoingInput?.isEnabled = true
                    sendButton?.isEnabled = true
                    pickFilesButton?.isEnabled = true
                    statusView?.text = "Сопряжение подтверждено на Mac"
                    updateClipboardControls()
                    startPolling()
                }
            } catch (error: Exception) {
                runOnUiThread { showPairError(raw, manualHost, error) }
            }
        }
    }

    private fun showPairError(raw: String, previousHost: String?, error: Exception) {
        val reason = error.localizedMessage.orEmpty()
        if (reason.contains("отклонили", ignoreCase = true) || reason.contains("истекло", ignoreCase = true)) {
            AlertDialog.Builder(this).setTitle("Код подключения больше не действует")
                .setMessage("${reason}. Создайте новый QR-код на Mac и отсканируйте его.")
                .setPositiveButton("Понятно", null).show()
            return
        }
        val host = EditText(this).apply {
            hint = "Например, 192.168.1.20"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setText(previousHost ?: runCatching { Invitation.parse(raw).host }.getOrNull().orEmpty())
            setSelection(text.length)
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), 0, dp(22), 0); addView(TextView(this@MainActivity).apply { text = error.localizedMessage ?: "Не удалось подключиться" }); addView(host) }
        AlertDialog.Builder(this)
            .setTitle("Не удалось подключиться")
            .setMessage("Проверьте, что Mac и телефон находятся в одной сети. Если автоматический адрес недоступен, введите IP-адрес Mac.")
            .setView(content)
            .setNegativeButton("Закрыть", null)
            .setNeutralButton("Найти Mac в сети") { _, _ -> discoverMac(raw) }
            .setPositiveButton("Повторить") { _, _ -> withLocalNetworkPermission { pairFromQr(raw, host.text.toString().trim()) } }
            .show()
    }

    private fun discoverMac(raw: String) {
        withLocalNetworkPermission {
            val manager = getSystemService(Context.NSD_SERVICE) as NsdManager
            val addresses = ConcurrentHashMap<String, String>()
            val discovery = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) = Unit
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { stopDiscovery(manager, this) }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                override fun onDiscoveryStopped(serviceType: String) = Unit
                override fun onServiceLost(serviceInfo: NsdServiceInfo) { addresses.remove(serviceInfo.serviceName) }
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (!serviceInfo.serviceType.trimEnd('.').equals("_smbbridge._tcp", ignoreCase = true)) return
                    @Suppress("DEPRECATION")
                    manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            info.host?.hostAddress?.let { addresses[info.serviceName] = it }
                        }
                    })
                }
            }
            statusView?.text = "Ищу Mac в локальной сети…"
            try {
                manager.discoverServices("_smbbridge._tcp.", NsdManager.PROTOCOL_DNS_SD, discovery)
                mainHandler.postDelayed({
                    stopDiscovery(manager, discovery)
                    val found = addresses.entries.sortedBy { it.key }
                    if (found.isEmpty()) {
                        statusView?.text = "Mac не найден. Убедитесь, что приложение открыто и устройства в одной сети."
                    } else {
                        AlertDialog.Builder(this)
                            .setTitle("Выберите Mac")
                            .setItems(found.map { "${it.key} · ${it.value}" }.toTypedArray()) { _, index ->
                                pairFromQr(raw, found[index].value)
                            }
                            .setNegativeButton("Отмена", null)
                            .show()
                    }
                }, 6500)
            } catch (error: Exception) {
                statusView?.text = "Не удалось найти Mac: ${error.localizedMessage ?: "проверьте Wi‑Fi"}"
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun stopDiscovery(manager: NsdManager, listener: NsdManager.DiscoveryListener) {
        runCatching { manager.stopServiceDiscovery(listener) }
    }

    private fun editMacAddress() {
        val field = EditText(this).apply { hint = "Имя или IP-адрес Mac"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI; setText(client.pair?.host.orEmpty()) }
        AlertDialog.Builder(this).setTitle("Адрес Mac").setView(field)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить") { _, _ ->
                runCatching { client.changeHost(field.text.toString().trim()); statusView?.text = "Адрес обновлён"; withLocalNetworkPermission { startPolling() } }
                    .onFailure { statusView?.text = it.localizedMessage }
            }.show()
    }

    private fun unpair() {
        AlertDialog.Builder(this).setTitle("Удалить пару?")
            .setMessage("Телефон и Mac перестанут доверять друг другу.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Удалить") { _, _ -> worker.execute {
                clipboardPreferences.enabled = false
                clipboardPreferences.clearClipboardCursor()
                receiptStore.clear()
                pendingCancellations.clear()
                pendingUploads.clear()
                clipboardPreferences.status = "Сначала подключите Mac"
                runCatching { ClipboardSyncService.stop(this, clearCursor = true) }
                runCatching { client.forgetRemote() }
                runOnUiThread { setIntent(Intent(this, MainActivity::class.java)); recreate() }
            } }.show()
    }

    private fun handleShareIntent(incoming: Intent?) {
        if (incoming == null || incoming.action !in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return
        val sharedText = incoming.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        val files = when (incoming.action) {
            Intent.ACTION_SEND_MULTIPLE -> incoming.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            Intent.ACTION_SEND -> listOfNotNull(incoming.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            else -> emptyList()
        }
        val clipFiles = (0 until (incoming.clipData?.itemCount ?: 0)).mapNotNull { incoming.clipData?.getItemAt(it)?.uri }
        val allFiles = (files + clipFiles).distinct()
        if (sharedText.isEmpty() && allFiles.isEmpty()) return
        val fingerprint = incoming.action + "|" + allFiles.joinToString("|") + "|" + sharedText
        val now = SystemClock.elapsedRealtime()
        if (fingerprint == lastHandledShareFingerprint && now - lastHandledShareAt < 1_000) return
        lastHandledShareFingerprint = fingerprint
        lastHandledShareAt = now
        if (client.pair == null) {
            statusView?.text = "Сначала подключите Mac, затем повторите отправку."
            return
        }
        val sendShared = {
            if (sharedText.isNotEmpty()) worker.execute {
                runCatching { sendBridgeText(sharedText) }
                    .onSuccess { runOnUiThread { statusView?.text = "Текст отправлен на Mac" } }
                    .onFailure { runOnUiThread { statusView?.text = it.localizedMessage ?: "Не удалось отправить текст" } }
            }
            if (allFiles.isNotEmpty()) {
                allFiles.forEach { uri ->
                    if (incoming.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0) {
                        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    }
                }
                enqueueFiles(allFiles)
            }
        }
        if (BluetoothModePreferences(this).enabled) sendShared() else withLocalNetworkPermission(sendShared)
    }

    private fun queryDisplayName(uri: Uri): String {
        return runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "Файл"
    }

    private fun enqueueFiles(uris: List<Uri>) {
        val items = uris.distinct().map { QueuedFile(UUID.randomUUID().toString(), it, queryDisplayName(it)) }
        if (items.isEmpty()) return
        synchronized(queuedFiles) { queuedFiles.addAll(items) }
        renderTransfers(lastTransfers)
        maybePrepareNextFile()
    }

    private fun cancelQueuedFile(id: String) {
        synchronized(queuedFiles) {
            val item = queuedFiles.firstOrNull { it.id == id } ?: return
            if (item.state == "preparing") cancelledQueueIds.add(id)
            queuedFiles.removeAll { it.id == id }
        }
        runOnUiThread { renderTransfers(lastTransfers) }
    }

    private fun retryQueuedFile(id: String) {
        synchronized(queuedFiles) {
            val items = queuedFiles.toList()
            queuedFiles.clear()
            items.forEach { queuedFiles.add(if (it.id == id) it.copy(state = "waiting", error = null) else it) }
        }
        runOnUiThread { renderTransfers(lastTransfers) }
        maybePrepareNextFile()
    }

    private fun maybePrepareNextFile() {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            runOnUiThread { maybePrepareNextFile() }
            return
        }
        if (client.pair == null || preparingFile || preparedFiles.isNotEmpty()) return
        val entry = synchronized(queuedFiles) {
            val next = queuedFiles.firstOrNull { it.state == "waiting" } ?: return
            val updated = next.copy(state = "preparing")
            val items = queuedFiles.toList()
            queuedFiles.clear()
            items.forEach { queuedFiles.add(if (it.id == next.id) updated else it) }
            updated
        }
        preparingFile = true
        renderTransfers(lastTransfers)
        var blockedByActiveTransfer = false
        worker.execute {
            try {
                val bluetooth = BluetoothModePreferences(this@MainActivity).enabled
                val canStart = if (bluetooth) BluetoothBridgeRuntime.connected else {
                    runCatching { client.status().getJSONArray("transfers") }.getOrNull()
                        ?.let { arr -> (0 until arr.length()).none { i -> val t = arr.getJSONObject(i); t.optString("type") == "file" && t.optString("direction") == "toMac" && t.optString("state") in ACTIVE_STATES } } ?: true
                }
                if (!canStart) {
                    blockedByActiveTransfer = true
                    synchronized(queuedFiles) {
                        val items = queuedFiles.toList(); queuedFiles.clear()
                        items.forEach { queuedFiles.add(if (it.id == entry.id) it.copy(state = "waiting") else it) }
                    }
                    return@execute
                }
                val prepared = client.prepare(entry.uri)
                if (cancelledQueueIds.remove(entry.id)) {
                    synchronized(queuedFiles) { queuedFiles.removeAll { it.id == entry.id } }
                    return@execute
                }
                if (bluetooth) {
                    val id = UUID.randomUUID().toString().lowercase()
                    preparedFiles[id] = prepared
                    FileLocationStore(this@MainActivity).record(id, entry.uri, prepared.name)
                    cacheImagePreview(id, prepared.name, entry.uri)
                    synchronized(queuedFiles) { queuedFiles.removeAll { it.id == entry.id } }
                    val transfer = JSONObject().put("id", id).put("direction", "toMac").put("type", "file")
                        .put("name", prepared.name).put("size", prepared.size).put("sha256", prepared.hash)
                        .put("state", "offered").put("progress", 0).put("createdAt", System.currentTimeMillis() / 1000.0)
                    upsertBluetoothTransfer(transfer)
                    runOnUiThread { statusView?.text = "«${prepared.name}»: ожидает подтверждения на Mac"; renderTransfers(lastTransfers) }
                    transferWorker.execute { sendPreparedFileOverBluetooth(id, prepared) }
                    return@execute
                }
                val id = client.offer(prepared)
                preparedFiles[id] = prepared
                FileLocationStore(this@MainActivity).record(id, entry.uri, prepared.name)
                cacheImagePreview(id, prepared.name, entry.uri)
                synchronized(queuedFiles) { queuedFiles.removeAll { it.id == entry.id } }
                runOnUiThread { statusView?.text = "«${prepared.name}»: ожидает подтверждения на Mac" }
            } catch (error: Exception) {
                val wasCancelled = cancelledQueueIds.remove(entry.id)
                synchronized(queuedFiles) {
                    val items = queuedFiles.toList(); queuedFiles.clear()
                    items.forEach { item ->
                        when {
                            item.id != entry.id -> queuedFiles.add(item)
                            wasCancelled -> Unit
                            else -> queuedFiles.add(item.copy(state = "failed", error = error.localizedMessage ?: "Не удалось подготовить файл"))
                        }
                    }
                }
                runOnUiThread { statusView?.text = error.localizedMessage ?: "Не удалось подготовить файл" }
            } finally {
                runOnUiThread {
                    preparingFile = false
                    renderTransfers(lastTransfers)
                    if (!blockedByActiveTransfer) maybePrepareNextFile()
                }
            }
        }
    }

    private fun sendPreparedFileOverBluetooth(id: String, file: BridgeClient.Prepared) {
        val transfer = lastTransfers.findTransfer(id) ?: return
        try {
            val decision = JSONObject(BluetoothBridgeRuntime.sendFileAndAwait(
                BluetoothFileMessage.offer(id, file.name, file.size, file.hash), id, "decision"
            ))
            if (decision.optString("state") != "accepted") throw IOException("Получатель отклонил файл")
            transfer.put("state", "transferring")
            upsertBluetoothTransfer(transfer)
            val started = SystemClock.elapsedRealtime()
            val digest = MessageDigest.getInstance("SHA-256")
            var sent = 0L
            val buffer = ByteArray(BluetoothFileMessage.CHUNK_BYTES)
            (contentResolver.openInputStream(file.uri) ?: throw IOException("Файл недоступен")).use { input ->
                while (true) {
                    if (id in bluetoothCancelledTransfers) throw IOException("Передача отменена")
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    digest.update(buffer, 0, count)
                    val next = sent + count
                    val reply = JSONObject(BluetoothBridgeRuntime.sendFileAndAwait(
                        BluetoothFileMessage.chunk(id, sent, buffer.copyOf(count)), id, "chunk"
                    ))
                    require(reply.optString("state") == "accepted" && reply.optLong("nextOffset", -1) == next) { "Mac не подтвердил часть файла" }
                    sent = next
                    transfer.put("progress", sent)
                    upsertBluetoothTransfer(transfer)
                    updateTransferProgress(id, sent, file.size, started)
                }
            }
            require(sent == file.size && digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == file.hash) { "Исходный файл изменился во время передачи" }
            val result = JSONObject(BluetoothBridgeRuntime.sendFileAndAwait(
                BluetoothFileMessage.finish(id, sent, file.hash), id, "result"
            ))
            require(result.optString("state") == "complete" && result.optString("sha256") == file.hash) { "Mac не подтвердил сохранение файла" }
            transfer.put("state", "complete").put("progress", sent)
            runOnUiThread { statusView?.text = "Файл «${file.name}» передан на Mac" }
        } catch (error: Exception) {
            val cancelled = id in bluetoothCancelledTransfers || error.message?.contains("отмен", true) == true
            transfer.put("state", if (cancelled) "cancelled" else "failed")
            runOnUiThread { statusView?.text = error.localizedMessage ?: "Передача Bluetooth-файла прервана" }
            if (!cancelled) BluetoothBridgeRuntime.send(BluetoothFileMessage.cancel(id))
        } finally {
            preparedFiles.remove(id)
            bluetoothCancelledTransfers.remove(id)
            clearTransferProgress(id)
            upsertBluetoothTransfer(transfer)
            runOnUiThread { renderTransfers(lastTransfers); maybePrepareNextFile() }
        }
    }

    private fun openTransferFile(id: String, name: String) {
        val uri = FileLocationStore(this).find(id)
        if (uri == null) {
            AlertDialog.Builder(this).setTitle("Файл недоступен")
                .setMessage("Для этой передачи место файла не сохранено. Передайте файл снова или найдите его в приложении «Мои файлы».")
                .setPositiveButton("Понятно", null).show()
            return
        }
        val extension = name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: runCatching { contentResolver.getType(uri) }.getOrNull() ?: "application/octet-stream"
        try {
            contentResolver.openFileDescriptor(uri, "r")?.use { } ?: throw IOException("Файл недоступен")
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, "Открыть файл"))
        } catch (_: Exception) {
            AlertDialog.Builder(this).setTitle("Не удалось открыть файл")
                .setMessage("Файл мог быть перемещён или удалён, либо доступ к нему больше недоступен.")
                .setPositiveButton("Понятно", null).show()
        }
    }

    private fun openReceivedFolder() {
        val directory = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download/SamsungMacBridge")
        val folderIntent = Intent(Intent.ACTION_VIEW).setDataAndType(directory, "vnd.android.document/directory")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        // Package visibility can make resolveActivity() return null even when a file browser
        // can handle this URI. Try the common document browser packages directly first.
        for (fileBrowser in listOf("com.google.android.documentsui", "com.android.documentsui", "com.sec.android.app.myfiles")) {
            try {
                startActivity(Intent(folderIntent).setPackage(fileBrowser))
                return
            } catch (_: android.content.ActivityNotFoundException) {
                // This file browser is not installed or does not support directory URIs.
            } catch (_: SecurityException) {
                // Try another system file browser or the platform picker below.
            }
        }

        try {
            startActivity(folderIntent)
            return
        } catch (_: android.content.ActivityNotFoundException) {
            // Fall through to the system folder picker.
        } catch (_: SecurityException) {
            // Fall through to the system folder picker.
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                startActivity(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    .putExtra(DocumentsContract.EXTRA_INITIAL_URI, directory)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
                return
            } catch (_: android.content.ActivityNotFoundException) {
                // Keep the file list fallback for devices without a document picker.
            } catch (_: SecurityException) {
                // Keep the file list fallback for devices that deny access to this location.
            }
        }

        showReceivedFilesFallback()
    }

    private fun showReceivedFilesFallback() {
        val files = FileLocationStore(this).recentReceived()
        val path = "Путь: Загрузки/SamsungMacBridge"
        if (files.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Папка с полученными файлами")
                .setMessage("Файловый менеджер не смог открыть папку автоматически. $path")
                .setPositiveButton("Понятно", null).show()
            return
        }
        AlertDialog.Builder(this).setTitle("Полученные файлы")
            .setMessage(path)
            .setItems(files.map { it.name }.toTypedArray()) { _, index -> openTransferFile(files[index].id, files[index].name) }
            .setPositiveButton("Понятно", null).show()
    }

    private fun ensureAutoReceiveNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(AUTO_RECEIVE_CHANNEL, "Полученные файлы", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Сообщает о файлах, принятых автоматически от сопряжённого устройства"
        })
    }

    private fun notifyAutoReceivedFile(id: String, name: String) {
        if (Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission()) return
        val preferences = getSharedPreferences("bridge_auto_receive_notifications", MODE_PRIVATE)
        if (preferences.getBoolean(id, false)) return
        if (!preferences.edit().putBoolean(id, true).commit()) return
        val open = Intent(this, MainActivity::class.java).apply {
            action = ACTION_OPEN_RECEIVED_FILE
            putExtra(EXTRA_OPEN_TRANSFER_ID, id)
            putExtra(EXTRA_OPEN_TRANSFER_NAME, name)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(this, id.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = android.app.Notification.Builder(this, AUTO_RECEIVE_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Файл получен")
            .setContentText(name)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(id.hashCode(), notification)
    }

    private fun handleNotificationOpen(incoming: Intent?): Boolean {
        if (incoming?.action != ACTION_OPEN_RECEIVED_FILE) return false
        val id = incoming.getStringExtra(EXTRA_OPEN_TRANSFER_ID) ?: return true
        val name = incoming.getStringExtra(EXTRA_OPEN_TRANSFER_NAME) ?: "Файл"
        incoming.action = null
        incoming.removeExtra(EXTRA_OPEN_TRANSFER_ID)
        incoming.removeExtra(EXTRA_OPEN_TRANSFER_NAME)
        openTransferFile(id, name)
        return true
    }

    private fun isImageName(name: String): Boolean {
        val lower = name.lowercase(java.util.Locale.ROOT)
        return listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".heic").any { lower.endsWith(it) }
    }

    private fun previewFile(id: String): java.io.File? {
        if (!id.matches(Regex("[0-9a-fA-F-]{36}"))) return null
        return java.io.File(java.io.File(cacheDir, "transfer-previews"), "$id.png")
    }

    // Only previews of files the user selected or accepted; lack of access is harmless.
    private fun cacheImagePreview(id: String, name: String, uri: Uri) {
        if (!isImageName(name)) return
        runCatching {
            val output = previewFile(id) ?: return
            val directory = output.parentFile ?: return
            if (!directory.isDirectory && !directory.mkdirs()) return
            val bitmap = runCatching { contentResolver.loadThumbnail(uri, android.util.Size(96, 96), null) }.getOrNull() ?: run {
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return
                var sample = 1
                while (bounds.outWidth / sample > 192 || bounds.outHeight / sample > 192) sample *= 2
                val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, options) } ?: return
            }
            try { output.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it) } }
            finally { bitmap.recycle() }
            directory.listFiles()?.sortedByDescending { it.lastModified() }?.drop(30)?.forEach { it.delete() }
        }
    }

    private fun processTransfers(transfers: JSONArray) {
        if (BluetoothModePreferences(this).enabled) return
        acceptIncomingFilesAutomatically(transfers)
        for (i in 0 until transfers.length()) {
            val transfer = transfers.optJSONObject(i) ?: continue
            val id = transfer.optString("id")
            val state = transfer.optString("state")
            val direction = transfer.optString("direction")
            val kind = transfer.optString("type")
            if (direction == "toPhone" && kind != "file" && state == "offered" && seenText.add(id)) {
                try { client.acknowledge(id) } catch (_: Exception) { seenText.remove(id) }
            }
            if (direction == "toMac" && state == "accepted" && preparedFiles.containsKey(id) && attemptedUploads.add(id) && uploading.add(id)) {
                val prepared = preparedFiles[id] ?: continue
                transferWorker.execute {
                    val started = SystemClock.elapsedRealtime()
                    client.progress = { sent, total -> updateTransferProgress(id, sent, total, started) }
                    try {
                        pendingUploads.record(id, client.pair?.deviceId ?: throw IOException("Пара недоступна"))
                        client.upload(id, prepared)
                    }
                    catch (error: Exception) {
                        val outcome = runCatching { client.reconcileTransfer(id) }.recoverCatching {
                            val entries = client.status().getJSONArray("transfers")
                            val match = (0 until entries.length()).mapNotNull { entries.optJSONObject(it) }.firstOrNull { it.optString("id") == id }
                            transferOutcome(match?.optString("state"))
                        }.getOrDefault(TransferOutcome.UNKNOWN)
                        if (outcome in setOf(TransferOutcome.COMPLETE, TransferOutcome.FAILED, TransferOutcome.CANCELLED)) pendingUploads.remove(id)
                        val cancellationQueued = if (outcome == TransferOutcome.PENDING || outcome == TransferOutcome.UNKNOWN) {
                            runCatching { pendingCancellations.add(id, client.pair?.deviceId ?: throw IOException("Пара недоступна")) }.isSuccess
                        } else false
                        runOnUiThread { statusView?.text = when (outcome) {
                            TransferOutcome.COMPLETE -> "Файл сохранён на Mac."
                            TransferOutcome.FAILED -> "Передача не завершена. Проверьте соединение и отправьте файл снова."
                            TransferOutcome.CANCELLED -> "Передача отменена."
                            TransferOutcome.PENDING, TransferOutcome.UNKNOWN -> if (cancellationQueued) "Передача прервана. После восстановления связи приложение сверит результат и отмену." else "Результат передачи пока неизвестен. Проверьте состояние на Mac перед повтором."
                        } }
                    }
                    finally { uploading.remove(id); client.progress = null; runOnUiThread { clearTransferProgress(id); renderTransfers(lastTransfers) } }
                }
            }
            if (direction == "toMac" && state in TERMINAL_STATES) { preparedFiles.remove(id); attemptedUploads.remove(id); if (pendingUploads.contains(id, client.pair?.deviceId)) pendingUploads.remove(id) }
        }
    }

    private fun handleBluetoothMessage(data: ByteArray) {
        if (!BluetoothModePreferences(this).enabled) return
        val message = runCatching { BluetoothFileMessage.decode(data) }.getOrNull() ?: return
        when (message.optString("kind")) {
            "text.offer" -> handleBluetoothText(message)
            "file.offer" -> handleBluetoothOffer(message)
            "file.chunk" -> handleBluetoothChunk(message)
            "file.finish" -> finishBluetoothReceive(message)
            "file.cancel" -> handleBluetoothCancel(message.optString("id"))
        }
    }

    private fun handleBluetoothText(message: JSONObject) {
        val id = message.optString("id")
        val value = message.optString("text")
        val type = message.optString("type")
        if (runCatching { UUID.fromString(id) }.isFailure || type !in setOf("text", "link") ||
            value.isEmpty() || value.toByteArray(Charsets.UTF_8).size > 1024 * 1024) {
            BluetoothBridgeRuntime.send(BluetoothFileMessage.reply(id, "text", "rejected"))
            return
        }
        if (!lastTransfers.hasTransfer(id)) {
            val transfer = JSONObject().put("id", id).put("direction", "toPhone").put("type", type)
                .put("text", value).put("size", value.toByteArray(Charsets.UTF_8).size)
                .put("state", "complete").put("createdAt", System.currentTimeMillis() / 1000.0)
            upsertBluetoothTransfer(transfer)
            runOnUiThread { renderTransfers(lastTransfers); statusView?.text = "Получен текст с Mac" }
        }
        BluetoothBridgeRuntime.send(BluetoothFileMessage.reply(id, "text", "accepted"))
    }

    private fun sendBridgeText(value: String): String {
        if (!BluetoothModePreferences(this).enabled) return client.sendText(value)
        require(value.isNotEmpty() && value.toByteArray(Charsets.UTF_8).size <= 1024 * 1024) { "Текст пустой или больше 1 МиБ" }
        val id = UUID.randomUUID().toString().lowercase()
        val type = if (value.startsWith("https://") || value.startsWith("http://")) "link" else "text"
        val message = JSONObject().put("kind", "text.offer").put("id", id).put("type", type).put("text", value)
        val reply = JSONObject(BluetoothBridgeRuntime.sendFileAndAwait(message.toString().toByteArray(Charsets.UTF_8), id, "text", 120_000))
        require(reply.optString("state") == "accepted") { "Mac не принял текст" }
        upsertBluetoothTransfer(JSONObject().put("id", id).put("direction", "toMac").put("type", type)
            .put("text", value).put("size", value.toByteArray(Charsets.UTF_8).size)
            .put("state", "complete").put("createdAt", System.currentTimeMillis() / 1000.0))
        runOnUiThread { renderTransfers(lastTransfers) }
        return id
    }

    private fun handleBluetoothOffer(offer: JSONObject) {
        val id = offer.optString("id")
        val name = offer.optString("name")
        val size = offer.optLong("size", -1)
        val sha = offer.optString("sha256")
        if (runCatching { UUID.fromString(id) }.isFailure || name.isBlank() || name.length > 180 || name.any { it == '/' || it == '\\' || it.isISOControl() } ||
            size !in 0..BluetoothFileMessage.MAX_FILE_BYTES || !sha.matches(Regex("[a-f0-9]{64}"))) {
            BluetoothBridgeRuntime.send(BluetoothFileMessage.reply(id, "decision", "rejected"))
            return
        }
        if (lastTransfers.hasTransfer(id)) return
        val transfer = JSONObject()
            .put("id", id).put("direction", "toPhone").put("type", "file")
            .put("name", name).put("size", size).put("sha256", sha)
            .put("state", "offered").put("progress", 0).put("startedAt", SystemClock.elapsedRealtime()).put("createdAt", System.currentTimeMillis() / 1000.0)
        upsertBluetoothTransfer(transfer)
        runOnUiThread {
            renderTransfers(lastTransfers)
            statusView?.text = if (autoAcceptFiles) "Принимаю «$name» по Bluetooth…" else "Файл «$name» ждёт подтверждения на телефоне"
        }
        if (autoAcceptFiles) receiveFile(JSONObject(transfer.toString()), automatically = true)
    }

    private fun handleBluetoothChunk(message: JSONObject) {
        val id = message.optString("id")
        val session = bluetoothReceiveSessions[id]
        if (session == null) {
            BluetoothBridgeRuntime.send(BluetoothFileMessage.reply(id, "chunk", "rejected", 0))
            return
        }
        try {
            val bytes = BluetoothFileMessage.data(message)
            synchronized(session) {
                val offset = message.optLong("offset", -1)
                require(offset == session.received && session.received + bytes.size <= session.transfer.optLong("size")) { "Нарушена последовательность частей файла" }
                session.output.write(bytes)
                session.digest.update(bytes)
                session.received += bytes.size
            }
            BluetoothBridgeRuntime.send(BluetoothFileMessage.reply(id, "chunk", "accepted", session.received))
            val started = (session.transfer.optLong("startedAt", SystemClock.elapsedRealtime()))
            updateTransferProgress(id, session.received, session.transfer.optLong("size"), started)
            session.transfer.put("progress", session.received).put("state", "transferring")
            upsertBluetoothTransfer(session.transfer)
        } catch (_: Exception) {
            BluetoothBridgeRuntime.send(BluetoothFileMessage.reply(id, "chunk", "rejected", session.received))
            failBluetoothReceive(id, session, "Часть файла повреждена")
        }
    }

    private fun finishBluetoothReceive(message: JSONObject) {
        val id = message.optString("id")
        val session = bluetoothReceiveSessions[id] ?: run {
            BluetoothBridgeRuntime.send(BluetoothFileMessage.reply(id, "result", "rejected"))
            return
        }
        transferWorker.execute {
            try {
                synchronized(session) { session.output.flush(); session.output.fd.sync(); session.output.close() }
                val expectedSize = session.transfer.optLong("size")
                val expectedHash = session.transfer.optString("sha256")
                val actualHash = session.digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                require(message.optLong("size", -1) == expectedSize && session.received == expectedSize) { "Размер файла не совпадает" }
                require(message.optString("sha256") == expectedHash && actualHash == expectedHash) { "Контрольная сумма файла не совпадает" }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, session.transfer.optString("name", "Файл"))
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SamsungMacBridge")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("Не удалось создать файл в Загрузках")
                try {
                    contentResolver.openOutputStream(uri, "w")?.use { output -> session.temporary.inputStream().use { it.copyTo(output) } }
                        ?: throw IOException("Не удалось открыть файл в Загрузках")
                    if (contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) != 1) throw IOException("Не удалось сохранить файл")
                } catch (error: Exception) { contentResolver.delete(uri, null, null); throw error }
                FileLocationStore(this@MainActivity).record(id, uri, session.transfer.optString("name"), received = true)
                cacheImagePreview(id, session.transfer.optString("name"), uri)
                session.transfer.put("state", "complete").put("progress", expectedSize)
                upsertBluetoothTransfer(session.transfer)
                BluetoothBridgeRuntime.send(BluetoothFileMessage.reply(id, "result", "complete", sha256 = expectedHash))
                if (autoAcceptedReceives.contains(id)) notifyAutoReceivedFile(id, session.transfer.optString("name", "Файл"))
                runOnUiThread { statusView?.text = "Файл «${session.transfer.optString("name") }» сохранён в Загрузках"; renderTransfers(lastTransfers) }
            } catch (error: Exception) {
                failBluetoothReceive(id, session, error.localizedMessage ?: "Не удалось сохранить файл")
                BluetoothBridgeRuntime.send(BluetoothFileMessage.reply(id, "result", "failed"))
            } finally {
                bluetoothReceiveSessions.remove(id, session)
                session.temporary.delete()
                receiving.remove(id)
                autoAcceptedReceives.remove(id)
                clearTransferProgress(id)
                runOnUiThread { renderTransfers(lastTransfers) }
            }
        }
    }

    private fun failBluetoothReceive(id: String, session: BluetoothReceiveSession, reason: String) {
        runCatching { session.output.close() }
        session.temporary.delete()
        bluetoothReceiveSessions.remove(id, session)
        receiving.remove(id)
        autoAcceptedReceives.remove(id)
        session.transfer.put("state", "failed")
        upsertBluetoothTransfer(session.transfer)
        runOnUiThread { statusView?.text = reason; renderTransfers(lastTransfers) }
    }

    private fun handleBluetoothCancel(id: String) {
        val session = bluetoothReceiveSessions.remove(id)
        if (session != null) {
            runCatching { session.output.close() }; session.temporary.delete(); receiving.remove(id); autoAcceptedReceives.remove(id)
            session.transfer.put("state", "cancelled"); upsertBluetoothTransfer(session.transfer)
            BluetoothBridgeRuntime.send(BluetoothFileMessage.reply(id, "result", "cancelled"))
        } else {
            bluetoothCancelledTransfers.add(id)
            BluetoothBridgeRuntime.send(BluetoothFileMessage.reply(id, "decision", "rejected"))
            BluetoothBridgeRuntime.send(BluetoothFileMessage.reply(id, "chunk", "cancelled"))
            BluetoothBridgeRuntime.send(BluetoothFileMessage.reply(id, "result", "cancelled"))
            lastTransfers.findTransfer(id)?.put("state", "cancelled")
            runOnUiThread { renderTransfers(lastTransfers) }
        }
    }

    private fun upsertBluetoothTransfer(transfer: JSONObject) {
        val id = transfer.optString("id")
        synchronized(lastTransfers) {
            val existing = (0 until lastTransfers.length()).firstOrNull { lastTransfers.optJSONObject(it)?.optString("id") == id }
            if (existing == null) lastTransfers.put(transfer) else lastTransfers.put(existing, transfer)
        }
    }

    private fun JSONArray.hasTransfer(id: String): Boolean = synchronized(this) {
        (0 until length()).any { optJSONObject(it)?.optString("id") == id }
    }
    private fun JSONArray.findTransfer(id: String): JSONObject? = synchronized(this) {
        (0 until length()).mapNotNull { optJSONObject(it) }.firstOrNull { it.optString("id") == id }
    }

    private fun acceptIncomingFilesAutomatically(transfers: JSONArray) {
        if (!autoAcceptFiles || BackgroundTransferService.isRunning) return
        for (i in 0 until transfers.length()) {
            val transfer = transfers.optJSONObject(i) ?: continue
            if (transfer.optString("direction") == "toPhone" && transfer.optString("type") == "file" && transfer.optString("state") == "offered") {
                receiveFile(JSONObject(transfer.toString()), automatically = true)
            }
        }
    }

    private fun renderTransfers(transfers: JSONArray) {
        val list = cards ?: return
        list.removeAllViews()
        val queue = synchronized(queuedFiles) { queuedFiles.toList() }
        if (queue.isNotEmpty()) {
            list.addView(TextView(this).apply {
                text = "Очередь отправки · ${queue.size}"
                textSize = 13f; setTextColor(0xFF182536.toInt()); setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(dp(12), dp(10), dp(12), dp(6))
            })
            queue.forEach { queued ->
                val item = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10)) }
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                val icon = android.widget.ImageView(this).apply { setImageResource(R.drawable.ic_action_file); setPadding(dp(8), dp(8), dp(8), dp(8)); background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFFEAF2FF.toInt()); cornerRadius = dp(8).toFloat() } }
                row.addView(icon, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(10) })
                val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                details.addView(TextView(this).apply { text = queued.name; textSize = 13f; setTextColor(0xFF182536.toInt()); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
                details.addView(TextView(this).apply {
                    text = when (queued.state) {
                        "preparing" -> "Подготовка файла…"
                        "failed" -> "Ошибка · ${queued.error ?: "Не удалось подготовить файл"}"
                        else -> "В очереди"
                    }
                    textSize = 11f; setTextColor(if (queued.state == "failed") 0xFFAF555F.toInt() else 0xFF657184.toInt()); maxLines = 2
                })
                row.addView(details, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                item.addView(row)
                val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, dp(5), 0, 0) }
                if (queued.state == "failed") actions.addView(Button(this).apply { text = "Повторить"; isAllCaps = false; setOnClickListener { retryQueuedFile(queued.id) } })
                actions.addView(Button(this).apply {
                    text = if (queued.state == "preparing") "Отменить подготовку" else "Убрать"
                    isAllCaps = false
                    setOnClickListener { cancelQueuedFile(queued.id) }
                })
                item.addView(actions)
                list.addView(item)
                list.addView(View(this).apply { setBackgroundColor(0xFFEBEDF2.toInt()) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
            }
        }
        if (transfers.length() == 0 && queue.isEmpty()) {
            list.addView(TextView(this).apply { text = "Пока нет передач"; setPadding(0, dp(8), 0, dp(8)) })
            return
        }
        if (transfers.length() == 0) return
        var completedShown = 0
        for (i in transfers.length() - 1 downTo 0) {
            val t = transfers.optJSONObject(i) ?: continue
            val id = t.optString("id")
            val kind = t.optString("type")
            val direction = t.optString("direction")
            val state = t.optString("state")
            val needsAttention = state !in TERMINAL_STATES || state == "unknown" || pendingCancellations.contains(id) || pendingUploads.contains(id, client.pair?.deviceId)
            if (!needsAttention && !historyExpanded && completedShown >= 3) continue
            if (!needsAttention) completedShown++
            val name = if (kind == "file") t.optString("name") else if (kind == "link") "Ссылка" else "Текст"
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(9), dp(12), dp(9)) }
            val waitingForCancellation = pendingCancellations.contains(id)
            val waitingForResult = direction == "toMac" && kind == "file" && state !in TERMINAL_STATES && !uploading.contains(id) && pendingUploads.contains(id, client.pair?.deviceId)
            val displayState = when {
                waitingForCancellation -> "Передача прервана · ждём подтверждения отмены"
                waitingForResult -> "Результат неизвестен · проверьте на Mac"
                autoAcceptFiles && direction == "toPhone" && kind == "file" && state == "offered" -> "Принимается автоматически"
                else -> translateState(state)
            }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val icon = android.widget.ImageView(this).apply {
                val lowerName = name.lowercase(java.util.Locale.ROOT)
                val isImage = kind == "file" && isImageName(name)
                val isPdf = kind == "file" && lowerName.endsWith(".pdf")
                val preview = if (isImage) previewFile(id)?.takeIf { it.isFile }?.let { android.graphics.BitmapFactory.decodeFile(it.absolutePath) } else null
                if (preview != null) { setImageBitmap(preview); scaleType = android.widget.ImageView.ScaleType.CENTER_CROP }
                else setImageResource(when { isImage -> R.drawable.ic_transfer_image; isPdf -> R.drawable.ic_transfer_pdf; kind == "file" -> R.drawable.ic_action_file; else -> R.drawable.ic_action_text })
                setPadding(if (isImage || isPdf) 0 else dp(8), if (isImage || isPdf) 0 else dp(8), if (isImage || isPdf) 0 else dp(8), if (isImage || isPdf) 0 else dp(8))
                clipToOutline = true
                background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFFEAF2FF.toInt()); cornerRadius = dp(8).toFloat() }
                contentDescription = null
            }
            row.addView(icon, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(10) })
            val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            if (kind == "file") {
                details.setOnClickListener { openTransferFile(id, name) }
                icon.setOnClickListener { openTransferFile(id, name) }
                details.contentDescription = "Открыть файл: $name"
            }
            row.addView(details, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(row)
            details.addView(TextView(this).apply { text = name; textSize = 13f; setTextColor(0xFF182536.toInt()); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE; setTypeface(null, android.graphics.Typeface.NORMAL) })
            val directionText = if (direction == "toMac") "На Mac" else "С Mac"
            val sizeText = if (kind == "file") formatBytes(t.optLong("size")) else ""
            val createdSeconds = t.optDouble("createdAt", 0.0)
            val createdMillis = if (createdSeconds.isFinite() && createdSeconds > 0) (createdSeconds * 1000).toLong() else 0L
            val timeText = if (createdMillis > 0) {
                val date = Date(createdMillis)
                val day = if (android.text.format.DateUtils.isToday(createdMillis)) "Сегодня" else DateFormat.getDateInstance(DateFormat.SHORT).format(date)
                "$day, ${DateFormat.getTimeInstance(DateFormat.SHORT).format(date)}"
            } else ""
            if (kind != "file" || state == "complete") {
                details.addView(TextView(this).apply { text = listOf(sizeText, timeText).filter { it.isNotEmpty() }.joinToString(" · "); textSize = 11f; setTextColor(0xFF8190A8.toInt()) })
            }
            val liveProgress = liveTransferProgress[id]
            if (kind == "file" && (state in setOf("accepted", "transferring") || liveProgress != null)) {
                val shownProgress = liveProgress?.sent ?: t.optLong("progress")
                val shownSize = liveProgress?.total ?: t.optLong("size")
                details.addView(TextView(this).apply {
                    text = "${formatFileProgress(shownProgress, shownSize)} из ${formatFileProgress(shownSize, shownSize)}"
                    textSize = 11f; setTextColor(0xFF657184.toInt())
                })
                liveProgress?.let { progress ->
                    val detail = progressDetails(progress.sent, progress.total, progress.startedAt)
                    if (detail.isNotEmpty()) details.addView(TextView(this).apply {
                        text = detail; textSize = 10f; setTextColor(0xFF657184.toInt()); maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                }
                val progressBar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    progress = ((shownProgress.coerceAtLeast(0).toDouble() / shownSize.coerceAtLeast(1)) * 100).toInt().coerceIn(0, 100)
                    progressTintList = android.content.res.ColorStateList.valueOf(0xFF2877E8.toInt())
                    progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0xFFE5EAF1.toInt())
                }
                details.addView(progressBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4)).apply { topMargin = dp(4) })
            }
            if (kind != "file") {
                val text = t.optString("text")
                if (text.isNotEmpty()) details.addView(TextView(this).apply { this.text = text; setTextIsSelectable(true); setPadding(0, dp(4), 0, dp(4)) })
            }
            val cancelUnderDecision = direction == "toMac" && kind == "file" && state == "offered" && !waitingForCancellation && !waitingForResult
            val cancelUnderSending = direction == "toMac" && kind == "file" && (state == "transferring" || liveProgress != null) && !waitingForCancellation && !waitingForResult
            val cancelUnderStatus = cancelUnderDecision || cancelUnderSending
            val stateColumn = LinearLayout(this).apply {
                orientation = if (cancelUnderStatus) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(stateColumn, LinearLayout.LayoutParams(dp(115), LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
            if (cancelUnderDecision) {
                val decision = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
                decision.addView(TextView(this).apply {
                    text = displayState; textSize = 11f; setTextColor(0xFF657184.toInt()); setPadding(dp(8), 0, 0, 0)
                })
                decision.addView(themedCancelButton { cancelTransfer(id) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(34)).apply {
                    topMargin = dp(7); marginStart = dp(8)
                })
                stateColumn.addView(decision, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            } else if (cancelUnderSending) {
                val sendingStatus = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
                sendingStatus.addView(TextView(this).apply {
                    text = "↑ Отправка"
                    textSize = 10f; gravity = Gravity.CENTER; setTextColor(0xFF216CDD.toInt())
                    setPadding(dp(8), dp(5), dp(8), dp(5))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(0xFFEAF2FF.toInt()); cornerRadius = dp(12).toFloat()
                    }
                })
                sendingStatus.addView(themedCancelButton { cancelTransfer(id) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(34)).apply {
                    topMargin = dp(7)
                })
                stateColumn.addView(sendingStatus, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            } else if (state == "complete" && !waitingForCancellation && !waitingForResult) {
                val check = TextView(this).apply {
                    text = "✓"; textSize = 16f; gravity = Gravity.CENTER; setTextColor(0xFF258150.toInt()); contentDescription = "Завершено"
                    background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0xFFE2F4E9.toInt()) }
                }
                stateColumn.addView(check, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginStart = dp(5); marginEnd = dp(7) })
                val result = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                result.addView(TextView(this).apply { text = if (direction == "toMac") "Отправлено" else "Получено"; textSize = 10f; setTextColor(0xFF182536.toInt()) })
                result.addView(TextView(this).apply { text = directionText; textSize = 10f; setTextColor(0xFF8190A8.toInt()) })
                stateColumn.addView(result, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            } else if (kind == "file" && (state == "transferring" || liveProgress != null)) {
                val sending = direction == "toMac"
                stateColumn.addView(TextView(this).apply {
                    text = if (sending) "↑ Отправка" else "↓ Получение"
                    textSize = 10f; gravity = Gravity.CENTER; setTextColor(0xFF216CDD.toInt())
                    setPadding(dp(8), dp(5), dp(8), dp(5))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(0xFFEAF2FF.toInt()); cornerRadius = dp(12).toFloat()
                    }
                })
            } else {
                stateColumn.addView(TextView(this).apply { text = displayState; textSize = 11f; setTextColor(0xFF657184.toInt()); setPadding(dp(8), 0, 0, 0) })
            }
            val more = TextView(this).apply {
                text = "⋮"; textSize = 20f; gravity = Gravity.CENTER; setTextColor(0xFF8190A8.toInt()); contentDescription = "Действия: $name"
                setOnClickListener { anchor ->
                    val menu = android.widget.PopupMenu(this@MainActivity, anchor)
                    menu.menu.add("Сведения")
                    if (kind == "file") { menu.menu.add("Открыть файл"); menu.menu.add("Копировать название") }
                    menu.setOnMenuItemClickListener { item ->
                        when (item.title.toString()) {
                            "Сведения" -> { AlertDialog.Builder(this@MainActivity).setTitle(name).setMessage("$displayState\n$directionText" + if (sizeText.isNotEmpty()) "\n$sizeText" else "").setPositiveButton("Понятно", null).show(); true }
                            "Открыть файл" -> { openTransferFile(id, name); true }
                            "Копировать название" -> { copyText(name); true }
                            else -> false
                        }
                    }
                    menu.show()
                }
            }
            row.addView(more, LinearLayout.LayoutParams(dp(36), dp(48)))
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
            if (direction == "toPhone" && kind == "file" && state == "offered" && !autoAcceptFiles) {
                actions.addView(Button(this@MainActivity).apply { text = "Сохранить файл"; setOnClickListener { confirmIncomingFile(JSONObject(t.toString())) } })
                actions.addView(Button(this@MainActivity).apply { text = "Отклонить"; setOnClickListener { cancelTransfer(id) } })
            }
            if (!waitingForCancellation && direction == "toPhone" && kind == "file" && state in setOf("accepted", "transferring")) {
                actions.gravity = Gravity.START
                actions.addView(themedCancelButton { cancelTransfer(id) })
            }
            if (direction == "toPhone" && kind != "file" && t.optString("text").isNotEmpty()) {
                actions.addView(Button(this).apply { text = "Копировать"; setOnClickListener { copyText(t.optString("text")) } })
                if (kind == "link") actions.addView(Button(this@MainActivity).apply { text = "Открыть"; setOnClickListener { openLink(t.optString("text")) } })
            }
            if (!waitingForCancellation && !cancelUnderStatus && direction == "toMac" && kind == "file" && state in ACTIVE_STATES) {
                actions.gravity = Gravity.START
                actions.addView(themedCancelButton { cancelTransfer(id) })
            }
            if (actions.childCount > 0) card.addView(actions)
            val separator = View(this).apply { setBackgroundColor(0xFFEBEDF2.toInt()) }
            list.addView(card)
            list.addView(separator, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
        }
    }

    private fun themedCancelButton(onCancel: () -> Unit): Button = Button(this).apply {
        text = "× Отмена"
        isAllCaps = false
        textSize = 12f
        setTextColor(0xFFAF555F.toInt())
        setPadding(dp(12), 0, dp(12), 0)
        minWidth = 0
        minimumWidth = 0
        minHeight = dp(34)
        minimumHeight = dp(34)
        backgroundTintList = null
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(0xFFFFF3F4.toInt())
            setStroke(dp(1), 0xFFE6C4C8.toInt())
        }
        stateListAnimator = null
        elevation = 0f
        contentDescription = "Отменить передачу"
        setOnClickListener { onCancel() }
    }

    private fun confirmIncomingFile(transfer: JSONObject) {
        AlertDialog.Builder(this)
            .setTitle("Принять файл?")
            .setMessage("Файл «${transfer.optString("name")}» будет сохранён в папку Загрузки/SamsungMacBridge.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить") { _, _ -> receiveFile(transfer) }
            .show()
    }

    private fun receiveFile(transfer: JSONObject, automatically: Boolean = false) {
        if (BluetoothModePreferences(this).enabled) {
            receiveBluetoothFile(transfer, automatically)
            return
        }
        val id = transfer.optString("id")
        if (!receiving.add(id)) return
        if (automatically) autoAcceptedReceives.add(id)
        transferWorker.execute {
            var temporary: java.io.File? = null
            var destination: Uri? = null
            var published = false
            try {
                client.accept(id)
                val started = SystemClock.elapsedRealtime()
                client.progress = { sent, total -> updateTransferProgress(id, sent, total, started) }
                val received = client.download(id, transfer.optLong("size"), transfer.optString("sha256"))
                temporary = received
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, transfer.optString("name", "файл"))
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SamsungMacBridge")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val fileUri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("Не удалось создать файл в Загрузках")
                destination = fileUri
                contentResolver.openOutputStream(fileUri, "w")?.use { output ->
                    received.inputStream().use { input ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            if (client.cancelRequested) throw IOException("Отменено")
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count > 0) output.write(buffer, 0, count)
                        }
                    }
                } ?: throw IOException("Не удалось открыть файл в Загрузках")
                synchronized(publicationLock) {
                    if (cancelledReceives.contains(id)) throw IOException("Отменено")
                    receiptStore.record(id, transfer.optString("sha256"), fileUri, client.pair?.deviceId ?: throw IOException("Пара с Mac недоступна"))
                    val visible = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    if (contentResolver.update(fileUri, visible, null, null) != 1) throw IOException("Не удалось сохранить файл в Загрузках")
                    published = true
                }
                FileLocationStore(this@MainActivity).record(id, fileUri, transfer.optString("name", "Файл"), received = true)
                val acknowledged = runCatching { client.acknowledge(id, transfer.optString("sha256")) }.isSuccess
                if (acknowledged) receiptStore.remove(id)
                cacheImagePreview(id, transfer.optString("name"), fileUri)
                if (autoAcceptedReceives.contains(id)) notifyAutoReceivedFile(id, transfer.optString("name", "Файл"))
                runOnUiThread {
                    statusView?.text = if (acknowledged) "Файл «${transfer.optString("name")}» сохранён в Загрузках"
                    else "Файл сохранён, но Mac не получил подтверждение."
                }
            } catch (error: Exception) {
                val queued = runCatching { pendingCancellations.add(id, client.pair?.deviceId ?: throw IOException("Пара недоступна")) }.isSuccess
                runOnUiThread {
                    statusView?.text = if (queued) "Приём прерван. После восстановления связи приложение сверит отмену." else "Приём прерван. Проверьте состояние файла на Mac."
                    renderTransfers(lastTransfers)
                }
            } finally {
                if (!published) { destination?.let { runCatching { contentResolver.delete(it, null, null) } }; receiptStore.remove(id) }
                temporary?.delete()
                receiving.remove(id)
                autoAcceptedReceives.remove(id)
                cancelledReceives.remove(id)
                client.progress = null
                runOnUiThread { clearTransferProgress(id); renderTransfers(lastTransfers) }
            }
        }
    }

    private fun receiveBluetoothFile(transfer: JSONObject, automatically: Boolean = false) {
        val id = transfer.optString("id")
        if (!BluetoothBridgeRuntime.connected || !receiving.add(id)) return
        if (automatically) autoAcceptedReceives.add(id)
        transferWorker.execute {
            var session: BluetoothReceiveSession? = null
            try {
                val temporary = File.createTempFile("smb-${id.take(8)}-", ".part", cacheDir)
                val created = BluetoothReceiveSession(
                    JSONObject(transfer.toString()).put("state", "transferring").put("progress", 0).put("startedAt", SystemClock.elapsedRealtime()),
                    temporary,
                    FileOutputStream(temporary),
                    MessageDigest.getInstance("SHA-256")
                )
                session = created
                bluetoothReceiveSessions[id] = created
                val sent = BluetoothBridgeRuntime.send(BluetoothFileMessage.reply(id, "decision", "accepted"))
                if (!sent) throw IOException("Не удалось подтвердить приём по Bluetooth")
                upsertBluetoothTransfer(created.transfer)
                runOnUiThread { statusView?.text = "Принимаю «${transfer.optString("name") }» по Bluetooth…"; renderTransfers(lastTransfers) }
            } catch (error: Exception) {
                session?.let { failBluetoothReceive(id, it, error.localizedMessage ?: "Не удалось начать приём") }
                if (session == null) {
                    receiving.remove(id)
                    autoAcceptedReceives.remove(id)
                    BluetoothBridgeRuntime.send(BluetoothFileMessage.reply(id, "decision", "rejected"))
                }
            }
        }
    }

    private fun cancelTransfer(id: String) {
        if (BluetoothModePreferences(this).enabled) {
            val transfer = lastTransfers.findTransfer(id)
            bluetoothCancelledTransfers.add(id)
            if (transfer?.optString("direction") == "toPhone" && transfer.optString("state") == "offered") {
                BluetoothBridgeRuntime.send(BluetoothFileMessage.reply(id, "decision", "rejected"))
                transfer.put("state", "rejected")
            } else {
                BluetoothBridgeRuntime.send(BluetoothFileMessage.cancel(id))
                transfer?.put("state", "cancelled")
            }
            val session = bluetoothReceiveSessions.remove(id)
            if (session != null) {
                runCatching { session.output.close() }; session.temporary.delete()
                receiving.remove(id); autoAcceptedReceives.remove(id)
            }
            preparedFiles.remove(id)
            runOnUiThread { statusView?.text = "Передача отменена"; renderTransfers(lastTransfers) }
            return
        }
        synchronized(publicationLock) {
            if (receiptStore.contains(id)) {
                statusView?.text = "Файл уже сохранён или его результат уточняется. Дождитесь подтверждения Mac."
                return
            }
            if (!cancelling.add(id)) return
            if (receiving.contains(id)) cancelledReceives.add(id)
            client.cancel()
        }
        transferWorker.execute {
            try {
                val queued = runCatching { pendingCancellations.add(id, client.pair?.deviceId ?: throw IOException("Пара недоступна")) }.isSuccess
                if (queued) runCatching { pendingCancellations.reconcile(client) }
                runOnUiThread {
                    statusView?.text = if (queued) "Отмена запрошена. При отсутствии связи дождитесь сверки с Mac." else "Не удалось сохранить отмену. Проверьте состояние на Mac."
                    renderTransfers(lastTransfers)
                }
            } finally { cancelling.remove(id); preparedFiles.remove(id) }
        }
    }

    private fun updateTransferProgress(id: String, sent: Long, total: Long, started: Long) {
        runOnUiThread {
            statusView?.text = ""
            liveTransferProgress[id] = LiveTransferProgress(sent, total, started)
            val now = SystemClock.elapsedRealtime()
            val previous = lastProgressRenderAt[id] ?: 0L
            if (now - previous >= 200 || sent >= total) {
                lastProgressRenderAt[id] = now
                renderTransfers(lastTransfers)
            }
        }
    }

    private fun clearTransferProgress(id: String) {
        liveTransferProgress.remove(id)
        lastProgressRenderAt.remove(id)
    }

    private fun progressDetails(count: Long, total: Long, started: Long): String {
        val elapsed = (SystemClock.elapsedRealtime() - started) / 1000.0
        if (elapsed < 1 || count <= 0) return ""
        val rate = count / elapsed
        val seconds = ((total - count).coerceAtLeast(0) / rate).toLong().coerceAtMost(31_536_000)
        val remaining = if (seconds < 60) "${seconds.coerceAtLeast(1)} с" else if (seconds < 3600) "${(seconds / 60).coerceAtLeast(1)} мин" else "${seconds / 3600} ч ${(seconds % 3600) / 60} мин"
        val megabytesPerSecond = rate / (1024.0 * 1024.0)
        val decimals = if (megabytesPerSecond < 0.1) "%.2f" else "%.1f"
        return "${String.format(decimals, megabytesPerSecond)} МБ/с · ≈$remaining"
    }

    private fun formatFileProgress(bytes: Long, total: Long): String = if (total >= 1024L * 1024L) {
        "${String.format("%.1f", bytes / (1024.0 * 1024.0))} МБ"
    } else formatBytes(bytes)

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes байт"
        bytes < 1024L * 1024L -> "${String.format("%.1f", bytes / 1024.0)} КиБ"
        else -> "${String.format("%.1f", bytes / (1024.0 * 1024.0))} МБ"
    }

    private fun sendTypedText() {
        val value = outgoingInput?.text?.toString().orEmpty()
        if (value.isEmpty()) return
        val send = {
            worker.execute {
                runCatching { sendBridgeText(value) }
                    .onSuccess { runOnUiThread { outgoingInput?.text?.clear(); statusView?.text = "Отправлено на Mac" } }
                    .onFailure { runOnUiThread { statusView?.text = it.localizedMessage ?: "Не удалось отправить текст" } }
            }
        }
        if (BluetoothModePreferences(this).enabled) send() else withLocalNetworkPermission(send)
    }

    private fun copyText(value: String) {
        ClipboardAccessibilityService.noteBridgeClipboardWrite()
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Переданный текст", value))
        Toast.makeText(this, "Скопировано", Toast.LENGTH_SHORT).show()
    }

    private fun renderClipboardHistory() {
        val list = clipboardHistoryList ?: return
        val history = ClipboardHistory(this).items()
        if (history == clipboardHistorySnapshot) return
        clipboardHistorySnapshot = history
        list.removeAllViews()
        clipboardHistoryClear?.visibility = if (history.isEmpty()) View.GONE else View.VISIBLE
        sizeClipboardHistory?.invoke()
        if (history.isEmpty()) {
            list.addView(TextView(this).apply { text = "История пока пуста"; textSize = 13f; setTextColor(0xFF667184.toInt()); setPadding(dp(14), dp(14), dp(14), dp(14)) })
            return
        }
        history.forEach { text ->
            list.addView(TextView(this).apply {
                this.text = text; textSize = 14f; setTextColor(0xFF182536.toInt())
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; minHeight = dp(56)
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(8))
                contentDescription = "Скопировать: ${text.take(100)}"
                setOnClickListener {
                    runCatching { clipboardPreferences.markSeen(ClipboardPayload.fromText(text, "android")) }
                    copyText(text)
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun openLink(value: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value))) }
            .onFailure { Toast.makeText(this, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show() }
    }

    private fun startPolling() {
        mainHandler.removeCallbacks(poll)
        mainHandler.post(poll)
    }

    private fun translateState(state: String): String = when (state) {
        "offered" -> "Ожидает решения"
        "accepted" -> "Принято"
        "transferring" -> "Передаётся"
        "complete" -> "Завершено"
        "rejected" -> "Отклонено"
        "cancelled" -> "Отменено"
        "failed" -> "Ошибка"
        "unknown" -> "Результат неизвестен — проверьте файл на принимающем устройстве"
        else -> state
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        @Volatile var isForeground: Boolean = false
            private set
        private const val REQUEST_LOCAL_NETWORK = 6317
        private const val STATE_SHARE_FINGERPRINT = "state_share_fingerprint"
        private const val AUTO_RECEIVE_CHANNEL = "auto_received_files"
        private const val ACTION_OPEN_RECEIVED_FILE = "dev.seferaki.bridge.OPEN_RECEIVED_FILE"
        private const val EXTRA_OPEN_TRANSFER_ID = "open_transfer_id"
        private const val EXTRA_OPEN_TRANSFER_NAME = "open_transfer_name"
        private val ACTIVE_STATES = setOf("offered", "accepted", "transferring")
        private val TERMINAL_STATES = setOf("complete", "cancelled", "failed", "rejected")
    }
}
