package com.arc.downloadmanager.engine

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Concatenates segment part files into a single output file, streaming with a bounded buffer.
 * The output file is created atomically-ish: content is written to a staging path and then renamed.
 */
object MergeManager {

    private const val COPY_BUFFER = 512 * 1024

    /** Merges [parts] (in order) into [output]. Returns the output file on success. */
    @Throws(IOException::class)
    fun merge(parts: List<File>, output: File): File {
        require(parts.isNotEmpty()) { "No parts to merge" }
        output.parentFile?.mkdirs()
        val staging = File(output.parentFile, output.name + ".staging")
        staging.delete()
        try {
            FileOutputStream(staging).use { out ->
                for (part in parts) {
                    if (!part.exists() || part.length() == 0L) {
                        throw IOException("Missing or empty segment: ${part.name}")
                    }
                    part.inputStream().use { input -> copy(input, out) }
                }
                out.flush()
                out.fd.sync()
            }
            if (staging.renameTo(output)) {
                return output
            }
            // Fallback copy-rename for cross-filesystem cases
            staging.copyTo(output, overwrite = true)
            staging.delete()
            return output
        } finally {
            if (staging.exists()) staging.delete()
        }
    }

    private fun copy(input: InputStream, output: FileOutputStream) {
        val buffer = ByteArray(COPY_BUFFER)
        var read = input.read(buffer)
        while (read != -1) {
            output.write(buffer, 0, read)
            read = input.read(buffer)
        }
    }
}
