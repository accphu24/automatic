package com.tuytam.automacro.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.tuytam.automacro.R
import com.tuytam.automacro.ScriptEditorActivity
import com.tuytam.automacro.data.ScriptJson
import com.tuytam.automacro.data.ScriptStep
import com.tuytam.automacro.data.StepType
import com.tuytam.automacro.engine.ScriptEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Service nen chiu trach nhiem "nhin" man hinh (qua AccessibilityEvent)
 * va "thao tac" thay nguoi dung (bam, vuot, mo app) khi kich ban chay.
 *
 * Ngoai runScript(), service nay ho tro CHE DO GHI (recording): bam bong
 * bong noi tren man hinh -> AutoMacro tu quan sat cac lan mo app / bam nut
 * that cua Ruby va bien thanh danh sach ScriptStep, mo san man hinh tao
 * kich ban de chinh sua tiep.
 *
 * GIOI HAN cua ban ghi nay (can biet truoc khi dung):
 * - Chi ghi duoc: mo app moi, bam vao nut/thanh phan co CHU hoac co ID.
 * - CHUA ghi duoc: vuot (swipe), go chu vao o nhap, cac buoc Kiem tra/Bao dong
 *   (Ruby van can tu them tay sau khi ghi xong, dung "+ Them buoc" nhu cu).
 */
class AutoAccessibilityService : AccessibilityService() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)

    // --- Trang thai ghi kich ban ---
    private var isRecording = false
    private val recordedSteps = mutableListOf<ScriptStep>()
    private var lastEventTimeMs = 0L
    private var lastPackageName: String? = null
    private var recordCounter = 0

    // --- Bong bong noi de dieu khien ghi ---
    private var overlayView: View? = null
    private var windowManager: WindowManager? = null

    companion object {
        private const val TAG = "AutoAccessibilityService"

        /** Tham chieu toi service dang chay, de noi khac (VD: MainActivity) goi runScript()/startRecording() */
        var instance: AutoAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility Service da ket noi")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isRecording || event == null) return
        // Bo qua su kien tu chinh app AutoMacro de khong ghi nham
        if (event.packageName == packageName) return

        val now = System.currentTimeMillis()
        maybeInsertWaitStep(now)

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString()
                if (!pkg.isNullOrBlank() && pkg != lastPackageName) {
                    addRecordedStep(StepType.OPEN_APP, mapOf("packageName" to pkg))
                    lastPackageName = pkg
                }
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val text = event.text?.joinToString(" ")?.trim()?.takeIf { it.isNotBlank() }
                val source = event.source
                val viewId = source?.viewIdResourceName
                when {
                    !text.isNullOrBlank() -> addRecordedStep(StepType.TAP, mapOf("by" to "text", "value" to text))
                    !viewId.isNullOrBlank() -> addRecordedStep(StepType.TAP, mapOf("by" to "viewId", "value" to viewId))
                }
                source?.recycle()
            }
            else -> {
                // Cac loai su kien khac chua duoc ghi lai trong ban nay
            }
        }
        lastEventTimeMs = now
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility Service bi ngat")
    }

    override fun onDestroy() {
        super.onDestroy()
        hideOverlay()
        instance = null
        serviceJob.cancel()
    }

    /** Chay 1 kich ban (danh sach cac buoc) trong nen, khong lam dung giao dien */
    fun runScript(steps: List<ScriptStep>) {
        serviceScope.launch {
            ScriptEngine(this@AutoAccessibilityService).run(steps)
        }
    }

    // ----------------- GHI KICH BAN (RECORD) -----------------

    /** Bat dau ghi: xoa du lieu ghi cu, hien bong bong dieu khien tren man hinh */
    fun startRecording() {
        recordedSteps.clear()
        lastEventTimeMs = System.currentTimeMillis()
        lastPackageName = null
        recordCounter = 0
        isRecording = true
        showOverlay()
    }

    private fun stopRecordingAndGetSteps(): List<ScriptStep> {
        isRecording = false
        hideOverlay()
        return recordedSteps.toList()
    }

    private fun maybeInsertWaitStep(now: Long) {
        if (recordedSteps.isEmpty()) return
        val gapMs = now - lastEventTimeMs
        if (gapMs >= 500) {
            val seconds = gapMs / 1000.0
            addRecordedStep(StepType.WAIT, mapOf("seconds" to String.format("%.1f", seconds)))
        }
    }

    private fun addRecordedStep(type: StepType, params: Map<String, String>) {
        recordCounter++
        recordedSteps.add(ScriptStep(id = "rec$recordCounter", type = type, params = params))
    }

    private fun showOverlay() {
        if (overlayView != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_recording, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 60
        params.y = 300

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var dragged = false

        view.setOnTouchListener { _, motionEvent ->
            when (motionEvent.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = motionEvent.rawX
                    initialTouchY = motionEvent.rawY
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (motionEvent.rawX - initialTouchX).toInt()
                    val dy = (motionEvent.rawY - initialTouchY).toInt()
                    if (abs(dx) > 12 || abs(dy) > 12) dragged = true
                    params.x = initialX + dx
                    params.y = initialY + dy
                    windowManager?.updateViewLayout(view, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) {
                        val steps = stopRecordingAndGetSteps()
                        openEditorWithRecordedSteps(steps)
                    }
                    true
                }
                else -> false
            }
        }

        wm.addView(view, params)
        overlayView = view
    }

    private fun hideOverlay() {
        val view = overlayView ?: return
        windowManager?.removeView(view)
        overlayView = null
    }

    private fun openEditorWithRecordedSteps(steps: List<ScriptStep>) {
        val json = ScriptJson.stepsToJson(steps)
        val intent = Intent(this, ScriptEditorActivity::class.java).apply {
            putExtra(ScriptEditorActivity.EXTRA_RECORDED_STEPS_JSON, json)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    // ----------------- CAC HAM MO PHONG THAO TAC (dung boi ScriptEngine) -----------------

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
