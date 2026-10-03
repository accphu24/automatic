package com.tuytam.automacro.data

/** Cach sap xep danh sach Pet Dex. DEFAULT = giu thu tu bot gui (loai dang co xep truoc). */
enum class DexSort(val label: String) {
    DEFAULT("Mặc định"), TOTAL("Tổng"), HP("HP"), ATT("ATT"), MAG("MAG"),
    PR("PR"), MR("MR"), WP("WP"), NAME("Tên A-Z")
}

/** Loc theo viec nguoi choi co loai do hay chua. */
enum class DexFilter(val label: String) { ALL("Tất cả"), OWNED("Đang có"), MISSING("Chưa có") }

/**
 * Loc + sap xep Pet Dex. Thuan Kotlin (khong dinh Android) de de kiem tra.
 * Muc dich: danh sach loai pet rat dai, can nhanh chong tim loai co chi so tot de giu lai.
 */
object HubDexQuery {

    /** Tong 6 chi so goc (thieu so nao tinh 0). */
    fun total(p: HubPetDexEntry): Long =
        (p.hp ?: 0L) + (p.att ?: 0L) + (p.pr ?: 0L) + (p.wp ?: 0L) + (p.mag ?: 0L) + (p.mr ?: 0L)

    /** Gia tri dung de xep. Loai thieu chi so tra -1 de luon nam cuoi. */
    private fun valueOf(p: HubPetDexEntry, sort: DexSort): Long = when (sort) {
        DexSort.TOTAL -> if (hasAnyStat(p)) total(p) else -1L
        DexSort.HP -> p.hp ?: -1L
        DexSort.ATT -> p.att ?: -1L
        DexSort.MAG -> p.mag ?: -1L
        DexSort.PR -> p.pr ?: -1L
        DexSort.MR -> p.mr ?: -1L
        DexSort.WP -> p.wp ?: -1L
        else -> 0L
    }

    private fun hasAnyStat(p: HubPetDexEntry): Boolean =
        p.hp != null || p.att != null || p.pr != null || p.wp != null || p.mag != null || p.mr != null

    /** Ten hang dung de loc: uu tien ban tieng Viet, khong co thi dung ten goc; rong -> null. */
    fun rankOf(p: HubPetDexEntry): String? =
        (p.rankVi ?: p.rank)?.trim()?.takeIf { it.isNotEmpty() }

    /** Cac hang co trong danh sach (khong trung, giu thu tu xuat hien). */
    fun ranks(list: List<HubPetDexEntry>): List<String> = list.mapNotNull { rankOf(it) }.distinct()

    fun apply(list: List<HubPetDexEntry>, sort: DexSort, filter: DexFilter, rank: String?): List<HubPetDexEntry> {
        val filtered = list.filter { p ->
            val okOwned = when (filter) {
                DexFilter.ALL -> true
                DexFilter.OWNED -> p.owned == true
                DexFilter.MISSING -> p.owned != true
            }
            val okRank = rank == null || rankOf(p) == rank
            okOwned && okRank
        }
        return when (sort) {
            DexSort.DEFAULT -> filtered
            DexSort.NAME -> filtered.sortedBy { (it.name ?: "").lowercase() }
            else -> filtered.sortedWith(
                compareByDescending<HubPetDexEntry> { valueOf(it, sort) }
                    .thenByDescending { it.owned == true }
                    .thenBy { (it.name ?: "").lowercase() }
            )
        }
    }

    /** Dong 6 chi so. Khi dang xep theo 1 chi so thi dua chi so do len dau va danh dau ▸. */
    fun statsLine(p: HubPetDexEntry, sort: DexSort): String {
        val stats = listOf("HP" to p.hp, "ATT" to p.att, "PR" to p.pr, "WP" to p.wp, "MAG" to p.mag, "MR" to p.mr)
        val focus = when (sort) {
            DexSort.HP -> "HP"
            DexSort.ATT -> "ATT"
            DexSort.PR -> "PR"
            DexSort.WP -> "WP"
            DexSort.MAG -> "MAG"
            DexSort.MR -> "MR"
            else -> null
        }
        val ordered = if (focus == null) stats else stats.sortedBy { if (it.first == focus) 0 else 1 }
        val text = ordered.joinToString(" · ") { (name, v) ->
            (if (name == focus) "▸ " else "") + "$name ${v ?: "?"}"
        }
        return if (sort == DexSort.TOTAL) "$text  (tổng ${total(p)})" else text
    }
}
