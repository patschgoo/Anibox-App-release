package io.github.patschgoo.anibox.net

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import io.github.patschgoo.anibox.stream.MediaSource
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/** Why a share couldn't be used, in words people understand (the setup and the player show [message]). */
enum class Problem { HOST_NOT_FOUND, NO_ANSWER, WRONG_SIGN_IN, NO_SHARE, ACCESS_DENIED, NOT_FOUND, OTHER }

class SmbProblem(val problem: Problem, message: String, cause: Throwable? = null) : IOException(message, cause)

/** One share on one host, reached at [address] ([host] is the name people know it by). */
data class SmbTarget(val host: String, val address: String, val share: String, val signIn: SignIn?)

/**
 * A Windows / Samba share (the Shield's drives) through smbj: list folders, read files from any
 * position (so mpv can seek), and the few writes the progress journal needs.
 * From CastApp (network/Smb.kt, same owner, GPL-3.0), with writes and plain-English problems added.
 */
class SmbConnection(val target: SmbTarget) : AutoCloseable {
    private val client = SMBClient(
        SmbConfig.builder()
            .withTimeout(20, TimeUnit.SECONDS)
            // No socket read timeout: smbj treats a silent socket as a broken connection, and a paused
            // episode is silent for minutes. Each request still gives up after the timeout above.
            .withSoTimeout(0, TimeUnit.SECONDS)
            // Android's crypto lacks MD4 (needed for NTLM sign-in); BouncyCastle has it.
            .withSecurityProvider(BCSecurityProvider())
            .withMultiProtocolNegotiate(true)
            .withSigningRequired(false)
            .build(),
    )
    private var connection: Connection? = null
    private var session: Session? = null
    private var disk: DiskShare? = null
    /** Closed for good ([ShareAccess.reset]): it never connects again by itself, so nothing is left open. */
    @Volatile private var retired = false

    @Synchronized
    @Throws(IOException::class)
    fun share(): DiskShare {
        if (retired) throw IOException("This connection to “${target.share}” was closed.")
        disk?.takeIf { it.isConnected }?.let { return it }
        closeQuietly()
        val began = System.currentTimeMillis()
        try {
            val c = client.connect(target.address)
            connection = c
            val s = target.signIn
            val auth = if (s == null || s.user.isEmpty()) AuthenticationContext.guest()
            else AuthenticationContext(s.user, s.password.toCharArray(), s.domain.ifEmpty { null })
            val session = c.authenticate(auth)
            this.session = session
            val d = session.connectShare(target.share) as? DiskShare
                ?: throw SmbProblem(Problem.NO_SHARE, "“${target.share}” on ${target.host} isn't a folder share.")
            disk = d
            io.github.patschgoo.anibox.debug.DebugLog.i(
                "shares",
                "Signed in to “${target.share}” on ${target.host} (${target.address}) in ${System.currentTimeMillis() - began} ms, " +
                    "SMB ${c.connectionContext.negotiatedProtocol?.dialect} (reads up to ${c.connectionContext.negotiatedProtocol?.maxReadSize?.div(1024)} KB), ${if (target.signIn?.user.isNullOrEmpty()) "as guest" else "with a user name"}",
            )
            return d
        } catch (e: SmbProblem) {
            closeQuietly()
            throw e
        } catch (e: Exception) {
            closeQuietly()
            val p = describe(e, target)
            io.github.patschgoo.anibox.debug.DebugLog.w("shares", "Couldn't open “${target.share}” on ${target.host} (${target.address}) after ${System.currentTimeMillis() - began} ms: ${p.problem}", e)
            throw p
        }
    }

    private inline fun <T> io(path: String, block: (DiskShare) -> T): T = try {
        block(share())
    } catch (e: SmbProblem) {
        throw e
    } catch (e: Exception) {
        // Anything but a plain "no" from the share (a dropped connection, a timeout): connect again next time.
        if (e !is SMBApiException) {
            io.github.patschgoo.anibox.debug.DebugLog.w("shares", "$path on “${target.share}”: the connection failed", e)
            close()
        }
        throw describe(e, target, path)
    }

    data class Entry(val name: String, val folder: Boolean, val size: Long)

    /** Lists [path] ("" = the share's top folder), hidden names left out. */
    @Throws(IOException::class)
    fun list(path: String): List<Entry> = io(path) { d ->
        d.list(smbPath(path))
            .filter { it.fileName != "." && it.fileName != ".." }
            .map {
                val dir = it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
                Entry(it.fileName, dir, if (dir) 0 else it.endOfFile)
            }
    }

    @Throws(IOException::class)
    fun exists(path: String): Boolean = io(path) { d -> d.fileExists(smbPath(path)) }

    @Throws(IOException::class)
    fun folderExists(path: String): Boolean = io(path) { d -> path.isEmpty() || d.folderExists(smbPath(path)) }

    /** The file's size, or null when it isn't there. */
    @Throws(IOException::class)
    fun sizeOrNull(path: String): Long? = try {
        io(path) { d -> d.getFileInformation(smbPath(path)).standardInformation.endOfFile }
    } catch (e: SmbProblem) {
        if (e.problem == Problem.NOT_FOUND) null else throw e
    }

    /** "<size>-<last written, ms>", or null when the file isn't there (so a changed file is noticed without reading it). */
    @Throws(IOException::class)
    fun stampOrNull(path: String): String? = try {
        io(path) { d ->
            val info = d.getFileInformation(smbPath(path))
            "${info.standardInformation.endOfFile}-${info.basicInformation.lastWriteTime.toEpochMillis()}"
        }
    } catch (e: SmbProblem) {
        if (e.problem == Problem.NOT_FOUND) null else throw e
    }

    @Throws(IOException::class)
    fun size(path: String): Long = sizeOrNull(path) ?: throw SmbProblem(Problem.NOT_FOUND, "$path isn't there any more.")

    /** Opens [path] for reading from [offset]. */
    @Throws(IOException::class)
    fun open(path: String, offset: Long): InputStream {
        val first = openForRead(path)
        return object : InputStream() {
            private var file = first
            private var position = offset
            private val one = ByteArray(1)

            override fun read(): Int = if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                val n = try {
                    file.read(b, position, off, minOf(len, MAX_READ))
                } catch (e: Exception) {
                    // A short drop (Wi-Fi, the Shield's drives waking up): connect again and go on from
                    // the same place, so mpv never sees it. Only after a few tries does the error reach mpv.
                    readAgain(b, off, len, e)
                }
                if (n <= 0) return -1
                position += n
                return n
            }

            private fun readAgain(b: ByteArray, off: Int, len: Int, first: Exception): Int {
                var last: Exception = first
                for ((i, wait) in RETRY_WAITS_MS.withIndex()) {
                    io.github.patschgoo.anibox.debug.DebugLog.w(
                        "shares", "$path: reading at ${position / 1024} KB failed (${last.javaClass.simpleName}), trying again (${i + 1})",
                    )
                    try { file.close() } catch (_: Exception) {}
                    if (last !is SMBApiException) closeQuietlySynced() // the connection itself broke: a new one
                    try {
                        Thread.sleep(wait)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                    try {
                        file = openForRead(path)
                        return file.read(b, position, off, minOf(len, MAX_READ))
                    } catch (e: Exception) {
                        last = e
                        if (retired) break
                    }
                }
                throw IOException(last.message ?: "The share stopped answering.", last)
            }

            override fun close() {
                try { file.close() } catch (_: Exception) {}
            }
        }
    }

    private fun openForRead(path: String): com.hierynomus.smbj.share.File = io(path) { d ->
        d.openFile(smbPath(path), EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)
    }

    @Synchronized
    private fun closeQuietlySynced() = closeQuietly()

    /** The whole file (for small files: library.json, journals, posters), or null when it isn't there. */
    @Throws(IOException::class)
    fun readAll(path: String, limit: Long = 64L * 1024 * 1024): ByteArray? {
        val size = sizeOrNull(path) ?: return null
        if (size > limit) throw SmbProblem(Problem.OTHER, "$path is too big (${size / 1024 / 1024} MB).")
        return open(path, 0).use { it.readBytes() }
    }

    @Throws(IOException::class)
    fun readRange(path: String, offset: Long, length: Int): ByteArray = open(path, offset).use { input ->
        val buf = ByteArray(length)
        var got = 0
        while (got < length) {
            val n = input.read(buf, got, length - got)
            if (n < 0) break
            got += n
        }
        buf.copyOf(got)
    }

    @Throws(IOException::class)
    fun mkdir(path: String) = io(path) { d -> if (!d.folderExists(smbPath(path))) d.mkdir(smbPath(path)) }

    /** Writes [data] at the file's end (the file is made if missing). */
    @Throws(IOException::class)
    fun append(path: String, data: ByteArray) = io(path) { d ->
        d.openFile(
            smbPath(path),
            EnumSet.of(AccessMask.FILE_READ_ATTRIBUTES, AccessMask.FILE_WRITE_DATA, AccessMask.FILE_APPEND_DATA),
            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
            SMB2CreateDisposition.FILE_OPEN_IF,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
        ).use { f ->
            val end = f.fileInformation.standardInformation.endOfFile
            var written = 0L
            while (written < data.size) {
                written += f.write(data, end + written, written.toInt(), (data.size - written).toInt())
            }
        }
    }

    /** Writes [data] into the new file [temp], then renames it to [path] (replacing it). */
    @Throws(IOException::class)
    fun replace(path: String, temp: String, data: ByteArray) = io(path) { d ->
        var renamed = false
        try {
            d.openFile(
                smbPath(temp),
                EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.DELETE, AccessMask.FILE_READ_ATTRIBUTES),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                EnumSet.noneOf(SMB2ShareAccess::class.java),
                SMB2CreateDisposition.FILE_CREATE,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
            ).use { f ->
                var written = 0L
                while (written < data.size) written += f.write(data, written, written.toInt(), (data.size - written).toInt())
                f.rename(smbPath(path), true)
                renamed = true
            }
        } finally {
            if (!renamed) try { d.rm(smbPath(temp)) } catch (_: Exception) {} // never leave the temp file behind
        }
    }

    @Synchronized
    override fun close() = closeQuietly()

    /** Closes it and keeps it closed (it was handed out and is replaced by a new one). */
    @Synchronized
    fun retire() {
        retired = true
        closeQuietly()
    }

    private fun closeQuietly() {
        try { disk?.close() } catch (_: Exception) {}
        try { session?.close() } catch (_: Exception) {}
        try { connection?.close() } catch (_: Exception) {}
        disk = null
        session = null
        connection = null
    }

    fun mediaSource(path: String, knownSize: Long? = null): MediaSource = object : MediaSource {
        override val length: Long by lazy { knownSize ?: try { size(path) } catch (_: Exception) { -1L } }
        override fun open(offset: Long): InputStream = this@SmbConnection.open(path, offset)
    }

    companion object {
        private const val MAX_READ = 1024 * 1024
        /** Waits before each new try after a read failed: about 15 s in all, enough for drives to wake. */
        private val RETRY_WAITS_MS = longArrayOf(500, 1500, 4000, 9000)

        fun smbPath(path: String): String = path.trim('/').replace('/', '\\')

        /** Plain English for what went wrong (never the password, never the whole exception). */
        fun describe(e: Throwable, t: SmbTarget, path: String = ""): SmbProblem {
            if (e is SmbProblem) return e
            val status = (e as? SMBApiException)?.status ?: findStatus(e)
            val where = "${t.host} (${t.address})"
            val text = e.message ?: ""
            return when {
                status == NtStatus.STATUS_LOGON_FAILURE || text.contains("STATUS_LOGON_FAILURE") ->
                    SmbProblem(Problem.WRONG_SIGN_IN, "The user name or password for “${t.share}” on ${t.host} isn't right.", e)
                status == NtStatus.STATUS_BAD_NETWORK_NAME || text.contains("STATUS_BAD_NETWORK_NAME") ->
                    SmbProblem(Problem.NO_SHARE, "${t.host} has no share called “${t.share}”.", e)
                status == NtStatus.STATUS_ACCESS_DENIED || text.contains("STATUS_ACCESS_DENIED") ->
                    SmbProblem(Problem.ACCESS_DENIED, "This user may not open ${if (path.isEmpty()) "“${t.share}”" else path} on ${t.host}.", e)
                status == NtStatus.STATUS_OBJECT_NAME_NOT_FOUND || status == NtStatus.STATUS_OBJECT_PATH_NOT_FOUND ||
                    text.contains("STATUS_OBJECT_NAME_NOT_FOUND") || text.contains("STATUS_OBJECT_PATH_NOT_FOUND") ->
                    SmbProblem(Problem.NOT_FOUND, "${path.ifEmpty { "That" }} isn't on “${t.share}”.", e)
                causedBy<UnknownHostException>(e) ->
                    SmbProblem(Problem.HOST_NOT_FOUND, "Can't find ${t.host} on this network.", e)
                causedBy<ConnectException>(e) || causedBy<NoRouteToHostException>(e) || causedBy<SocketTimeoutException>(e) ->
                    SmbProblem(Problem.NO_ANSWER, "$where doesn't answer. Is the Shield on, and is this phone on the same network?", e)
                else -> SmbProblem(Problem.OTHER, "The share $where didn't work: ${text.take(160).ifEmpty { e.javaClass.simpleName }}", e)
            }
        }

        private fun findStatus(e: Throwable): NtStatus? {
            var c: Throwable? = e
            var n = 0
            while (c != null && n++ < 8) {
                if (c is SMBApiException) return c.status
                c = c.cause
            }
            return null
        }

        private inline fun <reified T : Throwable> causedBy(e: Throwable): Boolean {
            var c: Throwable? = e
            var n = 0
            while (c != null && n++ < 8) {
                if (c is T) return true
                c = c.cause
            }
            return false
        }
    }
}
