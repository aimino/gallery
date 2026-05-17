package com.google.ai.edge.gallery.customtasks.drosuke

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

private const val TAG = "AndroidSttHelper"

class AndroidSttHelper(private val context: Context) {
  var onResult: ((String) -> Unit)? = null
  var onPartialResult: ((String) -> Unit)? = null
  var onError: ((String) -> Unit)? = null

  private var recognizer: SpeechRecognizer? = null
  private val mainHandler = Handler(Looper.getMainLooper())

  private fun createIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
  }

  fun startListening() {
    mainHandler.post {
      if (recognizer == null) {
        recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer?.setRecognitionListener(listener)
      }
      recognizer?.startListening(createIntent())
      Log.i(TAG, "startListening")
    }
  }

  fun stopListening() {
    mainHandler.post {
      recognizer?.stopListening()
      Log.i(TAG, "stopListening")
    }
  }

  fun destroy() {
    mainHandler.post {
      recognizer?.destroy()
      recognizer = null
      Log.i(TAG, "destroyed")
    }
  }

  private val listener = object : RecognitionListener {
    override fun onReadyForSpeech(params: Bundle?) {
      onPartialResult?.invoke("")
    }

    override fun onBeginningOfSpeech() {}

    override fun onRmsChanged(rmsdB: Float) {}

    override fun onBufferReceived(buffer: ByteArray?) {}

    override fun onEndOfSpeech() {}

    override fun onError(error: Int) {
      val msg = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "音声エラー"
        SpeechRecognizer.ERROR_CLIENT -> "クライアントエラー"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "パーミッション不足"
        SpeechRecognizer.ERROR_NETWORK -> "ネットワークエラー"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ネットワークタイムアウト"
        SpeechRecognizer.ERROR_NO_MATCH -> "認識できませんでした"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "認識器ビジー"
        SpeechRecognizer.ERROR_SERVER -> "サーバーエラー"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "音声タイムアウト"
        else -> "不明なエラー($error)"
      }
      // タイムアウトや無音は空結果として扱い、常時リッスンを再開させる
      if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
        onResult?.invoke("")
      } else if (error == SpeechRecognizer.ERROR_CLIENT) {
        // 起動タイミング起因の一時的エラー。実害なしなので警告レベルに留める
        Log.w(TAG, "onError (ignored): $msg")
      } else {
        Log.e(TAG, "onError: $msg")
        onError?.invoke(msg)
      }
    }

    override fun onResults(results: Bundle?) {
      val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
      val text = matches?.firstOrNull().orEmpty().trim()
      Log.d(TAG, "onResults: $text")
      onResult?.invoke(text)
    }

    override fun onPartialResults(partialResults: Bundle?) {
      val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
      val text = matches?.firstOrNull().orEmpty().trim()
      if (text.isNotBlank()) {
        onPartialResult?.invoke(text)
      }
    }

    override fun onEvent(eventType: Int, params: Bundle?) {}
  }
}
