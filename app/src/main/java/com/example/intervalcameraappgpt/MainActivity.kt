// Author: SamT
package com.example.intervalcameraappgpt

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager // Added import
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Button
import android.widget.EditText
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import androidx.camera.core.CameraSelector
import androidx.camera.core.Camera
// import androidx.camera.core.CameraInfo // CameraInfo is ambiguous, use specific types like camera.cameraInfo
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions // Added for setCaptureRequestOptions
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.widget.TextView
import android.widget.Toast
import android.content.SharedPreferences

class MainActivity : AppCompatActivity() {

    private lateinit var viewFinder: PreviewView
    private lateinit var intervalTimeEditText: EditText
    private lateinit var numShotsEditText: EditText
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var statusTextView: TextView
    private lateinit var resetButton: Button
    private lateinit var cameraSettingsButton: Button

    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private var intervalCaptureRunnable: Runnable? = null
    private var isStopping: Boolean = false
    private var captureStartTime: Long = 0
    private var totalPicturesTaken: Int = 0

    // Camera configuration variables
    private var camera: Camera? = null
    private var currentLensFacing: Int = CameraSelector.LENS_FACING_BACK
    private var currentZoomLevel: Float = 1.0f
    private var currentFocusMode: Int = 0 // 0: Auto, 1: Continuous Picture, 2: Off (Manual Focus)
    private var currentExposureCompensation: Int = 0 // Index for exposure compensation
    private var currentWhiteBalance: Int = 0 // 0: Auto, 1: Daylight, 2: Cloudy, 3: Incandescent, 4: Fluorescent

    // SharedPreferences for storing configuration
    private lateinit var sharedPreferences: SharedPreferences
    private val PREFS_NAME = "IntervalCameraConfig"
    private val KEY_INTERVAL_TIME = "interval_time"
    private val KEY_NUM_SHOTS = "num_shots"
    private val KEY_LENS_FACING = "lens_facing"
    private val KEY_ZOOM_LEVEL = "zoom_level"
    private val KEY_FOCUS_MODE = "focus_mode"
    private val KEY_EXPOSURE_COMPENSATION = "exposure_compensation"
    private val KEY_WHITE_BALANCE = "white_balance"

    // Pending request values to resume flow after permission grant
    private var pendingIntervalTimeSeconds: Int? = null
    private var pendingNumShots: Int? = null

    @SuppressLint("NewApi")
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val cameraGranted = permissions[Manifest.permission.CAMERA] == true
            val storageGranted = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                permissions[Manifest.permission.READ_MEDIA_IMAGES] == true
            } else {
                permissions[Manifest.permission.WRITE_EXTERNAL_STORAGE] == true
            }

            if (cameraGranted && (storageGranted || android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q)) { // For Q, WRITE_EXTERNAL_STORAGE is not needed for app-specific dir
                val interval = pendingIntervalTimeSeconds
                val shots = pendingNumShots
                if (interval != null && shots != null) {
                    startIntervalCapture(interval, shots)
                } else {
                    startCamera()
                }
                pendingIntervalTimeSeconds = null
                pendingNumShots = null
            } else {
                Log.e("Permissions", "Camera or storage permission denied")
                Toast.makeText(this, "需要相机和存储权限才能使用此应用", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewFinder = findViewById(R.id.viewFinder)
        intervalTimeEditText = findViewById(R.id.intervalTimeEditText)
        numShotsEditText = findViewById(R.id.numShotsEditText)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        statusTextView = findViewById(R.id.statusTextView)
        resetButton = findViewById(R.id.resetButton)
        cameraSettingsButton = findViewById(R.id.cameraSettingsButton)

        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadSavedConfiguration()

        if (hasSavedConfiguration()) {
            updateStatus("欢迎回来！已加载上次的配置")
        } else {
            updateStatus("欢迎使用间隔相机！已设置默认配置")
        }
        showConfigurationInStatus()
        showCameraConfigurationInStatus()

        if (allPermissionsGranted()) {
            updateStatus("正在启动相机...")
            startCamera()
        } else {
            updateStatus("需要相机权限")
            requestPermissions()
        }

        startButton.setOnClickListener {
            val intervalTimeText = intervalTimeEditText.text.toString()
            val numShotsText = numShotsEditText.text.toString()

            if (intervalTimeText.isEmpty() || numShotsText.isEmpty()) {
                updateStatus("请输入间隔时间和拍摄张数")
                return@setOnClickListener
            }
            try {
                val intervalTime = intervalTimeText.toInt()
                val numShots = numShotsText.toInt()
                if (intervalTime <= 0 || intervalTime > 3600) {
                    updateStatus("间隔时间必须在1-3600秒之间")
                    return@setOnClickListener
                }
                if (numShots <= 0 || numShots > 1000) {
                    updateStatus("拍摄张数必须在1-1000张之间")
                    return@setOnClickListener
                }
                pendingIntervalTimeSeconds = intervalTime
                pendingNumShots = numShots
                if (allPermissionsGranted()) {
                    saveConfiguration(intervalTime, numShots)
                    startIntervalCapture(intervalTime, numShots)
                    pendingIntervalTimeSeconds = null
                    pendingNumShots = null
                } else {
                    requestPermissions()
                }
            } catch (e: NumberFormatException) {
                updateStatus("请输入有效的数字")
            }
        }

        stopButton.setOnClickListener { stopIntervalCapture() }
        stopButton.setOnLongClickListener { forceStopCapture(); true }
        startButton.setOnLongClickListener { showProgressSummary(); true }
        resetButton.setOnClickListener { resetToDefaultConfiguration() }
        resetButton.setOnLongClickListener { showCurrentConfiguration(); true }
        cameraSettingsButton.setOnClickListener { showCameraSettingsDialog() }
        cameraSettingsButton.setOnLongClickListener { showCameraCapabilities(); true }

        cameraExecutor = Executors.newSingleThreadExecutor()
        updateButtonStates()
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun startCamera() {
        updateStatus("正在初始化相机...")
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(viewFinder.surfaceProvider)
                }
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                val cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(currentLensFacing)
                    .build()
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this as LifecycleOwner, cameraSelector, preview, imageCapture
                )
                applySettingsToCamera()
                updateStatus("相机已就绪")
                Toast.makeText(this, "相机已就绪", Toast.LENGTH_SHORT).show()
                showCameraConfigurationInStatus()
            } catch (exc: Exception) {
                Log.e("Camera", "Use case binding failed", exc)
                updateStatus("相机初始化失败: ${exc.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startIntervalCapture(intervalTime: Int, numShots: Int) {
        if (!isCameraReady()) {
            updateStatus("相机未就绪，正在启动...")
            startCamera()
            Handler(Looper.getMainLooper()).postDelayed({
                if (isCameraReady()) proceedWithIntervalCapture(intervalTime, numShots)
                else updateStatus("相机启动失败，请重试")
            }, 2000)
            return
        }
        proceedWithIntervalCapture(intervalTime, numShots)
    }

    private fun isCameraReady(): Boolean = imageCapture != null

    private fun getElapsedTimeString(): String {
        if (captureStartTime == 0L) return "0秒"
        val elapsedSeconds = (System.currentTimeMillis() - captureStartTime) / 1000
        return when {
            elapsedSeconds < 60 -> "${elapsedSeconds}秒"
            elapsedSeconds < 3600 -> "${elapsedSeconds / 60}分${elapsedSeconds % 60}秒"
            else -> "${elapsedSeconds / 3600}小时${(elapsedSeconds % 3600) / 60}分${elapsedSeconds % 60}秒"
        }
    }

    private fun proceedWithIntervalCapture(intervalTime: Int, numShots: Int) {
        stopIntervalCapture() // Ensure any old runnable is stopped
        isStopping = false
        captureStartTime = System.currentTimeMillis()
        totalPicturesTaken = 0
        updateStatus("开始间隔拍摄，间隔: ${intervalTime}秒，共${numShots}张")
        Toast.makeText(this, "开始间隔拍摄", Toast.LENGTH_SHORT).show()

        intervalCaptureRunnable = object : Runnable {
            var shotsTaken = 0
            override fun run() {
                if (shotsTaken < numShots && !isFinishing && !isDestroyed && !isStopping) {
                    val elapsedTime = getElapsedTimeString()
                    updateStatus("拍摄中... ${shotsTaken + 1}/$numShots (已用时: $elapsedTime)")
                    takePicture()
                    shotsTaken++
                    if (shotsTaken < numShots && !isStopping) {
                        Handler(Looper.getMainLooper()).postDelayed(this, (intervalTime * 1000).toLong())
                    } else {
                        intervalCaptureRunnable = null
                        if (!isStopping) {
                            val finalElapsedTime = getElapsedTimeString()
                            val summaryMessage = "拍摄完成！\n共拍摄 $numShots 张照片\n总用时: $finalElapsedTime"
                            updateStatus("拍摄完成！共拍摄 $numShots 张照片")
                            Toast.makeText(this@MainActivity, summaryMessage, Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    intervalCaptureRunnable = null // Ensure cleanup
                }
            }
        }
        Handler(Looper.getMainLooper()).post(intervalCaptureRunnable!!)
    }

    private fun stopIntervalCapture() {
        if (intervalCaptureRunnable != null) {
            isStopping = true
            Handler(Looper.getMainLooper()).removeCallbacks(intervalCaptureRunnable!!)
            intervalCaptureRunnable = null
            val elapsedTime = getElapsedTimeString()
            val summaryMessage = "拍摄已停止\n共拍摄 $totalPicturesTaken 张照片\n总用时: $elapsedTime"
            Toast.makeText(this, summaryMessage, Toast.LENGTH_LONG).show()
            updateStatus("拍摄已停止 - 共拍摄 $totalPicturesTaken 张照片")
        }
    }

    private fun forceStopCapture() {
        // Similar to stopIntervalCapture but with a different message
        if (intervalCaptureRunnable != null) {
            isStopping = true
            Handler(Looper.getMainLooper()).removeCallbacks(intervalCaptureRunnable!!)
            intervalCaptureRunnable = null
            val elapsedTime = getElapsedTimeString()
            val summaryMessage = "强制停止拍摄\n共拍摄 $totalPicturesTaken 张照片\n总用时: $elapsedTime"
            Toast.makeText(this, summaryMessage, Toast.LENGTH_LONG).show()
            updateStatus("强制停止拍摄 - 共拍摄 $totalPicturesTaken 张照片")
        }
    }
    
    private fun showProgressSummary() {
        if (intervalCaptureRunnable == null) {
            Toast.makeText(this, "当前没有进行中的拍摄", Toast.LENGTH_SHORT).show()
            return
        }
        val elapsedTime = getElapsedTimeString()
        val summaryMessage = "拍摄进度\n已拍摄 $totalPicturesTaken 张照片\n已用时: $elapsedTime"
        Toast.makeText(this, summaryMessage, Toast.LENGTH_LONG).show()
    }

    private fun saveConfiguration(intervalTime: Int, numShots: Int) {
        sharedPreferences.edit().apply {
            putString(KEY_INTERVAL_TIME, intervalTime.toString())
            putString(KEY_NUM_SHOTS, numShots.toString())
            apply()
        }
        Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show()
    }

    private fun resetToDefaultConfiguration() {
        sharedPreferences.edit().clear().apply()
        intervalTimeEditText.setText("5")
        numShotsEditText.setText("10")
        currentLensFacing = CameraSelector.LENS_FACING_BACK
        currentZoomLevel = 1.0f
        currentFocusMode = 0
        currentExposureCompensation = 0
        currentWhiteBalance = 0
        saveCameraConfiguration() // Save reset camera settings
        applySettingsToCamera() // Apply to current camera if active
        if (camera?.cameraInfo?.lensFacing != currentLensFacing) { // Restart if lens changed
            restartCameraWithNewSettings()
        }
        Toast.makeText(this, "已重置为默认值", Toast.LENGTH_SHORT).show()
        updateStatus("已重置为默认配置")
        showConfigurationInStatus()
        showCameraConfigurationInStatus()
    }

    private fun showCurrentConfiguration() {
        val currentInterval = intervalTimeEditText.text.toString()
        val currentShots = numShotsEditText.text.toString()
        val savedInterval = sharedPreferences.getString(KEY_INTERVAL_TIME, "未保存")
        val savedShots = sharedPreferences.getString(KEY_NUM_SHOTS, "未保存")
        val message = "当前配置:\n间隔: ${currentInterval}秒\n张数: ${currentShots}张\n\n已保存配置:\n间隔: ${savedInterval}秒\n张数: ${savedShots}张"
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
    
    private fun getFocusModeString(mode: Int): String {
        return when (mode) {
            CaptureRequest.CONTROL_AF_MODE_AUTO -> "自动"
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE -> "连续图像"
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO -> "连续视频"
            CaptureRequest.CONTROL_AF_MODE_EDOF -> "EDOF"
            CaptureRequest.CONTROL_AF_MODE_MACRO -> "微距"
            CaptureRequest.CONTROL_AF_MODE_OFF -> "手动"
            else -> "未知 ($mode)"
        }
    }

    private fun getWhiteBalanceString(mode: Int): String {
        return when (mode) {
            CaptureRequest.CONTROL_AWB_MODE_AUTO -> "自动"
            CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> "阴天"
            CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT -> "日光"
            CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT -> "荧光灯"
            CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT -> "白炽灯"
            CaptureRequest.CONTROL_AWB_MODE_SHADE -> "阴影"
            CaptureRequest.CONTROL_AWB_MODE_TWILIGHT -> "黄昏"
            CaptureRequest.CONTROL_AWB_MODE_WARM_FLUORESCENT -> "暖色荧光灯"
            else -> "未知 ($mode)"
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun showCameraCapabilities() {
        camera?.let { cam ->
            val camera2Info = Camera2CameraInfo.from(cam.cameraInfo)
            val manager = getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val characteristics = manager.getCameraCharacteristics(camera2Info.cameraId)
            val capabilities = StringBuilder("相机功能支持:\n")

            // Focus Modes
            val afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            capabilities.append("对焦模式: ")
            if (afModes?.isNotEmpty() == true) {
                capabilities.append(afModes.joinToString(", ") { getFocusModeString(it) })
            } else {
                capabilities.append("不支持或未知")
            }
            capabilities.append("\n")

            // White Balance Modes
            val awbModes = characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
            capabilities.append("白平衡模式: ")
            if (awbModes?.isNotEmpty() == true) {
                capabilities.append(awbModes.joinToString(", ") { getWhiteBalanceString(it) })
            } else {
                capabilities.append("不支持或未知")
            }
            capabilities.append("\n")

            // Exposure Compensation Range
            val exposureRange = cam.cameraInfo.exposureState.exposureCompensationRange
            if (cam.cameraInfo.exposureState.isExposureCompensationSupported) {
                capabilities.append("曝光补偿范围: ${exposureRange.lower} 到 ${exposureRange.upper} (步长: ${cam.cameraInfo.exposureState.exposureCompensationStep})\n")
            } else {
                capabilities.append("曝光补偿: 不支持\n")
            }
            
            // Zoom Range
            val minZoom = cam.cameraInfo.zoomState.value?.minZoomRatio ?: 1.0f
            val maxZoom = cam.cameraInfo.zoomState.value?.maxZoomRatio ?: 1.0f
             capabilities.append("变焦范围: ${minZoom}x 到 ${maxZoom}x")


            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("相机功能支持")
                .setMessage(capabilities.toString())
                .setPositiveButton("确定", null)
                .show()
        } ?: Toast.makeText(this, "相机未初始化", Toast.LENGTH_SHORT).show()
    }
    
    private fun showCurrentCameraConfiguration() {
        val lensText = if (currentLensFacing == CameraSelector.LENS_FACING_BACK) "后置镜头" else "前置镜头"
        val focusText = when (currentFocusMode) { // User-defined integer to string
            0 -> "自动" //  CONTROL_AF_MODE_AUTO or CONTROL_AF_MODE_CONTINUOUS_PICTURE
            1 -> "连续图像" // CONTROL_AF_MODE_CONTINUOUS_PICTURE
            2 -> "手动" // CONTROL_AF_MODE_OFF
            else -> "自动"
        }
        val wbText = when (currentWhiteBalance) { // User-defined integer to string
            0 -> "自动" // CONTROL_AWB_MODE_AUTO
            1 -> "日光" // CONTROL_AWB_MODE_DAYLIGHT
            2 -> "阴天" // CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
            3 -> "白炽灯" // CONTROL_AWB_MODE_INCANDESCENT
            4 -> "荧光灯" // CONTROL_AWB_MODE_FLUORESCENT
            else -> "自动"
        }
        val message = "当前相机配置:\n镜头: $lensText\n变焦: ${"%.1f".format(currentZoomLevel)}x\n对焦: $focusText\n曝光补偿: $currentExposureCompensation\n白平衡: $wbText"
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }


    private fun showCameraConfigurationInStatus() {
        val lensText = if (currentLensFacing == CameraSelector.LENS_FACING_BACK) "后置" else "前置"
        updateStatus("相机配置: 镜头${lensText}, 变焦${"%.1f".format(currentZoomLevel)}x")
    }

    private fun showCameraSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.camera_settings_dialog, null)
        val lensSpinner = dialogView.findViewById<android.widget.Spinner>(R.id.lensSpinner)
        val zoomSeekBar = dialogView.findViewById<android.widget.SeekBar>(R.id.zoomSeekBar)
        val zoomValueText = dialogView.findViewById<android.widget.TextView>(R.id.zoomValueText)
        val focusModeSpinner = dialogView.findViewById<android.widget.Spinner>(R.id.focusModeSpinner)
        val exposureSeekBar = dialogView.findViewById<android.widget.SeekBar>(R.id.exposureSeekBar)
        val exposureValueText = dialogView.findViewById<android.widget.TextView>(R.id.exposureValueText)
        val whiteBalanceSpinner = dialogView.findViewById<android.widget.Spinner>(R.id.whiteBalanceSpinner)

        setupLensSpinner(lensSpinner)
        setupZoomSeekBar(zoomSeekBar, zoomValueText)
        setupFocusModeSpinner(focusModeSpinner)
        setupExposureSeekBar(exposureSeekBar, exposureValueText)
        setupWhiteBalanceSpinner(whiteBalanceSpinner)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("相机设置")
            .setView(dialogView)
            .setPositiveButton("应用") { _, _ ->
                val previousLensFacing = currentLensFacing
                currentLensFacing = if (lensSpinner.selectedItemPosition == 0) CameraSelector.LENS_FACING_BACK else CameraSelector.LENS_FACING_FRONT
                
                // Zoom: seekBar progress is 0-40 for 1.0x to 5.0x (example range)
                // Adjust this logic based on actual camera capabilities if needed
                val minZoomRatio = camera?.cameraInfo?.zoomState?.value?.minZoomRatio ?: 1.0f
                val maxZoomRatio = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 5.0f // Sensible default max
                currentZoomLevel = minZoomRatio + (zoomSeekBar.progress / (zoomSeekBar.max).toFloat()) * (maxZoomRatio - minZoomRatio)
                currentZoomLevel = currentZoomLevel.coerceIn(minZoomRatio, maxZoomRatio)


                currentFocusMode = focusModeSpinner.selectedItemPosition // 0: Auto, 1: Continuous, 2: Manual
                
                // Exposure: seekBar progress 0-24 maps to -12 to +12 (example range)
                // This needs to be an index for setExposureCompensationIndex
                val exposureState = camera?.cameraInfo?.exposureState
                if (exposureState?.isExposureCompensationSupported == true) {
                    val range = exposureState.exposureCompensationRange
                    // seekBar.max should be (range.upper - range.lower)
                    // progress should map to an index in this range.
                    // currentExposureCompensation is the index.
                    currentExposureCompensation = range.lower + exposureSeekBar.progress // seekBar.progress from 0 to (range.upper - range.lower)
                    currentExposureCompensation = currentExposureCompensation.coerceIn(range.lower, range.upper)
                } else {
                    currentExposureCompensation = 0 // Reset if not supported
                }

                currentWhiteBalance = whiteBalanceSpinner.selectedItemPosition // 0:Auto, 1:Daylight etc.

                saveCameraConfiguration()
                applySettingsToCamera()
                if (currentLensFacing != previousLensFacing) {
                    restartCameraWithNewSettings()
                }
                Toast.makeText(this, "相机设置已应用", Toast.LENGTH_SHORT).show()
                showCameraConfigurationInStatus()
                showCurrentCameraConfiguration() // Show detailed toast
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun setupLensSpinner(spinner: android.widget.Spinner) {
        val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, arrayOf("后置镜头", "前置镜头"))
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(if (currentLensFacing == CameraSelector.LENS_FACING_BACK) 0 else 1)
    }

    private fun setupZoomSeekBar(seekBar: android.widget.SeekBar, valueText: android.widget.TextView) {
        val zoomState = camera?.cameraInfo?.zoomState?.value
        val minZoom = zoomState?.minZoomRatio ?: 1.0f
        val maxZoom = zoomState?.maxZoomRatio ?: 1.0f // Default to 1.0f if not available to avoid division by zero

        if (maxZoom <= minZoom) { // Handle case where zoom is not supported or range is invalid
             seekBar.isEnabled = false
             currentZoomLevel = minZoom // Ensure currentZoomLevel is set to a valid value
             valueText.text = "${"%.1f".format(currentZoomLevel)}x"
             seekBar.progress = 0
             return
        }
        seekBar.isEnabled = true

        // seekBar.max represents the number of steps. Let's use 100 steps for finer control.
        seekBar.max = 100 
        // Calculate progress based on currentZoomLevel relative to min and max zoom.
        val progress = if (maxZoom - minZoom > 0) {
            ((currentZoomLevel - minZoom) / (maxZoom - minZoom) * 100).toInt()
        } else {
            0
        }
        seekBar.progress = progress.coerceIn(0, 100)
        valueText.text = "${"%.1f".format(currentZoomLevel)}x"

        seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sBar: android.widget.SeekBar?, prog: Int, fromUser: Boolean) {
                if (fromUser) {
                    val calculatedZoom = minZoom + (prog / 100f) * (maxZoom - minZoom)
                    valueText.text = "${"%.1f".format(calculatedZoom)}x"
                }
            }
            override fun onStartTrackingTouch(sBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sBar: android.widget.SeekBar?) {}
        })
    }


    private fun setupFocusModeSpinner(spinner: android.widget.Spinner) {
        val focusOptions = arrayOf("自动", "连续图像", "手动") // User-facing options
        val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, focusOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(currentFocusMode.coerceIn(0, focusOptions.size - 1))
    }

    @SuppressLint("SetTextI18n")
    private fun setupExposureSeekBar(seekBar: android.widget.SeekBar, valueText: android.widget.TextView) {
        val exposureState = camera?.cameraInfo?.exposureState
        if (exposureState?.isExposureCompensationSupported == true) {
            val range = exposureState.exposureCompensationRange
            seekBar.max = range.upper - range.lower
            seekBar.progress = (currentExposureCompensation - range.lower).coerceIn(0, seekBar.max)
            valueText.text = currentExposureCompensation.toString()
            seekBar.isEnabled = true

            seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sBar: android.widget.SeekBar?, prog: Int, fromUser: Boolean) {
                     if (fromUser) {
                        valueText.text = (range.lower + prog).toString()
                    }
                }
                override fun onStartTrackingTouch(sBar: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sBar: android.widget.SeekBar?) {}
            })
        } else {
            seekBar.progress = 0
            seekBar.isEnabled = false
            valueText.text = "N/A"
        }
    }

    private fun setupWhiteBalanceSpinner(spinner: android.widget.Spinner) {
        val wbOptions = arrayOf("自动", "日光", "阴天", "白炽灯", "荧光灯") // User-facing
        val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, wbOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(currentWhiteBalance.coerceIn(0, wbOptions.size -1))
    }
    
    // applyCameraSettings is removed as its logic is merged into showCameraSettingsDialog's positive button

    private fun saveCameraConfiguration() {
        sharedPreferences.edit().apply {
            putInt(KEY_LENS_FACING, currentLensFacing)
            putFloat(KEY_ZOOM_LEVEL, currentZoomLevel)
            putInt(KEY_FOCUS_MODE, currentFocusMode)
            putInt(KEY_EXPOSURE_COMPENSATION, currentExposureCompensation)
            putInt(KEY_WHITE_BALANCE, currentWhiteBalance)
            apply()
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun applySettingsToCamera() {
        camera?.let { cam ->
            // Zoom
            cam.cameraControl.setZoomRatio(currentZoomLevel)

            val camera2Control = Camera2CameraControl.from(cam.cameraControl)
            val camera2Info = Camera2CameraInfo.from(cam.cameraInfo)
            val manager = getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val characteristics = manager.getCameraCharacteristics(camera2Info.cameraId)
            
            // Focus Mode (using user's selection: 0=Auto, 1=Continuous, 2=Off/Manual)
            // We need to map this to actual CameraCharacteristics constants
            val targetAfMode = when (currentFocusMode) {
                0 -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE // Prefer continuous for general use
                1 -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                2 -> CaptureRequest.CONTROL_AF_MODE_OFF
                else -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            }
             val afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
             if (afModes?.contains(targetAfMode) == true) {
                camera2Control.setCaptureRequestOptions(CaptureRequestOptions.Builder().setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, targetAfMode).build())
             } else if (afModes?.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) == true) {
                camera2Control.setCaptureRequestOptions(CaptureRequestOptions.Builder().setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO).build())
             }


            // Exposure Compensation
            if (cam.cameraInfo.exposureState.isExposureCompensationSupported) {
                cam.cameraControl.setExposureCompensationIndex(currentExposureCompensation)
            }

            // White Balance (user selection: 0=Auto, 1=Daylight etc.)
            val targetAwbMode = when (currentWhiteBalance) {
                0 -> CaptureRequest.CONTROL_AWB_MODE_AUTO
                1 -> CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
                2 -> CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
                3 -> CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT
                4 -> CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT
                else -> CaptureRequest.CONTROL_AWB_MODE_AUTO
            }
            val awbModes = characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
            if (awbModes?.contains(targetAwbMode) == true) {
                camera2Control.setCaptureRequestOptions(CaptureRequestOptions.Builder().setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, targetAwbMode).build())
            }
        }
    }

    private fun restartCameraWithNewSettings() {
        Log.d("CameraSettings", "Restarting camera for new settings.")
        startCamera() // This will rebind with currentLensFacing
    }

    private fun loadCameraConfiguration() {
        currentLensFacing = sharedPreferences.getInt(KEY_LENS_FACING, CameraSelector.LENS_FACING_BACK)
        currentZoomLevel = sharedPreferences.getFloat(KEY_ZOOM_LEVEL, 1.0f)
        currentFocusMode = sharedPreferences.getInt(KEY_FOCUS_MODE, 0) // 0 for Auto
        currentExposureCompensation = sharedPreferences.getInt(KEY_EXPOSURE_COMPENSATION, 0)
        currentWhiteBalance = sharedPreferences.getInt(KEY_WHITE_BALANCE, 0) // 0 for Auto
    }

    private fun loadSavedConfiguration() {
        val savedIntervalTime = sharedPreferences.getString(KEY_INTERVAL_TIME, "5")
        val savedNumShots = sharedPreferences.getString(KEY_NUM_SHOTS, "10")
        intervalTimeEditText.setText(savedIntervalTime)
        numShotsEditText.setText(savedNumShots)
        loadCameraConfiguration()
    }

    private fun hasSavedConfiguration(): Boolean = sharedPreferences.contains(KEY_INTERVAL_TIME)


    private fun showConfigurationInStatus() {
        val currentInterval = intervalTimeEditText.text.toString()
        val currentShots = numShotsEditText.text.toString()
        updateStatus("当前配置: 间隔${currentInterval}秒, 共${currentShots}张")
    }


    private fun updateStatus(message: String) {
        runOnUiThread {
            statusTextView.text = message
            updateButtonStates()
        }
    }

    private fun updateButtonStates() {
        val isRunning = intervalCaptureRunnable != null && !isStopping
        startButton.isEnabled = !isRunning
        stopButton.isEnabled = isRunning
        startButton.alpha = if (isRunning) 0.5f else 1.0f
        stopButton.alpha = if (isRunning) 1.0f else 0.5f
    }

    private fun takePicture() {
        val imageCapture = imageCapture ?: return
        if (isStopping) return

        val photoFile = File(outputDirectory, SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US).format(System.currentTimeMillis()) + ".jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
        showCaptureFeedback()
        imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                totalPicturesTaken++
                saveToGallery(photoFile)
            }
            override fun onError(exception: ImageCaptureException) {
                Log.e("Camera", "Photo capture failed: ${exception.message}", exception)
                updateStatus("拍摄失败: ${exception.message}")
                stopIntervalCapture()
            }
        })
    }

    private fun saveToGallery(photoFile: File) {
        if (!photoFile.exists()) {
             Log.e("Gallery", "Photo file does not exist: ${photoFile.absolutePath}")
             return
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, photoFile.name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/" + resources.getString(R.string.app_name))
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                put(MediaStore.Images.Media.DATA, photoFile.absolutePath) // For older versions
            }
        }

        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        uri?.let {
            try {
                contentResolver.openOutputStream(it)?.use { outputStream ->
                    photoFile.inputStream().use { inputStream -> inputStream.copyTo(outputStream) }
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    contentResolver.update(it, values, null, null)
                }
                runOnUiThread { Toast.makeText(this@MainActivity, "照片已保存到相册", Toast.LENGTH_SHORT).show()}
                // Delete original file after successful copy if it's in app-specific storage and not managed by MediaStore directly before Q
                 if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q || photoFile.parentFile == outputDirectory) {
                    photoFile.delete()
                }
            } catch (e: Exception) {
                Log.e("Gallery", "Failed to save photo to gallery: $uri", e)
                contentResolver.delete(it, null, null) // Clean up MediaStore entry on failure
                 runOnUiThread { Toast.makeText(this@MainActivity, "保存到相册失败", Toast.LENGTH_LONG).show()}
            }
        } ?: run {
             Log.e("Gallery", "Failed to create MediaStore entry for $photoFile")
             runOnUiThread { Toast.makeText(this@MainActivity, "创建相册条目失败", Toast.LENGTH_LONG).show()}
        }
    }


    private fun showCaptureFeedback() {
        viewFinder.post {
            val originalBackground = viewFinder.background
            viewFinder.setBackgroundColor(0x80FFFFFF.toInt())
            viewFinder.postDelayed({ viewFinder.background = originalBackground }, 100)
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        // Use the new ActivityResultLauncher
        requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
    }

    // onRequestPermissionsResult is now handled by requestPermissionLauncher
    // We can remove the override fun onRequestPermissionsResult(...)

    override fun onPause() {
        super.onPause()
        // Consider stopping capture, but be mindful of user experience if they briefly switch apps.
        // For simplicity, current logic stops capture.
        if (isChangingConfigurations) { // Don't stop if it's just a config change like rotation
             Log.d("Lifecycle", "onPause due to configuration change, not stopping capture.")
        } else {
            Log.d("Lifecycle", "onPause, stopping interval capture.")
            stopIntervalCapture()
        }
    }

    override fun onResume() {
        super.onResume()
        if (allPermissionsGranted() && camera == null) { // Restart camera if it was null (e.g. after permissions granted or first launch)
            startCamera()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("Lifecycle", "onDestroy, stopping interval capture and shutting down executor.")
        stopIntervalCapture() // Ensure capture is stopped
        cameraExecutor.shutdown()
    }

    companion object {
        private val REQUIRED_PERMISSIONS: Array<String> by lazy {
            val permissions = mutableListOf(Manifest.permission.CAMERA)
            // For Android 10 (Q) and above, WRITE_EXTERNAL_STORAGE is not needed for app-specific directory.
            // For saving to shared storage (Gallery), READ_MEDIA_IMAGES is needed for Tiramisu (33+)
            // WRITE_EXTERNAL_STORAGE is needed for below Q for MediaStore.Images.Media.DATA.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            } else if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            permissions.toTypedArray()
        }
    }

    private val outputDirectory: File by lazy {
        val mediaDir = externalMediaDirs.firstOrNull()?.let {
            File(it, resources.getString(R.string.app_name)).apply { mkdirs() }
        }
        if (mediaDir != null && mediaDir.exists()) mediaDir else filesDir
    }
}
