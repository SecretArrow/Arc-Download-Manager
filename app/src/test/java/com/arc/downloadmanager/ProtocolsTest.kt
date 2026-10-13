package com.arc.downloadmanager

import com.arc.downloadmanager.engine.CookieScopePolicy
import com.arc.downloadmanager.engine.Protocols
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure protocol-registry tests for the multi-protocol download engine. */
class ProtocolsTest {
    @Test
    fun schemeOf_detectsCommonSchemes() {
        assertEquals("https", Protocols.schemeOf("https://example.com/file.zip"))
        assertEquals("ftp", Protocols.schemeOf("FTP://example.com/file.zip"))
        assertEquals("sftp", Protocols.schemeOf("sftp://example.com/file"))
        assertEquals("content", Protocols.schemeOf("content://downloads/all/1"))
        assertEquals("magnet", Protocols.schemeOf("magnet:?xt=urn:btih:abcdef"))
        assertNull(Protocols.schemeOf("example.com/file.zip"))
        assertNull(Protocols.schemeOf(""))
        assertNull(Protocols.schemeOf("notaurl"))
        assertNull(Protocols.schemeOf("ht tp://broken"))
    }

    @Test
    fun isSupportedUrl_acceptsAllTransferProtocols() {
        assertTrue(CookieScopePolicy.isSupportedUrl("https://example.com/file.zip"))
        assertTrue(CookieScopePolicy.isSupportedUrl("http://example.com/file.zip"))
        assertTrue(CookieScopePolicy.isSupportedUrl("ftp://ftp.example.com/pub/file.zip"))
        assertTrue(CookieScopePolicy.isSupportedUrl("ftps://ftp.example.com/file.zip"))
        assertTrue(CookieScopePolicy.isSupportedUrl("sftp://server.example.com/home/user/file.iso"))
        assertTrue(CookieScopePolicy.isSupportedUrl("content://media/external/images/1"))
        // OkHttp leniency: stray spaces and unicode must not be rejected.
        assertTrue(CookieScopePolicy.isSupportedUrl("https://example.com/a b ünïcode.bin"))
    }

    @Test
    fun isSupportedUrl_rejectsUnknownAndBroken() {
        assertFalse(CookieScopePolicy.isSupportedUrl("magnet:?xt=urn:btih:abcdef"))
        assertFalse(CookieScopePolicy.isSupportedUrl("rtsp://stream.example.com/live"))
        assertFalse(CookieScopePolicy.isSupportedUrl("ftp://"))
        assertFalse(CookieScopePolicy.isSupportedUrl("sftp:///no-host/file"))
        assertFalse(CookieScopePolicy.isSupportedUrl("just some text"))
        assertFalse(CookieScopePolicy.isSupportedUrl(""))
    }

    @Test
    fun isHttpUrl_isStrictHttpFamily() {
        assertTrue(CookieScopePolicy.isHttpUrl("https://example.com/x"))
        assertTrue(CookieScopePolicy.isHttpUrl("http://example.com/x"))
        assertFalse(CookieScopePolicy.isHttpUrl("ftp://example.com/x"))
        assertFalse(CookieScopePolicy.isHttpUrl("content://media/1"))
    }

    @Test
    fun parseRemote_extractsHostPortPathAndCredentials() {
        val plain = Protocols.parseRemote("ftp://ftp.example.com/pub/tools/file.zip")
        assertEquals("ftp", plain?.scheme)
        assertEquals("ftp.example.com", plain?.host)
        assertEquals(21, plain?.port)
        assertEquals("/pub/tools/file.zip", plain?.path)
        assertNull(plain?.user)
        assertNull(plain?.password)

        val auth = Protocols.parseRemote("ftp://user:p%40ss@server.local:2121/dir/file.iso")
        assertEquals("server.local", auth?.host)
        assertEquals(2121, auth?.port)
        assertEquals("user", auth?.user)
        assertEquals("p@ss", auth?.password)
        assertEquals("/dir/file.iso", auth?.path)
    }

    @Test
    fun parseRemote_defaultPortsAndSchemes() {
        val sftp = Protocols.parseRemote("sftp://my.host/backup.tar.gz")
        assertEquals(22, sftp?.port)
        assertEquals("sftp", sftp?.scheme)
        assertEquals("/backup.tar.gz", sftp?.path)

        val ftps = Protocols.parseRemote("ftps://my.host:990/f.bin")
        assertEquals(990, ftps?.port)
        assertEquals("ftps", ftps?.scheme)
    }

    @Test
    fun parseRemote_rejectsNonRemoteProtocols() {
        assertNull(Protocols.parseRemote("https://example.com/file.zip"))
        assertNull(Protocols.parseRemote("content://media/1"))
        assertNull(Protocols.parseRemote("magnet:?xt=urn:btih:abc"))
    }

    @Test
    fun parseRemote_handlesRootAndMissingPath() {
        assertEquals("/", Protocols.parseRemote("ftp://host")?.path)
        assertEquals("/", Protocols.parseRemote("ftp://host/")?.path)
    }

    @Test
    fun defaultPorts_matchProtocolRegistry() {
        assertEquals(80, Protocols.defaultPort(Protocols.HTTP))
        assertEquals(443, Protocols.defaultPort(Protocols.HTTPS))
        assertEquals(21, Protocols.defaultPort(Protocols.FTP))
        assertEquals(21, Protocols.defaultPort(Protocols.FTPS))
        assertEquals(22, Protocols.defaultPort(Protocols.SFTP))
        assertEquals(-1, Protocols.defaultPort("gopher"))
    }

    @Test
    fun labelFor_isDisplayFriendly() {
        assertEquals("HTTPS", Protocols.labelFor("https"))
        assertEquals("SFTP", Protocols.labelFor("sftp"))
        assertEquals("Content", Protocols.labelFor("content"))
        assertEquals("", Protocols.labelFor(null))
    }
}
