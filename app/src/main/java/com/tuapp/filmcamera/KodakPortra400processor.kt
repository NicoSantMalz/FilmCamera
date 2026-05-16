package com.tuapp.filmcamera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import kotlin.math.*

/**
 * Kodak Portra 400 — Motor Físico-Químico v3.0
 *
 * Pipeline basado en el proceso real C-41:
 *
 * 1. sRGB → Linear → Log (scene-referred)
 * 2. Curva H&D Sigmoide (toe 3%, shoulder 0.82)
 * 3. Log → Linear → sRGB
 * 4. Densidad Sustractiva CMY (colores pastel pesados)
 * 5. Cross-channel matrix C-41
 * 6. Gamut compression
 * 7. Shift tonal C-41 (sombras cian, medios durazno, luces crema)
 * 8. Desaturación cromática en highlights
 * 9. Micro-softening (emulsión)
 * 10. Bloom Dreamy (halación suave)
 * 11. Emulsion Diffusion (dreamy selectivo por zona tonal)
 */

class KodakPortra400Daylight(private val config: DaylightConfig = DaylightConfig()) {

    data class DaylightConfig(
        // H&D
        val toeBlackPoint: Float        = 0.071f,   // punto negro 18/255 — look aireado
        val shoulderPoint: Float        = 0.922f,   // rolloff suave desde 235/255
        val shoulderStrength: Float     = 1.5f,     // agresividad del shoulder
        // Densidad sustractiva CMY
        val cmyDensityR: Float          = 0.92f,    // exponente canal R en CMY
        val cmyDensityG: Float          = 0.95f,
        val cmyDensityB: Float          = 0.88f,    // amarillo más denso (Portra)
        val cmyStrength: Float          = 0.75f,    // mezcla 0=sin efecto, 1=total
        // C-41 color shifts
        val shadowCyanR: Float          = -0.055f,  // sombras: cian-esmeralda
        val shadowCyanG: Float          = 0.012f,
        val shadowCyanB: Float          = 0.042f,
        val midtoneProtectSkin: Float   = 0.045f,   // durazno en medios tonos piel (aumentado)
        val highlightCreamR: Float      = 0.018f,   // luces: arena/crema
        val highlightCreamG: Float      = 0.010f,
        val highlightCreamB: Float      = -0.022f,
        // Saturación selectiva
        val greenSatBoost: Float        = 1.10f,
        val greenHueShift: Float        = 6f,
        val skyShift: Float             = 0.76f,
        val skinDesaturation: Float     = 0.92f,    // desaturación piel (subida: la global 0.88 ya hace trabajo)
        val skinWarmth: Float           = 0.032f,
        // Highlight desaturación cromática (pastel en luces)
        val highlightDesatStart: Float  = 0.55f,
        val highlightDesatAmount: Float = 0.42f,
        // Bloom
        val bloomThreshold: Float       = 0.78f,
        val bloomOpacity: Float         = 0.065f,
        val bloomRadius: Float          = 45f,
        // Contraste
        val contrastPivot: Float        = 0.10f,   // pivot bajo = medios tonos más brillantes
        val contrastAmount: Float       = 1.08f    // subido: más punch en medios tonos Portra
    )

    fun process(source: Bitmap): Bitmap {
        val s1 = applyMicroSoftening(source)
        val s2 = applyHDCurveLog(s1);            s1.recycle()
        val s3 = applySubtractiveDensity(s2);    s2.recycle()
        val s4 = applyC41ColorScience(s3);       s3.recycle()
        val s5 = applyDreamyBloom(s4);           s4.recycle()
        val s6 = applyEmulsionDiffusion(s5);     s5.recycle()
        val s7 = applyAtmosphericVeiling(s6);    s6.recycle()
        return s7
    }

    // ── Atmospheric Veiling — el último 5% del Portra ─────────────────────────
    /**
     * El veiling es una neblina cálida casi imperceptible que viene de:
     * 1. Dispersión de luz entre las capas de emulsión multicapa
     * 2. El "base density" del acetato que nunca es completamente transparente
     * 3. La dispersión atmosférica que el film captura diferente al sensor
     *
     * Técnica: mezcla Screen muy sutil de un tono crema-cálido
     * El efecto es más visible en zonas oscuras y medios tonos
     * que en highlights — exactamente como en película real
     *
     * Color del velo: RGB(255, 248, 235) — crema cálido levemente amarillo
     * Opacidad: 3.5% — casi imperceptible individualmente pero acumulativo
     */
    private fun applyAtmosphericVeiling(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w*h); src.getPixels(px, 0, w, 0, 0, w, h)

        // Color del velo: crema cálido #FFF8EB
        val veilR = 255f/255f
        val veilG = 248f/255f
        val veilB = 235f/255f

        for (i in px.indices) {
            val p = px[i]; val a = Color.alpha(p)
            val r = Color.red(p)/255f
            val g = Color.green(p)/255f
            val b = Color.blue(p)/255f
            val luma = r*0.2126f + g*0.7152f + b*0.0722f

            // Intensidad del velo por zona tonal:
            // Sombras: velo más visible (la neblina "levanta" los negros cálidamente)
            // Medios tonos: velo moderado
            // Highlights: casi nulo (los blancos ya son suficientemente cálidos)
            val veilStrength = when {
                luma < 0.15f -> lerp(0.055f, 0.038f, luma/0.15f)   // sombras: 5.5%→3.8%
                luma < 0.55f -> lerp(0.038f, 0.022f, (luma-0.15f)/0.40f) // medios: 3.8%→2.2%
                luma < 0.80f -> lerp(0.022f, 0.010f, (luma-0.55f)/0.25f) // altas: 2.2%→1%
                else         -> 0.008f                                // blancos: casi nulo
            }

            // Screen blend correcto: mezclar pixel con velo a la opacidad dada
            // screen(a,b) = 1-(1-a)*(1-b), luego lerp con pixel original por veilStrength
            val screenR = 1f - (1f-r)*(1f-veilR)
            val screenG = 1f - (1f-g)*(1f-veilG)
            val screenB = 1f - (1f-b)*(1f-veilB)
            val outR = lerp(r, screenR, veilStrength)
            val outG = lerp(g, screenG, veilStrength)
            val outB = lerp(b, screenB, veilStrength)

            px[i] = Color.argb(a,
                (outR.coerceIn(0f,1f)*255).toInt(),
                (outG.coerceIn(0f,1f)*255).toInt(),
                (outB.coerceIn(0f,1f)*255).toInt())
        }
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(px, 0, w, 0, 0, w, h)
        return result
    }

    // ── 1. Micro-softening 0.25px ─────────────────────────────────────────────
    private fun applyMicroSoftening(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val sp = IntArray(w*h); src.getPixels(sp, 0, w, 0, 0, w, h)
        val rp = IntArray(w*h)
        val wC=0.86f; val wS=0.025f; val wK=0.006f; val tot=wC+4*wS+4*wK
        for (y in 0 until h) for (x in 0 until w) {
            var r=0f; var g=0f; var b=0f
            for (dy in -1..1) for (dx in -1..1) {
                val p=sp[(y+dy).coerceIn(0,h-1)*w+(x+dx).coerceIn(0,w-1)]
                val wt=if(dx==0&&dy==0)wC else if(dx==0||dy==0)wS else wK
                r+=Color.red(p)*wt; g+=Color.green(p)*wt; b+=Color.blue(p)*wt
            }
            rp[y*w+x]=Color.argb(Color.alpha(sp[y*w+x]),
                (r/tot).toInt().coerceIn(0,255),(g/tot).toInt().coerceIn(0,255),(b/tot).toInt().coerceIn(0,255))
        }
        val res=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        res.setPixels(rp,0,w,0,0,w,h); return res
    }

    // ── 2. Curva H&D en espacio logarítmico ──────────────────────────────────
    /**
     * Pipeline correcto:
     * sRGB → Linear → Log (densidad óptica) → Sigmoide H&D → Linear → sRGB
     *
     * El espacio logarítmico es donde vive la película.
     * D = -log10(L + ε)  donde L es luminancia lineal
     *
     * La sigmoide H&D tiene:
     * - Toe: levanta negros al 3% (pedestal real del Portra)
     * - Zona lineal: respuesta proporcional a la exposición
     * - Shoulder: compresión exponencial desde 0.82
     */
    private fun applyHDCurveLog(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w*h); src.getPixels(px, 0, w, 0, 0, w, h)

        // LUT dual: [0] = valor sRGB para display, [1] = valor lineal para ratio (sin soft clip)
        val lut = IntArray(1024) { i ->
            val srgb = i / 1023f
            val lin = srgbToLinear(srgb)
            val eps = 1e-4f
            val logVal = (-log10((lin + eps).toDouble()) / 4.0).toFloat()
                .let { (it + 1f).coerceIn(0f, 1f) }
            val curved = portraSigmoid(logVal)
            val linOut = (10f.pow(-(curved * 4f - 4f))).coerceIn(0f, 1f)
            val srgbOut = linearToSrgb(linOut) * 0.978f
            (srgbOut.coerceIn(0f, 0.978f) * 255).toInt()
        }
        // LUT separada con lineal puro para ratio (evita error de srgbToLinear sobre valor ya clippeado)
        val lutLin = FloatArray(1024) { i ->
            val srgb = i / 1023f
            val lin = srgbToLinear(srgb)
            val eps = 1e-4f
            val logVal = (-log10((lin + eps).toDouble()) / 4.0).toFloat()
                .let { (it + 1f).coerceIn(0f, 1f) }
            val curved = portraSigmoid(logVal)
            (10f.pow(-(curved * 4f - 4f))).coerceIn(0f, 1f)
        }

        for (i in px.indices) {
            val p = px[i]; val a = Color.alpha(p)
            val linR = srgbToLinear(Color.red(p)/255f)
            val linG = srgbToLinear(Color.green(p)/255f)
            val linB = srgbToLinear(Color.blue(p)/255f)
            val luma = linR*0.2126f + linG*0.7152f + linB*0.0722f

            // Curva sobre luma (preserva balance de color)
            val lumaIdx = (linearToSrgb(luma) * 1023).toInt().coerceIn(0,1023)
            val lumaOut = lut[lumaIdx] / 255f

            // Ratio usando lutLin — valor lineal correcto sin soft clip aplicado
            val ratio = if (luma > 0.0001f) {
                val lumaOutLin = lutLin[lumaIdx]
                (lumaOutLin / luma).coerceIn(0.92f, 1.28f)
            } else 1f

            // Escalar canales + shift cian en sombras (diferencial canal B)
            val shadowF = (1f - luma).coerceIn(0f,1f).pow(2f)
            val sR = (linR * ratio).coerceIn(0f,1f)
            val sG = (linG * ratio).coerceIn(0f,1f)
            val sB = (linB * ratio + 0.010f * shadowF).coerceIn(0f,1f)

            // Contraste con pivot bajo
            val cR = (config.contrastPivot + (sR-config.contrastPivot)*config.contrastAmount).coerceIn(0f,1f)
            val cG = (config.contrastPivot + (sG-config.contrastPivot)*config.contrastAmount).coerceIn(0f,1f)
            val cB = (config.contrastPivot + (sB-config.contrastPivot)*config.contrastAmount).coerceIn(0f,1f)

            // Back to sRGB con soft clip
            val oR = (linearToSrgb(cR) * 0.978f).coerceIn(0f, 0.978f)
            val oG = (linearToSrgb(cG) * 0.978f).coerceIn(0f, 0.978f)
            val oB = (linearToSrgb(cB) * 0.978f).coerceIn(0f, 0.978f)

            px[i] = Color.argb(a, (oR*255).toInt(), (oG*255).toInt(), (oB*255).toInt())
        }
        val res = Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        res.setPixels(px,0,w,0,0,w,h); return res
    }

    /**
     * Sigmoide H&D del Portra 400.
     * Entrada/salida en espacio normalizado 0-1.
     * Toe levantado al 3%, shoulder exponencial desde 0.82.
     */
    // Curva S suave con puntos de control exactos:
    // (0,18) (64,68) (128,130) (200,198) (255,245) — en escala 0-255
    // Traducidos a 0-1: (0,0.071)(0.251,0.267)(0.502,0.510)(0.784,0.776)(1.0,0.961)
    private fun portraSigmoid(x: Float): Float {
        // Interpolar entre puntos de control con Hermite suave
        val x255 = x * 255f
        return when {
            x255 <= 0f   -> 18f/255f
            x255 <= 64f  -> {
                val t = x255 / 64f
                val smooth = t*t*(3f-2f*t)
                lerp(18f/255f, 68f/255f, smooth)
            }
            x255 <= 128f -> {
                val t = (x255-64f) / 64f
                val smooth = t*t*(3f-2f*t)
                lerp(68f/255f, 130f/255f, smooth)
            }
            x255 <= 200f -> {
                val t = (x255-128f) / 72f
                val smooth = t*t*(3f-2f*t)
                lerp(130f/255f, 198f/255f, smooth)
            }
            x255 <= 255f -> {
                val t = (x255-200f) / 55f
                val smooth = t*t*(3f-2f*t)
                lerp(198f/255f, 245f/255f, smooth)
            }
            else -> 245f/255f
        }
    }

    // ── 3. Densidad Sustractiva CMY ───────────────────────────────────────────
    /**
     * Los colores pastel del Portra vienen del proceso sustractivo C-41.
     * Los tintes de la película son CMY, no RGB.
     * Al aumentar la saturación, los tintes absorben más luz → los colores
     * saturados pierden luminancia automáticamente → efecto pastel pesado.
     *
     * C = 1-R, M = 1-G, Y = 1-B
     * C' = C^γC, M' = M^γM, Y' = Y^γY  (γ < 1 = más denso)
     * R' = 1-C', G' = 1-M', B' = 1-Y'
     */
    private fun applySubtractiveDensity(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w*h); src.getPixels(px, 0, w, 0, 0, w, h)
        val str = config.cmyStrength

        for (i in px.indices) {
            val p = px[i]; val a = Color.alpha(p)
            val r = Color.red(p)/255f
            val g = Color.green(p)/255f
            val b = Color.blue(p)/255f

            // Convertir a CMY (densidad sustractiva)
            val c = 1f - r; val m = 1f - g; val y = 1f - b

            // Aplicar densidad exponencial por canal
            // γ < 1 comprime las altas densidades → efecto pastel
            val cD = if (c > 0f) c.pow(config.cmyDensityR) else 0f
            val mD = if (m > 0f) m.pow(config.cmyDensityG) else 0f
            val yD = if (y > 0f) y.pow(config.cmyDensityB) else 0f

            // Reconvertir a RGB con mezcla
            val rD = 1f - cD; val gD = 1f - mD; val bD = 1f - yD

            // Proteger rojos saturados — usar densidades post-exponente para coherencia
            val redProtect = (cD - max(mD, yD)).coerceIn(0f, 1f)
            val strAdj = str * (1f - redProtect * 0.55f)

            // Mezclar original con resultado sustractivo
            val rOut = lerp(r, rD, strAdj).coerceIn(0f,1f)
            val gOut = lerp(g, gD, strAdj).coerceIn(0f,1f)
            val bOut = lerp(b, bD, strAdj).coerceIn(0f,1f)

            px[i] = Color.argb(a, (rOut*255).toInt(), (gOut*255).toInt(), (bOut*255).toInt())
        }
        val res = Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        res.setPixels(px,0,w,0,0,w,h); return res
    }

    // ── 4. Color Science C-41 ─────────────────────────────────────────────────
    private fun applyC41ColorScience(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w*h); src.getPixels(px, 0, w, 0, 0, w, h)

        for (i in px.indices) {
            val p = px[i]; val a = Color.alpha(p)
            var r = Color.red(p)/255f
            var g = Color.green(p)/255f
            var b = Color.blue(p)/255f
            // luma perceptual en sRGB — consistente con los umbrales de shadow/highlight de este paso
            val luma = r*0.2126f + g*0.7152f + b*0.0722f

            // ── PASO 1: Reducción de saturación global (firma del Portra) ──
            // El Portra captura con ~12% menos saturación que el digital (era 22% — demasiado agresivo,
            // aplastaba la piel antes de que llegara al bloque de protección skin)
            val hslGlobal = rgbToHsl(r.coerceIn(0f,1f), g.coerceIn(0f,1f), b.coerceIn(0f,1f))
            val sReduced = hslGlobal[1] * 0.88f
            val rgbDesat = hslToRgb(hslGlobal[0], sReduced, hslGlobal[2])
            r = rgbDesat[0]; g = rgbDesat[1]; b = rgbDesat[2]

            // Cross-channel matrix C-41
            val mR = (r*0.952f + g*0.038f + b*0.010f).coerceIn(0f,1f)
            val mG = (r*0.018f + g*0.962f + b*0.020f).coerceIn(0f,1f)
            val mB = (r*0.008f + g*0.032f + b*0.960f).coerceIn(0f,1f)
            r = mR; g = mG; b = mB

            // Shadow lift C-41 — rango ajustado para no afectar negros puros (bolsa, ropa)
            if (luma < 0.353f) {
                if (luma > 0.12f) {
                    // Sombras medias: lift verde-teal visible
                    val sf = smoothstep(0.353f, 0.12f, luma)
                    r += (2f/255f)  * sf
                    g += (20f/255f) * sf
                    b += (14f/255f) * sf
                } else if (luma > 0.04f) {
                    // Sombras bajas: lift muy sutil
                    val sf = smoothstep(0.12f, 0.04f, luma)
                    r += (1f/255f) * sf
                    g += (3f/255f) * sf
                    b += (2f/255f) * sf
                }
                // Negros puros (luma < 0.04): sin lift
            }

            // Desaturación cromática en highlights → pastel
            if (luma > config.highlightDesatStart) {
                val hf = smoothstep(config.highlightDesatStart, 1.0f, luma)
                val lumaBright = r*0.2126f + g*0.7152f + b*0.0722f
                val desatAmt = config.highlightDesatAmount * 0.65f
                r = lerp(r, lumaBright, hf * desatAmt)
                g = lerp(g, lumaBright, hf * desatAmt)
                b = lerp(b, lumaBright, hf * desatAmt)
            }

            // Saturación selectiva
            val hsl = rgbToHsl(r.coerceIn(0f,1f), g.coerceIn(0f,1f), b.coerceIn(0f,1f))
            var hh = hsl[0]; var s = hsl[1]; val l = hsl[2]

            // skinWeight extendido a hue 15-62 para capturar cara (tonos más rosados)
            val isSkinHue = hh in 15f..62f && s in 0.07f..0.85f
            val skinWeight = if (isSkinHue) {
                val lumFade = smoothstep(0.95f, 1.0f, l)
                (1f - lumFade)
            } else 0f

            var directlyModified = false  // flag: algún bloque escribió r/g/b directamente

            when {
                hh in 78f..168f -> {
                    val lw = smoothstep(0.18f, 0.55f, l)
                    s = (s * (1f + (config.greenSatBoost-1f)*lw)).coerceIn(0f,1f)
                    hh = (hh - config.greenHueShift * lw).coerceIn(0f,360f)
                    val rgb2 = hslToRgb(hh, s, l)
                    r = rgb2[0]; g = rgb2[1]; b = rgb2[2]
                    directlyModified = true
                }
                // Cielo (hue 195-218): desaturar y shift leve — más brumoso
                hh in 195f..218f && luma > 0.35f -> {
                    val sw = smoothstep(0.35f, 0.70f, luma)
                    s = (s * lerp(1.0f, 0.72f, sw)).coerceIn(0f,1f)  // desaturar más el cielo
                    hh = (hh - 2f * sw).coerceIn(0f,360f)
                    val rgb2 = hslToRgb(hh, s, l)
                    r = rgb2[0]; g = rgb2[1]; b = rgb2[2]
                    directlyModified = true
                }
                // Pared/superficies azules (hue 218-248): desaturación suave para que el crema la vire
                hh in 218f..248f && luma > 0.28f -> {
                    val sw = smoothstep(0.28f, 0.65f, luma)
                    s = (s * lerp(1.0f, 0.88f, sw)).coerceIn(0f,1f)  // solo -12% sat — preservar base azul
                    val rgb2 = hslToRgb(hh, s, l)
                    r = rgb2[0]; g = rgb2[1]; b = rgb2[2]
                    directlyModified = true
                }
                skinWeight > 0f -> {
                    hh = (hh + 5f).coerceIn(0f, 360f)
                    s = (s * lerp(1.0f, 0.92f, skinWeight)).coerceIn(0f, 1f)
                    val lumBoost = lerp(0f, 10f/255f, skinWeight * smoothstep(0.88f, 0.40f, l))
                    val lNew = (l + lumBoost).coerceIn(0f, 1f)
                    val rgb2 = hslToRgb(hh, s, lNew)
                    r = rgb2[0]; g = rgb2[1]; b = rgb2[2]
                    directlyModified = true
                }
                hh in 235f..270f && s > 0.15f -> {
                    hh = (hh - 5f).coerceIn(0f, 360f)
                    s = (s * 0.88f).coerceIn(0f, 1f)
                    val lNew = (l + 5f/255f).coerceIn(0f, 1f)
                    val rgb2 = hslToRgb(hh, s, lNew)
                    r = rgb2[0]; g = rgb2[1]; b = rgb2[2]
                    directlyModified = true
                }
                (hh < 25f || hh > 340f) && s > 0.15f && l in 0.10f..0.90f -> {
                    hh = (hh + 10f).let { if (it > 360f) it - 360f else it }
                    s = (s * 0.75f).coerceIn(0f, 1f)
                    val lNew = (l + 6f/255f).coerceIn(0f, 1f)
                    val rgb2 = hslToRgb(hh, s, lNew)
                    r = rgb2[0]; g = rgb2[1]; b = rgb2[2]
                    directlyModified = true
                }
            }

            // Highlights crema — viraje mantequilla diferenciado por zona de color
            if (luma > 0.46f) {
                val hf = smoothstep(0.46f, 1.0f, luma)
                val creamMult = lerp(1.0f, 0.15f, skinWeight)
                // En zonas azules reducir -B: no queremos neutralizar sino virar a azul-crema
                val isBlueZone = hh in 195f..270f && s > 0.08f
                val bMult = if (isBlueZone) 0.35f else 1.0f
                r += (32f/255f) * hf * creamMult
                g += (15f/255f) * hf * creamMult
                b += (-22f/255f) * hf * creamMult * bMult
                directlyModified = true
            }

            // Escribir pixel: si algún bloque modificó r/g/b directamente, usarlos
            // Si solo se modificó hh/s (verdes, cielo), reconstruir desde HSL
            val outR: Float
            val outG: Float
            val outB: Float
            if (directlyModified) {
                outR = r; outG = g; outB = b
            } else {
                val rgb = hslToRgb(hh, s, l)
                outR = rgb[0]; outG = rgb[1]; outB = rgb[2]
            }
            px[i] = Color.argb(a,
                (outR.coerceIn(0f,1f)*255).toInt(),
                (outG.coerceIn(0f,1f)*255).toInt(),
                (outB.coerceIn(0f,1f)*255).toInt())
        }
        val res = Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        res.setPixels(px,0,w,0,0,w,h); return res
    }

    // ── 5. Bloom Dreamy ───────────────────────────────────────────────────────
    /**
     * Difusión de halación: extrae altas luces, blur amplio (45px),
     * mezcla Screen 4% → resplandor que "abraza" los bordes al sol
     */
    private fun applyDreamyBloom(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val scale = (w/1080f).coerceAtLeast(0.5f)
        val srcPx = IntArray(w*h); src.getPixels(srcPx, 0, w, 0, 0, w, h)
        val hlPx = IntArray(w*h)
        for (i in srcPx.indices) {
            val p = srcPx[i]
            val luma = Color.red(p)*0.2126f/255f + Color.green(p)*0.7152f/255f + Color.blue(p)*0.0722f/255f
            if (luma > config.bloomThreshold) {
                val mask = smoothstep(config.bloomThreshold, 1.0f, luma)
                hlPx[i] = Color.argb((mask*255).toInt().coerceIn(0,255),
                    (Color.red(p)*1.015f).toInt().coerceIn(0,255),
                    (Color.green(p)*1.008f).toInt().coerceIn(0,255),
                    Color.blue(p))
            }
        }
        val hlBmp = Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        hlBmp.setPixels(hlPx,0,w,0,0,w,h)
        val blurred = fastGaussianBlur(hlBmp, (config.bloomRadius*scale).coerceAtLeast(12f))
        hlBmp.recycle()
        val result = src.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(result).drawBitmap(blurred,0f,0f,Paint().apply {
            alpha = (config.bloomOpacity*255).toInt()
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
        })
        blurred.recycle(); return result
    }

    // ── 6. Emulsion Diffusion — dreamy selectivo ──────────────────────────────
    private fun applyEmulsionDiffusion(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val scale = (w/1080f).coerceAtLeast(0.5f)
        val blurred = fastGaussianBlur(src, (1.8f*scale).coerceAtLeast(1f))
        val srcPx = IntArray(w*h); src.getPixels(srcPx, 0, w, 0, 0, w, h)
        val blurPx = IntArray(w*h); blurred.getPixels(blurPx, 0, w, 0, 0, w, h)
        val resPx = IntArray(w*h)
        for (i in srcPx.indices) {
            val sp = srcPx[i]; val bp = blurPx[i]
            val luma = Color.red(sp)*0.2126f/255f + Color.green(sp)*0.7152f/255f + Color.blue(sp)*0.0722f/255f
            val dw = when {
                luma < 0.15f -> 0f
                luma < 0.28f -> smoothstep(0.15f,0.28f,luma)*0.10f
                luma < 0.65f -> 0.16f
                luma < 0.82f -> lerp(0.16f,0.09f,smoothstep(0.65f,0.82f,luma))
                else         -> 0.09f
            }
            if (dw < 0.001f) { resPx[i]=sp; continue }
            resPx[i] = Color.argb(Color.alpha(sp),
                (lerp(Color.red(sp)/255f,Color.red(bp)/255f,dw).coerceIn(0f,1f)*255).toInt(),
                (lerp(Color.green(sp)/255f,Color.green(bp)/255f,dw).coerceIn(0f,1f)*255).toInt(),
                (lerp(Color.blue(sp)/255f,Color.blue(bp)/255f,dw).coerceIn(0f,1f)*255).toInt())
        }
        blurred.recycle()
        val res = Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        res.setPixels(resPx,0,w,0,0,w,h); return res
    }

    // ── Utilidades ────────────────────────────────────────────────────────────
    private fun srgbToLinear(v: Float) = if(v<=0.04045f) v/12.92f else ((v+0.055f)/1.055f).pow(2.4f)
    private fun linearToSrgb(v: Float) = if(v<=0.0031308f) v*12.92f else 1.055f*v.pow(1f/2.4f)-0.055f
    private fun Float.pow(e: Float): Float { if(this<=0f) return 0f; return exp(e.toDouble()*ln(this.toDouble())).toFloat() }
    private fun lerp(a:Float,b:Float,t:Float)=a+(b-a)*t.coerceIn(0f,1f)
    private fun smoothstep(e0:Float,e1:Float,x:Float):Float{val t=((x-e0)/(e1-e0)).coerceIn(0f,1f);return t*t*(3f-2f*t)}
    private fun fastGaussianBlur(src:Bitmap,radius:Float):Bitmap{
        val r=radius.toInt().coerceAtLeast(1);val w=src.width;val h=src.height
        val sp=IntArray(w*h);src.getPixels(sp,0,w,0,0,w,h)
        val sigma=r/2.5f;val size=2*r+1
        val k=FloatArray(size){val x=(it-r).toFloat();exp(-(x*x)/(2*sigma*sigma)).toFloat()}
        val ks=k.sum();for(i in k.indices)k[i]/=ks
        val hp=IntArray(w*h)
        for(y in 0 until h)for(x in 0 until w){var rS=0f;var gS=0f;var bS=0f;var aS=0f
            for(ki in k.indices){val p=sp[y*w+(x+ki-r).coerceIn(0,w-1)];val wk=k[ki]
                aS+=Color.alpha(p)*wk;rS+=Color.red(p)*wk;gS+=Color.green(p)*wk;bS+=Color.blue(p)*wk}
            hp[y*w+x]=Color.argb(aS.toInt().coerceIn(0,255),rS.toInt().coerceIn(0,255),gS.toInt().coerceIn(0,255),bS.toInt().coerceIn(0,255))}
        val rp=IntArray(w*h)
        for(x in 0 until w)for(y in 0 until h){var rS=0f;var gS=0f;var bS=0f;var aS=0f
            for(ki in k.indices){val p=hp[(y+ki-r).coerceIn(0,h-1)*w+x];val wk=k[ki]
                aS+=Color.alpha(p)*wk;rS+=Color.red(p)*wk;gS+=Color.green(p)*wk;bS+=Color.blue(p)*wk}
            rp[y*w+x]=Color.argb(aS.toInt().coerceIn(0,255),rS.toInt().coerceIn(0,255),gS.toInt().coerceIn(0,255),bS.toInt().coerceIn(0,255))}
        val res=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);res.setPixels(rp,0,w,0,0,w,h);return res
    }
    private fun rgbToHsl(r:Float,g:Float,b:Float):FloatArray{
        val mx=max(r,max(g,b));val mn=min(r,min(g,b));val d=mx-mn;val l=(mx+mn)/2f
        val s=if(d==0f)0f else d/(1f-abs(2f*l-1f))
        val h=when{d==0f->0f;mx==r->60f*(((g-b)/d)%6f);mx==g->60f*((b-r)/d+2f);else->60f*((r-g)/d+4f)}.let{if(it<0f)it+360f else it}
        return floatArrayOf(h,s.coerceIn(0f,1f),l.coerceIn(0f,1f))
    }
    private fun hslToRgb(h:Float,s:Float,l:Float):FloatArray{
        if(s==0f)return floatArrayOf(l,l,l)
        val c=(1f-abs(2f*l-1f))*s;val x=c*(1f-abs((h/60f)%2f-1f));val m=l-c/2f
        val(r1,g1,b1)=when{h<60f->Triple(c,x,0f);h<120f->Triple(x,c,0f);h<180f->Triple(0f,c,x);h<240f->Triple(0f,x,c);h<300f->Triple(x,0f,c);else->Triple(c,0f,x)}
        return floatArrayOf(r1+m,g1+m,b1+m)
    }
}

// ── Motor Tungsten ────────────────────────────────────────────────────────────
class KodakPortra400Tungsten(private val config: TungstenConfig = TungstenConfig()) {

    data class TungstenConfig(
        val wbCorrection: Float         = 0.60f,
        val shadowLift: Float           = 0.045f,
        val highlightRolloff: Float     = 0.68f,
        val contrastBoost: Float        = 1.04f,
        val shadowBlueR: Float          = -0.05f,
        val shadowBlueG: Float          = -0.01f,
        val shadowBlueB: Float          = 0.06f,
        val highlightGreenR: Float      = -0.01f,
        val highlightGreenG: Float      = 0.02f,
        val highlightGreenB: Float      = -0.01f,
        val warmLightBoost: Float       = 1.18f,
        val greenArtificialBoost: Float = 1.12f,
        val skinTungstenWarm: Float     = 0.04f,
        val skinDesaturation: Float     = 0.92f,
        val bloomEnabled: Boolean       = true,
        val bloomRadius: Float          = 18f,
        val bloomOpacity: Float         = 0.06f,
        val bloomThreshold: Float       = 0.65f
    )

    fun process(source: Bitmap): Bitmap {
        val s1 = applyHDCurve(source)
        val s2 = applyWBCorrection(s1); s1.recycle()
        val s3 = applyColorScience(s2); s2.recycle()
        val s4 = if (config.bloomEnabled) applyBloom(s3) else s3
        if (s3 !== s4) s3.recycle()
        return s4
    }

    private fun applyHDCurve(src: Bitmap): Bitmap {
        val w=src.width;val h=src.height;val px=IntArray(w*h);src.getPixels(px,0,w,0,0,w,h)
        val lut=IntArray(256){i->
            val s=i/255f;val l=if(s<=0.04045f)s/12.92f else((s+0.055f)/1.055f).pow(2.4f)
            val lifted=config.shadowLift+l*(1f-config.shadowLift)
            val curved=tungstenCurve(lifted)
            val shoulder=if(curved>config.highlightRolloff){
                val e=curved-config.highlightRolloff;val rng=1f-config.highlightRolloff
                config.highlightRolloff+rng*(1f-exp((-e/rng*2.8f).toDouble()).toFloat())}else curved
            val c=(0.40f+(shoulder-0.40f)*config.contrastBoost).coerceIn(0f,1f)
            val o=if(c<=0.0031308f)c*12.92f else 1.055f*c.pow(1f/2.4f)-0.055f
            (o.coerceIn(0f,0.978f)*255).toInt()}
        for(i in px.indices){val p=px[i];px[i]=Color.argb(Color.alpha(p),lut[Color.red(p)],lut[Color.green(p)],lut[Color.blue(p)])}
        val res=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);res.setPixels(px,0,w,0,0,w,h);return res
    }
    private fun applyWBCorrection(src:Bitmap):Bitmap{
        val w=src.width;val h=src.height;val px=IntArray(w*h);src.getPixels(px,0,w,0,0,w,h)
        val c=config.wbCorrection;val rS=-0.12f*c;val gS=-0.04f*c;val bS=+0.18f*c
        for(i in px.indices){val p=px[i];px[i]=Color.argb(Color.alpha(p),
            ((Color.red(p)/255f+rS).coerceIn(0f,1f)*255).toInt(),
            ((Color.green(p)/255f+gS).coerceIn(0f,1f)*255).toInt(),
            ((Color.blue(p)/255f+bS).coerceIn(0f,1f)*255).toInt())}
        val res=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);res.setPixels(px,0,w,0,0,w,h);return res
    }
    private fun applyColorScience(src:Bitmap):Bitmap{
        val w=src.width;val h=src.height;val px=IntArray(w*h);src.getPixels(px,0,w,0,0,w,h)
        for(i in px.indices){val p=px[i];val a=Color.alpha(p)
            var r=Color.red(p)/255f;var g=Color.green(p)/255f;var b=Color.blue(p)/255f
            val luma=r*0.2126f+g*0.7152f+b*0.0722f
            if(luma<0.35f){val sf=smoothstep(0.35f,0f,luma);r+=config.shadowBlueR*sf;g+=config.shadowBlueG*sf;b+=config.shadowBlueB*sf}
            else if(luma>0.65f){val hf=smoothstep(0.65f,1.0f,luma);r+=config.highlightGreenR*hf;g+=config.highlightGreenG*hf;b+=config.highlightGreenB*hf}
            val hsl=rgbToHsl(r.coerceIn(0f,1f),g.coerceIn(0f,1f),b.coerceIn(0f,1f))
            var hh=hsl[0];var s=hsl[1];val l=hsl[2]
            var skinModified = false
            when{hh in 25f..65f&&l>0.3f->s=(s*config.warmLightBoost).coerceIn(0f,1f)
                hh in 85f..155f->s=(s*config.greenArtificialBoost).coerceIn(0f,1f)
                hh in 8f..35f&&s in 0.15f..0.70f&&l in 0.30f..0.80f->{s=(s*config.skinDesaturation).coerceIn(0f,1f);r=(r+config.skinTungstenWarm).coerceIn(0f,1f);skinModified=true}
                hh in 240f..290f&&l<0.35f->s=(s*1.20f).coerceIn(0f,1f)}
            val rgb=hslToRgb(hh,s,l)
            // Si skin fue modificado, preservar el r ajustado; reconstruir g/b desde HSL
            val outR=if(skinModified)r else rgb[0]
            px[i]=Color.argb(a,(outR.coerceIn(0f,1f)*255).toInt(),(rgb[1].coerceIn(0f,1f)*255).toInt(),(rgb[2].coerceIn(0f,1f)*255).toInt())}
        val res=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);res.setPixels(px,0,w,0,0,w,h);return res
    }
    private fun applyBloom(src:Bitmap):Bitmap{
        val w=src.width;val h=src.height;val scale=(w/1080f).coerceAtLeast(0.5f)
        val sp=IntArray(w*h);src.getPixels(sp,0,w,0,0,w,h);val hp=IntArray(w*h)
        for(i in sp.indices){val p=sp[i];val luma=Color.red(p)*0.2126f/255f+Color.green(p)*0.7152f/255f+Color.blue(p)*0.0722f/255f
            if(luma>config.bloomThreshold){val mask=smoothstep(config.bloomThreshold,1.0f,luma);hp[i]=Color.argb((mask*255).toInt().coerceIn(0,255),Color.red(p),Color.green(p),Color.blue(p))}}
        val hb=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);hb.setPixels(hp,0,w,0,0,w,h)
        val bl=fastGaussianBlur(hb,(config.bloomRadius*scale).coerceAtLeast(6f));hb.recycle()
        val res=src.copy(Bitmap.Config.ARGB_8888,true)
        Canvas(res).drawBitmap(bl,0f,0f,Paint().apply{alpha=(config.bloomOpacity*255).toInt();xfermode=PorterDuffXfermode(PorterDuff.Mode.SCREEN)})
        bl.recycle();return res
    }
    private fun tungstenCurve(x:Float):Float=when{x<0.015f->x*0.80f;x<0.100f->0.012f+(x-0.015f)*0.90f;x<0.500f->0.0885f+(x-0.100f)*0.960f;x<0.750f->0.4725f+(x-0.500f)*0.880f;x<0.920f->0.6925f+(x-0.750f)*0.720f;else->0.8149f+(x-0.920f)*0.500f}.coerceIn(0f,1f)
    private fun Float.pow(e:Float):Float{if(this<=0f)return 0f;return exp(e.toDouble()*ln(this.toDouble())).toFloat()}
    private fun smoothstep(e0:Float,e1:Float,x:Float):Float{val t=((x-e0)/(e1-e0)).coerceIn(0f,1f);return t*t*(3f-2f*t)}
    private fun fastGaussianBlur(src:Bitmap,radius:Float):Bitmap{
        val r=radius.toInt().coerceAtLeast(1);val w=src.width;val h=src.height
        val sp=IntArray(w*h);src.getPixels(sp,0,w,0,0,w,h)
        val sigma=r/2.5f;val size=2*r+1
        val k=FloatArray(size){val x=(it-r).toFloat();exp(-(x*x)/(2*sigma*sigma)).toFloat()}
        val ks=k.sum();for(i in k.indices)k[i]/=ks
        val hp=IntArray(w*h)
        for(y in 0 until h)for(x in 0 until w){var rS=0f;var gS=0f;var bS=0f;var aS=0f
            for(ki in k.indices){val p=sp[y*w+(x+ki-r).coerceIn(0,w-1)];val wk=k[ki];aS+=Color.alpha(p)*wk;rS+=Color.red(p)*wk;gS+=Color.green(p)*wk;bS+=Color.blue(p)*wk}
            hp[y*w+x]=Color.argb(aS.toInt().coerceIn(0,255),rS.toInt().coerceIn(0,255),gS.toInt().coerceIn(0,255),bS.toInt().coerceIn(0,255))}
        val rp=IntArray(w*h)
        for(x in 0 until w)for(y in 0 until h){var rS=0f;var gS=0f;var bS=0f;var aS=0f
            for(ki in k.indices){val p=hp[(y+ki-r).coerceIn(0,h-1)*w+x];val wk=k[ki];aS+=Color.alpha(p)*wk;rS+=Color.red(p)*wk;gS+=Color.green(p)*wk;bS+=Color.blue(p)*wk}
            rp[y*w+x]=Color.argb(aS.toInt().coerceIn(0,255),rS.toInt().coerceIn(0,255),gS.toInt().coerceIn(0,255),bS.toInt().coerceIn(0,255))}
        val res=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);res.setPixels(rp,0,w,0,0,w,h);return res
    }
    private fun rgbToHsl(r:Float,g:Float,b:Float):FloatArray{val mx=max(r,max(g,b));val mn=min(r,min(g,b));val d=mx-mn;val l=(mx+mn)/2f;val s=if(d==0f)0f else d/(1f-abs(2f*l-1f));val h=when{d==0f->0f;mx==r->60f*(((g-b)/d)%6f);mx==g->60f*((b-r)/d+2f);else->60f*((r-g)/d+4f)}.let{if(it<0f)it+360f else it};return floatArrayOf(h,s.coerceIn(0f,1f),l.coerceIn(0f,1f))}
    private fun hslToRgb(h:Float,s:Float,l:Float):FloatArray{if(s==0f)return floatArrayOf(l,l,l);val c=(1f-abs(2f*l-1f))*s;val x=c*(1f-abs((h/60f)%2f-1f));val m=l-c/2f;val(r1,g1,b1)=when{h<60f->Triple(c,x,0f);h<120f->Triple(x,c,0f);h<180f->Triple(0f,c,x);h<240f->Triple(0f,x,c);h<300f->Triple(x,0f,c);else->Triple(c,0f,x)};return floatArrayOf(r1+m,g1+m,b1+m)}
}

// ── Presets ───────────────────────────────────────────────────────────────────
object KodakPortra400Presets {
    // Standard: valores base conservadores — funciona bien en cualquier foto
    val daylightStandard  = KodakPortra400Daylight.DaylightConfig(
        cmyStrength         = 0.65f,
        highlightDesatAmount= 0.30f,
        skinDesaturation    = 0.92f,
        greenSatBoost       = 0.95f   // ligeramente bajo 1 para no sobreexplotar pasto
    )
    // Portrait: los valores que iteramos en sesión para retratos exteriores
    val daylightPortrait  = KodakPortra400Daylight.DaylightConfig(
        cmyStrength         = 0.75f,
        highlightDesatAmount= 0.42f,
        skinDesaturation    = 0.92f,
        skinWarmth          = 0.045f,
        greenSatBoost       = 1.10f,
        greenHueShift       = 6f,
        skyShift            = 0.76f
    )
    // Landscape: verdes y cielo más vivos, menos énfasis en piel
    val daylightLandscape = KodakPortra400Daylight.DaylightConfig(
        greenSatBoost       = 1.18f,
        greenHueShift       = 9f,
        skyShift            = 0.70f,
        cmyStrength         = 0.70f,
        skinDesaturation    = 0.95f
    )
    val tungstenStandard  = KodakPortra400Tungsten.TungstenConfig()
    val tungstenPortrait  = KodakPortra400Tungsten.TungstenConfig(wbCorrection=0.55f,skinTungstenWarm=0.05f,shadowBlueB=0.07f)
    val tungstenNight     = KodakPortra400Tungsten.TungstenConfig(wbCorrection=0.45f,shadowBlueR=-0.06f,shadowBlueB=0.08f,warmLightBoost=1.25f,bloomOpacity=0.08f,bloomThreshold=0.58f)
}