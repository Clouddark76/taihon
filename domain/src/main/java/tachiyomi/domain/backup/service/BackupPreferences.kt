package tachiyomi.domain.backup.service

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

class BackupPreferences(
    preferenceStore: PreferenceStore,
) {

    val backupRetention: Preference<Int> = preferenceStore.getInt("backup_retention", 12)
    val backupInterval: Preference<Int> = preferenceStore.getInt("backup_interval", 12)

    val lastAutoBackupTimestamp: Preference<Long> = preferenceStore.getLong(
        Preference.appStateKey("last_auto_backup_timestamp"),
        0L,
    )

    val autoMirrorToCloud: Preference<Boolean> = preferenceStore.getBoolean("auto_mirror_to_cloud", false)
    val cloudBackupEnabled: Preference<Boolean> = preferenceStore.getBoolean("cloud_backup_enabled", false)
    val cloudStorageLocation: Preference<String> = preferenceStore.getString("cloud_storage_location", "")
    val cloudStoragePath: Preference<String> = preferenceStore.getString("cloud_storage_path", "")
}
