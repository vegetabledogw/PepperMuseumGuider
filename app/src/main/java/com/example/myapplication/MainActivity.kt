package com.example.myapplication

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.graphics.BitmapFactory
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.QiSDK
import com.aldebaran.qi.sdk.RobotLifecycleCallbacks
import com.aldebaran.qi.sdk.design.activity.RobotActivity
import com.aldebaran.qi.sdk.builder.HolderBuilder
import com.aldebaran.qi.sdk.builder.SayBuilder
import com.aldebaran.qi.sdk.`object`.holder.AutonomousAbilitiesType
import com.aldebaran.qi.sdk.`object`.holder.Holder
import java.io.File
import java.io.BufferedReader
import java.io.FileReader

class MainActivity : RobotActivity(), RobotLifecycleCallbacks {

    private val TAG = "MainActivity"
    private val PERMISSION_REQUEST_CODE = 100

    // UI components
    private lateinit var btnToggleAbilities: Button
    private lateinit var btnToggleSpeech: Button
    private lateinit var statusText: TextView
    private lateinit var imageView: ImageView
    private lateinit var logoView: ImageView

    // Robot related variables
    private var qiContext: QiContext? = null
    private var holder: Holder? = null
    private var isHolding = false

    // Auto speech variables
    private val speechHandler = Handler(Looper.getMainLooper())
    private var speechRunnable: Runnable? = null
    private var isAutoSpeechRunning = false
    private val SPEECH_INTERVAL = 10 * 60 * 1000L  // 10 minutes in milliseconds

    // Image slideshow variables
    private val imageHandler = Handler(Looper.getMainLooper())
    private var imageRunnable: Runnable? = null
    private var currentImageIndex = 0
    private val imageFiles = mutableListOf<File>()
    private val IMAGE_INTERVAL = 5000L  // 5 seconds between images

    // File paths - Updated to use proper external storage path
    private val TEXT_FILE_PATH by lazy {
        File(Environment.getExternalStorageDirectory(), "pepper/speech.txt").absolutePath
    }
    private val IMAGE_FOLDER_PATH by lazy {
        File(Environment.getExternalStorageDirectory(), "pepper/images").absolutePath
    }
    private val LOGO_PATH by lazy {
        File(Environment.getExternalStorageDirectory(), "pepper/logo.png").absolutePath
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            setContentView(R.layout.activity_main)
            Log.d(TAG, "onCreate: Layout set successfully")

            // Initialize UI components
            btnToggleAbilities = findViewById(R.id.btnToggleAbilities)
            btnToggleSpeech = findViewById(R.id.btnToggleSpeech)
            statusText = findViewById(R.id.statusText)
            imageView = findViewById(R.id.imageView)
            logoView = findViewById(R.id.logoView)
            Log.d(TAG, "onCreate: All UI components initialized")

            // Initial state: disable all buttons
            disableAllButtons()

            // Set button click listeners
            btnToggleAbilities.setOnClickListener {
                Log.d(TAG, "Toggle abilities button clicked")
                toggleAutonomousAbilities()
            }

            btnToggleSpeech.setOnClickListener {
                Log.d(TAG, "Toggle speech button clicked")
                toggleAutoSpeech()
            }

            // Check and request permissions
            if (checkAndRequestPermissions()) {
                // Permissions already granted, load files
                initializeFiles()
            }
            // else wait for permission callback

            // Register QiSDK
            QiSDK.register(this, this)
            Log.d(TAG, "onCreate: QiSDK registered")

        } catch (e: Exception) {
            Log.e(TAG, "ERROR in onCreate", e)
            e.printStackTrace()
        }
    }

    /**
     * Check and request storage permissions
     */
    private fun checkAndRequestPermissions(): Boolean {
        val permissionsNeeded = mutableListOf<String>()

        // Check READ permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        // Check WRITE permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }


        return if (permissionsNeeded.isNotEmpty()) {
            Log.d(TAG, "Requesting permissions: $permissionsNeeded")
            ActivityCompat.requestPermissions(
                this,
                permissionsNeeded.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
            false
        } else {
            Log.d(TAG, "All permissions already granted")
            true
        }
    }

    /**
     * Handle permission request results
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            var allGranted = true

            for (i in grantResults.indices) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false
                    Log.e(TAG, "Permission denied: ${permissions[i]}")
                }
            }

            if (allGranted) {
                Log.i(TAG, "All permissions granted")
                initializeFiles()
            } else {
                Log.e(TAG, "Some permissions were denied")
                showToast("Storage permissions required to read files")
                // Try to proceed anyway with limited functionality
                initializeFiles()
            }
        }
    }

    /**
     * Initialize files after permissions are granted
     */
    private fun initializeFiles() {
        try {
            // Log the paths being used
            Log.i(TAG, "Text file path: $TEXT_FILE_PATH")
            Log.i(TAG, "Image folder path: $IMAGE_FOLDER_PATH")
            Log.i(TAG, "Logo path: $LOGO_PATH")

            // Check if external storage is available
            val state = Environment.getExternalStorageState()
            if (Environment.MEDIA_MOUNTED != state) {
                Log.e(TAG, "External storage not mounted. State: $state")
                showToast("External storage not available")
                return
            }

            // Create pepper directory if it doesn't exist
            val pepperDir = File(Environment.getExternalStorageDirectory(), "pepper")
            if (!pepperDir.exists()) {
                if (pepperDir.mkdirs()) {
                    Log.i(TAG, "Created pepper directory: ${pepperDir.absolutePath}")
                } else {
                    Log.e(TAG, "Failed to create pepper directory")
                }
            }

            // Check if files exist
            checkFileExists(TEXT_FILE_PATH, "speech.txt")
            checkFileExists(LOGO_PATH, "logo.png")
            checkDirectoryExists(IMAGE_FOLDER_PATH, "images folder")

            // Load logo
            loadLogo()

            // Load images from folder
            loadImages()

            // Start image slideshow
            startImageSlideshow()

        } catch (e: Exception) {
            Log.e(TAG, "Error initializing files", e)
            showToast("Error loading files: ${e.message}")
        }
    }

    /**
     * Check if file exists and log result
     */
    private fun checkFileExists(path: String, description: String) {
        val file = File(path)
        if (file.exists()) {
            Log.i(TAG, "$description exists: ${file.absolutePath}")
            Log.i(TAG, "$description size: ${file.length()} bytes")
            Log.i(TAG, "$description readable: ${file.canRead()}")
        } else {
            Log.w(TAG, "$description NOT found at: ${file.absolutePath}")

            // Try to create a sample file if it's the text file
            if (description == "speech.txt") {
                try {
                    file.parentFile?.mkdirs()
                    file.writeText("Hello, I am Pepper robot. This is a test message.")
                    Log.i(TAG, "Created sample $description")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create sample $description", e)
                }
            }
        }
    }

    /**
     * Check if directory exists and log result
     */
    private fun checkDirectoryExists(path: String, description: String) {
        val dir = File(path)
        if (dir.exists() && dir.isDirectory) {
            Log.i(TAG, "$description exists: ${dir.absolutePath}")
            val files = dir.listFiles()
            Log.i(TAG, "$description contains ${files?.size ?: 0} files")
        } else {
            Log.w(TAG, "$description NOT found at: ${dir.absolutePath}")
            // Try to create the directory
            if (dir.mkdirs()) {
                Log.i(TAG, "Created $description")
            } else {
                Log.e(TAG, "Failed to create $description")
            }
        }
    }

    override fun onDestroy() {
        try {
            // Stop auto speech and image slideshow
            stopAutoSpeech()
            stopImageSlideshow()

            // Release holder if it exists
            holder?.async()?.release()

            // Unregister QiSDK
            QiSDK.unregister(this, this)
            super.onDestroy()
            Log.d(TAG, "onDestroy completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error in onDestroy", e)
        }
    }

    override fun onRobotFocusGained(qiContext: QiContext) {
        try {
            Log.i(TAG, "Robot focus GAINED")
            this.qiContext = qiContext

            runOnUiThread {
                updateStatusText()
                enableAllButtons()
            }

            // Welcome message in background thread
            Thread {
                try {
                    val say = SayBuilder.with(qiContext)
                        .withText("Hello! I am ready.")
                        .build()

                    say.async().run().andThenConsume {
                        Log.i(TAG, "Welcome message completed")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in welcome message", e)
                }
            }.start()

        } catch (e: Exception) {
            Log.e(TAG, "ERROR in onRobotFocusGained", e)
            e.printStackTrace()
        }
    }

    override fun onRobotFocusLost() {
        try {
            Log.i(TAG, "Robot focus LOST")
            this.qiContext = null

            // Stop auto speech when losing focus
            stopAutoSpeech()

            runOnUiThread {
                updateStatusText()
                disableAllButtons()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onRobotFocusLost", e)
        }
    }

    override fun onRobotFocusRefused(reason: String) {
        try {
            Log.e(TAG, "Robot focus REFUSED: $reason")

            runOnUiThread {
                statusText.text = "Status:\nConnection\nrefused"
                showToast("Cannot connect: $reason")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onRobotFocusRefused", e)
        }
    }

    /**
     * Toggle Autonomous Abilities - Switch between Hold and Release
     */
    private fun toggleAutonomousAbilities() {
        if (isHolding) {
            releaseAutonomousAbilities()
        } else {
            holdAutonomousAbilities()
        }
    }

    /**
     * Hold Autonomous Abilities - Disable autonomous behaviors
     */
    private fun holdAutonomousAbilities() {
        val context = qiContext

        if (context == null) {
            Log.e(TAG, "Cannot hold - qiContext is null")
            showToast("Robot not connected")
            return
        }

        Thread {
            try {
                Log.i(TAG, "Building holder in background thread")

                holder = HolderBuilder.with(context)
                    .withAutonomousAbilities(
                        AutonomousAbilitiesType.BACKGROUND_MOVEMENT,
                        AutonomousAbilitiesType.BASIC_AWARENESS,
                        AutonomousAbilitiesType.AUTONOMOUS_BLINKING
                    )
                    .build()

                Log.i(TAG, "Executing hold")

                holder?.async()?.hold()?.andThenConsume {
                    Log.i(TAG, "Hold successful")
                    runOnUiThread {
                        isHolding = true
                        updateStatusText()
                        showToast("Abilities disabled")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "ERROR in hold background thread", e)
                runOnUiThread {
                    showToast("Hold failed")
                }
            }
        }.start()
    }

    /**
     * Release Autonomous Abilities - Enable autonomous behaviors
     */
    private fun releaseAutonomousAbilities() {
        Thread {
            try {
                Log.i(TAG, "Releasing autonomous abilities in background thread")

                holder?.async()?.release()?.andThenConsume {
                    Log.i(TAG, "Release successful")
                    runOnUiThread {
                        isHolding = false
                        holder = null
                        updateStatusText()
                        showToast("Abilities enabled")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "ERROR in release background thread", e)
                runOnUiThread {
                    showToast("Release failed")
                }
            }
        }.start()
    }

    /**
     * Toggle Auto Speech - Switch between Start and Stop
     */
    private fun toggleAutoSpeech() {
        if (isAutoSpeechRunning) {
            stopAutoSpeech()
        } else {
            startAutoSpeech()
        }
    }

    /**
     * Update status text and button labels based on current state
     */
    private fun updateStatusText() {
        val connectionStatus = if (qiContext != null) "Connected" else "Disconnected"
        val abilitiesStatus = if (isHolding) "Disabled" else "Enabled"
        val speechStatus = if (isAutoSpeechRunning) "Running" else "Stopped"

        // Update status text (compact format for small display)
        statusText.text = "Status:\n$connectionStatus\nAbilities: $abilitiesStatus\nSpeech: $speechStatus"

        // Update button text based on state
        btnToggleAbilities.text = if (isHolding) "Enable Abilities" else "Disable Abilities"
        btnToggleSpeech.text = if (isAutoSpeechRunning) "Stop Speech" else "Start Speech"
    }

    /**
     * Load logo image
     */
    private fun loadLogo() {
        try {
            val logoFile = File(LOGO_PATH)

            if (logoFile.exists()) {
                val bitmap = BitmapFactory.decodeFile(logoFile.absolutePath)
                if (bitmap != null) {
                    logoView.setImageBitmap(bitmap)
                    Log.i(TAG, "Logo loaded successfully")
                } else {
                    Log.w(TAG, "Failed to decode logo image")
                }
            } else {
                Log.w(TAG, "Logo file not found at: $LOGO_PATH")
                // Keep default launcher icon
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading logo", e)
        }
    }

    /**
     * Read text from file
     */
    private fun readTextFromFile(): String? {
        return try {
            val file = File(TEXT_FILE_PATH)

            Log.d(TAG, "Attempting to read file: ${file.absolutePath}")
            Log.d(TAG, "File exists: ${file.exists()}")
            Log.d(TAG, "File readable: ${file.canRead()}")

            if (!file.exists()) {
                Log.e(TAG, "Text file not found at: $TEXT_FILE_PATH")
                return null
            }

            val text = BufferedReader(FileReader(file)).use { it.readText() }
            Log.d(TAG, "Text read from file: ${text.take(50)}...")
            text
        } catch (e: Exception) {
            Log.e(TAG, "Error reading text file", e)
            null
        }
    }

    /**
     * Make Pepper speak text from file
     */
    private fun speakFromFile() {
        val context = qiContext

        if (context == null) {
            Log.e(TAG, "Cannot speak - qiContext is null")
            return
        }

        Thread {
            try {
                val textToSpeak = readTextFromFile()

                if (textToSpeak.isNullOrEmpty()) {
                    Log.e(TAG, "No text to speak")
                    runOnUiThread {
                        showToast("No text found in speech.txt")
                    }
                    return@Thread
                }

                Log.d(TAG, "Building Say action with text from file")

                val say = SayBuilder.with(context)
                    .withText(textToSpeak)
                    .build()

                Log.d(TAG, "Executing Say action")

                say.async().run().andThenConsume {
                    Log.i(TAG, "Speech from file completed successfully")
                }

            } catch (e: Exception) {
                Log.e(TAG, "ERROR in speakFromFile", e)
                e.printStackTrace()
            }
        }.start()
    }

    /**
     * Start automatic speech every 10 minutes
     */
    private fun startAutoSpeech() {
        if (isAutoSpeechRunning) {
            return
        }

        isAutoSpeechRunning = true
        updateStatusText()

        // Speak immediately first time
        speakFromFile()

        // Then repeat every 10 minutes
        speechRunnable = object : Runnable {
            override fun run() {
                if (isAutoSpeechRunning) {
                    speakFromFile()
                    speechHandler.postDelayed(this, SPEECH_INTERVAL)
                }
            }
        }

        speechHandler.postDelayed(speechRunnable!!, SPEECH_INTERVAL)

        showToast("Auto speech started")
        Log.i(TAG, "Auto speech started")
    }

    /**
     * Stop automatic speech
     */
    private fun stopAutoSpeech() {
        if (!isAutoSpeechRunning) {
            return
        }

        isAutoSpeechRunning = false
        speechRunnable?.let { speechHandler.removeCallbacks(it) }
        speechRunnable = null

        updateStatusText()
        showToast("Auto speech stopped")
        Log.i(TAG, "Auto speech stopped")
    }

    /**
     * Load images from folder
     */
    private fun loadImages() {
        try {
            val folder = File(IMAGE_FOLDER_PATH)

            if (!folder.exists()) {
                Log.e(TAG, "Image folder not found: $IMAGE_FOLDER_PATH")
                folder.mkdirs()
                Log.i(TAG, "Created image folder: $IMAGE_FOLDER_PATH")
                return
            }

            imageFiles.clear()

            folder.listFiles()?.filter { file ->
                file.isFile && (file.extension.toLowerCase() == "jpg" ||
                        file.extension.toLowerCase() == "jpeg" ||
                        file.extension.toLowerCase() == "png")
            }?.forEach { imageFiles.add(it) }

            Log.i(TAG, "Loaded ${imageFiles.size} images from folder")

            if (imageFiles.isEmpty()) {
                Log.w(TAG, "No images found in folder")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading images", e)
        }
    }

    /**
     * Start image slideshow
     */
    private fun startImageSlideshow() {
        if (imageFiles.isEmpty()) {
            Log.w(TAG, "No images to display")
            return
        }

        imageRunnable = object : Runnable {
            override fun run() {
                displayNextImage()
                imageHandler.postDelayed(this, IMAGE_INTERVAL)
            }
        }

        // Display first image immediately
        displayNextImage()

        // Start slideshow
        imageHandler.postDelayed(imageRunnable!!, IMAGE_INTERVAL)

        Log.i(TAG, "Image slideshow started")
    }

    /**
     * Stop image slideshow
     */
    private fun stopImageSlideshow() {
        imageRunnable?.let { imageHandler.removeCallbacks(it) }
        imageRunnable = null
        Log.i(TAG, "Image slideshow stopped")
    }

    /**
     * Display next image in slideshow
     */
    private fun displayNextImage() {
        if (imageFiles.isEmpty()) {
            return
        }

        try {
            val imageFile = imageFiles[currentImageIndex]
            val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)

            if (bitmap != null) {
                runOnUiThread {
                    imageView.setImageBitmap(bitmap)
                    Log.d(TAG, "Displaying image: ${imageFile.name}")
                }
            } else {
                Log.e(TAG, "Failed to decode image: ${imageFile.name}")
            }

            // Move to next image
            currentImageIndex = (currentImageIndex + 1) % imageFiles.size

        } catch (e: Exception) {
            Log.e(TAG, "Error displaying image", e)
        }
    }

    /**
     * Disable all buttons
     */
    private fun disableAllButtons() {
        runOnUiThread {
            btnToggleAbilities.isEnabled = false
            btnToggleSpeech.isEnabled = false
        }
    }

    /**
     * Enable all buttons
     */
    private fun enableAllButtons() {
        runOnUiThread {
            btnToggleAbilities.isEnabled = true
            btnToggleSpeech.isEnabled = true
        }
    }

    /**
     * Display a Toast message
     */
    private fun showToast(message: String) {
        runOnUiThread {
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
        }
    }
}