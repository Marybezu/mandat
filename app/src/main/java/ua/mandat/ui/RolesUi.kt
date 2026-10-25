package ua.mandat.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import ua.mandat.R
import ua.mandat.data.MandatDb
import ua.mandat.data.Role
import ua.mandat.data.SETTING_ROLES_ASKED
import ua.mandat.data.Setting

/** Shown once after the first unlock: pick roles from common ones or add your own. */
@Composable
fun RolesSetup(session: Session) {
    val context = LocalContext.current
    val picked = remember { mutableStateListOf<String>() }
    val own = remember { mutableStateListOf<String>() }
    var newRole by remember { mutableStateOf("") }

    fun finish(save: Boolean) {
        val names = if (save) (ROLE_SUGGESTIONS.map { context.getString(it) }.filter { it in picked } + own) else emptyList()
        session.write { db ->
            names.forEachIndexed { i, name -> db.roles().insert(Role(name = name, position = i + 1)) }
            db.settings().put(Setting(SETTING_ROLES_ASKED, "1"))
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.roles_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.roles_text), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        ROLE_SUGGESTIONS.forEach { id ->
            val name = stringResource(id)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = name in picked, onCheckedChange = { if (it) picked += name else picked -= name })
                Text(name, style = MaterialTheme.typography.bodyLarge)
            }
        }
        own.forEach { name ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = true, onCheckedChange = { own -= name })
                Text(name, style = MaterialTheme.typography.bodyLarge)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newRole, onValueChange = { newRole = it },
                label = { Text(stringResource(R.string.roles_add_hint)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                val name = newRole.trim().take(100)
                if (name.isNotEmpty() && name !in own) own += name
                newRole = ""
            }) { Text(stringResource(R.string.add)) }
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = { finish(save = true) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.roles_done))
        }
        TextButton(onClick = { finish(save = false) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.roles_skip))
        }
    }
}

/** The roles section in Options: add, rename and delete. */
@Composable
fun RolesManager(db: MandatDb, session: Session) {
    val rolesFlow = remember(db) { db.roles().observeAll() }
    val roles by rolesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var newRole by remember { mutableStateOf("") }
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newRole, onValueChange = { newRole = it },
                label = { Text(stringResource(R.string.roles_add)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                val name = newRole.trim().take(100)
                newRole = ""
                if (name.isNotEmpty()) session.write { it.roles().insert(Role(name = name, position = it.roles().maxPosition() + 1)) }
            }) { Text(stringResource(R.string.add)) }
        }
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
                    session.write {
                        it.matters().clearRole(role.id)
                        it.roles().delete(role.id)
                    }
                    deleting = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
