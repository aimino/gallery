package com.google.ai.edge.gallery.customtasks.drosuke

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageType
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import com.google.ai.edge.gallery.ui.common.chat.SendMessageTrigger
import com.google.ai.edge.gallery.ui.common.textandvoiceinput.HoldToDictateViewModel
import com.google.ai.edge.gallery.ui.common.textandvoiceinput.TextAndVoiceInput
import com.google.ai.edge.gallery.ui.llmchat.LlmChatScreen
import com.google.ai.edge.gallery.ui.llmchat.LlmChatViewModel
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import java.util.Locale

private const val TAG = "DrosukeScreen"
private const val UTTERANCE_ID = "drosuke_tts"

@Composable
fun DrosukeScreen(
  task: Task,
  modelManagerViewModel: ModelManagerViewModel,
  modifier: Modifier = Modifier,
  setTopBarVisible: (Boolean) -> Unit = {},
) {
  val context = LocalContext.current
  var isSpeaking by remember { mutableStateOf(false) }
  var tts by remember { mutableStateOf<TextToSpeech?>(null) }
  val chatViewModel: LlmChatViewModel = hiltViewModel()
  val holdToDictateViewModel: HoldToDictateViewModel = hiltViewModel()
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  var sendMessageTrigger by remember { mutableStateOf<SendMessageTrigger?>(null) }
  var clearTextTrigger by remember { mutableStateOf(0L) }

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
        Log.i(TAG, "TTS initialized")
      }
    }
    tts = t
    onDispose {
      t.stop()
      t.shutdown()
    }
  }

  fun speak(text: String) {
    val params = Bundle()
    params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, UTTERANCE_ID)
    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, UTTERANCE_ID)
  }

  Column(modifier = modifier.fillMaxSize()) {
    // キャラクター表示エリア
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(240.dp),
      contentAlignment = Alignment.Center,
    ) {
      DrosukeCharaView(
        isSpeaking = isSpeaking,
        modifier = Modifier.fillMaxSize(),
      )
    }

    // 音声入力バー（キャラの下）
    TextAndVoiceInput(
      task = task,
      processing = chatViewModel.uiState.collectAsState().value.inProgress,
      holdToDictateViewModel = holdToDictateViewModel,
      onDone = { text ->
        if (text.isNotBlank()) {
          val model = modelManagerUiState.selectedModel
          sendMessageTrigger = SendMessageTrigger(
            model = model,
            messages = listOf(ChatMessageText(content = text, side = ChatSide.USER)),
          )
          clearTextTrigger = System.currentTimeMillis()
        }
      },
      onAmplitudeChanged = {},
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 6.dp),
    )

    // LLMチャット（テキスト入力UIあり、音声入力は上のバーで代替）
    LlmChatScreen(
      modelManagerViewModel = modelManagerViewModel,
      navigateUp = {},
      modifier = Modifier.weight(1f),
      viewModel = chatViewModel,
      sendMessageTrigger = sendMessageTrigger,
      onGenerateResponseDone = { model: Model ->
        val lastMsg = chatViewModel.getLastMessageWithTypeAndSide(
          model = model,
          type = ChatMessageType.TEXT,
          side = ChatSide.AGENT,
        ) as? ChatMessageText
        val text = lastMsg?.content
        if (!text.isNullOrBlank()) {
          speak(text)
        }
      },
    )
  }
}
