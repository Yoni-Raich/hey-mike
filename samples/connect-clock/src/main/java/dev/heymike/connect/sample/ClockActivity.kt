package dev.heymike.connect.sample

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.widget.TextView
import android.content.SharedPreferences

class ClockActivity : Activity(), SharedPreferences.OnSharedPreferenceChangeListener {
    private val prefs by lazy { getSharedPreferences("clock", MODE_PRIVATE) }
    private lateinit var text: TextView
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        text = TextView(this).apply { gravity = android.view.Gravity.CENTER; textSize = 48f }
        setContentView(text); prefs.registerOnSharedPreferenceChangeListener(this); draw()
    }
    private fun draw() { text.text = "12:34\nSDK test clock"; text.setBackgroundColor(Color.BLACK); text.setTextColor(if (prefs.getString("color","white") == "blue") Color.CYAN else Color.WHITE) }
    override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) { runOnUiThread { draw() } }
    override fun onDestroy() { prefs.unregisterOnSharedPreferenceChangeListener(this); super.onDestroy() }
}
