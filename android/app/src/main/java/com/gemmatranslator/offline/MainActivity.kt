package com.gemmatranslator.offline

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong

/** Native UI, microphone, models, and audio; no browser or remote inference server. */
class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val inference = Executors.newSingleThreadExecutor { Thread(it, "TranslatorInference") }
    private val generation = AtomicLong()
    private var pendingInference: Future<*>? = null
    private val microphone = MicrophoneRecorder()
    private lateinit var speech: OfflineSpeech
    private lateinit var gemma: GemmaTranslator
    private lateinit var player: PcmAudioPlayer
    private lateinit var store: ModelStore
    private lateinit var rootView: LinearLayout
    private lateinit var contentView: LinearLayout
    private lateinit var resultsView: ScrollView
    private lateinit var controlsView: LinearLayout
    private lateinit var controlsScroller: ScrollView
    private lateinit var status: TextView
    private lateinit var sourceLabel: TextView
    private lateinit var sourceText: TextView
    private lateinit var targetLabel: TextView
    private lateinit var targetText: TextView
    private lateinit var timing: TextView
    private lateinit var setupPanel: LinearLayout
    private lateinit var setupMessage: TextView
    private lateinit var setupButton: Button
    private lateinit var setupProgress: ProgressBar
    private lateinit var stopButton: Button
    private val languageButtons = arrayOfNulls<Button>(2)
    private val talkButtons = arrayOfNulls<Button>(2)
    private val arrows = mutableListOf<Button>()
    private var indices = intArrayOf(0, 1)
    private var activePerson = 1
    private var recordingLane: Int? = null
    private var owner: String? = null
    private var recordingContext: Pair<SupportedLanguage, SupportedLanguage>? = null
    private var ready = false
    private var processing = false
    private var finishingRecording = false
    private var destroyed = false
    private var speechEnabled = true
    private val orange = Color.rgb(255, 165, 0)
    private val ink = Color.rgb(20, 24, 28)
    private val setupReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { refreshSetup() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ModelStore(File(filesDir, "models"))
        speech = OfflineSpeech(store.root) { Log.i("OfflineTranslator", it) }
        gemma = GemmaTranslator(cacheDir)
        player = PcmAudioPlayer(applicationContext)
        val settings = getPreferences(MODE_PRIVATE)
        indices = intArrayOf(settings.getInt("language1", 0), settings.getInt("language2", 1))
        if (indices.any { it !in SupportedLanguages.all.indices } || indices[0] == indices[1]) indices = intArrayOf(0, 1)
        speechEnabled = settings.getBoolean("speechEnabled", true)
        buildInterface()
        refreshLanguages()
        refreshSetup()
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag") // Before API 33, a signature permission restricts senders.
    override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(setupReceiver, IntentFilter(ModelDownloadService.ACTION_UPDATE), RECEIVER_NOT_EXPORTED)
        else registerReceiver(setupReceiver, IntentFilter(ModelDownloadService.ACTION_UPDATE), "$packageName.permission.MODEL_STATUS", null)
        refreshSetup()
    }

    override fun onStop() {
        cancelRecording()
        cancelPipeline()
        unregisterReceiver(setupReceiver)
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) cancelRecording()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        cancelRecording()
        updateLayout()
    }

    override fun onDestroy() {
        destroyed = true
        generation.incrementAndGet()
        microphone.close()
        gemma.cancel()
        player.close()
        inference.execute { speech.close(); gemma.close() }
        inference.shutdown()
        super.onDestroy()
    }

    private fun buildInterface() {
        rootView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(orange)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(dp(12), dp(12), dp(12), dp(8))
        }
        setContentView(rootView)
        rootView.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                rootView.setPadding(dp(12) + bars.left, dp(8) + bars.top, dp(12) + bars.right, dp(8) + bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                rootView.setPadding(dp(12) + insets.systemWindowInsetLeft, dp(8) + insets.systemWindowInsetTop,
                    dp(12) + insets.systemWindowInsetRight, dp(8) + insets.systemWindowInsetBottom)
            }
            insets
        }
        val header = row()
        header.addView(text("GEMMA TRANSLATOR", 17f, true).apply { minHeight = dp(48) }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button("⋮") { showSettings() }.apply { contentDescription = "Settings and offline models" }, LinearLayout.LayoutParams(dp(48), dp(48)))
        rootView.addView(header)
        status = text("Install the models to start", 13f).apply { minHeight = dp(28) }
        rootView.addView(status)

        setupPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setupMessage = text("", 14f)
        setupPanel.addView(setupMessage)
        setupProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        setupPanel.addView(setupProgress, LinearLayout.LayoutParams(-1, dp(12)))
        setupButton = button("Download offline models") { toggleSetup() }
        setupPanel.addView(setupButton, LinearLayout.LayoutParams(-1, dp(52)))
        rootView.addView(setupPanel)

        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
        sourceLabel = text("YOUR SPEECH", 11f, true)
        sourceText = text("Select two languages, then hold your talk button.", 20f)
        targetLabel = text("TRANSLATION", 11f, true)
        targetText = text("", 20f)
        results.addView(card(sourceLabel, sourceText))
        results.addView(card(targetLabel, targetText))
        timing = text("All processing stays on this phone.", 11f)
        results.addView(timing)
        contentView = LinearLayout(this)
        resultsView = ScrollView(this).apply { addView(results); isFillViewport = true }
        controlsView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        controlsScroller = ScrollView(this).apply { addView(controlsView) }
        contentView.addView(resultsView)
        contentView.addView(controlsScroller)
        rootView.addView(contentView, LinearLayout.LayoutParams(-1, 0, 1f))
        stopButton = button("Stop / cancel") {
            cancelRecording()
            cancelPipeline()
            status.text = "Ready — hold either talk button"
        }.apply { visibility = View.GONE }
        controlsView.addView(stopButton, LinearLayout.LayoutParams(-1, dp(48)))

        val lanes = row()
        for (lane in 1..2) {
            val controls = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(if (lane == 2) dp(4) else 0, dp(6), if (lane == 1) dp(4) else 0, 0)
            }
            controls.addView(text("PERSON $lane", 11f, true))
            val selector = row()
            val previous = button("◀") { rotate(lane, -1) }.apply { contentDescription = "Previous language for person $lane" }
            val next = button("▶") { rotate(lane, 1) }.apply { contentDescription = "Next language for person $lane" }
            arrows.add(previous); arrows.add(next)
            selector.addView(previous, LinearLayout.LayoutParams(dp(48), dp(48)))
            val language = button("") { pickLanguage(lane) }.apply { textSize = 13f }
            languageButtons[lane - 1] = language
            selector.addView(language, LinearLayout.LayoutParams(0, -2, 1f))
            selector.addView(next, LinearLayout.LayoutParams(dp(48), dp(48)))
            controls.addView(selector)
            val talk = button("Hold to talk") {
                // Accessibility services can activate once to start, again to stop.
                if (recordingLane == lane) finishRecording(owner) else startRecording(lane, "accessibility:$lane")
            }.apply { textSize = 14f }
            talkButtons[lane - 1] = talk
            installHoldGesture(talk, lane)
            talk.minHeight = dp(60)
            controls.addView(talk, LinearLayout.LayoutParams(-1, -2))
            lanes.addView(controls, LinearLayout.LayoutParams(0, -2, 1f))
        }
        controlsView.addView(lanes)
        updateLayout()
    }

    private fun updateLayout() {
        val config = resources.configuration
        val horizontal = config.orientation == Configuration.ORIENTATION_LANDSCAPE && config.screenWidthDp >= 600
        contentView.orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        resultsView.layoutParams = if (horizontal) LinearLayout.LayoutParams(0, -1, 1f)
            else LinearLayout.LayoutParams(-1, 0, 1f)
        controlsScroller.layoutParams = if (horizontal) LinearLayout.LayoutParams(dp((config.screenWidthDp * 0.52).toInt()), -1)
            else LinearLayout.LayoutParams(-1, -2)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installHoldGesture(button: Button, lane: Int) {
        // OnClick above supplies TalkBack activation; touch uses explicit hold/release.
        button.setOnTouchListener { view, event ->
            val id = event.getPointerId(event.actionIndex)
            val token = "touch:$lane:$id"
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.parent.requestDisallowInterceptTouchEvent(true)
                    startRecording(lane, token)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> finishRecording(token)
                MotionEvent.ACTION_CANCEL -> if (owner?.startsWith("touch:$lane:") == true) cancelRecording()
            }
            true
        }
        button.setOnKeyListener { _, keyCode, event ->
            if (keyCode !in listOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_SPACE)) return@setOnKeyListener false
            val token = "button:$lane:$keyCode"
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) startRecording(lane, token)
            if (event.action == KeyEvent.ACTION_UP) finishRecording(token)
            true
        }
    }

    private fun startRecording(lane: Int, token: String) {
        if (!ready || ModelDownloadService.running || recordingLane != null || finishingRecording) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status.text = "Allow microphone access, then hold your talk button again."
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MICROPHONE_REQUEST)
            return
        }
        cancelPipeline()
        val ticket = generation.get()
        owner = token
        recordingLane = lane
        activePerson = lane
        recordingContext = SupportedLanguages.all[indices[lane - 1]] to SupportedLanguages.all[indices[if (lane == 1) 1 else 0]]
        refreshControls()
        talkButtons[lane - 1]?.text = "Preparing microphone…"
        status.text = "Preparing microphone — keep holding"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val accepted = microphone.start(
            onReady = { ui(ticket) {
                if (owner == token) {
                    talkButtons[lane - 1]?.text = "Release to translate"
                    status.text = "Listening in ${recordingContext?.first?.name}…"
                }
            } },
            onError = { message -> ui(ticket) {
                if (owner == token) {
                    clearRecording()
                    status.text = "Microphone: $message"
                }
            } },
            onLimit = { ui(ticket) { if (owner == token) finishRecording(token) } },
        )
        if (!accepted) { clearRecording(); status.text = "The microphone is finishing a previous recording. Try again." }
    }

    private fun finishRecording(token: String?) {
        if (token == null || token != owner || recordingLane == null || finishingRecording) return
        finishingRecording = true
        val pair = recordingContext ?: return
        val ticket = generation.get()
        microphone.stop { samples -> ui {
            if (generation.get() != ticket || owner != token) return@ui
            clearRecording()
            if (samples.isEmpty()) { status.text = "Hold the button until it says Release to translate, then speak."; return@ui }
            process(samples, pair.first, pair.second, ticket)
        } }
    }

    private fun cancelRecording() {
        if (recordingLane == null && !finishingRecording) return
        microphone.cancel()
        clearRecording()
        status.text = if (ready) "Recording cancelled — hold either talk button" else "Install offline models to start"
    }

    private fun clearRecording() {
        recordingLane = null
        owner = null
        recordingContext = null
        finishingRecording = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        refreshControls()
    }

    private fun process(samples: FloatArray, source: SupportedLanguage, target: SupportedLanguage, ticket: Long) {
        processing = true
        refreshControls()
        showText(sourceLabel, sourceText, source, "Source", "Recognizing your speech…")
        showText(targetLabel, targetText, target, "Translation", "Waiting…")
        status.text = "Recognizing ${source.name} locally…"
        val speak = speechEnabled
        pendingInference = inference.submit {
            try {
                fun ensureCurrent() { if (generation.get() != ticket || destroyed) throw CancellationException() }
                ensureCurrent()
                val begin = System.nanoTime()
                val transcript = speech.transcribe(samples, source.code)
                ensureCurrent()
                val recognized = System.nanoTime()
                ui(ticket) { showText(sourceLabel, sourceText, source, "Source", transcript.ifBlank { "No speech detected" }); status.text = "Translating to ${target.name} locally…" }
                if (transcript.isBlank()) {
                    ui(ticket) { targetText.text = "No speech detected. Please try again." }
                    return@submit
                }
                val translation = gemma.translate(transcript, source.name, target.name, File(store.directory("gemma"), ModelCatalog.GEMMA.requiredFiles.single()))
                ensureCurrent()
                val translated = System.nanoTime()
                val stageTiming = "Recognition %.1fs · Translation %.1fs".format((recognized - begin) / 1e9, (translated - recognized) / 1e9)
                ui(ticket) { showText(targetLabel, targetText, target, "Translation", translation); timing.text = stageTiming; status.text = if (speak) "Preparing ${target.name} speech locally…" else "Translation ready" }
                if (speak) {
                    var playback: Future<*>? = null
                    for ((index, chunk) in SpeechChunks.split(translation).withIndex()) {
                        ensureCurrent()
                        val audio = speech.synthesize(chunk, target.code)
                        ensureCurrent()
                        playback?.get() // One prepared chunk while the previous one plays.
                        ensureCurrent()
                        playback = player.play(audio.samples, audio.sampleRate)
                        ui(ticket) {
                            status.text = "Speaking ${target.name} — hold either button to start again"
                            if (index == 0) timing.text = "$stageTiming · Speech ready %.1fs".format((System.nanoTime() - translated) / 1e9)
                        }
                    }
                    playback?.get()
                }
            } catch (error: LinkageError) {
                ui(ticket) {
                    status.text = "Offline engine could not load"
                    targetText.text = "This APK needs an ARM64 Android device and its packaged native libraries. Reinstall the APK if this error persists: ${error.message}"
                    Log.e("OfflineTranslator", "Native runtime could not load", error)
                }
            } catch (error: Exception) {
                if (generation.get() == ticket && !destroyed) ui(ticket) {
                    status.text = "Translation stopped"
                    targetText.text = "${error.cause?.message ?: error.message ?: error.javaClass.simpleName}"
                    Log.e("OfflineTranslator", "Local pipeline failed", error)
                }
            } finally {
                ui(ticket) { processing = false; refreshControls(); if (status.text.startsWith("Speaking")) status.text = "Ready — hold either talk button" }
            }
        }
    }

    private fun cancelPipeline() {
        generation.incrementAndGet()
        pendingInference?.cancel(false) // Clear queued recordings without interrupting a native speech call.
        pendingInference = null
        processing = false
        if (::gemma.isInitialized) gemma.cancel()
        if (::player.isInitialized) player.stop()
        if (::stopButton.isInitialized) refreshControls()
    }

    private fun rotate(lane: Int, direction: Int) {
        if (recordingLane != null || finishingRecording || !ready) return
        val index = lane - 1
        indices[index] = SupportedLanguages.rotate(indices[index], indices[1 - index], direction)
        refreshLanguages()
    }

    private fun pickLanguage(lane: Int) {
        if (recordingLane != null || !ready) return
        val available = SupportedLanguages.all.filterIndexed { index, _ -> index != indices[if (lane == 1) 1 else 0] }
        AlertDialog.Builder(this).setTitle("Person $lane language")
            .setItems(available.map { it.name }.toTypedArray()) { _, selection ->
                indices[lane - 1] = SupportedLanguages.all.indexOf(available[selection]); refreshLanguages()
            }.show()
    }

    private fun refreshLanguages() {
        for (lane in 1..2) languageButtons[lane - 1]?.apply {
            text = SupportedLanguages.all[indices[lane - 1]].name
            contentDescription = "Choose person $lane language: $text"
        }
        getPreferences(MODE_PRIVATE).edit().putInt("language1", indices[0]).putInt("language2", indices[1]).apply()
        refreshControls()
    }

    private fun refreshControls() {
        val enabled = ready && !ModelDownloadService.running && recordingLane == null && !finishingRecording
        arrows.forEach { it.isEnabled = enabled }
        languageButtons.forEach { it?.isEnabled = enabled }
        for (lane in 1..2) talkButtons[lane - 1]?.apply {
            isEnabled = ready && !ModelDownloadService.running && !finishingRecording && (recordingLane == null || recordingLane == lane)
            if (recordingLane != lane) text = "Hold to talk"
            else if (finishingRecording) text = "Finishing recording…"
            contentDescription = "Hold to talk in ${SupportedLanguages.all[indices[lane - 1]].name}, person $lane. Activate twice with accessibility to start and stop."
            background = background(recordingLane == lane)
            setTextColor(if (recordingLane == lane) orange else ink)
        }
        stopButton.visibility = if (processing || recordingLane != null) View.VISIBLE else View.GONE
    }

    private fun refreshSetup() {
        ready = store.missing().isEmpty()
        controlsScroller.visibility = if (ready) View.VISIBLE else View.GONE
        val current = ModelDownloadService.latest
        setupPanel.visibility = if (ready && !ModelDownloadService.running) View.GONE else View.VISIBLE
        setupProgress.isIndeterminate = ModelDownloadService.running && (current?.progress?.total ?: 0) == 0L
        setupProgress.progress = current?.progress?.let { if (it.total > 0) (it.completed * 100 / it.total).toInt().coerceIn(0, 100) else 0 } ?: 0
        setupButton.text = if (ModelDownloadService.running) "Pause setup" else if (current?.error != null) "Resume setup" else "Download offline models"
        setupMessage.text = if (ModelDownloadService.running && current != null) {
            "${current.progress.title}\n${current.progress.stage}\n${ModelStore.formatBytes(current.progress.completed)} / ${ModelStore.formatBytes(current.progress.total)}"
        } else if (current?.error != null && !ready) current.error
        else "One-time setup: ${ModelStore.formatBytes(ModelCatalog.all().sumOf { it.bytes })} of downloads for all nine languages. Keep at least 4.5 GB free. Models and recordings stay in this app. Internet is needed only for setup."
        if (recordingLane == null && !processing) status.text = when {
            ModelDownloadService.running -> "Installing offline models…"
            ready -> "Ready — all nine languages work offline"
            else -> "Complete offline setup to start"
        }
        refreshControls()
    }

    private fun toggleSetup() {
        if (ModelDownloadService.running) {
            startService(Intent(this, ModelDownloadService::class.java).setAction(ModelDownloadService.ACTION_PAUSE))
        } else {
            cancelRecording(); cancelPipeline()
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST)
            }
            startForegroundService(Intent(this, ModelDownloadService::class.java))
        }
        main.postDelayed({ if (!destroyed) refreshSetup() }, 250)
    }

    private fun showSettings() {
        cancelRecording()
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)); setBackgroundColor(orange) }
        panel.addView(CheckBox(this).apply {
            text = "Speak translations automatically"
            setTextColor(ink)
            isChecked = speechEnabled
            setOnCheckedChangeListener { _, checked -> speechEnabled = checked; getPreferences(MODE_PRIVATE).edit().putBoolean("speechEnabled", checked).apply() }
        })
        panel.addView(text("Recognition: multilingual Whisper small INT8\nTranslation: Gemma 4 E2B, local GPU with CPU fallback\nVoices: Supertonic 3, Kokoro Chinese, Piper Persian and Urdu\n\nAll processing stays on your phone. No speech or translations are uploaded. Voice recordings are kept only in memory and discarded after use.\n\nModels: ${store.root}\nModel licenses and source references are included in the app's Open-source notices.", 13f))
        AlertDialog.Builder(this).setTitle("Offline translator settings").setView(ScrollView(this).apply { addView(panel) })
            .setPositiveButton("Done", null)
            .setNeutralButton("Open-source notices") { _, _ -> showNotices() }
            .show()
    }

    private fun showNotices() {
        val notices = runCatching { assets.open("NOTICE.txt").bufferedReader().use { it.readText() } }
            .getOrDefault("See android/THIRD_PARTY_NOTICES.md in the source repository for runtime and voice licenses.")
        val view = text(notices, 12f).apply { setPadding(dp(16), dp(12), dp(16), dp(12)); setTextIsSelectable(true); setBackgroundColor(orange) }
        AlertDialog.Builder(this).setTitle("Open-source notices").setView(ScrollView(this).apply { addView(view) })
            .setPositiveButton("Close", null).setNeutralButton("License texts") { _, _ -> showLicenseList() }.show()
    }

    private fun showLicenseList() {
        val names = assets.list("licenses")?.sorted() ?: emptyList()
        AlertDialog.Builder(this).setTitle("Packaged licenses")
            .setItems(names.toTypedArray()) { _, index ->
                val license = assets.open("licenses/${names[index]}").bufferedReader().use { it.readText() }
                val view = text(license, 12f).apply {
                    setPadding(dp(16), dp(12), dp(16), dp(12)); setBackgroundColor(orange); setTextIsSelectable(true)
                }
                AlertDialog.Builder(this).setTitle(names[index]).setView(ScrollView(this).apply { addView(view) })
                    .setPositiveButton("Close", null).show()
            }.show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MICROPHONE_REQUEST) status.text = if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
            "Microphone ready — hold either talk button" else "Microphone permission is needed for voice input. Tap a talk button to retry."
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val lane = when (event.keyCode) { KeyEvent.KEYCODE_Z -> activePerson; KeyEvent.KEYCODE_X -> 2; else -> null }
        if (lane != null) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) startRecording(lane, "keyboard:${event.keyCode}")
            if (event.action == KeyEvent.ACTION_UP) finishRecording("keyboard:${event.keyCode}")
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && recordingLane == null) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { rotate(activePerson, -1); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { rotate(activePerson, 1); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showText(label: TextView, text: TextView, language: SupportedLanguage, role: String, value: String) {
        label.text = "${language.name.uppercase()} · $role"
        text.textDirection = if (language.rtl) View.TEXT_DIRECTION_RTL else View.TEXT_DIRECTION_LTR
        text.layoutDirection = if (language.rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        text.gravity = Gravity.START
        text.textLocale = java.util.Locale.forLanguageTag(language.code)
        text.text = value
    }

    private fun ui(ticket: Long? = null, action: () -> Unit) {
        main.post { if (!destroyed && (ticket == null || generation.get() == ticket)) action() }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun text(value: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(ink); gravity = Gravity.CENTER_VERTICAL
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setTextIsSelectable(size >= 18f)
    }
    private fun background(selected: Boolean = false) = GradientDrawable().apply {
        setColor(if (selected) ink else orange); setStroke(dp(1), ink); cornerRadius = dp(6).toFloat()
    }
    private fun button(value: String, click: () -> Unit) = Button(this).apply {
        text = value; textSize = 14f; setTextColor(ink); isAllCaps = false
        minimumWidth = 0; minWidth = 0; minHeight = dp(48)
        setPadding(dp(3), 0, dp(3), 0); background = background()
        setOnClickListener { click() }
    }
    private fun card(label: TextView, content: TextView) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10)); background = background()
        addView(label); addView(content)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
    }
    companion object { private const val MICROPHONE_REQUEST = 10; private const val NOTIFICATION_REQUEST = 11 }
}

/** Short first utterance; one later chunk may be synthesized during playback. */
object SpeechChunks {
    fun split(text: String): List<String> {
        var remaining = text.trim()
        val chunks = mutableListOf<String>()
        while (remaining.isNotEmpty()) {
            val maximum = if (chunks.isEmpty()) 90 else 180
            var end = minOf(maximum, remaining.length)
            if (end < remaining.length) {
                val boundary = (30 until end).firstOrNull { remaining[it] in ".!?؟۔。！？\n" }
                val whitespace = remaining.substring(0, end).indexOfLast { it.isWhitespace() }
                end = when { boundary != null -> boundary + 1; whitespace >= end / 2 -> whitespace; else -> end }
                if (end > 0 && remaining[end - 1].isHighSurrogate()) end--
            }
            chunks.add(remaining.substring(0, end).trim())
            remaining = remaining.substring(end).trimStart()
        }
        return chunks.filter { it.isNotEmpty() }
    }
}
