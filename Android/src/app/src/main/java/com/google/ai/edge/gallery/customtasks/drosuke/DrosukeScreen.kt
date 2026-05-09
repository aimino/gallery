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
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.width
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
  var lastReply by remember { mutableStateOf("") }
  // 移動検知用
  var lastSceneBitmap by remember { mutableStateOf<Bitmap?>(null) }
  var lastAutoSpeakTime by remember { mutableStateOf(0L) }
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
      lastReply = ""  // モデル切り替え時は文脈をリセット
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
    val input = if (isAuto) "今見えている景色や場所について気づいたことを自由に話して" else text
    if (input.isBlank()) return
    sttState = SttState.PROCESSING
    val images = listOfNotNull(capturedBitmap ?: latestBitmap)

    // 直前の返答を添付して文脈を渡す（毎回リセットでエコーバグを回避）
    val inputWithContext = if (lastReply.isNotBlank()) {
      "[Your previous reply: \"$lastReply\"] $input"
    } else {
      input
    }

    chatViewModel.resetSession(
      task = task,
      model = selectedModel,
      supportImage = true,
      systemInstruction = Contents.of(DROSUKE_SYSTEM_PROMPT),
      onDone = {
        chatViewModel.generateResponse(
          model = selectedModel,
          input = inputWithContext,
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
              lastReply = reply
              speak(reply)
            }
          },
        )
      },
    )
  }

  fun startStt() {
    sttErrorMsg = ""
    if (!vosk.isModelAvailable) {
      sttState = SttState.ERROR
      sttErrorMsg = "モデル未配置"
      return
    }
    if (vosk.init()) {
      capturedBitmap = latestBitmap  // 話しかけた瞬間の映像を固定
      sttState = SttState.LISTENING
      vosk.startListening()
    }
  }

  DisposableEffect(Unit) {
    vosk.onResult = { text -> sendToLlm(text) }
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

  // カメラ全画面 + 右端にキャラ＆マイクを重ねる
  BoxWithConstraints(modifier = modifier.fillMaxSize()) {
    val screenHeight = maxHeight
    // 背面カメラ映像（全画面背景）
    LiveCameraView(
      onBitmap = { bitmap, imageProxy ->
        latestBitmap = bitmap
        // 移動検知：シーン差分が閉値を超えたら自動発話
        val now = System.currentTimeMillis()
        val prev = lastSceneBitmap
        if (prev != null
            && now - lastAutoSpeakTime > 15_000L
            && !chatUiState.inProgress
            && !isSpeaking
            && sttState == SttState.IDLE
        ) {
          val diff = computeBitmapDiff(prev, bitmap)
          if (diff > 40f) {
            lastAutoSpeakTime = now
            lastSceneBitmap = bitmap
            capturedBitmap = bitmap
            sendToLlm("", isAuto = true)
          }
        }
        if (prev == null) lastSceneBitmap = bitmap
        imageProxy.close()
      },
      modifier = Modifier.fillMaxSize(),
      preferredSize = 1920,
      useHardwarePreview = true,
      cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA,
    )

    // absoluteOffset で右端に直接配置
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
          SttState.IDLE -> if (!micPermissionGranted) "許可必要" else ""
          SttState.LISTENING -> "認識中..."
          SttState.PROCESSING -> "処理中..."
          SttState.ERROR -> sttErrorMsg
          SttState.OFFLINE_UNAVAILABLE -> "オフライン不可"
        },
        fontSize = 11.sp,
        color = Color.White,
        textAlign = TextAlign.Center,
      )

      // マイクボタン（小さめ）
      IconButton(
        onClick = {
          when {
            !micPermissionGranted ->
              micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            (sttState == SttState.IDLE || sttState == SttState.ERROR || sttState == SttState.OFFLINE_UNAVAILABLE) && !chatUiState.inProgress && !isSpeaking ->
              startStt()
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

/** 2枚のBitmapを 32x32 に縮小して画素差分の平均を返す（0〜255） */
private fun computeBitmapDiff(a: Bitmap, b: Bitmap): Float {
  val size = 32
  val ra = Bitmap.createScaledBitmap(a, size, size, false)
  val rb = Bitmap.createScaledBitmap(b, size, size, false)
  var diff = 0L
  for (x in 0 until size) {
    for (y in 0 until size) {
      val ca = ra.getPixel(x, y)
      val cb = rb.getPixel(x, y)
      diff += kotlin.math.abs(AndroidColor.red(ca) - AndroidColor.red(cb))
      diff += kotlin.math.abs(AndroidColor.green(ca) - AndroidColor.green(cb))
      diff += kotlin.math.abs(AndroidColor.blue(ca) - AndroidColor.blue(cb))
    }
  }
  return diff.toFloat() / (size * size * 3)
}
