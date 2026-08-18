package eu.kanade.presentation.more.settings.screen.data

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.hippo.unifile.UniFile
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.settings.screen.SettingsDataScreen
import eu.kanade.presentation.util.relativeTimeSpanString
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.backup.BackupNotifier
import eu.kanade.tachiyomi.data.backup.create.BackupCreator
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.cloud.CloudBackupManager
import eu.kanade.tachiyomi.data.cloud.CloudFile
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

class GoogleDriveFilePickerScreen(
    private val parentId: String? = null,
    private val relativePath: String? = null, // Path relative to Google Drive root
    private val mode: Mode,
    private val backupOptions: BackupOptions? = null,
) : Screen {

    override val key: ScreenKey =
        "GoogleDriveFilePickerScreen:${parentId ?: "root"}:${mode.name}:${System.identityHashCode(this)}"

    enum class Mode {
        PICK_FOLDER,
        PICK_FILE,
        SAVE_FILE,
    }

    @Composable
    override fun Content() {
        val context = androidx.compose.ui.platform.LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val cloudBackupManager = remember { Injekt.get<CloudBackupManager>() }
        val backupPreferences = remember { Injekt.get<BackupPreferences>() }

        val rootLabel = context.stringResource(MR.strings.label_google_drive)

        // UI Helpers
        val parentName = relativePath?.substringAfterLast("/") ?: ""
        val displaySubtitle = if (relativePath.isNullOrEmpty()) null else "$rootLabel/$relativePath"

        var items by remember { mutableStateOf<List<CloudFile>>(emptyList()) }
        var isLoading by remember { mutableStateOf(true) }
        var showCreateFolderDialog by remember { mutableStateOf(false) }
        var grandParentId by remember { mutableStateOf<String?>(null) }
        var saveFileName by remember { mutableStateOf(BackupCreator.getFilename()) }
        var progressTitle by remember { mutableStateOf<String?>(null) }

        val refresh = {
            scope.launch {
                isLoading = true
                try {
                    items = cloudBackupManager.listFiles(parentId)
                        .sortedWith(
                            compareByDescending<CloudFile> { it.isFolder }
                                .thenByDescending { it.modifiedTime }
                                .thenBy { it.name },
                        )

                    if (parentId != null) {
                        val file = cloudBackupManager.getFile(parentId)
                        grandParentId = file?.parents?.firstOrNull()
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    context.toast(MR.strings.unknown_error)
                } finally {
                    isLoading = false
                }
            }
        }

        LaunchedEffect(parentId) {
            refresh()
        }

        if (showCreateFolderDialog) {
            var folderName by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showCreateFolderDialog = false },
                title = { Text(stringResource(MR.strings.action_create)) },
                text = {
                    OutlinedTextField(
                        value = folderName,
                        onValueChange = { folderName = it },
                        label = { Text(stringResource(MR.strings.name)) },
                        singleLine = true,
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showCreateFolderDialog = false
                            scope.launch {
                                val result = cloudBackupManager.createFolder(folderName, parentId)
                                if (result != null) {
                                    refresh()
                                } else {
                                    context.toast(MR.strings.unknown_error)
                                }
                            }
                        },
                    ) {
                        Text(stringResource(MR.strings.action_create))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showCreateFolderDialog = false }) {
                        Text(stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }

        val currentProgressTitle = progressTitle
        if (currentProgressTitle != null) {
            AlertDialog(
                onDismissRequest = {},
                confirmButton = {},
                title = { Text(currentProgressTitle) },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.padding(vertical = 16.dp))
                    }
                },
            )
        }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = if (parentId == null) rootLabel else parentName,
                    subtitle = displaySubtitle,
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                    actions = {
                        IconButton(
                            onClick = { showCreateFolderDialog = true },
                            enabled = progressTitle == null,
                        ) {
                            Icon(Icons.Outlined.CreateNewFolder, contentDescription = null)
                        }
                    },
                )
            },
            bottomBar = {
                if (parentId != null && (mode == Mode.PICK_FOLDER || mode == Mode.SAVE_FILE)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(8.dp),
                    ) {
                        if (mode == Mode.PICK_FOLDER) {
                            Button(
                                onClick = {
                                    backupPreferences.cloudStorageLocation.set(parentId)
                                    backupPreferences.cloudStoragePath.set(relativePath!!)
                                    navigator.popUntil { it is SettingsDataScreen }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = progressTitle == null,
                            ) {
                                Text(stringResource(MR.strings.onboarding_storage_action_select))
                            }
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                OutlinedTextField(
                                    value = saveFileName,
                                    onValueChange = { saveFileName = it },
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 8.dp),
                                    singleLine = true,
                                    enabled = progressTitle == null,
                                )
                                Button(
                                    enabled = progressTitle == null,
                                    onClick = {
                                        scope.launch {
                                            progressTitle = context.stringResource(MR.strings.creating_backup)
                                            showNotification(context, progressTitle!!)
                                            val tempFile = File(
                                                context.cacheDir,
                                                "manual_cloud_backup_${System.currentTimeMillis()}.tachibk",
                                            )
                                            try {
                                                if (!tempFile.exists()) tempFile.createNewFile()

                                                val finalName =
                                                    if (saveFileName.endsWith(".tachibk")) {
                                                        saveFileName
                                                    } else {
                                                        "$saveFileName.tachibk"
                                                    }
                                                val uniFile = UniFile.fromFile(tempFile)!!

                                                BackupCreator(context, false).backup(
                                                    uniFile.uri,
                                                    backupOptions ?: BackupOptions(),
                                                )
                                                cloudBackupManager.uploadLocalBackup(tempFile, finalName)
                                                context.toast(MR.strings.backup_created)
                                                navigator.popUntil { it is SettingsDataScreen }
                                            } catch (e: Exception) {
                                                if (e is CancellationException) throw e
                                                logcat(LogPriority.ERROR, e)
                                                context.toast(MR.strings.creating_backup_error)
                                                tempFile.delete()
                                            } finally {
                                                progressTitle = null
                                                context.cancelNotification(Notifications.ID_CLOUD_MIRROR_PROGRESS)
                                            }
                                        }
                                    },
                                ) {
                                    Text(stringResource(MR.strings.action_save))
                                }
                            }
                        }
                    }
                }
            },
        ) { contentPadding ->
            if (isLoading) {
                Text(
                    text = stringResource(MR.strings.loading),
                    modifier = Modifier
                        .padding(contentPadding)
                        .padding(16.dp),
                )
            } else {
                FastScrollLazyColumn(
                    modifier = Modifier.padding(contentPadding),
                ) {
                    // Go up row: Only if not at Root (parentId == null)
                    if (parentId != null) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = progressTitle == null) {
                                        val items = navigator.items
                                        val previousScreen = if (items.size > 1) items[items.size - 2] else null
                                        if (previousScreen is GoogleDriveFilePickerScreen) {
                                            navigator.pop()
                                        } else {
                                            // Manual push parent
                                            val newRelativePath = relativePath?.substringBeforeLast("/", "") ?: ""
                                            if (newRelativePath.isEmpty()) {
                                                // Going up to root
                                                navigator.replace(
                                                    GoogleDriveFilePickerScreen(
                                                        mode = mode,
                                                        backupOptions = backupOptions,
                                                    ),
                                                )
                                            } else {
                                                navigator.replace(
                                                    GoogleDriveFilePickerScreen(
                                                        parentId = grandParentId.takeUnless { it == "root" },
                                                        relativePath = newRelativePath,
                                                        mode = mode,
                                                        backupOptions = backupOptions,
                                                    ),
                                                )
                                            }
                                        }
                                    }
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Outlined.ArrowUpward,
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 16.dp),
                                )
                                Text(
                                    stringResource(MR.strings.action_bar_up_description),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }

                    if (items.isEmpty()) {
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    text = stringResource(MR.strings.no_results_found),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    } else {
                        items(items) { item ->
                            CloudFileItem(
                                file = item,
                                onClick = {
                                    if (progressTitle != null) return@CloudFileItem
                                    if (item.isFolder) {
                                        navigator.push(
                                            GoogleDriveFilePickerScreen(
                                                parentId = item.id,
                                                relativePath =
                                                if (relativePath.isNullOrEmpty()) {
                                                    item.name
                                                } else {
                                                    "$relativePath/${item.name}"
                                                },
                                                mode = mode,
                                                backupOptions = backupOptions,
                                            ),
                                        )
                                    } else if (mode == Mode.PICK_FILE && item.name.endsWith(".tachibk")) {
                                        scope.launch {
                                            progressTitle =
                                                context.stringResource(MR.strings.downloading_with_progress, 0)
                                                    .substringBefore(" (")
                                            showNotification(context, progressTitle!!)
                                            val tempFile = File(
                                                context.cacheDir,
                                                "temp_restore_${System.currentTimeMillis()}.tachibk",
                                            )
                                            try {
                                                if (!tempFile.exists()) tempFile.createNewFile()
                                                cloudBackupManager.downloadCloudBackup(item.id, tempFile)

                                                if (tempFile.exists() && tempFile.length() > 0) {
                                                    val uri = UniFile.fromFile(tempFile)!!.uri.toString()
                                                    navigator.popUntil { it is SettingsDataScreen }
                                                    navigator.push(RestoreBackupScreen(uri))
                                                } else {
                                                    throw Exception("Downloaded file is empty")
                                                }
                                            } catch (e: Exception) {
                                                if (e is CancellationException) throw e
                                                logcat(LogPriority.ERROR, e)
                                                val errorMsg = if (e.message?.contains("403") == true) {
                                                    context.stringResource(MR.strings.cloud_error_403)
                                                } else {
                                                    context.stringResource(MR.strings.restoring_backup_error)
                                                }
                                                context.toast(errorMsg)
                                                BackupNotifier(context).showRestoreError(errorMsg)
                                                tempFile.delete()
                                            } finally {
                                                progressTitle = null
                                                context.cancelNotification(Notifications.ID_CLOUD_MIRROR_PROGRESS)
                                            }
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun CloudFileItem(
        file: CloudFile,
        onClick: () -> Unit,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (file.isFolder) Icons.Outlined.Folder else Icons.Outlined.Description,
                contentDescription = null,
                modifier = Modifier.padding(end = 16.dp),
            )
            Column {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!file.isFolder) {
                        Text(
                            text = android.text.format.Formatter.formatFileSize(
                                androidx.compose.ui.platform.LocalContext.current,
                                file.size,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = " • ",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = relativeTimeSpanString(file.modifiedTime),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    private fun showNotification(context: Context, title: String) {
        val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_BACKUP_RESTORE_PROGRESS)
            .setSmallIcon(R.drawable.ic_refresh_24dp)
            .setContentTitle(title)
            .setContentText(context.stringResource(MR.strings.channel_progress))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        try {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                NotificationManagerCompat.from(context).notify(Notifications.ID_CLOUD_MIRROR_PROGRESS, notification)
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
        }
    }
}
