// SPDX-License-Identifier: Apache-2.0
package dev.heymike.connect.example

import android.content.Context

internal class DisplaySettings(context: Context) {
    val preferences = context.getSharedPreferences("display", Context.MODE_PRIVATE)
    fun accent(): String = preferences.getString("accent", "blue")?.takeIf { it in setOf("blue", "amber") } ?: "blue"
    fun setAccent(value: String): String {
        require(value in setOf("blue", "amber"))
        check(preferences.edit().putString("accent", value).commit()) { "Display accent could not be saved" }
        return accent()
    }
}
