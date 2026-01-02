package com.nmerza.ndk.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.camera.core.CameraSelector
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.nmerza.ndk.camera.CameraManager

enum class CameraMode { PHOTO, VIDEO }

@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val grayscaleBitmap = remember { mutableStateOf<Bitmap?>(null) }
    val showGrayscale = remember { mutableStateOf(true) }

    val cameraManager = remember {
        CameraManager(context) { grayscaleBitmap.value = it }
    }
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }

    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var currentMode by remember { mutableStateOf(CameraMode.PHOTO) }
    var isRecording by remember { mutableStateOf(false) }
    var lastCapturedUri by remember { mutableStateOf<Uri?>(null) }

    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }

    LaunchedEffect(lensFacing) {
        cameraManager.startCamera(
            lifecycleOwner,
            previewView,
            CameraSelector.Builder().requireLensFacing(lensFacing).build()
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Camera preview
//        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // Overlay grayscale bitmap
        if (showGrayscale.value) {
            grayscaleBitmap.value?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Grayscale",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillWidth
                )
            }
        }

        // Toggle
//        Row(
//            modifier = Modifier
//                .fillMaxWidth()
//                .padding(top = 20.dp, end = 20.dp),
//            horizontalArrangement = Arrangement.End,
//            verticalAlignment = Alignment.CenterVertically
//        ) {
//            Text("Show Grayscale", color = Color.White)
//            Switch(
//                checked = showGrayscale.value,
//                onCheckedChange = { showGrayscale.value = it }
//            )
//        }

        // Mode switcher
        CameraModeSwitcher(currentMode, onModeChange = { if (!isRecording) currentMode = it })

        // Controls
        CameraControls(
            currentMode = currentMode,
            isRecording = isRecording,
            lastCapturedUri = lastCapturedUri,
            onCaptureClick = @androidx.annotation.RequiresPermission(android.Manifest.permission.RECORD_AUDIO) {
                if (currentMode == CameraMode.PHOTO) {
                    cameraManager.takePhoto(mainExecutor) { lastCapturedUri = it }
                } else {
                    if (isRecording) {
                        cameraManager.stopVideoRecording()
                        isRecording = false
                    } else {
                        isRecording = true
                        cameraManager.startVideoRecording(mainExecutor) { uri ->
                            lastCapturedUri = uri
                            isRecording = false
                        }
                    }
                }
            },
            onSwitchCameraClick = {
                lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                    CameraSelector.LENS_FACING_FRONT
                else
                    CameraSelector.LENS_FACING_BACK
            },
            onThumbnailClick = {
                lastCapturedUri?.let { uri ->
                    val intent = Intent(Intent.ACTION_VIEW, uri)
                        .apply { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    context.startActivity(intent)
                }
            }
        )
    }
}





@Composable
fun CameraModeSwitcher(currentMode: CameraMode, onModeChange: (CameraMode) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 80.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        listOf(CameraMode.PHOTO, CameraMode.VIDEO).forEach { mode ->
            val isSelected = currentMode == mode
            Text(
                text = mode.name,
                color = if (isSelected) Color.Yellow else Color.White,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
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
    onCaptureClick: () -> Unit,
    onSwitchCameraClick: () -> Unit,
    onThumbnailClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 48.dp, start = 32.dp, end = 32.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        // Horizontal row for buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Gallery Thumbnail (Left)
            Box(
                modifier = Modifier.size(60.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.DarkGray)
                    .clickable { onThumbnailClick() },
                contentAlignment = Alignment.Center
            ) {
                if (lastCapturedUri != null) {
                    AsyncImage(
                        model = lastCapturedUri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(Icons.Default.PhotoLibrary, null, tint = Color.White)
                }
            }

            // Shutter Button (Center)
            val buttonColor = if (currentMode == CameraMode.VIDEO) Color.Red else Color.White
            Box(
                modifier = Modifier.size(80.dp)
                    .clip(CircleShape)
                    .background(buttonColor.copy(alpha = 0.5f))
                    .clickable { onCaptureClick() }
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(buttonColor),
                contentAlignment = Alignment.Center
            ) {
                if (isRecording) {
                    Box(
                        modifier = Modifier.size(24.dp)
                            .background(Color.White, RoundedCornerShape(4.dp))
                    )
                }
            }

            // Switch Camera (Right)
            IconButton(
                onClick = onSwitchCameraClick,
                modifier = Modifier.background(Color.Black.copy(0.3f), CircleShape)
            ) {
                Icon(Icons.Default.FlipCameraAndroid, null, tint = Color.White)
            }
        }
    }
}
