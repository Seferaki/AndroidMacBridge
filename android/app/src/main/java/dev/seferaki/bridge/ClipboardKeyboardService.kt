package dev.seferaki.bridge

import android.content.ClipboardManager
import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputContentInfo
import android.view.inputmethod.InputConnection
import android.content.ClipDescription
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.provider.MediaStore
import android.net.Uri
import android.util.Size
import android.util.Log
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.Executors

/** Optional keyboard. Only copied plain text is published; pressed keys stay in the active editor. */
class ClipboardKeyboardService : InputMethodService() {
    private val preferences by lazy { ClipboardSyncPreferences(this) }
    private val client by lazy { BridgeClient(this) }
    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var clipboardManager: ClipboardManager? = null
    private var listenerRegistered = false
    @Volatile private var secureEditor = false
    private val clipboardGeneration = AtomicLong()
    @Volatile private var serviceActive = true
    private var keyboard: ClipboardKeyboardLayout? = null
    private val suggestionWorker = Executors.newSingleThreadExecutor()
    private val dictionary by lazy { KeyboardWordDictionary(applicationContext) }
    private var requestedWord = ""
    private var selectionStart = -1
    private var selectionEnd = -1
    private var suggestionSequence = 0
    private val requestSuggestions = Runnable { requestWordSuggestions() }
    private val suggestionPrefs by lazy { getSharedPreferences("keyboard_suggestions", MODE_PRIVATE) }
    private var lastScreenshotUri: Uri? = null
    private val screenshotObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            mainHandler.postDelayed({ findRecentScreenshot() }, 800)
        }
    }
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "enabled" || key == "send_method") {
            mainHandler.post {
                refreshClipboardListener()
                updateKeyboardStatus()
            }
        }
    }

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        onClipboardChanged()
    }

    override fun onCreate() {
        super.onCreate()
        activeService = this
        clipboardManager = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
        getSharedPreferences("clipboard_sync", MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(preferenceListener)
        refreshClipboardListener()
        contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, screenshotObserver)
    }

    override fun onCreateInputView(): View {
        return ClipboardKeyboardLayout(
            this,
            onCommit = ::commitKeyboardText,
            onBackspace = ::deleteBeforeCursor,
            onEnter = ::performEditorAction,
            onClipboardItems = { ClipboardHistory(this).items() },
            onVoiceInput = ::launchVoiceInput,
            onEnableScreenshots = {
                startActivity(Intent(this, ClipboardKeyboardMediaPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            onHideKeyboard = { requestHideSelf(0) },
            onSwitchKeyboard = { switchToNextInputMethod(false) },
            onShowKeyboardPicker = { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker() }
        ).also { keyboard = it }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        selectionStart = attribute?.initialSelStart ?: -1
        selectionEnd = attribute?.initialSelEnd ?: -1
        secureEditor = attribute?.isClipboardSyncSecureEditor() ?: false
        if (secureEditor) clipboardGeneration.incrementAndGet()
        keyboard?.updateEditorInfo(attribute)
        refreshClipboardListener()
        updateKeyboardStatus()
        refreshSuggestion()
        findRecentScreenshot()
    }

    override fun onFinishInput() {
        selectionStart = -1
        selectionEnd = -1
        mainHandler.removeCallbacks(requestSuggestions)
        requestedWord = ""
        suggestionSequence++
        keyboard?.clearClipboardSuggestion()
        secureEditor = false
        refreshClipboardListener()
        super.onFinishInput()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        refreshClipboardListener()
        updateKeyboardStatus()
        refreshSuggestion()
        findRecentScreenshot()
    }

    override fun onDestroy() {
        serviceActive = false
        clipboardGeneration.incrementAndGet()
        suggestionWorker.shutdownNow()
        clipboardManager?.takeIf { listenerRegistered }?.removePrimaryClipChangedListener(clipboardListener)
        contentResolver.unregisterContentObserver(screenshotObserver)
        listenerRegistered = false
        getSharedPreferences("clipboard_sync", MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(preferenceListener)
        worker.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        client.cancel()
        if (activeService === this) activeService = null
        super.onDestroy()
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        selectionStart = newSelStart
        selectionEnd = newSelEnd
        mainHandler.removeCallbacks(requestSuggestions)
        if (!secureEditor && newSelStart == newSelEnd) mainHandler.postDelayed(requestSuggestions, 180)
    }

    private fun currentWord(): String {
        val before = currentInputConnection?.getTextBeforeCursor(48, 0)?.toString().orEmpty()
        return before.takeLastWhile { it.isLetter() || it == '\'' }.takeLast(32)
    }

    private fun requestWordSuggestions() {
        if (secureEditor || !isInputViewShown) return
        val word = currentWord()
        requestedWord = word
        if (word.length < 2) { suggestionSequence++; keyboard?.clearWordSuggestions(); return }
        val sequence = ++suggestionSequence
        suggestionWorker.execute {
            val learned = getSharedPreferences("keyboard_learned", MODE_PRIVATE).all.mapNotNull { (key, value) -> (value as? Int)?.let { key to it } }.toMap()
            val words = runCatching { dictionary.complete(word, learned) }.getOrDefault(emptyList())
            mainHandler.post {
                if (sequence != suggestionSequence || requestedWord != word || currentWord() != word || secureEditor || !isInputViewShown) return@post
                if (words.isEmpty()) { keyboard?.clearWordSuggestions(); return@post }
                keyboard?.showWordSuggestions(words) { selected ->
                    if (currentWord() == word) {
                        currentInputConnection?.deleteSurroundingText(word.length, 0)
                        if (currentInputConnection?.commitText("$selected ", 1) == true) rememberWord(selected)
                        keyboard?.clearWordSuggestions()
                    }
                }
            }
        }
    }

    private fun mayLearnWords(): Boolean {
        if (secureEditor) return false
        val info = currentInputEditorInfo ?: return false
        val inputClass = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        return inputClass == InputType.TYPE_CLASS_TEXT && variation != InputType.TYPE_TEXT_VARIATION_URI &&
            variation != InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS && variation != InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
    }

    private fun rememberWord(value: String) {
        if (!mayLearnWords()) return
        val word = value.lowercase(Locale.ROOT)
        if (word.length !in 2..32 || !word.all { it.isLetter() }) return
        suggestionWorker.execute {
            val prefs = getSharedPreferences("keyboard_learned", MODE_PRIVATE)
            val editor = prefs.edit()
            val all = prefs.all
            if (word !in all && all.size >= 500) {
                val leastUsed = all.entries.minByOrNull { (it.value as? Int) ?: 0 }
                leastUsed?.let { editor.remove(it.key) }
            }
            editor.putInt(word, (prefs.getInt(word, 0) + 1).coerceAtMost(10000)).apply()
        }
    }

    private fun commitKeyboardText(value: String) {
        val completed = if (value.length == 1 && !value[0].isLetter() && value[0] != '\'' && mayLearnWords()) currentWord() else ""
        if (currentInputConnection?.commitText(value, 1) == true && completed.isNotEmpty()) rememberWord(completed)
    }

    internal fun enableScreenshotSuggestions() {
        lastScreenshotUri = null
    }

    private fun findRecentScreenshot() {
        if (secureEditor || !isInputViewShown) return
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val columns = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.RELATIVE_PATH, MediaStore.Images.Media.MIME_TYPE)
        runCatching {
            val since = System.currentTimeMillis() / 1000 - 600
            contentResolver.query(collection, columns, "${MediaStore.Images.Media.DATE_ADDED} >= ?", arrayOf(since.toString()), "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { cursor ->
                while (cursor.moveToNext()) {
                    val path = cursor.getString(2).orEmpty()
                    if (!path.contains("screenshot", ignoreCase = true) && !path.contains("скриншот", ignoreCase = true)) continue
                    val uri = android.content.ContentUris.withAppendedId(collection, cursor.getLong(0))
                    if (uri == lastScreenshotUri || uri.toString() == suggestionPrefs.getString("inserted_screenshot", null)) return@use
                    val type = cursor.getString(3) ?: "image/png"
                    val supported = currentInputEditorInfo?.contentMimeTypes?.any { ClipDescription.compareMimeTypes(type, it) } == true
                    if (!supported) return@use
                    val thumbnail = runCatching { contentResolver.loadThumbnail(uri, Size(96, 96), null) }.getOrNull()
                    keyboard?.showClipboardSuggestion("Вставить скриншот", thumbnail, onDismiss = {
                        suggestionPrefs.edit().putString("inserted_screenshot", uri.toString()).apply()
                        lastScreenshotUri = uri
                    }) {
                        val info = InputContentInfo(uri, ClipDescription("Скриншот", arrayOf(type)), null)
                        val inserted = runCatching { currentInputConnection?.commitContent(info, InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null) == true }.getOrDefault(false)
                        if (inserted) {
                            lastScreenshotUri = uri
                            suggestionPrefs.edit().putString("inserted_screenshot", uri.toString()).apply()
                            keyboard?.clearClipboardSuggestion()
                        }
                    }
                    return@use
                }
            }
        }
    }

    private fun launchVoiceInput() {
        if (secureEditor) {
            updateStatus("Голосовой ввод отключён в защищённом поле")
            return
        }
        runCatching {
            startActivity(Intent(this, ClipboardKeyboardVoiceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            updateStatus("Голосовой ввод недоступен")
        }
    }

    private fun commitVoiceResult(text: String) {
        if (text.isBlank()) return
        mainHandler.post {
            if (secureEditor) {
                updateStatus("Голосовой результат пропущен для защищённого поля")
            } else {
                val committed = runCatching { currentInputConnection?.commitText(text, 1) == true }.getOrDefault(false)
                updateStatus(if (committed) "Голосовой ввод добавлен" else "Вернитесь в поле и повторите ввод")
            }
        }
    }

    private fun refreshClipboardListener() {
        val eligible = !secureEditor &&
            ClipboardKeyboardStatus.read(this) == ClipboardKeyboardAvailability.SELECTED
        val manager = clipboardManager ?: return
        if (eligible && !listenerRegistered) {
            runCatching { manager.addPrimaryClipChangedListener(clipboardListener) }
                .onSuccess { listenerRegistered = true; Log.i("BridgeClipboard", "listener=registered") }
                .onFailure { updateStatus("Синхронизация буфера недоступна") }
        } else if (!eligible && listenerRegistered) {
            manager.removePrimaryClipChangedListener(clipboardListener)
            listenerRegistered = false
            Log.i("BridgeClipboard", "listener=removed")
        }
    }

    private fun onClipboardChanged() {
        val generation = clipboardGeneration.incrementAndGet()
        Log.i("BridgeClipboard", "clipboard_changed secure=$secureEditor enabled=${preferences.enabled} method=${preferences.sendMethod}")
        suggestionPrefs.edit().remove("inserted_text_hash").remove("dismissed_image").apply()
        mainHandler.post { refreshSuggestion() }
        if (secureEditor || !preferences.enabled || preferences.sendMethod != ClipboardSendMethod.IME_KEYBOARD) return
        if (ClipboardKeyboardStatus.read(this) != ClipboardKeyboardAvailability.SELECTED) {
            refreshClipboardListener()
            return
        }
        val snapshot = runCatching { ClipboardSendCoordinator.readClipboard(this) }
            .getOrElse {
                updateStatus("Не удалось прочитать буфер")
                return
            }
        if ((snapshot.text.isNullOrEmpty() && snapshot.image == null) || snapshot.sensitive || snapshot.status != null) {
            Log.i("BridgeClipboard", "snapshot_skipped status=${snapshot.status} sensitive=${snapshot.sensitive}")
            if (snapshot.status == ClipboardSendStatus.SENSITIVE) updateStatus("Чувствительный текст пропущен")
            return
        }
        sendClipboardSnapshot(snapshot, generation, java.util.UUID.randomUUID().toString(), 0)
    }

    private fun sendClipboardSnapshot(snapshot: ClipboardSnapshot, generation: Long, eventId: String, attempt: Int) {
        if (!serviceActive || generation != clipboardGeneration.get() || secureEditor || !preferences.enabled || preferences.sendMethod != ClipboardSendMethod.IME_KEYBOARD || ClipboardKeyboardStatus.read(this) != ClipboardKeyboardAvailability.SELECTED) return
        worker.execute {
            if (!serviceActive || generation != clipboardGeneration.get() || secureEditor || !preferences.enabled || preferences.sendMethod != ClipboardSendMethod.IME_KEYBOARD || ClipboardKeyboardStatus.read(this) != ClipboardKeyboardAvailability.SELECTED) return@execute
            val outcome = ClipboardSendCoordinator.send(
                this,
                client,
                snapshot,
                ClipboardSendMethod.IME_KEYBOARD,
                eventId
            )
            Log.i("BridgeClipboard", "send_result=${outcome.status} attempt=$attempt current=${generation == clipboardGeneration.get()}")
            val message = when (outcome.status) {
                ClipboardSendStatus.SENT -> "Текст отправлен на Mac"
                ClipboardSendStatus.DUPLICATE -> "Текст уже синхронизирован"
                ClipboardSendStatus.IMAGE_SENT -> "Изображение отправлено на Mac"
                ClipboardSendStatus.IMAGE_DUPLICATE -> "Изображение уже синхронизировано"
                ClipboardSendStatus.UNSUPPORTED_IMAGE -> "Изображение не поддерживается"
                ClipboardSendStatus.IMAGE_FAILED -> "Нет связи с Mac"
                ClipboardSendStatus.PAUSED -> "Общий буфер приостановлен"
                ClipboardSendStatus.NO_PAIR -> "Подключите Mac в приложении"
                ClipboardSendStatus.NO_NETWORK_PERMISSION -> "Разрешите доступ к локальной сети"
                ClipboardSendStatus.FAILED -> "Нет связи с Mac"
                ClipboardSendStatus.SENSITIVE -> "Чувствительный текст пропущен"
                ClipboardSendStatus.EMPTY, ClipboardSendStatus.UNSUPPORTED, ClipboardSendStatus.WRONG_METHOD -> return@execute
            }
            mainHandler.post { updateStatus(message) }
            val delays = longArrayOf(2000L, 5000L, 10000L)
            if (outcome.status in setOf(ClipboardSendStatus.FAILED, ClipboardSendStatus.IMAGE_FAILED) && attempt < delays.size) {
                mainHandler.postDelayed({ sendClipboardSnapshot(snapshot, generation, eventId, attempt + 1) }, delays[attempt])
            }
        }
    }

    private fun refreshSuggestion() {
        val view = keyboard ?: return
        if (secureEditor || !isInputViewShown) { view.clearClipboardSuggestion(); return }
        val clip = runCatching { clipboardManager?.primaryClip }.getOrNull()
        if (clip == null || clip.itemCount == 0 ||
            (Build.VERSION.SDK_INT >= 33 && clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true)) {
            view.clearClipboardSuggestion(); return
        }
        val item = clip.getItemAt(0)
        val text = item.text?.toString()?.takeIf { it.isNotEmpty() }
        if (text != null) {
            val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            if (digest == suggestionPrefs.getString("inserted_text_hash", null)) { view.clearClipboardSuggestion(); return }
            view.showClipboardSuggestion("▤  " + text.replace('\n', ' ').take(80), onDismiss = {
                suggestionPrefs.edit().putString("inserted_text_hash", digest).apply()
            }) {
                if (currentInputConnection?.commitText(text, 1) == true) {
                    suggestionPrefs.edit().putString("inserted_text_hash", digest).apply()
                    view.clearClipboardSuggestion()
                }
            }
            return
        }
        val uri = item.uri ?: run { view.clearClipboardSuggestion(); return }
        if (uri.toString() == suggestionPrefs.getString("dismissed_image", null)) { view.clearClipboardSuggestion(); return }
        val type = runCatching { contentResolver.getType(uri) }.getOrNull() ?: return
        val accepted = currentInputEditorInfo?.contentMimeTypes?.any { ClipDescription.compareMimeTypes(type, it) } == true
        if (!type.startsWith("image/") || !accepted || uri.scheme != "content") { view.clearClipboardSuggestion(); return }
        view.showClipboardSuggestion("▧  Вставить изображение", onDismiss = {
            suggestionPrefs.edit().putString("dismissed_image", uri.toString()).apply()
        }) {
            val info = InputContentInfo(uri, ClipDescription("Изображение", arrayOf(type)), null)
            runCatching { currentInputConnection?.commitContent(info, InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null) }
            view.clearClipboardSuggestion()
        }
    }

    private fun deleteBeforeCursor() {
        val connection = currentInputConnection ?: return
        val selectionKnown = selectionStart >= 0 && selectionEnd >= 0 && selectionStart != selectionEnd
        if (deleteKeyboardSelectionOrPrevious(connection, selectionKnown) && selectionKnown) {
            selectionStart = minOf(selectionStart, selectionEnd)
            selectionEnd = selectionStart
        }
    }

    private fun performEditorAction() {
        val connection = currentInputConnection ?: return
        val completed = if (mayLearnWords()) currentWord() else ""
        val info = currentInputEditorInfo
        val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        val suppressAction = info?.imeOptions?.and(EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        val handled = !suppressAction && action != EditorInfo.IME_ACTION_NONE &&
            action != EditorInfo.IME_ACTION_UNSPECIFIED && connection.performEditorAction(action)
        if (handled || connection.commitText("\n", 1)) {
            if (completed.isNotEmpty()) rememberWord(completed)
        }
    }

    private fun updateKeyboardStatus() {
        val status = when {
            secureEditor -> "Синхронизация приостановлена для защищённого поля"
            !preferences.enabled -> "Включите общий буфер для синхронизации"
            preferences.sendMethod != ClipboardSendMethod.IME_KEYBOARD -> "Клавиатурный способ не выбран"
            ClipboardKeyboardStatus.read(this) != ClipboardKeyboardAvailability.SELECTED -> "Выберите эту клавиатуру в Android"
            else -> "Общий буфер включён"
        }
        updateStatus(status)
    }

    private fun updateStatus(message: String) {
        mainHandler.post { keyboard?.updateStatus(message) }
    }

    private fun EditorInfo.isClipboardSyncSecureEditor(): Boolean {
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val passwordVariation = when (inputClass) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
        val noLearning = imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0
        return passwordVariation || noLearning
    }

    companion object {
        @Volatile private var activeService: ClipboardKeyboardService? = null

        internal fun deliverVoiceResult(text: String) {
            activeService?.commitVoiceResult(text)
        }

        internal fun screenshotsEnabled() { activeService?.enableScreenshotSuggestions() }
    }
}
