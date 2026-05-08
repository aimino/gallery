/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.ui.common

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors
import kotlinx.coroutines.launch

@Composable
fun LiveCameraView(
  onBitmap: (Bitmap, ImageProxy) -> Unit,
  modifier: Modifier = Modifier,
  preferredSize: Int = 500,
  @ImageAnalysis.OutputImageFormat
  outputImageFormat: Int = ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888,
  renderPreview: Boolean = true,
  useHardwarePreview: Boolean = false,
  cameraSelector: CameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val lifecycleOwner = LocalLifecycleOwner.current
  var imageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
  var cameraProvider: ProcessCameraProvider? by remember { mutableStateOf(null) }

  // Hardware preview mode: PreviewView を使う場合は専用の起動パスへ
  if (useHardwarePreview) {
    val previewView = remember { PreviewView(context) }

    val hardwareLauncher =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionGranted ->
        if (permissionGranted) {
          scope.launch {
            cameraProvider =
              startCameraWithHardwarePreview(
                context = context,
                lifecycleOwner = lifecycleOwner,
                onBitmap = onBitmap,
                preferredSize = preferredSize,
                outputImageFormat = outputImageFormat,
                cameraSelector = cameraSelector,
                previewView = previewView,
              )
          }
        }
      }

    LaunchedEffect(Unit) {
      when (PackageManager.PERMISSION_GRANTED) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) -> {
          cameraProvider =
            startCameraWithHardwarePreview(
              context = context,
              lifecycleOwner = lifecycleOwner,
              onBitmap = onBitmap,
              preferredSize = preferredSize,
              outputImageFormat = outputImageFormat,
              cameraSelector = cameraSelector,
              previewView = previewView,
            )
        }
        else -> hardwareLauncher.launch(Manifest.permission.CAMERA)
      }
    }

    DisposableEffect(Unit) { onDispose { cameraProvider?.unbindAll() } }

    if (renderPreview) {
      AndroidView(
        factory = { previewView },
        modifier = modifier,
      )
    }
    return
  }

  // --- 従来モード: ImageAnalysis → Bitmap → Compose Canvas ---

  val onBitmapFn: (Bitmap, ImageProxy) -> Unit = { bitmap, imageProxy ->
    imageBitmap = bitmap.asImageBitmap()
    onBitmap(bitmap, imageProxy)
  }

  val liveCameraPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
      permissionGranted ->
      if (permissionGranted) {
        scope.launch {
          cameraProvider =
            startCamera(
              context = context,
              lifecycleOwner = lifecycleOwner,
              onBitmap = onBitmapFn,
              preferredSize = preferredSize,
              outputImageFormat = outputImageFormat,
              cameraSelector = cameraSelector,
            )
        }
      }
    }

  LaunchedEffect(Unit) {
    // Check permission.
    when (PackageManager.PERMISSION_GRANTED) {
      // Already got permission. Call the lambda.
      ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) -> {
        cameraProvider =
          startCamera(
            context = context,
            lifecycleOwner = lifecycleOwner,
            onBitmap = onBitmapFn,
            preferredSize = preferredSize,
            outputImageFormat = outputImageFormat,
            cameraSelector = cameraSelector,
          )
      }

      // Otherwise, ask for permission
      else -> {
        liveCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
      }
    }
  }

  DisposableEffect(Unit) { onDispose { cameraProvider?.unbindAll() } }

  // Camera live view.
  if (renderPreview) {
    Row(modifier = modifier.background(Color.Red), horizontalArrangement = Arrangement.Center) {
      val ib = imageBitmap
      if (ib != null) {
        Canvas(modifier = Modifier.fillMaxSize()) {
          val bitmapWidth = ib.width.toFloat()
          val bitmapHeight = ib.height.toFloat()
          val canvasWidth = size.width
          val canvasHeight = size.height

          // Calculate the scale to fill the canvas while maintaining aspect ratio
          val scale: Float =
            if (bitmapWidth / bitmapHeight > canvasWidth / canvasHeight) {
              canvasHeight / bitmapHeight
            } else {
              canvasWidth / bitmapWidth
            }

          // Calculate the source rectangle (what to draw from the bitmap)
          val srcLeft = (bitmapWidth - canvasWidth / scale) / 2
          val srcTop = (bitmapHeight - canvasHeight / scale) / 2
          val srcRight = srcLeft + canvasWidth / scale
          val srcBottom = srcTop + canvasHeight / scale
          val srcRect = Rect(srcLeft, srcTop, srcRight, srcBottom)

          // The destination rectangle is the entire canvas
          val dstRect = Rect(0f, 0f, canvasWidth, canvasHeight)

          // Draw the bitmap with the calculated source and destination rectangles
          drawImage(
            image = ib,
            srcOffset = IntOffset(srcRect.topLeft.x.toInt(), srcRect.topLeft.y.toInt()),
            srcSize = IntSize(srcRect.width.toInt(), srcRect.height.toInt()),
            dstOffset = IntOffset(dstRect.topLeft.x.toInt(), dstRect.topLeft.y.toInt()),
            dstSize = IntSize(dstRect.width.toInt(), dstRect.height.toInt()),
          )
        }
      }
    }
  }
}

/**
 * ハードウェアPreview（PreviewView）＋ImageAnalysis を同時にバインドする。
 * PreviewView はハードウェアSurface に直接描画するため、CPU/GPU 負荷に影響されない。
 * ImageAnalysis は LLM へ渡す Bitmap の取得専用で、プレビュー表示には使わない。
 */
private suspend fun startCameraWithHardwarePreview(
  context: Context,
  lifecycleOwner: LifecycleOwner,
  onBitmap: (Bitmap, ImageProxy) -> Unit,
  preferredSize: Int,
  @ImageAnalysis.OutputImageFormat outputImageFormat: Int,
  cameraSelector: CameraSelector,
  previewView: PreviewView,
): ProcessCameraProvider {
  val cameraProvider = ProcessCameraProvider.awaitInstance(context)

  val preview = Preview.Builder().build()
  preview.setSurfaceProvider(previewView.surfaceProvider)

  val resolutionSelector =
    ResolutionSelector.Builder()
      .setResolutionStrategy(
        ResolutionStrategy(
          Size(preferredSize, preferredSize),
          ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
        )
      )
      .build()
  val imageAnalysis =
    ImageAnalysis.Builder()
      .setResolutionSelector(resolutionSelector)
      .setOutputImageFormat(outputImageFormat)
      .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
      .build()
      .also {
        it.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
          var bitmap = imageProxy.toBitmap()
          val rotation = imageProxy.imageInfo.rotationDegrees
          val matrix = Matrix()
          if (rotation != 0) {
            matrix.postRotate(rotation.toFloat())
          }
          if (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) {
            matrix.postScale(-1f, 1f)
          }
          bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
          onBitmap(bitmap, imageProxy)
        }
      }

  try {
    cameraProvider.unbindAll()
    cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)
  } catch (exc: Exception) {
    // todo: Handle exceptions
  }
  return cameraProvider
}

/** Asynchronously initializes and starts the camera for image capture and analysis. */
private suspend fun startCamera(
  context: Context,
  lifecycleOwner: LifecycleOwner,
  onBitmap: (Bitmap, ImageProxy) -> Unit,
  preferredSize: Int,
  @ImageAnalysis.OutputImageFormat outputImageFormat: Int,
  cameraSelector: CameraSelector,
): ProcessCameraProvider {
  val cameraProvider = ProcessCameraProvider.awaitInstance(context)

  val resolutionSelector =
    ResolutionSelector.Builder()
      .setResolutionStrategy(
        ResolutionStrategy(
          Size(preferredSize, preferredSize),
          ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
        )
      )
      .build()
  val imageAnalysis =
    ImageAnalysis.Builder()
      .setResolutionSelector(resolutionSelector)
      .setOutputImageFormat(outputImageFormat)
      .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
      .build()
      .also {
        it.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
          var bitmap = imageProxy.toBitmap()
          val rotation = imageProxy.imageInfo.rotationDegrees
          val matrix = Matrix()
          if (rotation != 0) {
            matrix.postRotate(rotation.toFloat())
          }
          if (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) {
            matrix.postScale(-1f, 1f)
          }
          bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
          //  The caller is responsible of calling `.close` on imageProxy to mark that the
          //  processing of the current frame is done.
          onBitmap(bitmap, imageProxy)
        }
      }

  try {
    cameraProvider.unbindAll()
    cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, imageAnalysis)
  } catch (exc: Exception) {
    // todo: Handle exceptions (e.g., camera initialization failure)
  }
  return cameraProvider
}
