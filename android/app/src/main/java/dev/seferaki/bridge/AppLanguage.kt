package dev.seferaki.bridge

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import org.json.JSONObject
import java.util.Locale

internal object AppLanguage {
    private const val SETTINGS = "bridge_settings"
    private const val KEY = "app_language"
    @Volatile private var cachedCatalog: JSONObject? = null

    data class Option(val tag: String, val nativeName: String)

    val options = listOf(
        Option("en", "English"), Option("ru", "Русский"), Option("es", "Español"),
        Option("fr", "Français"), Option("de", "Deutsch"), Option("it", "Italiano"),
        Option("pt-BR", "Português (Brasil)"), Option("tr", "Türkçe"), Option("pl", "Polski"),
        Option("uk", "Українська"), Option("zh-CN", "简体中文"), Option("ja", "日本語"),
        Option("ko", "한국어"), Option("ar", "العربية"), Option("hi", "हिन्दी"),
        Option("id", "Bahasa Indonesia"), Option("nl", "Nederlands"), Option("da", "Dansk"),
        Option("sv", "Svenska"), Option("nb", "Norsk bokmål"), Option("fi", "Suomi"),
        Option("el", "Ελληνικά"), Option("cs", "Čeština"), Option("sk", "Slovenčina"),
        Option("hu", "Magyar"), Option("ro", "Română"), Option("bg", "Български"),
        Option("hr", "Hrvatski"), Option("sr", "Српски"), Option("sl", "Slovenščina"),
        Option("et", "Eesti"), Option("lv", "Latviešu"), Option("lt", "Lietuvių"),
        Option("he", "עברית"), Option("th", "ไทย"), Option("vi", "Tiếng Việt"),
        Option("ms", "Bahasa Melayu"), Option("bn", "বাংলা"), Option("ta", "தமிழ்"),
        Option("te", "తెలుగు"), Option("ur", "اردو"), Option("fa", "فارسی"),
        Option("sw", "Kiswahili"), Option("fil", "Filipino"), Option("ca", "Català"),
        Option("eu", "Euskara"), Option("gl", "Galego"), Option("is", "Íslenska"),
        Option("bs", "Bosanski"), Option("mk", "Македонски")
    )

    fun selectedTag(context: Context): String = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
        .getString(KEY, "en")?.takeIf { tag -> options.any { it.tag == tag } } ?: "en"

    fun selectedName(context: Context): String = options.firstOrNull { it.tag == selectedTag(context) }?.nativeName ?: "English"

    fun setSelected(context: Context, tag: String) {
        if (options.any { it.tag == tag }) {
            context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
        }
    }

    fun wrap(context: Context): Context {
        val locale = Locale.forLanguageTag(selectedTag(context))
        Locale.setDefault(locale)
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(locale)
            if (android.os.Build.VERSION.SDK_INT >= 24) setLocales(LocaleList(locale))
        }
        return context.createConfigurationContext(configuration)
    }

    fun translate(context: Context, value: String): String {
        val language = selectedTag(context).substringBefore('-')
        if (language == "ru") return value
        return runCatching {
            val catalogRoot = cachedCatalog ?: synchronized(this) {
                cachedCatalog ?: JSONObject(context.assets.open("ui-localization.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
                    .also { cachedCatalog = it }
            }
            val catalog = catalogRoot.optJSONObject(language)
            val localized = catalog?.optString(value, "").orEmpty()
            if (localized.isNotEmpty()) localized else catalogRoot.optJSONObject("en")?.optString(value, value) ?: value
        }.getOrDefault(value)
    }

    fun applyToViewTree(context: Context, view: View, excluded: Set<View> = emptySet()) {
        if (view in excluded) return
        if (view is TextView && view !is EditText) {
            translateCurrent(context, view)
            view.contentDescription = view.contentDescription?.toString()?.let { translate(context, it) }
            view.addTextChangedListener(object : android.text.TextWatcher {
                private var translating = false
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (translating || s.isNullOrEmpty()) return
                    val localized = translate(context, s.toString())
                    if (localized != s.toString()) {
                        translating = true
                        view.text = localized
                        translating = false
                    }
                }
                override fun afterTextChanged(s: android.text.Editable?) = Unit
            })
        }
        if (view is ViewGroup) for (index in 0 until view.childCount) applyToViewTree(context, view.getChildAt(index), excluded)
    }

    private fun translateCurrent(context: Context, view: TextView) {
        val original = view.text?.toString().orEmpty()
        if (original.isNotEmpty()) {
            val localized = translate(context, original)
            if (localized != original) view.text = localized
        }
    }
}
