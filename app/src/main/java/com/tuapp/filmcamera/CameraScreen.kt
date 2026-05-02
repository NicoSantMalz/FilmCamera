package com.tuapp.filmcamera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.os.Build
import android.provider.MediaStore
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.OutputStream
import kotlin.math.roundToInt

val imageCaptureHolder = mutableStateOf<ImageCapture?>(null)

@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    rollName: String = "KG 200",
    filterIntensity: Float = 1f,
    grainIntensity: Float = 1f
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var errorMsg by remember { mutableStateOf<String?>(null) }

    if (errorMsg != null) {
        Box(
            modifier = modifier.background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Text(text = errorMsg!!, color = Color.Red)
        }
        return
    }

    val finalGrainIntensity = grainIntensity.coerceIn(0f, 1f)

    Box(modifier = modifier) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    startCamera(ctx, lifecycleOwner, this, onError = { errorMsg = it })
                }
            },
            update = { previewView ->
                val matrix = FilmFilters.getMatrix(rollName, filterIntensity)
                previewView.setLayerType(
                    android.view.View.LAYER_TYPE_HARDWARE,
                    Paint().apply {
                        colorFilter = ColorMatrixColorFilter(matrix)
                    }
                )
            },
            modifier = Modifier.fillMaxSize()
        )

        AndroidView(
            factory = { ctx ->
                ImageView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    scaleType = ImageView.ScaleType.FIT_XY
                    alpha = if (finalGrainIntensity > 0.01f) 1f else 0f

                    viewTreeObserver.addOnGlobalLayoutListener(
                        object : ViewTreeObserver.OnGlobalLayoutListener {
                            override fun onGlobalLayout() {
                                viewTreeObserver.removeOnGlobalLayoutListener(this)
                                if (width > 0 && height > 0 && finalGrainIntensity > 0.01f) {
                                    val grain = GrainProcessor.generateGrainBitmap(
                                        width, height, finalGrainIntensity
                                    )
                                    setImageBitmap(grain)
                                }
                            }
                        }
                    )
                }
            },
            update = { imageView ->
                imageView.alpha = if (finalGrainIntensity > 0.01f) 1f else 0f
                val w = imageView.width.takeIf { it > 0 }
                val h = imageView.height.takeIf { it > 0 }
                if (w != null && h != null && finalGrainIntensity > 0.01f) {
                    val grain = GrainProcessor.generateGrainBitmap(w, h, finalGrainIntensity)
                    imageView.setImageBitmap(grain)
                } else if (finalGrainIntensity <= 0.01f) {
                    imageView.setImageBitmap(null)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

private fun startCamera(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
    onError: (String) -> Unit
) {
    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

    cameraProviderFuture.addListener({
        try {
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            imageCaptureHolder.value = imageCapture

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageCapture
            )
        } catch (e: Exception) {
            onError("Error cámara: ${e.message}")
        }
    }, ContextCompat.getMainExecutor(context))
}

fun applyFilterToBitmap(bitmap: Bitmap, matrix: ColorMatrix): Bitmap {
    val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(result)
    val paint = Paint().apply {
        colorFilter = ColorMatrixColorFilter(matrix)
    }
    canvas.drawBitmap(bitmap, 0f, 0f, paint)
    return result
}

fun takePhoto(
    context: Context,
    rollName: String,
    filterIntensity: Float,
    grainIntensity: Float,
    onSaved: () -> Unit,
    onError: (String) -> Unit
) {
    val imageCapture = imageCaptureHolder.value ?: return

    imageCapture.takePicture(
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    image.close()

                    var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

                    val rotation = image.imageInfo.rotationDegrees
                    if (rotation != 0) {
                        val rotMatrix = android.graphics.Matrix()
                        rotMatrix.postRotate(rotation.toFloat())
                        bitmap = Bitmap.createBitmap(
                            bitmap, 0, 0,
                            bitmap.width, bitmap.height,
                            rotMatrix, true
                        )
                    }

                    // 1. Filtro de color
                    val colorMatrix = FilmFilters.getMatrix(rollName, filterIntensity)
                    var filtered = applyFilterToBitmap(bitmap, colorMatrix)

                    // 2. Grano
                    filtered = GrainProcessor.applyGrain(filtered, grainIntensity.coerceIn(0f, 1f))

                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, "film_${System.currentTimeMillis()}")
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FilmCamera")
                        }
                    }

                    val uri = context.contentResolver.insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        contentValues
                    )

                    uri?.let {
                        val stream: OutputStream? = context.contentResolver.openOutputStream(it)
                        stream?.use { out ->
                            filtered.compress(Bitmap.CompressFormat.JPEG, 95, out)
                        }
                        onSaved()
                    } ?: onError("No se pudo guardar")

                } catch (e: Exception) {
                    onError("Error: ${e.message}")
                }
            }

            override fun onError(exception: ImageCaptureException) {
                onError("Error captura: ${exception.message}")
            }
        }
    )
}