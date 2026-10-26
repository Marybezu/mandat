package ua.mandat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.withTransaction
import ua.mandat.R
import ua.mandat.data.MandatDb
import ua.mandat.data.Role
import ua.mandat.data.SETTING_ROLES_ASKED
import ua.mandat.data.Setting

/** Builds a role from a body, a position and an optional name: "Secretary — Committee «…»". */
@Composable
fun RoleBuilder(onAdd: (String) -> Unit) {
    val context = LocalContext.current
    var body by remember(AppMode.council) { mutableStateOf(roleBodies().first()) }
    var position by remember(AppMode.council) { mutableStateOf(roleBodies().first().positions.firstOrNull()) }
    var name by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Picker(
            label = stringResource(R.string.role_body),
            current = stringResource(body.name),
            options = roleBodies().map { it to context.getString(it.name) },
            onPick = {
                body = it
                position = it.positions.firstOrNull()
            },
        )
        position?.let { current ->
            Picker(
                label = stringResource(R.string.role_position),
                current = stringResource(current),
                options = body.positions.map { it to context.getString(it) },
                onPick = { position = it },
            )
        }
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text(stringResource(R.string.role_name_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = {
                onAdd(composeRoleName(context.getString(body.name), position?.let { context.getString(it) }, name))
                name = ""
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.role_add_button)) }
    }
}

/** "Position — Body «name»", or just the body when it has no positions. */
fun composeRoleName(body: String, position: String?, name: String): String {
    val base = if (position == null) body else "$position — $body"
    val extra = name.trim().take(100)
    return if (extra.isEmpty()) base else "$base «$extra»"
}

/** Shown once after the first unlock: build your roles; they can be changed later in Options. */
@Composable
fun RolesSetup(session: Session) {
    val added = remember { mutableStateListOf<String>() }
    var finished by remember { mutableStateOf(false) }

    fun finish(save: Boolean) {
        if (finished) return
        finished = true
        val names = if (save) added.toList() else emptyList()
        session.write { db ->
            db.withTransaction {
                names.forEachIndexed { i, name -> db.roles().insert(Role(name = name, position = i + 1)) }
                db.settings().put(Setting(SETTING_ROLES_ASKED, "1"))
            }
        }
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.roles_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.roles_text), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        ModePicker(session)
        Spacer(Modifier.height(8.dp))
        RoleBuilder(onAdd = { if (it !in added) added += it })
        if (added.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.roles_added), style = MaterialTheme.typography.titleMedium)
            added.forEach { name ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = { added -= name }) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete))
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = { finish(save = true) }, enabled = !finished, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.roles_done))
        }
        TextButton(onClick = { finish(save = false) }, enabled = !finished, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.roles_skip))
        }
    }
}

/** The roles section in Options: add, rename and delete. */
@Composable
fun RolesManager(db: MandatDb, session: Session) {
    val rolesFlow = remember(db) { db.roles().observeAll() }
    val roles by rolesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var renaming by remember { mutableStateOf<Role?>(null) }
    var deleting by remember { mutableStateOf<Role?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        roles.forEach { role ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(role.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { renaming = role }) {
                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.rename))
                }
                IconButton(onClick = { deleting = role }) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        RoleBuilder(onAdd = { name ->
            if (roles.none { it.name == name }) {
                session.write { it.roles().insert(Role(name = name, position = it.roles().maxPosition() + 1)) }
            }
        })
    }

    renaming?.let { role ->
        var name by remember(role.id) { mutableStateOf(role.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.rename)) },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = {
                    val clean = name.trim().take(100)
                    if (clean.isNotEmpty()) session.write { it.roles().rename(role.id, clean) }
                    renaming = null
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    deleting?.let { role ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            text = { Text(stringResource(R.string.role_delete_confirm, role.name)) },
            confirmButton = {
                TextButton(onClick = {
                    session.write { db ->
                        db.withTransaction {
                            db.matters().clearRole(role.id)
                            db.roles().delete(role.id)
                        }
                    }
                    deleting = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** National parliament or a local council: changes the bodies, positions and some names. */
@Composable
fun ModePicker(session: Session) {
    Text(stringResource(R.string.mode_title), style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(false to R.string.mode_rada, true to R.string.mode_council).forEach { (council, label) ->
            androidx.compose.material3.FilterChip(
                selected = AppMode.council == council,
                onClick = {
                    AppMode.council = council
                    session.write { it.settings().put(Setting(ua.mandat.data.SETTING_MODE, if (council) ua.mandat.data.MODE_COUNCIL else ua.mandat.data.MODE_RADA)) }
                },
                label = { Text(stringResource(label)) },
            )
        }
    }
    Text(stringResource(R.string.mode_hint), style = MaterialTheme.typography.labelSmall, color = Muted)
}
