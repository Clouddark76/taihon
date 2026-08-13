package eu.kanade.tachiyomi.data.cloud

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class GoogleDriveBackupManager(context: Context) : CloudBackupManager {
    override val accountState: StateFlow<CloudAccount?> = MutableStateFlow(null)
    override fun isAvailable() = false
    override fun getSignInIntent(): Intent = throw UnsupportedOperationException()
    override fun signOut() {}
    override fun updateAccount() {}
    override suspend fun uploadLocalBackup(localFile: java.io.File, fileName: String) = null
    override suspend fun listAllCloudBackups() = emptyList<Pair<String, List<CloudFile>>>()
    override suspend fun downloadCloudBackup(fileId: String, localFile: java.io.File) {}
    override suspend fun listFiles(parentId: String?) = emptyList<CloudFile>()
    override suspend fun deleteFile(fileId: String) {}
    override suspend fun getFile(fileId: String) = null
    override suspend fun createFolder(name: String, parentId: String?) = null
}
