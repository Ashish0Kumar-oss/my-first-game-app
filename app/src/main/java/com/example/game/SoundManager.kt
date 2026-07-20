package com.example.game

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import kotlinx.coroutines.*
import kotlin.math.sin

class SoundManager {

    private var soundEnabled = true
    private var musicEnabled = true

    private val synthScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var musicJob: Job? = null

    // Set configuration
    fun setSoundEnabled(enabled: Boolean) {
        this.soundEnabled = enabled
    }

    fun setMusicEnabled(enabled: Boolean) {
        val changed = this.musicEnabled != enabled
        this.musicEnabled = enabled
        if (changed) {
            if (enabled) {
                startBackgroundMusic()
            } else {
                stopBackgroundMusic()
            }
        }
    }

    // Play a procedurally generated coin collect sound (Ding!)
    fun playCoinSound() {
        if (!soundEnabled) return
        synthScope.launch {
            generateBeep(
                frequencies = floatArrayOf(880f, 1320f), // A5 to E6
                durationMs = 120,
                type = WaveType.SINE,
                amplitude = 0.5f
            )
        }
    }

    // Play procedural jump sound (Swoosh!)
    fun playJumpSound() {
        if (!soundEnabled) return
        synthScope.launch {
            generatePitchSlide(
                startFreq = 300f,
                endFreq = 800f,
                durationMs = 200,
                type = WaveType.SINE,
                amplitude = 0.4f
            )
        }
    }

    // Play procedural slide sound (Slide / Swoosh down)
    fun playSlideSound() {
        if (!soundEnabled) return
        synthScope.launch {
            generatePitchSlide(
                startFreq = 400f,
                endFreq = 150f,
                durationMs = 250,
                type = WaveType.TRIANGLE,
                amplitude = 0.4f
            )
        }
    }

    // Play procedural obstacle hit sound (Crash / Thud!)
    fun playObstacleHitSound() {
        if (!soundEnabled) return
        synthScope.launch {
            generateNoise(
                durationMs = 300,
                amplitude = 0.6f,
                isLowPass = true
            )
        }
    }

    // Play procedural monster roar sound (Rumble!)
    fun playMonsterRoarSound() {
        if (!soundEnabled) return
        synthScope.launch {
            generatePitchSlide(
                startFreq = 120f,
                endFreq = 60f,
                durationMs = 600,
                type = WaveType.SAWTOOTH,
                amplitude = 0.8f,
                modulateFreq = 12f // Growl wobble
            )
        }
    }

    // Start background atmospheric music
    fun startBackgroundMusic() {
        if (!musicEnabled) return
        if (musicJob?.isActive == true) return

        musicJob = synthScope.launch {
            val sampleRate = 22050
            val bufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            val audioTrack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                    AudioTrack.MODE_STREAM
                )
            }

            try {
                audioTrack.play()
            } catch (e: Exception) {
                return@launch
            }

            // Simple, beautiful pentatonic tribal/ancient temple theme loop
            // Bass line and high bells arpeggio
            val chords = arrayOf(
                floatArrayOf(110f, 165f), // A2, E3
                floatArrayOf(130.81f, 196f), // C3, G3
                floatArrayOf(146.83f, 220f), // D3, A3
                floatArrayOf(165f, 246.94f)  // E3, B3
            )

            val melodyNotes = floatArrayOf(
                440f, 493.88f, 523.25f, 587.33f, 659.25f, 783.99f, 880f
            )

            var chordIndex = 0
            var noteTick = 0

            val toneBuffer = ShortArray(4410) // 200ms ticks at 22050Hz

            while (isActive && musicEnabled) {
                val bassFreq1 = chords[chordIndex][0]
                val bassFreq2 = chords[chordIndex][1]

                // Pick a note from pentatonic scale based on pseudo-random sequence
                val melodyFreq = if (noteTick % 2 == 0) {
                    melodyNotes[(noteTick * 3 + chordIndex * 2) % melodyNotes.size]
                } else {
                    0f // Rest
                }

                // Fill buffer
                for (i in toneBuffer.indices) {
                    val t = i.toFloat() / sampleRate
                    
                    // Synthesize Bass wave (Triangle)
                    var bassSample = (sin(2.0 * Math.PI * bassFreq1 * t) + sin(2.0 * Math.PI * bassFreq2 * t)) * 0.25
                    if (bassSample > 1.0) bassSample = 1.0
                    if (bassSample < -1.0) bassSample = -1.0

                    // Synthesize Lead wave (Sine with decay)
                    var leadSample = 0.0
                    if (melodyFreq > 0f) {
                        val decay = 1.0 - (i.toFloat() / toneBuffer.size)
                        leadSample = sin(2.0 * Math.PI * melodyFreq * t) * 0.15 * decay
                    }

                    // Dynamic Ambient Jungle noise (subtle breeze)
                    val ambientWind = (Math.random() - 0.5) * 0.02

                    val mixed = (bassSample + leadSample + ambientWind) * Short.MAX_VALUE
                    toneBuffer[i] = mixed.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }

                audioTrack.write(toneBuffer, 0, toneBuffer.size)

                noteTick++
                if (noteTick % 8 == 0) {
                    chordIndex = (chordIndex + 1) % chords.size
                }

                delay(200) // Sleep 200ms per musical step
            }

            try {
                audioTrack.stop()
                audioTrack.release()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    fun stopBackgroundMusic() {
        musicJob?.cancel()
        musicJob = null
    }

    fun onDestroy() {
        synthScope.cancel()
    }

    // --- Helper synthesizer methods ---

    enum class WaveType { SINE, SQUARE, TRIANGLE, SAWTOOTH }

    private fun generateBeep(frequencies: FloatArray, durationMs: Int, type: WaveType, amplitude: Float) {
        val sampleRate = 22050
        val numSamples = (durationMs * sampleRate / 1000)
        val buffer = ShortArray(numSamples)

        for (i in buffer.indices) {
            val t = i.toFloat() / sampleRate
            var sampleSum = 0.0
            
            for (freq in frequencies) {
                val wave = when (type) {
                    WaveType.SINE -> sin(2.0 * Math.PI * freq * t)
                    WaveType.SQUARE -> if (sin(2.0 * Math.PI * freq * t) >= 0) 1.0 else -1.0
                    WaveType.TRIANGLE -> {
                        val period = 1.0 / freq
                        val progress = (t % period) / period
                        if (progress < 0.5) {
                            -1.0 + 4.0 * progress
                        } else {
                            3.0 - 4.0 * progress
                        }
                    }
                    WaveType.SAWTOOTH -> {
                        val period = 1.0 / freq
                        -1.0 + 2.0 * ((t % period) / period)
                    }
                }
                sampleSum += wave
            }
            
            sampleSum /= frequencies.size
            val volumeDecay = 1.0 - (i.toFloat() / numSamples) // Fade out
            val raw = (sampleSum * volumeDecay * amplitude * Short.MAX_VALUE).toInt()
            buffer[i] = raw.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        playBuffer(buffer, sampleRate)
    }

    private fun generatePitchSlide(
        startFreq: Float,
        endFreq: Float,
        durationMs: Int,
        type: WaveType,
        amplitude: Float,
        modulateFreq: Float = 0f
    ) {
        val sampleRate = 22050
        val numSamples = (durationMs * sampleRate / 1000)
        val buffer = ShortArray(numSamples)
        var phase = 0.0

        for (i in buffer.indices) {
            val t = i.toFloat() / sampleRate
            val progress = i.toFloat() / numSamples
            var currentFreq = startFreq + (endFreq - startFreq) * progress

            // Frequency modulation for rumbles/growls
            if (modulateFreq > 0f) {
                currentFreq += sin(2.0 * Math.PI * modulateFreq * t).toFloat() * (currentFreq * 0.15f)
            }

            phase += 2.0 * Math.PI * currentFreq / sampleRate
            val rawSample = when (type) {
                WaveType.SINE -> sin(phase)
                WaveType.SQUARE -> if (sin(phase) >= 0) 1.0 else -1.0
                WaveType.TRIANGLE -> {
                    val normPhase = (phase % (2.0 * Math.PI)) / (2.0 * Math.PI)
                    if (normPhase < 0.5) -1.0 + 4.0 * normPhase else 3.0 - 4.0 * normPhase
                }
                WaveType.SAWTOOTH -> -1.0 + 2.0 * ((phase % (2.0 * Math.PI)) / (2.0 * Math.PI))
            }

            val decay = 1.0 - progress
            val raw = (rawSample * decay * amplitude * Short.MAX_VALUE).toInt()
            buffer[i] = raw.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        playBuffer(buffer, sampleRate)
    }

    private fun generateNoise(durationMs: Int, amplitude: Float, isLowPass: Boolean = false) {
        val sampleRate = 22050
        val numSamples = (durationMs * sampleRate / 1000)
        val buffer = ShortArray(numSamples)
        var lastSample = 0.0

        for (i in buffer.indices) {
            var whiteNoise = (Math.random() * 2.0 - 1.0)
            
            if (isLowPass) {
                // Apply a simple first-order low-pass filter to simulate thuds
                whiteNoise = 0.85 * lastSample + 0.15 * whiteNoise
                lastSample = whiteNoise
            }

            val decay = 1.0 - (i.toFloat() / numSamples)
            val raw = (whiteNoise * decay * amplitude * Short.MAX_VALUE).toInt()
            buffer[i] = raw.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        playBuffer(buffer, sampleRate)
    }

    private fun playBuffer(buffer: ShortArray, sampleRate: Int) {
        try {
            val audioTrack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(buffer.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    buffer.size * 2,
                    AudioTrack.MODE_STATIC
                )
            }
            audioTrack.write(buffer, 0, buffer.size, AudioTrack.WRITE_BLOCKING)
            audioTrack.play()
            // Schedule destruction of static track after playback is done to avoid memory leaks
            synthScope.launch {
                delay(1000)
                try {
                    audioTrack.stop()
                    audioTrack.release()
                } catch (e: Exception) { }
            }
        } catch (e: Exception) {
            // AudioTrack creation or playback could fail on some targets
        }
    }
}
