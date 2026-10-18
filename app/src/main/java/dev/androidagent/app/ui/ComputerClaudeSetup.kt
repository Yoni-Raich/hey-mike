package dev.androidagent.app.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.androidagent.remote.ComputerClaude
import dev.androidagent.remote.ComputerClaudeSignInState
import dev.androidagent.remote.RemoteComputer

/** The computer's own account. Only its official login link and pasted code pass through the phone. */
@Composable
internal fun ColumnScope.ComputerClaudeSetup(
    computer: RemoteComputer,
    claude: ComputerClaude?,
    signIn: ComputerClaudeSignInState?,
    actions: AgentUiActions,
    enabled: Boolean = true,
) {
    val busy = signIn?.busy == true
    val url = signIn?.loginUrl
    // A code is never saveable and is cleared before it leaves this field.
    var code by remember(computer.id, url) { mutableStateOf("") }
    val submit = {
        if (!busy && enabled && code.isNotBlank()) {
            val pasted = code
            code = ""
            actions.onComputerClaudeCode(computer.id, pasted)
        }
    }
    HorizontalDivider(Modifier.padding(top = 14.dp, bottom = 12.dp))
    Text("Claude on ${computer.label}", fontSize = 15.sp, fontWeight = FontWeight.Medium)
    Text(CLAUDE_NOTICE, fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    when {
        url != null -> {
            Text("Finish sign-in on the page, then paste its code here. The account stays on ${computer.label}.", fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            TextButton(onClick = { actions.onOpenUrl(url) }, enabled = !busy) { Text("Open sign-in page") }
            OutlinedTextField(
                value = code, onValueChange = { code = it },
                label = { Text("Code from the sign-in page") },
                enabled = !busy && enabled, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = submit, enabled = !busy && enabled && code.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp)) {
                Text("Finish sign-in on ${computer.label}")
            }
        }
        busy -> Unit
        claude?.ready == true -> {
            Text("Signed in${claude.account.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}", fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            TextButton(onClick = { actions.onCheckComputerClaude(computer.id) }, enabled = enabled) { Text("Check again") }
        }
        claude?.installed == true -> {
            Text("Sign in once on ${computer.label}. You can do it from this phone.", fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            OutlinedButton(onClick = { actions.onComputerClaudeLogin(computer.id) }, enabled = enabled, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp)) {
                Text("Connect Claude to ${computer.label}")
            }
        }
        else -> {
            Text(
                if (claude != null && claude.error == null) "Install Claude Code on ${computer.label}, then check again."
                else "Check whether Claude Code is ready on ${computer.label}.",
                fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp),
            )
            TextButton(onClick = { actions.onCheckComputerClaude(computer.id) }, enabled = enabled) { Text("Check Claude") }
        }
    }
    if (busy) {
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("Connecting Claude…", fontSize = 13.sp)
        }
    }
    (signIn?.error ?: claude?.error)?.let {
        Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
    }
    if (busy || url != null) {
        TextButton(onClick = { code = ""; actions.onCancelComputerClaudeLogin(computer.id) }) { Text("Cancel sign-in") }
    }
}
