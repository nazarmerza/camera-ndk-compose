package com.nmerza.spectracam.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.camera.core.CameraSelector
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.nmerza.spectracam.camera.CameraManager
import com.nmerza.spectracam.camera.NativeProcessor

enum class CameraMode { PHOTO, VIDEO }

data class FilterInfo(val displayName: String, val internalName: String)

private val filterOptions = listOf(
    FilterInfo("None", "None"),
    FilterInfo("Blue Arch", "Blue Architecture"),
    FilterInfo("Hard Boost", "HardBoost"),
    FilterInfo("Morning", "LongBeachMorning"),
    FilterInfo("Lush Green", "LushGreen"),
    FilterInfo("Magic Hour", "MagicHour"),
    FilterInfo("Natural", "NaturalBoost"),
    FilterInfo("Orange/Blue", "OrangeAndBlue"),
    FilterInfo("B&W Soft", "SoftBlackAndWhite"),
    FilterInfo("Waves", "Waves"),
    FilterInfo("Blue Hour", "BlueHour"),
    FilterInfo("Cold Chrome", "ColdChrome"),
    FilterInfo("Autumn", "CrispAutumn"),
    FilterInfo("Somber", "DarkAndSomber")
)

@SuppressLint("MissingPermission")
@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val processedBitmap = remember { mutableStateOf<Bitmap?>(null) }
    val cameraManager = remember {
        CameraManager(context) { processedBitmap.value = it }
    }

    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var currentMode by remember { mutableStateOf(CameraMode.PHOTO) }
    var lastCapturedUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFilter by remember { mutableStateOf(filterOptions.first()) }

    val captureEnabled by remember { derivedStateOf { processedBitmap.value != null } }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    LaunchedEffect(lensFacing) {
        cameraManager.startCamera(
            lifecycleOwner,
            previewView,
            CameraSelector.Builder().requireLensFacing(lensFacing).build()
        )
    }

    // Root container is now a Column to ensure elements occupy distinct vertical space
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 1. TOP SECTION: Reduced padding Switcher
        CameraModeSwitcher(
            currentMode = currentMode,
            onModeChange = {
                if (!cameraManager.isRecording()) {
                    currentMode = it
                }
            }
        )

        // 2. MIDDLE SECTION: The Preview Area
        // Weight(1f) tells this box to take all remaining space, pushing it upward.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            // Background CameraX View (Hidden)
            AndroidView(
                factory = { previewView.apply { visibility = View.INVISIBLE } },
                update = { view ->
                    view.visibility = if (processedBitmap.value == null) View.VISIBLE else View.INVISIBLE
                },
                modifier = Modifier.fillMaxSize()
            )

            // Foreground NDK Processed Display
            processedBitmap.value?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth
                )
            }
        }

        // 3. BOTTOM SECTION: Controls + Filters
        // This area now physically sits at the bottom of the screen.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.8f))
                .padding(bottom = 60.dp)
        ) {
            FilterSelectorRow(
                selectedFilter = selectedFilter,
                onFilterSelected = { filter ->
                    selectedFilter = filter
                    NativeProcessor.setActiveFilter(filter.internalName)
                }
            )

            CameraControls(
                currentMode = currentMode,
                isRecording = cameraManager.isRecording(),
                lastCapturedUri = lastCapturedUri,
                captureEnabled = captureEnabled,
                onCaptureClick = {
                    if (!captureEnabled) return@CameraControls
                    handleCapture(context, currentMode, cameraManager, { lastCapturedUri = it })
                },
                onSwitchCameraClick = {
                    lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                        CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                },
                onThumbnailClick = {
                    openGallery(context, lastCapturedUri)
                }
            )
        }
    }
}

@Composable
fun FilterSelectorRow(selectedFilter: FilterInfo, onFilterSelected: (FilterInfo) -> Unit) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = 24.dp)
    ) {
        items(filterOptions) { filter ->
            val isSelected = filter.internalName == selectedFilter.internalName
            Surface(
                onClick = { onFilterSelected(filter) },
                shape = RoundedCornerShape(20.dp),
                color = if (isSelected) Color(0xFF13A4EC) else Color.DarkGray.copy(alpha = 0.6f)
            ) {
                Text(
                    text = filter.displayName,
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
fun CameraModeSwitcher(currentMode: CameraMode, onModeChange: (CameraMode) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 50.dp, bottom = 10.dp), // Drastically reduced top padding
        horizontalArrangement = Arrangement.Center
    ) {
        listOf(CameraMode.PHOTO, CameraMode.VIDEO).forEach { mode ->
            val isSelected = currentMode == mode
            Text(
                text = mode.name,
                color = if (isSelected) Color.Yellow else Color.White,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                fontSize = 14.sp,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .clickable { onModeChange(mode) }
            )
        }
    }
}

@Composable
fun CameraControls(
    currentMode: CameraMode,
    isRecording: Boolean,
    lastCapturedUri: Uri?,
    captureEnabled: Boolean,
    onCaptureClick: () -> Unit,
    onSwitchCameraClick: () -> Unit,
    onThumbnailClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.DarkGray)
                .clickable { onThumbnailClick() },
            contentAlignment = Alignment.Center
        ) {
            if (lastCapturedUri != null) {
                AsyncImage(model = lastCapturedUri, contentDescription = null, contentScale = ContentScale.Crop)
            } else {
                Icon(Icons.Default.PhotoLibrary, null, tint = Color.White)
            }
        }

        val buttonColor = if (currentMode == CameraMode.VIDEO) Color.Red else Color.White
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(buttonColor.copy(alpha = 0.4f))
                .clickable(enabled = captureEnabled) { onCaptureClick() }
                .padding(4.dp)
                .clip(CircleShape)
                .background(buttonColor.copy(alpha = if (captureEnabled) 1f else 0.5f)),
            contentAlignment = Alignment.Center
        ) {
            if (isRecording) {
                Box(Modifier.size(22.dp).background(Color.White, RoundedCornerShape(4.dp)))
            }
        }

        IconButton(
            onClick = onSwitchCameraClick,
            modifier = Modifier
                .size(48.dp)
                .background(Color.White.copy(0.15f), CircleShape)
        ) {
            Icon(Icons.Default.FlipCameraAndroid, null, tint = Color.White)
        }
    }
}

@RequiresPermission(Manifest.permission.RECORD_AUDIO)
private fun handleCapture(context: Context, mode: CameraMode, manager: CameraManager, onUri: (Uri) -> Unit) {
    when (mode) {
        CameraMode.PHOTO -> manager.takePhoto { onUri(it) }
        CameraMode.VIDEO -> {
            if (manager.isRecording()) manager.stopVideoRecording()
            else manager.startVideoRecording { onUri(it) }
        }
    }
}

private fun openGallery(context: Context, uri: Uri?) {
    uri?.let {
        val mimeType = context.contentResolver.getType(it)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(it, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { context.startActivity(intent) } catch (e: Exception) {
            Toast.makeText(context, "No app available to open file.", Toast.LENGTH_SHORT).show()
        }
    }
}