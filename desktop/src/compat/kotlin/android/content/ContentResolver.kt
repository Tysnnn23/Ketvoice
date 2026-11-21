package android.content

import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * On the desktop every picked document is a file (`file:` URI from the file chooser), so the
 * resolver opens files and answers the two OpenableColumns the shared code asks for.
 */
class ContentResolver {
    private fun file(uri: Uri): File? =
        if (uri.scheme == "file" || uri.scheme == null) uri.path?.let(::File)?.takeIf { it.isFile } else null

    fun openInputStream(uri: Uri): InputStream? = file(uri)?.let(::FileInputStream)

    fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? {
        val f = file(uri) ?: return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = columns.map { c ->
            when (c) {
                OpenableColumns.DISPLAY_NAME -> f.name
                OpenableColumns.SIZE -> f.length()
                else -> null
            }
        }
        return Cursor.ofRow(columns, row)
    }
}
