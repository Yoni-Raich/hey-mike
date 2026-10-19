// SPDX-License-Identifier: Apache-2.0
package dev.heymike.connect.example

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

class PreferencesActivity : Activity() {
    private lateinit var settings: DisplaySettings
    private lateinit var label: TextView
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> runOnUiThread { render() } }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = DisplaySettings(this)
        label = TextView(this).apply { gravity = Gravity.CENTER; textSize = 26f; setPadding(24, 24, 24, 24) }
        setContentView(label)
        render()
    }
    override fun onStart() { super.onStart(); settings.preferences.registerOnSharedPreferenceChangeListener(listener); render() }
    override fun onStop() { settings.preferences.unregisterOnSharedPreferenceChangeListener(listener); super.onStop() }
    private fun render() {
        val accent = settings.accent()
        label.text = "Mike Connect example\n\nSaved accent: $accent"
        label.setBackgroundColor(Color.BLACK)
        label.setTextColor(if (accent == "amber") Color.rgb(255, 191, 0) else Color.rgb(120, 180, 255))
    }
}
