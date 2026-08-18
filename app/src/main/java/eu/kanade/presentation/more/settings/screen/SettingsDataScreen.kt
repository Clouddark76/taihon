package eu.kanade.presentation.more.settings.screen

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.vectorResource
import androidx.core.net.toUri
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.hippo.unifile.UniFile
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.screen.data.CreateBackupScreen
import eu.kanade.presentation.more.settings.screen.data.GoogleDriveFilePickerScreen
import eu.kanade.presentation.more.settings.screen.data.RestoreBackupScreen
import eu.kanade.presentation.more.settings.screen.data.StorageInfo
import eu.kanade.presentation.more.settings.widget.BasePreferenceWidget
import eu.kanade.presentation.more.settings.widget.PrefsHorizontalPadding
import eu.kanade.presentation.util.relativeTimeSpanString
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.backup.create.BackupCreateJob
import eu.kanade.tachiyomi.data.backup.restore.BackupRestoreJob
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.cloud.CloudBackupManager
import eu.kanade.tachiyomi.data.cloud.mirror.CloudBackupMirrorWorker
import eu.kanade.tachiyomi.data.export.LibraryExporter
import eu.kanade.tachiyomi.data.export.LibraryExporter.ExportOptions
import eu.kanade.tachiyomi.util.system.DeviceUtil
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.storage.displayablePath
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.storage.service.StoragePreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object SettingsDataScreen : SearchableSettings {

    val restorePreferenceKeyString = MR.strings.label_backup
    const val HELP_URL = "https://mihon.app/docs/faq/storage"

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.label_data_storage

    @Composable
    override fun RowScope.AppBarAction() {
        val uriHandler = LocalUriHandler.current
        IconButton(onClick = { uriHandler.openUri(HELP_URL) }) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
                contentDescription = stringResource(MR.strings.tracking_guide),
            )
        }
    }

    @Composable
    override fun getPreferences(): List<Preference> {
        val backupPreferences = Injekt.get<BackupPreferences>()
        val storagePreferences = Injekt.get<StoragePreferences>()

        return listOf(
            getStorageGroup(storagePreferences = storagePreferences),
            getBackupGroup(backupPreferences = backupPreferences),
            getCloudBackupGroup(backupPreferences = backupPreferences),
            getExportGroup(),
        )
    }

    @Composable
    fun storageLocationPicker(
        storageDirPref: tachiyomi.core.common.preference.Preference<String>,
    ): ManagedActivityResultLauncher<Uri?, Uri?> {
        val context = LocalContext.current

        return rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree(),
        ) { uri ->
            if (uri != null) {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION

                // For some reason InkBook devices do not implement the SAF properly. Persistable URI grants do not
                // work. However, simply retrieving the URI and using it works fine for these devices. Access is not
                // revoked after the app is closed or the device is restarted.
                // This also holds for some Samsung devices. Thus, we simply execute inside of a try-catch block and
                // ignore the exception if it is thrown.
                try {
                    context.contentResolver.takePersistableUriPermission(uri, flags)
                } catch (e: SecurityException) {
                    logcat(LogPriority.ERROR, e)
                    context.toast(MR.strings.file_picker_uri_permission_unsupported)
                }

                UniFile.fromUri(context, uri)?.let {
                    storageDirPref.set(it.uri.toString())
                }
            }
        }
    }

    @Composable
    fun storageLocationText(
        storageDirPref: tachiyomi.core.common.preference.Preference<String>,
    ): String {
        val context = LocalContext.current
        val storageDir by storageDirPref.collectAsState()

        if (storageDir == storageDirPref.defaultValue()) {
            return stringResource(MR.strings.no_location_set)
        }

        return remember(storageDir) {
            val file = UniFile.fromUri(context, storageDir.toUri())
            file?.displayablePath
        } ?: stringResource(MR.strings.invalid_location, storageDir)
    }

    @Composable
    private fun getStorageGroup(storagePreferences: StoragePreferences): Preference.PreferenceGroup {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val libraryPreferences = remember { Injekt.get<LibraryPreferences>() }
        val pickStorageLocation = storageLocationPicker(storagePreferences.baseStorageDirectory)

        val chapterCache = remember { Injekt.get<ChapterCache>() }
        var cacheReadableSizeSema by remember { mutableIntStateOf(0) }
        val cacheReadableSize = remember(cacheReadableSizeSema) { chapterCache.readableSize }

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_storage_usage),
            preferenceItems = listOf(
                Preference.PreferenceItem.CustomPreference(
                    title = stringResource(MR.strings.pref_storage_usage),
                ) {
                    BasePreferenceWidget(
                        subcomponent = {
                            StorageInfo(
                                modifier = Modifier.padding(horizontal = PrefsHorizontalPadding),
                            )
                        },
                    )
                },
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_storage_location),
                    subtitle = storageLocationText(storagePreferences.baseStorageDirectory),
                    onClick = {
                        try {
                            pickStorageLocation.launch(null)
                        } catch (_: ActivityNotFoundException) {
                            context.toast(MR.strings.file_picker_error)
                        }
                    },
                ),
                Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.pref_storage_location_info)),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_clear_chapter_cache),
                    subtitle = stringResource(MR.strings.used_cache, cacheReadableSize),
                    onClick = {
                        scope.launchNonCancellable {
                            try {
                                val deletedFiles = chapterCache.clear()
                                withUIContext {
                                    context.toast(context.stringResource(MR.strings.cache_deleted, deletedFiles))
                                    cacheReadableSizeSema++
                                }
                            } catch (e: Throwable) {
                                logcat(LogPriority.ERROR, e)
                                withUIContext { context.toast(MR.strings.cache_delete_error) }
                            }
                        }
                    },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = libraryPreferences.autoClearChapterCache,
                    title = stringResource(MR.strings.pref_auto_clear_chapter_cache),
                ),
            ),
        )
    }

    @Composable
    private fun getBackupGroup(backupPreferences: BackupPreferences): Preference.PreferenceGroup {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow

        val lastAutoBackup by backupPreferences.lastAutoBackupTimestamp.collectAsState()
        val backupRetention by backupPreferences.backupRetention.collectAsState()
        val backupInterval by backupPreferences.backupInterval.collectAsState()

        val chooseBackup = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) {
            if (it == null) {
                context.toast(MR.strings.file_null_uri_error)
                return@rememberLauncherForActivityResult
            }

            navigator.push(RestoreBackupScreen(it.toString()))
        }

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.label_backup),
            preferenceItems = listOf(
                // Manual actions
                getBackupActionPreference(
                    titleRes = restorePreferenceKeyString,
                    onCreateClick = { navigator.push(CreateBackupScreen()) },
                    onRestoreClick = {
                        if (!BackupRestoreJob.isRunning(context)) {
                            if (DeviceUtil.isMiui && DeviceUtil.isMiuiOptimizationDisabled()) {
                                context.toast(MR.strings.restore_miui_warning)
                            }
                            chooseBackup.launch(arrayOf("*/*"))
                        } else {
                            context.toast(MR.strings.restore_in_progress)
                        }
                    },
                ),

                // Automatic backups
                Preference.PreferenceItem.ListPreference(
                    preference = backupPreferences.backupInterval,
                    entries = mapOf(
                        0 to stringResource(MR.strings.off),
                        3 to stringResource(MR.strings.update_6hour).replace("6", "3"),
                        6 to stringResource(MR.strings.update_6hour),
                        12 to stringResource(MR.strings.update_12hour),
                        24 to stringResource(MR.strings.update_24hour),
                        48 to stringResource(MR.strings.update_48hour),
                        168 to stringResource(MR.strings.update_weekly),
                    ),
                    title = stringResource(MR.strings.pref_backup_interval),
                    onValueChanged = {
                        BackupCreateJob.setupTask(context, it)
                        true
                    },
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = backupRetention,
                    valueRange = 4..100,
                    title = stringResource(MR.strings.pref_backup_retention),
                    subtitle = stringResource(MR.strings.pref_backup_retention_info),
                    onValueChanged = { backupPreferences.backupRetention.set(it) },
                    badge = ImageVector.vectorResource(R.drawable.ic_taihon),
                    steps = 0,
                    enabled = backupInterval > 0,
                ),
                Preference.PreferenceItem.InfoPreference(
                    stringResource(MR.strings.backup_info) + "\n\n" +
                        stringResource(MR.strings.last_auto_backup_info, relativeTimeSpanString(lastAutoBackup)),
                ),
            ),
        )
    }

    @Composable
    private fun getCloudBackupGroup(
        backupPreferences: BackupPreferences,
    ): Preference.PreferenceGroup {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow

        val cloudBackupManager = remember { Injekt.get<CloudBackupManager>() }
        val account by cloudBackupManager.accountState.collectAsState()

        val loginLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
        ) {
            cloudBackupManager.updateAccount()
            if (cloudBackupManager.accountState.value != null) {
                backupPreferences.cloudBackupEnabled.set(true)
            }
        }

        val cloudLocationId by backupPreferences.cloudStorageLocation.collectAsState()
        val cloudPath by backupPreferences.cloudStoragePath.collectAsState()
        val isCloudEnabled by backupPreferences.cloudBackupEnabled.collectAsState()

        val cloudItems = mutableListOf<Preference.PreferenceItem<out Any, out Any>>()

        if (!cloudBackupManager.isAvailable()) {
            cloudItems.add(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.label_google_drive),
                    subtitle = stringResource(MR.strings.cloud_backup_not_supported),
                    onClick = { context.toast(MR.strings.cloud_backup_not_supported) },
                    icon = Icons.Outlined.Cloud,
                ),
            )
        } else {
            cloudItems.add(
                Preference.PreferenceItem.SwitchPreference(
                    preference = backupPreferences.cloudBackupEnabled,
                    title = if (account != null) {
                        stringResource(MR.strings.label_google_drive)
                    } else {
                        stringResource(MR.strings.login_title, stringResource(MR.strings.label_google_drive))
                    },
                    subtitle = if (account != null) {
                        context.stringResource(MR.strings.login_success) + ": ${account!!.email}"
                    } else {
                        stringResource(MR.strings.login)
                    },
                    icon = Icons.Outlined.Cloud,
                    onValueChanged = { enabled ->
                        if (enabled && account == null) {
                            try {
                                loginLauncher.launch(cloudBackupManager.getSignInIntent())
                            } catch (e: Exception) {
                                context.toast(e.message)
                            }
                            false
                        } else if (!enabled && account != null) {
                            cloudBackupManager.signOut()
                            true
                        } else {
                            true
                        }
                    },
                ),
            )

            if (isCloudEnabled && account != null) {
                cloudItems.add(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = backupPreferences.autoMirrorToCloud,
                        title = stringResource(MR.strings.pref_auto_mirror_to_cloud),
                        subtitle = stringResource(MR.strings.pref_auto_mirror_to_cloud_summary),
                        onValueChanged = { enabled ->
                            if (enabled && cloudLocationId.isEmpty()) {
                                context.toast(MR.strings.no_location_set)
                                false
                            } else {
                                if (enabled) {
                                    CloudBackupMirrorWorker.schedule(context)
                                }
                                true
                            }
                        },
                    ),
                )
                cloudItems.add(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.pref_storage_location),
                        subtitle = if (cloudPath.isEmpty()) {
                            stringResource(MR.strings.no_location_set)
                        } else {
                            val rootLabel = context.stringResource(MR.strings.label_google_drive)
                            "$rootLabel/$cloudPath"
                        },
                        onClick = {
                            navigator.push(
                                GoogleDriveFilePickerScreen(
                                    parentId = cloudLocationId.takeIf { it.isNotEmpty() },
                                    relativePath = cloudPath.takeIf { it.isNotEmpty() },
                                    mode = GoogleDriveFilePickerScreen.Mode.PICK_FOLDER,
                                ),
                            )
                        },
                    ),
                )
                cloudItems.add(
                    getBackupActionPreference(
                        titleRes = MR.strings.label_backup,
                        onCreateClick = {
                            if (cloudLocationId.isEmpty()) {
                                context.toast(MR.strings.no_location_set)
                                return@getBackupActionPreference
                            }
                            navigator.push(CreateBackupScreen(isCloud = true))
                        },
                        onRestoreClick = {
                            if (cloudLocationId.isEmpty()) {
                                context.toast(MR.strings.no_location_set)
                                return@getBackupActionPreference
                            }
                            navigator.push(
                                GoogleDriveFilePickerScreen(
                                    parentId = cloudLocationId.takeIf { it.isNotEmpty() },
                                    relativePath = cloudPath.takeIf { it.isNotEmpty() },
                                    mode = GoogleDriveFilePickerScreen.Mode.PICK_FILE,
                                ),
                            )
                        },
                    ),
                )
            }
        }

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.cloud_backups_label),
            badge = ImageVector.vectorResource(R.drawable.ic_taihon),
            preferenceItems = cloudItems,
        )
    }

    @Composable
    private fun getBackupActionPreference(
        titleRes: StringResource,
        onCreateClick: () -> Unit,
        onRestoreClick: () -> Unit,
    ): Preference.PreferenceItem.CustomPreference {
        return Preference.PreferenceItem.CustomPreference(
            title = stringResource(titleRes),
        ) {
            BasePreferenceWidget(
                subcomponent = {
                    MultiChoiceSegmentedButtonRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(intrinsicSize = IntrinsicSize.Min)
                            .padding(horizontal = PrefsHorizontalPadding),
                    ) {
                        SegmentedButton(
                            modifier = Modifier.fillMaxHeight(),
                            checked = false,
                            onCheckedChange = { onCreateClick() },
                            shape = SegmentedButtonDefaults.itemShape(0, 2),
                        ) {
                            Text(stringResource(MR.strings.pref_create_backup))
                        }
                        SegmentedButton(
                            modifier = Modifier.fillMaxHeight(),
                            checked = false,
                            onCheckedChange = { onRestoreClick() },
                            shape = SegmentedButtonDefaults.itemShape(1, 2),
                        ) {
                            Text(stringResource(MR.strings.pref_restore_backup))
                        }
                    }
                },
            )
        }
    }

    @Composable
    private fun getExportGroup(): Preference.PreferenceGroup {
        var showDialog by remember { mutableStateOf(false) }
        var exportOptions by remember {
            mutableStateOf(
                ExportOptions(
                    includeTitle = true,
                    includeAuthor = true,
                    includeArtist = true,
                ),
            )
        }

        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val getFavorites = remember { Injekt.get<GetFavorites>() }
        var favorites by remember { mutableStateOf<List<Manga>>(emptyList()) }
        LaunchedEffect(Unit) {
            favorites = getFavorites.await()
        }

        val saveFileLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("text/csv"),
        ) { uri ->
            uri?.let {
                scope.launch {
                    LibraryExporter.exportToCsv(
                        context = context,
                        uri = it,
                        favorites = favorites,
                        options = exportOptions,
                        onExportComplete = {
                            scope.launch(Dispatchers.Main) {
                                context.toast(MR.strings.library_exported)
                            }
                        },
                    )
                }
            }
        }

        if (showDialog) {
            ColumnSelectionDialog(
                options = exportOptions,
                onConfirm = { options ->
                    exportOptions = options
                    saveFileLauncher.launch("mihon_library.csv")
                },
                onDismissRequest = { showDialog = false },
            )
        }

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.export),
            preferenceItems = listOf(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.library_list),
                    onClick = { showDialog = true },
                ),
            ),
        )
    }

    @Composable
    private fun ColumnSelectionDialog(
        options: ExportOptions,
        onConfirm: (ExportOptions) -> Unit,
        onDismissRequest: () -> Unit,
    ) {
        var titleSelected by remember { mutableStateOf(options.includeTitle) }
        var authorSelected by remember { mutableStateOf(options.includeAuthor) }
        var artistSelected by remember { mutableStateOf(options.includeArtist) }

        AlertDialog(
            onDismissRequest = onDismissRequest,
            title = {
                Text(text = stringResource(MR.strings.migration_dialog_what_to_include))
            },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = titleSelected,
                            onCheckedChange = { checked ->
                                titleSelected = checked
                                if (!checked) {
                                    authorSelected = false
                                    artistSelected = false
                                }
                            },
                        )
                        Text(text = stringResource(MR.strings.title))
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = authorSelected,
                            onCheckedChange = { authorSelected = it },
                            enabled = titleSelected,
                        )
                        Text(text = stringResource(MR.strings.author))
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = artistSelected,
                            onCheckedChange = { artistSelected = it },
                            enabled = titleSelected,
                        )
                        Text(text = stringResource(MR.strings.artist))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onConfirm(
                            ExportOptions(
                                includeTitle = titleSelected,
                                includeAuthor = authorSelected,
                                includeArtist = artistSelected,
                            ),
                        )
                        onDismissRequest()
                    },
                ) {
                    Text(text = stringResource(MR.strings.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissRequest) {
                    Text(text = stringResource(MR.strings.action_cancel))
                }
            },
        )
    }
}
