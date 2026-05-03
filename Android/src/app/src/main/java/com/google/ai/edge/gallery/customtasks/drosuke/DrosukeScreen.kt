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
import com.google.ai.edge.gallery.ui.common.LiveCameraView
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageType
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
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
  navigateUp: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  var isSpeaking by remember { mutableStateOf(false) }
  var tts by remember { mutableStateOf<TextToSpeech?>(null) }
  val chatViewModel: LlmChatViewModel = hiltViewModel()

  // TTS 初期化
  DisposableEffect(Unit) {
    val t = TextToSpeech(context) { status ->
      if (status == TextToSpeech.SUCCESS) {
        tts?.language = Locale.JAPANESE
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
          override fun onStart(utteranceId: String?) {
            isSpeaking = true
          }
          override fun onDone(utteranceId: String?) {
            isSpeaking = false
          }
          @Deprecated("Deprecated in Java")
          override fun onError(utteranceId: String?) {
            isSpeaking = false
          }
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
    Log.d(TAG, "speak: $text")
  }

  Column(modifier = modifier.fillMaxSize()) {
    // 上部: カメラ映像 + キャラ重ね表示
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(280.dp),
    ) {
      // カメラ映像（背景）
      LiveCameraView(
        onBitmapProduced = { /* カメラ映像は表示のみ */ },
        modifier = Modifier.fillMaxSize(),
      )
      // キャラクター（右下）
      DrosukeCharaView(
        isSpeaking = isSpeaking,
        modifier = Modifier
          .height(200.dp)
          .align(Alignment.BottomEnd)
          .padding(8.dp),
      )
    }

    // 下部: LLMチャット（STT + HoldToDictate 付き）
    LlmChatScreen(
      modelManagerViewModel = modelManagerViewModel,
      navigateUp = navigateUp,
      modifier = Modifier.weight(1f),
      viewModel = chatViewModel,
      onGenerateResponseDone = { model: Model ->
        // LLM 回答完了 → 最後のAIメッセージを TTS で読み上げ
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
