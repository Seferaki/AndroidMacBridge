package dev.seferaki.bridge

import android.content.Context
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.InsetDrawable
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.ImageView

/** Samsung-inspired input surface. It renders controls only; text is committed through callbacks. */
internal class ClipboardKeyboardLayout(
    context: Context,
    private val onCommit: (String) -> Unit,
    private val onBackspace: () -> Unit,
    private val onEnter: () -> Unit,
    private val onClipboardItems: () -> List<String>,
    private val onVoiceInput: () -> Unit,
    private val onEnableScreenshots: () -> Unit,
    private val onHideKeyboard: () -> Unit,
    private val onSwitchKeyboard: () -> Unit,
    private val onShowKeyboardPicker: () -> Unit
) : FrameLayout(context) {
    private enum class Page { KEYS, NUMERIC, EMOJI, CLIPBOARD, SETTINGS, MORE }
    private enum class KeyKind { LETTER, NUMBER, ACTION, SPACE, SHIFT, BACKSPACE, LANGUAGE }
    private data class Key(
        val label: String,
        val action: () -> Unit,
        val weight: Float = 1f,
        val alternate: String? = null,
        val kind: KeyKind = KeyKind.LETTER
    )
    private data class Palette(
        val background: Int,
        val key: Int,
        val utility: Int,
        val active: Int,
        val text: Int,
        val secondary: Int,
        val accent: Int,
        val pressed: Int,
        val line: Int
    )

    private val preferences = context.getSharedPreferences("keyboard_ui", Context.MODE_PRIVATE)
    private val palette = Palette(Color.BLACK, 0xFF3B3C40.toInt(), 0xFF17181B.toInt(), 0xFF252A33.toInt(), 0xFFF4F5F7.toInt(), 0xFFAAAEB7.toInt(), 0xFF72A7FF.toInt(), 0xFF55565B.toInt(), 0xFF303137.toInt())

    private val surface = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val toolbar = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val footer = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private var statusView: TextView? = null
    private var languageView: TextView? = null
    private var page = Page.KEYS
    private var showingClipboardSuggestion = false
    private var showingWords = false
    private var language = "RU"
    private var shiftState = 1 // Samsung-style initial capital; the first letter returns to lowercase.
    private var symbolsPage = 0
    private var lastShiftTapAt = 0L
    private var selectedEmojiCategory = 1
    private val recentEmoji = ArrayDeque<String>()
    private var showNumberRow = preferences.getBoolean("number_row", true)
    private var showKeyPreview = preferences.getBoolean("key_preview", true)
    private var hapticEnabled = preferences.getBoolean("haptic", true)
    private var oneHanded = preferences.getBoolean("one_handed", false)
    private var oneHandedRight = preferences.getBoolean("one_handed_right", true)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val characterKeys = mutableListOf<Pair<View, Key>>()
    private val letterLabels = mutableListOf<Pair<TextView, String>>()

    init {
        setBackgroundColor(palette.background)
        isFocusable = false
        surface.setPadding(dp(5), dp(4), dp(5), dp(48))
        addView(surface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        buildToolbar()
        surface.addView(toolbar, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(38)))
        surface.addView(content, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        renderKeys()
        applyWidthMode()
    }

    fun updateStatus(text: String) {
        statusView?.text = compactStatus(text)
        statusView?.contentDescription = text
    }

    fun updateEditorInfo(info: android.view.inputmethod.EditorInfo?) {
        val inputType = info?.inputType ?: 0
        val inputClass = inputType and android.text.InputType.TYPE_MASK_CLASS
        val variation = inputType and android.text.InputType.TYPE_MASK_VARIATION
        val urlLike = inputClass == android.text.InputType.TYPE_CLASS_TEXT && (
            variation == android.text.InputType.TYPE_TEXT_VARIATION_URI ||
                variation == android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == android.text.InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
            )
        val capsRequested = inputType and (
            android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            ) != 0
        if (inputClass == android.text.InputType.TYPE_CLASS_TEXT && !urlLike) shiftState = if (capsRequested || info?.actionLabel == null) 1 else 0
        else shiftState = 0
        if (page == Page.KEYS) renderKeys()
    }

    private fun compactStatus(value: String): String = when {
        value.contains("включ", true) -> "Буфер включён"
        value.contains("отправлен", true) -> "Текст отправлен"
        value.contains("синхрониз", true) -> "Уже синхронизировано"
        value.contains("приостанов", true) -> "Буфер приостановлен"
        value.contains("связ", true) || value.contains("подключ", true) -> "Нет связи с Mac"
        value.contains("защищ", true) -> "Защищённое поле"
        value.contains("клавиатур", true) -> "Выберите эту клавиатуру"
        value.contains("не удалось", true) || value.contains("недоступ", true) -> "Синхронизация недоступна"
        else -> value
    }.take(30)

    private fun buildToolbar() {
        showingClipboardSuggestion = false
        showingWords = false
        toolbar.removeAllViews()
        toolbar.setPadding(dp(2), 0, dp(2), dp(2))
        toolbar.setBackgroundColor(0xFF151518.toInt())
        addToolbarAction("🎙︎", "Голосовой ввод") { onVoiceInput() }
        addToolbarAction("123", "Цифровая клавиатура") { if (page == Page.NUMERIC) renderKeys() else showNumberPad() }
        addToolbarAction("☺︎", "Эмодзи") { if (page == Page.EMOJI) renderKeys() else showEmojiPanel() }
        addToolbarAction("▤", "История буфера") { if (page == Page.CLIPBOARD) renderKeys() else showClipboardPanel() }
        addToolbarAction("⚙", "Настройки клавиатуры") { if (page == Page.SETTINGS) renderKeys() else showSettingsPanel() }
    }

    fun showClipboardSuggestion(label: String, thumbnail: Bitmap? = null, onDismiss: () -> Unit = {}, paste: () -> Unit) {
        showingClipboardSuggestion = true
        toolbar.removeAllViews()
        toolbar.setBackgroundColor(0xFF151518.toInt())
        val suggestionBar = FrameLayout(context)
        val suggestionText = if (label == "Вставить скриншот") "Вставить" else label.removePrefix("▤  ").removePrefix("▧  ")
        val suggestion = toolbarButton(suggestionText, "Вставить из буфера", paste).apply {
            textSize = 14f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            val availableWidth = if (toolbar.width > 0) toolbar.width else resources.displayMetrics.widthPixels
            maxWidth = (availableWidth - dp(150)).coerceAtLeast(dp(70))
            setPadding(dp(6), 0, dp(12), 0)
        }
        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFF272A38.toInt(), 0xFF1B302F.toInt())).apply {
                cornerRadius = dp(22).toFloat(); setStroke(dp(1), 0xFF5C7980.toInt())
            }
            isClickable = true
            setOnClickListener { tapFeedback(this); paste() }
        }
        if (thumbnail != null) pill.addView(ImageView(context).apply {
            setImageBitmap(thumbnail); scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { setColor(palette.utility); cornerRadius = dp(4).toFloat() }
            clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { leftMargin = dp(8) })
        else pill.addView(toolbarOutline(false), LinearLayout.LayoutParams(dp(25), dp(25)).apply { leftMargin = dp(9) })
        suggestion.background = null
        suggestion.isClickable = false
        pill.addView(suggestion, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(34)))
        suggestionBar.addView(pill, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(34), Gravity.CENTER))
        suggestionBar.addView(backButton { onDismiss(); buildToolbar() }, FrameLayout.LayoutParams(dp(40), dp(36), Gravity.START or Gravity.CENTER_VERTICAL))
        toolbar.addView(suggestionBar, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(36)))
    }

    fun clearClipboardSuggestion() { buildToolbar() }

    fun showWordSuggestions(words: List<String>, select: (String) -> Unit) {
        if (showingClipboardSuggestion || page != Page.KEYS || words.isEmpty()) return
        showingWords = true
        toolbar.removeAllViews()
        toolbar.addView(backButton { buildToolbar() }, LinearLayout.LayoutParams(dp(36), dp(36)))
        words.take(3).forEach { word ->
            toolbar.addView(toolbarButton(word, "Вставить подсказку $word") { select(word) }.apply {
                textSize = 15f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, dp(36), 1f))
        }
    }

    fun clearWordSuggestions() { if (showingWords) buildToolbar() }

    private fun backButton(action: () -> Unit): View {
        val holder = FrameLayout(context).apply {
            isClickable = true
            contentDescription = "Вернуть инструменты"
            setOnClickListener { tapFeedback(this); action() }
        }
        val icon = object : View(context) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.text; style = Paint.Style.STROKE
                strokeWidth = resources.displayMetrics.density * 1.7f
                strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            }
            override fun onDraw(canvas: Canvas) {
                val d = resources.displayMetrics.density
                val x = width / 2f; val y = height / 2f
                canvas.drawRoundRect(x-10*d, y-8*d, x+10*d, y+8*d, 3*d, 3*d, paint)
                val arrow = Path().apply { moveTo(x+1.5f*d, y-4.5f*d); lineTo(x-2.5f*d, y); lineTo(x+1.5f*d, y+4.5f*d) }
                canvas.drawPath(arrow, paint)
            }
        }
        holder.addView(icon, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        return holder
    }

    private fun addToolbarAction(icon: String, description: String, action: () -> Unit) {
        val button = toolbarButton(icon, description, action).apply {
            if (icon != "···") background = ripple(palette.key, dp(32))
            includeFontPadding = false
            if (description in setOf("Голосовой ввод", "История буфера", "Эмодзи", "Настройки клавиатуры")) text = ""
        }
        val holder = FrameLayout(context)
        holder.addView(button, FrameLayout.LayoutParams(dp(27), dp(27), Gravity.CENTER))
        if (description == "Голосовой ввод" || description == "История буфера") holder.addView(toolbarOutline(description == "Голосовой ввод"), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        if (description == "Эмодзи" || description == "Настройки клавиатуры") holder.addView(centeredToolbarIcon(description == "Эмодзи"), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        toolbar.addView(holder, LinearLayout.LayoutParams(0, dp(36), 1f))
    }

    private fun toolbarOutline(microphone: Boolean): View = object : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.text; style = Paint.Style.STROKE; strokeWidth = resources.displayMetrics.density * 1.7f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        override fun onDraw(canvas: Canvas) {
            val x = width / 2f; val y = height / 2f; val s = resources.displayMetrics.density * .7f
            if (microphone) {
                canvas.drawRoundRect(x-3*s,y-10*s,x+3*s,y+2*s,3*s,3*s,paint)
                canvas.drawArc(x-7*s,y-6*s,x+7*s,y+7*s,0f,180f,false,paint)
                canvas.drawLine(x,y+7*s,x,y+10*s,paint); canvas.drawLine(x-4*s,y+10*s,x+4*s,y+10*s,paint)
            } else {
                canvas.drawRoundRect(x-7*s,y-7.5f*s,x+7*s,y+9.5f*s,2*s,2*s,paint)
                canvas.drawRoundRect(x-3*s,y-9.5f*s,x+3*s,y-5.5f*s,1*s,1*s,paint)
            }
        }
    }

    private fun centeredToolbarIcon(smile: Boolean): View = object : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.text; style = Paint.Style.STROKE
            strokeWidth = resources.displayMetrics.density * 1.5f
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        override fun onDraw(canvas: Canvas) {
            val x = width / 2f; val y = height / 2f; val d = resources.displayMetrics.density
            if (smile) {
                canvas.drawCircle(x, y, 7*d, paint)
                canvas.drawPoint(x-2.6f*d, y-2*d, paint); canvas.drawPoint(x+2.6f*d, y-2*d, paint)
                canvas.drawArc(x-4*d, y-2*d, x+4*d, y+4.5f*d, 20f, 140f, false, paint)
            } else {
                val path = Path()
                for (i in 0 until 32) {
                    val angle = i * Math.PI / 16 - Math.PI / 2
                    val radius = (if (i % 4 == 1 || i % 4 == 2) 8.5 else 6.5) * d
                    val px = x + (kotlin.math.cos(angle) * radius).toFloat()
                    val py = y + (kotlin.math.sin(angle) * radius).toFloat()
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close(); canvas.drawPath(path, paint); canvas.drawCircle(x, y, 2.5f*d, paint)
            }
        }
    }

    private fun buildFooter() {
        footer.setPadding(dp(3), 0, dp(3), 0)
        val microphone = toolbarButton("♩", "Голосовой ввод") { onVoiceInput() }
        footer.addView(microphone, LinearLayout.LayoutParams(dp(40), dp(22)))
        footer.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        val hide = toolbarButton("⌄", "Скрыть клавиатуру") { onHideKeyboard() }
        footer.addView(hide, LinearLayout.LayoutParams(dp(40), dp(22)))
    }

    private fun renderKeys() {
        page = Page.KEYS
        content.removeAllViews()
        characterKeys.clear()
        letterLabels.clear()
        val keyRows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val letterHeight = if (landscape) 31 else 41
        val numberHeight = if (landscape) 25 else 30
        val bottomHeight = if (landscape) 32 else 41
        if (showNumberRow && symbolsPage == 0) {
            keyRows.addView(createKeyRow((0..9).map { digit ->
                val value = if (digit == 9) "0" else (digit + 1).toString()
                Key(value, { commitCharacter(value) }, kind = KeyKind.NUMBER, alternate = listOf(")", "!", "@", "#", "₽", "%", "&", "*", "(", "-")[digit])
            }, numberHeight))
        }

        if (symbolsPage > 0) {
            val symbolRows = if (symbolsPage == 1) {
                listOf("1234567890", "+×÷=/−<>[]", "!@#₽%^&*()", "−'\":;,?")
            } else {
                listOf("1234567890", "~`|•√π{}\\", "£¢€¥^°=<>?", "%©®™✓[]")
            }
            symbolRows.forEachIndexed { index, row ->
                val keys = row.map { value -> Key(value.toString(), { commitCharacter(value.toString()) }, kind = KeyKind.NUMBER) }.toMutableList()
                if (index == 3) keys.add(0, Key(if (symbolsPage == 1) "1/2" else "2/2", { symbolsPage = if (symbolsPage == 1) 2 else 1; renderKeys() }, weight = 1.25f, kind = KeyKind.ACTION))
                if (index == 3) keys.add(Key("⌫", onBackspace, weight = 1.25f, kind = KeyKind.BACKSPACE))
                keyRows.addView(createKeyRow(keys, if (index == 0) numberHeight else letterHeight))
            }
        } else {
            val layouts = if (language == "RU") {
                listOf(
                    listOf("й" to "+", "ц" to "×", "у" to "÷", "к" to "=", "е" to "ё", "н" to "/", "г" to "−", "ш" to "<", "щ" to ">", "з" to "[", "х" to "]"),
                    listOf("ф" to "!", "ы" to "@", "в" to "#", "а" to "₽", "п" to "%", "р" to "^", "о" to "&", "л" to "*", "д" to "(", "ж" to ")", "э" to "~"),
                    listOf("я" to "`", "ч" to "'", "с" to "\"", "м" to ":", "и" to ";", "т" to "'", "ь" to "ъ", "б" to "?", "ю" to "…")
                )
            } else {
                listOf(
                    listOf("q" to "+", "w" to "×", "e" to "€", "r" to "=", "t" to "₤", "y" to "¥", "u" to "÷", "i" to "!", "o" to "[", "p" to "]"),
                    listOf("a" to "@", "s" to "#", "d" to "$", "f" to "%", "g" to "&", "h" to "−", "j" to "+", "k" to "(", "l" to ")"),
                    listOf("z" to "_", "x" to "\\", "c" to "€", "v" to "£", "b" to "?", "n" to "!", "m" to "…")
                )
            }
            keyRows.addView(createKeyRow(layouts[0].map { pair -> letterKey(pair.first, pair.second) }, letterHeight))
            keyRows.addView(createKeyRow(layouts[1].map { pair -> letterKey(pair.first, pair.second) }, letterHeight))
            val lastRow = mutableListOf(Key("⇧", { toggleShift() }, weight = 1.22f, kind = KeyKind.SHIFT))
            lastRow += layouts[2].map { pair -> letterKey(pair.first, pair.second) }
            lastRow += Key("⌫", onBackspace, weight = 1.22f, kind = KeyKind.BACKSPACE)
            keyRows.addView(createKeyRow(lastRow, letterHeight))
        }

        keyRows.addView(createKeyRow(listOf(
            Key(if (symbolsPage == 0) "?123" else if (language == "RU") "АБВ" else "ABC", {
                symbolsPage = if (symbolsPage == 0) 1 else 0
                renderKeys()
            }, weight = 1.32f, kind = KeyKind.ACTION),
            Key("🌐︎", { switchLanguage() }, weight = 0.82f, kind = KeyKind.LANGUAGE),
            Key(",", { commitCharacter(",") }, weight = 0.78f, alternate = "!", kind = KeyKind.ACTION),
            Key(if (language == "RU") "Русский" else "English (US)", { commitCharacter(" ") }, weight = 4.25f, kind = KeyKind.SPACE),
            Key(".", { commitCharacter(".") }, weight = 0.78f, alternate = "?", kind = KeyKind.ACTION),
            Key("↵", onEnter, weight = 1.2f, kind = KeyKind.ACTION)
        ), bottomHeight))
        content.addView(keyRows, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun showNumberPad() {
        page = Page.NUMERIC
        content.removeAllViews()
        characterKeys.clear()
        letterLabels.clear()
        val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        listOf("123", "456", "789").forEach { digits ->
            rows.addView(createKeyRow(digits.map { digit -> Key(digit.toString(), { commitCharacter(digit.toString()) }, kind = KeyKind.NUMBER) }, 42))
        }
        rows.addView(createKeyRow(listOf(
            Key(",", { commitCharacter(",") }, kind = KeyKind.ACTION),
            Key("0", { commitCharacter("0") }, kind = KeyKind.NUMBER),
            Key("⌫", onBackspace, kind = KeyKind.BACKSPACE)
        ), 42))
        rows.addView(createKeyRow(listOf(
            Key(if (language == "RU") "АБВ" else "ABC", { renderKeys() }, kind = KeyKind.ACTION),
            Key("Пробел", { commitCharacter(" ") }, weight = 2f, kind = KeyKind.SPACE),
            Key("↵", onEnter, kind = KeyKind.ACTION)
        ), 41))
        content.addView(rows, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun letterKey(value: String, alternate: String): Key {
        val display = if (shiftState > 0) value.uppercase() else value
        return Key(display, { commitCharacter(if (shiftState > 0) value.uppercase() else value) }, alternate = alternate, kind = KeyKind.LETTER)
    }

    private fun commitCharacter(value: String) {
        onCommit(value)
        if (shiftState == 1 && value.any { it.isLetter() }) {
            shiftState = 0
            letterLabels.forEach { (label, letter) -> label.text = letter.lowercase() }
        }
    }

    private fun toggleShift() {
        val now = android.os.SystemClock.uptimeMillis()
        shiftState = if (now - lastShiftTapAt < 350) {
            if (shiftState == 2) 0 else 2
        } else {
            if (shiftState == 0) 1 else 0
        }
        lastShiftTapAt = now
        renderKeys()
    }

    private fun switchLanguage() {
        language = if (language == "RU") "EN" else "RU"
        symbolsPage = 0
        languageView?.text = language
        if (page == Page.KEYS) renderKeys()
    }

    private fun createKeyRow(keys: List<Key>, rowHeight: Int): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(rowHeight + 8))
        }
        keys.forEach { key ->
            val cell = FrameLayout(context).apply {
                isClickable = true
                isFocusable = false
                val horizontalInset = dp(if (key.kind == KeyKind.NUMBER && rowHeight < 40) 3 else 2)
                background = InsetDrawable(ripple(keyBackground(key), dp(7)), horizontalInset, dp(4), horizontalInset, dp(4))
                contentDescription = key.alternate?.let { key.label + ", дополнительный знак " + it } ?: key.label
                setOnClickListener {
                    if (key.kind == KeyKind.BACKSPACE && backspaceDidRepeat) backspaceDidRepeat = false
                    else key.action()
                }
                if (key.kind == KeyKind.SPACE) {
                    setOnLongClickListener {
                        onShowKeyboardPicker()
                        true
                    }
                }
                setOnTouchListener { view, event ->
                    if (key.kind == KeyKind.LETTER || key.kind == KeyKind.NUMBER) {
                        return@setOnTouchListener handleCharacterTouch(view, key, event)
                    }
                    if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                        tapFeedback(view)
                        if (showKeyPreview && (key.kind == KeyKind.LETTER || key.kind == KeyKind.NUMBER)) showKeyPreview(view, key.label)
                        if (key.kind == KeyKind.BACKSPACE) startBackspaceRepeat(view)
                    } else if (event.action == android.view.MotionEvent.ACTION_UP || event.action == android.view.MotionEvent.ACTION_CANCEL) {
                        hideKeyPreviewSoon()
                        if (key.kind == KeyKind.BACKSPACE) stopBackspaceRepeat()
                    }
                    false
                }
            }
            val mainText = TextView(context).apply {
                text = key.label
                textSize = when (key.kind) {
                    KeyKind.SPACE -> 12.5f
                    KeyKind.ACTION, KeyKind.SHIFT, KeyKind.BACKSPACE, KeyKind.LANGUAGE -> if (key.label.length > 2) 11f else 19f
                    else -> 20f
                }
                setTypeface(Typeface.create("sans-serif", Typeface.NORMAL))
                setTextColor(if (key.kind == KeyKind.SHIFT && shiftState > 0) palette.accent else palette.text)
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                isClickable = false
                isFocusable = false
            }
            if (key.kind == KeyKind.LETTER) letterLabels.add(mainText to key.label)
            if (key.kind == KeyKind.LETTER || key.kind == KeyKind.NUMBER) characterKeys.add(cell to key)
            if (key.kind == KeyKind.SHIFT || key.label == "↵" || key.kind == KeyKind.LANGUAGE) {
                cell.addView(if (key.kind == KeyKind.LANGUAGE) globeIcon() else keyArrow(key.kind == KeyKind.SHIFT), FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            } else {
                cell.addView(mainText, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            }
            key.alternate?.takeIf { key.kind == KeyKind.LETTER }?.let { alternate ->
                cell.addView(TextView(context).apply {
                    text = alternate
                    textSize = 8.5f
                    setTextColor(palette.secondary)
                    gravity = Gravity.TOP or Gravity.END
                    setPadding(0, dp(2), dp(3), 0)
                    isClickable = false
                }, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            }
            row.addView(cell, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, key.weight))
        }
        return row
    }

    private fun globeIcon(): View = object : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.text; style = Paint.Style.STROKE; strokeWidth = resources.displayMetrics.density * 1.5f }
        override fun onDraw(canvas: Canvas) {
            val x = width / 2f; val y = height / 2f; val r = dp(9).toFloat()
            canvas.drawCircle(x, y, r, paint)
            canvas.drawOval(x-r*.43f, y-r, x+r*.43f, y+r, paint)
            canvas.drawLine(x-r, y, x+r, y, paint)
            canvas.drawLine(x-r*.8f, y-r*.55f, x+r*.8f, y-r*.55f, paint)
            canvas.drawLine(x-r*.8f, y+r*.55f, x+r*.8f, y+r*.55f, paint)
        }
    }

    private inner class CharacterGesture(val original: View, val originalKey: Key) {
        var selectedView: View = original
        var selectedKey: Key = originalKey
        var longPress = false
        var alternateSelected = false
        var popup: PopupWindow? = null
        var pending: Runnable? = null
    }
    private val characterGestures = mutableMapOf<View, CharacterGesture>()

    private fun handleCharacterTouch(view: View, key: Key, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val gesture = CharacterGesture(view, key)
                characterGestures[view] = gesture
                view.isPressed = true
                tapFeedback(view)
                if (showKeyPreview) showKeyPreview(view, key.label)
                if (key.alternate != null) {
                    val hold = Runnable {
                        if (characterGestures[view] !== gesture) return@Runnable
                        gesture.longPress = true
                        activePreview?.dismiss()
                        val options = TextView(context).apply {
                            text = gesture.selectedKey.alternate
                            textSize = 22f; gravity = Gravity.CENTER; setTextColor(palette.text)
                            background = ripple(palette.key, dp(10))
                        }
                        gesture.popup = PopupWindow(options, dp(52), dp(52), false).apply {
                            isTouchable = false; elevation = dp(5).toFloat()
                            runCatching { showAsDropDown(view, (view.width - dp(52)) / 2, -view.height - dp(52)) }
                        }
                    }
                    gesture.pending = hold
                    mainHandler.postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong())
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val gesture = characterGestures[view] ?: return true
                val pos = IntArray(2)
                gesture.original.getLocationOnScreen(pos)
                if (gesture.longPress) {
                    gesture.alternateSelected = event.rawY < pos[1]
                } else {
                    val next = characterKeys.firstOrNull { (cell, _) ->
                        cell.getLocationOnScreen(pos)
                        event.rawX >= pos[0] && event.rawX < pos[0] + cell.width && event.rawY >= pos[1] && event.rawY < pos[1] + cell.height
                    }
                    if (next != null && next.first !== gesture.selectedView) {
                        gesture.pending?.let(mainHandler::removeCallbacks)
                        gesture.selectedView.isPressed = false
                        gesture.selectedView = next.first; gesture.selectedKey = next.second
                        next.first.isPressed = true
                        if (showKeyPreview) showKeyPreview(next.first, next.second.label)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val gesture = characterGestures.remove(view) ?: return true
                gesture.pending?.let(mainHandler::removeCallbacks)
                gesture.popup?.dismiss()
                gesture.selectedView.isPressed = false
                hideKeyPreviewSoon()
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    val alternate = gesture.selectedKey.alternate
                    if (gesture.longPress && alternate != null) onCommit(alternate)
                    else gesture.selectedKey.action()
                }
                return true
            }
        }
        return true
    }

    private fun keyArrow(shift: Boolean): View = object : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (shift && shiftState > 0) palette.accent else palette.text
            style = if (shift && shiftState == 2) Paint.Style.FILL else Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val s = dp(1).toFloat()
            val path = Path()
            if (shift) {
                path.moveTo(cx - 1*s, cy - 9*s); path.quadTo(cx, cy - 10*s, cx + 1*s, cy - 9*s)
                path.lineTo(cx + 9*s, cy - 1*s); path.quadTo(cx + 10*s, cy + 1*s, cx + 8*s, cy + 1*s)
                path.lineTo(cx + 4*s, cy + 1*s); path.lineTo(cx + 4*s, cy + 8*s)
                path.quadTo(cx + 4*s, cy + 9*s, cx + 3*s, cy + 9*s)
                path.lineTo(cx - 3*s, cy + 9*s); path.quadTo(cx - 4*s, cy + 9*s, cx - 4*s, cy + 8*s)
                path.lineTo(cx - 4*s, cy + 1*s); path.lineTo(cx - 8*s, cy + 1*s)
                path.quadTo(cx - 10*s, cy + 1*s, cx - 9*s, cy - 1*s)
                path.close()
            } else {
                path.moveTo(cx + 9*s, cy - 8*s); path.lineTo(cx + 9*s, cy)
                path.quadTo(cx + 9*s, cy + 3*s, cx + 5*s, cy + 3*s)
                path.lineTo(cx - 8*s, cy + 3*s); path.moveTo(cx - 3*s, cy - 3*s)
                path.lineTo(cx - 9*s, cy + 3*s); path.lineTo(cx - 3*s, cy + 9*s)
            }
            canvas.drawPath(path, paint)
        }
    }

    private val backspaceHandler = Handler(Looper.getMainLooper())
    private var backspaceDidRepeat = false
    private var backspaceRepeatRunnable: Runnable? = null

    private fun startBackspaceRepeat(view: View) {
        backspaceDidRepeat = false
        backspaceRepeatRunnable?.let(backspaceHandler::removeCallbacks)
        val runnable = object : Runnable {
            override fun run() {
                if (!view.isPressed) return
                backspaceDidRepeat = true
                onBackspace()
                backspaceHandler.postDelayed(this, 65)
            }
        }
        backspaceRepeatRunnable = runnable
        backspaceHandler.postDelayed(runnable, 420)
    }

    private fun stopBackspaceRepeat() {
        backspaceRepeatRunnable?.let(backspaceHandler::removeCallbacks)
        backspaceRepeatRunnable = null
        // The click listener consumes the release click if a held-key repeat already ran.
    }

    private var activePreview: PopupWindow? = null
    private var previewDismiss: Runnable? = null

    private fun showKeyPreview(anchor: View, value: String) {
        previewDismiss?.let(mainHandler::removeCallbacks)
        activePreview?.dismiss()
        activePreview = null
        val label = TextView(context).apply {
            text = value
            textSize = 30f
            setTextColor(palette.text)
            gravity = Gravity.CENTER
            background = ripple(palette.key, dp(12))
            elevation = dp(5).toFloat()
        }
        val popup = PopupWindow(label, dp(58), dp(58), false).apply {
            isTouchable = false
            isOutsideTouchable = false
            elevation = dp(5).toFloat()
        }
        runCatching { popup.showAsDropDown(anchor, (anchor.width - dp(58)) / 2, -anchor.height - dp(58)) }
            .onSuccess { activePreview = popup }
    }

    private fun hideKeyPreviewSoon() {
        previewDismiss?.let(mainHandler::removeCallbacks)
        val dismiss = Runnable { activePreview?.dismiss(); activePreview = null }
        previewDismiss = dismiss
        mainHandler.postDelayed(dismiss, 100)
    }

    private fun keyBackground(key: Key): Int = when (key.kind) {
        KeyKind.ACTION, KeyKind.SHIFT, KeyKind.BACKSPACE, KeyKind.LANGUAGE -> palette.utility
        KeyKind.SPACE -> palette.key
        else -> palette.key
    }

    private fun toolbarButton(label: String, description: String, action: () -> Unit): TextView = TextView(context).apply {
        text = label
        textSize = if (label.length > 2) 12f else 20f
        setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
        setTextColor(palette.text)
        gravity = Gravity.CENTER
        contentDescription = description
        isClickable = true
        isFocusable = false
        background = ripple(Color.TRANSPARENT, dp(8))
        setOnClickListener { tapFeedback(this); action() }
    }

    private fun tapFeedback(view: View) {
        if (hapticEnabled) {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vibrator?.hasVibrator() == true) vibrator.vibrate(VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE))
            else view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_PRESS)
        }
    }

    private fun ripple(color: Int, radius: Int): RippleDrawable = RippleDrawable(
        ColorStateList.valueOf(palette.pressed),
        GradientDrawable().apply { setColor(color); cornerRadius = radius.toFloat() },
        null
    )

    private fun showEmojiPanel() {
        page = Page.EMOJI
        content.removeAllViews()
        val categoryBar = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val categories = listOf("◷", "☺", "🐾", "🍴", "⚽", "⌂", "♥")
        categories.forEachIndexed { index, symbol ->
            val tab = toolbarButton(symbol, emojiCategoryName(index)) {
                selectedEmojiCategory = index
                showEmojiPanel()
            }
            if (index == selectedEmojiCategory) tab.setTextColor(palette.accent)
            categoryBar.addView(tab, LinearLayout.LayoutParams(0, dp(34), 1f))
        }
        content.addView(categoryBar, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(36)))

        val emojis = emojiForCategory(selectedEmojiCategory)
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val columns = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 10 else 8
        emojis.chunked(columns).forEach { batch ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            batch.forEach { emoji ->
                row.addView(TextView(context).apply {
                    text = emoji
                    textSize = 24f
                    gravity = Gravity.CENTER
                    isClickable = true
                    background = ripple(Color.TRANSPARENT, dp(8))
                    contentDescription = "Эмодзи " + emoji
                    setOnClickListener {
                        tapFeedback(this)
                        onCommit(emoji)
                        recentEmoji.remove(emoji)
                        recentEmoji.addFirst(emoji)
                        while (recentEmoji.size > 24) recentEmoji.removeLast()
                    }
                }, LinearLayout.LayoutParams(0, dp(40), 1f))
            }
            repeat(columns - batch.size) { row.addView(View(context), LinearLayout.LayoutParams(0, dp(40), 1f)) }
            grid.addView(row)
        }
        val scroll = ScrollView(context).apply { isFillViewport = false; overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS }
        scroll.addView(grid, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        content.addView(scroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 118 else 168)))
    }

    private fun emojiCategoryName(index: Int): String = listOf("Недавние", "Смайлы", "Животные", "Еда", "Занятия", "Места", "Символы").getOrElse(index) { "Эмодзи" }

    private fun emojiForCategory(index: Int): List<String> = when (index) {
        0 -> recentEmoji.toList().ifEmpty { emojiForCategory(1) }
        1 -> "😀 😃 😄 😁 😆 😅 😂 🙂 🙃 😉 😊 😇 🥰 😍 🤩 😘 😗 😚 😙 🥲 😋 😛 😜 🤪 😝 🤑 🤗 🤭 🫢 🫣 🤫 🤔 🫡 🤐 🤨 😐 😑 😶 🫥 😏 😒 🙄 😬 🤥 😌 😔 😪 🤤 😴 😷 🤒 🤕 🤢 🤮 🥵 🥶 🥴 😵 🤯 🤠 🥳 🥸 😎 🤓 🧐 😕 🫤 😟 🙁 ☹️ 😮 😯 😲 😳 🥺 😦 😧 😨 😰 😥 😢 😭 😱 😖 😣 😞 😓 😩 😫 🥱 😤 😡 😠 🤬 😈 👿 💀 ☠️ 💩 🤡 👻 👽 🤖 🎃".split(' ')
        2 -> "🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐻‍❄️ 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🙈 🙉 🙊 🐒 🐔 🐧 🐦 🐤 🦆 🦅 🦉 🦇 🐺 🐗 🐴 🦄 🐝 🪱 🐛 🦋 🐌 🐞 🐜 🪰 🪲 🦟 🦗 🕷️ 🦂 🐢 🐍 🦎 🦖 🦕 🐙 🦑 🦐 🦞 🦀 🐡 🐠 🐟 🐬 🐳 🐋 🦈 🐊 🐅 🐆 🦓 🦍 🦧 🐘 🦛 🦏 🐪 🐫 🦒 🦘 🦬 🐃 🐂 🐄 🐎 🐖 🐏 🐑 🦙 🐐 🦌 🐕 🦮 🐩 🐈 🐓 🦃 🦚 🦜 🦢 🦩 🕊️ 🐇 🦝 🦨 🦡 🦦 🦥 🐁 🐀 🐿️ 🦔".split(' ')
        3 -> "🍏 🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🫐 🍈 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🫒 🥑 🍆 🥔 🥕 🌽 🌶️ 🫑 🥒 🥬 🥦 🧄 🧅 🍄 🥜 🫘 🌰 🍞 🥐 🥖 🫓 🥨 🥯 🥞 🧇 🧀 🍖 🍗 🥩 🥓 🍔 🍟 🍕 🌭 🥪 🌮 🌯 🫔 🥙 🧆 🍝 🍜 🍲 🍛 🍣 🍱 🥟 🦪 🍤 🍙 🍚 🍘 🍥 🥠 🥮 🍢 🍡 🍧 🍨 🍦 🥧 🧁 🍰 🎂 🍮 🍭 🍬 🍫 🍿 🍩 🍪 🌰 🥛 ☕ 🫖 🍵 🧃 🥤 🧋 🍺 🍻 🥂 🍷 🥃 🍸 🍹".split(' ')
        4 -> "⚽ 🏀 🏈 ⚾ 🥎 🎾 🏐 🏉 🥏 🎱 🪀 🏓 🏸 🏒 🏑 🥍 🏏 🪃 🥅 ⛳ 🪁 🏹 🎣 🤿 🥊 🥋 🎽 🛹 🛼 🛷 ⛸️ 🥌 🎿 ⛷️ 🏂 🏋️ 🤼 🤸 ⛹️ 🤺 🤾 🏌️ 🏇 🧘 🏄 🏊 🤽 🚣 🧗 🚵 🚴 🏆 🥇 🥈 🥉 🏅 🎖️ 🏵️ 🎗️ 🎫 🎟️ 🎪 🎭 🎨 🎬 🎤 🎧 🎼 🎹 🥁 🎷 🎺 🎸 🪕 🎻 🪇 🪈 🎮 🕹️ 🎲 ♟️ 🎯 🎳".split(' ')
        5 -> "🚗 🚕 🚙 🚌 🚎 🏎️ 🚓 🚑 🚒 🚐 🛻 🚚 🚛 🚜 🦯 🦽 🦼 🚲 🛴 🛹 🛵 🏍️ 🛺 🚨 🚔 🚍 🚘 🚖 🛞 🚡 🚠 🚟 🚃 🚋 🚞 🚝 🚄 🚅 🚈 🚂 🚆 🚇 🚊 🚉 ✈️ 🛫 🛬 🛩️💺 🚀 🛸 🚁 🛶 ⛵ 🚤 🛥️ 🛳️ ⛴️ 🚢 ⚓ 🪝 ⛽ 🚧 🚦 🚥 🚏 🗺️ 🗿 🗽 🗼 🏰 🏯 🏟️ 🎡 🎢 🎠 ⛲ ⛱️ 🏖️ 🏝️ 🏜️ 🌋 ⛰️ 🏔️ 🗻 🏕️ 🏞️ 🏠 🏡 🏢 🏣 🏥 🏦 🏨 🏩 🏪 🏫 🏬 🏭 🏛️ ⛪ 🕌 🛕 🕍 ⛩️ 🕋".split(' ')
        else -> "❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 💟 ☮️ ✝️ ☪️ 🕉️ ☸️ ✡️ 🔯 🕎 ☯️ ☦️ 🛐 ⛎ ♈ ♉ ♊ ♋ ♌ ♍ ♎ ♏ ♐ ♑ ♒ ♓ 🆔 ⚛️ 🉑 ☢️ ☣️ 📴 📳 🈶 🈚 🈸 🈺 🈷️ ✴️ 🆚 💮 🉐 ㊙️ ㊗️ 🈴 🈵 🈹 🈲 🅰️ 🅱️ 🆎 🆑 🅾️ 🆘 ❌ ⭕ 🛑 ⛔ 📛 🚫 💯 💢 ♨️ 🚷 🚯 🚳 🚱 🔞 📵 🚭 ❗ ❕ ❓ ❔ ‼️ ⁉️ 🔅 🔆 🔱 ⚜️ 🔰 ♻️ ✅ 🈯 💹 ❇️ ✳️ ❎ 🌐 💠".split(' ')
    }

    private fun showClipboardPanel() {
        page = Page.CLIPBOARD
        content.removeAllViews()
        val title = TextView(context).apply {
            text = "Буфер обмена"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(palette.text)
            setPadding(dp(6), dp(3), dp(6), dp(7))
        }
        content.addView(title)
        val scroll = ScrollView(context).apply { overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS }
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(3), dp(4), dp(4)) }
        val items = onClipboardItems().take(20)
        if (items.isEmpty()) {
            list.addView(panelMessage("История буфера пока пуста"))
        } else {
            items.chunked(3).forEachIndexed { rowIndex, values ->
                val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                values.forEachIndexed { column, value ->
                    val item = TextView(context).apply {
                        text = value.replace('\r', ' ').take(500)
                        textSize = 15f
                        setTextColor(palette.text)
                        maxLines = 6
                        ellipsize = TextUtils.TruncateAt.END
                        gravity = Gravity.TOP or Gravity.START
                        setPadding(dp(11), dp(10), dp(11), dp(8))
                        val shades = intArrayOf(0xFF191A1E.toInt(), 0xFF1C1B29.toInt(), 0xFF211F1C.toInt())
                        background = ripple(shades[(rowIndex + column) % shades.size], dp(12))
                        isClickable = true
                        contentDescription = "Вставить: " + value.take(100)
                        setOnClickListener { tapFeedback(this); onCommit(value); renderKeys() }
                    }
                    row.addView(item, LinearLayout.LayoutParams(0, dp(124), 1f).apply { leftMargin = dp(3); rightMargin = dp(3) })
                }
                repeat(3 - values.size) { row.addView(View(context), LinearLayout.LayoutParams(0, dp(124), 1f).apply { leftMargin = dp(3); rightMargin = dp(3) }) }
                list.addView(row, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(7) })
            }
        }
        scroll.addView(list)
        content.addView(scroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 140 else 250)))
    }

    private fun showSettingsPanel() {
        page = Page.SETTINGS
        content.removeAllViews()
        content.addView(panelTitle("Настройки клавиатуры").apply { setPadding(dp(12), dp(6), dp(12), dp(10)) })
        val scroll = ScrollView(context).apply { isFillViewport = true; overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS }
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(8), dp(12)) }
        addToggle(list, "Ряд цифр", "Показывать цифры над буквами", showNumberRow) {
            showNumberRow = it
            preferences.edit().putBoolean("number_row", it).apply()
        }
        addToggle(list, "Предпросмотр клавиш", "Показывать нажатую букву крупно", showKeyPreview) {
            showKeyPreview = it
            preferences.edit().putBoolean("key_preview", it).apply()
        }
        addToggle(list, "Виброотклик", "Короткий отклик при нажатии", hapticEnabled) {
            hapticEnabled = it
            preferences.edit().putBoolean("haptic", it).apply()
        }
        addPanelButton(list, "Предлагать новые скриншоты") { onEnableScreenshots() }
        addPanelButton(list, "Одноручный режим · слева") { setOneHanded(true, false); renderKeys() }
        addPanelButton(list, "Одноручный режим · справа") { setOneHanded(true, true); renderKeys() }
        addPanelButton(list, "Обычная ширина клавиатуры") { setOneHanded(false, oneHandedRight); renderKeys() }
        addPanelButton(list, "Очистить запомненные слова") {
            context.getSharedPreferences("keyboard_learned", Context.MODE_PRIVATE).edit().clear().apply()
            android.widget.Toast.makeText(context, "Запомненные слова удалены", android.widget.Toast.LENGTH_SHORT).show()
        }
        addPanelButton(list, "Сменить системную клавиатуру") { onSwitchKeyboard() }
        scroll.addView(list)
        content.addView(scroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 140 else 235)))
    }

    private fun showMorePanel() {
        page = Page.MORE
        content.removeAllViews()
        content.addView(panelTitle("Дополнительные действия"))
        addPanelButton(content, "Одноручный режим · слева") { setOneHanded(true, false); renderKeys() }
        addPanelButton(content, "Одноручный режим · справа") { setOneHanded(true, true); renderKeys() }
        addPanelButton(content, "Обычная ширина клавиатуры") { setOneHanded(false, oneHandedRight); renderKeys() }
        addPanelButton(content, "Сменить системную клавиатуру") { onSwitchKeyboard() }
        addPanelButton(content, "Вернуться к клавишам") { renderKeys() }
    }

    private fun addToggle(parent: LinearLayout, title: String, detail: String, enabled: Boolean, changed: (Boolean) -> Unit) {
        var state = enabled
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = ripple(palette.utility, dp(12))
            isClickable = true
            setOnClickListener { state = !state; changed(state); renderToggleText(this, title, detail, state) }
        }
        renderToggleText(row, title, detail, state)
        parent.addView(row, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(58)).apply { bottomMargin = dp(7) })
    }

    private fun renderToggleText(row: LinearLayout, title: String, detail: String, enabled: Boolean) {
        row.removeAllViews()
        val labels = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        labels.addView(TextView(context).apply { text = title; textSize = 14f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setTextColor(palette.text); maxLines = 1 })
        labels.addView(TextView(context).apply { text = detail; textSize = 11f; setTextColor(palette.secondary); maxLines = 1 })
        row.addView(labels, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        row.addView(TextView(context).apply {
            text = if (enabled) "●" else "○"
            textSize = 18f
            setTextColor(if (enabled) palette.accent else palette.secondary)
            gravity = Gravity.CENTER
            contentDescription = if (enabled) "Включено" else "Выключено"
        }, LinearLayout.LayoutParams(dp(32), LayoutParams.MATCH_PARENT))
    }

    private fun panelTitle(label: String): View = TextView(context).apply {
        text = label
        textSize = 14f
        setTypeface(null, Typeface.BOLD)
        setTextColor(palette.text)
        setPadding(dp(6), dp(2), dp(6), dp(7))
    }

    private fun panelMessage(label: String): View = TextView(context).apply {
        text = label
        textSize = 13f
        setTextColor(palette.secondary)
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(8), dp(8), dp(8))
    }

    private fun addPanelButton(parent: LinearLayout, label: String, action: () -> Unit) {
        val button = toolbarButton(label, label, action).apply {
            textSize = 12f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            background = ripple(palette.key, dp(8))
        }
        parent.addView(button, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(36)).apply { bottomMargin = dp(3) })
    }

    private fun setOneHanded(enabled: Boolean, right: Boolean) {
        oneHanded = enabled
        oneHandedRight = right
        preferences.edit().putBoolean("one_handed", enabled).putBoolean("one_handed_right", right).apply()
        applyWidthMode()
    }

    private fun applyWidthMode() {
        val params = (surface.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        params.width = if (oneHanded) (resources.displayMetrics.widthPixels * 0.78f).toInt() else LayoutParams.MATCH_PARENT
        params.gravity = Gravity.BOTTOM or if (oneHanded && oneHandedRight) Gravity.END else Gravity.START
        surface.layoutParams = params
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
