package com.starclan.starhub.engine

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.starclan.starhub.service.AutoAccessibilityService

/**
 * Chup "cau truc nut" cua man hinh dang hien (vd Quest Log trong Discord): chu, mo ta, nut nao bam duoc,
 * nut nao bi mo, vi tri. Dung de gui cho Claude viet dung cach bam nut thay vi doan (bam nham quest/nang cap
 * thi mat that). Luu y: ket qua chua chu dang hien tren man hinh.
 */
object NodeDump {
    private const val MAX_LINES = 700

    fun dump(service: AutoAccessibilityService): String {
        val root = service.rootInActiveWindow ?: return "Khong doc duoc man hinh (rootInActiveWindow = null)"
        val sb = StringBuilder()
        sb.append("package=").append(root.packageName).append('\n')
        var count = 0

        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (count >= MAX_LINES) return
            val text = n.text?.toString()?.replace("\n", " ")?.take(120).orEmpty()
            val desc = n.contentDescription?.toString()?.replace("\n", " ")?.take(120).orEmpty()
            if (n.isVisibleToUser && (text.isNotEmpty() || desc.isNotEmpty() || n.isClickable)) {
                val r = Rect()
                n.getBoundsInScreen(r)
                sb.append("  ".repeat(minOf(depth, 12)))
                    .append(n.className?.toString()?.substringAfterLast('.') ?: "?")
                    .append(" text='").append(text).append("' desc='").append(desc).append("'")
                    .append(if (n.isClickable) " CLICK" else "")
                    .append(if (!n.isEnabled) " DISABLED" else "")
                    .append(" [").append(r.left).append(',').append(r.top).append(',')
                    .append(r.right).append(',').append(r.bottom).append("]\n")
                count++
            }
            for (i in 0 until n.childCount) {
                val child = n.getChild(i) ?: continue
                walk(child, depth + 1)
            }
        }

        walk(root, 0)
        if (count >= MAX_LINES) sb.append("... (cat bot, qua dai)\n")
        return sb.toString()
    }
}
