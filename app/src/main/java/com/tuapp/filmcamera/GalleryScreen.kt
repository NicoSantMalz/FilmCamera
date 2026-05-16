package com.tuapp.filmcamera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_PREVIEW_SIZE = 800

private data class PortraPresetOption(
    val preset: PortraPreset,
    val label: String,
    val sublabel: String
)

private val portraPresetOptions = listOf(
    PortraPresetOption(PortraPreset.DAYLIGHT, "Daylight", "Exterior"),
    PortraPresetOption(PortraPreset.OVERCAST, "Overcast", "Nublado"),
    PortraPresetOption(PortraPreset.TUNGSTEN, "Tungsten", "Interior")
)

@Composable
fun GalleryEditorScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var baseBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var photoSaved by remember { mutableStateOf(false) }

    var rotationDegrees by remember { mutableStateOf(0) }
    var selectedRoll by remember { mutableStateOf(filmRolls[0]) }
    var selectedFormat by remember { mutableStateOf(filmFormats[0]) }
    var selectedBorderColor by remember { mutableStateOf(borderColors[0]) }
    var filterIntensity by remember { mutableStateOf(1f) }
    var grainIntensity by remember { mutableStateOf(GrainProcessor.getBaseGrain(filmRolls[0].name)) }
    var offsetX by remember { mutableStateOf(0.5f) }
    var offsetY by remember { mutableStateOf(0.5f) }
    var viewerW by remember { mutableStateOf(1f) }
    var viewerH by remember { mutableStateOf(1f) }

    var portraPreset by remember { mutableStateOf(PortraPreset.DAYLIGHT) }

    var currentUri by remember { mutableStateOf<Uri?>(null) }
    var cropJob by remember { mutableStateOf<Job?>(null) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            currentUri = it
            scope.launch {
                isLoading = true
                val bmp = loadBitmapFromUri(context, it, MAX_PREVIEW_SIZE)
                baseBitmap = bmp
                rotationDegrees = 0
                offsetX = 0.5f
                offsetY = 0.5f
                isLoading = false
            }
        }
    }

    var croppedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isCropping by remember { mutableStateOf(false) }

    LaunchedEffect(baseBitmap, rotationDegrees, selectedFormat, selectedBorderColor, offsetX, offsetY) {
        val bmp = baseBitmap ?: return@LaunchedEffect
        cropJob?.cancel()
        cropJob = scope.launch {
            delay(150)
            isCropping = true
            val result = withContext(Dispatchers.Default) {
                try {
                    val rotated = if (rotationDegrees != 0) {
                        val matrix = android.graphics.Matrix()
                        matrix.postRotate(rotationDegrees.toFloat())
                        Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                    } else bmp
                    FormatCrop.apply(rotated, selectedFormat, selectedBorderColor.color, offsetX, offsetY)
                } catch (e: Exception) { null }
            }
            croppedBitmap = result
            isCropping = false
        }
    }

    // ColorFilter para preview — GPU, instantáneo
    val previewColorFilter = remember(selectedRoll.name, filterIntensity) {
        val androidMatrix = FilmFilters.getMatrix(selectedRoll.name, filterIntensity)
        ColorFilter.colorMatrix(ColorMatrix(androidMatrix.array))
    }

    LaunchedEffect(photoSaved) {
        if (photoSaved) { delay(2000); photoSaved = false }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Column(modifier = Modifier.fillMaxSize()) {

            // Barra superior
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("FilmCamera", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(selectedRoll.iso, color = Color.Gray, fontSize = 14.sp)
            }

            // Visor
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color(0xFF111111))
                    .onGloballyPositioned { coords ->
                        viewerW = coords.size.width.toFloat().coerceAtLeast(1f)
                        viewerH = coords.size.height.toFloat().coerceAtLeast(1f)
                    }
                    .then(
                        if (selectedFormat.name != "none") {
                            Modifier.pointerInput(selectedFormat.name) {
                                detectDragGestures { _, drag ->
                                    offsetX = (offsetX - drag.x / viewerW * 2f).coerceIn(0f, 1f)
                                    offsetY = (offsetY - drag.y / viewerH * 2f).coerceIn(0f, 1f)
                                }
                            }
                        } else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                when {
                    isLoading -> {
                        Text("Cargando foto...", color = Color.Gray, fontSize = 14.sp)
                    }
                    croppedBitmap != null -> {
                        Image(
                            bitmap = croppedBitmap!!.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            colorFilter = previewColorFilter,
                            modifier = Modifier.fillMaxSize()
                        )

                        if (isCropping) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Recortando...", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                            }
                        }

                        Text(
                            text = selectedRoll.name,
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 13.sp,
                            modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
                        )

                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd).padding(12.dp)
                                .size(36.dp).clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.5f))
                                .clickable { rotationDegrees = (rotationDegrees + 90) % 360 },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("↻", color = Color.White, fontSize = 18.sp)
                        }

                        if (selectedFormat.name != "none") {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter).padding(bottom = 12.dp)
                                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(20.dp))
                                    .padding(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    "↔ Arrastra para encuadrar",
                                    color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp
                                )
                            }
                        }

                        if (isSaving) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.5f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "Procesando...",
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        if (photoSaved) {
                            Box(
                                modifier = Modifier.fillMaxSize().padding(bottom = 16.dp),
                                contentAlignment = Alignment.BottomCenter
                            ) {
                                Box(
                                    modifier = Modifier
                                        .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(20.dp))
                                        .padding(horizontal = 20.dp, vertical = 10.dp)
                                ) {
                                    Text("Foto guardada en FilmCamera", color = Color.White, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                    else -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(32.dp)
                        ) {
                            Text("🎞", fontSize = 48.sp)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                "Selecciona una foto",
                                color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Elige un rollo y aplica el filtro analógico",
                                color = Color(0xFF666666), fontSize = 13.sp
                            )
                        }
                    }
                }
            }

            // ── Chips de preset — solo visibles con PT 400 ─────────────────
            if (selectedRoll.name == "PT 400") {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(portraPresetOptions) { option ->
                        val isSelected = portraPreset == option.preset
                        Box(
                            modifier = Modifier
                                .clickable { portraPreset = option.preset }
                                .background(
                                    if (isSelected) Color(0xFFC8A882) else Color(0xFF1A1A1A),
                                    RoundedCornerShape(10.dp)
                                )
                                .border(
                                    1.dp,
                                    if (isSelected) Color(0xFFC8A882) else Color(0xFF333333),
                                    RoundedCornerShape(10.dp)
                                )
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = option.label,
                                    color = if (isSelected) Color.White else Color.Gray,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                                Text(
                                    text = option.sublabel,
                                    color = if (isSelected) Color.White.copy(alpha = 0.7f)
                                    else Color(0xFF555555),
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                }
            }

            // ── Formato + colores de borde ─────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LazyRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filmFormats) { format ->
                        Box(
                            modifier = Modifier
                                .clickable { selectedFormat = format; offsetX = 0.5f; offsetY = 0.5f }
                                .background(
                                    if (selectedFormat == format) Color(0xFF333333) else Color(0xFF1A1A1A),
                                    RoundedCornerShape(8.dp)
                                )
                                .border(
                                    1.dp,
                                    if (selectedFormat == format) Color.White else Color(0xFF333333),
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 10.dp, vertical = 7.dp)
                        ) {
                            Text(
                                format.label,
                                color = if (selectedFormat == format) Color.White else Color.Gray,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    borderColors.forEach { bc ->
                        val isSelected = selectedBorderColor == bc
                        val displayColor = if (bc.name == "Transparente") Color(0xFF2A2A2A)
                        else Color(bc.color)
                        Box(
                            modifier = Modifier
                                .size(22.dp).clip(CircleShape).background(displayColor)
                                .border(
                                    if (isSelected) 2.dp else 0.5.dp,
                                    if (isSelected) Color.White else Color(0xFF555555),
                                    CircleShape
                                )
                                .clickable { selectedBorderColor = bc }
                        )
                    }
                }
            }

            // ── Sliders ────────────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Filtro", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.width(48.dp))
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
                        "${(filterIntensity * 100).toInt()}%",
                        color = Color.Gray, fontSize = 12.sp, modifier = Modifier.width(36.dp)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Grano", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.width(48.dp))
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
                    Text(
                        "${(grainIntensity * 100).toInt()}%",
                        color = Color.Gray, fontSize = 12.sp, modifier = Modifier.width(36.dp)
                    )
                }
            }

            // ── Selector de rollos ─────────────────────────────────────────
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
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
                            if (roll.name == "PT 400") portraPreset = PortraPreset.DAYLIGHT
                        }
                    )
                }
            }

            // ── Botones ────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(PaddingValues(start = 24.dp, end = 24.dp, bottom = 36.dp)),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(60.dp).clip(CircleShape)
                        .border(2.dp, Color.Gray, CircleShape)
                        .clickable { launcher.launch("image/*") },
                    contentAlignment = Alignment.Center
                ) {
                    Text("GAL", color = Color.Gray, fontSize = 10.sp)
                }

                Box(
                    modifier = Modifier
                        .size(76.dp).clip(CircleShape)
                        .border(
                            3.dp,
                            if (croppedBitmap != null && !isSaving) Color.White else Color(0xFF333333),
                            CircleShape
                        )
                        .clickable {
                            if (croppedBitmap != null && !isSaving && currentUri != null) {
                                scope.launch {
                                    isSaving = true
                                    try {
                                        val appContext = context.applicationContext
                                        val roll = selectedRoll
                                        val fIntensity = filterIntensity
                                        val gIntensity = grainIntensity
                                        val uri = currentUri!!
                                        val rotation = rotationDegrees
                                        val format = selectedFormat
                                        val borderColor = selectedBorderColor.color
                                        val offX = offsetX
                                        val offY = offsetY
                                        val preset = portraPreset

                                        val filtered = withContext(Dispatchers.IO) {
                                            // 1. Cargar imagen full res (IO)
                                            val fullRes = loadBitmapFromUri(appContext, uri, 4000)
                                                ?: return@withContext null

                                            // 2. Rotar y recortar (puede ser IO)
                                            val rotated = if (rotation != 0) {
                                                val m = android.graphics.Matrix()
                                                m.postRotate(rotation.toFloat())
                                                Bitmap.createBitmap(fullRes, 0, 0, fullRes.width, fullRes.height, m, true)
                                            } else fullRes
                                            val fullCropped = FormatCrop.apply(rotated, format, borderColor, offX, offY)

                                            // 3. Cargar crops de textura en IO (acceso a assets)
                                            val crops = if (gIntensity > 0.01f) {
                                                FilmTextureGrain.loadTextureBitmaps(
                                                    appContext, fullCropped.width, fullCropped.height, roll.name
                                                )?.toList()
                                            } else null
                                            android.util.Log.d("Gallery", "Crops cargados: ${crops != null}, bitmap: ${fullCropped.width}x${fullCropped.height}")

                                            // 4. Procesar píxeles en Default
                                            withContext(Dispatchers.Default) {
                                                FilmFilters.applyFilmFilter(
                                                    bitmap          = fullCropped,
                                                    rollName        = roll.name,
                                                    filterIntensity = fIntensity,
                                                    grainIntensity  = gIntensity,
                                                    context         = null,
                                                    portraPreset    = preset,
                                                    textureCrops    = crops
                                                )
                                            }
                                        }

                                        if (filtered != null) {
                                            saveProcessedPhoto(
                                                context = context,
                                                bitmap  = filtered,
                                                onSaved = { photoSaved = true },
                                                onError = {}
                                            )
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.e("Gallery", "Error guardando: ${e.message}")
                                    } finally {
                                        isSaving = false
                                    }
                                }
                            }
                        }
                        .padding(6.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize().clip(CircleShape)
                            .background(
                                if (croppedBitmap != null && !isSaving) Color.White
                                else Color(0xFF1A1A1A)
                            )
                    )
                }

                Box(modifier = Modifier.size(60.dp))
            }
        }
    }
}

suspend fun loadBitmapFromUri(context: Context, uri: Uri, maxSize: Int = MAX_PREVIEW_SIZE): Bitmap? {
    return withContext(Dispatchers.IO) {
        try {
            val exifStream = context.contentResolver.openInputStream(uri)
            val exif = ExifInterface(exifStream!!)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )
            exifStream.close()

            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }

            val sampleSize = calculateSampleSize(opts.outWidth, opts.outHeight, maxSize)
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            var bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            } ?: return@withContext null

            val degrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90  -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (degrees != 0f) {
                val matrix = android.graphics.Matrix()
                matrix.postRotate(degrees)
                bitmap = Bitmap.createBitmap(
                    bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
                )
            }
            bitmap
        } catch (e: Exception) { null }
    }
}

private fun calculateSampleSize(width: Int, height: Int, maxSize: Int): Int {
    var sampleSize = 1
    while (maxOf(width, height) / (sampleSize * 2) >= maxSize) sampleSize *= 2
    return sampleSize
}

suspend fun saveProcessedPhoto(
    context: Context,
    bitmap: Bitmap,
    onSaved: () -> Unit,
    onError: (String) -> Unit
) {
    withContext(Dispatchers.IO) {
        try {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "film_${System.currentTimeMillis()}")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FilmCamera")
                }
            }
            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv
            )
            uri?.let {
                context.contentResolver.openOutputStream(it)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                withContext(Dispatchers.Main) { onSaved() }
            } ?: withContext(Dispatchers.Main) { onError("No se pudo guardar") }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { onError("Error: ${e.message}") }
        }
    }
}