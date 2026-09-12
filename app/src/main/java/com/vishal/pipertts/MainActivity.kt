package com.vishal.pipertts

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import kotlin.concurrent.thread


class MainActivity : AppCompatActivity() {

    companion object {
        private const val MODEL_NAME = "en_US-ryan-high.onnx"
        private const val ASSET_ROOT = "ryan"

        private const val PREPARE_VERSION = "ryan-high-v1"

        private const val DEFAULT_SPEED = 1.0f
        private const val MIN_SPEED = 0.90f
        private const val MAX_SPEED = 2.00f
    }

    private lateinit var textInput: EditText
    private lateinit var characterCount: TextView
    private lateinit var speedLabel: TextView
    private lateinit var speedSeekBar: SeekBar

    private lateinit var generateButton: Button
    private lateinit var playButton: Button
    private lateinit var stopButton: Button
    private lateinit var saveButton: Button
    private lateinit var copyButton: Button

    private lateinit var statusText: TextView

    private var tts: OfflineTts? = null
    private var generatedWav: File? = null
    private var mediaPlayer: MediaPlayer? = null

    @Volatile
    private var isGenerating = false


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        initializeViews()
        setupListeners()

        setUiEnabled(false)

        statusText.text = "Preparing offline voice..."

        prepareTtsInBackground()
    }


    private fun initializeViews() {

        textInput = findViewById(R.id.textInput)
        characterCount = findViewById(R.id.characterCount)

        speedLabel = findViewById(R.id.speedLabel)
        speedSeekBar = findViewById(R.id.speedSeekBar)

        generateButton = findViewById(R.id.generateButton)
        playButton = findViewById(R.id.playButton)
        stopButton = findViewById(R.id.stopButton)
        saveButton = findViewById(R.id.saveButton)
        copyButton = findViewById(R.id.copyButton)

        statusText = findViewById(R.id.statusText)

        updateCharacterCount()

        updateSpeedLabel()

        playButton.isEnabled = false
        stopButton.isEnabled = false
        saveButton.isEnabled = false
    }


    private fun setupListeners() {

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
                    updateCharacterCount()
                }

                override fun afterTextChanged(
                    s: android.text.Editable?
                ) {
                }
            }
        )


        speedSeekBar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {

                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    updateSpeedLabel()
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


        generateButton.setOnClickListener {
            generateSpeech()
        }


        playButton.setOnClickListener {
            playGeneratedAudio()
        }


        stopButton.setOnClickListener {
            stopPlayback()
        }


        saveButton.setOnClickListener {
            saveGeneratedWav()
        }


        copyButton.setOnClickListener {
            copyText()
        }
    }


    private fun updateCharacterCount() {

        val count = textInput.text?.length ?: 0

        characterCount.text =
            String.format(
                Locale.US,
                "%d",
                count
            )
    }


    private fun updateSpeedLabel() {

        val speed = getSelectedSpeed()

        speedLabel.text =
            String.format(
                Locale.US,
                "Speed  %.2fx",
                speed
            )
    }


    private fun getSelectedSpeed(): Float {

        val progress = speedSeekBar.progress

        /*
         * SeekBar:
         * 50  = 0.50x
         * 100 = 1.00x
         * 150 = 1.50x
         *
         * The XML currently has max=150.
         */

        return (progress.coerceIn(50, 150) / 100.0f)
            .coerceIn(
                MIN_SPEED,
                MAX_SPEED
            )
    }


    private fun setUiEnabled(enabled: Boolean) {

        generateButton.isEnabled = enabled
        speedSeekBar.isEnabled = enabled
        textInput.isEnabled = enabled
        copyButton.isEnabled = true

        playButton.isEnabled =
            enabled && generatedWav?.exists() == true

        saveButton.isEnabled =
            enabled && generatedWav?.exists() == true

        stopButton.isEnabled =
            mediaPlayer?.isPlaying == true
    }


    private fun prepareTtsInBackground() {

        thread {

            try {

                val modelDirectory =
                    preparePiperFiles()

                val modelFile =
                    File(
                        modelDirectory,
                        MODEL_NAME
                    )

                val tokensFile =
                    File(
                        modelDirectory,
                        "tokens.txt"
                    )

                val dataDir =
                    File(
                        modelDirectory,
                        "espeak-ng-data"
                    )

                if (!modelFile.isFile) {
                    throw IOException(
                        "Piper model was not copied:\n${modelFile.absolutePath}"
                    )
                }

                if (!tokensFile.isFile) {
                    throw IOException(
                        "tokens.txt was not copied:\n${tokensFile.absolutePath}"
                    )
                }

                if (!dataDir.isDirectory) {
                    throw IOException(
                        "espeak-ng-data was not copied:\n${dataDir.absolutePath}"
                    )
                }


                /*
                 * IMPORTANT:
                 *
                 * sherpa-onnx Piper VITS requires these to be
                 * real filesystem paths.
                 *
                 * Do NOT use:
                 *
                 * assets/ryan/...
                 *
                 * for model, tokens or dataDir.
                 */

                val vitsConfig =
                    OfflineTtsVitsModelConfig(
                        model = modelFile.absolutePath,
                        tokens = tokensFile.absolutePath,
                        dataDir = dataDir.absolutePath
                    )


                val modelConfig =
                    OfflineTtsModelConfig(
                        vits = vitsConfig,
                        numThreads = 2,
                        debug = false
                    )


                val config =
                    OfflineTtsConfig(
                        model = modelConfig
                    )


                val createdTts =
                    OfflineTts(
                        config = config
                    )


                tts = createdTts


                runOnUiThread {

                    statusText.text =
                        "Ready • Ryan Medium • Fully Offline"

                    setUiEnabled(true)
                }

            } catch (e: Throwable) {

                val message =
                    e.message
                        ?: e.javaClass.simpleName

                runOnUiThread {

                    statusText.text =
                        "TTS initialization failed:\n$message"

                    setUiEnabled(false)
                }
            }
        }
    }


    private fun preparePiperFiles(): File {

        val baseDirectory =
            File(
                filesDir,
                "piper"
            )

        val markerFile =
            File(
                baseDirectory,
                ".prepared"
            )


        /*
         * If the app was previously installed with
         * Ryan High, Medium, etc., remove the old
         * files and prepare the correct model again.
         */

        val needsPreparation =
            !markerFile.isFile ||
            markerFile.readText(
                Charsets.UTF_8
            ).trim() != PREPARE_VERSION ||
            !File(
                baseDirectory,
                MODEL_NAME
            ).isFile ||
            !File(
                baseDirectory,
                "tokens.txt"
            ).isFile ||
            !File(
                baseDirectory,
                "espeak-ng-data"
            ).isDirectory


        if (needsPreparation) {

            if (baseDirectory.exists()) {
                baseDirectory.deleteRecursively()
            }

            baseDirectory.mkdirs()


            copyAssetFile(
                "$ASSET_ROOT/$MODEL_NAME",
                File(
                    baseDirectory,
                    MODEL_NAME
                )
            )


            copyAssetFile(
                "$ASSET_ROOT/tokens.txt",
                File(
                    baseDirectory,
                    "tokens.txt"
                )
            )


            copyAssetDirectory(
                "$ASSET_ROOT/espeak-ng-data",
                File(
                    baseDirectory,
                    "espeak-ng-data"
                )
            )


            markerFile.writeText(
                PREPARE_VERSION,
                Charsets.UTF_8
            )
        }


        return baseDirectory
    }


    private fun copyAssetDirectory(
        assetPath: String,
        destination: File
    ) {

        val children =
            assets.list(assetPath)
                ?: emptyArray()


        if (children.isEmpty()) {

            copyAssetFile(
                assetPath,
                destination
            )

            return
        }


        if (!destination.exists()) {
            destination.mkdirs()
        }


        for (child in children) {

            val childAssetPath =
                "$assetPath/$child"

            val childDestination =
                File(
                    destination,
                    child
                )


            val childChildren =
                assets.list(childAssetPath)
                    ?: emptyArray()


            if (childChildren.isEmpty()) {

                copyAssetFile(
                    childAssetPath,
                    childDestination
                )

            } else {

                copyAssetDirectory(
                    childAssetPath,
                    childDestination
                )
            }
        }
    }


    private fun copyAssetFile(
        assetPath: String,
        destination: File
    ) {

        destination.parentFile?.mkdirs()

        assets.open(assetPath).use { input ->

            FileOutputStream(
                destination
            ).use { output ->

                val buffer =
                    ByteArray(
                        32 * 1024
                    )

                while (true) {

                    val count =
                        input.read(buffer)

                    if (count <= 0) {
                        break
                    }

                    output.write(
                        buffer,
                        0,
                        count
                    )
                }

                output.flush()
            }
        }
    }


    private fun generateSpeech() {

        val inputText =
            textInput.text
                ?.toString()
                ?.trim()
                ?: ""


        if (inputText.isEmpty()) {

            statusText.text =
                "Enter some text first."

            textInput.requestFocus()

            return
        }




        val currentTts =
            tts

        if (currentTts == null) {

            statusText.text =
                "TTS is still preparing."

            return
        }


        if (isGenerating) {
            return
        }


        isGenerating = true

        stopPlayback()

        generatedWav = null

        runOnUiThread {

            generateButton.isEnabled = false
            playButton.isEnabled = false
            saveButton.isEnabled = false
            stopButton.isEnabled = false

            statusText.text =
                "Generating speech..."
        }


        val speed =
            getSelectedSpeed()


        thread {

            var audio:
                com.k2fsa.sherpa.onnx.GeneratedAudio? =
                null


            try {

                val outputFile =
                    File(
                        cacheDir,
                        "piper_output.wav"
                    )


                if (outputFile.exists()) {
                    outputFile.delete()
                }


                val generationConfig =
                    GenerationConfig(
                        sid = 0,
                        speed = speed,
                        silenceScale = 0.2f
                    )


                audio =
                    currentTts.generateWithConfig(
                        text = inputText,
                        config = generationConfig
                    )


                if (!audio.save(
                        filename = outputFile.absolutePath
                    )
                ) {

                    throw IOException(
                        "Failed to save generated WAV."
                    )
                }


                if (!outputFile.isFile ||
                    outputFile.length() <= 0
                ) {

                    throw IOException(
                        "Generated WAV is empty."
                    )
                }


                generatedWav =
                    outputFile


                runOnUiThread {

                    statusText.text =
                        String.format(
                            Locale.US,
                            "Speech generated • %.2fx",
                            speed
                        )

                    generateButton.isEnabled = true
                    playButton.isEnabled = true
                    saveButton.isEnabled = true
                    stopButton.isEnabled = false
                }


            } catch (e: Throwable) {

                val message =
                    e.message
                        ?: e.javaClass.simpleName


                runOnUiThread {

                    statusText.text =
                        "Generation failed:\n$message"

                    generateButton.isEnabled = true
                    playButton.isEnabled = false
                    saveButton.isEnabled = false
                    stopButton.isEnabled = false
                }

            } finally {

                audio = null

                isGenerating = false
            }
        }
    }


    private fun playGeneratedAudio() {

        val wav =
            generatedWav


        if (wav == null ||
            !wav.isFile
        ) {

            statusText.text =
                "Generate speech first."

            return
        }


        stopPlayback()


        try {

            mediaPlayer =
                MediaPlayer().apply {

                    setDataSource(
                        wav.absolutePath
                    )


                    setOnPreparedListener {

                        start()

                        runOnUiThread {

                            statusText.text =
                                "Playing..."
                            stopButton.isEnabled =
                                true
                        }
                    }


                    setOnCompletionListener {

                        runOnUiThread {

                            statusText.text =
                                "Playback finished."

                            stopButton.isEnabled =
                                false
                        }

                        release()

                        mediaPlayer = null
                    }


                    setOnErrorListener {
                        _, _, _ ->

                        runOnUiThread {

                            statusText.text =
                                "Playback error."

                            stopButton.isEnabled =
                                false
                        }

                        release()

                        mediaPlayer = null

                        true
                    }


                    prepareAsync()
                }


        } catch (e: Throwable) {

            statusText.text =
                "Playback failed:\n${e.message}"

            mediaPlayer?.release()

            mediaPlayer = null
        }
    }


    private fun stopPlayback() {

        val player =
            mediaPlayer


        if (player != null) {

            try {

                if (player.isPlaying) {
                    player.stop()
                }

            } catch (_: Throwable) {
            }


            try {
                player.release()
            } catch (_: Throwable) {
            }

            mediaPlayer = null
        }


        if (!isFinishing) {

            runOnUiThread {

                stopButton.isEnabled =
                    false
            }
        }
    }


    private fun saveGeneratedWav() {

        val source =
            generatedWav


        if (source == null ||
            !source.isFile
        ) {

            statusText.text =
                "Generate speech first."

            return
        }


        thread {

            try {

                val filename =
                    "Piper_${System.currentTimeMillis()}.wav"


                val resolver =
                    contentResolver


                val values =
                    ContentValues().apply {

                        put(
                            MediaStore.MediaColumns.DISPLAY_NAME,
                            filename
                        )

                        put(
                            MediaStore.MediaColumns.MIME_TYPE,
                            "audio/wav"
                        )

                        put(
                            MediaStore.MediaColumns.RELATIVE_PATH,
                            Environment.DIRECTORY_MUSIC +
                                "/Piper TTS"
                        )
                    }


                val uri =
                    resolver.insert(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        values
                    )


                if (uri == null) {

                    throw IOException(
                        "Could not create output file."
                    )
                }


                try {

                    resolver.openOutputStream(uri).use { output ->

                        if (output == null) {

                            throw IOException(
                                "Could not open output stream."
                            )
                        }


                        FileInputStream(
                            source
                        ).use { input ->

                            input.copyTo(
                                output
                            )
                        }
                    }

                } catch (e: Throwable) {

                    resolver.delete(
                        uri,
                        null,
                        null
                    )

                    throw e
                }


                runOnUiThread {

                    statusText.text =
                        "Saved to Music/Piper TTS/$filename"
                }


            } catch (e: Throwable) {

                runOnUiThread {

                    statusText.text =
                        "Save failed:\n${e.message}"
                }
            }
        }
    }


    private fun copyText() {

        val text =
            textInput.text
                ?.toString()
                ?.trim()
                ?: ""


        if (text.isEmpty()) {

            statusText.text =
                "Nothing to copy."

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


        statusText.text =
            "Text copied."
    }


    override fun onDestroy() {

        stopPlayback()


        try {
            tts?.release()
        } catch (_: Throwable) {
        }


        tts = null

        super.onDestroy()
    }
}
