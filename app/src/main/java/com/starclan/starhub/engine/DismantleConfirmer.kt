package com.starclan.starhub.engine

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.starclan.starhub.service.AutoAccessibilityService
import com.starclan.starhub.view.FarmOverlay
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Sau khi app gõ `owo dismantle <ID>`, OwO hiện hộp xác nhận ("Are you sure you want to dismantle 1 ... weapon ...?")
 * kèm nút Dismantle / Cancel. Object này ĐỌC hộp đó trên màn hình và CHỈ bấm Dismantle khi mọi thứ khớp:
 *   - đúng 1 hộp "Are you sure" trong cùng 1 khối tin nhắn nhỏ (không phải cả danh sách chat),
 *   - khối đó không chứa tin lệnh của chính mình ("owo dismantle ..."), để ID không khớp nhầm với tin mình vừa gõ,
 *   - có đúng ID cây vũ khí bot đã chọn,
 *   - số cây là 1 ("dismantle 1 ..."),
 *   - chất lượng % trên hộp khớp chất lượng bot ghi nhận (sai số 0,15) nếu bot có gửi.
 * Không khớp -> bấm Cancel (nếu thấy) và báo thất bại kèm chữ đọc được. Chế độ chạy thử (click=false) luôn bấm Cancel.
 */
object DismantleConfirmer {
    private const val TAG = "DismantleConfirmer"
    private const val MAX_CONTAINER_NODES = 90

    class Outcome(val ok: Boolean, val note: String, val captcha: Boolean = false)
    private class Step(val done: Boolean, val outcome: Outcome)

    private val NON_TEXT = Regex("[^\\p{L}\\p{N} ]")
    private val SPACES = Regex("\\s+")
    private val PERCENT = Regex("(\\d{1,3}(?:\\.\\d+)?)\\s*%")
    private val COUNT = Regex("(?i)dismantle\\s+(\\d+)")
    private val OWN_COMMAND = Regex("(?i)^\\s*o(wo)?\\s*dismantle\\b")

    private fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return SPACES.replace(NON_TEXT.replace(raw.lowercase(), " "), " ").trim()
    }

    private fun labelIs(node: AccessibilityNodeInfo, target: String): Boolean {
        for (c in listOf(node.text?.toString(), node.contentDescription?.toString())) {
            var s = normalize(c)
            if (s.endsWith(" button")) s = s.removeSuffix(" button").trim()
            if (s == target) return true
        }
        return false
    }

    private fun enabledAndClickable(node: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = node
        while (cur != null) {
            if (!cur.isEnabled) return false
            if (cur.isClickable) return true
            cur = cur.parent
        }
        return false
    }

    private fun topOf(node: AccessibilityNodeInfo): Int {
        val r = android.graphics.Rect()
        node.getBoundsInScreen(r)
        return r.top
    }

    /** Chữ riêng của từng node trong cây con của `container`. */
    private fun textsOf(container: AccessibilityNodeInfo): List<String> {
        val out = mutableListOf<String>()
        for (n in NodeTools.collect(container, MAX_CONTAINER_NODES + 5) { true }) {
            val t = listOf(n.text?.toString(), n.contentDescription?.toString()).firstOrNull { !it.isNullOrBlank() }
            if (!t.isNullOrBlank()) out.add(t)
        }
        return out
    }

    private fun sizeOf(container: AccessibilityNodeInfo): Int =
        NodeTools.collect(container, MAX_CONTAINER_NODES + 5) { true }.size

    private fun buttonsIn(container: AccessibilityNodeInfo, label: String): List<AccessibilityNodeInfo> =
        NodeTools.collect(container, MAX_CONTAINER_NODES + 5) { it.isVisibleToUser && labelIs(it, label) }
            .filter { enabledAndClickable(it) }

    private fun snippet(texts: List<String>): String =
        SPACES.replace(texts.joinToString(" | "), " ").take(380)

    suspend fun confirm(
        service: AutoAccessibilityService,
        expectId: String,
        expectQuality: Double?,
        click: Boolean
    ): Outcome {
        delay(2_500L) // chờ OwO trả hộp xác nhận
        var last = Outcome(false, "Không thấy hộp xác nhận 'Are you sure' kèm nút Dismantle sau ~10 giây (cây không tồn tại / bị khóa / OwO chưa trả lời).")
        for (i in 1..5) {
            if (!FarmOverlay.isShown()) return Outcome(false, "Hub nổi đã tắt giữa chừng.")
            val step = try {
                attempt(service, expectId, expectQuality, click)
            } catch (e: Exception) {
                Log.w(TAG, "Lỗi khi đọc hộp xác nhận: ${e.message}")
                Step(false, Outcome(false, "Lỗi khi đọc màn hình: ${e.message}"))
            }
            if (step.done) return step.outcome
            last = step.outcome
            delay(1_500L)
        }
        return last
    }

    private suspend fun attempt(
        service: AutoAccessibilityService,
        expectId: String,
        expectQuality: Double?,
        click: Boolean
    ): Step {
        val root = service.rootInActiveWindow ?: return Step(false, Outcome(false, "Không đọc được màn hình."))
        if (NodeTools.looksLikeCaptcha(root)) return Step(true, Outcome(false, "CAPTCHA: OwO đang đòi giải captcha.", captcha = true))

        // Nút Dismantle thấp nhất trên màn hình = hộp xác nhận mới nhất
        val dismantleBtn = NodeTools.collect(root, 1500) { it.isVisibleToUser && labelIs(it, "dismantle") }
            .filter { enabledAndClickable(it) }
            .maxByOrNull { topOf(it) }
            ?: return Step(false, Outcome(false, "Chưa thấy nút Dismantle."))

        // Khối tin nhắn nhỏ nhất chứa cả nút lẫn câu "Are you sure"
        var container: AccessibilityNodeInfo? = null
        var texts: List<String> = emptyList()
        var cur: AccessibilityNodeInfo? = dismantleBtn.parent
        var depth = 0
        while (cur != null && depth < 12) {
            val t = textsOf(cur)
            if (t.any { it.lowercase().contains("are you sure") }) {
                container = cur
                texts = t
                break
            }
            cur = cur.parent
            depth++
        }
        if (container == null) return Step(false, Outcome(false, "Thấy nút Dismantle nhưng không thấy câu 'Are you sure' cùng khối tin nhắn."))

        // --- các điều kiện an toàn: sai là KHÔNG bấm Dismantle
        fun reject(reason: String): Step {
            return Step(true, Outcome(false, "$reason | chữ đọc được: ${snippet(texts)}"))
        }
        if (sizeOf(container) > MAX_CONTAINER_NODES) return reject("Khối đọc được quá lớn (có thể lẫn nhiều tin), không dám bấm.")
        val promptTexts = texts.filter { it.lowercase().contains("are you sure") }
        if (promptTexts.size != 1) return reject("Có ${promptTexts.size} hộp 'Are you sure' trong khối đọc được.")
        if (texts.any { OWN_COMMAND.containsMatchIn(it) }) return reject("Khối đọc được lẫn tin lệnh mình vừa gõ, không tách được hộp xác nhận.")
        val joined = texts.joinToString(" ")
        val idRe = Regex("(?i)(?<![A-Za-z0-9])" + Regex.escape(expectId) + "(?![A-Za-z0-9])")
        if (!idRe.containsMatchIn(joined)) return reject("Không thấy ID $expectId trên hộp xác nhận.")
        val count = COUNT.find(promptTexts[0])?.groupValues?.get(1)?.toIntOrNull()
        if (count != 1) return reject("Không xác nhận được 'dismantle 1' (đọc được: ${count ?: "không có số"}).")
        if (expectQuality != null) {
            val okQ = PERCENT.findAll(joined).any {
                val v = it.groupValues[1].toDoubleOrNull()
                v != null && abs(v - expectQuality) < 0.16
            }
            if (!okQ) return reject("Chất lượng trên hộp không khớp $expectQuality%.")
        }

        // --- khớp hết
        val cancelBtn = buttonsIn(container, "cancel").maxByOrNull { topOf(it) }
        val dismantleInBox = buttonsIn(container, "dismantle").maxByOrNull { topOf(it) }
        val note = snippet(texts)
        if (!click) {
            if (cancelBtn == null) return Step(true, Outcome(false, "Hộp khớp nhưng không thấy nút Cancel | chữ đọc được: $note"))
            if (!NodeTools.clickNode(service, cancelBtn)) return Step(true, Outcome(false, "Bấm Cancel thất bại | chữ đọc được: $note"))
            return Step(true, Outcome(true, "Hộp khớp ID/chất lượng/'1 weapon', đã bấm Cancel | chữ đọc được: $note"))
        }
        if (dismantleInBox == null) return Step(true, Outcome(false, "Nút Dismantle trong hộp không bấm được | chữ đọc được: $note"))
        if (!NodeTools.clickNode(service, dismantleInBox)) return Step(true, Outcome(false, "Bấm Dismantle thất bại | chữ đọc được: $note"))
        delay(2_000L)
        return Step(true, Outcome(true, "Đã bấm Dismantle | chữ đọc được: $note"))
    }
}
