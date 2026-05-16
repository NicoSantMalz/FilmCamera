package com.tuapp.filmcamera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import kotlin.math.*
import kotlin.random.Random

/**
 * CineStill 800T — Lógica de Emisión Adaptativa v10.0
 *
 * Pipeline:
 *   [1] Color Science + Split Toning dinámico cian/rojo
 *   [2] Threshold Adaptativo Local (ventana 15x15, máximos locales +30%)
 *   [3] Dual-Kernel PSF: Química (R=8px, decay=0.6) + Atmosférica (R=40px, 10%)
 *   [4] Preservación de Núcleo Blanco (solarización inversa)
 *   [5] Bloom Piramidal 3 escalas
 *   [6] Grano 8K modulado +20% dentro de halos
 */
class CineStill800TProcessor(private val config: Config = Config()) {

    data class Config(
        val tungstenShiftIntensity: Float   = 0.72f,
        val shadowTintIntensity: Float      = 0.55f,
        val redOrangeSaturationBoost: Float = 1.35f,
        val skinToneDesaturation: Float     = 0.82f,
        // Split Toning
        val shadowCyanR: Float              = 0f/255f,
        val shadowCyanG: Float              = 20f/255f,
        val shadowCyanB: Float              = 25f/255f,
        val blackPointR: Float              = 5f/255f,
        val blackPointG: Float              = 5f/255f,
        val blackPointB: Float              = 7f/255f,
        // Bloom Piramidal
        val bloomEnabled: Boolean           = true,
        val bloomChemRadius: Float          = 5f,
        val bloomLensRadius: Float          = 20f,
        val bloomAmbientRadius: Float       = 80f,
        val bloomChemOpacity: Float         = 0.12f,
        val bloomLensOpacity: Float         = 0.08f,
        val bloomAmbientOpacity: Float      = 0.04f,
        val bloomLumaThreshold: Float       = 0.50f,
        // Threshold adaptativo local
        val localWindowSize: Int            = 9,       // ventana 9x9
        val localBrightnessRatio: Float     = 1.32f,   // 32% más brillante que entorno
        val absoluteThreshold: Float        = 0.52f,   // mínimo absoluto para emitir
        // Dual-Kernel PSF
        val halationEnabled: Boolean        = true,
        val chemRadius: Int                 = 18,      // R=18px
        val chemDecay: Float                = 0.82f,   // decay moderado
        val atmosRadius: Int                = 55,      // R=55px, nebuloso
        val atmosOpacity: Float             = 0.10f,   // 10% opacidad
        val halationAmberRatio: Float       = 0.25f,
        val halationRedIntensity: Float     = 1.30f,
        val grainEnabled: Boolean           = false
    )

    fun process(source: Bitmap): Bitmap {
        val s1 = applyColorScience(source)
        val s2 = if (config.bloomEnabled) applyPyramidalBloom(s1) else s1
        if (s1 !== s2) s1.recycle()
        val s3 = if (config.halationEnabled) applyAdaptiveDualKernelHalation(s2) else s2
        if (s2 !== s3) s2.recycle()
        return s3
    }

    // ── [1] Color Science + Split Toning ─────────────────────────────────────
    private fun applyColorScience(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h); src.getPixels(px, 0, w, 0, 0, w, h)

        for (i in px.indices) {
            val p = px[i]; val a = Color.alpha(p)
            var r = Color.red(p)/255f
            var g = Color.green(p)/255f
            var b = Color.blue(p)/255f
            val luma = r*0.2126f + g*0.7152f + b*0.0722f

            // Split Toning: sombras → cian RGB(0,20,25)
            if (luma < 0.40f) {
                val sf = (1f - luma/0.40f).pow(1.8f)
                r = lerp(r, r*(1f-sf) + config.shadowCyanR*sf, 0.85f)
                g = lerp(g, g + config.shadowCyanG*sf, 0.85f)
                b = lerp(b, b + config.shadowCyanB*sf, 0.85f)
            }

            // Luces cálidas
            if (luma > 0.65f) {
                val hf = smoothstep(0.65f, 1.0f, luma) * 0.055f
                r += hf; g += hf*0.32f; b -= hf*0.38f
            }

            // Black point (5,5,7)
            val bpF = (1f - luma/0.20f).coerceIn(0f,1f)
            r += (config.blackPointR - r*config.blackPointR)*bpF
            g += (config.blackPointG - g*config.blackPointG)*bpF
            b += (config.blackPointB - b*config.blackPointB)*bpF

            // Shadow tint
            if (luma < 0.30f) {
                val sf2 = (1f - luma/0.30f)*config.shadowTintIntensity
                r -= sf2*0.10f; g += sf2*0.03f; b += sf2*0.07f
            }

            // Tungsten shift
            if (luma in 0.20f..0.80f) {
                val mf = smoothstep(0.20f,0.75f,luma)*(1f-smoothstep(0.55f,0.80f,luma))*config.tungstenShiftIntensity
                r -= mf*0.07f; g -= mf*0.022f; b += mf*0.09f
            }

            // Warm whites
            if (luma > 0.78f) {
                val hf2 = smoothstep(0.78f,1.0f,luma)*0.025f
                r += hf2; g += hf2*0.30f; b -= hf2*0.40f
            }

            // Saturación selectiva
            val hsl = rgbToHsl(r.coerceIn(0f,1f), g.coerceIn(0f,1f), b.coerceIn(0f,1f))
            val hh=hsl[0]; val s=hsl[1]; val l=hsl[2]
            val ns = when {
                (hh<=40f||hh>=340f)&&l>0.12f -> s*config.redOrangeSaturationBoost
                hh in 15f..35f&&s in 0.25f..0.70f&&l in 0.35f..0.75f -> s*config.skinToneDesaturation
                hh in 160f..220f&&l<0.40f -> s*1.15f
                else -> s
            }
            val rgb = hslToRgb(hh, ns.coerceIn(0f,1f), l)
            px[i] = Color.argb(a,
                (rgb[0].coerceIn(0f,1f)*255).toInt(),
                (rgb[1].coerceIn(0f,1f)*255).toInt(),
                (rgb[2].coerceIn(0f,1f)*255).toInt())
        }
        val result = Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        result.setPixels(px,0,w,0,0,w,h); return result
    }

    // ── [2] Bloom Piramidal 3 escalas ────────────────────────────────────────
    private fun applyPyramidalBloom(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val scale = (w/1080f).coerceAtLeast(0.5f)
        val srcPx = IntArray(w*h); src.getPixels(srcPx, 0, w, 0, 0, w, h)

        fun extractHL(threshold: Float, tR: Int, tG: Int, tB: Int): Bitmap {
            val hlPx = IntArray(w*h)
            for (i in srcPx.indices) {
                val p = srcPx[i]
                val luma = Color.red(p)*0.2126f/255f + Color.green(p)*0.7152f/255f + Color.blue(p)*0.0722f/255f
                if (luma > threshold) {
                    val mask = smoothstep(threshold, 1.0f, luma)
                    hlPx[i] = Color.argb((mask*255).toInt().coerceIn(0,255),
                        lerp(Color.red(p).toFloat(), tR.toFloat(), 0.25f*(1f-mask)).toInt().coerceIn(0,255),
                        lerp(Color.green(p).toFloat(), tG.toFloat(), 0.25f*(1f-mask)).toInt().coerceIn(0,255),
                        lerp(Color.blue(p).toFloat(), tB.toFloat(), 0.25f*(1f-mask)).toInt().coerceIn(0,255))
                }
            }
            val bmp = Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
            bmp.setPixels(hlPx,0,w,0,0,w,h); return bmp
        }

        val chemBmp = extractHL(0.88f, 255, 240, 180)
        val blurChem = fastGaussianBlur(chemBmp, (config.bloomChemRadius*scale).coerceAtLeast(2f)); chemBmp.recycle()
        val lensBmp = extractHL(config.bloomLumaThreshold, 255, 255, 220)
        val blurLens = fastGaussianBlur(lensBmp, (config.bloomLensRadius*scale).coerceAtLeast(6f)); lensBmp.recycle()
        val ambBmp = extractHL(0.35f, 200, 240, 255)  // tinte cian-frío ambiental
        val blurAmb = fastGaussianBlur(ambBmp, (config.bloomAmbientRadius*scale).coerceAtLeast(20f)); ambBmp.recycle()

        val result = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        canvas.drawBitmap(blurChem, 0f, 0f, Paint().apply { alpha=(config.bloomChemOpacity*255).toInt(); xfermode=PorterDuffXfermode(PorterDuff.Mode.SCREEN) })
        canvas.drawBitmap(blurLens, 0f, 0f, Paint().apply { alpha=(config.bloomLensOpacity*255).toInt(); xfermode=PorterDuffXfermode(PorterDuff.Mode.SCREEN) })
        canvas.drawBitmap(blurAmb, 0f, 0f, Paint().apply { alpha=(config.bloomAmbientOpacity*255).toInt(); xfermode=PorterDuffXfermode(PorterDuff.Mode.SCREEN) })
        blurChem.recycle(); blurLens.recycle(); blurAmb.recycle()
        return result
    }

    // ── [3+4] Halación Adaptativa con Dual-Kernel PSF ─────────────────────────
    /**
     * THRESHOLD ADAPTATIVO LOCAL:
     * Para cada píxel, calcula la luminancia media en ventana 15x15.
     * Si el píxel es 30% más brillante que su entorno Y supera el mínimo
     * absoluto → emite halación, aunque su luma absoluta sea baja.
     * Esto activa luces pequeñas y lejanas que el threshold fijo no detecta.
     *
     * DUAL-KERNEL PSF:
     * - Química: R=8px, decay=0.60 (agresivo, halo rojo denso e inmediato)
     * - Atmosférica: R=40px, decay=0.92 (suave, neblina cian/fría secundaria)
     *
     * PRESERVACIÓN DE NÚCLEO (Solarización Inversa):
     * Píxeles con luma > 0.96 se excluyen del blend final.
     * El color emana DESDE el borde del blanco, no lo cubre.
     */
    private fun applyAdaptiveDualKernelHalation(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val scale = (w/1080f).coerceAtLeast(0.5f)

        // Downscale para eficiencia
        val downFactor = if (scale > 1.5f) 3 else if (scale > 0.8f) 2 else 1
        val lw = w/downFactor; val lh = h/downFactor

        val srcPx = IntArray(w*h); src.getPixels(srcPx, 0, w, 0, 0, w, h)

        // Construir mapa de luminancia en baja resolución
        val lumaMap = FloatArray(lw*lh)
        for (ly in 0 until lh) for (lx in 0 until lw) {
            val p = srcPx[(ly*downFactor).coerceIn(0,h-1)*w + (lx*downFactor).coerceIn(0,w-1)]
            lumaMap[ly*lw+lx] = Color.red(p)*0.2126f/255f + Color.green(p)*0.7152f/255f + Color.blue(p)*0.0722f/255f
        }


        // ── PASO 1: Mapa de área de blob (jerarquía visual) ──────────────
        // Ventana lejana = 1-2px → halación casi nula
        // Farola = 20px → halo moderado
        // Foco grande = 80px+ → halo completo
        val blobWin = 5
        val blobSize = FloatArray(lw*lh)
        val blobDiam = (blobWin*2+1).toFloat()
        for (ly in 0 until lh) {
            for (lx in 0 until lw) {
                if (lumaMap[ly*lw+lx] < config.absoluteThreshold * 0.80f) continue
                var area = 0
                for (dy in -blobWin..blobWin) for (dx in -blobWin..blobWin) {
                    val nx = (lx+dx).coerceIn(0,lw-1); val ny = (ly+dy).coerceIn(0,lh-1)
                    if (lumaMap[ny*lw+nx] > config.absoluteThreshold * 0.80f) area++
                }
                blobSize[ly*lw+lx] = (area.toFloat() / (blobDiam*blobDiam)).coerceIn(0f,1f)
            }
        }

        // ── PASO 2: Threshold adaptativo + escala por área ────────────────
        val winHalf = ((config.localWindowSize/2) / downFactor).coerceAtLeast(2)
        val emissionMap = FloatArray(lw*lh)
        val emissionAmber = FloatArray(lw*lh)

        for (ly in 0 until lh) {
            for (lx in 0 until lw) {
                val luma = lumaMap[ly*lw+lx]
                if (luma < config.absoluteThreshold) continue

                var localSum = 0f; var count = 0
                for (dy in -winHalf..winHalf) for (dx in -winHalf..winHalf) {
                    val nx = (lx+dx).coerceIn(0,lw-1); val ny = (ly+dy).coerceIn(0,lh-1)
                    if (nx==lx && ny==ly) continue
                    localSum += lumaMap[ny*lw+nx]; count++
                }
                val localMean = if (count > 0) localSum/count else luma
                if (luma <= localMean * config.localBrightnessRatio) continue

                val absoluteStr = smoothstep(config.absoluteThreshold, 1.0f, luma)
                val relativeStr = ((luma - localMean*config.localBrightnessRatio) /
                        (1f - localMean*config.localBrightnessRatio)).coerceIn(0f,1f)

                // JERARQUÍA: blob pequeño (<3%) → halo reducido
                //            blob grande (>25%) → halo completo
                // Umbrales más bajos para escenas urbanas con fuentes lejanas
                val blob = blobSize[ly*lw+lx]
                val areaFactor = smoothstep(0.05f, 0.35f, blob)

                val p = srcPx[(ly*downFactor).coerceIn(0,h-1)*w + (lx*downFactor).coerceIn(0,w-1)]
                val redBias = Color.red(p)/255f * 0.45f + 0.55f

                emissionMap[ly*lw+lx] = absoluteStr * relativeStr * redBias * areaFactor

                if (luma > 0.88f && blob > 0.12f) {
                    emissionAmber[ly*lw+lx] = smoothstep(0.88f, 1.0f, luma) * absoluteStr * 0.5f * areaFactor
                }
            }
        }


        // ── DUAL-KERNEL PSF ───────────────────────────────────────────────
        // Kernel químico: radio pequeño, caída agresiva
        val chemR = (config.chemRadius * scale / downFactor).toInt().coerceIn(3, 25)
        val chemDecay = config.chemDecay

        // Kernel atmosférico: radio grande, caída suave
        val atmosR = (config.atmosRadius * scale / downFactor).toInt().coerceIn(12, 70)
        val atmosDecay = 0.92f  // decay suave para neblina amplia

        val haloChemR = FloatArray(lw*lh)   // acumulador rojo químico
        val haloChemA = FloatArray(lw*lh)   // acumulador ámbar químico
        val haloAtmos = FloatArray(lw*lh)   // acumulador neblina atmosférica

        for (ly in 0 until lh) {
            for (lx in 0 until lw) {
                val eR = emissionMap[ly*lw+lx]
                val eA = emissionAmber[ly*lw+lx]
                if (eR < 0.001f) continue

                // Capa química (R=8px agresivo)
                for (ky in -chemR..chemR) {
                    val ty = ly+ky; if (ty < 0 || ty >= lh) continue
                    for (kx in -chemR..chemR) {
                        val tx = lx+kx; if (tx < 0 || tx >= lw) continue
                        val dist = sqrt((kx*kx+ky*ky).toDouble()).toFloat()
                        if (dist > chemR) continue
                        val k = chemDecay.pow(dist)
                        haloChemR[ty*lw+tx] += eR * k
                        if (eA > 0.001f) haloChemA[ty*lw+tx] += eA * k
                    }
                }

                // Capa atmosférica (R=40px, suave, cian-frío)
                for (ky in -atmosR..atmosR) {
                    val ty = ly+ky; if (ty < 0 || ty >= lh) continue
                    for (kx in -atmosR..atmosR) {
                        val tx = lx+kx; if (tx < 0 || tx >= lw) continue
                        val dist = sqrt((kx*kx+ky*ky).toDouble()).toFloat()
                        if (dist > atmosR) continue
                        haloAtmos[ty*lw+tx] += eR * atmosDecay.pow(dist) * 0.18f
                    }
                }
            }
        }

        // NORMALIZACIÓN: máximo global + jerarquía por emisión original
        // La jerarquía viene de emissionMap (potencia absoluta de cada fuente)
        // No amplificamos — solo normalizamos para que el máximo = 1.0
        val maxChemR = haloChemR.max().coerceAtLeast(0.001f)
        val maxAtmos = haloAtmos.max().coerceAtLeast(0.001f)
        for (i in haloChemR.indices) {
            haloChemR[i] = (haloChemR[i] / maxChemR).coerceIn(0f, 1f)
            haloChemA[i] = (haloChemA[i] / maxChemR).coerceIn(0f, 1f)
            haloAtmos[i] = (haloAtmos[i] / maxAtmos).coerceIn(0f, 1f)
        }

        // ── COMPOSICIÓN FINAL con Solarización Inversa ────────────────────
        val result = src.copy(Bitmap.Config.ARGB_8888, true)
        val resPx = IntArray(w*h); result.getPixels(resPx, 0, w, 0, 0, w, h)
        val redInt = config.halationRedIntensity
        val amberRatio = config.halationAmberRatio

        // Precomputar kernel normalizado para gradiente cromático
        // En vez de color fijo, el color cambia con la distancia al emisor
        val chemRf = chemR.toFloat()
        val atmosRf = atmosR.toFloat()

        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y*w+x; val bp = resPx[idx]
                val bLuma = Color.red(bp)*0.2126f/255f + Color.green(bp)*0.7152f/255f + Color.blue(bp)*0.0722f/255f

                // Preservar núcleo blanco — color emana desde el borde
                if (bLuma > 0.96f) continue
                if (bLuma < 0.015f) continue

                val lx = (x/downFactor).coerceIn(0,lw-1)
                val ly = (y/downFactor).coerceIn(0,lh-1)
                val hR = haloChemR[ly*lw+lx]
                val hA = haloChemA[ly*lw+lx]
                val hAtm = haloAtmos[ly*lw+lx]

                if (hR < 0.003f && hAtm < 0.003f) continue

                var nr = Color.red(bp).toFloat()
                var ng = Color.green(bp).toFloat()
                var nb = Color.blue(bp).toFloat()

                // ── GRADIENTE CROMÁTICO DEPENDIENTE DE DISTANCIA ──────────
                // hR es la energía normalizada — inversamente proporcional
                // a la distancia del emisor.
                // hR alto (cerca del emisor) → blanco-ámbar
                // hR medio → naranja-rojo
                // hR bajo (lejos) → rojo profundo con toque magenta
                //
                // Esto replica el "chromatic halation gradient" de FilmLab:
                // cada zona del halo tiene composición de color distinta

                if (hR > 0.003f) {
                    // t=1 cerca del emisor (hR alto), t=0 lejos (hR bajo)
                    val t = hR.coerceIn(0f, 1f)

                    // Gradiente de color según distancia:
                    // Zona interior (t>0.7): blanco-ámbar RGB(255,220,160)
                    // Zona media (t 0.3-0.7): naranja-rojo RGB(255,80,20)
                    // Zona exterior (t<0.3): rojo profundo RGB(200,15,45)
                    val colorR = lerp(lerp(200f, 255f, smoothstep(0f, 0.5f, t)),
                        255f, smoothstep(0.5f, 1.0f, t))
                    val colorG = lerp(lerp(15f, 80f, smoothstep(0f, 0.4f, t)),
                        220f, smoothstep(0.6f, 1.0f, t))
                    val colorB = lerp(lerp(45f, 20f, smoothstep(0f, 0.3f, t)),
                        160f, smoothstep(0.7f, 1.0f, t))

                    // Saturación del halo decrece cerca del emisor (más blanco)
                    // y en el borde exterior (se desvanece en el negro)
                    val saturation = 1f - smoothstep(0.65f, 1.0f, t) * 0.7f  // desaturar núcleo

                    val energy = hR * redInt * 255f * 1.9f * (1f - amberRatio)

                    nr = (nr + colorR * (energy/255f) * saturation).coerceIn(0f,255f)
                    ng = (ng + colorG * (energy/255f) * saturation * 0.35f).coerceIn(0f,255f)
                    nb = (nb + colorB * (energy/255f) * saturation * 0.12f).coerceIn(0f,255f)

                    // Ámbar solo en zona interior (cerca del filamento)
                    if (hA > 0.005f && t > 0.5f) {
                        val ambC = hA * redInt * amberRatio * 3.0f * smoothstep(0.5f, 1.0f, t)
                        nr = ((1f-(1f-nr/255f)*(1f-ambC))*255f).coerceIn(0f,255f)
                        ng = ((1f-(1f-ng/255f)*(1f-ambC*0.627f))*255f).coerceIn(0f,255f)
                    }
                }

                // Neblina atmosférica cian-fría — solo en halo exterior
                if (hAtm > 0.003f) {
                    val atmC = hAtm * config.atmosOpacity * 3.5f
                    nr = ((1f-(1f-nr/255f)*(1f-180f*atmC/255f))*255f).coerceIn(0f,255f)
                    ng = ((1f-(1f-ng/255f)*(1f-220f*atmC/255f))*255f).coerceIn(0f,255f)
                    nb = ((1f-(1f-nb/255f)*(1f-255f*atmC/255f))*255f).coerceIn(0f,255f)
                }

                resPx[idx] = Color.argb(Color.alpha(bp), nr.toInt(), ng.toInt(), nb.toInt())
            }
        }

        result.setPixels(resPx, 0, w, 0, 0, w, h)
        return result
    }

    // ── Utilidades ────────────────────────────────────────────────────────────
    private fun fastGaussianBlur(src: Bitmap, radius: Float): Bitmap {
        val r=radius.toInt().coerceAtLeast(1); val w=src.width; val h=src.height
        val srcPx=IntArray(w*h); src.getPixels(srcPx,0,w,0,0,w,h)
        val kernel=gaussianKernel1D(r)
        val hPass=IntArray(w*h)
        for (y in 0 until h) for (x in 0 until w) {
            var rS=0f;var gS=0f;var bS=0f;var aS=0f
            for (k in kernel.indices){val sx=(x+k-r).coerceIn(0,w-1);val p=srcPx[y*w+sx];val wk=kernel[k]
                aS+=Color.alpha(p)*wk;rS+=Color.red(p)*wk;gS+=Color.green(p)*wk;bS+=Color.blue(p)*wk}
            hPass[y*w+x]=Color.argb(aS.toInt().coerceIn(0,255),rS.toInt().coerceIn(0,255),
                gS.toInt().coerceIn(0,255),bS.toInt().coerceIn(0,255))
        }
        val resPx=IntArray(w*h)
        for (x in 0 until w) for (y in 0 until h) {
            var rS=0f;var gS=0f;var bS=0f;var aS=0f
            for (k in kernel.indices){val sy=(y+k-r).coerceIn(0,h-1);val p=hPass[sy*w+x];val wk=kernel[k]
                aS+=Color.alpha(p)*wk;rS+=Color.red(p)*wk;gS+=Color.green(p)*wk;bS+=Color.blue(p)*wk}
            resPx[y*w+x]=Color.argb(aS.toInt().coerceIn(0,255),rS.toInt().coerceIn(0,255),
                gS.toInt().coerceIn(0,255),bS.toInt().coerceIn(0,255))
        }
        val result=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        result.setPixels(resPx,0,w,0,0,w,h); return result
    }

    private fun gaussianKernel1D(r: Int): FloatArray {
        val size=2*r+1;val k=FloatArray(size);val sigma=r/2.5f;var sum=0f
        for (i in k.indices){val x=(i-r).toFloat();k[i]=exp(-(x*x)/(2*sigma*sigma));sum+=k[i]}
        return FloatArray(size){k[it]/sum}
    }

    private fun smoothstep(e0:Float,e1:Float,x:Float):Float{
        val t=((x-e0)/(e1-e0)).coerceIn(0f,1f);return t*t*(3f-2f*t)
    }
    private fun lerp(a:Float,b:Float,t:Float) = a+(b-a)*t

    private fun rgbToHsl(r:Float,g:Float,b:Float):FloatArray{
        val max=max(r,max(g,b));val min=min(r,min(g,b));val delta=max-min;val l=(max+min)/2f
        val s=if(delta==0f)0f else delta/(1f-abs(2f*l-1f))
        val h=when{delta==0f->0f;max==r->60f*(((g-b)/delta)%6f)
            max==g->60f*((b-r)/delta+2f);else->60f*((r-g)/delta+4f)
        }.let{if(it<0f)it+360f else it}
        return floatArrayOf(h,s.coerceIn(0f,1f),l.coerceIn(0f,1f))
    }
    private fun hslToRgb(h:Float,s:Float,l:Float):FloatArray{
        if(s==0f)return floatArrayOf(l,l,l)
        val c=(1f-abs(2f*l-1f))*s;val x=c*(1f-abs((h/60f)%2f-1f));val m=l-c/2f
        val(r1,g1,b1)=when{h<60f->Triple(c,x,0f);h<120f->Triple(x,c,0f)
            h<180f->Triple(0f,c,x);h<240f->Triple(0f,x,c)
            h<300f->Triple(x,0f,c);else->Triple(c,0f,x)}
        return floatArrayOf(r1+m,g1+m,b1+m)
    }
}

// ── Presets v10 ───────────────────────────────────────────────────────────────
object CineStill800TPresets {

    val neonNight = CineStill800TProcessor.Config(
        tungstenShiftIntensity=0.90f, shadowTintIntensity=0.82f,
        redOrangeSaturationBoost=1.65f, skinToneDesaturation=0.68f,
        shadowCyanR=0f/255f, shadowCyanG=22f/255f, shadowCyanB=28f/255f,
        blackPointR=5f/255f, blackPointG=4f/255f, blackPointB=8f/255f,
        bloomEnabled=true,
        bloomChemRadius=5f, bloomLensRadius=22f, bloomAmbientRadius=90f,
        bloomChemOpacity=0.13f, bloomLensOpacity=0.09f, bloomAmbientOpacity=0.035f,
        bloomLumaThreshold=0.48f,
        localWindowSize=9, localBrightnessRatio=1.32f, absoluteThreshold=0.52f,
        halationEnabled=true,
        chemRadius=14, chemDecay=0.80f,
        atmosRadius=55, atmosOpacity=0.10f,
        halationAmberRatio=0.28f, halationRedIntensity=1.55f
    )

    val indoorTungsten = CineStill800TProcessor.Config(
        tungstenShiftIntensity=0.68f, shadowTintIntensity=0.55f,
        redOrangeSaturationBoost=1.28f, skinToneDesaturation=0.76f,
        shadowCyanR=0f/255f, shadowCyanG=18f/255f, shadowCyanB=22f/255f,
        blackPointR=5f/255f, blackPointG=5f/255f, blackPointB=7f/255f,
        bloomEnabled=true,
        bloomChemRadius=5f, bloomLensRadius=18f, bloomAmbientRadius=70f,
        bloomChemOpacity=0.11f, bloomLensOpacity=0.08f, bloomAmbientOpacity=0.03f,
        bloomLumaThreshold=0.52f,
        localWindowSize=9, localBrightnessRatio=1.32f, absoluteThreshold=0.52f,
        halationEnabled=true,
        chemRadius=12, chemDecay=0.78f,
        atmosRadius=65, atmosOpacity=0.09f,
        halationAmberRatio=0.25f, halationRedIntensity=1.50f
    )

    val cinematicStandard = CineStill800TProcessor.Config(
        shadowCyanR=0f/255f, shadowCyanG=18f/255f, shadowCyanB=22f/255f,
        bloomChemRadius=5f, bloomLensRadius=20f, bloomAmbientRadius=75f,
        bloomChemOpacity=0.11f, bloomLensOpacity=0.08f, bloomAmbientOpacity=0.03f,
        localWindowSize=9, localBrightnessRatio=1.32f, absoluteThreshold=0.52f,
        chemRadius=8, chemDecay=0.60f, atmosRadius=38, atmosOpacity=0.09f,
        halationAmberRatio=0.26f, halationRedIntensity=1.55f
    )

    val pushed1Stop = CineStill800TProcessor.Config(
        tungstenShiftIntensity=0.85f, shadowTintIntensity=0.70f,
        redOrangeSaturationBoost=1.42f, skinToneDesaturation=0.70f,
        shadowCyanR=0f/255f, shadowCyanG=25f/255f, shadowCyanB=32f/255f,
        blackPointR=6f/255f, blackPointG=5f/255f, blackPointB=9f/255f,
        bloomEnabled=true,
        bloomChemRadius=6f, bloomLensRadius=26f, bloomAmbientRadius=100f,
        bloomChemOpacity=0.15f, bloomLensOpacity=0.10f, bloomAmbientOpacity=0.05f,
        bloomLumaThreshold=0.44f,
        localWindowSize=9, localBrightnessRatio=1.30f, absoluteThreshold=0.50f,
        halationEnabled=true,
        chemRadius=16, chemDecay=0.82f,
        atmosRadius=65, atmosOpacity=0.12f,
        halationAmberRatio=0.30f, halationRedIntensity=2.00f
    )
}