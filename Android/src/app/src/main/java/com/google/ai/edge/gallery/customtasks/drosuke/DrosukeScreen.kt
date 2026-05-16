package com.google.ai.edge.gallery.customtasks.drosuke

import android.Manifest
import android.app.Activity
import android.content.Context
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
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.google.ai.edge.gallery.ui.common.LiveCameraView
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageType
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import com.google.ai.edge.gallery.ui.llmchat.LlmChatViewModel
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "DrosukeScreen"
private const val UTTERANCE_ID = "drosuke_tts"
private const val COMPACTION_THRESHOLD = 8
private suspend fun buildSystemPrompt(context: Context): String =
  withContext(Dispatchers.IO) {
    val baseDir = File(context.getExternalFilesDir(null), "drosuke_memory")
    val cal = java.util.Calendar.getInstance()
    val today = String.format(
      "%04d-%02d-%02d",
      cal.get(java.util.Calendar.YEAR),
      cal.get(java.util.Calendar.MONTH) + 1,
      cal.get(java.util.Calendar.DAY_OF_MONTH),
    )

    val soul   = File(baseDir, "SOUL.md").takeIf { it.exists() }?.readText() ?: ""
    val user   = File(baseDir, "USER.md").takeIf { it.exists() }?.readText() ?: ""
    val memory = File(baseDir, "MEMORY.md").takeIf { it.exists() }?.readText() ?: ""
    val daily  = File(baseDir, "memory/$today.md").takeIf { it.exists() }?.readText() ?: ""

    buildString {
      append(DROSUKE_SYSTEM_PROMPT)
      if (soul.isNotBlank())   append("\n\n[Character]\n$soul")
      if (user.isNotBlank())   append("\n\n[User]\n$user")
      if (memory.isNotBlank()) append("\n\n[Long-term memory]\n$memory")
      if (daily.isNotBlank())  append("\n\n[Today's conversation]\n$daily")
    }
  }

private suspend fun saveToMemory(context: Context, summary: String) {
  withContext(Dispatchers.IO) {
    val baseDir = File(context.getExternalFilesDir(null), "drosuke_memory")
    val cal = java.util.Calendar.getInstance()
    val today = String.format(
      "%04d-%02d-%02d",
      cal.get(java.util.Calendar.YEAR),
      cal.get(java.util.Calendar.MONTH) + 1,
      cal.get(java.util.Calendar.DAY_OF_MONTH),
    )

    val dailyDir = File(baseDir, "memory")
    dailyDir.mkdirs()
    File(dailyDir, "$today.md").appendText("- $summary\n")

    val memoryFile = File(baseDir, "MEMORY.md")
    val lines = if (memoryFile.exists()) memoryFile.readLines().toMutableList() else mutableListOf()
    lines.add("- [$today] $summary")
    memoryFile.writeText(lines.takeLast(30).joinToString("\n"))
  }
}

private suspend fun loadConfig(context: Context): Map<String, String> =
  withContext(Dispatchers.IO) {
    val file = File(context.getExternalFilesDir(null), "drosuke_memory/config.md")
    if (!file.exists()) return@withContext emptyMap()
    file.readLines()
      .mapNotNull { line ->
        val parts = line.split(":", limit = 2)
        if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
      }.toMap()
  }

private suspend fun searchWeb(query: String, apiKey: String): String =
  withContext(Dispatchers.IO) {
    try {
      val encoded = java.net.URLEncoder.encode(query, "UTF-8")
      val url = java.net.URL("https://api.search.brave.com/res/v1/web/search?q=$encoded&count=3")
      val conn = url.openConnection() as java.net.HttpURLConnection
      conn.setRequestProperty("Accept", "application/json")
      conn.setRequestProperty("Accept-Encoding", "gzip")
      conn.setRequestProperty("X-Subscription-Token", apiKey)
      val stream = if (conn.contentEncoding == "gzip") {
        java.util.zip.GZIPInputStream(conn.inputStream)
      } else {
        conn.inputStream
      }
      val response = stream.bufferedReader().readText()
      val results = org.json.JSONObject(response)
        .optJSONObject("web")
        ?.optJSONArray("results")
      if (results == null || results.length() == 0) return@withContext "No results found."
      buildString {
        for (i in 0 until minOf(3, results.length())) {
          val item = results.getJSONObject(i)
          val title = item.optString("title")
          val desc = item.optString("description")
          append("- $title: $desc\n")
        }
      }
    } catch (e: Exception) {
      "Search failed: ${e.message}"
    }
  }

enum class SttState { IDLE, LISTENING, PROCESSING, ERROR, OFFLINE_UNAVAILABLE }

@Composable
fun DrosukeScreen(
  task: Task,
  modelManagerViewModel: ModelManagerViewModel,
  modifier: Modifier = Modifier,
  setTopBarVisible: (Boolean) -> Unit = {},
) {
  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()
  var isSpeaking by remember { mutableStateOf(false) }
  var sttState by remember { mutableStateOf(SttState.IDLE) }
  var userSubtitleVisible by remember { mutableStateOf(true) }
  var aiSubtitleVisible by remember { mutableStateOf(false) }
  var userText by remember { mutableStateOf("") }
  var aiText by remember { mutableStateOf("") }
  var micPermissionGranted by remember {
    mutableStateOf(
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
        == PackageManager.PERMISSION_GRANTED
    )
  }
  var tts by remember { mutableStateOf<TextToSpeech?>(null) }
  var sttErrorMsg by remember { mutableStateOf("") }
  val stt = remember { AndroidSttHelper(context) }
  var cameraSelector by remember { mutableStateOf(CameraSelector.DEFAULT_BACK_CAMERA) }
  var cameraEnabled by remember { mutableStateOf(false) }
  var latestBitmap by remember { mutableStateOf<Bitmap?>(null) }
  var turnCount by remember { mutableStateOf(0) }
  var isCompacting by remember { mutableStateOf(false) }
  var hasGreeted by remember { mutableStateOf(false) }
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

  // モデル初期化（初回ダウンロード完了時）
  LaunchedEffect(modelManagerUiState.modelDownloadStatus[selectedModel.name]) {
    val status = modelManagerUiState.modelDownloadStatus[selectedModel.name]
    if (status?.status?.name == "SUCCEEDED") {
      modelManagerViewModel.initializeModel(context, task = task, model = selectedModel)
    }
  }

  // モデル初期化完了時に記憶入りシステムプロンプトを適用
  LaunchedEffect(modelManagerUiState.modelInitializationStatus[selectedModel.name]?.status) {
    val initStatus = modelManagerUiState.modelInitializationStatus[selectedModel.name]?.status
    if (initStatus?.name == "INITIALIZED") {
      coroutineScope.launch {
        val systemPrompt = buildSystemPrompt(context)
        if (systemPrompt.length > DROSUKE_SYSTEM_PROMPT.length) {
          chatViewModel.resetSession(
            task = task,
            model = selectedModel,
            systemInstruction = Contents.of(systemPrompt),
            supportImage = true,
          )
        }
      }
    }
  }

  // TTS 初期化
  val mainHandler = remember { Handler(Looper.getMainLooper()) }

  DisposableEffect(Unit) {
    var t: TextToSpeech? = null
    t = TextToSpeech(context) { status ->
      if (status == TextToSpeech.SUCCESS) {
        t?.language = Locale.US
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
      .replace(Regex("[\\p{So}\\p{Cn}\\p{Cs}]"), "")  // 絵文字を除去
      .trim()
    val params = Bundle()
    params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, UTTERANCE_ID)
    tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, params, UTTERANCE_ID)
  }

  fun compact() {
    isCompacting = true
    sttState = SttState.PROCESSING
    userText = ""
    aiText = "まとめ中..."

    val messages = chatUiState.messagesByModel[selectedModel.name] ?: emptyList()
    val historyText = messages
      .filterIsInstance<ChatMessageText>()
      .joinToString("\n") { msg ->
        val speaker = if (msg.side == ChatSide.USER) "ユーザー" else "AI"
        "$speaker: ${msg.content}"
      }

    if (historyText.isBlank()) {
      isCompacting = false
      sttState = SttState.IDLE
      return
    }

    val summaryPrompt = "以下の会話を3文以内で簡潔に要約してください。要約のみ出力し、他は何も出力しないでください。\n\n$historyText"

    chatViewModel.generateResponse(
      model = selectedModel,
      input = summaryPrompt,
      images = emptyList(),
      onError = {
        Log.e(TAG, "Compaction error: $it")
        isCompacting = false
        sttState = SttState.IDLE
        aiText = ""
      },
      onDone = {
        val summaryMsg = chatViewModel.getLastMessageWithTypeAndSide(
          model = selectedModel,
          type = ChatMessageType.TEXT,
          side = ChatSide.AGENT,
        ) as? ChatMessageText
        val summary = summaryMsg?.content?.trim() ?: ""

        coroutineScope.launch {
          if (summary.isNotBlank()) {
            saveToMemory(context, summary)
          }
          val newSystemPrompt = buildSystemPrompt(context)
          chatViewModel.resetSession(
            task = task,
            model = selectedModel,
            systemInstruction = Contents.of(newSystemPrompt),
            supportImage = true,
            onDone = {
              turnCount = 0
              isCompacting = false
              aiText = ""
              sttState = SttState.IDLE
            }
          )
        }
      },
    )
  }

  fun sendToLlm(text: String) {
    if (text.isBlank()) return
    userText = text
    sttState = SttState.PROCESSING
    val images = if (cameraEnabled) listOfNotNull(latestBitmap) else emptyList()

    chatViewModel.addMessage(
      model = selectedModel,
      message = ChatMessageText(content = text, side = ChatSide.USER),
    )
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
          val searchMatch = Regex("""\[SEARCH:\s*(.+?)\]""").find(trimmed)
          if (searchMatch != null) {
            val query = searchMatch.groupValues[1]
            aiText = "Searching..."
            coroutineScope.launch {
              val config = loadConfig(context)
              val apiKey = config["BRAVE_API_KEY"] ?: ""
              val results = if (apiKey.isNotBlank()) searchWeb(query, apiKey) else "No API key."
              val followUp = "Search results for \"$query\":\n$results\n\nPlease answer based on these results."
              sendToLlm(followUp)
            }
          } else {
            isSpeaking = true
            aiText = trimmed
            speak(trimmed)
            turnCount++
            if (turnCount >= COMPACTION_THRESHOLD) {
              compact()
              return@generateResponse
            }
            sttState = SttState.IDLE
          }
        }
      },
    )
  }

  // STT コールバック設定
  DisposableEffect(Unit) {
    stt.onResult = { text ->
      if (text.isBlank()) {
        sttState = SttState.IDLE  // 常時リッスンのLaunchedEffectが再開する
      } else {
        userText = text
        sendToLlm(text)
      }
    }
    stt.onPartialResult = { partial ->
      // ユーザーが話し始めたら TTS を即座にキャンセル（割り込み優先）
      if (partial.isNotBlank() && isSpeaking) {
        tts?.stop()
        isSpeaking = false
      }
    }
    stt.onError = { msg -> sttState = SttState.ERROR; sttErrorMsg = msg }
    onDispose { stt.destroy() }
  }

  // 常時リッスン: LLM/TTS停止後に自動で再開
  LaunchedEffect(chatUiState.inProgress, isSpeaking, micPermissionGranted, sttState) {
    if (micPermissionGranted && !chatUiState.inProgress && !isSpeaking
        && sttState == SttState.IDLE && !isCompacting) {
      sttState = SttState.LISTENING
      userText = ""
      stt.startListening()
      if (!hasGreeted) {
        hasGreeted = true
        delay(500)
        sendToLlm("Please greet the user with a brief, friendly greeting.")
      }
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
    if (cameraEnabled) {
      LiveCameraView(
        onBitmap = { bitmap, imageProxy ->
          if (cameraEnabled) latestBitmap = bitmap
          else latestBitmap = null
          imageProxy.close()
        },
        modifier = Modifier.fillMaxSize(),
        preferredSize = 1920,
        useHardwarePreview = true,
        cameraSelector = cameraSelector,
      )
    } else {
      Box(modifier = Modifier.fillMaxSize().background(Color.Black))
    }

    // 字幕オーバーレイ（画面下部左側）
    if ((userSubtitleVisible && userText.isNotBlank()) || (aiSubtitleVisible && aiText.isNotBlank())) {
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
          if (userSubtitleVisible && userText.isNotBlank()) {
            Text(
              text = "You: $userText",
              fontSize = 13.sp,
              color = Color(0xFFADD8E6),
              fontWeight = FontWeight.Normal,
              maxLines = 2,
            )
          }
          if (aiSubtitleVisible && aiText.isNotBlank()) {
            Text(
              text = "Drosuke: ${aiText.take(200)}${if (aiText.length > 200) "…" else ""}",
              fontSize = 13.sp,
              color = Color.White,
              maxLines = 4,
            )
          }
        }
      }
    }

    // CC ボタン＋カメラ切り替えボタン（左上に固定・半透明）
    Row(
      modifier = Modifier
        .align(Alignment.TopStart)
        .padding(top = 16.dp, start = 16.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      IconButton(
        onClick = { userSubtitleVisible = !userSubtitleVisible },
        modifier = Modifier
          .size(40.dp)
          .clip(CircleShape)
          .background(
            if (userSubtitleVisible) MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f)
            else Color.Gray.copy(alpha = 0.5f)
          ),
      ) {
        Text(
          text = "U",
          fontSize = 11.sp,
          fontWeight = FontWeight.Bold,
          color = Color.White,
        )
      }
      IconButton(
        onClick = { aiSubtitleVisible = !aiSubtitleVisible },
        modifier = Modifier
          .size(40.dp)
          .clip(CircleShape)
          .background(
            if (aiSubtitleVisible) MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f)
            else Color.Gray.copy(alpha = 0.5f)
          ),
      ) {
        Text(
          text = "A",
          fontSize = 11.sp,
          fontWeight = FontWeight.Bold,
          color = Color.White,
        )
      }
      IconButton(
        onClick = {
          cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
            CameraSelector.DEFAULT_FRONT_CAMERA
          } else {
            CameraSelector.DEFAULT_BACK_CAMERA
          }
        },
        enabled = cameraEnabled,
        modifier = Modifier
          .size(40.dp)
          .clip(CircleShape)
          .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f)),
      ) {
        Icon(
          imageVector = Icons.Default.Cameraswitch,
          contentDescription = "カメラ切り替え",
          tint = Color.White,
        )
      }
      IconButton(
        onClick = { cameraEnabled = !cameraEnabled },
        modifier = Modifier
          .size(40.dp)
          .clip(CircleShape)
          .background(
            if (cameraEnabled) MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f)
            else Color.Gray.copy(alpha = 0.5f)
          ),
      ) {
        Icon(
          imageVector = if (cameraEnabled) Icons.Default.VideocamOff else Icons.Default.Videocam,
          contentDescription = if (cameraEnabled) "カメラオフ" else "カメラオン",
          tint = Color.White,
        )
      }
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
    }
  }
}
