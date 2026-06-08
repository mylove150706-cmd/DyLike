package me.lingci.lib.player.exo.smb

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import jcifs.CIFSContext
import jcifs.context.SingletonContext
import jcifs.smb.SmbException
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import me.lingci.lib.base.storage.smb.SmbAuthToken
import java.io.IOException

/**
 * SMB DataSource for ExoPlayer (Media3).
 *
 * Uses [SmbRandomAccessFile] for true O(1) random-access seek instead of
 * the previous InputStream.skip() approach which was O(n) and unusable
 * for large files.
 */
@OptIn(UnstableApi::class)
class SmbDataSource(
    private val cifsContext: CIFSContext = SingletonContext.getInstance()
) : BaseDataSource(true) {

    @UnstableApi
    open class Factory(
        private val headers: Map<String, String>? = null
    ) : DataSource.Factory {

        override fun createDataSource(): DataSource {
            val credentials = SmbAuthToken.decode(headers?.get("Authorization"))
            return SmbDataSource(
                SmbAuthManager.getContext(
                    credentials?.username ?: headers?.get("username")?.trim(),
                    credentials?.password ?: headers?.get("password"),
                    credentials?.domain ?: headers?.get("domain")?.trim()
                )
            )
        }
    }

    private var randomAccessFile: SmbRandomAccessFile? = null
    private var uri: Uri? = null
    private var bytesRemaining: Long = 0
    private var opened = false

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)

        try {
            val smbFile = SmbFile(uri.toString(), cifsContext)

            // SmbRandomAccessFile constructor: connects + opens file handle
            val raf = SmbRandomAccessFile(smbFile, "r")

            // True random access seek — O(1), sends SMB2 READ with offset
            if (dataSpec.position > 0) {
                raf.seek(dataSpec.position)
            }

            randomAccessFile = raf

            val fileLength = raf.length()
            bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                dataSpec.length
            } else {
                fileLength - dataSpec.position
            }

            opened = true
            transferStarted(dataSpec)
            return bytesRemaining
        } catch (e: SmbException) {
            throw IOException("Failed to open SMB source: $uri", e)
        }
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int {
        if (readLength == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val bytesToRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            readLength
        } else {
            bytesRemaining.coerceAtMost(readLength.toLong()).toInt()
        }

        val bytesRead = try {
            randomAccessFile?.read(buffer, offset, bytesToRead) ?: -1
        } catch (e: SmbException) {
            // On read failure, close the handle to prevent leak.
            // ExoPlayer retries by creating a new DataSource via Factory.
            try { randomAccessFile?.close() } catch (_: Exception) {}
            randomAccessFile = null
            throw IOException("SMB read failed", e)
        }

        if (bytesRead == -1) return C.RESULT_END_OF_INPUT

        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            bytesRemaining -= bytesRead
        }

        bytesTransferred(bytesRead)
        return bytesRead
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            randomAccessFile?.close()
        } catch (_: SmbException) {
            // best-effort close
        } finally {
            randomAccessFile = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}
