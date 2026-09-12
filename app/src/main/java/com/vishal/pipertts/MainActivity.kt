package com.vishal.pipertts

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var textInput: EditText
    private lateinit var characterCount: TextView
    private lateinit var speedLabel: TextView
    private lateinit var statusText: TextView

    private lateinit var generateButton: Button
    private lateinit var playButton: Button
    private lateinit var stopButton: Button
    private lateinit var saveButton: Button
    private lateinit var copyButton: Button

    private var tts: OfflineTts? = null

    private var generatedSamples: FloatArray? = null
    private var generatedSampleRate = 22050

    private var audioTrack: AudioTrack? = null

    private val executor =
        Executors.newSingleThreadExecutor()

    @Volatile
    private var generating = false

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        setContentView(
            R.layout.activity_main
        )

        initializeViews()
        initializeTextCounter()
        initializeSpeed()

        generateButton.setOnClickListener {
            generateSpeech()
        }

        playButton.setOnClickListener {
            playAudio()
        }

        stopButton.setOnClickListener {
            stopAudio()
        }

        saveButton.setOnClickListener {
            saveAudio()
        }

        copyButton.setOnClickListener {
            copyText()
        }

        initializeTts()
    }

    private fun initializeViews() {

        textInput =
            findViewById(R.id.textInput)

        characterCount =
            findViewById(R.id.characterCount)

        speedLabel =
            findViewById(R.id.speedLabel)

        statusText =
            findViewById(R.id.statusText)

        generateButton =
            findViewById(R.id.generateButton)

        playButton =
            findViewById(R.id.playButton)

        stopButton =
            findViewById(R.id.stopButton)

        saveButton =
            findViewById(R.id.saveButton)

        copyButton =
            findViewById(R.id.copyButton)

        generateButton.isEnabled = false
        playButton.isEnabled = false
        saveButton.isEnabled = false
    }

    private fun initializeTextCounter() {

        textInput.addTextChangedListener(
            object : android.text.TextWatcher {

                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) {
                }

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int
                ) {
                    characterCount.text =
                        "${s?.length ?: 0} / 5000"
                }

                override fun afterTextChanged(
                    s: android.text.Editable?
                ) {
                }
            }
        )

        characterCount.text =
            "${textInput.text.length} / 5000"
    }

    private fun initializeSpeed() {

        val seekBar =
            findViewById<SeekBar>(
                R.id.speedSeekBar
            )

        seekBar.progress = 50

        updateSpeedLabel(50)

        seekBar.setOnSeekBarChangeListener(
            object :
                SeekBar.OnSeekBarChangeListener {

                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    updateSpeedLabel(progress)
                }

                override fun onStartTrackingTouch(
                    seekBar: SeekBar?
                ) {
                }

                override fun onStopTrackingTouch(
                    seekBar: SeekBar?
                ) {
                }
            }
        )
    }

    private fun updateSpeedLabel(
        progress: Int
    ) {

        val speed =
            0.50f +
                progress / 100f

        speedLabel.text =
            String.format(
                "Speed: %.2fx",
                speed
            )
    }

    private fun getSpeed(): Float {

        val progress =
            findViewById<SeekBar>(
                R.id.speedSeekBar
            ).progress

        return 0.50f +
            progress / 100f
    }

    private fun initializeTts() {

        statusText.text =
            "Loading Piper TTS model..."

        generateButton.isEnabled = false
        playButton.isEnabled = false
        saveButton.isEnabled = false

        executor.execute {

            try {

                val modelPath =
                    "ryan/en_US-ryan-medium.onnx"

                val tokensPath =
                    "ryan/tokens.txt"

                val dataPath =
                    "ryan/espeak-ng-data"

                verifyAsset(modelPath)
                verifyAsset(tokensPath)
                verifyAsset(dataPath)

                runOnUiThread {
                    statusText.text =
                        "Loading Ryan Medium..."
                }

                val vitsConfig =
                    OfflineTtsVitsModelConfig(
                        model = modelPath,
                        tokens = tokensPath,
                        dataDir = dataPath,
                        noiseScale = 0.667f,
                        noiseScaleW = 0.8f,
                        lengthScale = 1.0f
                    )

                val modelConfig =
                    OfflineTtsModelConfig(
                        vits = vitsConfig,
                        numThreads = 2,
                        debug = false,
                        provider = "cpu"
                    )

                val config =
                    OfflineTtsConfig(
                        model = modelConfig
                    )

                val engine =
                    OfflineTts(
                        assets,
                        config
                    )

                val sampleRate =
                    engine.sampleRate()

                tts = engine

                generatedSampleRate =
                    sampleRate

                runOnUiThread {

                    statusText.text =
                        "Ready • Ryan Medium • ${sampleRate} Hz"

                    generateButton.isEnabled =
                        true
                }

            } catch (e: Throwable) {

                val message =
                    getErrorMessage(e)

                runOnUiThread {

                    statusText.text =
                        "Piper TTS failed\n\n$message"

                    generateButton.isEnabled =
                        false

                    playButton.isEnabled =
                        false

                    saveButton.isEnabled =
                        false

                    Toast.makeText(
                        this,
                        "Piper TTS failed to load",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun verifyAsset(
        path: String
    ) {

        try {

            assets.open(path).use {
                return
            }

        } catch (_: IOException) {
        }

        try {

            val children =
                assets.list(path)

            if (
                children == null ||
                children.isEmpty()
            ) {
                throw IOException(
                    "Missing asset: $path"
                )
            }

        } catch (e: Exception) {

            throw IOException(
                "Missing asset: $path",
                e
            )
        }
    }

    private fun getErrorMessage(
        throwable: Throwable
    ): String {

        val result =
            StringBuilder()

        result.append(
            throwable.javaClass.simpleName
        )

        result.append("\n")

        result.append(
            throwable.message
                ?: "Unknown error"
        )

        var cause =
            throwable.cause

        var count = 0

        while (
            cause != null &&
            count < 3
        ) {

            result.append(
                "\n\nCaused by: "
            )

            result.append(
                cause.javaClass.simpleName
            )

            result.append(
                "\n"
            )

            result.append(
                cause.message
                    ?: "Unknown cause"
            )

            cause =
                cause.cause

            count++
        }

        return result.toString()
    }

    private fun generateSpeech() {

        if (generating) {
            return
        }

        val engine =
            tts

        if (engine == null) {

            Toast.makeText(
                this,
                "Piper TTS is still loading",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        val text =
            textInput.text
                .toString()
                .trim()

        if (text.isEmpty()) {

            Toast.makeText(
                this,
                "Enter some text first",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        hideKeyboard()

        generating = true

        generateButton.isEnabled = false
        playButton.isEnabled = false
        saveButton.isEnabled = false

        statusText.text =
            "Generating speech..."

        val speed =
            getSpeed()

        executor.execute {

            try {

                val generation =
                    GenerationConfig(
                        silenceScale = 0.2f,
                        speed = speed,
                        sid = 0
                    )

                val audio =
                    engine.generateWithConfig(
                        text,
                        generation
                    )

                generatedSamples =
                    audio.samples

                generatedSampleRate =
                    audio.sampleRate

                val duration =
                    audio.samples.size.toFloat() /
                        audio.sampleRate

                runOnUiThread {

                    statusText.text =
                        String.format(
                            "Ready • %.2f seconds",
                            duration
                        )

                    playButton.isEnabled =
                        true

                    saveButton.isEnabled =
                        true
                }

            } catch (e: Throwable) {

                val message =
                    getErrorMessage(e)

                runOnUiThread {

                    statusText.text =
                        "Generation failed\n\n$message"

                    Toast.makeText(
                        this,
                        "Speech generation failed",
                        Toast.LENGTH_LONG
                    ).show()
                }

            } finally {

                generating = false

                runOnUiThread {
                    generateButton.isEnabled =
                        tts != null
                }
            }
        }
    }

    private fun playAudio() {

        val samples =
            generatedSamples
                ?: return

        stopAudio()

        try {

            val minBuffer =
                AudioTrack.getMinBufferSize(
                    generatedSampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_FLOAT
                )

            audioTrack =
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(
                                AudioAttributes.USAGE_MEDIA
                            )
                            .setContentType(
                                AudioAttributes.CONTENT_TYPE_SPEECH
                            )
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(
                                generatedSampleRate
                            )
                            .setEncoding(
                                AudioFormat.ENCODING_PCM_FLOAT
                            )
                            .setChannelMask(
                                AudioFormat.CHANNEL_OUT_MONO
                            )
                            .build()
                    )
                    .setBufferSizeInBytes(
                        maxOf(
                            minBuffer,
                            samples.size * 4
                        )
                    )
                    .setTransferMode(
                        AudioTrack.MODE_STATIC
                    )
                    .build()

            audioTrack?.write(
                samples,
                0,
                samples.size,
                AudioTrack.WRITE_BLOCKING
            )

            audioTrack?.play()

            statusText.text =
                "Playing"

        } catch (e: Throwable) {

            statusText.text =
                "Playback failed\n\n" +
                    getErrorMessage(e)
        }
    }

    private fun stopAudio() {

        try {
            audioTrack?.stop()
        } catch (_: Throwable) {
        }

        try {
            audioTrack?.release()
        } catch (_: Throwable) {
        }

        audioTrack = null
    }

    private fun saveAudio() {

        val samples =
            generatedSamples

        if (samples == null) {

            Toast.makeText(
                this,
                "Generate speech first",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        executor.execute {

            var uri: android.net.Uri? = null

            try {

                val fileName =
                    "piper_${System.currentTimeMillis()}.wav"

                val values =
                    ContentValues().apply {

                        put(
                            MediaStore.Downloads.DISPLAY_NAME,
                            fileName
                        )

                        put(
                            MediaStore.Downloads.MIME_TYPE,
                            "audio/wav"
                        )

                        put(
                            MediaStore.Downloads.RELATIVE_PATH,
                            Environment.DIRECTORY_DOWNLOADS +
                                "/PiperTTS"
                        )
                    }

                uri =
                    contentResolver.insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        values
                    )
                        ?: throw IOException(
                            "Could not create WAV file"
                        )

                contentResolver
                    .openOutputStream(uri)
                    ?.use { output ->

                        writeWav(
                            output,
                            samples,
                            generatedSampleRate
                        )
                    }
                    ?: throw IOException(
                        "Could not open WAV file"
                    )

                runOnUiThread {

                    Toast.makeText(
                        this,
                        "Saved to Downloads/PiperTTS",
                        Toast.LENGTH_LONG
                    ).show()
                }

            } catch (e: Throwable) {

                if (uri != null) {

                    try {
                        contentResolver.delete(
                            uri,
                            null,
                            null
                        )
                    } catch (_: Throwable) {
                    }
                }

                runOnUiThread {

                    statusText.text =
                        "Save failed\n\n" +
                            getErrorMessage(e)
                }
            }
        }
    }

    private fun writeWav(
        output: OutputStream,
        samples: FloatArray,
        sampleRate: Int
    ) {

        val pcmSize =
            samples.size * 2

        val byteRate =
            sampleRate * 2

        val header =
            ByteArray(44)

        header[0] =
            'R'.code.toByte()

        header[1] =
            'I'.code.toByte()

        header[2] =
            'F'.code.toByte()

        header[3] =
            'F'.code.toByte()

        writeIntLE(
            header,
            4,
            36 + pcmSize
        )

        header[8] =
            'W'.code.toByte()

        header[9] =
            'A'.code.toByte()

        header[10] =
            'V'.code.toByte()

        header[11] =
            'E'.code.toByte()

        header[12] =
            'f'.code.toByte()

        header[13] =
            'm'.code.toByte()

        header[14] =
            't'.code.toByte()

        header[15] =
            ' '.code.toByte()

        writeIntLE(
            header,
            16,
            16
        )

        writeShortLE(
            header,
            20,
            1
        )

        writeShortLE(
            header,
            22,
            1
        )

        writeIntLE(
            header,
            24,
            sampleRate
        )

        writeIntLE(
            header,
            28,
            byteRate
        )

        writeShortLE(
            header,
            32,
            2
        )

        writeShortLE(
            header,
            34,
            16
        )

        header[36] =
            'd'.code.toByte()

        header[37] =
            'a'.code.toByte()

        header[38] =
            't'.code.toByte()

        header[39] =
            'a'.code.toByte()

        writeIntLE(
            header,
            40,
            pcmSize
        )

        output.write(header)

        val buffer =
            ByteArray(8192)

        var index = 0

        while (
            index < samples.size
        ) {

            val count =
                minOf(
                    buffer.size / 2,
                    samples.size - index
                )

            for (i in 0 until count) {

                val sample =
                    samples[index + i]
                        .coerceIn(-1f, 1f)

                val value =
                    (sample * 32767f)
                        .toInt()
                        .toShort()

                buffer[i * 2] =
                    (value.toInt() and 0xFF)
                        .toByte()

                buffer[i * 2 + 1] =
                    ((value.toInt() shr 8) and 0xFF)
                        .toByte()
            }

            output.write(
                buffer,
                0,
                count * 2
            )

            index += count
        }
    }

    private fun writeIntLE(
        buffer: ByteArray,
        offset: Int,
        value: Int
    ) {

        buffer[offset] =
            (value and 0xFF).toByte()

        buffer[offset + 1] =
            ((value shr 8) and 0xFF).toByte()

        buffer[offset + 2] =
            ((value shr 16) and 0xFF).toByte()

        buffer[offset + 3] =
            ((value shr 24) and 0xFF).toByte()
    }

    private fun writeShortLE(
        buffer: ByteArray,
        offset: Int,
        value: Int
    ) {

        buffer[offset] =
            (value and 0xFF).toByte()

        buffer[offset + 1] =
            ((value shr 8) and 0xFF).toByte()
    }

    private fun copyText() {

        val text =
            textInput.text
                .toString()

        if (text.isEmpty()) {
            return
        }

        val clipboard =
            getSystemService(
                Context.CLIPBOARD_SERVICE
            ) as ClipboardManager

        clipboard.setPrimaryClip(
            ClipData.newPlainText(
                "Piper TTS",
                text
            )
        )

        Toast.makeText(
            this,
            "Text copied",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun hideKeyboard() {

        val imm =
            getSystemService(
                Context.INPUT_METHOD_SERVICE
            ) as InputMethodManager

        imm.hideSoftInputFromWindow(
            textInput.windowToken,
            0
        )

        textInput.clearFocus()
    }

    override fun onDestroy() {

        stopAudio()

        try {
            tts?.release()
        } catch (_: Throwable) {
        }

        tts = null

        executor.shutdownNow()

        super.onDestroy()
    }
}
