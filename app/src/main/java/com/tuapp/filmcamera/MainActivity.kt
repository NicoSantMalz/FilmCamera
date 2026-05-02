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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.pow
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState

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

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun FilmCameraApp() {
    var showGalleryEditor by remember { mutableStateOf(false) }
    var selectedRoll by remember { mutableStateOf(filmRolls[0]) }
    var photoSaved by remember { mutableStateOf(false) }
    var filterIntensity by remember { mutableStateOf(1f) }
    // grainIntensity = simulación ISO — escala amplitud Y tamaño del grano
    var grainIntensity by remember { mutableStateOf(GrainProcessor.getBaseGrain(filmRolls[0].name)) }
    val context = LocalContext.current

    val cameraPermissionState = rememberPermissionState(
        android.Manifest.permission.CAMERA
    )

    LaunchedEffect(Unit) {
        cameraPermissionState.launchPermissionRequest()
    }

    LaunchedEffect(photoSaved) {
        if (photoSaved) {
            kotlinx.coroutines.delay(2000)
            photoSaved = false
        }
    }

    if (showGalleryEditor) {
        GalleryEditorScreen(onBack = { showGalleryEditor = false })
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // Barra superior
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "FilmCamera",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = selectedRoll.iso,
                    color = Color.Gray,
                    fontSize = 14.sp
                )
            }

            // Visor
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (cameraPermissionState.status.isGranted) {
                    CameraPreview(
                        modifier = Modifier.fillMaxSize(),
                        rollName = selectedRoll.name,
                        filterIntensity = filterIntensity,
                        grainIntensity = grainIntensity
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF111111)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Se necesita permiso de cámara",
                            color = Color.Gray,
                            fontSize = 14.sp
                        )
                    }
                }

                Text(
                    text = selectedRoll.name,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(12.dp)
                )

                if (photoSaved) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 16.dp),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        Box(
                            modifier = Modifier
                                .background(
                                    Color.Black.copy(alpha = 0.7f),
                                    RoundedCornerShape(20.dp)
                                )
                                .padding(horizontal = 20.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = "Foto guardada",
                                color = Color.White,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }

            // Sliders
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                // Slider de filtro
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Filtro",
                        color = Color.Gray,
                        fontSize = 12.sp,
                        modifier = Modifier.width(48.dp)
                    )
                    Slider(
                        value = filterIntensity,
                        onValueChange = { filterIntensity = it },
                        valueRange = 0f..1f,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = selectedRoll.tint,
                            inactiveTrackColor = Color(0xFF333333)
                        )
                    )
                    Text(
                        text = "${(filterIntensity * 100).toInt()}%",
                        color = Color.Gray,
                        fontSize = 12.sp,
                        modifier = Modifier.width(36.dp)
                    )
                }

                // Slider ISO — escala amplitud Y tamaño de grano simultáneamente
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "ISO",
                        color = Color.Gray,
                        fontSize = 12.sp,
                        modifier = Modifier.width(48.dp)
                    )
                    Slider(
                        value = grainIntensity,
                        onValueChange = { grainIntensity = it },
                        valueRange = 0f..1f,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = selectedRoll.tint,
                            inactiveTrackColor = Color(0xFF333333)
                        )
                    )
                    // Mostrar ISO equivalente fotográfico
                    Text(
                        text = isoLabel(grainIntensity),
                        color = Color.Gray,
                        fontSize = 11.sp,
                        modifier = Modifier.width(44.dp)
                    )
                }
            }

            // Selector de rollos
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(horizontal = 20.dp)
            ) {
                items(filmRolls) { roll ->
                    RollChip(
                        roll = roll,
                        selected = roll == selectedRoll,
                        onClick = {
                            selectedRoll = roll
                            grainIntensity = GrainProcessor.getBaseGrain(roll.name)
                            filterIntensity = 1f
                        }
                    )
                }
            }

            // Botones
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(PaddingValues(start = 24.dp, end = 24.dp, bottom = 36.dp)),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .border(2.dp, Color.Gray, CircleShape)
                        .clickable { showGalleryEditor = true },
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "GAL", color = Color.Gray, fontSize = 10.sp)
                }

                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                        .border(3.dp, Color.White, CircleShape)
                        .clickable {
                            takePhoto(
                                context = context,
                                rollName = selectedRoll.name,
                                filterIntensity = filterIntensity,
                                grainIntensity = grainIntensity,
                                onSaved = { photoSaved = true },
                                onError = {}
                            )
                        }
                        .padding(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(Color.White)
                    )
                }

                Box(modifier = Modifier.size(60.dp))
            }
        }
    }
}

// Convierte 0..1 a etiqueta ISO fotográfica
fun isoLabel(value: Float): String {
    val iso = (50 * 2f.pow(value * 5)).toInt().coerceIn(50, 1600)
    return "ISO $iso"
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