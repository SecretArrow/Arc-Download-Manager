package com.arc.downloadmanager.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Metadata row for one file stored inside the encrypted vault. The actual
 * bytes live as an AES-256-GCM blob in the app-private vault directory and
 * are invisible to the system gallery / file managers.
 */
@Entity(tableName = "vault_files")
data class VaultFileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val mimeType: String?,
    /** Lightweight category used for the row icon (FileCategory name). */
    val category: String,
    val sizeBytes: Long,
    val encSizeBytes: Long,
    /** Blob file name inside the vault directory (never a user-visible path). */
    val blobPath: String,
    val addedAt: Long,
    val lastOpenedAt: Long? = null,
)
