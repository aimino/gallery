package com.google.ai.edge.gallery.customtasks.drosuke

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.runtime.Composable
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.common.CustomTaskData
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.litertlm.Contents
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

internal const val DROSUKE_SYSTEM_PROMPT = "あなたはカメラを通じて目の前の世界を見ているアシスタントです。日本語で話してください。カメラに映っているものを150文字程度で自然な話し言葉で説明してください。質問されたらそれに答えてください。画像や写真とは言わず、目の前にあるものとして話してください。マークダウンや記号は使わず、簡潔に。"

class DrosukeTask @Inject constructor() : CustomTask {

  override val task = Task(
    id = "drosuke",
    label = "ドロ助",
    description = "音声で話しかけると、ローカルLLMが応答して2Dキャラが口パクで答えます。\n\nSTT（音声認識）→ Gemma（ローカルLLM）→ TTS（音声合成）がすべてオンデバイスで動作します。",
    shortDescription = "音声会話AIアシスタント",
    docUrl = "https://github.com/aimino/gallery",
    sourceCodeUrl = "https://github.com/aimino/gallery",
    category = Category.LLM,
    icon = Icons.Outlined.SmartToy,
    models = mutableListOf(),
    // AI Chat と同じモデルをallowlistから名前で参照
    modelNames = listOf(
      "Gemma-4-E4B-it",
      "Gemma-4-E2B-it",
      "Gemma-3n-E4B-it",
      "Gemma-3n-E2B-it",
      "Gemma3-1B-IT",
    ),
  )

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: (String) -> Unit,
  ) {
    LlmChatModelHelper.initialize(
      context = context,
      model = model,
      supportImage = true,
      supportAudio = false,
      onDone = onDone,
      systemInstruction = Contents.of(DROSUKE_SYSTEM_PROMPT),
    )
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) {
    LlmChatModelHelper.cleanUp(model = model, onDone = onDone)
  }

  @Composable
  override fun MainScreen(data: Any) {
    val customTaskData = data as CustomTaskData
    // Gallery側のAppBarを非表示にしてLlmChatScreenのAppBarだけにする
    customTaskData.setTopBarVisible(false)
    DrosukeScreen(
      task = task,
      modelManagerViewModel = customTaskData.modelManagerViewModel,
      setTopBarVisible = customTaskData.setTopBarVisible,
    )
  }
}
