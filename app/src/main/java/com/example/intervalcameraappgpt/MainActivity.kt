package com.example.intervalcameraappgpt

import com.example.intervalcameraappgpt.R

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Button
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.graphics.ImageFormat
import android.util.Size

class MainActivity : AppCompatActivity() {

    private lateinit var intervalTimeEditText: EditText
    private lateinit var numShotsEditText: EditText
    private lateinit var startButton: Button

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraDevice: CameraDevice? = null
    private var imageReader: ImageReader? = null
    private var cameraCaptureSession: CameraCaptureSession? = null

    private var selectedCameraId: String? = null
    private var jpegSize: Size? = null

    @SuppressLint("NewApi")
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions[Manifest.permission.CAMERA] == true) {
                startCamera()
            } else {
                Log.e("Permissions", "Camera permission denied")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        intervalTimeEditText = findViewById(R.id.intervalTimeEditText)
        numShotsEditText = findViewById(R.id.numShotsEditText)
        startButton = findViewById(R.id.startButton)

        startButton.setOnClickListener {
            val intervalTimeText = intervalTimeEditText.text.toString()
            val numShotsText = numShotsEditText.text.toString()

            if (intervalTimeText.isEmpty() || numShotsText.isEmpty()) {
                Log.e("InputValidation", "Interval time or number of shots cannot be empty.")
                return@setOnClickListener
            }

            val intervalTime = intervalTimeText.toInt()
            val numShots = numShotsText.toInt()

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
            } else {
                startIntervalCapture(intervalTime, numShots)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun startCamera() {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.e("Camera", "Camera permission not granted, requesting permissions.")
            requestPermissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
            return
        }

        try {
            // Prefer back camera
            selectedCameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                facing == CameraCharacteristics.LENS_FACING_BACK
            } ?: cameraManager.cameraIdList.firstOrNull()

            val cameraId = selectedCameraId
            if (cameraId == null) {
                Log.e("Camera", "No camera available.")
                return
            }

            // Query supported JPEG sizes and pick the best available (prefer 1920x1080 if present)
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val configMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val supportedJpegSizes = configMap?.getOutputSizes(ImageFormat.JPEG)

            if (supportedJpegSizes.isNullOrEmpty()) {
                Log.e("Camera", "No supported JPEG sizes found.")
                return
            }

            val preferred = supportedJpegSizes.firstOrNull { it.width == 1920 && it.height == 1080 }
            jpegSize = preferred ?: supportedJpegSizes.maxByOrNull { it.width.toLong() * it.height.toLong() }

            val size = jpegSize!!
            imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, /*maxImages*/ 2).apply {
                setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage()
                    image?.let {
                        val buffer = it.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        saveImageToGallery(bytes)
                        it.close()
                    }
                }, Handler(Looper.getMainLooper()))
            }

            cameraManager.openCamera(cameraId, cameraExecutor, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    createCameraPreviewSession()
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    cameraDevice = null
                }

                override fun onError(device: CameraDevice, error: Int) {
                    Log.e("Camera", "Camera error: $error")
                    device.close()
                    cameraDevice = null
                }
            })
        } catch (e: SecurityException) {
            Log.e("Camera", "Security exception opening camera", e)
        } catch (e: CameraAccessException) {
            Log.e("Camera", "Failed to access camera", e)
        }
    }

    private fun createCameraPreviewSession() {
        val device = cameraDevice
        val surface = imageReader?.surface
        if (device == null || surface == null) {
            Log.e("Camera", "Cannot create session: device or surface is null")
            return
        }
        try {
            val captureRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureRequestBuilder.addTarget(surface)
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)

            device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    cameraCaptureSession = session
                    Log.d("Camera", "Camera session configured.")
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e("Camera", "Camera configuration failed")
                }
            }, null)
        } catch (e: CameraAccessException) {
            Log.e("Camera", "Failed to create capture session", e)
        }
    }

    @SuppressLint("NewApi")
    private fun startIntervalCapture(intervalTime: Int, numShots: Int) {
        if (cameraDevice == null || cameraCaptureSession == null || imageReader == null) {
            Log.e("IntervalCapture", "Camera not ready for capture. Call startCamera first.")
            startCamera()
            Handler(Looper.getMainLooper()).postDelayed({
                if (cameraDevice != null && cameraCaptureSession != null) {
                    proceedWithIntervalCapture(intervalTime, numShots)
                } else {
                    Log.e("IntervalCapture", "Camera still not ready after delay.")
                }
            }, 2000)
            return
        }
        proceedWithIntervalCapture(intervalTime, numShots)
    }

    private fun proceedWithIntervalCapture(intervalTime: Int, numShots: Int) {
        val handler = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            var shotsTaken = 0
            override fun run() {
                if (shotsTaken < numShots) {
                    takePicture()
                    shotsTaken++
                    if (shotsTaken < numShots) {
                        handler.postDelayed(this, (intervalTime * 1000).toLong())
                    }
                }
            }
        }
        handler.post(runnable)
    }

    private fun takePicture() {
        val device = cameraDevice
        val session = cameraCaptureSession
        val surface = imageReader?.surface
        if (device == null || session == null || surface == null) {
            Log.e("Camera", "Cannot take picture: device/session/surface is null")
            return
        }
        try {
            val captureRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureRequestBuilder.addTarget(surface)
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
            // Optionally set JPEG orientation here if needed

            session.capture(captureRequestBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    super.onCaptureCompleted(session, request, result)
                    Log.d("Camera", "Picture capture completed.")
                }

                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure
                ) {
                    super.onCaptureFailed(session, request, failure)
                    Log.e("Camera", "Picture capture failed: ${failure.reason}")
                }
            }, null)
        } catch (e: CameraAccessException) {
            Log.e("Camera", "Failed to take picture", e)
        }
    }

    private fun saveImageToGallery(imageBytes: ByteArray) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.TITLE, "Interval Shot")
            put(MediaStore.Images.Media.DISPLAY_NAME, "interval_shot_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        uri?.let {
            try {
                contentResolver.openOutputStream(it)?.use { outputStream ->
                    outputStream.write(imageBytes)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    contentResolver.update(it, values, null, null)
                }
                Log.d("Picture", "Picture saved to: $it")
            } catch (e: Exception) {
                Log.e("Picture", "Failed to save picture", e)
                contentResolver.delete(it, null, null)
            }
        } ?: Log.e("Picture", "Failed to create MediaStore entry.")
    }

    override fun onPause() {
        super.onPause()
        closeCamera()
        cameraExecutor.shutdown()
    }

    override fun onResume() {
        super.onResume()
    }

    private fun closeCamera() {
        cameraCaptureSession?.close()
        cameraCaptureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        Log.d("Camera", "Camera resources closed.")
    }
}
