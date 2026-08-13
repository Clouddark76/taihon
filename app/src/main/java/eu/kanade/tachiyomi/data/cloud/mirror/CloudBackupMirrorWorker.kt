package eu.kanade.tachiyomi.data.cloud.mirror

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.backup.create.BackupCreator
import eu.kanade.tachiyomi.data.cloud.CloudBackupManager
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

class CloudBackupMirrorWorker(context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        logcat(LogPriority.DEBUG) { "Starting full cloud mirror sync" }

        val backupPreferences = Injekt.get<BackupPreferences>()
        val cloudLocationId = backupPreferences.cloudStorageLocation.get()
        if (cloudLocationId.isEmpty()) {
            logcat(LogPriority.DEBUG) { "Cloud storage location not set, skipping sync" }
            return Result.failure()
        }

        val cloudBackupManager = Injekt.get<CloudBackupManager>()
        if (!cloudBackupManager.isAvailable() || cloudBackupManager.accountState.value == null) {
            logcat(LogPriority.DEBUG) { "Cloud manager not available or not signed in, retrying later" }
            return Result.retry()
        }

        val storageManager = Injekt.get<StorageManager>()
        val localAutoBackupDir = storageManager.getAutomaticBackupsDirectory() ?: return Result.failure()

        setForegroundSafely()

        return try {
            // 1. Get local files
            logcat(LogPriority.DEBUG) { "Checking local backups in: ${localAutoBackupDir.uri}" }
            if (!localAutoBackupDir.exists()) {
                logcat(LogPriority.ERROR) { "Local backup directory does not exist" }
                return Result.failure()
            }

            val allFiles = localAutoBackupDir.listFiles() ?: emptyArray()
            val localFiles = allFiles.filter { it.name?.endsWith(".tachibk") == true }

            logcat(LogPriority.DEBUG) {
                "Found ${localFiles.size} local auto-backups out of ${allFiles.size} total files"
            }
            if (localFiles.isEmpty() && allFiles.isNotEmpty()) {
                logcat(LogPriority.DEBUG) {
                    "No .tachibk files found, but saw: ${allFiles.take(5).joinToString { it.name ?: "null" }}"
                }
            }

            // 2. Get cloud files
            val cloudFiles = cloudBackupManager.listFiles(cloudLocationId)
            val cloudFileNames = cloudFiles.map { it.name }.toSet()
            logcat(LogPriority.DEBUG) { "Found ${cloudFiles.size} files in cloud storage" }

            // 3. Sync missing to cloud
            val missingLocalFiles = localFiles.filter { it.name != null && it.name !in cloudFileNames }

            if (missingLocalFiles.isNotEmpty()) {
                logcat(LogPriority.INFO) {
                    "Found ${missingLocalFiles.size} files missing in cloud. Starting upload..."
                }

                var hasFailures = false
                for (localFile in missingLocalFiles.sortedBy { it.name }) {
                    logcat(LogPriority.DEBUG) { "Mirroring ${localFile.name} to cloud..." }
                    val tempFile = File(applicationContext.cacheDir, "mirror_${localFile.name}")
                    try {
                        val bytesCopied = localFile.openInputStream().use { input ->
                            tempFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }

                        if (bytesCopied == 0L) {
                            logcat(LogPriority.ERROR) { "Local backup file is empty: ${localFile.name}" }
                            continue
                        }

                        val result = cloudBackupManager.uploadLocalBackup(tempFile, localFile.name!!)
                        if (result == null) {
                            logcat(LogPriority.ERROR) { "Failed to upload ${localFile.name} during mirror" }
                            hasFailures = true
                        } else {
                            logcat(LogPriority.INFO) { "Successfully mirrored ${localFile.name} to cloud" }
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        logcat(LogPriority.ERROR, e) { "Error uploading ${localFile.name}" }
                        hasFailures = true
                    } finally {
                        tempFile.delete()
                    }
                }
                if (hasFailures) return Result.retry()
            } else {
                logcat(LogPriority.DEBUG) { "No missing files to mirror" }
            }

            // 4. Prune cloud files that exceed the retention limit
            val backupRetention = backupPreferences.backupRetention.get()
            val autoBackupRegex = BackupCreator.FILENAME_REGEX

            val cloudBackups = cloudFiles
                .filter { autoBackupRegex.matches(it.name) }
                .sortedByDescending { it.modifiedTime }

            if (cloudBackups.size > backupRetention) {
                val filesToPrune = cloudBackups.drop(backupRetention)
                logcat(LogPriority.INFO) { "Pruning ${filesToPrune.size} old cloud backups" }
                filesToPrune.forEach {
                    logcat(LogPriority.DEBUG) { "Pruning cloud file ${it.name} as it exceeds retention limit" }
                    cloudBackupManager.deleteFile(it.id)
                }
            }

            Result.success()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logcat(LogPriority.ERROR, e) { "Failed during cloud mirror sync" }
            Result.retry()
        } finally {
            applicationContext.cancelNotification(Notifications.ID_CLOUD_MIRROR_PROGRESS)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, Notifications.CHANNEL_BACKUP_RESTORE_PROGRESS)
            .setSmallIcon(R.drawable.ic_refresh_24dp)
            .setContentTitle(applicationContext.stringResource(MR.strings.cloud_backups_label))
            .setContentText(applicationContext.stringResource(MR.strings.channel_progress))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        return ForegroundInfo(
            Notifications.ID_CLOUD_MIRROR_PROGRESS,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    companion object {
        private const val TAG = "CloudBackupMirror"

        fun schedule(context: Context) {
            val preferences = Injekt.get<LibraryPreferences>()
            val restrictions = preferences.autoUpdateDeviceRestrictions.get()

            val networkType = if (LibraryPreferences.DEVICE_NETWORK_NOT_METERED in restrictions) {
                NetworkType.UNMETERED
            } else {
                NetworkType.CONNECTED
            }

            val networkRequest = NetworkRequest.Builder().apply {
                removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                if (LibraryPreferences.DEVICE_ONLY_ON_WIFI in restrictions) {
                    addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                }
                if (LibraryPreferences.DEVICE_NETWORK_NOT_METERED in restrictions) {
                    addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                }
            }
                .build()

            val constraints = Constraints.Builder()
                .setRequiredNetworkRequest(networkRequest, networkType)
                .build()

            val request = OneTimeWorkRequestBuilder<CloudBackupMirrorWorker>()
                .setConstraints(constraints)
                .addTag(TAG)
                .build()

            androidx.work.WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    TAG,
                    ExistingWorkPolicy.REPLACE,
                    request,
                )
        }
    }
}
