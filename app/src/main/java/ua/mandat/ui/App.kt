package ua.mandat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ua.mandat.R
import ua.mandat.data.InboxItem
import ua.mandat.data.MandatDb
import ua.mandat.data.cleanInboxText

@Composable
fun App(session: Session, onUnlock: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (val state = session.state) {
            LockState.NeedsScreenLock -> Message(stringResource(R.string.no_screen_lock))
            LockState.KeyLost -> Message(stringResource(R.string.key_lost))
            LockState.FirstRun -> Welcome(onUnlock)
            LockState.Locked -> Locked(session.lastError, onUnlock)
            LockState.Opening -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            is LockState.Open -> Main(state.db, onLock = { session.lock() })
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

@Composable
private fun Notice() {
    Spacer(Modifier.height(32.dp))
    Text(
        stringResource(R.string.classified_notice),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.secondary,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun Message(text: String) = Centered {
    Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
}

@Composable
private fun Welcome(onStart: () -> Unit) = Centered {
    Text(stringResource(R.string.first_run_title), style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(16.dp))
    Text(
        stringResource(R.string.first_run_text),
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(32.dp))
    Button(onClick = onStart) { Text(stringResource(R.string.first_run_start)) }
    Notice()
}

@Composable
private fun Locked(failed: Boolean, onUnlock: () -> Unit) = Centered {
    Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
    Text(stringResource(R.string.tagline), style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(32.dp))
    Button(onClick = onUnlock) { Text(stringResource(R.string.unlock)) }
    if (failed) {
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.unlock_failed), color = MaterialTheme.colorScheme.error)
    }
    Notice()
}

private enum class Tab(val title: Int, val icon: ImageVector) {
    Today(R.string.tab_today, Icons.Filled.Home),
    Matters(R.string.tab_matters, Icons.Filled.List),
    People(R.string.tab_people, Icons.Filled.Person),
    Time(R.string.tab_time, Icons.Filled.DateRange),
    Review(R.string.tab_review, Icons.Filled.CheckCircle),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Main(db: MandatDb, onLock: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.Today) }
    var adding by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(tab.title)) },
                actions = {
                    IconButton(onClick = onLock) {
                        Icon(Icons.Filled.Lock, contentDescription = stringResource(R.string.lock_now))
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = t == tab,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(stringResource(t.title), style = MaterialTheme.typography.labelMedium) },
                    )
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { adding = true }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add))
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                Tab.Today -> Inbox(db)
                else -> Centered {
                    Text(stringResource(R.string.coming_soon), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }

    if (adding) {
        AddDialog(
            onSave = { clean ->
                scope.launch { db.inbox().insert(InboxItem(text = clean, createdAt = System.currentTimeMillis())) }
            },
            onDone = { adding = false },
        )
    }
}

@Composable
private fun Inbox(db: MandatDb) {
    val flow = remember(db) { db.inbox().observeAll() }
    val items by flow.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text(
            stringResource(R.string.inbox),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        if (items.isEmpty()) {
            Text(stringResource(R.string.inbox_empty), style = MaterialTheme.typography.bodyLarge)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.id }) { item ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(item.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            IconButton(onClick = { scope.launch { db.inbox().delete(item.id) } }) {
                                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddDialog(onSave: (String) -> Unit, onDone: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.inbox)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(stringResource(R.string.add_hint)) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val clean = cleanInboxText(text)
                if (clean != null) onSave(clean)
                onDone()
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.cancel)) } },
    )
}
