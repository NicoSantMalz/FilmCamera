package com.tuapp.filmcamera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint

object FilmFilters {

    fun getKodakGold200(intensity: Float = 1f): ColorMatrix {
        return interpolate(ColorMatrix(), ColorMatrix(floatArrayOf(
            1.04f,  0.01f,  0.00f, 0f,  4f,
            0.00f,  1.01f,  0.00f, 0f,  1f,
            -0.01f, -0.02f,  0.92f, 0f, -2f,
            0.00f,  0.00f,  0.00f, 1f,  0f
        )), intensity)
    }

    fun getIlfordHP5(intensity: Float = 1f): ColorMatrix {
        return interpolate(ColorMatrix(), ColorMatrix(floatArrayOf(
            0.299f, 0.587f, 0.114f, 0f, 0f,
            0.299f, 0.587f, 0.114f, 0f, 0f,
            0.299f, 0.587f, 0.114f, 0f, 0f,
            0.000f, 0.000f, 0.000f, 1f, 0f
        )), intensity)
    }

    fun getKodakPortra400(intensity: Float = 1f): ColorMatrix {
        return interpolate(ColorMatrix(), ColorMatrix(floatArrayOf(
            1.02f,  0.01f,  0.00f, 0f,  2f,
            0.00f,  1.00f,  0.00f, 0f,  1f,
            0.00f,  0.00f,  0.96f, 0f, -1f,
            0.00f,  0.00f,  0.00f, 1f,  0f
        )), intensity)
    }

    fun getFujiVelvia50(intensity: Float = 1f): ColorMatrix {
        return interpolate(ColorMatrix(), ColorMatrix(floatArrayOf(
            1.08f,  0.00f,  0.00f, 0f, -3f,
            0.00f,  1.12f,  0.00f, 0f,  2f,
            0.00f,  0.00f,  1.06f, 0f,  3f,
            0.00f,  0.00f,  0.00f, 1f,  0f
        )), intensity)
    }

    private fun interpolate(from: ColorMatrix, to: ColorMatrix, t: Float): ColorMatrix {
        val f = from.array; val toA = to.array
        return ColorMatrix(FloatArray(20) { i -> f[i] + (toA[i] - f[i]) * t })
    }

    fun getMatrix(rollName: String, intensity: Float = 1f): ColorMatrix {
        return when (rollName) {
            "KG 200"  -> getKodakGold200(intensity)
            "HP5 400" -> getIlfordHP5(intensity)
            "PT 400"  -> getKodakPortra400(intensity)
            "VV 50"   -> getFujiVelvia50(intensity)
            "CS 800T" -> ColorMatrix()
            else      -> ColorMatrix()
        }
    }

    fun getCineStillConfig(filterIntensity: Float, grainIntensity: Float): CineStill800TProcessor.Config {
        val base = when {
            filterIntensity < 0.33f -> CineStill800TPresets.indoorTungsten
            filterIntensity < 0.66f -> CineStill800TPresets.cinematicStandard
            else                    -> CineStill800TPresets.neonNight
        }
        return base.copy(grainEnabled = false)
    }

    @Suppress("UNUSED_PARAMETER")
    fun applyFilmFilter(
        bitmap: Bitmap,
        rollName: String,
        filterIntensity: Float,
        grainIntensity: Float = 1f,
        context: Context? = null,
        portraPreset: PortraPreset = PortraPreset.DAYLIGHT,
        textureCrops: List<Bitmap?>? = null
    ): Bitmap {

        android.util.Log.d("FilmFilters", "applyFilmFilter: roll=$rollName filter=$filterIntensity grain=$grainIntensity preset=$portraPreset")

        // ── Paso 1: Filtro de color ────────────────────────────────────────
        val colorFiltered: Bitmap = when (rollName) {
            "CS 800T" -> {
                val config = getCineStillConfig(filterIntensity, grainIntensity)
                CineStill800TProcessor(config).process(bitmap)
            }
            "PT 400" -> {
                // Usar preset seleccionado explícitamente por el usuario
                when (portraPreset) {
                    PortraPreset.TUNGSTEN -> {
                        val preset = KodakPortra400Presets.tungstenStandard
                        val config = scalePortraTungstenConfig(preset, filterIntensity)
                        KodakPortra400Tungsten(config).process(bitmap)
                    }
                    PortraPreset.OVERCAST -> {
                        // Overcast: Daylight con config conservadora (sin crema agresivo)
                        val preset = KodakPortra400Presets.daylightStandard
                        val config = scalePortraDaylightConfig(preset, filterIntensity)
                        KodakPortra400Daylight(config).process(bitmap)
                    }
                    PortraPreset.DAYLIGHT -> {
                        val preset = KodakPortra400Presets.daylightPortrait
                        val config = scalePortraDaylightConfig(preset, filterIntensity)
                        KodakPortra400Daylight(config).process(bitmap)
                    }
                }
            }
            else -> {
                val matrix = getMatrix(rollName, filterIntensity)
                applyColorMatrix(bitmap, matrix)
            }
        }

        // ── Paso 2: GrainProcessor Silver Halide (no para CS 800T) ────────
        val withGrain: Bitmap = if (rollName != "CS 800T" && grainIntensity > 0.01f) {
            val baseGrain = GrainProcessor.getBaseGrain(rollName)
            val finalGrain = (baseGrain * grainIntensity).coerceIn(0f, 1f)
            GrainProcessor.applyGrain(colorFiltered, finalGrain)
        } else {
            colorFiltered
        }

        // ── Paso 3: Grano C-41 físico para Portra 400 ─────────────────────
        val withC41Grain: Bitmap = if (rollName == "PT 400" && grainIntensity > 0.01f) {
            PortraGrainProcessor.apply(withGrain, grainIntensity)
        } else withGrain

        // ── Paso 4: Textura física 8K ──────────────────────────────────────
        // Usar textureCrops pre-cargados si están disponibles (evita re-leer assets)
        val withTexture: Bitmap = when {
            textureCrops != null && grainIntensity > 0.01f -> {
                val textureIntensity = when (rollName) {
                    "CS 800T" -> (grainIntensity * 1.40f).coerceIn(0f, 1f)
                    "HP5 400" -> (grainIntensity * 1.55f).coerceIn(0f, 1f)
                    "VV 50"   -> (grainIntensity * 0.75f).coerceIn(0f, 1f)
                    "PT 400"  -> (grainIntensity * 1.20f).coerceIn(0f, 1f)
                    else      -> (grainIntensity * 1.25f).coerceIn(0f, 1f)
                }
                val cleanliness = when (rollName) {
                    "CS 800T" -> 0.35f
                    "HP5 400" -> 0.25f
                    "VV 50"   -> 0.60f
                    "PT 400"  -> 0.35f
                    else      -> 0.40f
                }
                FilmTextureGrain.applyWithCrops(withC41Grain, textureCrops, textureIntensity, cleanliness)
            }
            context != null && grainIntensity > 0.01f -> {
                val textureIntensity = when (rollName) {
                    "CS 800T" -> (grainIntensity * 1.40f).coerceIn(0f, 1f)
                    "HP5 400" -> (grainIntensity * 1.55f).coerceIn(0f, 1f)
                    "VV 50"   -> (grainIntensity * 0.75f).coerceIn(0f, 1f)
                    "PT 400"  -> (grainIntensity * 1.20f).coerceIn(0f, 1f)
                    else      -> (grainIntensity * 1.25f).coerceIn(0f, 1f)
                }
                val cleanliness = when (rollName) {
                    "CS 800T" -> 0.35f
                    "HP5 400" -> 0.25f
                    "VV 50"   -> 0.60f
                    "PT 400"  -> 0.35f
                    else      -> 0.40f
                }
                FilmTextureGrain.apply(context, withC41Grain, textureIntensity, cleanliness, rollName)
            }
            else -> withC41Grain
        }

        return withTexture
    }

    private fun scalePortraDaylightConfig(
        base: KodakPortra400Daylight.DaylightConfig,
        intensity: Float
    ): KodakPortra400Daylight.DaylightConfig {
        val t = intensity.coerceIn(0f, 1f)
        return base.copy(
            shadowCyanR = base.shadowCyanR * t,
            shadowCyanG = base.shadowCyanG * t,
            shadowCyanB = base.shadowCyanB * t,
            highlightCreamR = base.highlightCreamR * t,
            highlightCreamG = base.highlightCreamG * t,
            highlightCreamB = base.highlightCreamB * t,
            greenSatBoost = 1f + (base.greenSatBoost - 1f) * t,
            skinWarmth = base.skinWarmth * t,
            cmyStrength = base.cmyStrength * t,
            bloomOpacity = base.bloomOpacity * t
        )
    }

    private fun scalePortraTungstenConfig(
        base: KodakPortra400Tungsten.TungstenConfig,
        intensity: Float
    ): KodakPortra400Tungsten.TungstenConfig {
        val t = intensity.coerceIn(0f, 1f)
        return base.copy(
            wbCorrection = base.wbCorrection * t,
            shadowBlueR = base.shadowBlueR * t,
            shadowBlueB = base.shadowBlueB * t,
            warmLightBoost = 1f + (base.warmLightBoost - 1f) * t,
            bloomOpacity = base.bloomOpacity * t
        )
    }

    private fun applyColorMatrix(bitmap: Bitmap, matrix: ColorMatrix): Bitmap {
        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(result)
        val paint = android.graphics.Paint().apply {
            colorFilter = android.graphics.ColorMatrixColorFilter(matrix)
        }
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return result
    }
}