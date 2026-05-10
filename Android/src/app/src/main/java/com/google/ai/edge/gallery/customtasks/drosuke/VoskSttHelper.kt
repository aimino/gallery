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
  var onPartialResult: ((String) -> Unit)? = null  // 発話開始検知（割り込み用）
  var onSilence: (() -> Unit)? = null              // 無音タイムアウト通知
  var onError: ((String) -> Unit)? = null
  private var model: Model? = null
  private var speechService: SpeechService? = null
  private var resultInvoked = false

  val isModelAvailable: Boolean
    get() = File(modelPath).exists()

  fun init(): Boolean {
    if (model != null) return true  // 既にロード済み
    Log.i(TAG, "Loading model from: $modelPath (exists=${File(modelPath).exists()})")
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
    resultInvoked = false
    val m = model ?: run {
      onError?.invoke("モデル未初期化")
      return
    }
    try {
      val rec = Recognizer(m, SAMPLE_RATE)
      speechService = SpeechService(rec, SAMPLE_RATE)
      speechService?.startListening(object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
          val text = parseText(hypothesis)
          if (text.isNotBlank()) {
            onPartialResult?.invoke(text)
          }
        }

        override fun onResult(hypothesis: String?) {
          val text = parseText(hypothesis)
          Log.d(TAG, "result: $text")
          if (text.isNotBlank() && !resultInvoked) {
            resultInvoked = true
            stopListening()
            onResult?.invoke(text)
          }
        }

        override fun onFinalResult(hypothesis: String?) {
          val text = parseText(hypothesis)
          Log.d(TAG, "final: $text")
          if (!resultInvoked) {
            resultInvoked = true
            stopListening()
            onResult?.invoke(text)
          }
        }

        override fun onError(e: Exception?) {
          Log.e(TAG, "STT error: ${e?.message}")
          onError?.invoke("認識エラー: ${e?.message}")
        }

        override fun onTimeout() {
          Log.d(TAG, "timeout")
          stopListening()
          onResult?.invoke("")
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
