package com.singlecrop.app

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.DocumentsContract
import androidx.exifinterface.media.ExifInterface
import java.io.BufferedInputStream

data class ImageItem(val uri: Uri, val name: String)

object Storage {

    private val IMAGE_EXT = setOf(
        "jpg", "jpeg", "png", "bmp", "webp", "tif", "tiff", "gif", "heic", "heif"
    )

    fun isImageName(name: String): Boolean {
        val i = name.lastIndexOf('.')
        if (i < 0) return false
        return IMAGE_EXT.contains(name.substring(i + 1).lowercase())
    }

    fun outputName(name: String): String {
        val i = name.lastIndexOf('.')
        val base = if (i > 0) name.substring(0, i) else name
        return "$base.png"
    }

    // ---------------- 枚举图片 ----------------

    fun listImages(resolver: ContentResolver, treeUri: Uri): List<ImageItem> {
        val out = ArrayList<ImageItem>()
        try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )
            resolver.query(childrenUri, projection, null, null, null)?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (c.moveToNext()) {
                    val mime = c.getString(mimeIdx) ?: ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                    val name = c.getString(nameIdx) ?: continue
                    if (!isImageName(name)) continue
                    val id = c.getString(idIdx) ?: continue
                    val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                    out.add(ImageItem(uri, name))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        out.sortWith(NaturalOrder)
        return out
    }

    // ---------------- 自然排序 ----------------

    private val NaturalOrder = Comparator<ImageItem> { a, b -> naturalCompare(a.name, b.name) }

    fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ni = i
                while (ni < a.length && a[ni].isDigit()) ni++
                var nj = j
                while (nj < b.length && b[nj].isDigit()) nj++
                val na = a.substring(i, ni).trimStart('0')
                val nb = b.substring(j, nj).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
                i = ni
                j = nj
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }

    // ---------------- 解码 ----------------

    fun decodeBitmap(ctx: Context, uri: Uri): Bitmap? {
        return try {
            val resolver = ctx.contentResolver
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = false
            }
            val bmp: Bitmap? = resolver.openInputStream(uri)?.use { ins ->
                BitmapFactory.decodeStream(ins, null, opts)
            }
            if (bmp == null) return null

            val orientation = try {
                resolver.openInputStream(uri)?.use { ins ->
                    ExifInterface(BufferedInputStream(ins)).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                } ?: ExifInterface.ORIENTATION_NORMAL
            } catch (_: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }
            applyOrientation(bmp, orientation)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun applyOrientation(bmp: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                m.postRotate(90f); m.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                m.postRotate(270f); m.postScale(-1f, 1f)
            }
            else -> return bmp
        }
        return try {
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        } catch (e: Exception) {
            bmp
        }
    }
}

/**
 * 向输出文件夹（SAF Tree Uri）写入文件，内部维护 文件名 -> Uri 索引，避免反复查询。
 */
class OutputWriter(private val ctx: Context, private val treeUri: Uri) {

    private val resolver = ctx.contentResolver
    private val treeDocId: String = DocumentsContract.getTreeDocumentId(treeUri)
    private val parentUri: Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
    private val childrenUri: Uri =
        DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)

    private var index: MutableMap<String, Uri>? = null

    @Synchronized
    private fun ensureIndex(): MutableMap<String, Uri> {
        index?.let { return it }
        val m = HashMap<String, Uri>()
        try {
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            )
            resolver.query(childrenUri, projection, null, null, null)?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (c.moveToNext()) {
                    val id = c.getString(idIdx) ?: continue
                    val name = c.getString(nameIdx) ?: continue
                    m[name] = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        index = m
        return m
    }

    @Synchronized
    fun exists(name: String): Boolean = ensureIndex().containsKey(name)

    @Synchronized
    fun write(name: String, bmp: Bitmap): Boolean {
        val m = ensureIndex()
        m[name]?.let { old ->
            try {
                resolver.delete(old, null, null)
            } catch (_: Exception) { }
            m.remove(name)
        }
        val newUri = try {
            DocumentsContract.createDocument(resolver, parentUri, "image/png", name)
        } catch (e: Exception) {
            e.printStackTrace(); null
        } ?: return false

        return try {
            resolver.openOutputStream(newUri)?.use { os ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, os)
                os.flush()
                true
            } ?: false
        } catch (e: Exception) {
            e.printStackTrace(); false
        }.also { ok ->
            if (ok) m[name] = newUri
        }
    }
}
