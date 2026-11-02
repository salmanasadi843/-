package com.example.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

class SpeechRecognizerHelper(private val context: Context) {
    private val TAG = "SpeechRecognizerHelper"
    private var speechRecognizer: SpeechRecognizer? = null

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _liveSpokenText = MutableStateFlow("")
    val liveSpokenText: StateFlow<String> = _liveSpokenText.asStateFlow()

    private val _recognizedSegments = MutableStateFlow<List<String>>(emptyList())
    val recognizedSegments: StateFlow<List<String>> = _recognizedSegments.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun isAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }

    fun startListening(languageCode: String = "fa-IR") {
        if (!isAvailable()) {
            _errorMessage.value = "سرویس تشخیص گفتار بر روی این دستگاه در دسترس نیست"
            return
        }

        stopListening()
        _errorMessage.value = null

        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        _isListening.value = true
                    }

                    override fun onBeginningOfSpeech() {}

                    override fun onRmsChanged(rmsdB: Float) {}

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        _isListening.value = false
                    }

                    override fun onError(error: Int) {
                        _isListening.value = false
                        val errorText = when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> "خطای ضبط صدا"
                            SpeechRecognizer.ERROR_CLIENT -> "خطای کلاینت"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "عدم دسترسی به میکروفون"
                            SpeechRecognizer.ERROR_NETWORK -> "خطای اتصال اینترنت برای تبدیل صوت"
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "اتمام مهلت شبکه"
                            SpeechRecognizer.ERROR_NO_MATCH -> "صدایی با وضوح کافی تشخیص داده نشد"
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "سرویس تشخیص گفتار مشغول است"
                            SpeechRecognizer.ERROR_SERVER -> "خطای سرور گوگل"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "صدایی دریافت نشد"
                            else -> "خطای ناشناخته در تبدیل صوت به متن"
                        }
                        Log.w(TAG, "Speech recognition error: $errorText ($error)")
                        _errorMessage.value = errorText
                    }

                    override fun onResults(results: Bundle?) {
                        _isListening.value = false
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            val recognized = matches[0]
                            _liveSpokenText.value = ""
                            _recognizedSegments.value = _recognizedSegments.value + recognized
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            _liveSpokenText.value = matches[0]
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageCode)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageCode)
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, languageCode)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }

            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start speech recognizer", e)
            _isListening.value = false
            _errorMessage.value = e.localizedMessage
        }
    }

    fun stopListening() {
        _isListening.value = false
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recognizer", e)
        } finally {
            speechRecognizer = null
        }
    }

    fun clearSegments() {
        _recognizedSegments.value = emptyList()
        _liveSpokenText.value = ""
        _errorMessage.value = null
    }
}
