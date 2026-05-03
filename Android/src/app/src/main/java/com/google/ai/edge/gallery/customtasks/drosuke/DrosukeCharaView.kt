package com.google.ai.edge.gallery.customtasks.drosuke

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.google.ai.edge.gallery.R
import kotlinx.coroutines.delay
import kotlin.random.Random

@Composable
fun DrosukeCharaView(
  isSpeaking: Boolean,
  modifier: Modifier = Modifier,
) {
  val mouths = listOf(
    R.drawable.drosuke_mouth_a,
    R.drawable.drosuke_mouth_i,
    R.drawable.drosuke_mouth_u,
    R.drawable.drosuke_mouth_e,
    R.drawable.drosuke_mouth_o,
  )
  val blinkMouths = listOf(
    R.drawable.drosuke_blink_mouth_a,
    R.drawable.drosuke_blink_mouth_i,
    R.drawable.drosuke_blink_mouth_u,
    R.drawable.drosuke_blink_mouth_e,
    R.drawable.drosuke_blink_mouth_o,
  )

  var mouthIndex by remember { mutableIntStateOf(0) }
  var isBlinking by remember { mutableIntStateOf(0) } // 0: 目開き, 1: まばたき中

  // 口パクアニメーション
  LaunchedEffect(isSpeaking) {
    if (isSpeaking) {
      while (true) {
        delay(150)
        mouthIndex = (mouthIndex + 1) % mouths.size
      }
    } else {
      mouthIndex = 0
    }
  }

  // まばたきアニメーション（3〜5秒おきにランダム）
  LaunchedEffect(Unit) {
    while (true) {
      delay(Random.nextLong(3000, 5000))
      isBlinking = 1
      delay(150)
      isBlinking = 0
    }
  }

  val baseRes = if (isBlinking == 1) R.drawable.drosuke_blink_chara_base else R.drawable.drosuke_chara_base
  val mouthRes = if (isSpeaking) {
    if (isBlinking == 1) blinkMouths[mouthIndex] else mouths[mouthIndex]
  } else null

  Box(modifier = modifier) {
    Image(
      painter = painterResource(baseRes),
      contentDescription = "ドロ助",
      modifier = Modifier.fillMaxSize(),
      contentScale = ContentScale.Fit,
    )
    if (mouthRes != null) {
      Image(
        painter = painterResource(mouthRes),
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Fit,
      )
    }
  }
}
