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
 * FilmTextureGrain v4.0 — Grano en Espacio Logarítmico
 *
 * El cambio fundamental: procesamos en densidad óptica D = -log10(luminancia)
 * En este espacio, el grano respeta la curva sensitométrica real:
 * - Sombras (D alto): grano casi ausente sin necesidad de máscaras manuales
 * - Medios tonos (D medio): grano máximo
 * - Altas luces (D bajo): grano comprimido naturalmente
 *
 * Adicionalmente, la textura 8K se filtra por frecuencia espacial:
 * - Se extraen solo las frecuencias medias (el grano real)
 * - Se eliminan frecuencias bajas (polvo, manchas de la foto base)
 * - Se eliminan frecuencias altas (ruido de cámara de la foto de textura)
 */
object FilmTextureGrain {

    private const val TEXTURE_FILENAME = "Textura 8k.jpg"
    private var textureWidth: Int = -1
    private var textureHeight: Int = -1

    fun apply(
        context: Context,
        photo: Bitmap,
        intensity: Float = 0.65f,
        cleanliness: Float = 0.45f,
        rollName: String = ""
    ): Bitmap {
        if (intensity <= 0.01f) return photo

        // [1] Micro-difusión 0.4px
        val diffused = applyEmulsionBlur(photo)

        // [2] SÍNTESIS MULTICAPA — 3 capas a escalas distintas
        // Simula la proyección 2D de haluros distribuidos en profundidad:
        //   Capa A (1.0x escala): grano grueso de superficie — 50% peso
        //   Capa B (0.6x escala): grano medio de emulsión   — 30% peso
        //   Capa C (0.35x escala): grano fino de base        — 20% peso
        // La superposición crea clusters irregulares imposibles con una sola capa
        val cropA = loadCropAtScale(context, diffused.width, diffused.height, 1.00f, rollName)
        val cropB = loadCropAtScale(context, diffused.width, diffused.height, 0.60f, rollName)
        val cropC = loadCropAtScale(context, diffused.width, diffused.height, 0.35f, rollName)

        // [3] Filtro paso banda en cada capa
        // Rugosidad por rollo — Lightroom Roughness +50 para Portra
        // rLarge más pequeño = bandpass más alto = grano más definido y contrastado
        // Rugosidad por rollo — Lightroom Roughness +50 para Portra
        val isRough = rollName == "PT 400" || rollName == "HP5 400"
        val rSmallA = 1; val rLargeA = if (isRough) 3 else 4
        val rSmallB = 1; val rLargeB = if (isRough) 2 else 3
        val rSmallC = 1; val rLargeC = 2
        val grainA = if (cropA != null) extractBandpassGrain(cropA, cleanliness, rSmallA, rLargeA) else null
        val grainB = if (cropB != null) extractBandpassGrain(cropB, cleanliness, rSmallB, rLargeB) else null
        val grainC = if (cropC != null) extractBandpassGrain(cropC, cleanliness, rSmallC, rLargeC) else null
        cropA?.recycle(); cropB?.recycle(); cropC?.recycle()

        // [4] Blend multicapa en espacio logarítmico
        val result = blendMultilayerLogSpace(diffused, grainA, grainB, grainC, intensity, rollName)
        grainA?.recycle(); grainB?.recycle(); grainC?.recycle()
        diffused.recycle()

        return result
    }

    // ── [1] Micro-difusión 0.4px ──────────────────────────────────────────────
    private fun applyEmulsionBlur(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val srcPx = IntArray(w * h); src.getPixels(srcPx, 0, w, 0, 0, w, h)
        val resPx = IntArray(w * h)
        val wC = 0.82f; val wS = 0.030f; val wK = 0.008f
        val total = wC + 4*wS + 4*wK
        for (y in 0 until h) for (x in 0 until w) {
            var rS=0f; var gS=0f; var bS=0f; var aS=0f
            for (dy in -1..1) for (dx in -1..1) {
                val p = srcPx[(y+dy).coerceIn(0,h-1)*w+(x+dx).coerceIn(0,w-1)]
                val wt = when { dx==0&&dy==0->wC; dx==0||dy==0->wS; else->wK }
                aS+=Color.alpha(p)*wt; rS+=Color.red(p)*wt
                gS+=Color.green(p)*wt; bS+=Color.blue(p)*wt
            }
            resPx[y*w+x] = Color.argb((aS/total).toInt().coerceIn(0,255),
                (rS/total).toInt().coerceIn(0,255),(gS/total).toInt().coerceIn(0,255),
                (bS/total).toInt().coerceIn(0,255))
        }
        val result = Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        result.setPixels(resPx,0,w,0,0,w,h); return result
    }

    // ── [2] Crop con escala variable por capa ────────────────────────────────
    /**
     * layerScale controla qué tan grande es el crop en la textura:
     * - 1.0: crop grande → downscale grande → grano fino (superficie)
     * - 0.6: crop medio → grano medio (emulsión)
     * - 0.35: crop pequeño → menos downscale → grano más grueso (base)
     *
     * Cada capa usa un offset aleatorio diferente → patrón único garantizado
     */
    private fun loadCropAtScale(
        context: Context,
        targetW: Int,
        targetH: Int,
        layerScale: Float,
        rollName: String = ""
    ): Bitmap? {
        return try {
            if (textureWidth < 0) {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.assets.open(TEXTURE_FILENAME).use { BitmapFactory.decodeStream(it,null,opts) }
                textureWidth = opts.outWidth; textureHeight = opts.outHeight
            }

            // Escala base según resolución de la foto
            val baseScale = grainScaleForResolution(targetW, targetH, rollName)

            // El crop en la textura: más grande = grano más fino tras downscale
            val ratioW = textureWidth.toFloat() / targetW
            val ratioH = textureHeight.toFloat() / targetH
            val ratio = minOf(ratioW, ratioH)

            val effectiveScale = baseScale * (0.5f + layerScale * 0.5f)
            val cropW = (targetW * ratio * effectiveScale).toInt().coerceIn(targetW, textureWidth)
            val cropH = (targetH * ratio * effectiveScale).toInt().coerceIn(targetH, textureHeight)

            // Offset aleatorio independiente por capa
            val maxX = (textureWidth - cropW).coerceAtLeast(0)
            val maxY = (textureHeight - cropH).coerceAtLeast(0)
            val startX = if (maxX > 0) Random.nextInt(maxX) else 0
            val startY = if (maxY > 0) Random.nextInt(maxY) else 0

            val decoder: BitmapRegionDecoder? = context.assets.open(TEXTURE_FILENAME).use {
                BitmapRegionDecoder.newInstance(it, false)
            }
            if (decoder == null) return null

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
            android.util.Log.e("FilmTextureGrain", "Error capa $layerScale: ${e.message}"); null
        }
    }

    private fun grainScaleForResolution(w: Int, h: Int, rollName: String = ""): Float {
        val mp = (w * h) / 1_000_000f
        val baseScale = when {
            mp > 10f -> 0.98f
            mp > 6f  -> 0.96f
            mp > 3f  -> 0.94f
            mp > 1f  -> 0.90f
            else     -> 0.85f
        }
        // Tamaño de grano por rollo:
        // Lightroom Tamaño +25 para Portra = crop más pequeño = grano más grueso
        // Escala < 1.0 = crop más pequeño de la textura = grano más grande en la foto
        return when (rollName) {
            "PT 400"  -> baseScale * 0.72f  // grano grueso ISO 400 (Lightroom Size +25)
            "CS 800T" -> baseScale * 0.82f  // grano grueso ISO 800
            "HP5 400" -> baseScale * 0.75f  // grano B&W grueso
            "VV 50"   -> baseScale * 0.98f  // grano muy fino ISO 50
            else      -> baseScale * 0.88f
        }
    }

    // ── [3] Filtro paso banda — extrae solo el grano, elimina polvo y ruido ───
    /**
     * Técnica: Unsharp Mask diferencial
     * grain_bandpass = texture - blur_large (elimina polvo) - (texture - blur_small) (elimina ruido)
     * = blur_small - blur_large
     * Esto deja solo las frecuencias espaciales del grano real (~2-8px)
     *
     * cleanliness: 0=textura completa con polvo, 1=solo grano puro sin imperfecciones
     */
    private fun extractBandpassGrain(grain: Bitmap, cleanliness: Float, rSmall: Int = 1, rLarge: Int = 5): Bitmap {
        val w = grain.width; val h = grain.height
        val px = IntArray(w*h); grain.getPixels(px, 0, w, 0, 0, w, h)

        val blurSmall = boxBlurLuma(px, w, h, rSmall)
        val blurLarge = boxBlurLuma(px, w, h, rLarge)

        // Bandpass = diferencia entre los dos blurs
        // Centrado en 0.5 (neutro) para mezcla posterior
        val bandpass = FloatArray(w*h) { i ->
            val bp = blurSmall[i] - blurLarge[i]  // -1..1
            // Ajuste de limpieza: reduce el rango para aspecto más limpio
            val scale = lerp(1.0f, 0.4f, cleanliness)
            0.5f + bp * scale  // 0..1 centrado en 0.5
        }

        // Micro-blur 0.5px sobre el grano — quita la "dureza digital"
        // y lo hace verse más químico/orgánico
        val blurred = boxBlurField(bandpass, w, h, 1)

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val resPx = IntArray(w*h) { i ->
            val v = (blurred[i] * 255).toInt().coerceIn(0,255)
            Color.argb(255, v, v, v)
        }
        result.setPixels(resPx, 0, w, 0, 0, w, h)
        return result
    }

    // Box blur sobre campo de floats (para el micro-blur del grano)
    private fun boxBlurField(field: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val hPass = FloatArray(field.size)
        val diam = (2*r+1).toFloat()
        for (y in 0 until h) {
            var sum = 0f
            for (k in -r..r) sum += field[y*w+k.coerceIn(0,w-1)]
            hPass[y*w] = sum/diam
            for (x in 1 until w) {
                sum -= field[y*w+(x-r-1).coerceIn(0,w-1)]
                sum += field[y*w+(x+r).coerceIn(0,w-1)]
                hPass[y*w+x] = sum/diam
            }
        }
        val result = FloatArray(field.size)
        for (x in 0 until w) {
            var sum = 0f
            for (k in -r..r) sum += hPass[k.coerceIn(0,h-1)*w+x]
            result[x] = sum/diam
            for (y in 1 until h) {
                sum -= hPass[(y-r-1).coerceIn(0,h-1)*w+x]
                sum += hPass[(y+r).coerceIn(0,h-1)*w+x]
                result[y*w+x] = sum/diam
            }
        }
        return result
    }

    // Box blur eficiente en luminancia
    private fun boxBlurLuma(px: IntArray, w: Int, h: Int, r: Int): FloatArray {
        val luma = FloatArray(w*h) { i ->
            val p = px[i]
            Color.red(p)*0.2126f/255f + Color.green(p)*0.7152f/255f + Color.blue(p)*0.0722f/255f
        }
        val hPass = FloatArray(w*h)
        // Horizontal
        for (y in 0 until h) {
            var sum = 0f
            for (k in -r..r) sum += luma[y*w+k.coerceIn(0,w-1)]
            hPass[y*w] = sum/(2*r+1)
            for (x in 1 until w) {
                sum -= luma[y*w+(x-r-1).coerceIn(0,w-1)]
                sum += luma[y*w+(x+r).coerceIn(0,w-1)]
                hPass[y*w+x] = sum/(2*r+1)
            }
        }
        // Vertical
        val result = FloatArray(w*h)
        for (x in 0 until w) {
            var sum = 0f
            for (k in -r..r) sum += hPass[k.coerceIn(0,h-1)*w+x]
            result[x] = sum/(2*r+1)
            for (y in 1 until h) {
                sum -= hPass[(y-r-1).coerceIn(0,h-1)*w+x]
                sum += hPass[(y+r).coerceIn(0,h-1)*w+x]
                result[y*w+x] = sum/(2*r+1)
            }
        }
        return result
    }

    // ── [4] Blend Multicapa en Espacio Logarítmico ──────────────────────────
    /**
     * Mezcla 3 capas de grano con pesos 50/30/20%
     * Cada capa opera en espacio de densidad óptica → curva H&D automática
     * La superposición crea la irregularidad característica del grano analógico
     */
    private fun blendMultilayerLogSpace(
        photo: Bitmap,
        grainA: Bitmap?,
        grainB: Bitmap?,
        grainC: Bitmap?,
        intensity: Float,
        rollName: String = ""
    ): Bitmap {
        val w = photo.width; val h = photo.height
        val photoPx = IntArray(w*h); photo.getPixels(photoPx, 0, w, 0, 0, w, h)
        val pxA = grainA?.let { IntArray(w*h).also { px -> it.getPixels(px,0,w,0,0,w,h) } }
        val pxB = grainB?.let { IntArray(w*h).also { px -> it.getPixels(px,0,w,0,0,w,h) } }
        val pxC = grainC?.let { IntArray(w*h).also { px -> it.getPixels(px,0,w,0,0,w,h) } }
        val resPx = IntArray(w*h)
        val eps = 1e-4f

        for (i in photoPx.indices) {
            val pp = photoPx[i]
            val pR = Color.red(pp)/255f
            val pG = Color.green(pp)/255f
            val pB = Color.blue(pp)/255f
            val luma = pR*0.2126f + pG*0.7152f + pB*0.0722f

            // Densidad óptica
            val density = -log10((luma + eps).toDouble()).toFloat()

            // Curva H&D en espacio logarítmico
            val hdWeight = when {
                density < 0.10f -> density / 0.10f * 0.08f
                density < 0.50f -> lerp(0.08f, 1.0f, (density-0.10f)/0.40f)
                density < 1.10f -> 1.0f
                density < 1.60f -> lerp(1.0f, 0.25f, (density-1.10f)/0.50f)
                density < 2.20f -> lerp(0.25f, 0.01f, (density-1.60f)/0.60f)
                else -> 0.0f
            }

            if (hdWeight < 0.005f) { resPx[i] = pp; continue }

            val rawA = if (pxA != null) Color.red(pxA[i])/255f - 0.5f else 0f
            val rawB = if (pxB != null) Color.red(pxB[i])/255f - 0.5f else 0f
            val rawC = if (pxC != null) Color.red(pxC[i])/255f - 0.5f else 0f

            // Portra 400: pesos dinámicos por zona tonal
            // Sombras = grano grueso, luces = grano fino (estructura real de haluros)
            // Todos los demás rollos: pesos fijos 50/30/20
            val combined = if (rollName == "PT 400") {
                val tonal = (1f - (density / 2.5f).coerceIn(0f, 1f))
                val wA = lerp(0.35f, 0.60f, tonal)
                val wC = lerp(0.35f, 0.10f, tonal)
                rawA*wA + rawB*0.30f + rawC*wC
            } else {
                rawA*0.50f + rawB*0.30f + rawC*0.20f
            }

            // Soft clip con tanh calibrado por rugosidad del rollo
            // Portra Roughness +50: tanh más pronunciado = granos más definidos
            val tanhStr = if (rollName == "PT 400" || rollName == "HP5 400") 4.5 else 3.0
            val grainSoft = (Math.tanh((combined * tanhStr).toDouble()) / tanhStr).toFloat()

            // Multiplicador base calibrado para cada rollo
            val grainMultiplier = if (rollName == "PT 400") 0.140f else 0.090f
            val grainAmount = grainSoft * intensity * hdWeight * grainMultiplier

            // Fluctuación cromática independiente por canal (como emulsión real)
            // Portra: R más activo, G más fino, B intermedio
            val chromaR = if (rollName == "PT 400") grainAmount * 1.22f else grainAmount * 1.10f
            val chromaG = if (rollName == "PT 400") grainAmount * 0.88f else grainAmount * 0.92f
            val chromaB = if (rollName == "PT 400") grainAmount * 1.06f else grainAmount * 1.04f

            resPx[i] = Color.argb(Color.alpha(pp),
                ((pR+chromaR).coerceIn(0f,1f)*255).toInt(),
                ((pG+chromaG).coerceIn(0f,1f)*255).toInt(),
                ((pB+chromaB).coerceIn(0f,1f)*255).toInt())
        }
        val result = Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        result.setPixels(resPx,0,w,0,0,w,h); return result
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b-a)*t.coerceIn(0f,1f)
}