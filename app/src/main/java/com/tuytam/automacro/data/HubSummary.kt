package com.tuytam.automacro.data

/**
 * Tom tat de nhin la hieu: moi o/the co 1 gia tri to + 1 mau trang thai.
 * Ham thuan (khong dung Android) nen kiem tra duoc rieng.
 *
 *  OK   xanh la  = xong / san sang / on
 *  INFO xanh duong = dang dem nguoc, binh thuong
 *  WARN cam      = nen de y (vd HuntBot dang nghi, gem thap)
 *  BAD  do       = can lam ngay (vd gem sap het) hoac loi
 *  IDLE xam      = chua co du lieu
 */
enum class Tone { OK, INFO, WARN, BAD, IDLE }

data class TileState(val value: String, val sub: String, val tone: Tone,
                     val icon: IconSpec = NO_ICON, val ringPercent: Int? = null)

data class LineState(val text: String, val tone: Tone)

object HubSummary {

    private const val GEM_BAD_PERCENT = 15
    private const val GEM_WARN_PERCENT = 40
    // OwO co dinh 24h/lan — dung de uoc luong % da troi qua cho vong tron o Daily (chi de ve, khong phai so chinh xac).
    private const val DAILY_COOLDOWN_SECONDS = 24 * 3600L

    fun gemTone(percent: Int?): Tone = when {
        percent == null -> Tone.IDLE
        percent <= GEM_BAD_PERCENT -> Tone.BAD
        percent <= GEM_WARN_PERCENT -> Tone.WARN
        else -> Tone.OK
    }

    // ---------- 6 o tom tat o dau man hinh ----------

    fun dailyTile(d: HubDaily?, elapsedSec: Long): TileState {
        if (d == null) return TileState("—", "Gõ owo daily 1 lần", Tone.IDLE)
        val left = d.secondsLeft ?: return TileState("?", "Chưa rõ giờ nhận", Tone.IDLE)
        val remain = left - elapsedSec
        val extras = mutableListOf<String>()
        if (d.streak != null) extras.add("🔥 ${d.streak}")
        if (d.lastReward != null) extras.add("+${HubFormat.num(d.lastReward)}")
        val icon = dailyTileIcon(d)
        return if (remain > 0) {
            val ring = (100 - (remain * 100 / DAILY_COOLDOWN_SECONDS).toInt()).coerceIn(0, 99)
            TileState(HubFormat.shortDuration(remain), extras.joinToString(" · ").ifEmpty { "Chờ tới giờ nhận" }, Tone.INFO, icon, ring)
        } else {
            TileState("Sẵn sàng!", "Gõ owo daily để nhận", Tone.OK, icon, 100)
        }
    }

    fun huntbotTile(h: HubHuntbot?, elapsedSec: Long): TileState {
        if (h == null) return TileState("—", "Gõ lệnh HuntBot 1 lần", Tone.IDLE)
        val extras = mutableListOf<String>()
        if (h.progressPct != null) extras.add("${HubFormat.trimNum(h.progressPct)}%")
        if (h.animalsCaptured != null) extras.add("${HubFormat.num(h.animalsCaptured)} pet")
        if (h.essence != null) extras.add("${HubFormat.num(h.essence)} essence")
        val info = extras.joinToString(" · ")
        val icon = huntbotTileIcon()
        val ring = h.progressPct?.toInt()?.coerceIn(0, 100)
        if (h.hunting != true) {
            return TileState("Đang nghỉ", info.ifEmpty { "Chưa gửi đi săn" }, Tone.WARN, icon, ring)
        }
        val left = h.secondsLeft
        return when {
            left == null -> TileState("Đang săn", h.timeRemainingText ?: "Chưa rõ giờ xong", Tone.INFO, icon, ring)
            left - elapsedSec > 0 -> TileState(HubFormat.shortDuration(left - elapsedSec), info.ifEmpty { "Đang săn" }, Tone.INFO, icon, ring)
            else -> TileState("Xong!", "Có thể thu về", Tone.OK, icon, 100)
        }
    }

    fun cowoncyTile(c: HubCowoncy?, ageSec: Long?): TileState {
        if (c == null || c.amount == null) return TileState("—", "Gõ owo money 1 lần", Tone.IDLE)
        val age = HubFormat.age(ageSec)
        return TileState(HubFormat.num(c.amount), if (age.isEmpty()) "cowoncy" else "cập nhật $age", Tone.INFO, cowoncyTileIcon(c))
    }

    fun questTile(q: HubQuest?, elapsedSec: Long): TileState {
        if (q == null) return TileState("—", "Gõ owo quest 1 lần", Tone.IDLE)
        val items = q.quests.orEmpty()
        val nextSec = q.nextQuestSeconds
        val next = when {
            nextSec == null -> null
            nextSec - elapsedSec > 0 -> "Mới sau ${HubFormat.shortDuration(nextSec - elapsedSec)}"
            else -> "Đã có quest mới"
        }
        val seals = if (q.seals != null) "Seals ${HubFormat.num(q.seals)}" else null
        val sub = listOfNotNull(next, seals).joinToString(" · ").ifEmpty { "Quest" }
        val icon = questTileIcon(q)
        return when {
            q.allDone == true -> TileState("Xong hết", sub, Tone.OK, icon, 100)
            items.isNotEmpty() -> {
                val done = items.count { it.done == true }
                val ring = (done * 100 / items.size)
                TileState("$done/${items.size} xong", sub, if (done == items.size) Tone.OK else Tone.INFO, icon, ring)
            }
            else -> TileState("Còn quest", sub, Tone.INFO, icon)
        }
    }

    fun gemsTile(g: HubGems?): TileState {
        if (g == null) return TileState("—", "Gõ owo hunt 1 lần", Tone.IDLE)
        val equipped = g.equipped.orEmpty()
        val lowest = equipped.minByOrNull { it.percent ?: 100 }
            ?: return TileState("—", "Chưa thấy gem đang dùng", Tone.IDLE)
        val more = if (equipped.size > 1) " · ${equipped.size} gem" else ""
        val icon = iconOf(lowest.emojiId, lowest.emojiAnimated, lowest.emoji, lowest.tier)
        return TileState("${lowest.percent ?: 0}%", "Slot ${lowest.slot ?: "?"} ${lowest.tier ?: ""}$more".trim(),
                         gemTone(lowest.percent), icon, lowest.percent)
    }

    fun zooTile(z: HubZoo?): TileState {
        if (z == null) return TileState("—", "Gõ owo zoo 1 lần", Tone.IDLE)
        return TileState(HubFormat.num(z.totalPets), "pet · ${HubFormat.num(z.zooPoints)} điểm", Tone.INFO, zooTileIcon(z))
    }

    // ---------- Dong tom tat cua tung the chi tiet ----------

    private fun noData() = LineState("Chưa có dữ liệu — cần gõ lệnh tương ứng 1 lần", Tone.IDLE)

    fun gemsLine(g: HubGems?): LineState {
        if (g == null) return noData()
        val equipped = g.equipped.orEmpty()
        val spareTotal = g.spare.orEmpty().sumOf { it.count ?: 0L }
        val lowest = equipped.minByOrNull { it.percent ?: 100 }
        val text = if (lowest == null) "Chưa thấy gem đang dùng · kho ${HubFormat.num(spareTotal)} viên"
        else "${equipped.size} đang dùng, thấp nhất ${lowest.percent ?: 0}% · kho ${HubFormat.num(spareTotal)} viên"
        return LineState(text, if (lowest == null) Tone.IDLE else gemTone(lowest.percent))
    }

    fun questLine(q: HubQuest?): LineState {
        if (q == null) return noData()
        val items = q.quests.orEmpty()
        val seals = if (q.seals != null) " · Seals ${HubFormat.num(q.seals)}" else ""
        return when {
            q.allDone == true -> LineState("Đã xong hết quest hôm nay$seals", Tone.OK)
            items.isNotEmpty() -> {
                val left = items.count { it.done != true }
                LineState("$left/${items.size} quest chưa xong$seals", if (left == 0) Tone.OK else Tone.INFO)
            }
            else -> LineState("Còn quest chưa xong$seals", Tone.INFO)
        }
    }

    fun teamLine(t: HubTeam?): LineState {
        val members = t?.members.orEmpty()
        if (members.isEmpty()) return noData()
        val names = members.mapNotNull { it.name }
        val shown = names.take(3).joinToString(", ") + if (names.size > 3) "…" else ""
        return LineState("${members.size} pet: $shown", Tone.INFO)
    }

    fun zooLine(z: HubZoo?): LineState {
        if (z == null) return noData()
        return LineState("${HubFormat.num(z.totalPets)} pet · ${HubFormat.num(z.zooPoints)} điểm", Tone.INFO)
    }

    fun weaponsLine(w: HubWeapons?): LineState {
        if (w == null || w.weapons.orEmpty().isEmpty()) return noData()
        return LineState("${HubFormat.num(w.count ?: w.weapons?.size)} cây đã ghi nhận", Tone.INFO)
    }

    fun inventoryLine(inv: HubInventory?): LineState {
        if (inv == null) return noData()
        return LineState("${HubFormat.num(inv.kinds)} loại · ${HubFormat.num(inv.totalItems)} món", Tone.INFO)
    }
}
