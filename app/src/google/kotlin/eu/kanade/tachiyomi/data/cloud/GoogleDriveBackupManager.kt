package eu.kanade.tachiyomi.data.cloud

import android.content.Context
import android.content.Intent
import android.os.Build
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.FileContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import eu.kanade.tachiyomi.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.backup.service.BackupPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class GoogleDriveBackupManager(
    private val context: Context,
    private val backupPreferences: BackupPreferences = Injekt.get(),
) : CloudBackupManager {

    private val googleSignInOptions = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestEmail()
        .requestScopes(Scope(DriveScopes.DRIVE_FILE), Scope(DriveScopes.DRIVE_METADATA_READONLY))
        .build()

    private val googleSignInClient = GoogleSignIn.getClient(context, googleSignInOptions)

    private var driveService: Drive? = null

    private val _accountState = MutableStateFlow<CloudAccount?>(null)
    override val accountState: StateFlow<CloudAccount?> = _accountState.asStateFlow()

    init {
        updateAccount()
    }

    override fun isAvailable() = true

    override fun getSignInIntent(): Intent = googleSignInClient.signInIntent

    override fun signOut() {
        googleSignInClient.signOut()
        driveService = null
        updateAccount()
    }

    override fun updateAccount() {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        _accountState.update {
            account?.let {
                CloudAccount(
                    email = it.email ?: "",
                    displayName = it.displayName,
                    rawAccount = it,
                )
            }
        }
    }

    private fun getDriveService(): Drive? {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return null
        if (driveService != null) return driveService

        val credential = GoogleAccountCredential.usingOAuth2(
            context,
            listOf(DriveScopes.DRIVE_FILE, DriveScopes.DRIVE_METADATA_READONLY),
        ).apply {
            selectedAccount = account.account
        }

        driveService = Drive.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), credential)
            .setApplicationName(context.getString(R.string.app_name))
            .build()

        return driveService
    }

    override suspend fun uploadLocalBackup(localFile: java.io.File, fileName: String): String? = withContext(
        Dispatchers.IO,
    ) {
        if (!localFile.exists() || localFile.length() == 0L) {
            logcat(LogPriority.ERROR) { "Attempted to upload empty or non-existent file: ${localFile.absolutePath}" }
            return@withContext null
        }
        val drive = getDriveService() ?: return@withContext null
        val folderId = getOrCreateBackupFolder(drive) ?: return@withContext null

        val metadata = File().apply {
            name = fileName
            parents = listOf(folderId)
        }

        try {
            val content = FileContent("application/octet-stream", localFile)
            val file = drive.files().create(metadata, content).setFields("id").execute()
            file.id
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logcat(LogPriority.ERROR, e)
            null
        }
    }

    override suspend fun listAllCloudBackups(): List<Pair<String, List<CloudFile>>> = withContext(Dispatchers.IO) {
        val drive = getDriveService() ?: return@withContext emptyList()
        val rootId = findFolder(drive, CloudBackupManager.ROOT_FOLDER) ?: return@withContext emptyList()
        val backupsId = findFolder(drive, CloudBackupManager.BACKUPS_FOLDER, rootId) ?: return@withContext emptyList()

        val deviceFolders = listFilesInternal(drive, backupsId, CloudBackupManager.MIME_TYPE_FOLDER)

        deviceFolders.map { folder ->
            folder.name to listFilesInternal(drive, folder.id)
                .filter { it.name.endsWith(".tachibk") }
                .sortedByDescending { it.name }
                .map { it.toCloudFile() }
        }
    }

    override suspend fun downloadCloudBackup(fileId: String, localFile: java.io.File) = withContext(Dispatchers.IO) {
        val drive = getDriveService() ?: throw Exception("Drive service not available")
        try {
            localFile.outputStream().use { output ->
                drive.files().get(fileId).executeMediaAndDownloadTo(output)
            }
            if (localFile.length() == 0L) {
                throw Exception("Downloaded file is empty")
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error downloading file $fileId" }
            throw e
        }
    }

    override suspend fun listFiles(parentId: String?): List<CloudFile> = withContext(Dispatchers.IO) {
        val drive = getDriveService() ?: return@withContext emptyList()
        listFilesInternal(drive, parentId).map { it.toCloudFile() }
    }

    override suspend fun deleteFile(fileId: String) {
        withContext(Dispatchers.IO) {
            getDriveService()?.files()?.delete(fileId)?.execute()
        }
    }

    override suspend fun getFile(fileId: String): CloudFile? = withContext(Dispatchers.IO) {
        getDriveService()?.files()?.get(fileId)
            ?.setFields("id, name, parents, modifiedTime, size, mimeType")
            ?.execute()?.toCloudFile()
    }

    override suspend fun createFolder(name: String, parentId: String?): String? = withContext(Dispatchers.IO) {
        val drive = getDriveService() ?: return@withContext null
        createFolderInternal(drive, name, parentId)
    }

    private fun createFolderInternal(drive: Drive, name: String, parentId: String? = null): String? {
        val metadata = File().apply {
            this.name = name
            mimeType = CloudBackupManager.MIME_TYPE_FOLDER
            if (parentId != null) parents = listOf(parentId)
        }
        return try {
            drive.files().create(metadata).setFields("id").execute().id
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
    }

    private fun findFolder(drive: Drive, name: String, parentId: String? = null): String? {
        val escapedName = name.replace("\\", "\\\\").replace("'", "\\'")

        val q = mutableListOf(
            "name = '$escapedName'",
            "mimeType = '${CloudBackupManager.MIME_TYPE_FOLDER}'",
            "trashed = false",
        )

        q.add(if (parentId != null) "'$parentId' in parents" else "'root' in parents")

        return try {
            val result = drive.files().list()
                .setQ(q.joinToString(" and "))
                .setFields("files(id)")
                .execute()

            result.files?.firstOrNull()?.id
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
    }
    private fun listFilesInternal(drive: Drive, parentId: String?, mimeType: String? = null): List<File> {
        val q = mutableListOf("trashed = false")
        q.add(if (parentId != null) "'$parentId' in parents" else "'root' in parents")
        if (mimeType != null) q.add("mimeType = '$mimeType'")

        return try {
            val files = mutableListOf<File>()
            var pageToken: String? = null
            do {
                val result = drive.files().list()
                    .setQ(q.joinToString(" and "))
                    .setFields("nextPageToken, files(id, name, modifiedTime, size, mimeType)")
                    .setPageToken(pageToken)
                    .execute()
                result.files?.let { files.addAll(it) }
                pageToken = result.nextPageToken
            } while (pageToken != null)
            files
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            emptyList()
        }
    }

    private fun getOrCreateBackupFolder(drive: Drive): String? {
        val customLocation = backupPreferences.cloudStorageLocation.get()
        if (customLocation.isNotEmpty()) return customLocation

        val rootId =
            findFolder(drive, CloudBackupManager.ROOT_FOLDER)
                ?: createFolderInternal(drive, CloudBackupManager.ROOT_FOLDER)
                ?: return null
        val backupsId =
            findFolder(drive, CloudBackupManager.BACKUPS_FOLDER, rootId)
                ?: createFolderInternal(drive, CloudBackupManager.BACKUPS_FOLDER, rootId)
                ?: return null
        return findFolder(drive, Build.MODEL, backupsId) ?: createFolderInternal(drive, Build.MODEL, backupsId)
    }

    @Suppress("UsePropertyAccessSyntax")
    private fun File.toCloudFile() = CloudFile(
        id = id,
        name = name,
        size = getSize() ?: 0L,
        modifiedTime = modifiedTime?.value ?: 0L,
        mimeType = mimeType,
        parents = parents,
    )
}
