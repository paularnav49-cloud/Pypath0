package com.pypath.app.ui.screens.certificate

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import java.io.File

/** Saves or shares the certificate image. Everything stays on the phone unless the learner shares it. */
object CertificateExport {

    /** Writes the PNG into cache/certificates (blocking: call off the main thread). */
    fun writeToCache(context: Context, bitmap: Bitmap, id: String): File {
        val dir = File(context.cacheDir, "certificates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // keep only the newest one
        val file = File(dir, "PyPath-certificate-$id.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    /** Opens the Android share sheet for [file] (call on the main thread). */
    fun share(context: Context, file: File, courseTitle: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, "I completed $courseTitle on PyPath.")
            clipData = ClipData.newRawUri("certificate", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Share your certificate").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(chooser)
    }

    /** Gallery saving needs no permission from Android 10 (API 29). */
    val canSaveToGallery get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** Saves into Pictures/PyPath (blocking: call off the main thread). Returns true on success. */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun saveToGallery(context: Context, bitmap: Bitmap, id: String): Boolean {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "PyPath-certificate-$id.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PyPath")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            val ok = resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: false
            if (!ok) { resolver.delete(uri, null, null); return false }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            true
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            false
        }
    }
}
