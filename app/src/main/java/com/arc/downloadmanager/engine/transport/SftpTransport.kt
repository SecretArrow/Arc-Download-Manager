package com.arc.downloadmanager.engine.transport

import com.arc.downloadmanager.engine.FilenameDetector
import com.arc.downloadmanager.engine.Protocols
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import java.io.IOException
import java.io.InputStream

/** Thrown when the SFTP server rejects the credentials or permission is denied. */
class SftpAuthException(
    message: String,
) : IOException(message)

/** Opened SFTP session: file metadata plus a one-shot resume-capable data stream. */
class SftpSession(
    val size: Long,
    val filenameHint: String?,
    private val session: Session,
    private val channel: ChannelSftp,
    private val path: String,
) : AutoCloseable {
    /**
     * Opens the remote file starting at byte [offset] (0 = full file). JSch streams always
     * start at 0, so a resume skips ahead locally until the requested offset is reached.
     */
    fun openStreamAt(offset: Long): InputStream {
        val stream = channel.get(path) ?: throw IOException("SFTP open failed: $path")
        if (offset > 0) skipFully(stream, offset)
        return stream
    }

    override fun close() {
        runCatching { channel.disconnect() }
        runCatching { session.disconnect() }
    }

    companion object {
        /** InputStream.skip can under-skip; read-discard until [toSkip] bytes are consumed. */
        private fun skipFully(
            stream: InputStream,
            toSkip: Long,
        ) {
            val buffer = ByteArray(64 * 1024)
            var remaining = toSkip
            while (remaining > 0) {
                val n = stream.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (n < 0) throw IOException("Remote file is shorter than the resume offset")
                remaining -= n
            }
        }
    }
}

/** SFTP (SSH File Transfer Protocol) transport built on JSch. */
class SftpTransport {
    /** Connects, authenticates and probes the remote file. Blocking; call on an IO dispatcher. */
    fun connect(
        url: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): SftpSession {
        val target = Protocols.parseRemote(url) ?: throw IOException("Malformed SFTP URL")
        val user = target.user ?: throw SftpAuthException("SFTP requires credentials: sftp://user:pass@host/path")
        if (target.path == "/" || target.path.isBlank()) throw IOException("SFTP URL does not point at a file")
        val jsch = JSch()
        val session = jsch.getSession(user, target.host, target.port)
        session.setPassword(target.password ?: "")
        // Arbitrary user-entered hosts cannot have pre-distributed host keys; accept on first
        // use. The SSH transport itself still encrypts everything including the password.
        session.setConfig("StrictHostKeyChecking", "no")
        try {
            session.connect(connectTimeoutMs)
            session.timeout = readTimeoutMs
            val channel = session.openChannel("sftp") as ChannelSftp
            channel.connect(connectTimeoutMs)
            try {
                val attrs = channel.lstat(target.path)
                if (attrs.isDir) throw IOException("SFTP path is a directory: ${target.path}")
                return SftpSession(attrs.size, FilenameDetector.fromUrl(url), session, channel, target.path)
            } catch (e: SftpException) {
                runCatching { channel.disconnect() }
                throw mapSftpError(e, target.path)
            }
        } catch (e: IOException) {
            runCatching { session.disconnect() }
            throw e
        } catch (e: Exception) {
            runCatching { session.disconnect() }
            throw IOException("SFTP connection failed: ${e.message}", e)
        }
    }

    private fun mapSftpError(
        e: SftpException,
        path: String,
    ): IOException =
        when (e.id) {
            ChannelSftp.SSH_FX_NO_SUCH_FILE -> IOException("SFTP file not found: $path")
            ChannelSftp.SSH_FX_PERMISSION_DENIED -> SftpAuthException("SFTP permission denied for $path")
            else -> IOException("SFTP error ${e.id}: ${e.message ?: "unknown"}")
        }
}
