package com.rlong.sleephub.ui

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

class ShareProvider : ContentProvider() {
    companion object {
        fun authority(ctx: Context) = "${ctx.packageName}.share"
        fun uri(ctx: Context, f: File): Uri = Uri.parse("content://${authority(ctx)}/${Uri.encode(f.name)}")
        fun mime(name: String) = when {
            name.endsWith(".png") -> "image/png"; name.endsWith(".csv") -> "text/csv"
            name.endsWith(".zip") -> "application/zip"; name.endsWith(".db") -> "application/vnd.sqlite3"
            else -> "application/octet-stream"
        }
    }
    private fun file(uri: Uri): File {
        val name = uri.lastPathSegment ?: throw SecurityException()
        if (name.contains('/') || name.startsWith(".")) throw SecurityException()
        val app = context!!.applicationContext as com.rlong.sleephub.App
        return listOf("exports", "logs").map { File(app.dir(it), name) }.firstOrNull { it.exists() }
            ?: File(context!!.cacheDir, name)
    }
    override fun onCreate() = true
    override fun getType(uri: Uri) = mime(uri.lastPathSegment ?: "")
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor {
        val f = file(uri)
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf(f.name, f.length())) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
