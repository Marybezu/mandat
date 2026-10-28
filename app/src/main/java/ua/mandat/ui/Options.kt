@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package ua.mandat.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import ua.mandat.R
import ua.mandat.backup.BackupCrypto
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Options(session: Session, onBack: () -> Unit) {
    val backups = session.backups
    BackHandler(onBack = onBack)

    // Passwords live only in memory, never in the saved screen state
    var askNewPassword by remember { mutableStateOf(false) }
    var newPassword by remember { mutableStateOf<String?>(null) }
    var askRestorePassword by remember { mutableStateOf<android.net.Uri?>(null) }
    // The lock generation when a file picker was opened; results from before a lock are ignored
    var pickedAt by remember { mutableStateOf(-1) }

    // Leaving Options drops a decrypted backup that is still waiting for confirmation
    DisposableEffect(Unit) { onDispose { backups.cancelRestore() } }

    val saveFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        session.pickingFile = false
        val password = newPassword
        newPassword = null
        val fresh = pickedAt == session.generationNow()
        if (uri != null && password != null && fresh) backups.save(uri, password)
    }
    // Automatic backups: the folder is chosen first, then the password
    var autoFolder by remember { mutableStateOf<android.net.Uri?>(null) }
    var confirmAutoOff by remember { mutableStateOf(false) }
    var cloudFolder by remember { mutableStateOf<android.net.Uri?>(null) }
    LaunchedEffect(Unit) { backups.refreshAuto() }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        session.pickingFile = false
        if (uri != null && pickedAt == session.generationNow()) {
            // A folder of another app (a cloud drive): warn before anything is written there
            if (ua.mandat.backup.AutoBackup.maySync(uri)) cloudFolder = uri else autoFolder = uri
        }
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        session.pickingFile = false
        if (uri != null && pickedAt == session.generationNow()) askRestorePassword = uri
    }

    // A copy holds everything: making, restoring or switching on copies always needs the phone's lock,
    // so a moment with the phone unattended (or forced open) cannot set up a lasting leak.
    // Each runs on the screen that shows when the answer comes (also after the phone was turned).
    val guardTitle = stringResource(R.string.confirm_backup)
    val askBackup = rememberConfirmed(session, "backup", guardTitle) { askNewPassword = true }
    val askRestore = rememberConfirmed(session, "restore", guardTitle) {
        session.pickingFile = true
        pickedAt = session.generationNow()
        try {
            openFile.launch(arrayOf("*/*"))
        } catch (e: Exception) {
            session.pickingFile = false
        }
    }
    val askAuto = rememberConfirmed(session, "auto", guardTitle) {
        session.pickingFile = true
        pickedAt = session.generationNow()
        try {
            pickFolder.launch(null)
        } catch (e: Exception) {
            session.pickingFile = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.options)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .imePadding()
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val openDb = (session.state as? LockState.Open)?.db

            // Grouped and folded, the most used first: trip mode heads privacy and security
            OptionGroup(stringResource(R.string.opt_privacy), stringResource(R.string.opt_privacy_hint), Sym.lock) {
                Text(stringResource(R.string.security_text), style = MaterialTheme.typography.bodyMedium)
                openDb?.let { TripSwitch(it, session) }
                run {
                    PinOnlySwitch(session)
                    AutoLockChoice(session)
                    ScreenshotsSwitch(session)
                }
            }

            OptionGroup(stringResource(R.string.opt_data), stringResource(R.string.opt_data_hint), Sym.save) {
                run {
                    Section(stringResource(R.string.backup_title))
                    Text(stringResource(R.string.backup_text), style = MaterialTheme.typography.bodyLarge)
                    if (backups.busy) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource(R.string.backup_working))
                        }
                    } else {
                        Button(onClick = askBackup, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.backup_make))
                        }
                        OutlinedButton(
                            onClick = askRestore,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.backup_restore)) }
                    }

                    // Automatic backups (optional)
                    val auto = backups.auto
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.auto_title), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.auto_hint, ua.mandat.backup.AutoBackup.KEEP), style = MaterialTheme.typography.labelMedium, color = Muted)
                        }
                        androidx.compose.material3.Switch(
                            checked = auto?.enabled == true,
                            enabled = !backups.busy && auto != null,
                            onCheckedChange = { on ->
                                if (on) askAuto()
                                else confirmAutoOff = true
                            },
                        )
                    }
                    if (auto?.enabled == true) {
                        val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                        Text(
                            auto.lastOk?.let { stringResource(R.string.auto_last_ok, fmt.format(Date(it))) } ?: stringResource(R.string.auto_none_yet),
                            style = MaterialTheme.typography.labelMedium, color = Muted,
                        )
                        if (auto.lastFail != null && (auto.lastOk == null || auto.lastFail > auto.lastOk)) {
                            val reason = when (auto.failReason) {
                                ua.mandat.backup.AutoBackup.REASON_TOO_BIG -> R.string.auto_fail_too_big
                                ua.mandat.backup.AutoBackup.REASON_STUCK -> R.string.auto_fail_stuck
                                else -> R.string.auto_last_fail
                            }
                            Text(stringResource(reason, fmt.format(Date(auto.lastFail))), style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error)
                        }
                        if (auto.folder?.let { ua.mandat.backup.AutoBackup.maySync(it) } == true) {
                            Text(stringResource(R.string.auto_may_sync), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                        }
                        TextButton(onClick = { backups.autoNow() }, enabled = !backups.busy) { Text(stringResource(R.string.auto_now)) }
                    }

                    Spacer(Modifier.height(8.dp))
                }
                Section(stringResource(R.string.export_title))
                Text(stringResource(R.string.export_options_hint), style = MaterialTheme.typography.bodyMedium)
                androidx.compose.material3.OutlinedButton(onClick = { session.showExport = true; onBack() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.export_title))
                }
            }

            openDb?.let { db ->
                OptionGroup(stringResource(R.string.opt_work), stringResource(R.string.opt_work_hint), Sym.badge) {
                    ModePicker(session)
                    DistrictSwitch(db, session)
                    RolesManager(db, session)
                }
            }

            run {
                OptionGroup(stringResource(R.string.opt_look), stringResource(R.string.opt_look_hint), Sym.palette) {
                    Section(stringResource(R.string.look_title))
                    LookSection(session)
                    NeutralIconSwitch()
                    Spacer(Modifier.height(8.dp))
                    Section(stringResource(R.string.reminders_title))
                    RemindersSection(session)
                    Spacer(Modifier.height(8.dp))
                    Section(stringResource(R.string.phone_cal_title))
                    PhoneCalendarSwitch(session)
                }

                OptionGroup(stringResource(R.string.ai_title), stringResource(R.string.opt_ai_hint), Sym.info) {
                    AiSection(session)
                }
            }

            OptionGroup(stringResource(R.string.about_title), stringResource(R.string.opt_about_hint), Sym.info) {
                Text(stringResource(R.string.about_text, appVersion()), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (askNewPassword) {
        NewPasswordDialog(
            words = backups.words,
            onDone = { password ->
                askNewPassword = false
                if (password != null) {
                    newPassword = password
                    session.pickingFile = true
                    pickedAt = session.generationNow()
                    try {
                        // A neutral name: the file itself does not say where it comes from
                        saveFile.launch("archive-${java.time.LocalDate.now()}.bin")
                    } catch (e: Exception) {
                        session.pickingFile = false
                        newPassword = null
                    }
                }
            },
        )
    }

    autoFolder?.let { folder ->
        NewPasswordDialog(
            words = backups.words,
            onDone = { password ->
                autoFolder = null
                if (password != null) backups.enableAuto(folder, password)
            },
        )
    }

    cloudFolder?.let { folder ->
        AlertDialog(
            onDismissRequest = { cloudFolder = null },
            text = { Text(stringResource(R.string.auto_may_sync)) },
            confirmButton = { TextButton(onClick = { cloudFolder = null; autoFolder = folder }) { Text(stringResource(R.string.auto_use_anyway)) } },
            dismissButton = { TextButton(onClick = { cloudFolder = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (confirmAutoOff) {
        AlertDialog(
            onDismissRequest = { confirmAutoOff = false },
            text = { Text(stringResource(R.string.auto_off_confirm)) },
            confirmButton = { TextButton(onClick = { confirmAutoOff = false; backups.disableAuto() }) { Text(stringResource(R.string.auto_off)) } },
            dismissButton = { TextButton(onClick = { confirmAutoOff = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    askRestorePassword?.let { uri ->
        PasswordDialog(
            title = stringResource(R.string.restore_password_title),
            onDone = { password ->
                askRestorePassword = null
                if (password != null) backups.open(uri, password, session.generationNow())
            },
        )
    }

    backups.notice?.let { notice ->
        val text = when (notice) {
            is BackupNotice.Saved -> stringResource(R.string.notice_saved, notice.rows)
            BackupNotice.SaveFailed -> stringResource(R.string.notice_save_failed)
            BackupNotice.WrongPassword -> stringResource(R.string.notice_wrong_password)
            BackupNotice.NotABackup -> stringResource(R.string.notice_not_backup)
            BackupNotice.Incompatible -> stringResource(R.string.notice_incompatible)
            BackupNotice.IsExport -> stringResource(R.string.notice_is_export)
            BackupNotice.FromNewer -> stringResource(R.string.notice_from_newer)
            is BackupNotice.Restored -> stringResource(R.string.notice_restored, notice.rows)
            BackupNotice.RestoreFailed -> stringResource(R.string.notice_restore_failed)
            BackupNotice.AutoOn -> stringResource(R.string.notice_auto_on)
            BackupNotice.AutoFailed -> stringResource(R.string.notice_auto_failed)
            BackupNotice.AutoOff -> stringResource(R.string.notice_auto_off)
            BackupNotice.AutoSaved -> stringResource(R.string.notice_auto_saved)
            BackupNotice.AutoTooBig -> stringResource(R.string.notice_auto_too_big)
            BackupNotice.ExistingFile -> stringResource(R.string.notice_existing_file)
            BackupNotice.TooBig -> stringResource(R.string.notice_too_big)
        }
        AlertDialog(
            onDismissRequest = { backups.notice = null },
            text = { Text(text, style = MaterialTheme.typography.bodyLarge) },
            confirmButton = { TextButton(onClick = { backups.notice = null }) { Text(stringResource(R.string.ok)) } },
        )
    }

    if (backups.notice == null && !askNewPassword && !backups.busy) backups.pending?.let { pending ->
        val date = DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT)
            .format(Date(pending.summary.createdAt))
        AlertDialog(
            onDismissRequest = { backups.cancelRestore() },
            title = { Text(stringResource(R.string.restore_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.restore_confirm_text, date, pending.summary.rows))
                    // A backup from an earlier version has no data for newer parts (for example matters)
                    if (pending.summary.schema < ua.mandat.data.DB_VERSION) {
                        Text(stringResource(R.string.restore_older), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { backups.confirmRestore() }) { Text(stringResource(R.string.restore_replace)) }
            },
            dismissButton = {
                Row {
                    // Save what is in Mandat now, then come back to this question
                    TextButton(onClick = { askNewPassword = true }) { Text(stringResource(R.string.restore_save_first)) }
                    TextButton(onClick = { backups.cancelRestore() }) { Text(stringResource(R.string.cancel)) }
                }
            },
        )
    }
}

@Composable
private fun OptionGroup(
    title: String, hint: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    // Folded by default, so the whole list fits on a screen; the choice survives turning the phone
    var open by androidx.compose.runtime.saveable.rememberSaveable(title) { mutableStateOf(false) }
    val stateText = stringResource(if (open) R.string.opt_open else R.string.opt_closed)
    MCard {
        Row(
            Modifier.fillMaxWidth().clickable(onClickLabel = stringResource(if (open) R.string.opt_fold else R.string.opt_unfold)) { open = !open }
                .groupState(stateText).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(hint, style = MaterialTheme.typography.labelMedium, color = Muted)
            }
            Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = Muted)
        }
        if (open) Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

/** Tells a screen reader whether a group is open. */
private fun Modifier.groupState(state: String): Modifier = semantics(mergeDescendants = true) { stateDescription = state }

@Composable
private fun Section(title: String) {
    SectionHeader(
        when (title) {
            stringResource(R.string.backup_title) -> Sym.save
            stringResource(R.string.roles_manage) -> Sym.badge
            stringResource(R.string.reminders_title) -> Sym.notifications
            stringResource(R.string.security_title) -> Sym.lock
            stringResource(R.string.look_title) -> Sym.palette
            else -> Sym.info
        },
        title,
    )
}

@Composable
private fun appVersion(): String {
    val context = LocalContext.current
    return remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
    }
}

private val passwordKeyboard = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)

@Composable
private fun NewPasswordDialog(words: List<String>, onDone: (String?) -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var tried by remember { mutableStateOf(false) }
    // Shown in clear text only when Mandat suggested it, so it can be written down
    var suggested by remember { mutableStateOf(false) }
    val tooShort = BackupCrypto.isTooSimple(first)
    val mismatch = first != second
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text(stringResource(R.string.backup_password_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.widthIn(max = 480.dp)) {
                Text(stringResource(R.string.backup_password_hint), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = {
                    first = BackupCrypto.suggestPassword(words)
                    second = first
                    suggested = true
                }) { Text(stringResource(R.string.suggest_password)) }
                if (suggested) {
                    Text(stringResource(R.string.suggested_note), style = MaterialTheme.typography.bodyMedium)
                    Text(first, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                }
                NoAutofill()
                OutlinedTextField(
                    value = first,
                    onValueChange = {
                        first = it
                        suggested = false
                    },
                    label = { Text(stringResource(R.string.password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = passwordKeyboard,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = second,
                    onValueChange = {
                        second = it
                        suggested = false
                    },
                    label = { Text(stringResource(R.string.password_repeat)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = passwordKeyboard,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (tried && tooShort) Text(stringResource(R.string.password_too_short), color = MaterialTheme.colorScheme.error)
                if (tried && !tooShort && mismatch) Text(stringResource(R.string.password_mismatch), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                tried = true
                if (!tooShort && !mismatch) onDone(first)
            }) { Text(stringResource(R.string.continue_)) }
        },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun PasswordDialog(title: String, onDone: (String?) -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text(title) },
        text = {
            NoAutofill()
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = passwordKeyboard,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (password.isNotEmpty()) onDone(password) }) {
                Text(stringResource(R.string.continue_))
            }
        },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Reminders: off by default; the notification permission is asked only when switched on. */
@Composable
private fun RemindersSection(session: Session) {
    val context = LocalContext.current
    var on by remember { mutableStateOf(ua.mandat.notify.Reminders.isOn(context)) }
    var hour by remember { mutableStateOf(ua.mandat.notify.Reminders.hour(context)) }
    var denied by remember { mutableStateOf(false) }

    fun enable() {
        ua.mandat.notify.Reminders.setOn(context, true)
        on = true
        session.refreshReminders()
    }

    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) enable() else denied = true
    }

    Text(stringResource(R.string.reminders_text), style = MaterialTheme.typography.bodyLarge)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.reminders_switch), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        androidx.compose.material3.Switch(checked = on, onCheckedChange = { want ->
            denied = false
            if (!want) {
                ua.mandat.notify.Reminders.setOn(context, false)
                on = false
            } else if (android.os.Build.VERSION.SDK_INT >= 33 &&
                androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                // The system dialog only covers Mandat (it does not stop it), so nothing special here
                try {
                    ask.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                } catch (e: Exception) {
                    denied = true
                }
            } else {
                enable()
            }
        })
    }
    if (denied) Text(stringResource(R.string.reminders_denied), color = MaterialTheme.colorScheme.error)
    if (on) {
        Picker(
            label = stringResource(R.string.reminders_hour),
            current = "%02d:00".format(hour),
            options = (6..22).map { it to "%02d:00".format(it) },
            onPick = {
                hour = it
                ua.mandat.notify.Reminders.setHour(context, it)
            },
        )
    }
}

/** Showing the phone's calendar: off by default; the permission is asked only when turned on. */
@Composable
private fun PhoneCalendarSwitch(session: Session) {
    val context = LocalContext.current
    var on by remember { mutableStateOf(PhoneCalendar.isOn(context)) }
    var denied by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        session.pickingFile = false
        PhoneCalendar.setOn(context, granted)
        on = PhoneCalendar.isOn(context)
        denied = !granted
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.phone_cal_switch), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(if (PhoneCalendar.canGiveBack) R.string.phone_cal_hint else R.string.phone_cal_hint_12), style = MaterialTheme.typography.labelMedium, color = Muted)
            if (denied) Text(stringResource(R.string.phone_cal_denied), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
        }
        androidx.compose.material3.Switch(checked = on, onCheckedChange = { want ->
            denied = false
            if (!want) {
                PhoneCalendar.setOn(context, false)
                on = false
            } else if (PhoneCalendar.allowed(context)) {
                PhoneCalendar.setOn(context, true)
                on = true
            } else {
                // The system question may briefly cover Mandat; it must not lock it
                session.pickingFile = true
                ask.launch(android.Manifest.permission.READ_CALENDAR)
            }
        })
    }
}

/** Allowing screenshots is an explicit choice, confirmed with a warning every time. */
@Composable
private fun ScreenshotsSwitch(session: Session) {
    val context = LocalContext.current
    val activity = context as? android.app.Activity ?: return
    var confirm by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val guardShots = stringResource(R.string.screenshots_confirm_title)
    fun set(on: Boolean) {
        failed = !Appearance.setScreenshotsAllowed(activity, on)
        session.screenshotsAllowed = Appearance.screenshotsAllowed(activity)
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.screenshots_allow), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.screenshots_hint), style = MaterialTheme.typography.labelMedium, color = Muted)
            if (!Appearance.canAllowScreenshots) Text(stringResource(R.string.screenshots_need_13), style = MaterialTheme.typography.labelMedium, color = Muted)
            if (failed) Text(stringResource(if (session.screenshotsAllowed) R.string.screenshots_off_failed else R.string.screenshots_failed), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
        }
        androidx.compose.material3.Switch(checked = session.screenshotsAllowed, enabled = Appearance.canAllowScreenshots, onCheckedChange = { on ->
            if (on) confirm = true else set(false)
        })
    }
    if (confirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.screenshots_confirm_title)) },
            text = { Text(stringResource(R.string.screenshots_confirm_text)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirm = false
                    // Allowing screenshots needs the phone's lock too: a recording could carry everything off
                    (activity as? androidx.fragment.app.FragmentActivity)?.let { a -> confirmWithPhoneLock(a, session, guardShots) { set(true) } }
                }) { Text(stringResource(R.string.screenshots_confirm_yes)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** Theme: automatic, light or dark; and Material You colours instead of blue and yellow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LookSection(session: Session) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(Appearance.mode(context)) }
    Text(stringResource(R.string.look_theme), style = MaterialTheme.typography.labelLarge)
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(Appearance.AUTO to R.string.look_auto, Appearance.LIGHT to R.string.look_light, Appearance.DARK to R.string.look_dark)
            .forEach { (value, label) ->
                QuickChip(stringResource(label), mode == value) {
                        mode = value
                        Appearance.setMode(context, value)
                    }
            }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.look_dynamic), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.look_dynamic_hint), style = MaterialTheme.typography.labelMedium, color = Muted)
        }
        androidx.compose.material3.Switch(checked = session.dynamicColors, onCheckedChange = {
            session.dynamicColors = it
            Appearance.setDynamic(context, it)
        })
    }
}

/** The district section: for those elected in a district; off by default. */
@Composable
private fun DistrictSwitch(db: ua.mandat.data.MandatDb, session: Session) {
    val on by remember(db) { db.settings().observe(ua.mandat.data.SETTING_DISTRICT) }.collectAsStateWithLifecycle(null)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.district_switch), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.district_switch_hint), style = MaterialTheme.typography.labelMedium, color = Muted)
        }
        androidx.compose.material3.Switch(checked = on?.value == "1", onCheckedChange = { v ->
            session.write { it.settings().put(ua.mandat.data.Setting(ua.mandat.data.SETTING_DISTRICT, if (v) "1" else "0")) }
        })
    }
}

/** A neutral name and icon on the home screen, so the app does not draw attention. */
@Composable
private fun NeutralIconSwitch() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(Appearance.neutralIcon(context)) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.neutral_icon), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.neutral_icon_hint), style = MaterialTheme.typography.labelMedium, color = Muted)
        }
        androidx.compose.material3.Switch(checked = on, onCheckedChange = { v ->
            try { Appearance.setNeutralIcon(context, v) } catch (e: Exception) { }
            on = Appearance.neutralIcon(context)
        })
    }
}

/** After how many minutes without a touch open Mandat locks itself. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AutoLockChoice(session: Session) {
    val context = LocalContext.current
    var minutes by remember { mutableStateOf(Appearance.autoLockMinutes(context)) }
    Column(Modifier.padding(top = 8.dp)) {
        Text(stringResource(R.string.autolock_title), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.autolock_hint), style = MaterialTheme.typography.labelMedium, color = Muted)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
            Appearance.AUTOLOCK_CHOICES.forEach { m ->
                QuickChip(if (m == 0) stringResource(R.string.autolock_never) else stringResource(R.string.autolock_min, m), minutes == m) {
                    minutes = m
                    Appearance.setAutoLockMinutes(context, m)
                    session.touched()
                }
            }
        }
    }
}

/** "PIN only": no fingerprint at the unlock prompt, for situations where a finger could be forced. */
@Composable
private fun PinOnlySwitch(session: Session) {
    val activity = LocalContext.current.findActivity() as? MainActivity ?: return
    // The key itself decides: switching wraps the passphrase again with a key that refuses a fingerprint
    var on by remember { mutableStateOf(activity.keyIsPinOnly()) }
    val older = !on && Appearance.pinOnly(activity)
    var failed by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(session.pinOnlyResult) {
        session.pinOnlyResult?.let { ok -> failed = !ok; on = activity.keyIsPinOnly(); session.pinOnlyResult = null }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.pin_only), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.pin_only_hint), style = MaterialTheme.typography.labelMedium, color = Muted)
            // Turned on in an older version: only the prompt left out the fingerprint, not the key
            if (older) Text(stringResource(R.string.pin_only_older), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            if (failed) Text(stringResource(R.string.pin_only_failed), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
        }
        // Both ways need the phone's PIN or password (never a fingerprint)
        androidx.compose.material3.Switch(checked = on, onCheckedChange = { v -> failed = !activity.switchPinOnly(v) })
    }
}

/**
 * Trip mode: sensitive matters and everything linked to them leave the screens, lists, charts
 * and search. Turning it on shows how it works and what it does not do.
 */
@Composable
private fun TripSwitch(db: ua.mandat.data.MandatDb, session: Session) {
    val trip by remember(db) { db.settings().observe(SETTING_TRIP) }.collectAsStateWithLifecycle(null)
    var explain by remember { mutableStateOf(false) }
    val on = trip?.value == "1"
    fun set(v: Boolean) = session.write { it.settings().put(ua.mandat.data.Setting(SETTING_TRIP, if (v) "1" else "0")) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.trip_mode), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(if (on) R.string.trip_on else R.string.trip_hint), style = MaterialTheme.typography.labelMedium, color = Muted)
            TextButton(onClick = { explain = true }) { Text(stringResource(R.string.trip_how)) }
        }
        val context = LocalContext.current
        val confirmTitle = stringResource(R.string.trip_confirm_off)
        // Turning it off needs the phone's own fingerprint or PIN, not just a tap
        androidx.compose.material3.Switch(checked = on, onCheckedChange = { v ->
            if (v) explain = true
            else context.findActivity()?.let { a -> confirmWithPhoneLock(a, session, confirmTitle) { set(false) } }
        })
    }
    if (explain) {
        EditSheet(
            title = stringResource(R.string.trip_mode),
            onDismiss = { explain = false },
            onSave = if (!on) ({ explain = false; set(true) }) else null,
            saveText = stringResource(R.string.trip_turn_on),
        ) {
            SheetGroup { Text(stringResource(R.string.trip_explain), style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
