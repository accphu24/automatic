package com.starclan.starhub.engine

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.starclan.starhub.service.AutoAccessibilityService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Quan ly Quest Log SAU khi QuestClaimer da nhan thuong (man hinh dang o tab Quests):
 *  1) KHOA (Lock) quest co thuong cao (weapon shard / crate) de khong bi doi mat. Luong that (tu dump cua Ruby):
 *     bam "Lock/Unlock" -> hien menu "Select a quest to lock/unlock..." -> bam menu -> chon dong "🔓 Quest N: ..."
 *     -> nut "Lock" sang -> bam "Lock". Xong doc lai: quest phai co dau khoa.
 *  2) MUA NANG CAP bang Quest Seals (3 gio / lan): nut "seal" o dau Quest Log -> trang Quest Upgrades (co the nhieu trang,
 *     nut "forward"); tab Daily -> "seal" -> Daily Upgrades. Moi lan mua mon RE NHAT trong danh sach cho phep, mua xong doc lai
 *     "Level: x/y" phai tang, khong tang thi DUNG. Khong mua Quest Slots / Quest Tier / Extra Rerolls (dat, de sau).
 * Chua lam Reroll: nut Reroll dang xam trong dump nen chua biet luong chon quest.
 */
object QuestManager {
    private const val TAG = "QuestManager"
    private const val MAX_LOCKS_PER_RUN = 3
    private const val UPGRADE_MAX_COST = 400
    private const val UPGRADE_MAX_BUYS = 25

    private val UPGRADE_ALLOWED = listOf(
        "Reward Type Luck", "Quest Reward Boost", "Quest Seal Boost", "Bonus Quest Chance",
        "Checklist Seal Bonus", "Checklist Reward Boost", "Checklist Jackpot"
    )
    private val UPGRADE_KNOWN = UPGRADE_ALLOWED + listOf("Quest Slots", "Quest Tier Unlock", "Extra Quest Rerolls")

    private val NON_TEXT = Regex("[^\\p{L}\\p{N} ]")
    private val SPACES = Regex("\\s+")
    private val INVISIBLE = Regex("[\\u200B\\u2060\\u2066-\\u2069]")
    private val QUEST_HEAD = Regex("(\\d+)\\.\\s+[A-Za-z]+")
    private val REWARD = Regex("([A-Za-z_]+)\\s*\\+\\s*([\\d,]+)")
    private val PROGRESS = Regex("(\\d[\\d,]*)\\s*/\\s*(\\d[\\d,]*)\\s*$")
    private val LEVEL = Regex("Level:\\s*(\\d+)\\s*/\\s*(\\d+)")
    private val BALANCE = Regex("Balance:\\s*([\\d,]+)")
    private val PAGE = Regex("^(\\d+)/(\\d+)$")
    private val COST = Regex("^[\\d,]+$")

    class Report(val locked: List<String>, val bought: List<String>, val note: String?)

    private class Quest(val index: Int, val locked: Boolean, val done: Boolean, val high: Boolean, val rewardText: String)
    private class UpRow(val name: String, val level: Int?, val cost: Int?, val enabled: Boolean, val button: AccessibilityNodeInfo?)

    // ----------------- tien ich doc man hinh -----------------

    private fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return SPACES.replace(NON_TEXT.replace(raw.lowercase(), " "), " ").trim()
    }

    private fun exactLabel(node: AccessibilityNodeInfo, target: String): Boolean {
        for (c in listOf(node.text?.toString(), node.contentDescription?.toString())) {
            var s = normalize(c)
            if (s.endsWith(" button")) s = s.removeSuffix(" button").trim()
            if (s == target) return true
        }
        return false
    }

    private fun textOf(n: AccessibilityNodeInfo): String {
        val t = listOf(n.text?.toString(), n.contentDescription?.toString()).firstOrNull { !it.isNullOrBlank() }
        return INVISIBLE.replace(t.orEmpty(), "")
    }

    private fun topOf(n: AccessibilityNodeInfo): Int {
        val r = Rect()
        n.getBoundsInScreen(r)
        return r.top
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

    private fun bottomMost(root: AccessibilityNodeInfo, label: String, enabledOnly: Boolean): AccessibilityNodeInfo? {
        val nodes = NodeTools.collect(root, 1500) { it.isVisibleToUser && exactLabel(it, label) }
        val pool = nodes.filter { it.isClickable }.ifEmpty { nodes }.filter { !enabledOnly || enabledAndClickable(it) }
        return pool.maxByOrNull { topOf(it) }
    }

    /** Moc: nut tab "Daily" thap nhat = dau tin Quest Log moi nhat; chi doc cac node nam DUOI moc nay. */
    private fun anchorTop(root: AccessibilityNodeInfo): Int? =
        NodeTools.collect(root, 1500) { it.isVisibleToUser && exactLabel(it, "daily") }.map { topOf(it) }.maxOrNull()

    private fun radioSheetOpen(root: AccessibilityNodeInfo): Boolean =
        NodeTools.collect(root, 1500) { it.isVisibleToUser && (it.className?.toString() ?: "").contains("RadioButton") }.isNotEmpty()

    // ----------------- doc quest -----------------

    private fun readQuests(root: AccessibilityNodeInfo, anchor: Int): List<Quest> {
        val out = mutableListOf<Quest>()
        val nodes = NodeTools.collect(root, 1500) { it.isVisibleToUser && topOf(it) > anchor && textOf(it).contains("Reward:") }
        for (n in nodes) {
            val t = textOf(n)
            val head = QUEST_HEAD.find(t) ?: continue
            val index = head.groupValues[1].toIntOrNull() ?: continue
            val prefix = t.substring(0, head.range.first)
            val locked = prefix.contains("🔒") || prefix.lowercase().contains("lock")
            val rStart = t.indexOf("Reward:")
            val bar = t.indexOfFirst { it == '▰' || it == '▱' }
            val seg = if (bar > rStart) t.substring(rStart, bar) else t.substring(rStart)
            val rewards = REWARD.findAll(seg).map { it.groupValues[1].lowercase() }.toList()
            val high = rewards.any { it.contains("shard") || it.contains("crate") || it == "ws" || it == "azws" }
            val prog = PROGRESS.find(t.trim())
            val done = prog != null &&
                (prog.groupValues[1].replace(",", "").toLongOrNull() ?: 0L) >= (prog.groupValues[2].replace(",", "").toLongOrNull() ?: Long.MAX_VALUE)
            out.add(Quest(index, locked, done, high, seg.removePrefix("Reward:").trim()))
        }
        return out
    }

    // ----------------- 1) khoa quest thuong cao -----------------

    private suspend fun cancelDialog(service: AutoAccessibilityService) {
        val root = service.rootInActiveWindow ?: return
        if (radioSheetOpen(root)) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            delay(1_200)
        }
        val root2 = service.rootInActiveWindow ?: return
        val cancel = bottomMost(root2, "cancel", enabledOnly = true)
        if (cancel != null) {
            NodeTools.clickNode(service, cancel)
            delay(1_200)
        }
    }

    private suspend fun lockOne(service: AutoAccessibilityService, index: Int): Boolean {
        var root = service.rootInActiveWindow ?: return false
        val toggle = bottomMost(root, "lock unlock", enabledOnly = true) ?: return false
        if (!NodeTools.clickNode(service, toggle)) return false
        delay(1_800)

        root = service.rootInActiveWindow ?: return false
        val menu = NodeTools.collect(root, 1500) { it.isVisibleToUser && textOf(it).lowercase().contains("select a quest to lock") }
            .maxByOrNull { topOf(it) }
        if (menu == null || !NodeTools.clickNode(service, menu)) {
            cancelDialog(service)
            return false
        }
        delay(1_800)

        root = service.rootInActiveWindow ?: return false
        val questRe = Regex("Quest\\s+$index:")
        val radio = NodeTools.collect(root, 1500) {
            it.isVisibleToUser && textOf(it).contains("🔓") && questRe.containsMatchIn(textOf(it))
        }.firstOrNull()
        if (radio == null || !NodeTools.clickNode(service, radio)) {
            cancelDialog(service)
            return false
        }
        delay(1_800)

        root = service.rootInActiveWindow ?: return false
        var lockBtn = bottomMost(root, "lock", enabledOnly = true)
        if (lockBtn == null && radioSheetOpen(root)) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            delay(1_500)
            root = service.rootInActiveWindow ?: return false
            lockBtn = bottomMost(root, "lock", enabledOnly = true)
        }
        if (lockBtn == null || !NodeTools.clickNode(service, lockBtn)) {
            cancelDialog(service)
            return false
        }
        delay(2_200)

        root = service.rootInActiveWindow ?: return false
        val anchor = anchorTop(root) ?: return false
        return readQuests(root, anchor).firstOrNull { it.index == index }?.locked == true
    }

    private suspend fun lockHighValue(service: AutoAccessibilityService): List<String> {
        val done = mutableListOf<String>()
        for (i in 1..MAX_LOCKS_PER_RUN) {
            val root = service.rootInActiveWindow ?: return done
            val anchor = anchorTop(root) ?: return done
            val target = readQuests(root, anchor).firstOrNull { it.high && !it.locked && !it.done } ?: return done
            if (!lockOne(service, target.index)) {
                Log.w(TAG, "Khong khoa duoc quest ${target.index}")
                return done
            }
            done.add("Quest ${target.index} (${target.rewardText})")
            delay(1_000)
        }
        return done
    }

    // ----------------- 2) mua nang cap -----------------

    private fun readBalance(root: AccessibilityNodeInfo, anchor: Int): Int? {
        for (n in NodeTools.collect(root, 1500) { it.isVisibleToUser && topOf(it) > anchor && textOf(it).contains("Balance:") }) {
            val m = BALANCE.find(textOf(n)) ?: continue
            return m.groupValues[1].replace(",", "").toIntOrNull()
        }
        return null
    }

    private fun readUpgradeRows(root: AccessibilityNodeInfo, anchor: Int): List<UpRow> {
        val rows = NodeTools.collect(root, 1500) { it.isVisibleToUser && topOf(it) > anchor && textOf(it).contains("Level:") }
        val buttons = NodeTools.collect(root, 1500) {
            it.isVisibleToUser && (it.className?.toString() ?: "").contains("Button") && COST.matches(textOf(it))
        }
        val out = mutableListOf<UpRow>()
        for (r in rows) {
            val t = textOf(r)
            val name = UPGRADE_KNOWN.firstOrNull { t.startsWith(it) } ?: t.take(24)
            val level = LEVEL.find(t)?.groupValues?.get(1)?.toIntOrNull()
            val rt = topOf(r)
            val btn = buttons.firstOrNull { kotlin.math.abs(topOf(it) - rt) <= 14 }
            val cost = btn?.let { textOf(it).replace(",", "").toIntOrNull() }
            out.add(UpRow(name, level, cost, btn != null && enabledAndClickable(btn), btn))
        }
        return out
    }

    /** Mua tren trang dang hien: lap "mon re nhat mua duoc" cho toi khi het tien / het mon cho phep / loi. */
    private suspend fun buyOnPage(service: AutoAccessibilityService, bought: MutableList<String>, budget: Int): String? {
        var buys = 0
        while (buys < budget) {
            val root = service.rootInActiveWindow ?: return "mất màn hình"
            val anchor = anchorTop(root) ?: return "không thấy Quest Log"
            val balance = readBalance(root, anchor) ?: return "không đọc được số seal"
            val cand = readUpgradeRows(root, anchor)
                .filter { r ->
                    val c = r.cost
                    r.button != null && r.enabled && c != null && c <= balance && c <= UPGRADE_MAX_COST && r.name in UPGRADE_ALLOWED
                }
                .minByOrNull { it.cost ?: Int.MAX_VALUE } ?: return null
            val cost = cand.cost ?: return null
            val btn = cand.button ?: return null
            if (!NodeTools.clickNode(service, btn)) return "bấm nút mua thất bại"
            delay(2_200)
            val root2 = service.rootInActiveWindow ?: return "mất màn hình sau khi mua"
            val anchor2 = anchorTop(root2) ?: return "không thấy Quest Log sau khi mua"
            val after = readUpgradeRows(root2, anchor2).firstOrNull { it.name == cand.name }
            val before = cand.level
            val lv = after?.level
            if (before == null || lv == null || lv <= before) return "mua ${cand.name} nhưng level không tăng (có thể cần xác nhận) — dừng"
            bought.add("${cand.name} → Lv$lv (-$cost seal)")
            buys++
        }
        return null
    }

    /** Vao trang upgrades bang nut "seal", mua het cac trang, roi "back" ve Quest Log. */
    private suspend fun upgradeFlow(service: AutoAccessibilityService, bought: MutableList<String>, budget: Int): String? {
        var root = service.rootInActiveWindow ?: return "mất màn hình"
        val seal = bottomMost(root, "seal", enabledOnly = true) ?: return "không thấy nút seal"
        if (!NodeTools.clickNode(service, seal)) return "bấm nút seal thất bại"
        delay(2_200)
        root = service.rootInActiveWindow ?: return "mất màn hình"
        val isUpgrades = NodeTools.collect(root, 1500) { it.isVisibleToUser && textOf(it).contains("Upgrades") }.isNotEmpty()
        if (!isUpgrades) return "không mở được trang Upgrades"

        var err: String? = null
        var pages = 1
        val pageNode = NodeTools.collect(root, 1500) { it.isVisibleToUser && PAGE.matches(textOf(it)) }.firstOrNull()
        if (pageNode != null) pages = PAGE.find(textOf(pageNode))?.groupValues?.get(2)?.toIntOrNull() ?: 1
        for (p in 1..pages) {
            val before = bought.size
            err = buyOnPage(service, bought, budget - bought.size)
            if (err != null || bought.size >= budget) break
            if (p < pages) {
                val r2 = service.rootInActiveWindow ?: break
                val fwd = bottomMost(r2, "forward", enabledOnly = true) ?: break
                NodeTools.clickNode(service, fwd)
                delay(2_000)
            }
            Log.d(TAG, "trang $p: mua ${bought.size - before}")
        }
        // ve Quest Log
        for (i in 1..3) {
            val r3 = service.rootInActiveWindow ?: break
            if (NodeTools.collect(r3, 1500) { it.isVisibleToUser && textOf(it).contains("Quest Log") }.isNotEmpty()) break
            val back = bottomMost(r3, "back", enabledOnly = true) ?: break
            NodeTools.clickNode(service, back)
            delay(1_800)
        }
        return err
    }

    private suspend fun buyUpgrades(service: AutoAccessibilityService, bought: MutableList<String>): String? {
        var note = upgradeFlow(service, bought, UPGRADE_MAX_BUYS)
        // Daily Upgrades: vao tab Daily roi bam seal
        val root = service.rootInActiveWindow ?: return note
        val daily = bottomMost(root, "daily", enabledOnly = true)
        if (daily != null && bought.size < UPGRADE_MAX_BUYS) {
            NodeTools.clickNode(service, daily)
            delay(1_800)
            val n2 = upgradeFlow(service, bought, UPGRADE_MAX_BUYS)
            if (note == null) note = n2
            // tra ve tab Quests cho cac buoc sau
            val r4 = service.rootInActiveWindow
            val quests = r4?.let { bottomMost(it, "quests", enabledOnly = true) }
            if (quests != null) {
                NodeTools.clickNode(service, quests)
                delay(1_500)
            }
        }
        return note
    }

    // ----------------- diem vao -----------------

    suspend fun manage(service: AutoAccessibilityService, upgrades: Boolean): Report {
        val locked = mutableListOf<String>()
        val bought = mutableListOf<String>()
        var note: String? = null
        try {
            locked += lockHighValue(service)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Loi khoa quest: ${e.message}")
            note = "lỗi khóa quest: ${e.message}"
        }
        if (upgrades) {
            try {
                val n = buyUpgrades(service, bought)
                if (n != null) note = (note?.let { "$it; " } ?: "") + "nâng cấp: $n"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Loi mua nang cap: ${e.message}")
                note = (note?.let { "$it; " } ?: "") + "lỗi mua nâng cấp: ${e.message}"
            }
        }
        return Report(locked, bought, note)
    }
}
