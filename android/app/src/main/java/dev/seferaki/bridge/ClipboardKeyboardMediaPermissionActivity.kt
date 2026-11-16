package dev.seferaki.bridge

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/** Requests image access only when the user selects the screenshot button. */
class ClipboardKeyboardMediaPermissionActivity : Activity() {
    private val permission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            enable()
        } else {
            requestPermissions(arrayOf(permission), 8412)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 8412 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) enable()
        else { Toast.makeText(this, "Доступ к изображениям не предоставлен", Toast.LENGTH_SHORT).show(); finish() }
    }

    private fun enable() {
        ClipboardKeyboardService.screenshotsEnabled()
        Toast.makeText(this, "Новые скриншоты будут предлагаться в клавиатуре", Toast.LENGTH_SHORT).show()
        finish()
    }
}
