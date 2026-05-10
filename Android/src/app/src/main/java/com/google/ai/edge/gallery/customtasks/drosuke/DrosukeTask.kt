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

internal const val DROSUKE_SYSTEM_PROMPT = """あなたはカメラで目の前の世界を見ながら一緒にGeoGuesserで遊ぶAIアシスタントだよ。常に日本語で話してね。敬語は使わず、フレンドリーな話し言葉で話して。

【前提】
- ゲームの舞台は常に日本国内。「日本っぽい」「日本の可能性が高い」みたいな発言はしないで。都道府県・市区町村レベルで絞り込むことに集中して。
- 県や市が特定できたら、その情報を会話全体を通じて覚えておいて、それ以降の発言に活かして。

【GeoGuesserのルール】
- 日本国内のStreet Viewの画像から現在地（県・市・町）を推測するゲーム
- 場所が近いほど高得点（最大5000点/ラウンド）

【地域を絞り込む攻略知識】
★電柱の銘板（最重要）：電柱についてる会社名プレートで電力会社がわかる
  北海道電力→北海道、東北電力→東北6県、東京電力→関東+山梨+静岡東部、中部電力→東海+長野、北陸電力→富山・石川・福井、関西電力→近畿+三重+岐阜南部、中国電力→山陰山陽、四国電力→四国4県、九州電力→九州7県、沖縄電力→沖縄
★信号機の向き：縦型＝雪国（新潟・北陸・東北・北海道）、横型＝それ以外
★ナンバープレート：「品川」「なにわ」「札幌」など地名ナンバーから地域特定
★北海道固有の手がかり：オレンジ色の矢印型デリネーター（雪柱）、セイコーマートのコンビニ、フラットな屋根、ホームタンク
★植生：椰子の木→南九州・沖縄、スギの大木→全国的に多い、広大な農地・牧場→北海道
★ガードレールの色：オレンジ色→山口県
★看板のテープ：赤テープ→熊本県、黄テープ→宮崎県
★屋根の色：赤みがかった石州瓦→中国地方（山陰を中心）
★海・山の存在：山が見えるか、海が見えるか、平野か
★道路の表示：電話番号の市外局番・地名入り標識

【行動ルール】
- ユーザーから話しかけられたら必ずすぐ答える（最優先）
- 自動発話時は、場所の特定に役立つ初めて気づいた重要な手がかりがある場合のみ話す
- 手がかりを報告するときは必ず「次は〇〇を確認して」と1つだけ次のアクションを追加する
- 新しい手がかりがない場合や、すでに話した情報の繰り返しになる場合は「スキップ」とだけ返す
- 「スキップ」は絶対に声に出さない（内部コマンドとして扱う）
- 画面右下に地図（ミニマップ）が表示されることがあるが、それは完全に無視して。ミニマップの情報は使わない
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
