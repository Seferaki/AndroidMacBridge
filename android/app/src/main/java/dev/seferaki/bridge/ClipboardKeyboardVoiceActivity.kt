package dev.seferaki.bridge

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import java.util.Locale

/** Hands voice capture to Android's configured recognizer; SamsungMacBridge never records audio. */
class ClipboardKeyboardVoiceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val language = intent.getStringExtra(EXTRA_LANGUAGE) ?: Locale.getDefault().toLanguageTag()
        val request = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Голосовой ввод")
        }
        try {
            startActivityForResult(request, REQUEST_RECOGNITION)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "В системе не настроен голосовой ввод", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    @Deprecated("The recognizer returns its result through the activity result contract")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_RECOGNITION && resultCode == RESULT_OK) {
            val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
            ClipboardKeyboardService.deliverVoiceResult(text)
        }
        finish()
    }

    companion object {
        const val EXTRA_LANGUAGE = "dev.seferaki.bridge.extra.VOICE_LANGUAGE"
        private const val REQUEST_RECOGNITION = 8107
    }
}
