package com.arc.downloadmanager.engine

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/** Streaming SHA-256 helpers. */
object Checksum {
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
            var read = input.read(buffer)
            while (read != -1) {
                digest.update(buffer, 0, read)
                read = input.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun normalize(hex: String): String = hex.trim().lowercase().removePrefix("sha256:").removePrefix("sha-256:").removePrefix("sha256 ")

    fun matches(
        expected: String,
        actual: String,
    ): Boolean = normalize(expected) == normalize(actual)
}
