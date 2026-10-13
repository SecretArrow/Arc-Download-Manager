package com.arc.downloadmanager.engine.transport

import com.arc.downloadmanager.engine.FilenameDetector
import com.arc.downloadmanager.engine.Protocols
import java.io.IOException
import java.io.InputStream
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPCmd
import org.apache.commons.net.ftp.FTPSClient

/** Thrown when the FTP server rejects the credentials. */
class FtpAuthException(
    val replyCode: Int,
) : IOException("FTP login rejected by server ($replyCode)")

/** Opened FTP/FTPS session: file metadata plus a one-shot resume-capable data stream. */
class FtpSession(
    val size: Long,
    val filenameHint: String?,
    val restSupported: Boolean,
    private val client: FTPClient,
    private val path: String,
) : AutoCloseable {
    /** Opens the RETR data stream starting at [offset] (0 = full file). Called exactly once. */
    fun openStreamAt(offset: Long): InputStream {
        if (offset > 0) client.setRestartOffset(offset)
        return client.retrieveFileStream(path)
            ?: throw IOException("FTP RETR failed: ${client.replyCode} ${client.replyString?.trim().orEmpty()}")
    }

    override fun close() {
        runCatching { client.completePendingCommand() }
        runCatching { client.logout() }
        runCatching { client.disconnect() }
    }
}

/**
 * FTP/FTPS transport built on Apache Commons Net. Uses passive mode + binary transfer, keeps
 * one control connection per task attempt and supports REST-based resume for partial data.
 */
class FtpTransport {
    /** Connects, authenticates and probes the remote file. Blocking; call on an IO dispatcher. */
    fun connect(
        url: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): FtpSession {
        val target = Protocols.parseRemote(url) ?: throw IOException("Malformed FTP URL")
        if (target.path == "/" || target.path.isBlank()) throw IOException("FTP URL does not point at a file")
        val client =
            if (target.scheme == Protocols.FTPS) {
                // Download managers routinely reach self-signed home/NAS servers; accept any
                // certificate while keeping control + data channels TLS-encrypted.
                FTPSClient("TLS").apply { trustManager = LenientTrustManager }
            } else {
                FTPClient()
            }
        client.defaultTimeout = connectTimeoutMs
        client.connectTimeout = connectTimeoutMs
        try {
            client.connect(target.host, target.port)
            client.soTimeout = readTimeoutMs
            client.setDataTimeout(readTimeoutMs)
            client.enterLocalPassiveMode()
            client.setFileType(FTP.BINARY_FILE_TYPE)
            val user = target.user ?: ANONYMOUS_USER
            val pass = target.password ?: ANONYMOUS_PASSWORD
            if (!client.login(user, pass)) {
                val reply = client.replyCode
                runCatching { client.disconnect() }
                throw FtpAuthException(reply)
            }
            if (client is FTPSClient) runCatching { client.execPROT("P") }
            val file = runCatching { client.mlistFile(target.path) }.getOrNull()
            if (file?.isDirectory == true) throw IOException("FTP path is a directory: $target.path")
            var size = file?.size?.takeIf { it >= 0 } ?: -1L
            if (size < 0) {
                size =
                    runCatching {
                        if (client.sendCommand(FTPCmd.SIZE, target.path) == 213) {
                            client.replyString?.trim()?.substringAfter(' ')?.toLongOrNull() ?: -1L
                        } else {
                            -1L
                        }
                    }.getOrDefault(-1L)
            }
            if (size < 0) {
                size =
                    runCatching {
                        client.listFiles(target.path).firstOrNull()?.size?.takeIf { it >= 0 } ?: -1L
                    }.getOrDefault(-1L)
            }
            // RFC 3659: REST 0 must be answered with 350 when the server supports resume.
            val restSupported = runCatching { client.sendCommand(FTPCmd.REST, "0") == 350 }.getOrDefault(false)
            return FtpSession(size, FilenameDetector.fromUrl(url), restSupported, client, target.path)
        } catch (e: IOException) {
            runCatching { client.disconnect() }
            throw e
        } catch (e: RuntimeException) {
            runCatching { client.disconnect() }
            throw IOException("FTP connection failed: ${e.message}", e)
        }
    }

    private companion object {
        const val ANONYMOUS_USER = "anonymous"
        const val ANONYMOUS_PASSWORD = "arc-download@example.com"
    }
}

/** Accepts any server certificate (self-signed home servers are the norm for FTPS). */
private object LenientTrustManager : X509TrustManager {
    override fun checkClientTrusted(
        chain: Array<X509Certificate>,
        authType: String,
    ) {}

    override fun checkServerTrusted(
        chain: Array<X509Certificate>,
        authType: String,
    ) {}

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
