package com.example.intervalcameraapp

import com.example.intervalcameraappgpt.R // Assuming this is the correct R file

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper // Import Looper for Handler
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

class MainActivity : AppCompatActivity() {

    private lateinit var intervalTimeEditText: EditText
    private lateinit var numShotsEditText: EditText
    private lateinit var startButton: Button

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraDevice: CameraDevice? = null // Keep a reference to the CameraDevice
    private var imageReader: ImageReader? = null
    private var cameraCaptureSession: CameraCaptureSession? = null


    @SuppressLint("NewApi")
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions[Manifest.permission.CAMERA] == true &&
                permissions[Manifest.permission.WRITE_EXTERNAL_STORAGE] == true) {
                // 权限申请通过
                startCamera()
            } else {
                // 权限未通过
                Log.e("Permissions", "Camera or storage permission denied")
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
                // Optionally, show a Toast to the user
                return@setOnClickListener
            }

            val intervalTime = intervalTimeText.toInt()
            val numShots = numShotsText.toInt()


            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE))
            } else {
                startIntervalCapture(intervalTime, numShots)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun startCamera() {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e("Camera", "Camera permission not granted, requesting permissions.")
            requestPermissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE))
            return
        }

        try {
            val cameraId = cameraManager.cameraIdList.firstOrNull()
            if (cameraId == null) {
                Log.e("Camera", "No camera available.")
                return
            }

            imageReader = ImageReader.newInstance(1920, 1080, android.graphics.ImageFormat.JPEG, 1).apply {
                setOnImageAvailableListener({ reader ->
                    // This is where you get the image after it's captured
                    val image = reader.acquireLatestImage()
                    // Process and save the image here
                    image?.let {
                        val buffer = it.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        saveImageToGallery(bytes) // Call a function to save the image data
                        it.close()
                    }
                }, Handler(Looper.getMainLooper())) // Ensure handler is on the correct looper if needed for UI updates
            }


            cameraManager.openCamera(cameraId, cameraExecutor, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device // Store the cameraDevice instance
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
        } catch (e: CameraAccessException) {
            Log.e("Camera", "Failed to access camera", e)
        }
    }

    private fun createCameraPreviewSession() {
        cameraDevice?.let { device ->
            imageReader?.surface?.let { surface ->
                try {
                    val captureRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE) // Use TEMPLATE_STILL_CAPTURE for taking photos
                    captureRequestBuilder.addTarget(surface)

                    device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            cameraCaptureSession = session
                            // Camera is configured, ready to take pictures if startIntervalCapture calls takePictureAndSave
                            // If you need a preview, you would set up a repeating request here.
                            // For interval capture, you might not need a continuous preview.
                            Log.d("Camera", "Camera session configured.")
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            Log.e("Camera", "Camera configuration failed")
                        }
                    }, null) // Handler can be null or a background handler
                } catch (e: CameraAccessException) {
                    Log.e("Camera", "Failed to create capture session", e)
                }
            } ?: Log.e("Camera", "ImageReader surface is null")
        } ?: Log.e("Camera", "CameraDevice is null")
    }


    // Make sure these functions are INSIDE the MainActivity class
    @SuppressLint("NewApi")
    private fun startIntervalCapture(intervalTime: Int, numShots: Int) {
        if (cameraDevice == null || cameraCaptureSession == null || imageReader == null) {
            Log.e("IntervalCapture", "Camera not ready for capture. Call startCamera first.")
            // You might want to re-initialize the camera here or show an error
            startCamera() // Attempt to start the camera if not ready
            // Add a delay or a callback mechanism to ensure camera is ready before proceeding
            Handler(Looper.getMainLooper()).postDelayed({
                // Retry after a short delay, or implement a more robust state check
                if (cameraDevice != null && cameraCaptureSession != null) {
                    proceedWithIntervalCapture(intervalTime, numShots)
                } else {
                    Log.e("IntervalCapture", "Camera still not ready after delay.")
                }
            }, 2000) // 2 second delay, adjust as needed
            return
        }
        proceedWithIntervalCapture(intervalTime, numShots)
    }

    private fun proceedWithIntervalCapture(intervalTime: Int, numShots: Int) {
        val handler = Handler(Looper.getMainLooper()) // Use Looper.getMainLooper() or a background looper if preferred

        val runnable = object : Runnable {
            var shotsTaken = 0

            override fun run() {
                if (shotsTaken < numShots) {
                    takePicture() // Modified to just trigger capture
                    shotsTaken++
                    if (shotsTaken < numShots) { // Schedule next shot only if more are needed
                        handler.postDelayed(this, (intervalTime * 1000).toLong())
                    }
                }
            }
        }
        handler.post(runnable) // Start the first capture
    }

    private fun takePicture() {
        cameraDevice?.let { device ->
            cameraCaptureSession?.let { session ->
                imageReader?.surface?.let { surface ->
                    try {
                        val captureRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                        captureRequestBuilder.addTarget(surface)
                        // Add any other capture settings (e.g., JPEG orientation)
                        // captureRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION, 90) // Example

                        session.capture(captureRequestBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(
                                session: CameraCaptureSession,
                                request: CaptureRequest,
                                result: TotalCaptureResult
                            ) {
                                super.onCaptureCompleted(session, request, result)
                                Log.d("Camera", "Picture capture completed.")
                                // Image will be available in ImageReader.OnImageAvailableListener
                            }

                            override fun onCaptureFailed(
                                session: CameraCaptureSession,
                                request: CaptureRequest,
                                failure: CaptureFailure
                            ) {
                                super.onCaptureFailed(session, request, failure)
                                Log.e("Camera", "Picture capture failed: ${failure.reason}")
                            }
                        }, null) // Handler can be null or a background handler
                    } catch (e: CameraAccessException) {
                        Log.e("Camera", "Failed to take picture", e)
                    }
                } ?: Log.e("Camera", "ImageReader surface is null for takePicture")
            } ?: Log.e("Camera", "CameraCaptureSession is null for takePicture")
        } ?: Log.e("Camera", "CameraDevice is null for takePicture")
    }

    private fun saveImageToGallery(imageBytes: ByteArray) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.TITLE, "Interval Shot")
            put(MediaStore.Images.Media.DISPLAY_NAME, "interval_shot_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            // For API 29+ (Android 10+), you might need to use MediaStore.Images.Media.IS_PENDING
            // and then update it after the stream is closed.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)

        uri?.let {
            try {
                contentResolver.openOutputStream(it)?.use { outputStream ->
                    outputStream.write(imageBytes)
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    contentResolver.update(it, values, null, null)
                }
                Log.d("Picture", "Picture saved to: $it")
            } catch (e: Exception) {
                Log.e("Picture", "Failed to save picture", e)
                // If saving failed, you might want to delete the MediaStore entry
                contentResolver.delete(it, null, null)
            }
        } ?: Log.e("Picture", "Failed to create MediaStore entry.")
    }


    override fun onPause() {
        super.onPause()
        closeCamera() // Close camera resources
        cameraExecutor.shutdown() // Shutdown the executor
    }

    override fun onResume() {
        super.onResume()
        // Re-initialize camera if needed, or ensure it's started when user clicks the button
        // For simplicity, we are starting the camera when the button is clicked if permissions are granted.
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
