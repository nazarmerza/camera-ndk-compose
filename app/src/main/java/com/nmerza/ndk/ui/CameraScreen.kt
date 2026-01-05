package com.nmerza.ndk.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.widget.Toast
import android.util.Log
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
import coil.compose.AsyncImage
import com.nmerza.ndk.camera.CameraManager

enum class CameraMode { PHOTO, VIDEO }
@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val grayscaleBitmap = remember { mutableStateOf<Bitmap?>(null) }

    val cameraManager = remember {
        CameraManager(context) { grayscaleBitmap.value = it }
    }

    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var currentMode by remember { mutableStateOf(CameraMode.PHOTO) }
    var lastCapturedUri by remember { mutableStateOf<Uri?>(null) }

    // Enabled when processed frame is available
    val captureEnabled by derivedStateOf { grayscaleBitmap.value != null }

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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {

        // Host the PreviewView so CameraX has an attached surface. We keep the view in the
        // hierarchy but hide its visual output by setting visibility = INVISIBLE. Using
        // INVISIBLE (instead of alpha=0) avoids extra alpha compositing overhead.
        AndroidView(
            factory = {
                previewView.apply {
                    visibility = View.INVISIBLE
                }
            },
            update = { view ->
                // Optionally show the native preview only until the first processed frame is ready.
                view.visibility = if (grayscaleBitmap.value == null) View.VISIBLE else View.INVISIBLE
            },
            modifier = Modifier.fillMaxSize()
        )

        grayscaleBitmap.value?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillWidth
            )
        }

        CameraModeSwitcher(
            currentMode = currentMode,
            onModeChange = {
                if (!cameraManager.isRecording()) {
                    currentMode = it
                }
            }
        )

        CameraControls(
            currentMode = currentMode,
            isRecording = cameraManager.isRecording(),
            lastCapturedUri = lastCapturedUri,
            captureEnabled = captureEnabled,
            onCaptureClick = {
                if (!captureEnabled) return@CameraControls
                when (currentMode) {
                    CameraMode.PHOTO -> {
                        cameraManager.takePhoto { uri ->
                            Log.d("CameraScreen", "takePhoto callback received uri=$uri")
                            lastCapturedUri = uri
                            // inform user
                            Toast.makeText(context, "Saved: $uri", Toast.LENGTH_SHORT).show()
                        }
                    }
                    CameraMode.VIDEO -> {
                        if (cameraManager.isRecording()) {
                            cameraManager.stopVideoRecording()
                        } else {
                            cameraManager.startVideoRecording {
                                lastCapturedUri = it
                            }
                        }
                    }
                }
            },
            onSwitchCameraClick = {
                lensFacing =
                    if (lensFacing == CameraSelector.LENS_FACING_BACK)
                        CameraSelector.LENS_FACING_FRONT
                    else
                        CameraSelector.LENS_FACING_BACK
            },
            onThumbnailClick = {
                lastCapturedUri?.let { uri ->
                    Log.d("CameraScreen", "thumbnail click uri=$uri")
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "image/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
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
    captureEnabled: Boolean,
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
                    .clickable(enabled = captureEnabled) { onCaptureClick() }
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(buttonColor.copy(alpha = if (captureEnabled) 1f else 0.5f)),
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
