package com.tuapp.filmcamera

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.pow

data class FilmRoll(
    val name: String,
    val iso: String,
    val tint: Color,
    val description: String
)

val filmRolls = listOf(
    FilmRoll("KG 200",  "ISO 200", Color(0xFFD4A017), "Cálido, dorado, piel perfecta"),
    FilmRoll("CS 800T", "ISO 800", Color(0xFF1A3A5C), "Azul nocturno, halos rojos"),
    FilmRoll("HP5 400", "ISO 400", Color(0xFF444444), "Blanco y negro clásico"),
    FilmRoll("PT 400",  "ISO 400", Color(0xFFC8A882), "Piel natural, colores suaves"),
    FilmRoll("VV 50",   "ISO 50",  Color(0xFF2D6A2D), "Colores saturados, paisajes")
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FilmCameraApp()
        }
    }
}

@Composable
fun FilmCameraApp() {
    GalleryEditorScreen(onBack = {})
}

@Composable
fun RollChip(roll: FilmRoll, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clickable { onClick() }
            .background(
                if (selected) roll.tint else Color(0xFF1A1A1A),
                RoundedCornerShape(20.dp)
            )
            .border(
                width = 1.dp,
                color = if (selected) roll.tint else Color(0xFF333333),
                shape = RoundedCornerShape(20.dp)
            )
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text = roll.name,
            color = if (selected) Color.White else Color.Gray,
            fontSize = 12.sp
        )
    }
}

fun isoLabel(value: Float): String {
    val iso = (50 * 2f.pow(value * 5)).toInt().coerceIn(50, 1600)
    return "ISO $iso"
}