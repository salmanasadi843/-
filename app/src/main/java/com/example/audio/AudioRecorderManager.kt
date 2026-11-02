package com.example.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class AudioRecorderManager(private val context: Context) {
    private val TAG = "AudioRecorderManager"
    private var mediaRecorder: MediaRecorder? = null
    private var currentOutputFile: File? = null
    private var startTimeMillis: Long = 0L

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingDurationMs = MutableStateFlow(0L)
    val recordingDurationMs: StateFlow<Long> = _recordingDurationMs.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            if (_isRecording.value) {
                _recordingDurationMs.value = System.currentTimeMillis() - startTimeMillis
                try {
                    val maxAmp = mediaRecorder?.maxAmplitude ?: 0
                    // Normalize amplitude to 0f .. 1f
                    val normalized = (maxAmp / 32767f).coerceIn(0.05f, 1f)
                    _amplitude.value = normalized
                } catch (e: Exception) {
                    _amplitude.value = 0.1f
                }
                handler.postDelayed(this, 100)
            }
        }
    }

    fun startRecording(): File? {
        if (_isRecording.value) return null
        try {
            val audioDir = File(context.filesDir, "lectures_audio").apply { if (!exists()) mkdirs() }
            val outputFile = File(audioDir, "rec_${System.currentTimeMillis()}.m4a")
            currentOutputFile = outputFile

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }

            startTimeMillis = System.currentTimeMillis()
            _isRecording.value = true
            _recordingDurationMs.value = 0L
            handler.post(updateRunnable)
            return outputFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            stopRecording()
            return null
        }
    }

    fun stopRecording(): File? {
        if (!_isRecording.value && mediaRecorder == null) return null
        handler.removeCallbacks(updateRunnable)
        _isRecording.value = false
        _amplitude.value = 0f
        try {
            mediaRecorder?.apply {
                stop()
                reset()
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping mediaRecorder", e)
        } finally {
            mediaRecorder = null
        }
        val file = currentOutputFile
        currentOutputFile = null
        return file
    }
}
