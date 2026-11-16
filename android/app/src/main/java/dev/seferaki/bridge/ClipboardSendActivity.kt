package dev.seferaki.bridge

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.util.concurrent.Executors

/** Short-lived focused surface required by Android before clipboard content can be read. */
class ClipboardSendActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var statusView: TextView
    private lateinit var progressView: ProgressBar
    private lateinit var closeButton: Button
    private var attempted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        attempted = savedInstanceState?.getBoolean(KEY_ATTEMPTED) == true
        buildPanel()
        window?.let { dialogWindow ->
            dialogWindow.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            dialogWindow.setLayout(
                (resources.displayMetrics.widthPixels * 0.90f).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            dialogWindow.attributes = dialogWindow.attributes.apply { dimAmount = 0.24f }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_ATTEMPTED, attempted)
        super.onSaveInstanceState(outState)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || attempted || isFinishing) return
        attempted = true
        // Wait for the Quick Settings shade transition to finish before reading the clip.
        mainHandler.postDelayed(::beginSend, FOCUS_SETTLE_MILLIS)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun beginSend() {
        if (isFinishing) return
        val client = BridgeClient(this)
        ClipboardSendCoordinator.preflight(this, client, ClipboardSendMethod.QUICK_SETTINGS_TILE)?.let {
            showResult(it)
            return
        }
        val snapshot = ClipboardSendCoordinator.readClipboard(this)
        if (snapshot.status != null) {
            showResult(ClipboardSendCoordinator.outcomeFor(snapshot.status))
            return
        }
        statusView.text = "Отправляю текст на Mac…"
        worker.execute {
            val outcome = ClipboardSendCoordinator.send(
                this,
                client,
                snapshot,
                ClipboardSendMethod.QUICK_SETTINGS_TILE
            )
            runOnUiThread { if (!isFinishing) showResult(outcome) }
        }
    }

    private fun showResult(outcome: ClipboardSendOutcome) {
        statusView.text = outcome.message
        progressView.visibility = View.GONE
        if (outcome.isSuccessful) {
            closeButton.visibility = View.GONE
            mainHandler.postDelayed({ if (!isFinishing) finishAndRemoveTask() }, SUCCESS_CLOSE_DELAY_MILLIS)
        } else {
            closeButton.visibility = View.VISIBLE
        }
    }

    private fun buildPanel() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(22), dp(24), dp(18))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(24).toFloat()
            }
            elevation = dp(12).toFloat()
        }
        val title = TextView(this).apply {
            text = "На Mac"
            textSize = 21f
            setTextColor(Color.rgb(24, 37, 54))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        statusView = TextView(this).apply {
            text = "Подготовка отправки…"
            textSize = 16f
            setTextColor(Color.rgb(86, 101, 125))
            gravity = Gravity.CENTER
            setPadding(0, dp(9), 0, dp(10))
        }
        progressView = ProgressBar(this).apply { isIndeterminate = true }
        closeButton = Button(this).apply {
            text = "Закрыть"
            visibility = View.GONE
            setOnClickListener { finishAndRemoveTask() }
        }
        content.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        content.addView(statusView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        content.addView(progressView, LinearLayout.LayoutParams(dp(28), dp(28)))
        content.addView(closeButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        setContentView(content)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val KEY_ATTEMPTED = "clipboard_send_attempted"
        private const val FOCUS_SETTLE_MILLIS = 220L
        private const val SUCCESS_CLOSE_DELAY_MILLIS = 900L
    }
}
