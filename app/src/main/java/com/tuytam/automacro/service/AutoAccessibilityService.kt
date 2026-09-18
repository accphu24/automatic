package com.tuytam.automacro.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.tuytam.automacro.data.ScriptStep
import com.tuytam.automacro.engine.ScriptEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Service nen chiu trach nhiem "nhin" man hinh (qua AccessibilityEvent)
 * va "thao tac" thay nguoi dung (bam, vuot, mo app) khi kich ban chay.
 *
 * runScript() la ham cong khai de MainActivity (hoac man hinh khac sau nay)
 * goi vao de bat dau chay 1 kich ban trong nen.
 */
class AutoAccessibilityService : AccessibilityService() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)

    companion object {
        private const val TAG = "AutoAccessibilityService"

        /** Tham chieu toi service dang chay, de noi khac (VD: MainActivity) goi runScript() */
        var instance: AutoAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility Service da ket noi")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // TODO: doc noi dung man hinh / phat hien dieu kien kich hoat kich ban tu dong
        // se duoc noi vao day khi lam tinh nang "trigger tu dong" (khong can bam nut)
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility Service bi ngat")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        serviceJob.cancel()
    }

    /** Chay 1 kich ban (danh sach cac buoc) trong nen, khong lam dung giao dien */
    fun runScript(steps: List<ScriptStep>) {
        serviceScope.launch {
            ScriptEngine(this@AutoAccessibilityService).run(steps)
        }
    }

    /** Gia lap mot cu cham tai toa do (x, y) tren man hinh */
    fun performClick(x: Float, y: Float, durationMs: Long = 50) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        dispatchGesture(gesture, null, null)
    }

    /** Gia lap mot cu vuot tu (startX, startY) den (endX, endY) */
    fun performSwipe(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        durationMs: Long = 300
    ) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        dispatchGesture(gesture, null, null)
    }
}
