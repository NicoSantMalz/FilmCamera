package com.tuapp.filmcamera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.*
import kotlin.random.Random

/**
 * FilmTextureGrain v5.1
 *
 * Separación clara entre carga (IO) y procesamiento (Default):
 * - loadTextureBitmaps(): carga los 3 crops desde assets — llamar en Dispatchers.IO
 * - applyWithBitmaps(): procesa píxeles con los bitmaps ya cargados
 * - applyWithCrops(): wrapper para List<Bitmap> — compatible con GalleryEditorScreen
 * - apply(): API legacy — carga y aplica en un solo paso
 */
object FilmTextureGrain {

    private const val TEXTURE_FILENAME = "Textura 8k.jpg"

    // ── API pública ───────────────────────────────────────────────────────────

    fun loadTextureBitmaps(
        context: Context,
        targetW: Int,
        targetH: Int,
        rollName: String
    ): Triple<Bitmap?, Bitmap?, Bitmap?>? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.assets.open(TEXTURE_FILENAME).use {
                BitmapFactory.decodeStream(it, null, opts)
            }
            val textureW = opts.outWidth
            val textureH = opts.outHeight
            if (textureW <= 0 || textureH <= 0) {
                android.util.Log.e("FilmTextureGrain", "Textura inválida: ${textureW}x${textureH}")
                return null
            }
            android.util.Log.d("FilmTextureGrain", "Textura OK: ${textureW}x${textureH}")

            val a = loadCrop(context, targetW, targetH, 1.00f, rollName, textureW, textureH)
            val b = loadCrop(context, targetW, targetH, 0.60f, rollName, textureW, textureH)
            val c = loadCrop(context, targetW, targetH, 0.35f, rollName, textureW, textureH)
            Triple(a, b, c)
        } catch (e: Exception) {
            android.util.Log.e("FilmTextureGrain", "Error cargando textura: ${e.message}")
            null
        }
    }

    fun applyWithBitmaps(
        photo: Bitmap,
        cropA: Bitmap?,
        cropB: Bitmap?,
        cropC: Bitmap?,
        intensity: Float,
        cleanliness: Float,
        rollName: String
    ): Bitmap {
        if (intensity <= 0.01f) return photo

        val diffused = applyEmulsionBlur(photo)

        val isRough = rollName == "PT 400" || rollName == "HP5 400"
        val grainA = if (cropA != null) extractBandpassGrain(cropA, cleanliness, 1, if (isRough) 3 else 4) else null
        val grainB = if (cropB != null) extractBandpassGrain(cropB, cleanliness, 1, if (isRough) 2 else 3) else null
        val grainC = if (cropC != null) extractBandpassGrain(cropC, cleanliness, 1, 2) else null

        val result = blendMultilayerLogSpace(diffused, grainA, grainB, grainC, intensity, rollName)
        grainA?.recycle(); grainB?.recycle(); grainC?.recycle()
        diffused.recycle()
        return result
    }

    /**
     * applyWithCrops — wrapper sobre applyWithBitmaps para usar con List<Bitmap>.
     * Compatible con el formato que retorna GalleryEditorScreen al pre-cargar crops.
     * La lista debe tener 3 elementos: [cropA, cropB, cropC] (cualquiera puede ser null).
     */
    fun applyWithCrops(
        photo: Bitmap,
        crops: List<Bitmap?>,
        intensity: Float,
        cleanliness: Float,
        rollName: String = ""
    ): Bitmap {
        val cropA = crops.getOrNull(0)
        val cropB = crops.getOrNull(1)
        val cropC = crops.getOrNull(2)
        return applyWithBitmaps(photo, cropA, cropB, cropC, intensity, cleanliness, rollName)
    }

    /**
     * API legacy — carga y aplica en un solo paso.
     * Úsala solo cuando el contexto esté disponible en el hilo actual.
     */
    fun apply(
        context: Context,
        photo: Bitmap,
        intensity: Float = 0.65f,
        cleanliness: Float = 0.45f,
        rollName: String = ""
    ): Bitmap {
        if (intensity <= 0.01f) return photo
        val crops = loadTextureBitmaps(context, photo.width, photo.height, rollName)
            ?: return photo
        return applyWithBitmaps(photo, crops.first, crops.second, crops.third,
            intensity, cleanliness, rollName).also {
            crops.first?.recycle(); crops.second?.recycle(); crops.third?.recycle()
        }
    }

    // ── Carga de un crop ──────────────────────────────────────────────────────

    private fun loadCrop(
        context: Context,
        targetW: Int,
        targetH: Int,
        layerScale: Float,
        rollName: String,
        textureW: Int,
        textureH: Int
    ): Bitmap? {
        return try {
            val baseScale = grainScaleForResolution(targetW, targetH, rollName)
            val ratioW = textureW.toFloat() / targetW
            val ratioH = textureH.toFloat() / targetH
            val ratio = minOf(ratioW, ratioH)
            val effectiveScale = baseScale * (0.5f + layerScale * 0.5f)
            val cropW = (targetW * ratio * effectiveScale).toInt().coerceIn(targetW, textureW)
            val cropH = (targetH * ratio * effectiveScale).toInt().coerceIn(targetH, textureH)
            val maxX = (textureW - cropW).coerceAtLeast(0)
            val maxY = (textureH - cropH).coerceAtLeast(0)
            val startX = if (maxX > 0) Random.nextInt(maxX) else 0
            val startY = if (maxY > 0) Random.nextInt(maxY) else 0

            val decoder = context.assets.open(TEXTURE_FILENAME).use {
                BitmapRegionDecoder.newInstance(it, false)
            } ?: return null

            val crop = decoder.decodeRegion(
                Rect(startX, startY, startX + cropW, startY + cropH),
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            )
            decoder.recycle()
            if (crop == null) return null

            val scaled = Bitmap.createScaledBitmap(crop, targetW, targetH, true)
            crop.recycle()
            scaled
        } catch (e: Exception) {
            android.util.Log.e("FilmTextureGrain", "Error crop $layerScale: ${e.message}")
            null
        }
    }

    private fun grainScaleForResolution(w: Int, h: Int, rollName: String): Float {
        val mp = (w * h) / 1_000_000f
        val base = when {
            mp > 10f -> 0.98f; mp > 6f -> 0.96f
            mp > 3f  -> 0.94f; mp > 1f -> 0.90f
            else     -> 0.85f
        }
        return when (rollName) {
            "PT 400"  -> base * 0.72f
            "CS 800T" -> base * 0.82f
            "HP5 400" -> base * 0.75f
            "VV 50"   -> base * 0.98f
            else      -> base * 0.88f
        }
    }

    // ── Procesamiento de píxeles ──────────────────────────────────────────────

    private fun applyEmulsionBlur(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val srcPx = IntArray(w * h); src.getPixels(srcPx, 0, w, 0, 0, w, h)
        val resPx = IntArray(w * h)
        val wC = 0.82f; val wS = 0.030f; val wK = 0.008f
        val total = wC + 4 * wS + 4 * wK
        for (y in 0 until h) for (x in 0 until w) {
            var rS = 0f; var gS = 0f; var bS = 0f; var aS = 0f
            for (dy in -1..1) for (dx in -1..1) {
                val p = srcPx[(y + dy).coerceIn(0, h - 1) * w + (x + dx).coerceIn(0, w - 1)]
                val wt = when { dx == 0 && dy == 0 -> wC; dx == 0 || dy == 0 -> wS; else -> wK }
                aS += Color.alpha(p) * wt; rS += Color.red(p) * wt
                gS += Color.green(p) * wt; bS += Color.blue(p) * wt
            }
            resPx[y * w + x] = Color.argb(
                (aS / total).toInt().coerceIn(0, 255),
                (rS / total).toInt().coerceIn(0, 255),
                (gS / total).toInt().coerceIn(0, 255),
                (bS / total).toInt().coerceIn(0, 255)
            )
        }
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(resPx, 0, w, 0, 0, w, h)
        return result
    }

    private fun extractBandpassGrain(grain: Bitmap, cleanliness: Float, rSmall: Int, rLarge: Int): Bitmap {
        val w = grain.width; val h = grain.height
        val px = IntArray(w * h); grain.getPixels(px, 0, w, 0, 0, w, h)
        val blurSmall = boxBlurLuma(px, w, h, rSmall)
        val blurLarge = boxBlurLuma(px, w, h, rLarge)
        val scale = lerp(1.0f, 0.4f, cleanliness)
        val bandpass = FloatArray(w * h) { i -> 0.5f + (blurSmall[i] - blurLarge[i]) * scale }
        val blurred = boxBlurField(bandpass, w, h, 1)
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val resPx = IntArray(w * h) { i ->
            val v = (blurred[i] * 255).toInt().coerceIn(0, 255)
            Color.argb(255, v, v, v)
        }
        result.setPixels(resPx, 0, w, 0, 0, w, h)
        return result
    }

    private fun boxBlurField(field: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val diam = (2 * r + 1).toFloat()
        val hPass = FloatArray(field.size)
        for (y in 0 until h) {
            var sum = 0f
            for (k in -r..r) sum += field[y * w + k.coerceIn(0, w - 1)]
            hPass[y * w] = sum / diam
            for (x in 1 until w) {
                sum -= field[y * w + (x - r - 1).coerceIn(0, w - 1)]
                sum += field[y * w + (x + r).coerceIn(0, w - 1)]
                hPass[y * w + x] = sum / diam
            }
        }
        val result = FloatArray(field.size)
        for (x in 0 until w) {
            var sum = 0f
            for (k in -r..r) sum += hPass[k.coerceIn(0, h - 1) * w + x]
            result[x] = sum / diam
            for (y in 1 until h) {
                sum -= hPass[(y - r - 1).coerceIn(0, h - 1) * w + x]
                sum += hPass[(y + r).coerceIn(0, h - 1) * w + x]
                result[y * w + x] = sum / diam
            }
        }
        return result
    }

    private fun boxBlurLuma(px: IntArray, w: Int, h: Int, r: Int): FloatArray {
        val luma = FloatArray(w * h) { i ->
            val p = px[i]
            Color.red(p) * 0.2126f / 255f + Color.green(p) * 0.7152f / 255f + Color.blue(p) * 0.0722f / 255f
        }
        val hPass = FloatArray(w * h)
        for (y in 0 until h) {
            var sum = 0f
            for (k in -r..r) sum += luma[y * w + k.coerceIn(0, w - 1)]
            hPass[y * w] = sum / (2 * r + 1)
            for (x in 1 until w) {
                sum -= luma[y * w + (x - r - 1).coerceIn(0, w - 1)]
                sum += luma[y * w + (x + r).coerceIn(0, w - 1)]
                hPass[y * w + x] = sum / (2 * r + 1)
            }
        }
        val result = FloatArray(w * h)
        for (x in 0 until w) {
            var sum = 0f
            for (k in -r..r) sum += hPass[k.coerceIn(0, h - 1) * w + x]
            result[x] = sum / (2 * r + 1)
            for (y in 1 until h) {
                sum -= hPass[(y - r - 1).coerceIn(0, h - 1) * w + x]
                sum += hPass[(y + r).coerceIn(0, h - 1) * w + x]
                result[y * w + x] = sum / (2 * r + 1)
            }
        }
        return result
    }

    private fun blendMultilayerLogSpace(
        photo: Bitmap,
        grainA: Bitmap?, grainB: Bitmap?, grainC: Bitmap?,
        intensity: Float,
        rollName: String
    ): Bitmap {
        val w = photo.width; val h = photo.height
        val photoPx = IntArray(w * h); photo.getPixels(photoPx, 0, w, 0, 0, w, h)
        val pxA = grainA?.let { IntArray(w * h).also { px -> it.getPixels(px, 0, w, 0, 0, w, h) } }
        val pxB = grainB?.let { IntArray(w * h).also { px -> it.getPixels(px, 0, w, 0, 0, w, h) } }
        val pxC = grainC?.let { IntArray(w * h).also { px -> it.getPixels(px, 0, w, 0, 0, w, h) } }
        val resPx = IntArray(w * h)
        val eps = 1e-4f

        for (i in photoPx.indices) {
            val pp = photoPx[i]
            val pR = Color.red(pp) / 255f
            val pG = Color.green(pp) / 255f
            val pB = Color.blue(pp) / 255f
            val luma = pR * 0.2126f + pG * 0.7152f + pB * 0.0722f
            val density = -log10((luma + eps).toDouble()).toFloat()

            val hdWeight = when {
                density < 0.10f -> density / 0.10f * 0.08f
                density < 0.50f -> lerp(0.08f, 1.0f, (density - 0.10f) / 0.40f)
                density < 1.10f -> 1.0f
                density < 1.60f -> lerp(1.0f, 0.25f, (density - 1.10f) / 0.50f)
                density < 2.20f -> lerp(0.25f, 0.01f, (density - 1.60f) / 0.60f)
                else            -> 0.0f
            }

            if (hdWeight < 0.005f) { resPx[i] = pp; continue }

            val rawA = if (pxA != null) Color.red(pxA[i]) / 255f - 0.5f else 0f
            val rawB = if (pxB != null) Color.red(pxB[i]) / 255f - 0.5f else 0f
            val rawC = if (pxC != null) Color.red(pxC[i]) / 255f - 0.5f else 0f

            val combined = if (rollName == "PT 400") {
                val tonal = (1f - (density / 2.5f).coerceIn(0f, 1f))
                val wA = lerp(0.35f, 0.60f, tonal)
                val wC = lerp(0.35f, 0.10f, tonal)
                rawA * wA + rawB * 0.30f + rawC * wC
            } else {
                rawA * 0.50f + rawB * 0.30f + rawC * 0.20f
            }

            val tanhStr = if (rollName == "PT 400" || rollName == "HP5 400") 4.5 else 3.0
            val grainSoft = (Math.tanh(combined * tanhStr) / tanhStr).toFloat()

            val mult = if (rollName == "PT 400") 0.140f else 0.090f
            val chromaR = grainSoft * intensity * hdWeight * mult * if (rollName == "PT 400") 1.22f else 1.10f
            val chromaG = grainSoft * intensity * hdWeight * mult * if (rollName == "PT 400") 0.88f else 0.92f
            val chromaB = grainSoft * intensity * hdWeight * mult * if (rollName == "PT 400") 1.06f else 1.04f

            resPx[i] = Color.argb(
                Color.alpha(pp),
                ((pR + chromaR).coerceIn(0f, 1f) * 255).toInt(),
                ((pG + chromaG).coerceIn(0f, 1f) * 255).toInt(),
                ((pB + chromaB).coerceIn(0f, 1f) * 255).toInt()
            )
        }
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(resPx, 0, w, 0, 0, w, h)
        return result
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t.coerceIn(0f, 1f)
}