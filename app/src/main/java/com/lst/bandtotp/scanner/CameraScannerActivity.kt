package com.lst.bandtotp.scanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.lst.bandtotp.ui.theme.BandtotpTheme
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class CameraScannerActivity : ComponentActivity() {

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var cameraControl: CameraControl? = null
    private var isTorchOn by mutableStateOf(false)
    private val isScanned = AtomicBoolean(false)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(this, "需要相机权限以扫描二维码", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        setContent {
            BandtotpTheme {
                ScannerScreen(
                    onBackClick = { finish() },
                    isTorchOn = isTorchOn,
                    onToggleTorch = {
                        val newState = !isTorchOn
                        cameraControl?.enableTorch(newState)
                        isTorchOn = newState
                    },
                    onQrCodeScanned = { rawText ->
                        if (isScanned.compareAndSet(false, true)) {
                            vibrateDevice()
                            val data = Intent().apply {
                                putExtra(EXTRA_SCAN_RESULT, rawText)
                            }
                            setResult(RESULT_OK, data)
                            finish()
                        }
                    },
                    onCameraControlReady = { control ->
                        cameraControl = control
                    }
                )
            }
        }
    }

    private fun vibrateDevice() {
        try {
            val vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(50)
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        const val EXTRA_SCAN_RESULT = "extra_scan_result"
    }

    @Composable
    fun ScannerScreen(
        onBackClick: () -> Unit,
        isTorchOn: Boolean,
        onToggleTorch: () -> Unit,
        onQrCodeScanned: (String) -> Unit,
        onCameraControlReady: (CameraControl) -> Unit
    ) {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current

        val infiniteTransition = rememberInfiniteTransition(label = "scan_line")
        val scanLineProgress by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(2200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "scan_line_progress"
        )

        Box(modifier = Modifier.fillMaxSize()) {
            // Camera Preview
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                        val imageAnalysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()

                        val hints = mapOf(
                            DecodeHintType.CHARACTER_SET to "UTF-8",
                            DecodeHintType.TRY_HARDER to java.lang.Boolean.TRUE,
                            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)
                        )
                        val reader = MultiFormatReader()

                        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                            if (!isScanned.get()) {
                                val buffer = imageProxy.planes[0].buffer
                                val bytes = ByteArray(buffer.remaining())
                                buffer.get(bytes)

                                val width = imageProxy.width
                                val height = imageProxy.height

                                val source = PlanarYUVLuminanceSource(
                                    bytes, width, height,
                                    0, 0, width, height, false
                                )
                                val binaryBitmap = BinaryBitmap(HybridBinarizer(source))

                                try {
                                    reader.reset()
                                    val result = reader.decode(binaryBitmap, hints)
                                    if (result != null) {
                                        val payload = com.lst.bandtotp.parser.QRCodeDecoder.extractResultPayload(result)
                                        if (payload.isNotEmpty()) {
                                            onQrCodeScanned(payload)
                                        }
                                    }
                                } catch (e: Exception) {
                                    // No QR code in frame
                                }
                            }
                            imageProxy.close()
                        }

                        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                        try {
                            cameraProvider.unbindAll()
                            val camera = cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                cameraSelector,
                                preview,
                                imageAnalysis
                            )
                            onCameraControlReady(camera.cameraControl)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }, ContextCompat.getMainExecutor(ctx))

                    previewView
                }
            )

            // Scanning Overlay Mask with Box
            Canvas(modifier = Modifier.fillMaxSize()) {
                val boxSize = size.width * 0.72f
                val left = (size.width - boxSize) / 2f
                val top = (size.height - boxSize) / 2.4f

                // Semi-transparent dark background
                drawRect(
                    color = Color.Black.copy(alpha = 0.55f),
                    size = size
                )

                // Clear center scan area
                drawRoundRect(
                    color = Color.Transparent,
                    topLeft = Offset(left, top),
                    size = Size(boxSize, boxSize),
                    cornerRadius = CornerRadius(24f, 24f),
                    blendMode = BlendMode.Clear
                )

                // Frame Border
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.8f),
                    topLeft = Offset(left, top),
                    size = Size(boxSize, boxSize),
                    cornerRadius = CornerRadius(24f, 24f),
                    style = Stroke(width = 3.dp.toPx())
                )

                // Corner Accent Marks
                val cornerLen = 28.dp.toPx()
                val cornerStroke = 5.dp.toPx()
                val accentColor = Color(0xFF00E676) // Bright Green

                // Top-Left
                drawLine(accentColor, Offset(left, top + cornerLen), Offset(left, top), cornerStroke)
                drawLine(accentColor, Offset(left, top), Offset(left + cornerLen, top), cornerStroke)

                // Top-Right
                drawLine(accentColor, Offset(left + boxSize - cornerLen, top), Offset(left + boxSize, top), cornerStroke)
                drawLine(accentColor, Offset(left + boxSize, top), Offset(left + boxSize, top + cornerLen), cornerStroke)

                // Bottom-Left
                drawLine(accentColor, Offset(left, top + boxSize - cornerLen), Offset(left, top + boxSize), cornerStroke)
                drawLine(accentColor, Offset(left, top + boxSize), Offset(left + cornerLen, top + boxSize), cornerStroke)

                // Bottom-Right
                drawLine(accentColor, Offset(left + boxSize - cornerLen, top + boxSize), Offset(left + boxSize, top + boxSize), cornerStroke)
                drawLine(accentColor, Offset(left + boxSize, top + boxSize), Offset(left + boxSize, top + boxSize - cornerLen), cornerStroke)

                // Animated Laser Line
                val laserY = top + boxSize * scanLineProgress
                drawLine(
                    color = Color(0xFF00E676),
                    start = Offset(left + 16f, laserY),
                    end = Offset(left + boxSize - 16f, laserY),
                    strokeWidth = 3.dp.toPx()
                )
            }

            // Top Bar Controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .systemBarsPadding()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBackClick,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                        .size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "返回",
                        tint = Color.White
                    )
                }

                Text(
                    text = "对准二维码扫描",
                    color = Color.White,
                    fontSize = 17.sp,
                    style = MaterialTheme.typography.titleMedium
                )

                IconButton(
                    onClick = onToggleTorch,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                        .size(44.dp)
                ) {
                    Icon(
                        imageVector = if (isTorchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                        contentDescription = "手电筒",
                        tint = if (isTorchOn) Color(0xFFFFEB3B) else Color.White
                    )
                }
            }

            // Hint Text Below Frame
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 64.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                Text(
                    text = "支持 Google / 微软 / 瓦特工具箱 / 各种 2FA 二维码",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 14.sp,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                        .padding(horizontal = 18.dp, vertical = 8.dp)
                )
            }
        }
    }
}
