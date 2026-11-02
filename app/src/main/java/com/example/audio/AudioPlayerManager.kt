package com.example.audio

import android.content.Context
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class AudioPlayerManager(private val context: Context) {
    private val TAG = "AudioPlayerManager"
    private var mediaPlayer: MediaPlayer? = null
    private var currentFilePath: String? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPosition = MutableStateFlow(0)
    val currentPosition: StateFlow<Int> = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0)
    val duration: StateFlow<Int> = _duration.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val _activeFilePath = MutableStateFlow<String?>(null)
    val activeFilePath: StateFlow<String?> = _activeFilePath.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            mediaPlayer?.let { player ->
                if (_isPlaying.value) {
                    try {
                        _currentPosition.value = player.currentPosition
                        _duration.value = player.duration
                        handler.postDelayed(this, 200)
                    } catch (e: Exception) {
                        // Player might be released
                    }
                }
            }
        }
    }

    fun playAudio(path: String) {
        if (currentFilePath == path && mediaPlayer != null) {
            mediaPlayer?.start()
            applyPlaybackSpeed(_playbackSpeed.value)
            _isPlaying.value = true
            handler.post(progressRunnable)
            return
        }

        stop()
        currentFilePath = path
        _activeFilePath.value = path

        try {
            val player = MediaPlayer()
            val file = File(path)
            if (file.exists()) {
                player.setDataSource(file.absolutePath)
            } else if (path.startsWith("content://") || path.startsWith("http")) {
                player.setDataSource(context, Uri.parse(path))
            } else {
                Log.w(TAG, "Audio file does not exist: $path")
                return
            }

            player.prepare()
            _duration.value = player.duration
            _currentPosition.value = 0
            applyPlaybackSpeed(_playbackSpeed.value, player)
            player.start()
            _isPlaying.value = true

            player.setOnCompletionListener {
                _isPlaying.value = false
                _currentPosition.value = player.duration
                handler.removeCallbacks(progressRunnable)
            }

            mediaPlayer = player
            handler.removeCallbacks(progressRunnable)
            handler.post(progressRunnable)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play audio from $path", e)
            _isPlaying.value = false
        }
    }

    fun pause() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.pause()
                }
            }
            _isPlaying.value = false
            handler.removeCallbacks(progressRunnable)
        } catch (e: Exception) {
            Log.e(TAG, "Error pausing player", e)
        }
    }

    fun togglePlayPause(path: String) {
        if (_isPlaying.value && currentFilePath == path) {
            pause()
        } else {
            playAudio(path)
        }
    }

    fun seekTo(positionMs: Int) {
        try {
            mediaPlayer?.seekTo(positionMs)
            _currentPosition.value = positionMs
        } catch (e: Exception) {
            Log.e(TAG, "Error seeking player", e)
        }
    }

    fun skipForward(deltaMs: Int = 15000) {
        val newPos = (_currentPosition.value + deltaMs).coerceAtMost(_duration.value)
        seekTo(newPos)
    }

    fun skipBackward(deltaMs: Int = 15000) {
        val newPos = (_currentPosition.value - deltaMs).coerceAtLeast(0)
        seekTo(newPos)
    }

    fun setSpeed(speed: Float) {
        _playbackSpeed.value = speed
        mediaPlayer?.let { applyPlaybackSpeed(speed.coerceIn(0.5f, 2.0f), it) }
    }

    private fun applyPlaybackSpeed(speed: Float, player: MediaPlayer? = mediaPlayer) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && player != null) {
            try {
                val params = player.playbackParams ?: PlaybackParams()
                params.speed = speed
                player.playbackParams = params
            } catch (e: Exception) {
                Log.w(TAG, "Unable to set playback speed", e)
            }
        }
    }

    fun stop() {
        handler.removeCallbacks(progressRunnable)
        _isPlaying.value = false
        _currentPosition.value = 0
        try {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                reset()
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing player", e)
        } finally {
            mediaPlayer = null
            currentFilePath = null
            _activeFilePath.value = null
        }
    }
}
