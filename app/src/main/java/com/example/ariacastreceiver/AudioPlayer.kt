package com.example.ariacastreceiver

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue

class AudioPlayer(private val config: AudioConfig) {
    private var audioTrack: AudioTrack? = null
    private val frameQueue = LinkedBlockingQueue<ByteArray>(100)
    private var isPlaying = false
    private var playbackThread: Thread? = null

    fun start() {
        if (isPlaying) return
        
        val minBufferSize = AudioTrack.getMinBufferSize(
            config.sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(config.sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(minBufferSize.coerceAtLeast(config.frameSize * 10))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        isPlaying = true
        audioTrack?.play()

        playbackThread = Thread {
            while (isPlaying) {
                try {
                    val frame = frameQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (frame != null) {
                        audioTrack?.write(frame, 0, frame.size)
                    }
                } catch (e: InterruptedException) {
                    break
                }
            }
        }.apply { start() }
        
        Log.i("AudioPlayer", "AudioPlayer started")
    }

    fun stop() {
        isPlaying = false
        playbackThread?.interrupt()
        playbackThread = null
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        frameQueue.clear()
        Log.i("AudioPlayer", "AudioPlayer stopped")
    }

    fun enqueueFrame(data: ByteArray) {
        if (!isPlaying) start()
        frameQueue.offer(data)
    }
    
    fun clearQueue() {
        frameQueue.clear()
    }
}
