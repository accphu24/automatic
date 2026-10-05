package com.starclan.starhub.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Reo chuong bao dong + rung khi OwO doi captcha. Dung kenh am thanh BAO THUC
 * (van keu khi dien thoai dang im lang). Tu tat sau 2 phut neu Ruby khong tat.
 */
object AlarmPlayer {
    private const val TAG = "AlarmPlayer"
    private const val MAX_RING_MS = 120_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    @Volatile
    var ringing: Boolean = false
        private set

    /** Goi khi chuong da dung (Ruby tat, hoac het gio) de Hub noi cap nhat nut */
    var onStopped: (() -> Unit)? = null

    fun start(context: Context) {
        if (ringing) return
        ringing = true
        val app = context.applicationContext
        job = scope.launch {
            try {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                val r = RingtoneManager.getRingtone(app, uri)
                if (r != null) {
                    try {
                        r.setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Khong dat duoc kenh bao thuc: ${e.message}")
                    }
                }
                ringtone = r

                val v = app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator = v
                if (v != null && v.hasVibrator()) {
                    v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 400), 0))
                }

                val endAt = System.currentTimeMillis() + MAX_RING_MS
                while (isActive && System.currentTimeMillis() < endAt) {
                    if (r != null && !r.isPlaying) r.play()
                    delay(1_500)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Reo chuong loi: ${e.message}")
            } finally {
                cleanup()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        cleanup()
    }

    private fun cleanup() {
        val wasRinging = ringing
        try {
            ringtone?.stop()
        } catch (e: Exception) {
            // bo qua
        }
        try {
            vibrator?.cancel()
        } catch (e: Exception) {
            // bo qua
        }
        ringtone = null
        vibrator = null
        ringing = false
        if (wasRinging) onStopped?.invoke()
    }
}
