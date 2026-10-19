package com.instruments.sp404sx.storage

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.instruments.sp404sx.audio.SampleEditor
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Handles audio file importing from external storage or file providers into
 * the SP-404SX local samples directory, and inspects audio metadata.
 */
class FileImporter(private val context: Context) {

    private val tag = "FileImporter"
    private val sampleStorage = SampleStorage(context)

    companion object {
        private val SUPPORTED_MIME_TYPES = arrayOf(
            "audio/x-wav",
            "audio/wav",
            "audio/wave",
            "audio/mpeg",
            "audio/mp3",
            "audio/x-aiff",
            "audio/aiff",
            "audio/flac",
            "audio/x-m4a",
            "audio/aac",
            "audio/*"
        )
    }

    /**
     * Imports an external audio file referenced by URI into the internal samples directory.
     *
     * @param uri The content URI or file URI to import.
     * @return The absolute filesystem path to the imported file, or null if import failed.
     */
    fun importFile(uri: Uri): String? {
        val samplesDir = sampleStorage.getSamplesDir()
        val originalFileName = resolveDisplayName(uri) ?: "imported_sample_${System.currentTimeMillis()}.wav"
        val safeFileName = sanitizeFileName(originalFileName)

        val targetFile = getUniqueFile(samplesDir, safeFileName)

        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream: InputStream ->
                FileOutputStream(targetFile).use { outputStream: FileOutputStream ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                    }
                    outputStream.flush()
                }
            } ?: run {
                Log.e(tag, "Failed to open input stream for URI: $uri")
                return null
            }

            Log.i(tag, "Successfully imported audio file to ${targetFile.absolutePath}")
            return targetFile.absolutePath
        } catch (e: Exception) {
            Log.e(tag, "Error importing audio file from $uri: ${e.message}", e)
            if (targetFile.exists()) {
                targetFile.delete()
            }
            return null
        }
    }

    /**
     * Returns the array of supported MIME types for audio file picking.
     */
    fun getSupportedMimeTypes(): Array<String> {
        return SUPPORTED_MIME_TYPES
    }

    /**
     * Inspects audio file metadata to retrieve duration in ms, sample rate, and channel count.
     * Supports WAV files directly via RIFF parser, as well as MP3, AIFF, AAC, and FLAC via MediaExtractor.
     *
     * @param filePath Absolute filesystem path to the audio file.
     * @return Triple of (durationMs, sampleRate, channels), or null if analysis failed.
     */
    fun getFileInfo(filePath: String): Triple<Long, Int, Int>? {
        val file = File(filePath)
        if (!file.exists() || !file.canRead()) {
            Log.e(tag, "File does not exist or cannot be read: $filePath")
            return null
        }

        // 1. Attempt native RIFF WAV parsing first
        try {
            val wavInfo = SampleEditor.readWav(filePath)
            if (wavInfo != null) {
                val (samples, sampleRate, channels) = wavInfo
                val durationMs = if (sampleRate > 0 && channels > 0) {
                    ((samples.size / channels).toDouble() / sampleRate * 1000.0).toLong()
                } else {
                    0L
                }
                return Triple(durationMs, sampleRate, channels)
            }
        } catch (e: Exception) {
            Log.d(tag, "RIFF WAV parse skipped: ${e.message}")
        }

        // 2. Fallback to Android MediaExtractor for MP3, AIFF, AAC, FLAC
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(filePath)
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }

            if (audioTrackIndex >= 0 && audioFormat != null) {
                val sampleRate = if (audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                } else {
                    44100
                }

                val channels = if (audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else {
                    2
                }

                val durationUs = if (audioFormat.containsKey(MediaFormat.KEY_DURATION)) {
                    audioFormat.getLong(MediaFormat.KEY_DURATION)
                } else {
                    0L
                }
                val durationMs = durationUs / 1000L

                Triple(durationMs, sampleRate, channels)
            } else {
                Log.w(tag, "No audio track found in file: $filePath")
                null
            }
        } catch (e: Exception) {
            Log.e(tag, "MediaExtractor inspection failed for $filePath: ${e.message}", e)
            null
        } finally {
            try {
                extractor.release()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Resolves display name from ContentResolver query or fallback to URI path.
     */
    private fun resolveDisplayName(uri: Uri): String? {
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && cursor.moveToFirst()) {
                        val name = cursor.getString(nameIndex)
                        if (!name.isNullOrBlank()) {
                            return name
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "Could not resolve display name from ContentResolver: ${e.message}")
            }
        }
        return uri.lastPathSegment
    }

    /**
     * Replaces invalid filename characters with underscores.
     */
    private fun sanitizeFileName(fileName: String): String {
        return fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }

    /**
     * Ensures target file is unique in destination directory to prevent overwriting.
     */
    private fun getUniqueFile(dir: File, fileName: String): File {
        var file = File(dir, fileName)
        if (!file.exists()) return file

        val nameWithoutExt = file.nameWithoutExtension
        val ext = file.extension
        val dotExt = if (ext.isNotEmpty()) ".$ext" else ""

        var counter = 1
        while (file.exists()) {
            file = File(dir, "${nameWithoutExt}_$counter$dotExt")
            counter++
        }
        return file
    }
}
