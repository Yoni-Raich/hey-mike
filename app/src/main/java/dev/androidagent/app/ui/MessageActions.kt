/*
 * Hey Mike - an on-device Android AI agent.
 * Copyright (C) 2025-2026 Yoni Raich
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package dev.androidagent.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

/** Both roles share the same menu; finished agent replies also have a shortcut. */
@Composable
internal fun MessageActions(
    messageId: String,
    text: String,
    showCopyAction: Boolean,
    content: @Composable (onLongPress: () -> Unit) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var menuOpen by remember(messageId) { mutableStateOf(false) }
    var selection by remember(messageId) { mutableStateOf<String?>(null) }
    var copied by remember(messageId, text) { mutableStateOf(false) }
    val copy = {
        clipboard.setText(AnnotatedString(text))
        copied = true
    }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }
    Column {
        Box(Modifier.semantics {
            customActions = listOf(
                CustomAccessibilityAction("Copy message") { copy(); true },
                CustomAccessibilityAction("Select text") { selection = text; true },
            )
        }) {
            content { menuOpen = true }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Copy") },
                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                    onClick = { menuOpen = false; copy() },
                )
                DropdownMenuItem(
                    text = { Text("Select text") },
                    leadingIcon = { Icon(Icons.Outlined.SelectAll, contentDescription = null) },
                    onClick = { menuOpen = false; selection = text },
                )
            }
        }
        if (showCopyAction) {
            IconButton(
                onClick = copy,
                modifier = Modifier.semantics { stateDescription = if (copied) "Copied" else "Copy response" },
            ) {
                Icon(
                    if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                    contentDescription = "Copy response",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    // Freeze the text while selecting, even if a live reply gets more blocks.
    selection?.let { selected -> SelectMessageText(selected) { selection = null } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectMessageText(text: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                TopAppBar(
                    title = { Text("Select text") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Close text selection")
                        }
                    },
                    actions = {
                        IconButton(onClick = { clipboard.setText(AnnotatedString(text)) }) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy all")
                        }
                    },
                )
                SelectionContainer(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    // Original text: copying must not include display-only RTL markers.
                    Text(text, Modifier.fillMaxWidth().padding(24.dp),
                        style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content))
                }
            }
        }
    }
}
