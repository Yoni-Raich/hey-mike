package dev.androidagent.app.assist

import android.app.Activity
import android.os.Bundle

/**
 * `ACTION_ASSIST` entry point, for OEM skins and shortcuts that fire the intent
 * directly instead of starting the voice interaction session.
 *
 * A trampoline rather than a filter on `MainActivity`: the system launches
 * assist intents with `FLAG_ACTIVITY_NEW_TASK` only, which would stack a second
 * chat screen on top of the one already open.
 */
class AssistEntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { startActivity(AssistLaunch.voiceIntent(applicationContext)) }
        finish()
    }
}
