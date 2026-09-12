package com.vishal.pipertts

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
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
import java.io.RandomAccessFile
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    companion object {
        private const val MODEL_NAME = "en_US-ryan-high.onnx"
        private const val ASSET_ROOT = "ryan"

        private const val PREPARE_VERSION = "ryan-high-v4"

        private const val MAX_TEXT_LENGTH = 20_000

        private const val MIN_SPEED = 0.80f
        private const val MAX_SPEED = 1.50f

        /*
         * Text is generated in smaller sections.
         * This prevents very large requests from consuming
         * excessive memory and improves reliability.
         */
        private const val MAX_CHUNK_CHARACTERS = 700

        /*
         * 0.2 is the sherpa-onnx default sentence silence scale.
         * Keeping the default gives natural sentence spacing.
         */
        private const val SILENCE_SCALE = 0.20f
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

    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView

    private var tts: OfflineTts? = null
    private var generatedWav: File? = null
    private var mediaPlayer: MediaPlayer? = null

    @Volatile
    private var isGenerating = false

    @Volatile
    private var generationCancelled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        initializeViews()
        setupListeners()

        setUiEnabled(false)

        statusText.text =
            "Preparing Ryan High offline voice..."

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

        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)

        updateCharacterCount()
        updateSpeedLabel()

        playButton.isEnabled = false
        stopButton.isEnabled = false
        saveButton.isEnabled = false

        progressBar.visibility = ProgressBar.GONE
        progressBar.progress = 0
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

            if (isGenerating) {

                generationCancelled = true

                statusText.text =
                    "Stopping generation..."

            } else {

                stopPlayback()
            }
        }

        saveButton.setOnClickListener {
            saveGeneratedWav()
        }

        copyButton.setOnClickListener {
            copyText()
        }
    }

    private fun updateCharacterCount() {

        val count =
            textInput.text?.length ?: 0

        characterCount.text =
            String.format(
                Locale.US,
                "%,d / %,d",
                count,
                MAX_TEXT_LENGTH
            )
    }

    private fun updateSpeedLabel() {

        speedLabel.text =
            String.format(
                Locale.US,
                "Speed  %.2fx",
                getSelectedSpeed()
            )
    }

    private fun getSelectedSpeed(): Float {

        /*
         * SeekBar is expected to use:
         * min = 0
         * max = 200
         *
         * 0    = 0.80x
         * 100  = 1.15x
         * 200  = 1.50x
         *
         * The XML should start at progress 57
         * for approximately 1.00x.
         */

        val progress =
            speedSeekBar.progress.coerceIn(0, 200)

        return MIN_SPEED +
                (progress / 200.0f) *
                (MAX_SPEED - MIN_SPEED)
    }

    private fun setUiEnabled(enabled: Boolean) {

        generateButton.isEnabled =
            enabled && !isGenerating

        speedSeekBar.isEnabled =
            enabled && !isGenerating

        textInput.isEnabled =
            enabled && !isGenerating

        copyButton.isEnabled = true

        playButton.isEnabled =
            enabled &&
                    !isGenerating &&
                    generatedWav?.isFile == true

        saveButton.isEnabled =
            enabled &&
                    !isGenerating &&
                    generatedWav?.isFile == true

        stopButton.isEnabled =
            isGenerating ||
                    mediaPlayer != null
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

                val cpuCount =
                    Runtime.getRuntime()
                        .availableProcessors()

                /*
                 * Keep the number of inference threads reasonable.
                 * Too many threads can actually make low-end phones
                 * slower because of thermal throttling.
                 */
                val threadCount =
                    cpuCount.coerceIn(2, 4)

                val vitsConfig =
                    OfflineTtsVitsModelConfig(
                        model =
                            modelFile.absolutePath,
                        tokens =
                            tokensFile.absolutePath,
                        dataDir =
                            dataDir.absolutePath
                    )

                val modelConfig =
                    OfflineTtsModelConfig(
                        vits = vitsConfig,
                        numThreads = threadCount,
                        debug = false
                    )

                val config =
                    OfflineTtsConfig(
                        model = modelConfig
                    )

                tts =
                    OfflineTts(
                        config = config
                    )

                runOnUiThread {

                    statusText.text =
                        "Ready • Ryan High • Fully Offline"

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

            if (!baseDirectory.mkdirs() &&
                !baseDirectory.isDirectory
            ) {
                throw IOException(
                    "Could not create Piper directory."
                )
            }

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
                    ByteArray(32 * 1024)

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

        if (inputText.length > MAX_TEXT_LENGTH) {

            statusText.text =
                "Maximum 20,000 characters."

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
        generationCancelled = false

        stopPlayback()

        generatedWav = null

        runOnUiThread {

            generateButton.isEnabled = false
            playButton.isEnabled = false
            saveButton.isEnabled = false

            stopButton.isEnabled = true

            progressBar.progress = 0
            progressBar.visibility =
                ProgressBar.VISIBLE

            statusText.text =
                "Preparing script..."
        }

        val speed =
            getSelectedSpeed()

        thread {

            val temporaryDirectory =
                File(
                    cacheDir,
                    "tts_chunks"
                )

            try {

                val chunks =
                    splitTextIntoChunks(
                        inputText,
                        MAX_CHUNK_CHARACTERS
                    )

                if (chunks.isEmpty()) {
                    throw IOException(
                        "No usable text found."
                    )
                }

                if (temporaryDirectory.exists()) {
                    temporaryDirectory.deleteRecursively()
                }

                if (!temporaryDirectory.mkdirs() &&
                    !temporaryDirectory.isDirectory
                ) {
                    throw IOException(
                        "Could not create temporary directory."
                    )
                }

                val chunkFiles =
                    ArrayList<File>()

                for (index in chunks.indices) {

                    if (generationCancelled) {
                        throw GenerationCancelledException()
                    }

                    val chunk =
                        chunks[index]

                    runOnUiThread {

                        statusText.text =
                            String.format(
                                Locale.US,
                                "Generating • %d / %d",
                                index + 1,
                                chunks.size
                            )

                        progressBar.progress =
                            (
                                (
                                    index.toFloat() /
                                            chunks.size.toFloat()
                                    ) *
                                        100f
                                ).toInt()
                    }

                    val audio =
                        currentTts.generateWithConfig(
                            text = chunk,
                            config =
                                GenerationConfig(
                                    sid = 0,
                                    speed = speed,
                                    silenceScale =
                                        SILENCE_SCALE
                                )
                        )

                    if (generationCancelled) {
                        throw GenerationCancelledException()
                    }

                    val chunkFile =
                        File(
                            temporaryDirectory,
                            String.format(
                                Locale.US,
                                "chunk_%05d.wav",
                                index
                            )
                        )

                    /*
                     * GeneratedAudio.save() creates the WAV.
                     *
                     * Do NOT call audio.release().
                     * GeneratedAudio is not a MediaPlayer and
                     * the Kotlin API does not expose release().
                     */
                    if (!audio.save(
                            filename =
                                chunkFile.absolutePath
                        )
                    ) {

                        throw IOException(
                            "Failed to generate chunk ${index + 1}."
                        )
                    }

                    if (
                        !chunkFile.isFile ||
                        chunkFile.length() <= 44
                    ) {

                        throw IOException(
                            "Invalid audio chunk ${index + 1}."
                        )
                    }

                    chunkFiles.add(chunkFile)

                    runOnUiThread {

                        progressBar.progress =
                            (
                                (
                                    (index + 1).toFloat() /
                                            chunks.size.toFloat()
                                    ) *
                                        100f
                                ).toInt()
                    }
                }

                if (generationCancelled) {
                    throw GenerationCancelledException()
                }

                runOnUiThread {

                    statusText.text =
                        "Combining audio..."
                }

                val finalFile =
                    File(
                        cacheDir,
                        "piper_output.wav"
                    )

                if (finalFile.exists()) {
                    finalFile.delete()
                }

                concatenateWavFiles(
                    chunkFiles,
                    finalFile
                )

                if (
                    !finalFile.isFile ||
                    finalFile.length() <= 44
                ) {

                    throw IOException(
                        "Final WAV is invalid."
                    )
                }

                generatedWav =
                    finalFile

                temporaryDirectory.deleteRecursively()

                runOnUiThread {

                    progressBar.progress = 100
                    progressBar.visibility =
                        ProgressBar.GONE

                    statusText.text =
                        String.format(
                            Locale.US,
                            "Ready • Ryan High • %.2fx",
                            speed
                        )

                    generateButton.isEnabled = true
                    playButton.isEnabled = true
                    saveButton.isEnabled = true
                    stopButton.isEnabled = false
                }

            } catch (e: GenerationCancelledException) {

                temporaryDirectory.deleteRecursively()

                generatedWav = null

                runOnUiThread {

                    progressBar.visibility =
                        ProgressBar.GONE

                    progressBar.progress = 0

                    statusText.text =
                        "Generation cancelled."

                    generateButton.isEnabled = true
                    playButton.isEnabled = false
                    saveButton.isEnabled = false
                    stopButton.isEnabled = false
                }

            } catch (e: Throwable) {

                temporaryDirectory.deleteRecursively()

                generatedWav = null

                val message =
                    e.message
                        ?: e.javaClass.simpleName

                runOnUiThread {

                    progressBar.visibility =
                        ProgressBar.GONE

                    progressBar.progress = 0

                    statusText.text =
                        "Generation failed:\n$message"

                    generateButton.isEnabled = true
                    playButton.isEnabled = false
                    saveButton.isEnabled = false
                    stopButton.isEnabled = false
                }

            } finally {

                isGenerating = false
                generationCancelled = false
            }
        }
    }

    private class GenerationCancelledException :
        Exception()

    private fun splitTextIntoChunks(
        text: String,
        maxCharacters: Int
    ): List<String> {

        val cleaned =
            text
                .replace(
                    "\r\n",
                    "\n"
                )
                .replace(
                    "\r",
                    "\n"
                )
                .replace(
                    Regex("[ \t]+"),
                    " "
                )
                .replace(
                    Regex("\n{3,}"),
                    "\n\n"
                )
                .trim()

        if (cleaned.isEmpty()) {
            return emptyList()
        }

        if (cleaned.length <= maxCharacters) {
            return listOf(cleaned)
        }

        val chunks =
            ArrayList<String>()

        val paragraphs =
            cleaned.split(
                Regex("\\n\\s*\\n")
            )

        var current =
            StringBuilder()

        fun flush() {

            val value =
                current.toString().trim()

            if (value.isNotEmpty()) {
                chunks.add(value)
            }

            current =
                StringBuilder()
        }

        fun addWords(
            value: String
        ) {

            val words =
                value
                    .trim()
                    .split(
                        Regex("\\s+")
                    )

            for (word in words) {

                if (word.isEmpty()) {
                    continue
                }

                if (word.length > maxCharacters) {

                    if (current.isNotEmpty()) {
                        flush()
                    }

                    var start = 0

                    while (
                        start < word.length
                    ) {

                        val end =
                            minOf(
                                start +
                                        maxCharacters,
                                word.length
                            )

                        chunks.add(
                            word.substring(
                                start,
                                end
                            )
                        )

                        start = end
                    }

                    continue
                }

                if (current.isEmpty()) {

                    current.append(word)

                } else if (
                    current.length +
                    1 +
                    word.length <=
                    maxCharacters
                ) {

                    current
                        .append(" ")
                        .append(word)

                } else {

                    flush()

                    current.append(word)
                }
            }
        }

        fun addPiece(
            piece: String
        ) {

            val value =
                piece.trim()

            if (value.isEmpty()) {
                return
            }

            if (value.length <= maxCharacters) {

                if (current.isEmpty()) {

                    current.append(value)

                } else if (
                    current.length +
                    1 +
                    value.length <=
                    maxCharacters
                ) {

                    current
                        .append(" ")
                        .append(value)

                } else {

                    flush()

                    current.append(value)
                }

                return
            }

            /*
             * First try to split long sections around
             * commas, semicolons, colons and dashes.
             */
            val clauses =
                value.split(
                    Regex(
                        "(?<=[,;:—–-])\\s+"
                    )
                )

            for (clause in clauses) {

                if (clause.length <= maxCharacters) {

                    addPiece(clause)

                } else {

                    addWords(clause)
                }
            }
        }

        for (paragraph in paragraphs) {

            if (paragraph.trim().isEmpty()) {
                continue
            }

            /*
             * Split at normal sentence-ending punctuation.
             * This keeps sentences together whenever possible.
             */
            val sentences =
                paragraph
                    .trim()
                    .split(
                        Regex(
                            "(?<=[.!?。！？])\\s+"
                        )
                    )

            for (sentence in sentences) {
                addPiece(sentence)
            }

            if (current.isNotEmpty()) {
                flush()
            }
        }

        if (current.isNotEmpty()) {
            flush()
        }

        return chunks
            .map {
                it.trim()
            }
            .filter {
                it.isNotEmpty()
            }
    }

    private fun concatenateWavFiles(
        files: List<File>,
        output: File
    ) {

        if (files.isEmpty()) {
            throw IOException(
                "No WAV files."
            )
        }

        if (output.exists()) {
            output.delete()
        }

        val first =
            WavInfo.read(
                files.first()
            )

        if (first.audioFormat != 1) {
            throw IOException(
                "Only PCM WAV files are supported."
            )
        }

        FileOutputStream(output).use { out ->

            writeAscii(
                out,
                "RIFF"
            )

            writeIntLE(
                out,
                0
            )

            writeAscii(
                out,
                "WAVE"
            )

            writeAscii(
                out,
                "fmt "
            )

            writeIntLE(
                out,
                16
            )

            writeShortLE(
                out,
                first.audioFormat
            )

            writeShortLE(
                out,
                first.channels
            )

            writeIntLE(
                out,
                first.sampleRate
            )

            writeIntLE(
                out,
                first.byteRate
            )

            writeShortLE(
                out,
                first.blockAlign
            )

            writeShortLE(
                out,
                first.bitsPerSample
            )

            writeAscii(
                out,
                "data"
            )

            writeIntLE(
                out,
                0
            )

            var totalDataSize = 0L

            val buffer =
                ByteArray(
                    64 * 1024
                )

            for (file in files) {

                val info =
                    WavInfo.read(file)

                if (
                    info.audioFormat !=
                    first.audioFormat ||
                    info.channels !=
                    first.channels ||
                    info.sampleRate !=
                    first.sampleRate ||
                    info.bitsPerSample !=
                    first.bitsPerSample
                ) {

                    throw IOException(
                        "WAV format mismatch."
                    )
                }

                RandomAccessFile(
                    file,
                    "r"
                ).use { raf ->

                    raf.seek(
                        info.dataOffset
                    )

                    var remaining =
                        info.dataSize

                    while (
                        remaining > 0
                    ) {

                        val wanted =
                            minOf(
                                buffer.size.toLong(),
                                remaining
                            ).toInt()

                        val read =
                            raf.read(
                                buffer,
                                0,
                                wanted
                            )

                        if (read <= 0) {
                            break
                        }

                        out.write(
                            buffer,
                            0,
                            read
                        )

                        totalDataSize +=
                            read.toLong()

                        remaining -=
                            read.toLong()
                    }
                }
            }

            out.flush()

            RandomAccessFile(
                output,
                "rw"
            ).use { raf ->

                val riffSize =
                    (
                        36L +
                                totalDataSize
                        )
                        .coerceAtMost(
                            0xFFFFFFFFL
                        )
                        .toInt()

                val dataSize =
                    totalDataSize
                        .coerceAtMost(
                            0xFFFFFFFFL
                        )
                        .toInt()

                raf.seek(4)

                writeIntLE(
                    raf,
                    riffSize
                )

                raf.seek(40)

                writeIntLE(
                    raf,
                    dataSize
                )
            }
        }
    }

    private fun writeAscii(
        output: FileOutputStream,
        value: String
    ) {

        output.write(
            value.toByteArray(
                Charsets.US_ASCII
            )
        )
    }

    private fun writeIntLE(
        output: FileOutputStream,
        value: Int
    ) {

        output.write(
            value and 0xFF
        )

        output.write(
            (value shr 8) and 0xFF
        )

        output.write(
            (value shr 16) and 0xFF
        )

        output.write(
            (value shr 24) and 0xFF
        )
    }

    private fun writeShortLE(
        output: FileOutputStream,
        value: Int
    ) {

        output.write(
            value and 0xFF
        )

        output.write(
            (value shr 8) and 0xFF
        )
    }

    private fun writeIntLE(
        raf: RandomAccessFile,
        value: Int
    ) {

        raf.write(
            value and 0xFF
        )

        raf.write(
            (value shr 8) and 0xFF
        )

        raf.write(
            (value shr 16) and 0xFF
        )

        raf.write(
            (value shr 24) and 0xFF
        )
    }

    private class WavInfo(
        val audioFormat: Int,
        val channels: Int,
        val sampleRate: Int,
        val byteRate: Int,
        val blockAlign: Int,
        val bitsPerSample: Int,
        val dataOffset: Long,
        val dataSize: Long
    ) {

        companion object {

            fun read(
                file: File
            ): WavInfo {

                RandomAccessFile(
                    file,
                    "r"
                ).use { raf ->

                    val riff =
                        readAscii(
                            raf,
                            4
                        )

                    if (riff != "RIFF") {

                        throw IOException(
                            "Invalid WAV: ${file.name}"
                        )
                    }

                    raf.skipBytes(4)

                    val wave =
                        readAscii(
                            raf,
                            4
                        )

                    if (wave != "WAVE") {

                        throw IOException(
                            "Invalid WAVE: ${file.name}"
                        )
                    }

                    var audioFormat = -1
                    var channels = -1
                    var sampleRate = -1
                    var byteRate = -1
                    var blockAlign = -1
                    var bitsPerSample = -1

                    var dataOffset = -1L
                    var dataSize = -1L

                    while (
                        raf.filePointer + 8 <=
                        raf.length()
                    ) {

                        val chunkId =
                            readAscii(
                                raf,
                                4
                            )

                        val chunkSize =
                            readIntLE(raf)
                                .toLong() and
                                0xFFFFFFFFL

                        val chunkStart =
                            raf.filePointer

                        when (chunkId) {

                            "fmt " -> {

                                if (chunkSize < 16) {

                                    throw IOException(
                                        "Invalid fmt chunk."
                                    )
                                }

                                audioFormat =
                                    readShortLE(
                                        raf
                                    )

                                channels =
                                    readShortLE(
                                        raf
                                    )

                                sampleRate =
                                    readIntLE(
                                        raf
                                    )

                                byteRate =
                                    readIntLE(
                                        raf
                                    )

                                blockAlign =
                                    readShortLE(
                                        raf
                                    )

                                bitsPerSample =
                                    readShortLE(
                                        raf
                                    )
                            }

                            "data" -> {

                                dataOffset =
                                    chunkStart

                                dataSize =
                                    chunkSize

                                break
                            }
                        }

                        raf.seek(
                            chunkStart +
                                    chunkSize +
                                    (
                                        chunkSize and
                                                1L
                                        )
                        )
                    }

                    if (
                        audioFormat < 0 ||
                        channels < 0 ||
                        sampleRate < 0 ||
                        byteRate < 0 ||
                        blockAlign < 0 ||
                        bitsPerSample < 0 ||
                        dataOffset < 0 ||
                        dataSize < 0
                    ) {

                        throw IOException(
                            "Invalid WAV structure."
                        )
                    }

                    return WavInfo(
                        audioFormat =
                            audioFormat,
                        channels =
                            channels,
                        sampleRate =
                            sampleRate,
                        byteRate =
                            byteRate,
                        blockAlign =
                            blockAlign,
                        bitsPerSample =
                            bitsPerSample,
                        dataOffset =
                            dataOffset,
                        dataSize =
                            dataSize
                    )
                }
            }

            private fun readAscii(
                raf: RandomAccessFile,
                count: Int
            ): String {

                val bytes =
                    ByteArray(count)

                raf.readFully(bytes)

                return String(
                    bytes,
                    Charsets.US_ASCII
                )
            }

            private fun readIntLE(
                raf: RandomAccessFile
            ): Int {

                val b0 =
                    raf.read()

                val b1 =
                    raf.read()

                val b2 =
                    raf.read()

                val b3 =
                    raf.read()

                if (
                    b0 < 0 ||
                    b1 < 0 ||
                    b2 < 0 ||
                    b3 < 0
                ) {

                    throw IOException(
                        "Unexpected end of WAV."
                    )
                }

                return b0 or
                        (b1 shl 8) or
                        (b2 shl 16) or
                        (b3 shl 24)
            }

            private fun readShortLE(
                raf: RandomAccessFile
            ): Int {

                val b0 =
                    raf.read()

                val b1 =
                    raf.read()

                if (
                    b0 < 0 ||
                    b1 < 0
                ) {

                    throw IOException(
                        "Unexpected end of WAV."
                    )
                }

                return b0 or
                        (b1 shl 8)
            }
        }
    }

    private fun playGeneratedAudio() {

        val wav =
            generatedWav

        if (
            wav == null ||
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

                    setOnPreparedListener { mp ->

                        mp.start()

                        runOnUiThread {

                            statusText.text =
                                "Playing Ryan High..."

                            stopButton.isEnabled =
                                true
                        }
                    }

                    setOnCompletionListener { mp ->

                        runOnUiThread {

                            statusText.text =
                                "Playback finished."

                            stopButton.isEnabled =
                                false
                        }

                        /*
                         * IMPORTANT:
                         * release the MediaPlayer itself.
                         */
                        try {
                            mp.release()
                        } catch (_: Throwable) {
                        }

                        if (
                            mediaPlayer === mp
                        ) {
                            mediaPlayer = null
                        }
                    }

                    setOnErrorListener { mp, _, _ ->

                        runOnUiThread {

                            statusText.text =
                                "Playback error."

                            stopButton.isEnabled =
                                false
                        }

                        /*
                         * IMPORTANT:
                         * release the MediaPlayer itself.
                         */
                        try {
                            mp.release()
                        } catch (_: Throwable) {
                        }

                        if (
                            mediaPlayer === mp
                        ) {
                            mediaPlayer = null
                        }

                        true
                    }

                    prepareAsync()
                }

        } catch (e: Throwable) {

            statusText.text =
                "Playback failed:\n${e.message}"

            try {
                mediaPlayer?.release()
            } catch (_: Throwable) {
            }

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

            if (
                mediaPlayer === player
            ) {
                mediaPlayer = null
            }
        }

        if (!isFinishing) {

            runOnUiThread {

                if (!isGenerating) {
                    stopButton.isEnabled = false
                }
            }
        }
    }

    private fun saveGeneratedWav() {

        val source =
            generatedWav

        if (
            source == null ||
            !source.isFile
        ) {

            statusText.text =
                "Generate speech first."

            return
        }

        thread {

            try {

                val filename =
                    "Piper_RyanHigh_${System.currentTimeMillis()}.wav"

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
                    contentResolver.insert(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        values
                    )

                if (uri == null) {

                    throw IOException(
                        "Could not create output file."
                    )
                }

                try {

                    contentResolver
                        .openOutputStream(uri)
                        .use { output ->

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

                    contentResolver.delete(
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

        generationCancelled = true
        isGenerating = false

        stopPlayback()

        try {
            tts?.release()
        } catch (_: Throwable) {
        }

        tts = null

        super.onDestroy()
    }
}
