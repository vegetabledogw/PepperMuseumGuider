package com.example.myapplication

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import android.graphics.BitmapFactory
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.aldebaran.qi.Future
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.QiSDK
import com.aldebaran.qi.sdk.RobotLifecycleCallbacks
import com.aldebaran.qi.sdk.builder.ChatBuilder
import com.aldebaran.qi.sdk.builder.QiChatbotBuilder
import com.aldebaran.qi.sdk.builder.TopicBuilder
import com.aldebaran.qi.sdk.design.activity.RobotActivity
import com.aldebaran.qi.sdk.builder.HolderBuilder
import com.aldebaran.qi.sdk.builder.SayBuilder
import com.aldebaran.qi.sdk.`object`.conversation.Chat
import com.aldebaran.qi.sdk.`object`.conversation.QiChatbot
import com.aldebaran.qi.sdk.`object`.holder.AutonomousAbilitiesType
import com.aldebaran.qi.sdk.`object`.holder.Holder
import java.io.File
import java.io.BufferedReader
import java.io.FileReader
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : RobotActivity(), RobotLifecycleCallbacks {

    private val TAG = "MainActivity"
    private val PERMISSION_REQUEST_CODE = 100

    // UI components
    private lateinit var settingsButton: ImageView
    private lateinit var settingsMenu: LinearLayout
    private lateinit var abilitiesIndicator: ImageView
    private lateinit var speechIndicator: ImageView
    private lateinit var toggleAbilitiesLayout: LinearLayout
    private lateinit var toggleSpeechLayout: LinearLayout
    private lateinit var imageView: ImageView
    private lateinit var logoView: ImageView

    // Robot variables
    private var qiContext: QiContext? = null
    private var holder: Holder? = null
    @Volatile private var isHolding = false

    // Chatbot variables
    private var qiChatbot: QiChatbot? = null
    private var chat: Chat? = null
    @Volatile private var chatFuture: Future<Void>? = null
    private val isChatbotRunning = AtomicBoolean(false)
    private val isChatbotStopping = AtomicBoolean(false)

    // Auto speech variables
    @Volatile private var isAutoSpeechRunning = false
    private val speechHandler = Handler(Looper.getMainLooper())
    private var speechRunnable: Runnable? = null
    private val SPEECH_INTERVAL = 60 * 60 * 1000L
    private val isSpeaking = AtomicBoolean(false)

    // Image slideshow variables
    private val imageHandler = Handler(Looper.getMainLooper())
    private var imageRunnable: Runnable? = null
    private var currentImageIndex = 0
    private val imageFiles = mutableListOf<File>()
    private val IMAGE_INTERVAL = 5000L

    // File paths
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
        setContentView(R.layout.activity_main)

        settingsButton = findViewById(R.id.settingsButton)
        settingsMenu = findViewById(R.id.settingsMenu)
        abilitiesIndicator = findViewById(R.id.abilitiesIndicator)
        speechIndicator = findViewById(R.id.speechIndicator)
        toggleAbilitiesLayout = findViewById(R.id.toggleAbilitiesLayout)
        toggleSpeechLayout = findViewById(R.id.toggleSpeechLayout)
        imageView = findViewById(R.id.imageView)
        logoView = findViewById(R.id.logoView)

        settingsButton.isEnabled = false

        settingsButton.setOnClickListener {
            settingsMenu.visibility = if (settingsMenu.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        toggleAbilitiesLayout.setOnClickListener {
            toggleAutonomousAbilities()
        }
        toggleSpeechLayout.setOnClickListener {
            toggleAutoSpeech()
        }

        if (checkAndRequestPermissions()) {
            initializeFiles()
        }

        QiSDK.register(this, this)
    }

    override fun onDestroy() {
        stopAutoSpeech()
        stopImageSlideshow()
        stopChatbot()
        holder?.async()?.release()
        QiSDK.unregister(this, this)
        super.onDestroy()
    }

    override fun onRobotFocusGained(qiContext: QiContext) {
        Log.i(TAG, "Robot focus gained")
        this.qiContext = qiContext

        // Start chatbot directly without initial greeting to avoid conflict
        // The chatbot itself can handle greetings
        if (!isHolding) {
            Log.d(TAG, "Starting chatbot...")
            // Small delay to ensure focus is fully established
            Thread {
                Thread.sleep(500)
                startChatbot()
            }.start()
        }

        runOnUiThread {
            updateUI()
            settingsButton.isEnabled = true
            showToast("Robot connected")
        }
    }

    override fun onRobotFocusLost() {
        Log.i(TAG, "Robot focus lost")
        this.qiContext = null
        stopAutoSpeech()
        stopChatbot()
        runOnUiThread {
            updateUI()
            settingsButton.isEnabled = false
        }
    }

    override fun onRobotFocusRefused(reason: String) {
        Log.e(TAG, "Robot focus REFUSED: $reason")
        runOnUiThread { showToast("Cannot connect: $reason") }
    }

    // --- Chatbot Control Functions (Thread-safe) ---
    private fun startChatbot() {
        // Check if already running or stopping
        if (!isChatbotRunning.compareAndSet(false, true)) {
            Log.d(TAG, "Chatbot is already running or starting.")
            return
        }

        if (isChatbotStopping.get()) {
            Log.d(TAG, "Chatbot is currently stopping, postponing start.")
            isChatbotRunning.set(false)
            return
        }

        val context = qiContext
        if (context == null) {
            Log.e(TAG, "Cannot start chatbot - qiContext is null")
            isChatbotRunning.set(false)
            return
        }

        Thread {
            try {
                Log.i(TAG, "Starting chatbot in background thread...")

                // Try resource file first, fallback to text if it fails
                val topic = try {
                    Log.d(TAG, "Trying to build topic from resource file...")
                    TopicBuilder.with(context)
                        .withResource(R.raw.greetings)
                        .build()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load from resource, using embedded text: ${e.message}")
                    // Fallback to embedded text - simple format without spaces after colons
                    val topicText = """topic: ~greetings()
language:enu

u:(e:onStart) Hello! I am ready to chat with you!
u:(hello) hello, nice to meet you!
u:(hi) hi there, how are you?
u:(hey) hey, what can I do for you!
u:(how are you) I am doing great, thank you for asking!
u:(what is your name) My name is Pepper!
u:(who are you) I am Pepper, a friendly robot assistant!
u:(bye) goodbye, see you later!
u:(goodbye) bye bye, have a great day!
u:(thank you) you're welcome!
u:(thanks) no problem at all!"""

                    TopicBuilder.with(context)
                        .withText(topicText)
                        .build()
                }

                Log.d(TAG, "Topic built successfully")

                // Build QiChatbot
                Log.d(TAG, "Building QiChatbot...")
                qiChatbot = QiChatbotBuilder.with(context)
                    .withTopic(topic)
                    .build()

                Log.d(TAG, "QiChatbot built successfully")

                // Build Chat
                Log.d(TAG, "Building Chat...")
                chat = ChatBuilder.with(context)
                    .withChatbot(qiChatbot)
                    .build()

                Log.d(TAG, "Chat built successfully")

                // Add listener to see what's happening
                chat?.addOnStartedListener {
                    Log.i(TAG, "Chat has started listening")
                    runOnUiThread { showToast("Chatbot is listening") }
                }

                // Add listener for heard phrases
                chat?.addOnHeardListener { heardPhrase ->
                    Log.d(TAG, "Heard: $heardPhrase")
                }

                // Run chat asynchronously
                Log.d(TAG, "Running chat...")
                chatFuture = chat?.async()?.run()

                // Handle completion
                chatFuture?.thenConsume {
                    Log.i(TAG, "Chatbot finished running normally")
                    isChatbotRunning.set(false)
                }

                // Handle errors separately
                chatFuture?.thenConsume { future ->
                    if (future.hasError()) {
                        Log.e(TAG, "Chatbot error: ${future.errorMessage}")
                        runOnUiThread { showToast("Chatbot error: ${future.errorMessage}") }
                    }
                }

                Log.i(TAG, "Chatbot setup completed successfully")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to build or start chatbot: ${e.message}", e)
                e.printStackTrace()
                runOnUiThread { showToast("Chatbot error: ${e.message}") }
                isChatbotRunning.set(false)
            }
        }.start()
    }

    private fun stopChatbot() {
        if (!isChatbotRunning.get()) {
            Log.d(TAG, "Chatbot is not running, nothing to stop.")
            return
        }

        if (!isChatbotStopping.compareAndSet(false, true)) {
            Log.d(TAG, "Chatbot is already being stopped.")
            return
        }

        Thread {
            try {
                Log.i(TAG, "Stopping chatbot in background thread...")

                chatFuture?.let { future ->
                    if (!future.isDone) {
                        future.requestCancellation()
                        // Wait a bit for cancellation to complete
                        Thread.sleep(500)
                    }
                }

                chatFuture = null
                chat = null
                qiChatbot = null

                Log.i(TAG, "Chatbot stopped successfully")

            } catch (e: Exception) {
                Log.e(TAG, "Error while stopping chatbot", e)
            } finally {
                isChatbotRunning.set(false)
                isChatbotStopping.set(false)
            }
        }.start()
    }

    private fun toggleAutonomousAbilities() {
        if (isHolding) releaseAutonomousAbilities() else holdAutonomousAbilities()
    }

    private fun holdAutonomousAbilities() {
        qiContext?.let { context ->
            Thread {
                try {
                    // First stop the chatbot
                    stopChatbot()
                    Thread.sleep(1000) // Wait for chatbot to stop

                    holder = HolderBuilder.with(context)
                        .withAutonomousAbilities(
                            AutonomousAbilitiesType.BACKGROUND_MOVEMENT,
                            AutonomousAbilitiesType.BASIC_AWARENESS,
                            AutonomousAbilitiesType.AUTONOMOUS_BLINKING
                        ).build()

                    holder?.async()?.hold()?.andThenConsume {
                        isHolding = true
                        runOnUiThread {
                            updateUI()
                            showToast("Abilities disabled")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Hold abilities failed", e)
                    runOnUiThread { showToast("Failed to hold abilities") }
                }
            }.start()
        } ?: showToast("Robot not connected")
    }

    private fun releaseAutonomousAbilities() {
        holder?.async()?.release()?.andThenConsume {
            isHolding = false
            holder = null

            // Restart chatbot after releasing abilities
            Thread {
                Thread.sleep(500) // Small delay to ensure clean release
                startChatbot()
            }.start()

            runOnUiThread {
                updateUI()
                showToast("Abilities enabled")
            }
        }
    }

    private fun toggleAutoSpeech() {
        if (isAutoSpeechRunning) stopAutoSpeech() else startAutoSpeech()
    }

    private fun speakFromFile() {
        if (isSpeaking.get()) {
            Log.d(TAG, "Already speaking, skipping this cycle")
            return
        }

        qiContext?.let { context ->
            Thread {
                try {
                    isSpeaking.set(true)

                    val textToSpeak = readTextFromFile()
                    if (textToSpeak.isNullOrEmpty()) {
                        runOnUiThread { showToast("No text found in speech.txt") }
                        isSpeaking.set(false)
                        return@Thread
                    }

                    Log.i(TAG, "Pausing chatbot to speak from file.")

                    // Stop chatbot before speaking
                    stopChatbot()
                    Thread.sleep(1000) // Wait for chatbot to fully stop

                    // Speak the text
                    val say = SayBuilder.with(context).withText(textToSpeak).build()
                    say.run()

                    Log.i(TAG, "Speech from file completed successfully.")

                    // Restart chatbot if abilities are not held
                    if (!isHolding) {
                        Log.i(TAG, "Resuming chatbot after speech.")
                        Thread.sleep(500) // Small delay before restarting
                        startChatbot()
                    }

                } catch(e: Exception) {
                    Log.e(TAG, "Speak from file failed", e)
                    // Ensure chatbot restarts even if speech fails
                    if (!isHolding && !isChatbotRunning.get()) {
                        Thread.sleep(500)
                        startChatbot()
                    }
                } finally {
                    isSpeaking.set(false)
                }
            }.start()
        } ?: Log.e(TAG, "Cannot speak - qiContext is null")
    }

    // --- UI and File Handling ---
    private fun updateUI() {
        runOnUiThread {
            abilitiesIndicator.setImageResource(if (isHolding) R.drawable.indicator_red else R.drawable.indicator_green)
            speechIndicator.setImageResource(if (isAutoSpeechRunning) R.drawable.indicator_green else R.drawable.indicator_red)
        }
    }

    private fun startAutoSpeech() {
        if (isAutoSpeechRunning) return
        isAutoSpeechRunning = true
        updateUI()

        // Speak immediately when starting
        speakFromFile()

        // Schedule periodic speech
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
    }

    private fun stopAutoSpeech() {
        if (!isAutoSpeechRunning) return
        isAutoSpeechRunning = false
        speechRunnable?.let { speechHandler.removeCallbacks(it) }
        speechRunnable = null
        updateUI()
        showToast("Auto speech stopped")
    }

    private fun checkAndRequestPermissions(): Boolean {
        val permissionsNeeded = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.RECORD_AUDIO)
        }
        return if (permissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsNeeded.toTypedArray(), PERMISSION_REQUEST_CODE)
            false
        } else {
            true
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                initializeFiles()
            } else {
                showToast("Storage and Microphone permissions are required.")
                initializeFiles()
            }
        }
    }

    private fun initializeFiles() {
        try {
            val pepperDir = File(Environment.getExternalStorageDirectory(), "pepper")
            if (!pepperDir.exists()) {
                pepperDir.mkdirs()
            }
            checkFileExists(TEXT_FILE_PATH, "speech.txt")
            checkFileExists(LOGO_PATH, "logo.png")
            checkDirectoryExists(IMAGE_FOLDER_PATH, "images folder")
            loadLogo()
            loadImages()
            startImageSlideshow()
        } catch (e: Exception) {
            showToast("Error loading files: ${e.message}")
        }
    }

    private fun checkFileExists(path: String, description: String) {
        val file = File(path)
        if (!file.exists() && description == "speech.txt") {
            try {
                file.parentFile?.mkdirs()
                file.writeText("Hello, I am Pepper robot. This is a test message.")
            } catch (e: Exception) { Log.e(TAG, "Failed to create sample $description", e) }
        }
    }

    private fun checkDirectoryExists(path: String, description: String) {
        val dir = File(path)
        if (!dir.exists()) {
            dir.mkdirs()
        }
    }

    private fun loadLogo() {
        try {
            val logoFile = File(LOGO_PATH)
            if (logoFile.exists()) {
                BitmapFactory.decodeFile(logoFile.absolutePath)?.let { logoView.setImageBitmap(it) }
            }
        } catch (e: Exception) { Log.e(TAG, "Error loading logo", e) }
    }

    private fun readTextFromFile(): String? {
        return try {
            File(TEXT_FILE_PATH).takeIf { it.exists() }?.let {
                BufferedReader(FileReader(it)).use { reader -> reader.readText() }
            }
        } catch (e: Exception) { null }
    }

    private fun loadImages() {
        try {
            val folder = File(IMAGE_FOLDER_PATH)
            if (!folder.exists()) {
                folder.mkdirs()
                return
            }
            imageFiles.clear()
            folder.listFiles()?.filter { file ->
                file.isFile && (file.extension.equals("jpg", true) ||
                        file.extension.equals("jpeg", true) ||
                        file.extension.equals("png", true))
            }?.forEach { imageFiles.add(it) }
        } catch (e: Exception) { Log.e(TAG, "Error loading images", e) }
    }

    private fun startImageSlideshow() {
        if (imageFiles.isEmpty()) return
        imageRunnable = object : Runnable {
            override fun run() {
                displayNextImage()
                imageHandler.postDelayed(this, IMAGE_INTERVAL)
            }
        }
        displayNextImage()
        imageHandler.postDelayed(imageRunnable!!, IMAGE_INTERVAL)
    }

    private fun stopImageSlideshow() {
        imageRunnable?.let { imageHandler.removeCallbacks(it) }
        imageRunnable = null
    }

    private fun displayNextImage() {
        if (imageFiles.isEmpty()) return
        try {
            val imageFile = imageFiles[currentImageIndex]
            BitmapFactory.decodeFile(imageFile.absolutePath)?.let { runOnUiThread { imageView.setImageBitmap(it) } }
            currentImageIndex = (currentImageIndex + 1) % imageFiles.size
        } catch (e: Exception) { Log.e(TAG, "Error displaying image", e) }
    }

    private fun showToast(message: String) {
        runOnUiThread {
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
        }
    }
}