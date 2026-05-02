package com.tuapp.filmcamera

import android.graphics.ColorMatrix

object FilmFilters {

    fun getKodakGold200(intensity: Float = 1f): ColorMatrix {
        return interpolate(ColorMatrix(), ColorMatrix(floatArrayOf(
            1.04f,  0.01f,  0.00f, 0f,  4f,
            0.00f,  1.01f,  0.00f, 0f,  1f,
            -0.01f, -0.02f,  0.92f, 0f, -2f,
            0.00f,  0.00f,  0.00f, 1f,  0f
        )), intensity)
    }

    fun getCineStill800T(intensity: Float = 1f): ColorMatrix {
        return interpolate(ColorMatrix(), ColorMatrix(floatArrayOf(
            0.92f,  0.00f,  0.02f, 0f, -4f,
            0.00f,  0.95f,  0.02f, 0f,  1f,
            0.02f,  0.00f,  1.12f, 0f,  6f,
            0.00f,  0.00f,  0.00f, 1f,  0f
        )), intensity)
    }

    fun getIlfordHP5(intensity: Float = 1f): ColorMatrix {
        val full = ColorMatrix(floatArrayOf(
            0.299f, 0.587f, 0.114f, 0f, 0f,
            0.299f, 0.587f, 0.114f, 0f, 0f,
            0.299f, 0.587f, 0.114f, 0f, 0f,
            0.000f, 0.000f, 0.000f, 1f, 0f
        ))
        return interpolate(ColorMatrix(), full, intensity)
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

    // Interpola entre identidad (sin filtro) y el filtro completo según intensity 0..1
    private fun interpolate(from: ColorMatrix, to: ColorMatrix, t: Float): ColorMatrix {
        val f = from.array
        val toA = to.array
        val result = FloatArray(20) { i -> f[i] + (toA[i] - f[i]) * t }
        return ColorMatrix(result)
    }

    fun getMatrix(rollName: String, intensity: Float = 1f): ColorMatrix {
        return when (rollName) {
            "KG 200"  -> getKodakGold200(intensity)
            "CS 800T" -> getCineStill800T(intensity)
            "HP5 400" -> getIlfordHP5(intensity)
            "PT 400"  -> getKodakPortra400(intensity)
            "VV 50"   -> getFujiVelvia50(intensity)
            else      -> ColorMatrix()
        }
    }
}