package com.instruments.sp404sx

import android.app.Application
import android.content.Context
import android.util.Log
import com.instruments.sp404sx.model.BankId
import com.instruments.sp404sx.model.PlayMode
import com.instruments.sp404sx.model.Sample
import com.instruments.sp404sx.storage.FileImporter
import com.instruments.sp404sx.storage.SampleStorage
import java.util.UUID

/**
 * Application class for the SP-404SX sampler app.
 * Initializes default sample kits from raw resources to internal storage upon first launch.
 */
class SP404App : Application() {

    companion object {
        private const val TAG = "SP404App"
        private const val PREFS_APP = "sp404_app_prefs"
        private const val KEY_SAMPLES_INITIALIZED = "samples_initialized"

        lateinit var instance: SP404App
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        initializeDefaultSamples()
    }

    /**
     * Checks if default samples have already been initialized on the device.
     * If not, copies the factory WAV audio resources to app internal storage
     * and persists the initial pad configuration for Bank A.
     */
    private fun initializeDefaultSamples() {
        val prefs = getSharedPreferences(PREFS_APP, Context.MODE_PRIVATE)
        val isInitialized = prefs.getBoolean(KEY_SAMPLES_INITIALIZED, false)

        if (isInitialized) {
            Log.d(TAG, "Factory samples already initialized.")
            return
        }

        try {
            val sampleStorage = SampleStorage(this)
            val fileImporter = FileImporter(this)
            val defaultMapping = sampleStorage.getDefaultSampleMapping()

            for ((padIndex, resId) in defaultMapping) {
                val entryName = try {
                    resources.getResourceEntryName(resId)
                } catch (e: Exception) {
                    "sample_$padIndex"
                }

                val fileName = "$entryName.wav"
                val filePath = sampleStorage.copyResourceToFile(resId, fileName)

                // Inspect audio properties for duration, sample rate, channels
                val fileInfo = fileImporter.getFileInfo(filePath)
                val durationMs = fileInfo?.first ?: 0L
                val sampleRate = fileInfo?.second ?: 44100
                val channels = fileInfo?.third ?: 2

                // Bass loop defaults to LOOP play mode; percussion defaults to TRIGGER
                val playMode = if (entryName.contains("loop", ignoreCase = true)) {
                    PlayMode.LOOP
                } else {
                    PlayMode.TRIGGER
                }

                val sample = Sample(
                    id = UUID.randomUUID().toString(),
                    name = entryName,
                    filePath = filePath,
                    durationMs = durationMs,
                    sampleRate = sampleRate,
                    channels = channels,
                    playMode = playMode,
                    volume = 1.0f,
                    pitch = 1.0f,
                    startPoint = 0f,
                    endPoint = 1.0f,
                    isReversed = false
                )

                // Persist the default mapping to Bank A
                sampleStorage.savePadConfig(BankId.A, padIndex, sample)
            }

            prefs.edit().putBoolean(KEY_SAMPLES_INITIALIZED, true).apply()
            Log.i(TAG, "Default factory samples successfully initialized for Bank A.")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing default factory samples: ${e.message}", e)
        }
    }
}
