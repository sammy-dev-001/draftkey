package com.draftkeys.keyboard

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * MainActivity is just a friendly setup screen.
 * It's NOT the keyboard — the keyboard is KeyboardService.
 *
 * This screen:
 * 1. Shows users how to enable DraftKeys in system settings
 * 2. Shows whether DraftKeys is currently the active keyboard
 * 3. Has a button to jump straight to keyboard settings
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Opens Android's keyboard selection settings so the user can enable DraftKeys
        findViewById<View>(R.id.btn_enable_keyboard).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        findViewById<View>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        if (intent.getBooleanExtra("request_audio", false)) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 101)
        }
    }

    override fun onResume() {
        super.onResume()
        // Check if DraftKeys is currently active each time we return to this screen
        updateStatus()
    }

    /**
     * Checks if DraftKeys is the currently selected default keyboard
     * and updates the status text accordingly.
     */
    private fun updateStatus() {
        val currentIme = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val isActive = currentIme?.contains(packageName) == true

        val statusText = findViewById<TextView>(R.id.tv_status)
        if (isActive) {
            statusText.text = "✅ DraftKeys is active!"
            statusText.setTextColor(android.graphics.Color.parseColor("#AEE2FF"))
        } else {
            statusText.text = "⚠️ DraftKeys is not set as default keyboard"
            statusText.setTextColor(android.graphics.Color.parseColor("#FFB86C"))
        }
    }
}
