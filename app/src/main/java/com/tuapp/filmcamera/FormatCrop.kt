package com.tuapp.filmcamera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint

data class FilmFormat(
    val name: String,
    val label: String,
    val ratio: Float,
    val borderPercent: Float
)

val filmFormats = listOf(
    FilmFormat("none", "Original", 0f,           0f),
    FilmFormat("35mm", "35mm 3:2", 3f / 2f,      0.03f),
    FilmFormat("sq",   "Cuadrado", 1f,            0.03f),
    FilmFormat("6x7",  "120mm 6×7", 6f / 7f,     0.04f),
    FilmFormat("645",  "120mm 6×4.5", 6f / 4.5f, 0.04f)
)

data class BorderColor(
    val name: String,
    val color: Int
)

val borderColors = listOf(
    BorderColor("Negro",       android.graphics.Color.BLACK),
    BorderColor("Blanco",      android.graphics.Color.WHITE),
    BorderColor("Crema",       android.graphics.Color.rgb(245, 235, 210)),
    BorderColor("Transparente",android.graphics.Color.TRANSPARENT)
)

object FormatCrop {

    fun apply(
        bitmap: Bitmap,
        format: FilmFormat,
        borderColor: Int = android.graphics.Color.BLACK,
        offsetX: Float = 0.5f,
        offsetY: Float = 0.5f
    ): Bitmap {
        if (format.name == "none") return bitmap

        val srcW = bitmap.width
        val srcH = bitmap.height
        val ratio = format.ratio

        // Calcular recorte
        val targetW: Int
        val targetH: Int

        if (srcW.toFloat() / srcH > ratio) {
            targetH = srcH
            targetW = (srcH * ratio).toInt()
        } else {
            targetW = srcW
            targetH = (srcW / ratio).toInt()
        }

        // Offset controlado por el usuario (0..1) — posición del recorte
        val maxX = srcW - targetW
        val maxY = srcH - targetH
        val x = (maxX * offsetX).toInt().coerceIn(0, maxX)
        val y = (maxY * offsetY).toInt().coerceIn(0, maxY)

        val cropped = Bitmap.createBitmap(bitmap, x, y, targetW, targetH)

        return if (format.borderPercent > 0f && borderColor != android.graphics.Color.TRANSPARENT) {
            addFilmBorder(cropped, format.borderPercent, borderColor)
        } else {
            cropped
        }
    }

    private fun addFilmBorder(bitmap: Bitmap, borderPercent: Float, color: Int): Bitmap {
        val borderW = (bitmap.width * borderPercent).toInt().coerceAtLeast(1)
        val borderH = (bitmap.height * borderPercent).toInt().coerceAtLeast(1)

        val totalW = bitmap.width + borderW * 2
        val totalH = bitmap.height + borderH * 2

        val result = Bitmap.createBitmap(totalW, totalH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint()

        canvas.drawColor(color)
        canvas.drawBitmap(bitmap, borderW.toFloat(), borderH.toFloat(), paint)

        return result
    }
}