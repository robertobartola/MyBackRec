package io.github.robertobartola.mybackrec

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.github.robertobartola.mybackrec.R
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordService : Service() {
    private var isRecording = false
    private var audioRecord: AudioRecord? = null
    private var circularBuffer: ByteArray? = null
    private var writeIndex = 0
    private val sampleRate = 44100
    private val CHANNEL_ID = "RecordServiceChannel"
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): RecordService = this@RecordService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 1. CREA IL CANALE PRIMA DI TUTTO
        createNotificationChannel()

        // 2. PREPARA L'INTENT PER APRIRE L'APP DALLA NOTIFICA
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        // 3. COSTRUISCI LA NOTIFICA (Assicurati che R.drawable.ic_recording_notif esista)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MyBackRec")
            .setContentText("Registrazione attiva in background")
            .setSmallIcon(R.drawable.ic_recording_notif) // Deve essere un Vector Asset bianco
            .setColor(Color.RED)
            .setOngoing(true) // Non cancellabile con swipe
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            .build()

        // 4. AVVIA IL SERVIZIO IN FOREGROUND
        startForeground(1, notification)

        return START_STICKY
    }

    fun startRecording(secondi: Int) {
        if (isRecording) return
        val bufferSize = secondi * sampleRate * 2
        circularBuffer = ByteArray(bufferSize)
        writeIndex = 0
        isRecording = true

        Thread {
            val minBufSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            try {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBufSize
                )
                audioRecord?.startRecording()
                val tempBuffer = ByteArray(1024)
                while (isRecording) {
                    val read = audioRecord?.read(tempBuffer, 0, tempBuffer.size) ?: 0
                    for (i in 0 until read) {
                        circularBuffer?.set(writeIndex, tempBuffer[i])
                        writeIndex = (writeIndex + 1) % bufferSize
                    }
                }
            } catch (e: SecurityException) { e.printStackTrace() }
        }.start()
    }

    fun freezeAndSave() {
        val bufferCopy = circularBuffer?.clone() ?: return
        val currentWriteIndex = writeIndex
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(getExternalFilesDir(null), "mybackrec_$timeStamp.wav")

        Thread {
            try {
                FileOutputStream(file).use { out ->
                    writeWavHeader(out, 1, sampleRate, 16, bufferCopy.size.toLong())
                    out.write(bufferCopy, currentWriteIndex, bufferCopy.size - currentWriteIndex)
                    out.write(bufferCopy, 0, currentWriteIndex)
                }
            } catch (e: Exception) { e.printStackTrace() }
        }.start()
    }

    fun stopRecording() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            "Canale Registrazione",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        val manager = getSystemService(NotificationManager::class.java)

        manager.createNotificationChannel(serviceChannel)
    }

    private fun writeWavHeader(out: FileOutputStream, channels: Int, sampleRate: Int, bitDepth: Int, dataLength: Long) {
        val totalLength = dataLength + 36
        val byteRate = sampleRate * channels * bitDepth / 8
        val header = ByteArray(44)

        header[0] = 'R'.toByte(); header[1] = 'I'.toByte(); header[2] = 'F'.toByte(); header[3] = 'F'.toByte()
        header[4] = (totalLength and 0xff).toByte(); header[5] = (totalLength shr 8 and 0xff).toByte()
        header[6] = (totalLength shr 16 and 0xff).toByte(); header[7] = (totalLength shr 24 and 0xff).toByte()
        header[8] = 'W'.toByte(); header[9] = 'A'.toByte(); header[10] = 'V'.toByte(); header[11] = 'E'.toByte()
        header[12] = 'f'.toByte(); header[13] = 'm'.toByte(); header[14] = 't'.toByte(); header[15] = ' '.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
        header[20] = 1; header[21] = 0
        header[22] = channels.toByte(); header[23] = 0
        header[24] = (sampleRate and 0xff).toByte(); header[25] = (sampleRate shr 8 and 0xff).toByte()
        header[26] = (sampleRate shr 16 and 0xff).toByte(); header[27] = (sampleRate shr 24 and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte(); header[29] = (byteRate shr 8 and 0xff).toByte()
        header[30] = (byteRate shr 16 and 0xff).toByte(); header[31] = (byteRate shr 24 and 0xff).toByte()
        header[32] = (channels * bitDepth / 8).toByte(); header[33] = 0
        header[34] = bitDepth.toByte(); header[35] = 0
        header[36] = 'd'.toByte(); header[37] = 'a'.toByte(); header[38] = 't'.toByte(); header[39] = 'a'.toByte()
        header[40] = (dataLength and 0xff).toByte(); header[41] = (dataLength shr 8 and 0xff).toByte()
        header[42] = (dataLength shr 16 and 0xff).toByte(); header[43] = (dataLength shr 24 and 0xff).toByte()
        out.write(header)
    }
}