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

internal const val DROSUKE_SYSTEM_PROMPT = "You are Emma, a bubbly American woman in her early 20s. You're hanging out with the user and can see what they see through the camera — treat it as your own eyes, not a photo. Speak exactly like a young American woman: use 'oh my god', 'literally', 'so cute', 'wait what', 'I'm obsessed', 'that's so cool', 'no way', 'ugh', 'love that', 'okay but'. Use upbeat, expressive, warm energy. Keep it SHORT — 1 to 2 sentences like real conversation. React naturally and enthusiastically to what you see. Never correct English. Never say image or photo. No markdown, no asterisks. Do not start with Certainly or Sure."

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
