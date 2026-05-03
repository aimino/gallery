package com.google.ai.edge.gallery.customtasks.drosuke

import android.Manifest
import android.graphics.Bitmap
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.common.LiveCameraView
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageType
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import com.google.ai.edge.gallery.ui.llmchat.LlmChatViewModel
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import java.util.Locale

private const val TAG = "DrosukeScreen"
private const val UTTERANCE_ID = "drosuke_tts"

enum class SttState { IDLE, LISTENING, PROCESSING }

@Composable
fun DrosukeScreen(
  task: Task,
  modelManagerViewModel: ModelManagerViewModel,
  modifier: Modifier = Modifier,
  setTopBarVisible: (Boolean) -> Unit = {},
) {
  val context = LocalContext.current
  var isSpeaking by remember { mutableStateOf(false) }
  var sttState by remember { mutableStateOf(SttState.IDLE) }
  var micPermissionGranted by remember {
    mutableStateOf(
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
        == PackageManager.PERMISSION_GRANTED
    )
  }
  var tts by remember { mutableStateOf<TextToSpeech?>(null) }
  var stt by remember { mutableStateOf<SpeechRecognizer?>(null) }
  var latestBitmap by remember { mutableStateOf<Bitmap?>(null) }
  val chatViewModel: LlmChatViewModel = hiltViewModel()
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val chatUiState by chatViewModel.uiState.collectAsState()
  val selectedModel = modelManagerUiState.selectedModel

  // マイクパーミッションランチャー
  val micPermissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { granted -> micPermissionGranted = granted }

  // モデル初期化
  LaunchedEffect(modelManagerUiState.modelDownloadStatus[selectedModel.name]) {
    val status = modelManagerUiState.modelDownloadStatus[selectedModel.name]
    if (status?.status?.name == "SUCCEEDED") {
      modelManagerViewModel.initializeModel(context, task = task, model = selectedModel)
    }
  }

  // TTS 初期化
  DisposableEffect(Unit) {
    val t = TextToSpeech(context) { status ->
      if (status == TextToSpeech.SUCCESS) {
        tts?.language = Locale.JAPANESE
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
          override fun onStart(utteranceId: String?) { isSpeaking = true }
          override fun onDone(utteranceId: String?) { isSpeaking = false }
          @Deprecated("Deprecated in Java")
          override fun onError(utteranceId: String?) { isSpeaking = false }
        })
      }
    }
    tts = t
    onDispose { t.stop(); t.shutdown() }
  }

  fun speak(text: String) {
    val params = Bundle()
    params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, UTTERANCE_ID)
    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, UTTERANCE_ID)
  }

  fun sendToLlm(text: String) {
    if (text.isBlank()) return
    sttState = SttState.PROCESSING
    val images = listOfNotNull(latestBitmap)
    chatViewModel.generateResponse(
      model = selectedModel,
      input = text,
      images = images,
      onError = { Log.e(TAG, "LLM error: $it"); sttState = SttState.IDLE },
      onDone = {
        sttState = SttState.IDLE
        val lastMsg = chatViewModel.getLastMessageWithTypeAndSide(
          model = selectedModel,
          type = ChatMessageType.TEXT,
          side = ChatSide.AGENT,
        ) as? ChatMessageText
        lastMsg?.content?.let { speak(it) }
      },
    )
  }

  fun startStt() {
    stt?.destroy()
    val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
    stt = recognizer
    recognizer.setRecognitionListener(object : RecognitionListener {
      override fun onReadyForSpeech(params: Bundle?) { sttState = SttState.LISTENING }
      override fun onBeginningOfSpeech() {}
      override fun onRmsChanged(rmsdB: Float) {}
      override fun onBufferReceived(buffer: ByteArray?) {}
      override fun onEndOfSpeech() { sttState = SttState.PROCESSING }
      override fun onError(error: Int) { Log.w(TAG, "STT error: $error"); sttState = SttState.IDLE }
      override fun onResults(results: Bundle?) {
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
        sendToLlm(text)
      }
      override fun onPartialResults(partialResults: Bundle?) {}
      override fun onEvent(eventType: Int, params: Bundle?) {}
    })
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
      putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
      putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
      putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }
    recognizer.startListening(intent)
  }

  DisposableEffect(Unit) { onDispose { stt?.destroy() } }

  Column(modifier = modifier.fillMaxSize()) {
    // 上部: カメラ映像 + キャラ重ね表示
    Box(
      modifier = Modifier.fillMaxWidth().weight(1f),
    ) {
      // 背面カメラ映像（背景）
      // imageProxy.close() を必ず呼ぶことでフレームが継続して流れる
      LiveCameraView(
        onBitmap = { bitmap, imageProxy ->
          latestBitmap = bitmap
          imageProxy.close()
        },
        modifier = Modifier.fillMaxSize(),
        cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA,
      )
      // キャラクター（右下）
      DrosukeCharaView(
        isSpeaking = isSpeaking,
        modifier = Modifier
          .height(220.dp)
          .align(Alignment.BottomEnd)
          .padding(8.dp),
      )
    }

    // テキスト表示なし（音声のみ）

    // マイクボタンエリア
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 12.dp),
      contentAlignment = Alignment.Center,
    ) {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
          text = when (sttState) {
            SttState.IDLE -> if (!micPermissionGranted) "マイクの許可が必要です" else ""
            SttState.LISTENING -> "認識中..."
            SttState.PROCESSING -> "処理中..."
          },
          fontSize = 13.sp,
          color = MaterialTheme.colorScheme.primary,
          textAlign = TextAlign.Center,
          modifier = Modifier.height(20.dp),
        )
        Spacer(Modifier.height(8.dp))
        IconButton(
          onClick = {
            when {
              !micPermissionGranted ->
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
              sttState == SttState.IDLE && !chatUiState.inProgress ->
                startStt()
            }
          },
          modifier = Modifier
            .size(68.dp)
            .clip(CircleShape)
            .background(
              when {
                sttState == SttState.LISTENING -> MaterialTheme.colorScheme.error
                chatUiState.inProgress || sttState == SttState.PROCESSING ->
                  MaterialTheme.colorScheme.surfaceVariant
                else -> MaterialTheme.colorScheme.primary
              }
            ),
        ) {
          Icon(
            Icons.Rounded.Mic,
            contentDescription = "マイク",
            tint = Color.White,
            modifier = Modifier.size(34.dp),
          )
        }
      }
    }
  }
}
