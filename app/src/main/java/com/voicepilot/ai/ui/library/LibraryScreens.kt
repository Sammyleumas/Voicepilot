package com.voicepilot.ai.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voicepilot.ai.ui.components.EmptyState
import com.voicepilot.ai.ui.components.VpCard
import com.voicepilot.ai.ui.components.VpTopBar
import com.voicepilot.ai.ui.theme.LocalStatusColors
import com.voicepilot.ai.ui.theme.Spacing
import com.voicepilot.core.history.HistoryEntry
import com.voicepilot.core.history.HistoryStatus
import com.voicepilot.core.memory.MemoryKind
import com.voicepilot.core.memory.Routine
import com.voicepilot.core.planner.ActionDescriber
import java.text.DateFormat
import java.util.Date

private fun formatTime(millis: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

// ------------------------------------------------------------------ history

@Composable
fun HistoryScreen(viewModel: LibraryViewModel, onBack: () -> Unit, onRetry: (HistoryEntry) -> Unit) {
    val entries by viewModel.historyEntries.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf<HistoryStatus?>(null) }
    val colors = LocalStatusColors.current
    val shown = entries.filter { filter == null || it.status == filter }

    Scaffold(topBar = { VpTopBar("Automation history", onBack = onBack) }, containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = Spacing.screen, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("All") })
                    listOf(HistoryStatus.COMPLETED, HistoryStatus.PREPARED, HistoryStatus.FAILED, HistoryStatus.CANCELLED).forEach { st ->
                        FilterChip(selected = filter == st, onClick = { filter = if (filter == st) null else st }, label = { Text(st.label) })
                    }
                }
            }
            if (shown.isEmpty()) {
                item { EmptyState("No history yet", "Commands you run will be listed here with their results.", Icons.Filled.History) }
            }
            items(shown, key = { it.id }) { entry ->
                VpCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.utterance, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(formatTime(entry.timestampMillis), entry.appLabel, entry.actionTitle).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            entry.status.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = when (entry.status) {
                                HistoryStatus.COMPLETED, HistoryStatus.PREPARED, HistoryStatus.ANSWERED -> colors.success
                                HistoryStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
                                else -> colors.danger
                            },
                        )
                    }
                    Spacer(Modifier.height(Spacing.xs))
                    Text(entry.resultMessage, style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        if (entry.retryRequest != null && entry.status in setOf(HistoryStatus.FAILED, HistoryStatus.CANCELLED, HistoryStatus.COMPLETED, HistoryStatus.PREPARED)) {
                            TextButton(onClick = { onRetry(entry) }) {
                                Icon(Icons.Filled.Replay, contentDescription = null)
                                Text(if (entry.status == HistoryStatus.FAILED) " Retry" else " Run again")
                            }
                        }
                        IconButton(onClick = { viewModel.deleteHistory(entry.id) }) { Icon(Icons.Filled.Delete, contentDescription = "Delete entry") }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ memory

@Composable
fun MemoryScreen(viewModel: LibraryViewModel, onBack: () -> Unit) {
    val items by viewModel.memories.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    Scaffold(
        topBar = { VpTopBar("Memory", onBack = onBack) },
        floatingActionButton = { FloatingActionButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, contentDescription = "Add nickname") } },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            item {
                Text(
                    "VoicePilot only remembers what you tell it to, such as “Remember that Samuel Okafor is my brother”. Message contents are never stored.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (items.isEmpty()) item { EmptyState("Nothing remembered yet", "Add a nickname so you can say “message my brother”.", Icons.Filled.Memory) }
            items(items, key = { it.id }) { m ->
                VpCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(if (m.kind == MemoryKind.CONTACT_ALIAS) "“my ${m.key}” → ${m.value}" else "${m.key}: ${m.value}", style = MaterialTheme.typography.bodyLarge)
                            Text(m.kind.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { viewModel.deleteMemory(m.id) }) { Icon(Icons.Filled.Delete, contentDescription = "Forget") }
                    }
                }
            }
        }
    }
    if (adding) {
        var alias by remember { mutableStateOf("") }
        var contact by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Add a nickname") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedTextField(alias, { alias = it.take(40) }, label = { Text("Nickname, e.g. brother") }, singleLine = true)
                    OutlinedTextField(contact, { contact = it.take(80) }, label = { Text("Contact name as saved") }, singleLine = true)
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.addAlias(alias, contact); adding = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
}

// ------------------------------------------------------------------ routines

@Composable
fun RoutinesScreen(viewModel: LibraryViewModel, onBack: () -> Unit) {
    val routines by viewModel.routineList.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Routine?>(null) }
    var creating by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); message = null } }

    Scaffold(
        topBar = { VpTopBar("Routines", onBack = onBack) },
        floatingActionButton = { FloatingActionButton(onClick = { creating = true }) { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = "New routine") } },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            item {
                Text(
                    "A routine runs several actions from one phrase, like “Prepare my phone for work”. Sensitive steps still ask first.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (routines.isEmpty()) item { EmptyState("No routines yet", "Tap + to create one.", Icons.AutoMirrored.Filled.PlaylistAdd) }
            items(routines, key = { it.id }) { r ->
                VpCard(onClick = { editing = r }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.name, style = MaterialTheme.typography.titleMedium)
                            Text("Say: " + r.triggerPhrases.joinToString(" / ") { "“$it”" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = r.enabled, onCheckedChange = { viewModel.setRoutineEnabled(r, it) })
                    }
                    Spacer(Modifier.height(Spacing.sm))
                    r.steps.forEachIndexed { i, step -> Text("${i + 1}. ${ActionDescriber.describe(step)}", style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }
    if (creating || editing != null) {
        RoutineEditor(
            routine = editing,
            onDismiss = { creating = false; editing = null },
            onDelete = editing?.let { r -> { viewModel.deleteRoutine(r.id); editing = null } },
            onSave = { name, triggers, commands, done ->
                viewModel.saveRoutine(editing?.id ?: 0L, name, triggers, commands) { result ->
                    when (result) {
                        is RoutineDraftResult.Saved -> { message = "Saved “${result.name}”."; creating = false; editing = null }
                        is RoutineDraftResult.Invalid -> done(result.message)
                    }
                }
            },
        )
    }
}

@Composable
private fun RoutineEditor(
    routine: Routine?,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (String, String, String, (String) -> Unit) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(routine?.name.orEmpty()) }
    var triggers by rememberSaveable { mutableStateOf(routine?.triggerPhrases?.joinToString(", ").orEmpty()) }
    var commands by rememberSaveable { mutableStateOf(routine?.steps?.joinToString("\n") { ActionDescriber.describe(it) }.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (routine == null) "New routine" else "Edit routine") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(name, { name = it.take(40) }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(triggers, { triggers = it.take(200) }, label = { Text("Phrases (comma separated)") })
                OutlinedTextField(
                    commands, { commands = it.take(1000) },
                    label = { Text("Steps, one command per line") },
                    placeholder = { Text("Set the volume to 30 percent\nOpen Calendar\nTurn on do not disturb") },
                    minLines = 3,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, triggers, commands) { error = it } }) { Text("Save") } },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

// ------------------------------------------------------------------ notes, tasks, reminders

@Composable
fun NotesScreen(viewModel: LibraryViewModel, onBack: () -> Unit) {
    val notes by viewModel.noteList.collectAsStateWithLifecycle()
    val tasks by viewModel.taskList.collectAsStateWithLifecycle()
    val reminders by viewModel.reminderList.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(topBar = { VpTopBar("Notes & tasks", onBack = onBack) }, containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
                listOf("Tasks", "Notes", "Reminders").forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
            }
            LazyColumn(contentPadding = PaddingValues(Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                when (tab) {
                    0 -> {
                        if (tasks.isEmpty()) item { EmptyState("No tasks", "Say “Add buy fuel to my to-do list”.", Icons.AutoMirrored.Filled.PlaylistAdd) }
                        items(tasks, key = { it.id }) { t ->
                            VpCard {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = t.done, onCheckedChange = { viewModel.setTaskDone(t.id, it) })
                                    Column(Modifier.weight(1f)) {
                                        Text(t.title, style = MaterialTheme.typography.bodyLarge, textDecoration = if (t.done) TextDecoration.LineThrough else null)
                                        t.due?.let { Text("Due ${ActionDescriber.formatDateTime(it)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    }
                                    IconButton(onClick = { viewModel.deleteTask(t.id) }) { Icon(Icons.Filled.Delete, contentDescription = "Delete task") }
                                }
                            }
                        }
                    }
                    1 -> {
                        if (notes.isEmpty()) item { EmptyState("No notes", "Say “Take a note that the gate code is 4512”.", Icons.Filled.Description) }
                        items(notes, key = { it.id }) { n ->
                            VpCard {
                                Row(verticalAlignment = Alignment.Top) {
                                    Column(Modifier.weight(1f)) {
                                        Text(n.text, style = MaterialTheme.typography.bodyLarge)
                                        Text(formatTime(n.createdAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    IconButton(onClick = { viewModel.deleteNote(n.id) }) { Icon(Icons.Filled.Delete, contentDescription = "Delete note") }
                                }
                            }
                        }
                    }
                    else -> {
                        if (reminders.isEmpty()) item { EmptyState("No reminders", "Say “Remind me to call Mum at 6pm”.", Icons.Filled.Alarm) }
                        items(reminders, key = { it.id }) { r ->
                            VpCard {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(r.text, style = MaterialTheme.typography.bodyLarge)
                                        Text((if (r.delivered) "Delivered · " else "Scheduled · ") + formatTime(r.triggerAtMillis), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    IconButton(onClick = { viewModel.cancelReminder(r.id) }) {
                                        Icon(Icons.Filled.Delete, contentDescription = if (r.delivered) "Delete reminder" else "Cancel reminder")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
