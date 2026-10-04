package com.starclan.starhub.engine

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.starclan.starhub.service.AutoAccessibilityService
import kotlinx.coroutines.delay

/**
 * Bo cong cu chung de "nhin" va thao tac tren cay giao dien (node) cua app khac.
 * Dung boi ScriptEngine (buoc Go chu) va DiscordSender (gui lenh tu owo-tracker).
 *
 * Y chinh: khong tin vao 1 cach duy nhat. Go chu thi thu SET_TEXT truoc, doc lai
 * xem chu da vao chua; chua vao thi dan (paste) tu clipboard roi doc lai lan nua.
 */
object NodeTools {
    private const val TAG = "NodeTools"

    // Nhan (contentDescription) cua nut Gui tren Discord, ca tieng Anh lan tieng Viet
    private val sendLabels = setOf("send", "gửi", "send message", "gửi tin nhắn")

    /** Duyet cay giao dien, tra ve cac node thoa dieu kien. Gioi han so node de khong lam dung may. */
    fun collect(
        root: AccessibilityNodeInfo?,
        maxNodes: Int = 600,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        val result = mutableListOf<AccessibilityNodeInfo>()
        val stack = mutableListOf(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < maxNodes) {
            val node = stack.removeAt(stack.size - 1)
            visited++
            if (predicate(node)) result.add(node)
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                stack.add(child)
            }
        }
        return result
    }

    private fun boundsOf(node: AccessibilityNodeInfo): Rect {
        val r = Rect()
        node.getBoundsInScreen(r)
        return r
    }

    // ----------------- O NHAP -----------------

    /** O nhap dang duoc chon; neu chua co thi lay o nhap hien tren man hinh nam thap nhat (o chat Discord). */
    fun findInput(service: AutoAccessibilityService): AccessibilityNodeInfo? {
        val focused = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) return focused
        val editors = collect(service.rootInActiveWindow) { it.isEditable && it.isVisibleToUser }
        return editors.maxByOrNull { boundsOf(it).top }
    }

    /** Doi toi da timeoutMs cho den khi co o nhap (sau khi vua cham vao o). */
    suspend fun waitForInput(service: AutoAccessibilityService, timeoutMs: Long): AccessibilityNodeInfo? {
        val start = System.currentTimeMillis()
        do {
            val node = findInput(service)
            if (node != null) return node
            delay(200)
        } while (System.currentTimeMillis() - start < timeoutMs)
        return null
    }

    /** Chu trong o nhap co chua doan `text` khong (doc lai that tu man hinh). */
    fun inputContains(input: AccessibilityNodeInfo, text: String): Boolean {
        input.refresh()
        val current = input.text?.toString()?.trim().orEmpty()
        return text.isNotBlank() && current.contains(text.trim())
    }

    // ----------------- GO CHU -----------------

    /**
     * Go `text` vao o nhap hien tai. Tra ve true CHI KHI da doc lai thay chu nam trong o.
     * Cach 1: SET_TEXT. Cach 2 (neu cach 1 khong vao): dan tu clipboard.
     */
    suspend fun typeInto(service: AutoAccessibilityService, text: String): Boolean {
        val input = waitForInput(service, 2500) ?: return false
        if (setTextAndVerify(input, text)) return true
        Log.w(TAG, "SET_TEXT khong vao, thu dan tu clipboard")
        return pasteAndVerify(service, input, text)
    }

    private suspend fun setTextAndVerify(input: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        delay(350)
        return ok && inputContains(input, text)
    }

    private suspend fun pasteAndVerify(
        service: AutoAccessibilityService,
        input: AccessibilityNodeInfo,
        text: String
    ): Boolean {
        return try {
            val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return false
            clipboard.setPrimaryClip(ClipData.newPlainText("starhub", text))
            input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            // Chon het chu dang co (neu co) de lan dan nay de len tren
            val selection = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, input.text?.length ?: 0)
            }
            input.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection)
            val ok = input.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            delay(350)
            ok && inputContains(input, text)
        } catch (e: Exception) {
            Log.w(TAG, "Dan tu clipboard loi: ${e.message}")
            false
        }
    }

    // ----------------- BAM -----------------

    /** Bam 1 node: uu tien ACTION_CLICK cua node (hoac cha bam duoc), khong duoc thi cham vao giua node. */
    suspend fun clickNode(service: AutoAccessibilityService, node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            current = current.parent
        }
        val r = boundsOf(node)
        if (r.isEmpty) return false
        return service.performClickAwait(r.exactCenterX(), r.exactCenterY())
    }

    private fun isSendLabel(node: AccessibilityNodeInfo): Boolean {
        val d = node.contentDescription?.toString()?.trim()?.lowercase() ?: return false
        return d in sendLabels
    }

    /** Tim nut Gui (theo nhan "Send"/"Gửi") dang hien tren man hinh. */
    fun findSendButton(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? =
        collect(root) { it.isVisibleToUser && isSendLabel(it) }.firstOrNull()

    // ----------------- CAPTCHA -----------------

    /**
     * Man hinh dang co tin yeu cau giai captcha cua OwO khong?
     * Chi nhan dien cau canh bao ("Are you a real human?" / "complete your captcha"),
     * khong nhan dien tin "da xac minh" de khong bi dung nham sau khi ban da giai xong.
     */
    fun looksLikeCaptcha(root: AccessibilityNodeInfo?): Boolean {
        val nodes = collect(root, maxNodes = 800) { it.text != null || it.contentDescription != null }
        for (n in nodes) {
            val t = (n.text?.toString() ?: n.contentDescription?.toString() ?: "").lowercase()
            if (t.contains("are you a real human") ||
                t.contains("complete your captcha") ||
                t.contains("captcha to verify")
            ) return true
        }
        return false
    }
}
