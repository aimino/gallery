package com.google.ai.edge.gallery.customtasks.drosuke

import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File

private const val TAG = "VoskSttHelper"
private const val SAMPLE_RATE = 16000.0f

class VoskSttHelper(private val modelPath: String) {
  var onResult: ((String) -> Unit)? = null
  var onError: ((String) -> Unit)? = null
  private var model: Model? = null
  private var speechService: SpeechService? = null

  val isModelAvailable: Boolean
    get() = File(modelPath).exists()

  fun init(): Boolean {
    return try {
      model = Model(modelPath)
      Log.i(TAG, "Vosk model loaded: $modelPath")
      true
    } catch (e: Exception) {
      Log.e(TAG, "Model load failed: ${e.message}")
      onError?.invoke("モデル読み込み失敗: ${e.message}")
      false
    }
  }

  fun startListening() {
    val m = model ?: run {
      onError?.invoke("モデル未初期化")
      return
    }
    try {
      val rec = Recognizer(m, SAMPLE_RATE)
      speechService = SpeechService(rec, SAMPLE_RATE)
      speechService?.startListening(object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {}

        override fun onResult(hypothesis: String?) {
          val text = parseText(hypothesis)
          Log.d(TAG, "result: $text")
          if (text.isNotBlank()) onResult?.invoke(text)
        }

        override fun onFinalResult(hypothesis: String?) {
          val text = parseText(hypothesis)
          Log.d(TAG, "final: $text")
          if (text.isNotBlank()) onResult?.invoke(text)
        }

        override fun onError(e: Exception?) {
          Log.e(TAG, "STT error: ${e?.message}")
          onError?.invoke("認識エラー: ${e?.message}")
        }

        override fun onTimeout() {
          Log.d(TAG, "timeout")
        }
      })
      Log.i(TAG, "Vosk listening started")
    } catch (e: Exception) {
      Log.e(TAG, "startListening failed: ${e.message}")
      onError?.invoke("開始失敗: ${e.message}")
    }
  }

  fun stopListening() {
    speechService?.stop()
    speechService = null
  }

  fun destroy() {
    speechService?.shutdown()
    speechService = null
    model?.close()
    model = null
  }

  private fun parseText(hypothesis: String?): String {
    if (hypothesis.isNullOrBlank()) return ""
    return try {
      JSONObject(hypothesis).optString("text", "").trim()
    } catch (e: Exception) {
      ""
    }
  }
}
