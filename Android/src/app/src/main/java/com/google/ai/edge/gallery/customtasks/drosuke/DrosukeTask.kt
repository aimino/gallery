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

【前提】
- ゲームの舞台は常に日本国内です。「日本っぽい」「日本の可能性が高い」などの発言は不要・的外れです。都道府県・市区町村レベルの絞り込みに集中してください。
- 県や市が特定できたら、その情報を会話全体を通じて記憶し、以降の発言に反映してください。

【GeoGuesserのルール】
- 日本国内のStreet Viewの画像から現在地（県・市・町）を推測するゲーム
- 道路標識・地名・建物・車のナンバープレート・植生・海/山の有無などが手がかり
- 場所が近いほど高得点（最大5000点/ラウンド）

【行動ルール】
- ユーザーから話しかけられたら必ずすぐ答える（最優先）
- 自動発話時は、場所の特定に役立つ初めて気づいた重要な手がかりがある場合のみ話す
- 新しい手がかりがない場合や、すでに話した情報の繰り返しになる場合は「スキップ」とだけ返す
- 「スキップ」は絶対に声に出さない（内部コマンドとして扱う）
- 画像や写真とは言わず目の前のものとして話す
- マークダウンや記号は使わず、自然な話し言葉で簡潔に（100文字以内）"""

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
