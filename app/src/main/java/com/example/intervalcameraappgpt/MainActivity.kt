package com.example.intervalcameraappgpt

import com.example.intervalcameraappgpt.R

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
import android.util.Size
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
    private val mainHandler = Handler(Looper.getMainLooper())
    private var intervalCaptureRunnable: Runnable? = null


    @SuppressLint("NewApi")
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val cameraGranted = permissions[Manifest.permission.CAMERA] == true
            val storageGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android 13+: Use READ_MEDIA_IMAGES, no WRITE permission needed for MediaStore
                permissions[Manifest.permission.READ_MEDIA_IMAGES] == true
            } else {
                // Android 12 and below: Use traditional storage permissions
                permissions[Manifest.permission.WRITE_EXTERNAL_STORAGE] == true
            }
            
            if (cameraGranted && storageGranted) {
                // Permissions granted
                startCamera()
            } else {
                // Permissions denied
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

            val intervalTime: Int
            val numShots: Int
            
            try {
                intervalTime = intervalTimeText.toInt()
                numShots = numShotsText.toInt()
                
                // Validate ranges to prevent crashes or unreasonable values
                if (intervalTime <= 0 || intervalTime > 3600) {
                    Log.e("InputValidation", "Interval time must be between 1 and 3600 seconds.")
                    return@setOnClickListener
                }
                
                if (numShots <= 0 || numShots > 1000) {
                    Log.e("InputValidation", "Number of shots must be between 1 and 1000.")
                    return@setOnClickListener
                }
            } catch (e: NumberFormatException) {
                Log.e("InputValidation", "Please enter valid numbers for interval time and number of shots.", e)
                return@setOnClickListener
            }


            val cameraPermissionGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            val storagePermissionGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android 13+: Check READ_MEDIA_IMAGES permission
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
            } else {
                // Android 12 and below: Check WRITE_EXTERNAL_STORAGE permission
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            }
            
            if (!cameraPermissionGranted || !storagePermissionGranted) {
                val permissionsToRequest = mutableListOf(Manifest.permission.CAMERA)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES)
                } else {
                    permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
                requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
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
            val permissionsToRequest = mutableListOf(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES)
            } else {
                permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
            return
        }

        try {
            val cameraIdList = cameraManager.cameraIdList
            if (cameraIdList.isEmpty()) {
                Log.e("Camera", "No camera available on this device.")
                return
            }
            
            // Find the best rear camera (Samsung devices may have multiple cameras)
            val cameraId = findBestRearCamera(cameraManager, cameraIdList)
            if (cameraId == null) {
                Log.e("Camera", "No suitable rear camera found.")
                return
            }
            
            // Get camera characteristics to determine supported sizes
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val outputSizes = map?.getOutputSizes(android.graphics.ImageFormat.JPEG)
            
            // Use a more compatible resolution (many Samsung devices prefer 16:9 or 4:3)
            val selectedSize = selectBestImageSize(outputSizes)
            Log.d("Camera", "Selected image size: ${selectedSize.width}x${selectedSize.height}")

            imageReader = ImageReader.newInstance(selectedSize.width, selectedSize.height, android.graphics.ImageFormat.JPEG, 1).apply {
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
                }, mainHandler) // Use the reusable main handler
            }


            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    Log.d("Camera", "Camera opened successfully")
                    cameraDevice = device // Store the cameraDevice instance
                    createCameraPreviewSession()
                }

                override fun onDisconnected(device: CameraDevice) {
                    Log.w("Camera", "Camera disconnected")
                    device.close()
                    cameraDevice = null
                }

                override fun onError(device: CameraDevice, error: Int) {
                    val errorMessage = when (error) {
                        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "Camera is already in use"
                        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "Maximum number of cameras in use"
                        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "Camera is disabled (check device policy)"
                        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "Camera device has encountered a fatal error"
                        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "Camera service has encountered a fatal error"
                        else -> "Unknown camera error: $error"
                    }
                    Log.e("Camera", "Camera error: $errorMessage")
                    device.close()
                    cameraDevice = null
                    
                                         // For Samsung devices, sometimes we need to retry with a different camera
                     if (error == CameraDevice.StateCallback.ERROR_CAMERA_IN_USE) {
                         mainHandler.postDelayed({
                             Log.d("Camera", "Retrying camera initialization...")
                             // Could implement retry logic here
                         }, 1000)
                     }
                }
                            }, mainHandler)
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
            mainHandler.postDelayed({
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
        // Cancel any existing interval capture
        stopIntervalCapture()

        intervalCaptureRunnable = object : Runnable {
            var shotsTaken = 0

            override fun run() {
                if (shotsTaken < numShots && !isFinishing && !isDestroyed) {
                    takePicture() // Modified to just trigger capture
                    shotsTaken++
                    if (shotsTaken < numShots) { // Schedule next shot only if more are needed
                        mainHandler.postDelayed(this, (intervalTime * 1000).toLong())
                    } else {
                        // All shots completed, cleanup
                        intervalCaptureRunnable = null
                        Log.d("IntervalCapture", "All $numShots shots completed")
                    }
                } else {
                    // Capture stopped or activity is finishing
                    intervalCaptureRunnable = null
                }
            }
        }
        intervalCaptureRunnable?.let { mainHandler.post(it) } // Start the first capture
    }
    
    private fun stopIntervalCapture() {
        intervalCaptureRunnable?.let { runnable ->
            mainHandler.removeCallbacks(runnable)
            intervalCaptureRunnable = null
            Log.d("IntervalCapture", "Interval capture stopped")
        }
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
        stopIntervalCapture() // Stop any ongoing interval capture
        closeCamera() // Close camera resources
    }

    override fun onResume() {
        super.onResume()
        // Re-initialize camera if needed, or ensure it's started when user clicks the button
        // For simplicity, we are starting the camera when the button is clicked if permissions are granted.
    }


    private fun closeCamera() {
        try {
            cameraCaptureSession?.close()
            cameraCaptureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
            Log.d("Camera", "Camera resources closed.")
        } catch (e: Exception) {
            Log.e("Camera", "Error closing camera resources", e)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopIntervalCapture() // Stop any ongoing interval capture
        closeCamera() // Close camera resources
        mainHandler.removeCallbacksAndMessages(null) // Remove all pending callbacks
        cameraExecutor.shutdown() // Shutdown the executor
    }
    
    /**
     * Find the best rear camera on Samsung devices
     */
    private fun findBestRearCamera(cameraManager: CameraManager, cameraIdList: Array<String>): String? {
        try {
            for (cameraId in cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                
                // Look for rear camera
                if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                    Log.d("Camera", "Found rear camera: $cameraId")
                    return cameraId
                }
            }
        } catch (e: CameraAccessException) {
            Log.e("Camera", "Failed to access camera characteristics", e)
        }
        
        // Fallback to first camera if no rear camera found
        return cameraIdList.firstOrNull()
    }
    
    /**
     * Select the best image size for Samsung devices
     */
    private fun selectBestImageSize(sizes: Array<Size>?): Size {
        if (sizes == null || sizes.isEmpty()) {
            Log.w("Camera", "No sizes available, using default 1920x1080")
            return Size(1920, 1080)
        }
        
        // Sort sizes by pixel count (area) in descending order
        val sortedSizes = sizes.sortedByDescending { it.width * it.height }
        
        // Prefer sizes with common aspect ratios that work well on Samsung devices
        val preferredAspectRatios = listOf(
            16.0/9.0,  // 16:9 (most common)
            4.0/3.0,   // 4:3 (traditional)
            3.0/2.0    // 3:2
        )
        
        for (aspectRatio in preferredAspectRatios) {
            for (size in sortedSizes) {
                val sizeAspectRatio = size.width.toDouble() / size.height.toDouble()
                if (Math.abs(sizeAspectRatio - aspectRatio) < 0.1) {
                    // Found a good size with preferred aspect ratio
                    if (size.width <= 4000 && size.height <= 3000) {
                        // Reasonable size limit to avoid memory issues
                        Log.d("Camera", "Selected size: ${size.width}x${size.height} (aspect ratio: $sizeAspectRatio)")
                        return size
                    }
                }
            }
        }
        
        // Fallback: use the largest available size under reasonable limits
        for (size in sortedSizes) {
            if (size.width <= 4000 && size.height <= 3000) {
                Log.d("Camera", "Fallback to size: ${size.width}x${size.height}")
                return size
            }
        }
        
        // Last resort: use the smallest available size
        val fallbackSize = sortedSizes.last()
        Log.w("Camera", "Using smallest available size: ${fallbackSize.width}x${fallbackSize.height}")
        return fallbackSize
    }
}
