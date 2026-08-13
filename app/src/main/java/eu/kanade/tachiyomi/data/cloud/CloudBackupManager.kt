package eu.kanade.tachiyomi.data.cloud

import android.content.Intent
import kotlinx.coroutines.flow.StateFlow

interface CloudBackupManager {
    val accountState: StateFlow<CloudAccount?>

    fun isAvailable(): Boolean
    fun getSignInIntent(): Intent
    fun signOut()
    fun updateAccount()

    suspend fun uploadLocalBackup(localFile: java.io.File, fileName: String): String?
    suspend fun listAllCloudBackups(): List<Pair<String, List<CloudFile>>>
    suspend fun downloadCloudBackup(fileId: String, localFile: java.io.File)
    suspend fun listFiles(parentId: String? = null): List<CloudFile>
    suspend fun deleteFile(fileId: String)
    suspend fun getFile(fileId: String): CloudFile?
    suspend fun createFolder(name: String, parentId: String? = null): String?

    // Shared folder names for consistency
    companion object {
        const val ROOT_FOLDER = "Taihon"
        const val BACKUPS_FOLDER = "Backups"
        const val MIME_TYPE_FOLDER = "application/vnd.google-apps.folder"
    }
}

data class CloudAccount(
    val email: String,
    val displayName: String?,
    val rawAccount: Any,
)

data class CloudFile(
    val id: String,
    val name: String,
    val size: Long,
    val modifiedTime: Long,
    val mimeType: String? = null,
    val parents: List<String>? = null,
) {
    val isFolder: Boolean
        get() = mimeType == "application/vnd.google-apps.folder"
}
