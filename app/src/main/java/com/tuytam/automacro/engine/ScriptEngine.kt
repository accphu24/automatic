package com.tuytam.automacro.engine

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.media.RingtoneManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.tuytam.automacro.data.ScriptStep
import com.tuytam.automacro.data.StepType
import com.tuytam.automacro.service.AutoAccessibilityService
import kotlinx.coroutines.delay

private const val TAG = "ScriptEngine"

/**
 * Bo may chay 1 kich ban: doc tung buoc theo thu tu, thuc hien, va nhay
 * sang buoc khac neu buoc do co khai bao onSuccess/onFail (xem ScriptStep.kt).
 *
 * Neu 1 buoc that bai va KHONG co onFail duoc khai bao, kich ban dung lai
 * tai do - de tranh lam sai them khi da co loi.
 */
class ScriptEngine(private val service: AutoAccessibilityService) {

    suspend fun run(steps: List<ScriptStep>) {
        if (steps.isEmpty()) return
        val indexById = steps.mapIndexed { index, step -> step.id to index }.toMap()

        var index = 0
        var guard = 0
        while (index in steps.indices) {
            guard++
            if (guard > 500) {
                Log.w(TAG, "Dung: kich ban chay qua 500 buoc, co the bi lap vo han")
                break
            }

            val step = steps[index]
            val success = try {
                executeStep(step)
            } catch (e: Exception) {
                Log.e(TAG, "Loi khi chay buoc ${step.id}: ${e.message}")
                false
            }

            val jumpId = if (success) step.onSuccess else step.onFail
            index = when {
                jumpId != null -> indexById[jumpId] ?: run {
                    Log.w(TAG, "Khong tim thay buoc id=$jumpId de nhay toi, dung kich ban")
                    steps.size
                }
                success -> index + 1
                else -> steps.size // that bai, khong co onFail duoc khai bao -> dung han
            }
        }
    }

    private suspend fun executeStep(step: ScriptStep): Boolean {
        return when (step.type) {
            StepType.OPEN_APP -> openApp(step.params["packageName"])
            StepType.WAIT -> wait(step.params["seconds"])
            StepType.TAP -> tap(step.params)
            StepType.SWIPE -> swipe(step.params)
            StepType.CHECK_TEXT -> checkText(step.params)
            StepType.CHECK_EXISTS -> checkExists(step.params)
            StepType.NOTIFY -> notify(step.params)
        }
    }

    private fun swipe(params: Map<String, String>): Boolean {
        val fromX = params["fromX"]?.toFloatOrNull() ?: return false
        val fromY = params["fromY"]?.toFloatOrNull() ?: return false
        val toX = params["toX"]?.toFloatOrNull() ?: return false
        val toY = params["toY"]?.toFloatOrNull() ?: return false
        val duration = params["durationMs"]?.toLongOrNull() ?: 300L
        service.performSwipe(fromX, fromY, toX, toY, duration)
        return true
    }

    private fun openApp(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val intent = service.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        service.startActivity(intent)
        return true
    }

    private suspend fun wait(seconds: String?): Boolean {
        val ms = ((seconds?.toFloatOrNull() ?: 1f) * 1000).toLong()
        delay(ms)
        return true
    }

    private suspend fun tap(params: Map<String, String>): Boolean {
        val by = params["by"]
        val value = params["value"]
        if (by != null && value != null) {
            val node = findNodeWithTimeout(by, value, 2000)
            if (node != null) {
                val clickable = findClickableAncestor(node)
                if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
                val rect = Rect()
                node.getBoundsInScreen(rect)
                service.performClick(rect.exactCenterX(), rect.exactCenterY())
                return true
            }
        }
        val fx = params["fallbackX"]?.toFloatOrNull()
        val fy = params["fallbackY"]?.toFloatOrNull()
        if (fx != null && fy != null) {
            service.performClick(fx, fy)
            return true
        }
        return false
    }

    private suspend fun checkText(params: Map<String, String>): Boolean {
        val by = params["by"] ?: "text"
        val value = params["value"] ?: return false
        val expected = params["expected"] ?: ""
        val timeoutMs = ((params["timeoutSeconds"]?.toFloatOrNull() ?: 2f) * 1000).toLong()
        val node = findNodeWithTimeout(by, value, timeoutMs) ?: return false
        val actual = node.text?.toString() ?: ""
        return actual.contains(expected, ignoreCase = true)
    }

    private suspend fun checkExists(params: Map<String, String>): Boolean {
        val by = params["by"] ?: "text"
        val value = params["value"] ?: return false
        val timeoutMs = ((params["timeoutSeconds"]?.toFloatOrNull() ?: 5f) * 1000).toLong()
        return findNodeWithTimeout(by, value, timeoutMs) != null
    }

    private fun notify(params: Map<String, String>): Boolean {
        if (params["vibrate"] == "true") {
            val vibrator = service.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            vibrator?.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
        }
        if (params["sound"] == "true" || params["ring"] == "true") {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(service, uri)?.play()
        }
        Log.w(TAG, "NOTIFY: ${params["message"] ?: "Kich ban can chu y"}")
        return true
    }

    private suspend fun findNodeWithTimeout(by: String, value: String, timeoutMs: Long): AccessibilityNodeInfo? {
        val start = System.currentTimeMillis()
        do {
            val node = findNode(service.rootInActiveWindow, by, value)
            if (node != null) return node
            delay(300)
        } while (System.currentTimeMillis() - start < timeoutMs)
        return null
    }

    private fun findNode(root: AccessibilityNodeInfo?, by: String, value: String): AccessibilityNodeInfo? {
        if (root == null) return null
        val list = if (by == "viewId") {
            root.findAccessibilityNodeInfosByViewId(value)
        } else {
            root.findAccessibilityNodeInfosByText(value)
        }
        return list?.firstOrNull()
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) return current
            current = current.parent
        }
        return null
    }
}
