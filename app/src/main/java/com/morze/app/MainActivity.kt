package com.morze.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.morze.app.dsp.Alphabet
import com.morze.app.dsp.MorseAudioProcessor
import com.morze.app.dsp.MorseTimingDecoder
import com.morze.app.text.GroupFormat
import kotlin.math.roundToInt

class MainActivity : Activity(), MorseTimingDecoder.Listener {

    private lateinit var indicator: View
    private lateinit var status: TextView
    private lateinit var level: ProgressBar
    private lateinit var levelValue: TextView
    private lateinit var currentCode: TextView
    private lateinit var textScroll: ScrollView
    private lateinit var decodedText: TextView
    private lateinit var alphabetGroup: RadioGroup
    private lateinit var groupMode: CheckBox
    private lateinit var autoFrequency: CheckBox
    private lateinit var frequencyBar: SeekBar
    private lateinit var frequencyValue: TextView
    private lateinit var sensitivityBar: SeekBar
    private lateinit var autoSpeed: CheckBox
    private lateinit var speedBar: SeekBar
    private lateinit var speedValue: TextView
    private lateinit var volumeBar: SeekBar
    private lateinit var volumeValue: TextView
    private lateinit var startStop: Button

    private lateinit var prefs: SharedPreferences
    private val mainHandler = Handler(Looper.getMainLooper())
    private val text = StringBuilder()
    private val groupFormat = GroupFormat()
    private var defaultTextSizePx = 0f

    private val processor = MorseAudioProcessor(SAMPLE_RATE, this)

    @Volatile
    private var listening = false
    private var audioThread: Thread? = null

    private val statusUpdater = object : Runnable {
        override fun run() {
            updateStatus()
            if (listening) mainHandler.postDelayed(this, STATUS_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        indicator = findViewById(R.id.indicator)
        status = findViewById(R.id.status)
        level = findViewById(R.id.level)
        levelValue = findViewById(R.id.levelValue)
        currentCode = findViewById(R.id.currentCode)
        textScroll = findViewById(R.id.textScroll)
        decodedText = findViewById(R.id.decodedText)
        alphabetGroup = findViewById(R.id.alphabet)
        groupMode = findViewById(R.id.groupMode)
        autoFrequency = findViewById(R.id.autoFrequency)
        frequencyBar = findViewById(R.id.frequency)
        frequencyValue = findViewById(R.id.frequencyValue)
        sensitivityBar = findViewById(R.id.sensitivity)
        autoSpeed = findViewById(R.id.autoSpeed)
        speedBar = findViewById(R.id.speed)
        speedValue = findViewById(R.id.speedValue)
        volumeBar = findViewById(R.id.volumeThreshold)
        volumeValue = findViewById(R.id.volumeThresholdValue)
        startStop = findViewById(R.id.startStop)

        applySystemBarInsets(findViewById(R.id.root))
        prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        setupControls()

        savedInstanceState?.getString(STATE_TEXT)?.let {
            text.append(it)
            showText()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_TEXT, text.toString())
    }

    override fun onStop() {
        super.onStop()
        stopListening()
    }

    private fun setupControls() {
        val alphabet = Alphabet.valueOf(prefs.getString(PREF_ALPHABET, Alphabet.CYRILLIC.name)!!)
        alphabetGroup.check(if (alphabet == Alphabet.LATIN) R.id.alphabetLatin else R.id.alphabetCyrillic)
        processor.alphabet = alphabet
        alphabetGroup.setOnCheckedChangeListener { _, checkedId ->
            val selected = if (checkedId == R.id.alphabetLatin) Alphabet.LATIN else Alphabet.CYRILLIC
            processor.alphabet = selected
            prefs.edit().putString(PREF_ALPHABET, selected.name).apply()
        }

        defaultTextSizePx = decodedText.textSize
        groupMode.isChecked = prefs.getBoolean(PREF_GROUP_MODE, true)
        groupMode.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(PREF_GROUP_MODE, checked).apply()
            fitTextSize()
            showText()
        }
        // Шрифт подбирается под ширину поля, чтобы 5 групп помещались в строку.
        decodedText.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) fitTextSize()
        }

        frequencyBar.max = (MAX_FREQUENCY - MIN_FREQUENCY) / FREQUENCY_STEP
        frequencyBar.progress = (prefs.getInt(PREF_FREQUENCY, 700) - MIN_FREQUENCY) / FREQUENCY_STEP
        autoFrequency.isChecked = prefs.getBoolean(PREF_AUTO_FREQUENCY, true)
        applyFrequency()
        autoFrequency.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(PREF_AUTO_FREQUENCY, checked).apply()
            applyFrequency()
        }
        frequencyBar.setOnSeekBarChangeListener(object : SimpleSeekBarListener() {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                prefs.edit().putInt(PREF_FREQUENCY, manualFrequency()).apply()
                applyFrequency()
            }
        })

        speedBar.max = MAX_SPEED - MIN_SPEED
        speedBar.progress = prefs.getInt(PREF_SPEED, 20) - MIN_SPEED
        autoSpeed.isChecked = prefs.getBoolean(PREF_AUTO_SPEED, true)
        applySpeed()
        autoSpeed.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(PREF_AUTO_SPEED, checked).apply()
            applySpeed()
        }
        speedBar.setOnSeekBarChangeListener(object : SimpleSeekBarListener() {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                prefs.edit().putInt(PREF_SPEED, selectedSpeed()).apply()
                applySpeed()
            }
        })

        volumeBar.max = MAX_VOLUME_DB - MIN_VOLUME_DB
        volumeBar.progress = prefs.getInt(PREF_VOLUME, MIN_VOLUME_DB) - MIN_VOLUME_DB
        applyVolumeThreshold()
        volumeBar.setOnSeekBarChangeListener(object : SimpleSeekBarListener() {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                prefs.edit().putInt(PREF_VOLUME, selectedVolumeDb()).apply()
                applyVolumeThreshold()
            }
        })

        sensitivityBar.progress = prefs.getInt(PREF_SENSITIVITY, 16)
        processor.minSnrDb = snrForSensitivity(sensitivityBar.progress)
        sensitivityBar.setOnSeekBarChangeListener(object : SimpleSeekBarListener() {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                processor.minSnrDb = snrForSensitivity(progress)
                prefs.edit().putInt(PREF_SENSITIVITY, progress).apply()
            }
        })

        startStop.setOnClickListener {
            if (listening) stopListening() else startListeningWithPermission()
        }
        findViewById<Button>(R.id.clear).setOnClickListener {
            text.clear()
            showText()
            currentCode.text = ""
        }
        findViewById<Button>(R.id.copy).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), displayedText()))
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun manualFrequency() = MIN_FREQUENCY + frequencyBar.progress * FREQUENCY_STEP

    private fun applyFrequency() {
        val auto = autoFrequency.isChecked
        frequencyBar.isEnabled = !auto
        processor.manualFrequency = if (auto) 0.0 else manualFrequency().toDouble()
        frequencyValue.text = getString(R.string.frequency_value, manualFrequency())
        frequencyValue.alpha = if (auto) 0.4f else 1f
    }

    private fun selectedSpeed() = MIN_SPEED + speedBar.progress

    private fun applySpeed() {
        val auto = autoSpeed.isChecked
        // В авто-режиме ползунок задаёт скорость, с которой начинается подстройка.
        processor.fixedWpm = if (auto) 0.0 else selectedSpeed().toDouble()
        speedValue.text = getString(R.string.speed_value, selectedSpeed())
    }

    private fun selectedVolumeDb() = MIN_VOLUME_DB + volumeBar.progress

    /** Крайнее левое положение ползунка — порог выключен. */
    private fun volumeThresholdEnabled() = volumeBar.progress > 0

    private fun applyVolumeThreshold() {
        val enabled = volumeThresholdEnabled()
        processor.minLevelDb = if (enabled) selectedVolumeDb().toDouble() else Double.NEGATIVE_INFINITY
        volumeValue.text = if (enabled) {
            getString(R.string.decibels, selectedVolumeDb())
        } else {
            getString(R.string.volume_threshold_off)
        }
        level.secondaryProgress = if (enabled) levelPercent(selectedVolumeDb().toDouble()) else 0
    }

    /** Положение уровня [db] на шкале индикатора: от [MIN_VOLUME_DB] до 0 дБ. */
    private fun levelPercent(db: Double) = ((db - MIN_VOLUME_DB) / -MIN_VOLUME_DB * 100).toInt().coerceIn(0, 100)

    /** Чем выше чувствительность, тем меньшее превышение над шумом считается тоном. */
    private fun snrForSensitivity(progress: Int) = 26.0 - progress

    private fun startListeningWithPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO)
            return
        }
        startListening()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_AUDIO) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else {
            Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    private fun startListening() {
        val record = openAudioRecord()
        if (record == null) {
            Toast.makeText(this, R.string.mic_error, Toast.LENGTH_LONG).show()
            return
        }
        listening = true
        startStop.setText(R.string.stop)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        mainHandler.post(statusUpdater)

        audioThread = Thread({ recordLoop(record) }, "morse-audio").apply { start() }
    }

    private fun stopListening() {
        if (!listening) return
        listening = false
        audioThread?.join(1000)
        audioThread = null
        startStop.setText(R.string.start)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        mainHandler.removeCallbacks(statusUpdater)
        updateStatus()
    }

    @SuppressLint("MissingPermission")
    private fun openAudioRecord(): AudioRecord? {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return null
        val bufferSize = maxOf(minBuffer, SAMPLE_RATE / 2 * 2)
        // VOICE_RECOGNITION обычно отключает шумоподавление и АРУ, которые «съедают» тон.
        for (source in listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
            val record = try {
                AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
            } catch (e: Exception) {
                null
            }
            if (record?.state == AudioRecord.STATE_INITIALIZED) return record
            record?.release()
        }
        return null
    }

    private fun recordLoop(record: AudioRecord) {
        val buffer = ShortArray(SAMPLE_RATE / 50)
        processor.reset(selectedSpeed().toDouble())
        try {
            record.startRecording()
            while (listening) {
                val count = record.read(buffer, 0, buffer.size)
                if (count > 0) processor.process(buffer, count)
            }
            processor.flush()
        } catch (e: Exception) {
            mainHandler.post {
                Toast.makeText(this, R.string.mic_error, Toast.LENGTH_LONG).show()
                stopListening()
            }
        } finally {
            try {
                record.stop()
            } catch (e: IllegalStateException) {
            }
            record.release()
        }
    }

    private fun updateStatus() {
        val frequency = processor.frequency
        status.text = when {
            !listening -> getString(R.string.status_idle)
            frequency <= 0 -> getString(R.string.status_searching)
            else -> {
                getString(R.string.status_listening, frequency, processor.decoder.wpm)
            }
        }

        val detector = processor.keyDetector
        val levelDb = processor.levelDb
        if (!listening || levelDb.isNaN()) {
            level.progress = 0
            levelValue.text = ""
        } else {
            level.progress = levelPercent(levelDb)
            levelValue.text = getString(R.string.decibels, levelDb.roundToInt())
        }

        val on = listening && detector.keyDown
        (indicator.background.mutate() as GradientDrawable)
            .setColor(getColor(if (on) R.color.signal_on else R.color.signal_off))
    }

    // MorseTimingDecoder.Listener — вызывается из потока записи.

    override fun onCodeChanged(code: String) {
        mainHandler.post { currentCode.text = code.replace('.', '•').replace('-', '−') }
    }

    override fun onCharacter(code: String, text: String) {
        mainHandler.post { appendText(text) }
    }

    override fun onWordSpace() {
        mainHandler.post { if (text.isNotEmpty() && !text.endsWith(" ")) appendText(" ") }
    }

    override fun onErrorCode() {
        mainHandler.post {
            // Стираем последнюю группу (или слово), как принято при приёме кода «ошибка».
            if (groupMode.isChecked) {
                text.setLength(text.length - groupFormat.lastGroupLength(text))
            } else {
                val trimmed = text.trimEnd()
                val lastSpace = trimmed.lastIndexOf(' ')
                text.setLength(if (lastSpace >= 0) lastSpace + 1 else 0)
            }
            showText()
        }
    }

    private fun appendText(fragment: String) {
        text.append(fragment)
        showText()
        textScroll.post { textScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun displayedText(): String =
        if (groupMode.isChecked) groupFormat.format(text) else text.toString().trim()

    private fun showText() {
        decodedText.text = if (groupMode.isChecked) groupFormat.format(text) else text
    }

    /** В режиме групп уменьшает шрифт так, чтобы строка из 5 групп влезала по ширине. */
    private fun fitTextSize() {
        val available = decodedText.width - decodedText.paddingLeft - decodedText.paddingRight
        if (available <= 0) return
        var size = defaultTextSizePx
        if (groupMode.isChecked) {
            val paint = Paint(decodedText.paint)
            paint.textSize = defaultTextSizePx
            // Самые широкие буквы, чтобы любая строка гарантированно помещалась.
            val widest = "Ш".repeat(groupFormat.lineLength)
            size = minOf(size, defaultTextSizePx * available / paint.measureText(widest) * 0.98f)
        }
        decodedText.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
    }

    private fun applySystemBarInsets(root: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val base = root.paddingTop
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(base + bars.left, base + bars.top, base + bars.right, base + bars.bottom)
            insets
        }
    }

    private abstract class SimpleSeekBarListener : SeekBar.OnSeekBarChangeListener {
        override fun onStartTrackingTouch(seekBar: SeekBar) {}
        override fun onStopTrackingTouch(seekBar: SeekBar) {}
    }

    companion object {
        private const val SAMPLE_RATE = 44100
        private const val REQUEST_AUDIO = 1
        private const val STATUS_INTERVAL_MS = 50L
        private const val MIN_FREQUENCY = 200
        private const val MAX_FREQUENCY = 2000
        private const val FREQUENCY_STEP = 10
        private const val STATE_TEXT = "text"
        private const val PREF_ALPHABET = "alphabet"
        private const val PREF_AUTO_FREQUENCY = "auto_frequency"
        private const val PREF_FREQUENCY = "frequency"
        private const val PREF_SENSITIVITY = "sensitivity"
        private const val PREF_GROUP_MODE = "group_mode"
        private const val PREF_AUTO_SPEED = "auto_speed"
        private const val PREF_SPEED = "speed"
        private const val PREF_VOLUME = "volume_threshold"
        private const val MIN_SPEED = 5
        private const val MAX_SPEED = 60
        private const val MIN_VOLUME_DB = -100
        private const val MAX_VOLUME_DB = -10
    }
}
