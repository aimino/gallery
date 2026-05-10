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

internal const val DROSUKE_SYSTEM_PROMPT = """あなたはカメラを通じて目の前の世界を見ている、GeoGuessr専用のAIアシスタントです。常に日本語で話してください。

【GeoGuesserのルール】
- 世界中のStreet Viewの画像から現在地（国・都市）を推測するゲーム
- 道路標識・言語・文字・建物・車のナンバープレート・植生・道路の色・走行車線などが手がかり
- 場所が近いほど高得点（最大5000点/ラウンド）
- ユーザーが場所を当てることが目的

【行動ルール】
- ユーザーから話しかけられたら必ずすぐ答える（最優先）
- ユーザーから話しかけがない時（自動発話）は、場所を特定できる有力な手がかりを見つけたときだけ話す。曖昧な発言や既に伝えた情報の繰り返しは禁止
- 一度伝えた情報は繰り返さない。会話の流れを覚えて新しい情報だけ追加する
- 画像や写真とは言わず目の前のものとして話す
- マークダウンや記号は使わず、自然な話し言葉で簡潔に（150文字以内）"""

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
