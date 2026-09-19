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
import android.widget.ScrollView
import android.widget.TextView
import com.tuytam.automacro.R
import com.tuytam.automacro.ScriptEditorActivity
import com.tuytam.automacro.data.ScriptJson
import com.tuytam.automacro.data.ScriptStep
import com.tuytam.automacro.data.StepType
import com.tuytam.automacro.data.summarizeStep
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
 * cua Ruby va bien thanh danh sach ScriptStep, HIEN NGAY tren bong bong
 * (co danh so thu tu) de biet dang ghi dung khong -> bam lai vao dau bong
 * bong (khong keo) de dung, mo thang sang man hinh sua kich ban.
 *
 * Rieng buoc VUOT: Android khong cho biet toa do ngon tay that khi vuot,
 * nen KHONG doan tu dong - thay vao do co nut ➕ tren bong bong de Ruby tu
 * cham 2 diem that (dau va cuoi) tren man hinh, ghep thanh 1 duong thang
 * chinh xac.
 *
 * GIOI HAN cua ban ghi nay:
 * - Bam vao nut/thanh phan co CHU hoac co ID -> ghi tu dong, chinh xac.
 * - Vuot -> phai chu dong bam nut ➕ roi cham 2 diem (khong tu dong ghi
 *   khi Ruby tu vuot tay, vi khong doc duoc toa do that).
 * - CHUA ghi duoc: go chu vao o nhap, cac buoc Kiem tra/Bao dong (Ruby van
 *   can tu them tay sau khi ghi, dung "+ Them buoc" nhu cu).
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
    private var overlayParams: WindowManager.LayoutParams? = null
    private var windowManager: WindowManager? = null

    // --- Man hinh chon diem (dung khi bam nut ➕ de danh dau vuot) ---
    private var pickerView: View? = null

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

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString()
                if (!pkg.isNullOrBlank() && pkg != lastPackageName) {
                    maybeInsertWaitStep(now)
                    addRecordedStep(StepType.OPEN_APP, mapOf("packageName" to pkg))
                    lastPackageName = pkg
                    lastEventTimeMs = now
                }
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val text = event.text?.joinToString(" ")?.trim()?.takeIf { it.isNotBlank() }
                val source = event.source
                val viewId = source?.viewIdResourceName
                val added = when {
                    !text.isNullOrBlank() -> {
                        addRecordedStep(StepType.TAP, mapOf("by" to "text", "value" to text)); true
                    }
                    !viewId.isNullOrBlank() -> {
                        addRecordedStep(StepType.TAP, mapOf("by" to "viewId", "value" to viewId)); true
                    }
                    else -> false
                }
                source?.recycle()
                if (added) {
                    maybeInsertWaitStep(now)
                    lastEventTimeMs = now
                }
            }
            else -> {
                // Cac loai su kien khac chua duoc ghi lai trong ban nay
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility Service bi ngat")
    }

    override fun onDestroy() {
        super.onDestroy()
        hideOverlay()
        hidePicker()
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
        updateOverlayText()
    }

    /** Ve lai danh sach hanh dong (co danh so tu 1) tren bong bong, tu cuon xuong dong moi nhat */
    private fun updateOverlayText() {
        val view = overlayView ?: return
        val tv = view.findViewById<TextView>(R.id.tvOverlaySteps) ?: return
        val sv = view.findViewById<ScrollView>(R.id.svOverlaySteps)

        tv.text = if (recordedSteps.isEmpty()) {
            getString(R.string.overlay_no_action_yet)
        } else {
            recordedSteps.mapIndexed { index, step -> "${index + 1}. ${summarizeStep(step)}" }
                .joinToString("\n")
        }

        overlayParams?.let { windowManager?.updateViewLayout(view, it) }
        sv?.post { sv.fullScroll(View.FOCUS_DOWN) }
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
        params.x = 40
        params.y = 250
        overlayParams = params

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var dragged = false

        // Chi gan cam ung keo/dung vao PHAN DAU (dau tron + nhan chu) - de vung
        // danh sach ben duoi cuon binh thuong, va nut ➕ tu xu ly rieng cu bam cua no.
        val header = view.findViewById<View>(R.id.overlayHeader)
        header.setOnTouchListener { _, motionEvent ->
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
                        val recorded = stopRecordingAndGetSteps()
                        openEditorWithRecordedSteps(recorded)
                    }
                    true
                }
                else -> false
            }
        }

        view.findViewById<View>(R.id.btnAddSwipePoint).setOnClickListener {
            startSwipePointPicking { fromX, fromY, toX, toY ->
                maybeInsertWaitStep(System.currentTimeMillis())
                addRecordedStep(
                    StepType.SWIPE,
                    mapOf(
                        "fromX" to fromX.toInt().toString(),
                        "fromY" to fromY.toInt().toString(),
                        "toX" to toX.toInt().toString(),
                        "toY" to toY.toInt().toString()
                    )
                )
                lastEventTimeMs = System.currentTimeMillis()
            }
        }

        wm.addView(view, params)
        overlayView = view
    }

    private fun hideOverlay() {
        val view = overlayView ?: return
        windowManager?.removeView(view)
        overlayView = null
        overlayParams = null
    }

    private fun openEditorWithRecordedSteps(steps: List<ScriptStep>) {
        val json = ScriptJson.stepsToJson(steps)
        val intent = Intent(this, ScriptEditorActivity::class.java).apply {
            putExtra(ScriptEditorActivity.EXTRA_RECORDED_STEPS_JSON, json)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    // ----------------- CHON 2 DIEM TREN MAN HINH (cho buoc Vuot) -----------------

    /** Cham diem DAU -> doi chu -> cham diem CUOI -> tra ve 4 toa do qua onComplete */
    private fun startSwipePointPicking(onComplete: (fromX: Float, fromY: Float, toX: Float, toY: Float) -> Unit) {
        startPointPicking(getString(R.string.picker_prompt_start)) { x1, y1 ->
            startPointPicking(getString(R.string.picker_prompt_end)) { x2, y2 ->
                onComplete(x1, y1, x2, y2)
            }
        }
    }

    private fun startPointPicking(promptText: String, onPicked: (Float, Float) -> Unit) {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_point_picker, null)
        view.findViewById<TextView>(R.id.tvPickerPrompt).text = promptText

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        view.setOnTouchListener { _, motionEvent ->
            if (motionEvent.action == MotionEvent.ACTION_DOWN) {
                val x = motionEvent.rawX
                val y = motionEvent.rawY
                wm.removeView(view)
                if (pickerView == view) pickerView = null
                onPicked(x, y)
            }
            true
        }

        wm.addView(view, params)
        pickerView = view
    }

    private fun hidePicker() {
        val view = pickerView ?: return
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view)
        } catch (e: Exception) {
            // Da bi go truoc do, bo qua
        }
        pickerView = null
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
