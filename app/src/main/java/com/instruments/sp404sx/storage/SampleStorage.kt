package com.instruments.sp404sx.storage

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.instruments.sp404sx.R
import com.instruments.sp404sx.model.BankId
import com.instruments.sp404sx.model.Sample
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Manages sample configuration persistence and internal sample storage
 * for the SP-404SX sampler using SharedPreferences and Gson.
 */
class SampleStorage(private val context: Context) {

    private val tag = "SampleStorage"
    private val gson = Gson()
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "sp404_pad_configs"
        private const val DIR_SAMPLES = "samples"

        /**
         * Builds a consistent key for pad storage.
         * Format: "{bankId}_{padIndex}" (e.g. "A_0", "B_5").
         */
        fun buildKey(bankId: BankId, padIndex: Int): String = "${bankId.name}_$padIndex"
    }

    /**
     * Saves the configuration and metadata of a pad's sample to SharedPreferences as JSON.
     */
    fun savePadConfig(bankId: BankId, padIndex: Int, sample: Sample) {
        try {
            val key = buildKey(bankId, padIndex)
            val json = gson.toJson(sample)
            prefs.edit().putString(key, json).apply()
            Log.d(tag, "Saved pad config for $key: ${sample.name}")
        } catch (e: Exception) {
            Log.e(tag, "Failed to save pad config for ${bankId.name}_$padIndex: ${e.message}", e)
        }
    }

    /**
     * Loads the saved sample configuration for a specific bank and pad index.
     * Returns null if no configuration exists or if parsing fails.
     */
    fun loadPadConfig(bankId: BankId, padIndex: Int): Sample? {
        val key = buildKey(bankId, padIndex)
        val json = prefs.getString(key, null) ?: return null
        return try {
            gson.fromJson(json, Sample::class.java)
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse pad config JSON for $key: ${e.message}", e)
            null
        }
    }

    /**
     * Loads all saved pad configurations across all banks.
     * Returns a map with keys formatted as "bankId_padIndex" (e.g., "A_0", "C_11").
     */
    fun loadAllPadConfigs(): Map<String, Sample> {
        val configs = mutableMapOf<String, Sample>()
        val allEntries = prefs.all
        for ((key, value) in allEntries) {
            if (value is String) {
                try {
                    val sample = gson.fromJson(value, Sample::class.java)
                    if (sample != null) {
                        configs[key] = sample
                    }
                } catch (e: Exception) {
                    Log.w(tag, "Skipping invalid config entry for key $key: ${e.message}")
                }
            }
        }
        return configs
    }

    /**
     * Removes the configuration for the specified pad from persistent storage.
     */
    fun deletePadConfig(bankId: BankId, padIndex: Int) {
        val key = buildKey(bankId, padIndex)
        prefs.edit().remove(key).apply()
        Log.d(tag, "Deleted pad config for $key")
    }

    /**
     * Returns the dedicated internal storage directory for audio samples.
     * Ensures the directory exists before returning.
     */
    fun getSamplesDir(): File {
        val dir = File(context.filesDir, DIR_SAMPLES)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Copies an Android raw resource to the internal samples directory as a WAV file.
     *
     * @param resId Android raw resource ID (e.g., R.raw.kick).
     * @param fileName Target filename (e.g., "kick.wav").
     * @return Absolute filesystem path to the created file.
     */
    fun copyResourceToFile(resId: Int, fileName: String): String {
        val samplesDir = getSamplesDir()
        val destFile = File(samplesDir, fileName)

        // If file already exists and is not empty, avoid redundant copy
        if (destFile.exists() && destFile.length() > 0L) {
            return destFile.absolutePath
        }

        try {
            context.resources.openRawResource(resId).use { input: InputStream ->
                FileOutputStream(destFile).use { output: FileOutputStream ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                    }
                    output.flush()
                }
            }
            Log.i(tag, "Copied raw resource $resId to ${destFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(tag, "Failed to copy resource $resId to file $fileName: ${e.message}", e)
        }

        return destFile.absolutePath
    }

    /**
     * Returns the default factory sample mapping for Bank A.
     * Maps 0-based pad index (0..11) to its corresponding raw resource ID.
     */
    fun getDefaultSampleMapping(): Map<Int, Int> {
        return mapOf(
            0 to R.raw.kick,
            1 to R.raw.snare,
            2 to R.raw.hihat_closed,
            3 to R.raw.hihat_open,
            4 to R.raw.clap,
            5 to R.raw.rim,
            6 to R.raw.shaker,
            7 to R.raw.crash,
            8 to R.raw.tom_low,
            9 to R.raw.tom_high,
            10 to R.raw.bass_loop,
            11 to R.raw.perc
        )
    }
}
