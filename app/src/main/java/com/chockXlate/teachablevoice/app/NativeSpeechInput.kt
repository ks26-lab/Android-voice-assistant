package com.chockXlate.teachablevoice.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Push-to-talk using the installed Android recognizer; no application backend or API key. */
internal class NativeSpeechInput(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onTranscript: (String) -> Unit
) : RecognitionListener {
    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    fun toggle() {
        if (listening) {
            recognizer?.stopListening()
            onStatus("Finishing speech recognition…")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onStatus("No Android speech recognition service is installed. Use typed input.")
            return
        }
        try {
            val service = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
                it.setRecognitionListener(this)
                recognizer = it
            }
            listening = true
            onStatus("Listening… Tap MIC / STOP to finish.")
            service.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            })
        } catch (_: Exception) {
            listening = false
            onStatus("Speech recognition could not start. Use typed input or try again.")
        }
    }

    fun close() { recognizer?.destroy(); recognizer = null; listening = false }
    override fun onResults(results: Bundle?) {
        listening = false
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        onStatus(if (text.isNullOrBlank()) "No speech recognized. Try again." else "Speech recognized.")
        if (!text.isNullOrBlank()) onTranscript(text)
    }
    override fun onError(error: Int) {
        listening = false
        onStatus("Speech recognition stopped (Android error $error). Retry or use typed input.")
    }
    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() { onStatus("Recognizing speech…") }
    override fun onPartialResults(partialResults: Bundle?) = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit
}
