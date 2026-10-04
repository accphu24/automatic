package com.starclan.starhub.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.media.RingtoneManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import com.starclan.starhub.R
import com.starclan.starhub.ScriptEditorActivity
import com.starclan.starhub.data.OwoTrackerApi
import com.starclan.starhub.data.OwoTrackerPrefs
import com.starclan.starhub.data.ScriptJson
import com.starclan.starhub.data.ScriptStep
import com.starclan.starhub.data.StepType
import com.starclan.starhub.data.summarizeStep
import com.starclan.starhub.engine.DiscordSender
import com.starclan.starhub.engine.ScriptEngine
import com.starclan.starhub.engine.SendResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.random.Random

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
 * 2 nut tren bong bong de tu tay danh dau khi tu dong khong bat duoc:
 *   👆 = danh dau 1 diem de Bam (dung khi bam-tu-dong khong ghi nhan duoc,
 *        vi du: nut Gui nam TREN BAN PHIM chu khong phai tren man hinh app)
 *   ➕ = danh dau 2 diem (dau/cuoi) de tao buoc Vuot chinh xac
 * Ca 2 deu hien 1 cham mau tai dung vi tri vua cham de xac nhan bang mat.
 *
 * GIOI HAN cua ban ghi tu dong (khong qua nut thu cong o tren):
 * - Chi bat duoc thao tac bam vao nut/thanh phan co CHU hoac co ID tren
 *   MAN HINH APP. KHONG bat duoc: go chu vao o nhap, bam nut Gui/Enter
 *   tren ban phim (day la gioi han that cua Android, khong sua duoc bang
 *   code app nay) - nhung truong hop nay dung nut 👆 de thay the.
 */
class AutoAccessibilityService : AccessibilityService() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)

    // Cac goi he thong khong tinh la "mo app" khi ghi (ban phim, thanh trang thai...)
    private val ignoredPackages = setOf(
        "com.android.systemui",
        "com.samsung.android.honeyboard",
        "com.google.android.inputmethod.latin",
        "com.android.inputmethod.latin",
        "com.touchtype.swiftkey",
        "com.samsung.android.app.smartcapture"
    )

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

    // --- Man hinh chon diem (dung khi bam nut 👆/➕) ---
    private var pickerView: View? = null

    // --- Dong bo voi owo-tracker: cu vai giay kiem tra lenh moi 1 lan ---
    private var syncJob: Job? = null

    // Khi OwO doi giai captcha: tam dung gui lenh toi moc nay (de Ruby tu giai), tranh gui don dap
    private var captchaPauseUntilMs = 0L

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
        val saved = OwoTrackerPrefs.load(this)
        applySyncSettings(saved.enabled, saved.apiUrl, saved.token)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isRecording || event == null) return
        // Bo qua su kien tu chinh app AutoMacro de khong ghi nham
        if (event.packageName == packageName) return

        val now = System.currentTimeMillis()

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString()
                if (!pkg.isNullOrBlank() && pkg != lastPackageName && pkg !in ignoredPackages) {
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
                val tapParams = when {
                    !text.isNullOrBlank() -> mapOf("by" to "text", "value" to text)
                    !viewId.isNullOrBlank() -> mapOf("by" to "viewId", "value" to viewId)
                    else -> null
                }
                source?.recycle()
                // Chen Doi (neu can) TRUOC khi ghi buoc Bam, khong phai sau -
                // de thu tu buoc dung nhu thuc te da xay ra.
                if (tapParams != null) {
                    maybeInsertWaitStep(now)
                    addRecordedStep(StepType.TAP, tapParams)
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

    // ----------------- DONG BO VOI OWO-TRACKER -----------------

    /** Goi khi Ruby luu cai dat ket noi trong man hinh Cai dat - bat/tat vong lap kiem tra lenh. */
    fun applySyncSettings(enabled: Boolean, apiUrl: String, token: String) {
        syncJob?.cancel()
        syncJob = null
        if (!enabled || apiUrl.isBlank() || token.isBlank()) {
            Log.d(TAG, "Dong bo owo-tracker: TAT")
            return
        }
        Log.d(TAG, "Dong bo owo-tracker: BAT, cu 15 giay kiem tra 1 lan")
        syncJob = serviceScope.launch {
            while (isActive) {
                try {
                    pollOwoTrackerOnce(apiUrl, token)
                } catch (e: Exception) {
                    Log.e(TAG, "Loi khi dong bo owo-tracker: ${e.message}")
                }
                delay(15_000)
            }
        }
    }

    private suspend fun pollOwoTrackerOnce(apiUrl: String, token: String) {
        // Dang tam dung vi captcha -> khong dong vao lenh nao, de chung nam cho
        if (System.currentTimeMillis() < captchaPauseUntilMs) return

        val commands = OwoTrackerApi.fetchPending(apiUrl, token)
        if (commands.isEmpty()) return

        val settings = OwoTrackerPrefs.load(this)
        if (!settings.hasDiscordTargets) {
            // Toa do chi la phuong an du phong (khi khong tim thay o nhap/nut Gui theo giao dien)
            Log.w(TAG, "Chua cai vi tri o nhap/nut Gui Discord - van thu tim theo giao dien")
        }

        val sender = DiscordSender(this)
        var first = true
        for (cmd in commands) {
            val commandText = cmd.params["command_text"]
            if (commandText.isNullOrBlank()) {
                Log.w(TAG, "Lenh '${cmd.action}' khong co command_text - bo qua")
                OwoTrackerApi.ack(apiUrl, token, cmd.id, "failed")
                continue
            }

            val result = try {
                sender.send(commandText, settings, reopenChannel = first)
            } catch (e: Exception) {
                Log.e(TAG, "Loi khi chay lenh '${cmd.action}': ${e.message}")
                SendResult.SEND_FAILED
            }
            first = false
            Log.d(TAG, "Lenh '${cmd.action}' (${cmd.id}) -> $result")

            OwoTrackerApi.ack(apiUrl, token, cmd.id, if (result == SendResult.OK) "done" else "failed")

            if (result == SendResult.CAPTCHA) {
                alertCaptcha()
                captchaPauseUntilMs = System.currentTimeMillis() + 10 * 60 * 1000L
                break // dung ngay, cac lenh con lai de lan sau
            }
            // Nghi ngau nhien giua 2 lenh, khong gui lien tuc nhu may
            delay(Random.nextLong(2500, 5500))
        }
    }

    /** Rung + chuong de Ruby biet OwO dang doi giai captcha. */
    private fun alertCaptcha() {
        Log.w(TAG, "OwO dang yeu cau captcha - dung gui lenh tu dong 10 phut")
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        vibrator?.vibrate(VibrationEffect.createOneShot(1500, VibrationEffect.DEFAULT_AMPLITUDE))
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(this, uri)?.play()
        } catch (e: Exception) {
            Log.w(TAG, "Khong phat duoc chuong: ${e.message}")
        }
    }

    /** Wizard cai vi tri 1 lan: cham o nhap tin nhan, roi cham nut Gui, luu lai de dung cho moi lenh sau nay. */
    fun setupDiscordTargets(onComplete: (msgX: Float, msgY: Float, sendX: Float, sendY: Float) -> Unit) {
        startTwoPointPicking(
            getString(R.string.picker_prompt_message_box),
            getString(R.string.picker_prompt_send_button),
            onComplete
        )
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
        // danh sach ben duoi cuon binh thuong, va 2 nut 👆/➕ tu xu ly rieng cu bam cua chung.
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

        view.findViewById<View>(R.id.btnAddTapPoint).setOnClickListener {
            startTapPointPicking { x, y ->
                maybeInsertWaitStep(System.currentTimeMillis())
                addRecordedStep(
                    StepType.TAP,
                    mapOf("fallbackX" to x.toInt().toString(), "fallbackY" to y.toInt().toString())
                )
                lastEventTimeMs = System.currentTimeMillis()
            }
        }

        view.findViewById<View>(R.id.btnAddSwipePoint).setOnClickListener {
            startTwoPointPicking(getString(R.string.picker_prompt_start), getString(R.string.picker_prompt_end)) { fromX, fromY, toX, toY ->
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

    // ----------------- CHON DIEM TREN MAN HINH (co dau cham xac nhan) -----------------

    /** Danh dau 1 diem - dung cho nut 👆 (Bam) */
    private fun startTapPointPicking(onComplete: (x: Float, y: Float) -> Unit) {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_point_picker, null)
        view.findViewById<TextView>(R.id.tvPickerPrompt).text = getString(R.string.picker_prompt_tap)
        val marker = view.findViewById<View>(R.id.pickerMarkerStart)

        val params = pickerWindowParams()

        view.setOnTouchListener { _, motionEvent ->
            if (motionEvent.action == MotionEvent.ACTION_DOWN) {
                val x = motionEvent.rawX
                val y = motionEvent.rawY
                placeMarker(marker, x, y)
                view.postDelayed({
                    removePickerView(wm, view)
                    onComplete(x, y)
                }, 350)
            }
            true
        }

        wm.addView(view, params)
        pickerView = view
    }

    /** Danh dau 2 diem lien tiep (dau roi cuoi), ca 2 cham cung hien tren 1 man hinh - dung chung cho
     * nut ➕ (Vuot) va wizard cai vi tri Discord (setupDiscordTargets). */
    private fun startTwoPointPicking(
        prompt1: String,
        prompt2: String,
        onComplete: (x1: Float, y1: Float, x2: Float, y2: Float) -> Unit
    ) {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_point_picker, null)
        val promptView = view.findViewById<TextView>(R.id.tvPickerPrompt)
        val markerStart = view.findViewById<View>(R.id.pickerMarkerStart)
        val markerEnd = view.findViewById<View>(R.id.pickerMarkerEnd)
        promptView.text = prompt1

        val params = pickerWindowParams()
        var firstPoint: Pair<Float, Float>? = null

        view.setOnTouchListener { _, motionEvent ->
            if (motionEvent.action == MotionEvent.ACTION_DOWN) {
                val x = motionEvent.rawX
                val y = motionEvent.rawY
                val first = firstPoint
                if (first == null) {
                    firstPoint = x to y
                    placeMarker(markerStart, x, y)
                    promptView.text = prompt2
                } else {
                    placeMarker(markerEnd, x, y)
                    view.postDelayed({
                        removePickerView(wm, view)
                        onComplete(first.first, first.second, x, y)
                    }, 400)
                }
            }
            true
        }

        wm.addView(view, params)
        pickerView = view
    }

    private fun pickerWindowParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
    }

    /** Dat 1 cham danh dau tai dung toa do (x, y) vua cham, de nguoi dung thay ro da cham dung cho */
    private fun placeMarker(marker: View, x: Float, y: Float) {
        val size = marker.layoutParams.width.takeIf { it > 0 }
            ?: (26 * resources.displayMetrics.density).toInt()
        val lp = marker.layoutParams as FrameLayout.LayoutParams
        lp.leftMargin = (x - size / 2).toInt()
        lp.topMargin = (y - size / 2).toInt()
        marker.layoutParams = lp
        marker.visibility = View.VISIBLE
    }

    private fun removePickerView(wm: WindowManager, view: View) {
        try {
            wm.removeView(view)
        } catch (e: Exception) {
            // Da bi go truoc do (VD: service bi ngat giua chung), bo qua
        }
        if (pickerView == view) pickerView = null
    }

    private fun hidePicker() {
        val view = pickerView ?: return
        removePickerView(getSystemService(WINDOW_SERVICE) as WindowManager, view)
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

    /**
     * Nhu performClick nhung CHO den khi cu cham thuc su xong (hoac bi huy) roi moi tra ve.
     * Tra ve true neu he thong bao da cham xong. Dung cho cac buoc phai doi o nhap duoc chon
     * truoc khi go chu.
     */
    suspend fun performClickAwait(x: Float, y: Float, durationMs: Long = 50): Boolean =
        suspendCancellableCoroutine { cont ->
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }
            val dispatched = dispatchGesture(gesture, callback, null)
            if (!dispatched && cont.isActive) cont.resume(false)
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
