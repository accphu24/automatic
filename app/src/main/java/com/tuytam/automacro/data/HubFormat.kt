package com.tuytam.automacro.data

import java.util.Locale

/**
 * Bien du lieu /hub thanh chu de hien tren man hinh. Toan bo la ham thuan
 * (khong dung gi cua Android) nen de kiem tra rieng va khong bi loi giao dien.
 */
object HubFormat {

    private const val LOW_GEM_PERCENT = 15

    // ---------- Ham nho dung chung ----------

    /** 11272674 -> "11,272,674"; null -> "?" */
    fun num(v: Number?): String =
        if (v == null) "?" else String.format(Locale.US, "%,d", v.toLong())

    /** 88.50 -> "88.5", 90.0 -> "90" */
    fun trimNum(v: Double?): String {
        if (v == null) return "?"
        return String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
    }

    /** "cap nhat X truoc" — null thi tra chuoi rong */
    fun age(seconds: Long?): String {
        if (seconds == null) return ""
        val s = maxOf(seconds, 0L)
        return when {
            s < 60 -> "vừa xong"
            s < 3600 -> "${s / 60} phút trước"
            s < 86400 -> {
                val h = s / 3600
                val m = (s % 3600) / 60
                if (m == 0L) "$h giờ trước" else "$h giờ $m phút trước"
            }
            else -> "${s / 86400} ngày trước"
        }
    }

    /** 3725 -> "1 giờ 02 phút 05 giây" */
    fun duration(seconds: Long): String {
        val s = maxOf(seconds, 0L)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return when {
            h > 0 -> String.format(Locale.US, "%d giờ %02d phút %02d giây", h, m, sec)
            m > 0 -> String.format(Locale.US, "%d phút %02d giây", m, sec)
            else -> "$sec giây"
        }
    }

    /** Ban ngan cho o tom tat: 3725 -> "1g 02p", 125 -> "2p 05s", 40 -> "40s" */
    fun shortDuration(seconds: Long): String {
        val s = maxOf(seconds, 0L)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return when {
            h > 0 -> String.format(Locale.US, "%dg %02dp", h, m)
            m > 0 -> String.format(Locale.US, "%dp %02ds", m, sec)
            else -> "${sec}s"
        }
    }

    /** 63 -> "▰▰▰▰▰▰▱▱▱▱" */
    fun bar(percent: Int?, width: Int = 10): String {
        val p = (percent ?: 0).coerceIn(0, 100)
        val filled = Math.round(p / 100.0 * width).toInt().coerceIn(0, width)
        return "▰".repeat(filled) + "▱".repeat(width - filled)
    }

    /** Hien thay cho noi dung 1 muc khi muc do khong doc duoc (cac muc khac van binh thuong). */
    fun sectionError(reason: String): String = "⚠️ Không đọc được mục này:\n$reason"

    private fun noData(hint: String?): String =
        "Chưa có dữ liệu — bot chưa thấy tin nhắn nào cho mục này." +
            (if (hint.isNullOrBlank()) "" else "\n$hint")

    // ---------- Tuoi du lieu cua tung muc ----------

    /** Gem gom nhieu slot: lay slot moi nhat (tuoi nho nhat) lam tuoi cua ca muc. */
    fun gemsAgeSeconds(g: HubGems?): Long? = g?.equipped.orEmpty().mapNotNull { it.ageSeconds }.minOrNull()

    // ---------- Tung muc ----------

    /** elapsedSec = so giay tu luc app lay du lieu (de dem nguoc chay muot giua 2 lan lam moi). */
    fun daily(d: HubDaily?, elapsedSec: Long): String {
        if (d == null) return noData("Gõ owo daily 1 lần để bot ghi nhận.")
        val sb = StringBuilder()
        val left = d.secondsLeft
        if (left == null) {
            sb.append("Chưa rõ giờ nhận kế tiếp")
        } else {
            val remain = left - elapsedSec
            if (remain > 0) {
                sb.append("⏳ Còn ${duration(remain)} nữa mới nhận được")
            } else {
                sb.append("✅ Daily đã sẵn sàng — gõ owo daily để nhận")
            }
        }
        val extras = mutableListOf<String>()
        if (d.streak != null) extras.add("🔥 Streak: ${num(d.streak)} ngày")
        if (d.lastReward != null) extras.add("Lần nhận gần nhất: ${num(d.lastReward)} cowoncy")
        if (extras.isNotEmpty()) sb.append("\n").append(extras.joinToString(" · "))
        return sb.toString()
    }

    fun cowoncy(c: HubCowoncy?): String {
        if (c == null || c.amount == null) return noData("Gõ owo money để bot ghi nhận.")
        return "${num(c.amount)} cowoncy"
    }

    fun gems(g: HubGems?): String {
        if (g == null) return noData("Gõ owo hunt để ghi nhận gem đang dùng, owo inventory để ghi nhận kho.")
        val sb = StringBuilder()
        val equipped = g.equipped.orEmpty()
        if (equipped.isEmpty()) {
            sb.append("Chưa thấy gem đang trang bị — gõ owo hunt 1 lần để bot ghi nhận.")
        } else {
            sb.append("Đang dùng:")
            for (e in equipped) {
                val warn = if ((e.percent ?: 100) <= LOW_GEM_PERCENT) " ⚠️ sắp hết" else ""
                sb.append("\n• Slot ${e.slot ?: "?"} — ${e.tier ?: "?"}$warn")
                sb.append("\n   ${bar(e.percent)} ${e.current ?: "?"}/${e.max ?: "?"} (${e.percent ?: 0}%)")
            }
        }
        sb.append("\n\nDự phòng trong kho:")
        val spare = g.spare.orEmpty()
        if (spare.isEmpty()) {
            sb.append("\n(không có, hoặc bot chưa thấy kho)")
        } else {
            for ((slot, list) in spare.groupBy { it.slot ?: "?" }) {
                sb.append("\n• Slot $slot: ")
                sb.append(list.joinToString(" · ") { "${it.tier ?: "?"} ×${num(it.count)} (${it.code ?: "?"})" })
            }
        }
        return sb.toString()
    }

    /** elapsedSec = so giay tu luc app lay du lieu, de dem nguoc chay muot giua 2 lan lam moi. */
    fun huntbot(h: HubHuntbot?, elapsedSec: Long): String {
        if (h == null) return noData("Gõ lệnh HuntBot 1 lần để bot ghi nhận.")
        val sb = StringBuilder()
        if (h.hunting == true) {
            sb.append("Đang đi săn")
            if (h.progressPct != null) sb.append(" · ${trimNum(h.progressPct)}%")
            val left = h.secondsLeft
            if (left != null) {
                val remain = left - elapsedSec
                if (remain > 0) {
                    sb.append("\n⏳ Còn ${duration(remain)}")
                } else {
                    sb.append("\n✅ Đã tới giờ xong — HuntBot có thể đã về (tính theo lần bot thấy gần nhất)")
                }
            } else if (!h.timeRemainingText.isNullOrBlank()) {
                sb.append("\n⏳ Còn khoảng ${h.timeRemainingText}")
            } else {
                sb.append("\n⏳ Chưa rõ giờ xong")
            }
        } else {
            sb.append("Không đang đi săn (theo lần bot thấy gần nhất)")
        }
        val extras = mutableListOf<String>()
        if (h.animalsCaptured != null) extras.add("Đã bắt: ${num(h.animalsCaptured)} pet")
        if (h.essence != null) extras.add("Essence: ${num(h.essence)}")
        if (extras.isNotEmpty()) sb.append("\n").append(extras.joinToString(" · "))
        return sb.toString()
    }

    fun quest(q: HubQuest?, elapsedSec: Long): String {
        if (q == null) return noData(null)
        val items = q.quests.orEmpty()
        val header = when {
            q.allDone == true -> "✅ Đã xong hết quest"
            items.isNotEmpty() -> "📋 Còn ${items.count { it.done != true }}/${items.size} quest chưa xong"
            else -> "📋 Còn quest chưa xong"
        }
        val sb = StringBuilder(header)
        if (q.seals != null) sb.append(" · Seals: ${num(q.seals)}")
        val nextSec = q.nextQuestSeconds
        if (nextSec != null) {
            val remain = nextSec - elapsedSec
            if (remain > 0) {
                sb.append("\n⏰ Quest mới sau ${duration(remain)}")
            } else {
                sb.append("\n⏰ Đã có quest mới (theo mốc bot ghi nhận)")
            }
        } else if (!q.nextQuest.isNullOrBlank()) {
            sb.append("\nKế tiếp: ${q.nextQuest}")
        }
        for (qi in items) {
            val mark = if (qi.done == true) "✅" else "•"
            val rarity = if (qi.rarity.isNullOrBlank()) "" else " (${qi.rarity})"
            sb.append("\n\n$mark ${qi.index ?: "?"}. ${qi.title ?: "?"}$rarity")
            if (qi.current != null && qi.max != null && qi.max > 0) {
                val pct = (qi.current * 100 / qi.max).toInt().coerceIn(0, 100)
                sb.append("\n   ${bar(pct)} ${num(qi.current)}/${num(qi.max)} ($pct%)")
            }
            if (!qi.description.isNullOrBlank()) sb.append("\n   ${qi.description}")
            val rewards = qi.rewards.orEmpty()
            if (rewards.isNotEmpty()) {
                sb.append("\n   Thưởng: ").append(rewards.joinToString(" · ") { r -> "${r.item ?: "?"} +${num(r.amount)}" })
            }
        }
        return sb.toString()
    }

    fun team(t: HubTeam?): String {
        val members = t?.members.orEmpty()
        if (members.isEmpty()) return noData(null)
        return members.joinToString("\n") { m ->
            val stats = "HP${m.hp ?: "?"} WP${m.wp ?: "?"} ATT${m.att ?: "?"} MAG${m.mag ?: "?"}"
            val weapon = if (m.weaponId.isNullOrBlank()) "" else "\n   ${m.weaponId} · ${trimNum(m.quality)}%"
            "[${m.pos ?: "?"}] ${m.name ?: "?"} Lv${m.level ?: "?"} — $stats$weapon"
        }
    }

    fun battles(b: HubBattles?): String {
        if (b == null) return noData(null)
        val sb = StringBuilder()
        sb.append("${b.sampleSize ?: 0} trận gần nhất: ${b.wins ?: 0} thắng · ${b.losses ?: 0} thua")
        if (b.currentStreak != null) sb.append(" · Streak: ${b.currentStreak}")
        for (r in b.recent.orEmpty()) {
            val icon = if (r.result == "won") "✅" else "❌"
            sb.append("\n$icon ${r.turns ?: "?"} lượt · +${num(r.xp)} xp · streak ${r.streak ?: "?"}")
        }
        return sb.toString()
    }

    fun zoo(z: HubZoo?, expanded: Boolean): String {
        if (z == null) return noData("Gõ owo zoo để bot ghi nhận pet.")
        val sb = StringBuilder()
        sb.append("Zoo Points: ${num(z.zooPoints)} · Tổng ${num(z.totalPets)} pet")
        for ((tier, t) in z.byTier.orEmpty()) {
            if ((t.total ?: 0L) > 0L) sb.append("\n• $tier: ${num(t.total)} con · ${num(t.species)} loài")
        }
        if (expanded) {
            if (!z.breakdownRaw.isNullOrBlank()) sb.append("\n\nChi tiết: ${z.breakdownRaw}")
            sb.append("\n")
            for ((tier, list) in z.pets.orEmpty().groupBy { it.tier ?: "?" }) {
                sb.append("\n$tier: ")
                sb.append(list.joinToString(", ") { "${it.name ?: "?"} ×${num(it.count)}" })
            }
        } else if (z.pets.orEmpty().isNotEmpty()) {
            sb.append("\n\n👆 Chạm để xem từng loài")
        }
        return sb.toString()
    }

    fun inventory(inv: HubInventory?, expanded: Boolean): String {
        if (inv == null) return noData("Gõ owo inventory để bot ghi nhận kho đồ.")
        val sb = StringBuilder("${num(inv.kinds)} loại · ${num(inv.totalItems)} món")
        val items = inv.items.orEmpty()
        if (expanded) {
            sb.append("\n")
            for (i in items.sortedBy { it.code ?: "" }) {
                sb.append("\n${i.code ?: "?"}  ${i.name ?: "?"} ×${num(i.count)}")
            }
        } else if (items.isNotEmpty()) {
            sb.append("\n\n👆 Chạm để xem toàn bộ")
        }
        return sb.toString()
    }

    fun weapons(w: HubWeapons?, expanded: Boolean): String {
        val list = w?.weapons.orEmpty()
        if (w == null || list.isEmpty()) return noData("Chỉ ghi nhận những cây bạn từng xem bằng owo weapon <ID>.")
        val sb = StringBuilder("Đã ghi nhận ${num(w.count ?: list.size)} cây (chỉ tính cây bạn từng xem)")
        val shown = if (expanded) list else list.take(5)
        for (x in shown) {
            sb.append("\n• ${x.id ?: "?"} · ${x.typeName ?: "?"} ${trimNum(x.quality)}%")
            if (!x.passiveName.isNullOrBlank()) sb.append(" · ${x.passiveName}")
            if (expanded && !x.passiveDesc.isNullOrBlank()) sb.append("\n   ${x.passiveDesc}")
        }
        if (!expanded && list.size > shown.size) sb.append("\n\n👆 Chạm để xem hết ${list.size} cây")
        return sb.toString()
    }
}
