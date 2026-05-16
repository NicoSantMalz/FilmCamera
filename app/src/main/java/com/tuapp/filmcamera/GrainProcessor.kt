package com.tuapp.filmcamera

import android.graphics.Bitmap
import kotlin.math.*

object GrainProcessor {

    fun getBaseGrain(rollName: String): Float {
        return when (rollName) {
            "KG 200"  -> 0.28f
            "CS 800T" -> 0.62f
            "HP5 400" -> 0.50f
            "PT 400"  -> 0.42f   // ISO 400: grano prominente como scan real
            "VV 50"   -> 0.10f
            else      -> 0.25f
        }
    }

    // ─── Permutation table para Perlin ───────────────────────────────────────
    private val perm: IntArray by lazy {
        val base = (0..255).toMutableList().also { it.shuffle() }
        IntArray(512) { base[it and 255] }
    }

    private fun fade(t: Double)  = t * t * t * (t * (t * 6.0 - 15.0) + 10.0)
    private fun lerp(a: Double, b: Double, t: Double) = a + t * (b - a)

    private fun grad(hash: Int, x: Double, y: Double): Double = when (hash and 3) {
        0 ->  x + y; 1 -> -x + y; 2 ->  x - y; else -> -x - y
    }

    private fun perlin(x: Double, y: Double): Double {
        val xi = floor(x).toInt() and 255
        val yi = floor(y).toInt() and 255
        val xf = x - floor(x)
        val yf = y - floor(y)
        val u  = fade(xf); val v = fade(yf)
        val aa = perm[perm[xi]     + yi]
        val ab = perm[perm[xi]     + yi + 1]
        val ba = perm[perm[xi + 1] + yi]
        val bb = perm[perm[xi + 1] + yi + 1]
        return lerp(
            lerp(grad(aa, xf,     yf),     grad(ba, xf - 1, yf),     u),
            lerp(grad(ab, xf,     yf - 1), grad(bb, xf - 1, yf - 1), u),
            v
        )
    }

    // Ruido fractal multi-octava → clusters orgánicos
    private fun fractalNoise(x: Double, y: Double, octaves: Int = 4): Double {
        var val_ = 0.0; var amp = 0.5; var freq = 1.0; var max = 0.0
        repeat(octaves) {
            val_ += perlin(x * freq, y * freq) * amp
            max  += amp; amp *= 0.5; freq *= 2.1
        }
        return (val_ / max).coerceIn(-1.0, 1.0)
    }

    // ─── Blur Gaussiano 1D separable ─────────────────────────────────────────
    private fun gaussianKernel(sigma: Float): FloatArray {
        val radius = ceil(sigma * 2.5f).toInt().coerceAtLeast(1)
        val size   = radius * 2 + 1
        val k      = FloatArray(size) { exp(-((it - radius).toFloat().pow(2)) / (2 * sigma * sigma)) }
        val sum    = k.sum()
        return FloatArray(size) { k[it] / sum }
    }

    private fun blurH(src: FloatArray, w: Int, h: Int, k: FloatArray): FloatArray {
        val r   = k.size / 2
        val dst = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            for (i in k.indices) s += src[y * w + (x + i - r).coerceIn(0, w - 1)] * k[i]
            dst[y * w + x] = s
        }
        return dst
    }

    private fun blurV(src: FloatArray, w: Int, h: Int, k: FloatArray): FloatArray {
        val r   = k.size / 2
        val dst = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            for (i in k.indices) s += src[(y + i - r).coerceIn(0, h - 1) * w + x] * k[i]
            dst[y * w + x] = s
        }
        return dst
    }

    private fun gaussianBlur(src: FloatArray, w: Int, h: Int, sigma: Float): FloatArray {
        if (sigma < 0.3f) return src
        val k = gaussianKernel(sigma)
        return blurV(blurH(src, w, h, k), w, h, k)
    }

    // ─── Máscara de luminancia (campana Gauss centrada en 18%) ───────────────
    private fun luminanceMask(lum: Float): Float {
        val center    = 0.18f
        val sigmaLow  = 0.40f   // permisivo en sombras
        val sigmaHigh = 0.22f   // agresivo en altas luces → protege blancos
        val sigma     = if (lum <= center) sigmaLow else sigmaHigh
        val x         = lum - center
        return exp(-(x * x) / (2f * sigma * sigma))
    }

    // ─── Soft Light blending (W3C spec) ──────────────────────────────────────
    private fun softLight(base: Float, blend: Float): Float {
        // blend en -1..1, base en 0..1
        val b  = base.coerceIn(0f, 1f)
        val bl = ((blend + 1f) / 2f).coerceIn(0f, 1f)   // → 0..1
        return if (bl <= 0.5f) {
            b - (1f - 2f * bl) * b * (1f - b)
        } else {
            val d = if (b <= 0.25f) ((16f * b - 12f) * b + 4f) * b else sqrt(b)
            b + (2f * bl - 1f) * (d - b)
        }
    }

    // ─── Generar mapa de grano (para preview en cámara) ──────────────────────
    fun generateGrainBitmap(width: Int, height: Int, intensity: Float): Bitmap? {
        if (intensity <= 0.01f || width <= 0 || height <= 0) return null

        val grainScale = 3.0 + intensity * 8.0
        val blurSigma  = 0.5f + intensity * 3.0f
        val downScale  = 4
        val gw = (width  / downScale).coerceAtLeast(1)
        val gh = (height / downScale).coerceAtLeast(1)

        val ox = Math.random() * 500.0
        val oy = Math.random() * 500.0

        val noise = FloatArray(gw * gh) { i ->
            val x = i % gw; val y = i / gw
            fractalNoise((x + ox) / grainScale, (y + oy) / grainScale, 4).toFloat()
        }
        val blurred = gaussianBlur(noise, gw, gh, blurSigma / downScale)

        val pixels = IntArray(gw * gh) { i ->
            val n     = blurred[i]
            val alpha = (intensity * 180f).toInt().coerceIn(0, 255)
            val v     = (128 + n * 90f * intensity).toInt().coerceIn(0, 255)
            (alpha shl 24) or (v shl 16) or (v shl 8) or v
        }

        val small = Bitmap.createBitmap(gw, gh, Bitmap.Config.ARGB_8888)
        small.setPixels(pixels, 0, gw, 0, 0, gw, gh)
        val scaled = Bitmap.createScaledBitmap(small, width, height, true)
        small.recycle()
        return scaled
    }

    // ─── Aplicar grano Silver Halide al bitmap final ─────────────────────────
    fun applyGrain(bitmap: Bitmap, intensity: Float): Bitmap {
        if (intensity <= 0.01f) return bitmap

        val w = bitmap.width
        val h = bitmap.height

        val grainScale  = 3.0 + intensity * 8.0
        val blurSigma   = 0.5f + intensity * 3.0f
        val amplitude   = intensity * 80f           // 0..80 niveles de grano
        val chromaAmp   = amplitude * 0.12f         // fluctuación cromática ~12%

        // Generar mapa Perlin en baja resolución
        val downScale = 3
        val gw = (w / downScale).coerceAtLeast(1)
        val gh = (h / downScale).coerceAtLeast(1)

        val ox = Math.random() * 500.0
        val oy = Math.random() * 500.0

        val noiseMap = FloatArray(gw * gh) { i ->
            val x = i % gw; val y = i / gw
            fractalNoise((x + ox) / grainScale, (y + oy) / grainScale, 4).toFloat()
        }
        val blurredMap = gaussianBlur(noiseMap, gw, gh, blurSigma / downScale)

        // Segundo mapa de baja frecuencia para fluctuación cromática
        val chromaScale = grainScale * 3.0
        val ox2 = Math.random() * 500.0; val oy2 = Math.random() * 500.0
        val chromaMap = FloatArray(gw * gh) { i ->
            val x = i % gw; val y = i / gw
            fractalNoise((x + ox2) / chromaScale, (y + oy2) / chromaScale, 2).toFloat()
        }

        // Procesar píxeles
        val srcPixels = IntArray(w * h)
        bitmap.getPixels(srcPixels, 0, w, 0, 0, w, h)
        val outPixels = IntArray(w * h)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                val px  = srcPixels[idx]

                val r = ((px shr 16) and 0xFF) / 255f
                val g = ((px shr 8)  and 0xFF) / 255f
                val b = ( px         and 0xFF) / 255f

                // Luminancia fotográfica
                val lum  = 0.299f * r + 0.587f * g + 0.114f * b
                val mask = luminanceMask(lum)

                // Ruido lumínico del mapa Perlin
                val gx    = (x / downScale).coerceIn(0, gw - 1)
                val gy    = (y / downScale).coerceIn(0, gh - 1)
                val noise = blurredMap[gy * gw + gx]          // -1..1
                val lumN  = noise * mask * amplitude / 255f    // normalizado

                // Fluctuación cromática de baja frecuencia
                val chroma = chromaMap[gy * gw + gx]
                val cr = chroma * chromaAmp / 255f
                val cg = -chroma * chromaAmp * 0.5f / 255f
                val cb = chroma * chromaAmp * 0.7f / 255f

                // Soft Light blend
                val newR = (softLight(r, lumN + cr) * 255).toInt().coerceIn(0, 255)
                val newG = (softLight(g, lumN + cg) * 255).toInt().coerceIn(0, 255)
                val newB = (softLight(b, lumN + cb) * 255).toInt().coerceIn(0, 255)

                outPixels[idx] = (0xFF shl 24) or (newR shl 16) or (newG shl 8) or newB
            }
        }

        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        result.setPixels(outPixels, 0, w, 0, 0, w, h)
        return result
    }
}