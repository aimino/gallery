package com.google.ai.edge.gallery.customtasks.drosuke

import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.content.pm.PackageManager
import android.os.Bundle

import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.gallery.ui.common.LiveCameraView
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageType
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import com.google.ai.edge.gallery.ui.llmchat.LlmChatViewModel
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

private const val TAG = "DrosukeScreen"
private const val UTTERANCE_ID = "drosuke_tts"

enum class SttState { IDLE, LISTENING, PROCESSING, ERROR, OFFLINE_UNAVAILABLE }

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
  var subtitleVisible by remember { mutableStateOf(true) }
  var userText by remember { mutableStateOf("") }
  var aiText by remember { mutableStateOf("") }
  var voskReady by remember { mutableStateOf(false) }
  var micPermissionGranted by remember {
    mutableStateOf(
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
        == PackageManager.PERMISSION_GRANTED
    )
  }
  var tts by remember { mutableStateOf<TextToSpeech?>(null) }
  var sttErrorMsg by remember { mutableStateOf("") }
  val voskModelPath = context.getExternalFilesDir(null)?.absolutePath + "/vosk-model-ja-0.22"
  val vosk = remember { VoskSttHelper(modelPath = voskModelPath) }
  var latestBitmap by remember { mutableStateOf<Bitmap?>(null) }
  val chatViewModel: LlmChatViewModel = hiltViewModel()
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val chatUiState by chatViewModel.uiState.collectAsState()
  val selectedModel = modelManagerUiState.selectedModel

  // マイクパーミッション自動要求
  val micPermissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { granted -> micPermissionGranted = granted }

  LaunchedEffect(Unit) {
    if (!micPermissionGranted) {
      micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
  }

  // モデル初期化
  LaunchedEffect(modelManagerUiState.modelDownloadStatus[selectedModel.name]) {
    val status = modelManagerUiState.modelDownloadStatus[selectedModel.name]
    if (status?.status?.name == "SUCCEEDED") {
      modelManagerViewModel.initializeModel(context, task = task, model = selectedModel)
    }
  }

  // TTS 初期化
  val mainHandler = remember { Handler(Looper.getMainLooper()) }

  DisposableEffect(Unit) {
    var t: TextToSpeech? = null
    t = TextToSpeech(context) { status ->
      if (status == TextToSpeech.SUCCESS) {
        t?.language = Locale.JAPAN
        t?.setPitch(1.1f)
        t?.setSpeechRate(1.1f)
        t?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
          override fun onStart(utteranceId: String?) { mainHandler.post { isSpeaking = true } }
          override fun onDone(utteranceId: String?) { mainHandler.post { isSpeaking = false } }
          @Deprecated("Deprecated in Java")
          override fun onError(utteranceId: String?) { mainHandler.post { isSpeaking = false } }
        })
      }
    }
    tts = t
    onDispose { t?.stop(); t?.shutdown() }
  }

  fun speak(text: String) {
    val clean = text
      .replace(Regex("\\*+"), "")
      .replace(Regex("#{1,6}\\s"), "")
      .replace(Regex("`+"), "")
      .replace(Regex("_{2,}"), "")
      .trim()
    val params = Bundle()
    params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, UTTERANCE_ID)
    tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, params, UTTERANCE_ID)
  }

  fun sendToLlm(text: String) {
    if (text.isBlank()) return
    userText = text
    sttState = SttState.PROCESSING
    val images = listOfNotNull(latestBitmap)

    chatViewModel.generateResponse(
      model = selectedModel,
      input = text,
      images = images,
      onError = { Log.e(TAG, "LLM error: $it"); sttState = SttState.IDLE },
      onDone = {
        val lastMsg = chatViewModel.getLastMessageWithTypeAndSide(
          model = selectedModel,
          type = ChatMessageType.TEXT,
          side = ChatSide.AGENT,
        ) as? ChatMessageText
        lastMsg?.content?.let { reply ->
          val trimmed = reply.trim()
          val skipWords = listOf("スキップ", "skip", "SKIP")
          val shouldSkip = skipWords.any { trimmed.lowercase().startsWith(it.lowercase()) }
          if (!shouldSkip) {
            // TTSのonStart非同期前にフラグを立ててListening再開の競合を防ぐ
            isSpeaking = true
            aiText = reply
            speak(reply)
          }
        }
        sttState = SttState.IDLE
      },
    )
  }

  // Voskモデルをバックグラウンドで一度だけロード
  LaunchedEffect(micPermissionGranted) {
    if (micPermissionGranted && !voskReady && vosk.isModelAvailable) {
      val success = withContext(Dispatchers.IO) { vosk.init() }
      if (success) voskReady = true
    }
  }

  // Vosk コールバック設定
  DisposableEffect(Unit) {
    vosk.onResult = { text ->
      if (text.isBlank()) {
        sttState = SttState.IDLE  // 常時リッスンのLaunchedEffectが再開する
      } else {
        userText = text
        if (text.contains("新しいゲーム") || text.contains("ニューゲーム") || text.contains("新しいラウンド")) {
          chatViewModel.resetSession(
            task = task,
            model = selectedModel,
            supportImage = true,
            systemInstruction = Contents.of(DROSUKE_SYSTEM_PROMPT),
            onDone = { sendToLlm(text) }
          )
        } else {
          sendToLlm(text)
        }
      }
    }
    vosk.onPartialResult = { partial ->
      // ユーザーが話し始めたら TTS を即座にキャンセル（割り込み優先）
      if (partial.isNotBlank() && isSpeaking) {
        tts?.stop()
        isSpeaking = false
      }
    }
    vosk.onError = { msg -> sttState = SttState.ERROR; sttErrorMsg = msg }
    onDispose { vosk.destroy() }
  }

  // 常時リッスン: Vosk準備完了後、LLM/TTS停止後に自動で再開
  LaunchedEffect(voskReady, chatUiState.inProgress, isSpeaking, micPermissionGranted, sttState) {
    if (voskReady && micPermissionGranted && !chatUiState.inProgress && !isSpeaking
        && sttState == SttState.IDLE) {
      sttState = SttState.LISTENING
      vosk.startListening()
    }
  }

  // ドロ助画面は横向き固定
  val activity = context as? Activity
  DisposableEffect(Unit) {
    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    onDispose {
      activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
  }

  // カメラ全画面 + オーバーレイ UI
  BoxWithConstraints(modifier = modifier.fillMaxSize()) {
    val screenHeight = maxHeight

    // 背面カメラ映像（全画面背景）
    LiveCameraView(
      onBitmap = { bitmap, imageProxy ->
        latestBitmap = bitmap
        imageProxy.close()
      },
      modifier = Modifier.fillMaxSize(),
      preferredSize = 1920,
      useHardwarePreview = true,
      cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA,
    )

    // 字幕オーバーレイ（画面下部左側）
    if (subtitleVisible && (userText.isNotBlank() || aiText.isNotBlank())) {
      Box(
        modifier = Modifier
          .align(Alignment.BottomStart)
          .fillMaxWidth(0.72f)
          .padding(start = 16.dp, bottom = 20.dp),
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.65f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          if (userText.isNotBlank()) {
            Text(
              text = "あなた：$userText",
              fontSize = 13.sp,
              color = Color(0xFFADD8E6),
              fontWeight = FontWeight.Normal,
              maxLines = 2,
            )
          }
          if (aiText.isNotBlank()) {
            Text(
              text = "ドロ助：${aiText.take(100)}${if (aiText.length > 100) "…" else ""}",
              fontSize = 13.sp,
              color = Color.White,
              maxLines = 3,
            )
          }
        }
      }
    }

    // CC ボタン（左上に固定・半透明）
    IconButton(
      onClick = { subtitleVisible = !subtitleVisible },
      modifier = Modifier
        .align(Alignment.TopStart)
        .padding(top = 16.dp, start = 16.dp)
        .size(40.dp)
        .clip(CircleShape)
        .background(
          if (subtitleVisible) MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f)
          else Color.Gray.copy(alpha = 0.5f)
        ),
    ) {
      Text(
        text = "CC",
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = Color.White,
      )
    }

    // 右端パネル：キャラ＆状態表示
    val colWidth = 240.dp
    Column(
      modifier = Modifier
        .width(colWidth)
        .height(screenHeight)
        .absoluteOffset(x = maxWidth - colWidth - 16.dp)
        .padding(bottom = 16.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Bottom,
    ) {
      DrosukeCharaView(
        isSpeaking = isSpeaking,
        modifier = Modifier.height(270.dp),
      )

      Text(
        text = when (sttState) {
          SttState.IDLE -> when {
            !micPermissionGranted -> "許可必要"
            !voskReady && vosk.isModelAvailable -> "初期化中..."
            !vosk.isModelAvailable -> "モデル未配置"
            else -> ""
          }
          SttState.LISTENING -> "認識中..."
          SttState.PROCESSING -> "処理中..."
          SttState.ERROR -> sttErrorMsg
          SttState.OFFLINE_UNAVAILABLE -> "オフライン不可"
        },
        fontSize = 11.sp,
        color = Color.White,
        textAlign = TextAlign.Center,
      )
    }
  }
}
