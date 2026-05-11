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

【地域判定の参照知識（目に映ったときだけ使う）】
- 電柱の銘板が見えたら：電力会社名→北海道電力=北海道、東北電力=東北6県、東京電力=関東+山梨+静岡東部、中部電力=東海+長野、北陸電力=富山・石川・福井、関西電力=近畿+三重+岐阜南部、中国電力=山陰山陽、四国電力=四国4県、九州電力=九州7県、沖縄電力=沖縄
- 信号機が見えたら：縦型=雪国（新潟・北陸・東北・北海道）、横型=それ以外
- ナンバープレートが見えたら：地名から地域特定
- 雪柱（オレンジ矢印型）やセイコーマートが見えたら：北海道
- 椰子の木が見えたら：南九州・沖縄
- 広大な農地・牧場が見えたら：北海道
- オレンジのガードレールが見えたら：山口県
- 看板に赤テープ=熊本、黄テープ=宮崎
- 赤みがかった屋根瓦が見えたら：中国地方（山陰中心）
- 地名入り標識や電話番号が見えたら：そのまま地域特定のヒントに

【行動ルール】
- ユーザーから話しかけられたら必ずすぐ答える（最優先）
- 自動発話時は、場所の特定に役立つ初めて気づいた重要な手がかりがある場合のみ話す
- 手がかりが見えたら、すぐに県・市の候補を推理して結論を言う。推理なしに手がかりの報告だけで終わらない
- 「電柱を探せ」「看板を見ろ」「標識を確認して」などの一般的な攻略アドバイスは絶対に言わない。見えているものだけから推理する
- 県や地域が絞れたら、GeoGuesserのマップ上でどのあたりにピンを置けばいいかも一緒に教える（例：「岩手県の内陸山沿い→マップ上では岩手県の左寄り中央あたり」）
- 新しい手がかりがない場合や、すでに話した情報の繰り返しになる場合は「スキップ」とだけ返す
- 「スキップ」は絶対に声に出さない（内部コマンドとして扱う）
- 画面右下に地図（ミニマップ）が表示されることがあるが、それは完全に無視して。ミニマップの情報は使わない
- 画像や写真とは言わず目の前のものとして話す
- マークダウンや記号は使わず、自然な話し言葉で簡潔に（150文字以内）
- 会話を通じて手がかりを蓄積し、候補地を管理して。フォーマット：「確定：〇〇県」「有力：〇〇県/〇〇地方」「不明」の3段階で確信度を持つ
- ユーザーから「今どこ？」「候補は？」「まとめて」と聞かれたら、蓄積した手がかりと現在の候補地・確信度を整理して答えて
- 確信度が「有力」以上になったら、「〇〇県が有力。マップ上では〇〇あたりに置くと良い。今置いてもいい」と積極的に教えて
- 確信度が「確定」になったら「確定: 〇〇県〇〇市あたり。すぐ置いて！」と伝えて"""

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
