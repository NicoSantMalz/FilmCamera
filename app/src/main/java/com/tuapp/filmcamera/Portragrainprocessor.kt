package com.tuapp.filmcamera

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.*
import kotlin.random.Random

/**
 * PortraGrainProcessor — Grano C-41 Físico del Kodak Portra 400
 *
 * Simula el proceso real de revelado C-41:
 *
 * El Portra 400 tiene 3 capas de emulsión sensibles a R, G, B.
 * En el revelado C-41, los haluros de plata expuestos se convierten
 * en tintes de colorante (CMY). Cada capa tiene:
 *
 * - Distribución de grano independiente (clusters ovales, no círculos)
 * - Tamaño y densidad diferente por capa
 * - Desplazamiento espacial entre capas (registro imperfecto)
 * - Acopladores de color que crean variación cromática orgánica
 * - Inhibición de revelado entre granos vecinos (distribución ordenada)
 *
 * Pipeline:
 * 1. Generar 3 mapas de grano independientes (capas R, G, B del film)
 * 2. Aplicar clusters ovales con orientación aleatoria
 * 3. Modular intensidad por curva H&D (máximo en medios tonos)
 * 4. Mezclar en espacio logarítmico con variación cromática C-41
 */
object PortraGrainProcessor {

    // Parámetros del grano Portra 400 calibrados
    data class GrainParams(
        val intensity: Float = 1.0f,    // 0-1, escalado por el slider
        // Capas de emulsión (tamaño relativo de grano por capa)
        val layerRedSize: Float = 1.15f,    // capa cian: grano ligeramente más grande
        val layerGreenSize: Float = 1.00f,  // capa magenta: referencia
        val layerBlueSize: Float = 0.88f,   // capa amarilla: grano más fino
        // Variación cromática C-41 (acopladores de color)
        val chromaVarianceR: Float = 0.018f,
        val chromaVarianceG: Float = 0.012f,
        val chromaVarianceB: Float = 0.015f,
        // Aspecto oval de los granos
        val ovalityX: Float = 1.3f,    // elongación horizontal
        val ovalityY: Float = 0.85f,   // compresión vertical
        // Tamaño base del grano en píxeles (para foto 12MP)
        val grainSizePx: Float = 2.8f
    )

    // Parámetros calibrados: intensidad 20/100, tamaño 2.2px, roughness 65%
    private val defaultParams = GrainParams(
        grainSizePx = 2.2f,          // tamaño 2.0-2.5px
        chromaVarianceR = 0.022f,    // varianza cromática C-41
        chromaVarianceG = 0.015f,
        chromaVarianceB = 0.018f
    )

    /**
     * Aplica el grano C-41 del Portra 400 sobre un bitmap ya procesado.
     *
     * @param src Bitmap de entrada (ya con color science aplicado)
     * @param intensity Intensidad 0-1 del slider de grano
     * @param seed Semilla aleatoria para variación entre fotos
     */
    fun apply(src: Bitmap, intensity: Float, seed: Long = System.currentTimeMillis()): Bitmap {
        if (intensity < 0.01f) return src
        val params = defaultParams.copy(intensity = intensity)

        val w = src.width; val h = src.height
        val scale = (w / 1080f).coerceAtLeast(0.5f)

        // Generar 3 mapas de grano independientes (capas C-41)
        val grainRed   = generateC41Layer(w, h, scale, params.layerRedSize, seed + 0L)
        val grainGreen = generateC41Layer(w, h, scale, params.layerGreenSize, seed + 1000L)
        val grainBlue  = generateC41Layer(w, h, scale, params.layerBlueSize, seed + 2000L)

        val srcPx = IntArray(w * h); src.getPixels(srcPx, 0, w, 0, 0, w, h)
        val resPx = IntArray(w * h)
        val eps = 1e-4f

        for (i in srcPx.indices) {
            val p = srcPx[i]; val a = Color.alpha(p)
            val r = Color.red(p) / 255f
            val g = Color.green(p) / 255f
            val b = Color.blue(p) / 255f

            // Luminancia en espacio lineal
            val rLin = srgbToLinear(r); val gLin = srgbToLinear(g); val bLin = srgbToLinear(b)
            val luma = rLin * 0.2126f + gLin * 0.7152f + bLin * 0.0722f

            // Densidad óptica: espacio logarítmico donde vive el grano C-41
            val density = -log10((luma + eps).toDouble()).toFloat()

            // Curva H&D del grano Portra 400:
            // - Toe (D > 2.0): grano casi ausente en sombras muy profundas
            // - Zona óptima (D 0.5-1.4): máximo grano en medios tonos
            // - Shoulder (D < 0.2): grano mínimo en altas luces comprimidas
            // Curva H&D del grano: midtones 70% de peso, sombras/luces menos
            // Roughness 65%: tanh más pronunciado (aplicado en generateC41Layer)
            // Midtones 80-180/255 = luma 0.314-0.706 = density 0.15-0.50
            val hdWeight = when {
                density > 2.8f -> 0.0f
                density > 2.0f -> lerp(0.0f, 0.20f, (2.8f-density)/0.8f)   // sombras profundas
                density > 1.2f -> lerp(0.20f, 0.55f, (2.0f-density)/0.8f)  // sombras
                density > 0.50f -> lerp(0.55f, 1.0f, (1.2f-density)/0.7f)  // sombras medias
                density > 0.15f -> 1.0f   // MIDTONES 80-180/255: peso máximo
                density > 0.05f -> lerp(1.0f, 0.25f, (0.15f-density)/0.10f) // altas luces
                else -> 0.15f                                                  // blancos
            }

            if (hdWeight < 0.01f) { resPx[i] = p; continue }

            // Grano de cada capa — centrado en 0 (-0.5 a 0.5)
            // Grano centrado en 0
            val gR = grainRed[i] - 0.5f
            val gG = grainGreen[i] - 0.5f
            val gB = grainBlue[i] - 0.5f

            // Intensidad 20/100, Overlay blend a 40% opacidad
            val baseAmt = params.intensity * hdWeight * 0.080f

            // Normalizar grano a 0-1 para overlay blend
            val gnR = (gR * baseAmt + 0.5f).coerceIn(0f, 1f)
            val gnG = (gG * baseAmt * 0.95f + 0.5f).coerceIn(0f, 1f)
            val gnB = (gB * baseAmt * 1.05f + 0.5f).coerceIn(0f, 1f)

            // Overlay: 2ab si b<0.5, 1-2(1-a)(1-b) si b>=0.5
            fun overlay(base: Float, grain: Float): Float =
                if (base < 0.5f) 2f * base * grain
                else 1f - 2f * (1f - base) * (1f - grain)

            // 40% opacidad
            val oR = r + (overlay(r, gnR) - r) * 0.40f
            val oG = g + (overlay(g, gnG) - g) * 0.40f
            val oB = b + (overlay(b, gnB) - b) * 0.40f

            // Variación cromática C-41
            val outR = (oR + gG * params.chromaVarianceR * baseAmt * 0.3f).coerceIn(0f, 1f)
            val outG = (oG + gB * params.chromaVarianceG * baseAmt * 0.2f).coerceIn(0f, 1f)
            val outB = (oB + gR * params.chromaVarianceB * baseAmt * 0.25f).coerceIn(0f, 1f)


            resPx[i] = Color.argb(a,
                (outR * 255).toInt(),
                (outG * 255).toInt(),
                (outB * 255).toInt())
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(resPx, 0, w, 0, 0, w, h)
        return result
    }

    /**
     * Genera un mapa de grano con estructura C-41 para una capa del film.
     *
     * La clave es el smooth noise con correlación espacial oval:
     * - Los granos son clusters, no ruido blanco
     * - La forma es ligeramente oval (como los cristales de haluro real)
     * - La inhibición de revelado se simula con una función de exclusión suave
     */
    private fun generateC41Layer(
        w: Int, h: Int,
        scale: Float,
        layerSize: Float,
        seed: Long
    ): FloatArray {
        val rng = Random(seed)
        val result = FloatArray(w * h)

        // Tamaño del grano escalado — más grande en fotos de menor resolución
        val grainPx = (defaultParams.grainSizePx * layerSize * scale).coerceAtLeast(1.0f)

        // Seeds para el noise de esta capa
        val seedX = rng.nextFloat() * 500f
        val seedY = rng.nextFloat() * 500f
        val seedRot = rng.nextFloat() * PI.toFloat() // rotación aleatoria del oval

        // Coseno y seno para rotar el oval
        val cosR = cos(seedRot.toDouble()).toFloat()
        val sinR = sin(seedRot.toDouble()).toFloat()

        for (y in 0 until h) {
            for (x in 0 until w) {
                // Coordenadas normalizadas por tamaño de grano
                val gx = (x / grainPx + seedX)
                val gy = (y / grainPx + seedY)

                // Aplicar rotación para el aspecto oval no alineado con ejes
                val rx = gx * cosR - gy * sinR
                val ry = gx * sinR + gy * cosR

                // Coordenadas con aspecto oval (elongación horizontal/compresión vertical)
                val ox = rx * defaultParams.ovalityX
                val oy = ry * defaultParams.ovalityY

                // Smooth noise de 2 octavas para estructura de cluster
                val n1 = smoothNoise(ox, oy)
                val n2 = smoothNoise(ox * 2.0f + 31.7f, oy * 2.0f + 47.3f) * 0.5f
                val combined = (n1 + n2) / 1.5f

                // Umbral de inhibición: el grano C-41 no es continuo
                // Los granos revelados crean zonas de inhibición alrededor
                // Esto da la distribución más "ordenada" del color negativo vs B&W
                val inhibited = applyInhibition(combined)

                result[y * w + x] = inhibited
            }
        }

        // Normalizar 0-1
        val min = result.min(); val max = result.max()
        val range = (max - min).coerceAtLeast(0.001f)
        for (i in result.indices) result[i] = (result[i] - min) / range

        return result
    }

    /**
     * Función de inhibición de revelado C-41.
     * Los granos revelados secretan inhibidores que reducen el revelado vecino.
     * Esto crea una distribución más uniforme y organizada que el B&W.
     * Se modela como una función sigmoide con zona de exclusión suave.
     */
    // Roughness 65%: tanh con pendiente 2.6 para granos bien definidos
    private fun applyInhibition(noise: Float): Float {
        val centered = (noise - 0.5f) * 2.4f
        val sigmoid = tanh(centered.toDouble() * 2.6).toFloat()
        return (sigmoid + 1f) / 2f
    }

    // Smooth noise con interpolación cúbica (igual que en AGSL)
    private fun smoothNoise(x: Float, y: Float): Float {
        val ix = floor(x).toInt(); val iy = floor(y).toInt()
        val fx = x - floor(x); val fy = y - floor(y)
        val ux = fx * fx * (3f - 2f * fx)
        val uy = fy * fy * (3f - 2f * fy)
        val a = hash(ix, iy); val b = hash(ix+1, iy)
        val c = hash(ix, iy+1); val d = hash(ix+1, iy+1)
        return lerp(lerp(a, b, ux), lerp(c, d, ux), uy)
    }

    private fun hash(x: Int, y: Int): Float {
        var h = (x * 374761393 + y * 668265263).toLong()
        h = h xor (h shr 13); h *= -1274126177L
        h = h xor (h shr 16)
        return (h and 0x7FFFFFFFL).toFloat() / 0x7FFFFFFF.toFloat()
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun srgbToLinear(v: Float): Float {
        return if (v <= 0.04045f) v / 12.92f
        else exp(2.4 * ln(((v + 0.055f) / 1.055f).toDouble())).toFloat()
    }

    private fun tanh(x: Double): Float {
        val e2x = exp(2.0 * x)
        return ((e2x - 1.0) / (e2x + 1.0)).toFloat()
    }

    private fun floor(x: Float) = kotlin.math.floor(x.toDouble()).toFloat()
}