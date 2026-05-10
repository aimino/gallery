package com.google.ai.edge.gallery.customtasks.drosuke

import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
  // アプリ専用外部ストレージ（パーミッション不要）
  val voskModelPath = context.getExternalFilesDir(null)?.absolutePath + "/vosk-model-small-ja-0.22"
  val vosk = remember { VoskSttHelper(modelPath = voskModelPath) }
  var latestBitmap by remember { mutableStateOf<Bitmap?>(null) }
  var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }
  var lastSceneBitmap by remember { mutableStateOf<Bitmap?>(null) }
  var lastAutoSpeakTime by remember { mutableStateOf(0L) }
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

  fun sendToLlm(text: String, isAuto: Boolean = false) {
    val input = if (isAuto) "GeoGuesserで場所を特定するために、今初めて気づいた重要な手がかりがあれば教えて。すでに話した内容や曖昧な情報は不要。新しい手がかりがなければ「スキップ」とだけ返して。" else text
    if (input.isBlank()) return
    if (!isAuto) userText = text
    sttState = SttState.PROCESSING
    val images = listOfNotNull(capturedBitmap ?: latestBitmap)

    chatViewModel.resetSession(task, selectedModel, supportImage = true, systemInstruction = Contents.of(DROSUKE_SYSTEM_PROMPT), onDone = {
      chatViewModel.generateResponse(
        model = selectedModel,
        input = input,
        images = images,
        onError = { Log.e(TAG, "LLM error: $it"); sttState = SttState.IDLE },
        onDone = {
          sttState = SttState.IDLE
          val lastMsg = chatViewModel.getLastMessageWithTypeAndSide(
            model = selectedModel,
            type = ChatMessageType.TEXT,
            side = ChatSide.AGENT,
          ) as? ChatMessageText
          lastMsg?.content?.let { reply ->
            val trimmed = reply.trim()
            if (!trimmed.startsWith("スキップ") && trimmed != "スキップ") {
              aiText = reply
              speak(reply)
            }
          }
        },
      )
    })
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
        sttState = SttState.IDLE
      } else {
        userText = text
        sendToLlm(text)
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
        val prev = lastSceneBitmap
        val now = System.currentTimeMillis()
        if (prev == null) {
          lastSceneBitmap = bitmap
        } else if (now - lastAutoSpeakTime > 30_000L
          && !chatUiState.inProgress && !isSpeaking && sttState == SttState.IDLE) {
          val diff = computeBitmapDiff(prev, bitmap)
          if (diff > 50f) {
            lastAutoSpeakTime = now
            lastSceneBitmap = bitmap
            capturedBitmap = bitmap
            sendToLlm("", isAuto = true)
          }
        }
        imageProxy.close()
      },
      modifier = Modifier.fillMaxSize(),
      preferredSize = 1920,
      useHardwarePreview = true,
      cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA,
    )

    // 字幕オーバーレイ（画面下部中央）
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
              color = Color(0xFFADD8E6),  // ライトブルー
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

    // 右端パネル：キャラ＆ボタン
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
      // キャラクター
      DrosukeCharaView(
        isSpeaking = isSpeaking,
        modifier = Modifier.height(270.dp),
      )

      // 状態ラベル
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

      // マイクボタン（手動）
      Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        // マイクボタン（手動でも押せる）
        IconButton(
          onClick = {
            when {
              !micPermissionGranted ->
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
              voskReady && (sttState == SttState.IDLE || sttState == SttState.ERROR)
                  && !chatUiState.inProgress && !isSpeaking -> {
                capturedBitmap = latestBitmap
                sttState = SttState.LISTENING
                vosk.startListening()
              }
            }
          },
          modifier = Modifier
            .size(48.dp)
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
            modifier = Modifier.size(24.dp),
          )
        }
      }
    }
  }
}

private fun computeBitmapDiff(a: Bitmap, b: Bitmap): Float {
  val size = 32
  val sa = Bitmap.createScaledBitmap(a, size, size, false)
  val sb = Bitmap.createScaledBitmap(b, size, size, false)
  var diff = 0f
  for (y in 0 until size) {
    for (x in 0 until size) {
      val pa = sa.getPixel(x, y)
      val pb = sb.getPixel(x, y)
      diff += kotlin.math.abs(AndroidColor.red(pa) - AndroidColor.red(pb))
      diff += kotlin.math.abs(AndroidColor.green(pa) - AndroidColor.green(pb))
      diff += kotlin.math.abs(AndroidColor.blue(pa) - AndroidColor.blue(pb))
    }
  }
  return diff / (size * size * 3)
}
