package com.starclan.starhub.engine

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.starclan.starhub.service.AutoAccessibilityService
import kotlinx.coroutines.delay

/**
 * Nhan thuong nhiem vu cua OwO bang cach BAM NUT tren Quest Log (owo quest) trong Discord:
 *   tab Daily  -> nut "Claim Rewards"
 *   tab Weekly -> nut "Claim Weekly Reward"
 *   tab Quests -> nut "Claim"
 * Chi bam khi nut dang sang (enabled). Goi sau khi da go `owo quest` va Quest Log da hien o cuoi kenh.
 */
object QuestClaimer {
    private const val TAG = "QuestClaimer"

    data class ClaimResult(val claimed: List<String>, val tabsFound: Int)

    private data class Tab(val name: String, val tabLabel: String, val claimLabel: String)

    private val TABS = listOf(
        Tab("Daily", "daily", "claim rewards"),
        Tab("Weekly", "weekly", "claim weekly reward"),
        Tab("Quests", "quests", "claim")
    )

    private val NON_TEXT = Regex("[^\\p{L}\\p{N} ]")
    private val SPACES = Regex("\\s+")

    /** Bo emoji/dau cau, chu thuong, gop khoang trang: "📋 Daily" -> "daily" */
    private fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val cleaned = NON_TEXT.replace(raw.lowercase(), " ")
        return SPACES.replace(cleaned, " ").trim()
    }

    private fun labelMatches(node: AccessibilityNodeInfo, target: String): Boolean {
        val candidates = listOf(node.text?.toString(), node.contentDescription?.toString())
        for (c in candidates) {
            var s = normalize(c)
            if (s.endsWith(" button")) s = s.removeSuffix(" button").trim()
            if (s == target || s.endsWith(" $target")) return true
        }
        return false
    }

    /** Tim node khop nhan, lay cai nam THAP NHAT tren man hinh (= tin Quest Log moi nhat o cuoi kenh). */
    private fun findBottomMost(service: AutoAccessibilityService, target: String): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        val nodes = NodeTools.collect(root, maxNodes = 1500) { it.isVisibleToUser && labelMatches(it, target) }
        // Discord dat ten nut o mo ta (desc) cua chinh nut va lap lai bang 1 o chu con: uu tien nut bam duoc
        val clickable = nodes.filter { it.isClickable }
        val pool = if (clickable.isNotEmpty()) clickable else nodes
        return pool.maxByOrNull { n ->
            val r = Rect()
            n.getBoundsInScreen(r)
            r.top
        }
    }

    /** Nut co dang sang (bam duoc) khong? Kiem tra ca node va cac node cha cho toi nut bam duoc. */
    private fun effectivelyEnabled(node: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = node
        while (cur != null) {
            if (!cur.isEnabled) return false
            if (cur.isClickable) return true
            cur = cur.parent
        }
        return true
    }

    suspend fun claimAll(service: AutoAccessibilityService): ClaimResult {
        val claimed = mutableListOf<String>()
        var tabsFound = 0
        for (tab in TABS) {
            val tabNode = findBottomMost(service, tab.tabLabel)
            if (tabNode == null) {
                Log.w(TAG, "Khong thay tab ${tab.name}")
                continue
            }
            tabsFound++
            NodeTools.clickNode(service, tabNode)
            delay(1_800)

            val claimNode = findBottomMost(service, tab.claimLabel)
            if (claimNode == null) {
                Log.i(TAG, "Tab ${tab.name}: khong thay nut nhan thuong")
                continue
            }
            if (!effectivelyEnabled(claimNode)) {
                Log.i(TAG, "Tab ${tab.name}: nut nhan thuong chua sang (chua xong nhiem vu)")
                continue
            }
            if (NodeTools.clickNode(service, claimNode)) {
                Log.i(TAG, "Tab ${tab.name}: da bam nhan thuong")
                claimed.add(tab.name)
                delay(2_000)
            }
        }
        return ClaimResult(claimed, tabsFound)
    }
}
